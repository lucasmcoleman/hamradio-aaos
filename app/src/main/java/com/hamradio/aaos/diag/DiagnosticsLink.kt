package com.hamradio.aaos.diag

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.Process
import android.util.Log
import com.hamradio.aaos.radio.transport.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.net.ssl.SSLSocket

private const val TAG = "DiagLink"
// The truck drops inbound connections, so the app dials out to this relay on the home LAN
// (the chattr box). Overridable through radio_prefs "diag_relay_host" for emulator tests.
private const val DEFAULT_RELAY_HOST = "192.168.0.33"
const val RELAY_TRUCK_PORT = 47391
private const val AUTO_OFF_MS = 30 * 60_000L

/**
 * Diagnostics link (job #875): while switched on, the app dials out to a relay on the home
 * LAN (the truck drops inbound connections) and runs TLS in server mode over that socket,
 * completing a handshake only with Lucas's PC (pinned client certificate, see [DiagTls]), then waits for an
 * Allow tap on the head unit before running the fixed [DiagCommand] set against the
 * radio saved in the app. One client at a time; turns itself off after 30 minutes.
 * Session lifecycle and limits live in [DiagServer] (codex review #941).
 */
@SuppressLint("MissingPermission")
object DiagnosticsLink {

    sealed class Status {
        object Off : Status()
        data class Listening(val addresses: List<String>, val fingerprint: String, val clientConnected: Boolean) : Status()
        data class AwaitingApproval(val peer: String, val fingerprint: String) : Status()
        data class Failed(val reason: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var current: DiagServer? = null
    private var pendingApproval: CompletableDeferred<Boolean>? = null
    private var fingerprint = ""
    private var relayHost = DEFAULT_RELAY_HOST

    @Synchronized
    fun start(context: Context) {
        if (current?.active == true) return
        val app = context.applicationContext
        try {
            val tls = DiagTls.serverContext(app)
            fingerprint = tls.fingerprint
            relayHost = app.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE)
                .getString("diag_relay_host", null) ?: DEFAULT_RELAY_HOST
            val host = relayHost
            val source = DialOutSource(host, RELAY_TRUCK_PORT, wrap = { raw ->
                (tls.context.socketFactory.createSocket(raw, host, RELAY_TRUCK_PORT, true) as SSLSocket).apply {
                    useClientMode = false
                    needClientAuth = true
                }
            })
            lateinit var server: DiagServer
            server = DiagServer(
                source = source,
                peerFilter = { true },   // we chose the relay; the pinned client cert is the check
                approve = { askApproval(server, "Claude's PC (via relay $host)") },
                execute = { cmd -> run(app, cmd) },
                banner = "hamradio diagnostics link (${Build.MODEL}). type help.",
                autoOffMs = AUTO_OFF_MS,
                handshake = { (it as SSLSocket).startHandshake() },
                onChange = { s -> publish(s) },
            )
            current = server.start()
            publish(server)
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            _status.value = Status.Failed("could not start: ${describe(e)}")
        }
    }

    @Synchronized
    fun stop() {
        current?.stop()
        current = null
        pendingApproval?.complete(false)
        pendingApproval = null
        _status.value = Status.Off
    }

    /** The head unit's answer to the Allow prompt for the connection now waiting. */
    @Synchronized
    fun answer(allow: Boolean) {
        pendingApproval?.complete(allow)
        pendingApproval = null
    }

    private suspend fun askApproval(server: DiagServer, peer: String): Boolean {
        val decision = CompletableDeferred<Boolean>()
        synchronized(this) {
            if (current !== server || !server.active) return false
            pendingApproval?.complete(false)
            pendingApproval = decision
            _status.value = Status.AwaitingApproval(peer, fingerprint)
        }
        return try {
            decision.await()
        } finally {
            synchronized(this) {
                if (pendingApproval === decision) pendingApproval = null
                if (current === server) publish(server)
            }
        }
    }

    @Synchronized
    private fun publish(server: DiagServer) {
        if (current !== server) return   // an old session never changes what the screen shows
        if (!server.active) {
            current = null
            pendingApproval?.complete(false)
            pendingApproval = null
            _status.value = Status.Off
            return
        }
        if (pendingApproval == null) {
            _status.value = Status.Listening(listOf("relay $relayHost:$RELAY_TRUCK_PORT"), fingerprint, server.clientConnected)
        }
    }

    private fun savedRadio(context: Context): String? =
        context.getSharedPreferences("radio_prefs", Context.MODE_PRIVATE).getString("device_address", null)?.uppercase()

