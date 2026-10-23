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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "DiagLink"
const val DIAG_PORT = 47390
private const val ALLOWED_HOST = "192.168.0.30"   // Lucas's PC on the home LAN
private const val AUTO_OFF_MS = 30 * 60_000L
private const val END = "<<END>>"

/**
 * Diagnostics link (job #875): while switched on, accepts one TCP client at a time from
 * Lucas's PC (or loopback via adb forward) and runs the fixed [DiagCommand] set against
 * the Bluetooth stack from inside the app. Turns itself off after 30 minutes.
 */
@SuppressLint("MissingPermission")
object DiagnosticsLink {

    sealed class Status {
        object Off : Status()
        data class Listening(val addresses: List<String>, val clients: Int) : Status()
        data class Failed(val reason: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = AtomicReference<ServerSocket?>(null)
    private var job: Job? = null
    private var clients = 0

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            val socket = try {
                ServerSocket(DIAG_PORT)
            } catch (e: Exception) {
                _status.value = Status.Failed("could not listen on $DIAG_PORT: ${describe(e)}")
                return@launch
            }
            server.set(socket)
            clients = 0
            publish()
            val timer = launch { delay(AUTO_OFF_MS); stop() }
            try {
                while (true) {
                    val client = socket.accept()
                    if (!DiagCommand.isAllowedPeer(client.inetAddress, ALLOWED_HOST)) {
                        Log.w(TAG, "refused ${client.inetAddress.hostAddress}")
                        client.close()
                        continue
                    }
                    launch { serve(app, client) }
                }
            } catch (_: Exception) {
                // closed by stop()
            } finally {
                timer.cancel()
                server.getAndSet(null)?.close()
                if (_status.value !is Status.Failed) _status.value = Status.Off
            }
        }
    }

    @Synchronized
    fun stop() {
        server.getAndSet(null)?.let { runCatching { it.close() } }
        job?.cancel()
        job = null
        _status.value = Status.Off
    }

    private fun publish() {
        if (server.get() != null) _status.value = Status.Listening(localAddresses(), clients)
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { "${it.hostAddress}:$DIAG_PORT" }
    }.getOrDefault(emptyList())

    private suspend fun serve(context: Context, client: Socket) {
        synchronized(this) { clients++ }
        publish()
        try {
            client.use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val out = PrintWriter(sock.getOutputStream(), true)
                out.println("hamradio diagnostics link (${Build.MODEL}). type help.")
                out.println(END)
                while (true) {
                    val line = reader.readLine() ?: break
                    val cmd = DiagCommand.parse(line)
                    val text = runCatching { run(context, cmd) }
                        .getOrElse { "error: ${describe(it)}" }
                    out.println(text)
                    out.println(END)
                    if (cmd is DiagCommand.Off) { stop(); break }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "client ended: ${describe(e)}")
        } finally {
            synchronized(this) { clients-- }
            publish()
        }
    }

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
                when (cmd) {
                    DiagCommand.Bonded -> adapter.bondedDevices.joinToString("\n") { describeDevice(it) }
                        .ifEmpty { "no bonded devices" }
                    DiagCommand.Profiles -> profiles(context, adapter)
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
        // connect() blocks and ignores coroutine cancellation; closing the socket interrupts it.
        val watchdog = scope.launch { delay(20_000); runCatching { socket.close() } }
        return try {
            socket.connect()
            val took = System.currentTimeMillis() - started
            val bytes = readFor(socket, cmd.readMs)
            "$label ${if (cmd.secure) "secure" else "insecure"}: CONNECTED in ${took} ms; " +
                "read ${bytes.size} bytes in ${cmd.readMs} ms" +
                if (bytes.isEmpty()) "" else ": " + bytes.take(64).joinToString(" ") { "%02x".format(it) }
        } catch (e: Exception) {
            "$label ${if (cmd.secure) "secure" else "insecure"}: connect failed after " +
                "${System.currentTimeMillis() - started} ms: ${describe(e)}"
        } finally {
            watchdog.cancel()
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