    private suspend fun run(context: Context, cmd: DiagCommand): String {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return when (cmd) {
            DiagCommand.Help -> DiagCommand.USAGE
            DiagCommand.Off -> "turning off"
            is DiagCommand.Invalid -> "refused: ${cmd.reason}"
            DiagCommand.Info -> info(context, adapter)
            is DiagCommand.Prop -> "${cmd.key}=${systemProperty(cmd.key)}"
            is DiagCommand.Log -> appLog(cmd.lines)
            else -> {
                adapter ?: return "no Bluetooth adapter"
                DiagCommand.targetRefusal(cmd, savedRadio(context))?.let { return it }
                when (cmd) {
                    DiagCommand.Bonded -> adapter.bondedDevices.joinToString("\n") { describeDevice(it) }
                        .ifEmpty { "no bonded devices" }
                    DiagCommand.Profiles -> profiles(context, adapter)
                    DiagCommand.Scan -> scan(context, adapter, savedRadio(context))
                    is DiagCommand.Uuids -> uuids(context, adapter.getRemoteDevice(cmd.address))
                    is DiagCommand.Bond -> bond(context, adapter.getRemoteDevice(cmd.address))
                    is DiagCommand.Gatt -> gatt(context, adapter.getRemoteDevice(cmd.address))
                    is DiagCommand.Rfcomm -> rfcomm(adapter, cmd)
                    else -> "unhandled"
                }
            }
        }
    }

    private fun info(context: Context, adapter: BluetoothAdapter?): String {
        val pm = context.packageManager
        val version = runCatching { pm.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val perms = listOf(
            "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN",
            "android.permission.RECORD_AUDIO", "android.permission.ACCESS_FINE_LOCATION",
        ).joinToString("\n") { p ->
            val granted = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            "  ${p.substringAfterLast('.')}: ${if (granted) "granted" else "DENIED"}"
        }
        return buildString {
            appendLine("model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            appendLine("fingerprint: ${Build.FINGERPRINT}")
            appendLine("app: ${context.packageName} $version")
            appendLine("bluetooth: ${adapter?.let { "state=${it.state} name=${runCatching { it.name }.getOrNull()}" } ?: "none"}")
            append("permissions:\n$perms")
        }
    }

    private fun systemProperty(key: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrElse { "<unreadable: ${describe(it)}>" }

    private fun appLog(lines: Int): String = runCatching {
        val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", lines.toString(),
            "--pid=${Process.myPid()}").redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().readText().ifBlank { "log empty" }
    }.getOrElse { "log unavailable: ${describe(it)}" }

    private fun describeDevice(d: BluetoothDevice): String {
        val type = when (d.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "le"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
            else -> "unknown"
        }
        val cls = runCatching { d.bluetoothClass?.toString() }.getOrNull() ?: "?"
        return "${d.address} '${runCatching { d.name }.getOrNull()}' type=$type bond=${d.bondState} class=$cls"
    }

    private suspend fun profiles(context: Context, adapter: BluetoothAdapter): String {
        // Public ids plus the automotive client-side ones (A2DP_SINK=11, HEADSET_CLIENT=16, PBAP_CLIENT=17, MAP_CLIENT=18).
        val ids = linkedMapOf(
            "HEADSET" to BluetoothProfile.HEADSET, "A2DP" to BluetoothProfile.A2DP,
            "A2DP_SINK" to 11, "HEADSET_CLIENT" to 16, "PBAP_CLIENT" to 17, "MAP_CLIENT" to 18,
            "GATT" to BluetoothProfile.GATT, "HID_DEVICE" to BluetoothProfile.HID_DEVICE,
        )
        val lines = mutableListOf<String>()
        for ((name, id) in ids) {
            val state = runCatching { adapter.getProfileConnectionState(id) }.getOrNull()
            val proxy = if (id == BluetoothProfile.GATT) "n/a" else proxyAvailable(context, adapter, id)
            lines += "$name($id): connectionState=$state proxy=$proxy"
        }
        return lines.joinToString("\n")
    }

    private suspend fun proxyAvailable(context: Context, adapter: BluetoothAdapter, id: Int): String {
        val result = CompletableDeferred<BluetoothProfile>()
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { result.complete(proxy) }
            override fun onServiceDisconnected(profile: Int) {}
        }
        val started = runCatching { adapter.getProfileProxy(context, listener, id) }.getOrDefault(false)
        if (!started) return "unsupported"
        val proxy = withTimeoutOrNull(3_000) { result.await() } ?: return "no answer"
        val devices = runCatching { proxy.connectedDevices.joinToString { it.address } }.getOrDefault("?")
        runCatching { adapter.closeProfileProxy(id, proxy) }
        return "yes connected=[$devices]"
    }

    private suspend fun uuids(context: Context, device: BluetoothDevice): String {
        val cached = device.uuids?.joinToString("\n") { "  $it" } ?: "  none"
        val fresh = CompletableDeferred<Array<ParcelUuid>?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != device.address) return
                @Suppress("DEPRECATION")
                val arr = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                fresh.complete(arr?.filterIsInstance<ParcelUuid>()?.toTypedArray())
            }
        }
        register(context, receiver, IntentFilter(BluetoothDevice.ACTION_UUID))
        return try {
            val started = device.fetchUuidsWithSdp()
            val got = withTimeoutOrNull(15_000) { fresh.await() }
            buildString {
                appendLine("device: ${describeDevice(device)}")
                appendLine("cached uuids:\n$cached")
                append("sdp fetch started=$started -> ")
                append(when {
                    got == null && !fresh.isCompleted -> "no answer in 15 s"
                    got.isNullOrEmpty() -> "empty"
                    else -> "\n" + got.joinToString("\n") { "  $it" }
                })
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * Classic discovery. The truck had never discovered the radio (type=unknown, no class),
     * and Android can refuse classic sockets to a device it only knows from BLE. Reports the
     * saved radio only; other devices are counted, not listed.
     */
    private suspend fun scan(context: Context, adapter: BluetoothAdapter, radio: String?): String {
        val seen = mutableSetOf<String>()
        val radioHits = mutableListOf<String>()
        val finished = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        synchronized(seen) {
                            seen += d.address
                            if (d.address.equals(radio, ignoreCase = true)) {
                                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                                radioHits += "found: ${describeDevice(d)} rssi=$rssi"
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finished.complete(Unit)
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        register(context, receiver, filter)
        return try {
            adapter.cancelDiscovery()
            val started = adapter.startDiscovery()
            if (started) withTimeoutOrNull(12_000) { finished.await() }
            synchronized(seen) {
                buildString {
                    appendLine("discovery started=$started, ${seen.size} device(s) answered")
                    if (radio == null) appendLine("no radio saved in the app")
                    else if (radioHits.isEmpty()) appendLine("saved radio $radio did not answer classic discovery")
                    else radioHits.distinct().forEach { appendLine(it) }
                    append("now: ${radio?.let { describeDevice(adapter.getRemoteDevice(it)) } ?: "-"}")
                }
            }
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private suspend fun bond(context: Context, device: BluetoothDevice): String {
        val events = Channel<String>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                val reason = intent.getIntExtra("android.bluetooth.device.extra.REASON", -1)
                events.trySend("bond $prev -> $state (reason $reason)")
                if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) events.close()
            }
        }
        register(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        return try {
            val before = device.bondState
            val started = device.createBond()
            val log = mutableListOf("bondState before=$before createBond=$started")
            if (started) {
                withTimeoutOrNull(30_000) { for (e in events) log += e } ?: log.add("timed out after 30 s")
            }
            log += "bondState after=${device.bondState}"
            log.joinToString("\n")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private suspend fun gatt(context: Context, device: BluetoothDevice): String {
        val done = CompletableDeferred<String>()
        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else done.complete("disconnected (status $status)")
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                done.complete("services (status $status):\n" + g.services.joinToString("\n") { s ->
                    "  ${s.uuid}\n" + s.characteristics.joinToString("\n") { "    ${it.uuid} props=0x%02X".format(it.properties) }
                })
            }
        }, BluetoothDevice.TRANSPORT_LE) ?: return "connectGatt returned null"
        return try {
            withTimeoutOrNull(20_000) { done.await() } ?: "no answer in 20 s"
        } finally {
            runCatching { gatt.disconnect(); gatt.close() }
        }
    }

    private suspend fun rfcomm(adapter: BluetoothAdapter, cmd: DiagCommand.Rfcomm): String {
        adapter.cancelDiscovery()
        val device = adapter.getRemoteDevice(cmd.address)
        val label = cmd.uuid?.toString() ?: "channel ${cmd.channel}"
        val socket: BluetoothSocket = try {
            when {
                cmd.uuid != null && cmd.secure -> device.createRfcommSocketToServiceRecord(cmd.uuid)
                cmd.uuid != null -> device.createInsecureRfcommSocketToServiceRecord(cmd.uuid)
                else -> {
                    val name = if (cmd.secure) "createRfcommSocket" else "createInsecureRfcommSocket"
                    device.javaClass.getMethod(name, Int::class.javaPrimitiveType)
                        .invoke(device, cmd.channel) as BluetoothSocket
                }
            }
        } catch (e: Exception) {
            return "$label ${if (cmd.secure) "secure" else "insecure"}: create failed: ${describe(e)}"
        }
        val started = System.currentTimeMillis()
        val mode = if (cmd.secure) "secure" else "insecure"
        // connect() blocks and ignores cancellation; the watchdog closes the socket on its
        // 20 s deadline or when the session is stopped (cancellation runs its finally).
        return try {
            coroutineScope {
                val watchdog = launch {
                    try { delay(20_000) } finally { runCatching { socket.close() } }
                }
                try {
                    runInterruptible(Dispatchers.IO) { socket.connect() }
                    val took = System.currentTimeMillis() - started
                    val bytes = readFor(socket, cmd.readMs)
                    "$label $mode: CONNECTED in ${took} ms; read ${bytes.size} bytes in ${cmd.readMs} ms" +
                        if (bytes.isEmpty()) "" else ": " + bytes.take(64).joinToString(" ") { "%02x".format(it) }
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "$label $mode: connect failed after ${System.currentTimeMillis() - started} ms: ${describe(e)}"
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun readFor(socket: BluetoothSocket, ms: Long): ByteArray {
        if (ms <= 0) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        val input = socket.inputStream
        val deadline = System.currentTimeMillis() + ms
        val buf = ByteArray(512)
        while (System.currentTimeMillis() < deadline && out.size() < 4096) {
            val n = runCatching { input.available() }.getOrDefault(0)
            if (n > 0) out.write(buf, 0, input.read(buf, 0, minOf(n, buf.size))) else delay(50)
        }
        return out.toByteArray()
    }

    private fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }
}
