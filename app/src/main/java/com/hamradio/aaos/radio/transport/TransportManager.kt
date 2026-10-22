package com.hamradio.aaos.radio.transport

import android.content.Context
import android.util.Log
import com.hamradio.aaos.BuildConfig
import com.hamradio.aaos.di.RadioPrefs
import com.hamradio.aaos.radio.protocol.BenshiMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TransportManager"

/**
 * The app-wide [IRadioTransport] singleton. Owns a swappable inner transport
 * (mock / RFCOMM / disconnected, chosen from [RadioPrefs]) and re-emits its
 * state and messages through stable flows, so consumers (RadioController)
 * keep their collectors across a transport switch.
 *
 * This replaces the old design where the concrete transport itself was the
 * Hilt singleton: SingletonComponent lives for the whole process, so
 * Activity.recreate() could never re-inject a different transport — toggling
 * mock mode or picking a new device silently kept the old transport until
 * the app was force-killed.
 */
@Singleton
class TransportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: RadioPrefs,
) : IRadioTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<BenshiMessage>(extraBufferCapacity = 64)
    override val inboundMessages: Flow<BenshiMessage> = _inbound.asSharedFlow()

    private var mirrorJobs: List<Job> = emptyList()

    private var active: IRadioTransport = buildTransport()

    init {
        startMirrors()
    }

    override val isConnected: Boolean get() = active.isConnected

    /** Device address when the active transport is RFCOMM (used for the audio channel). */
    val rfcommDeviceAddress: String? get() = (active as? RfcommTransport)?.deviceAddress

    val isMock: Boolean get() = active is MockTransport

    private fun buildTransport(): IRadioTransport {
        return if (BuildConfig.MOCK_RADIO || prefs.useMockRadio) {
            Log.i(TAG, "Using MockTransport")
            MockTransport()
        } else {
            val addr = prefs.deviceAddress
            if (addr != null) {
                Log.i(TAG, "Using RfcommTransport for $addr")
                RfcommTransport(context, addr)
            } else {
                Log.i(TAG, "No device address — staying disconnected until configured")
                DisconnectedTransport()
            }
        }
    }

    private fun startMirrors() {
        mirrorJobs.forEach { it.cancel() }
        val t = active
        mirrorJobs = listOf(
            scope.launch { t.connectionState.collect { _state.value = it } },
            scope.launch { t.inboundMessages.collect { _inbound.emit(it) } },
        )
    }

    /**
     * Tear down the current transport and rebuild from prefs.
     * Callers should reconnect afterwards (RadioController.switchTransport does).
     */
    suspend fun switchTransport() {
        Log.i(TAG, "Switching transport (mock=${prefs.useMockRadio}, addr=${prefs.deviceAddress})")
        mirrorJobs.forEach { it.cancel() }
        try {
            active.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Old transport disconnect failed: ${e.message}")
        }
        active = buildTransport()
        _state.value = ConnectionState.DISCONNECTED
        startMirrors()
    }

    override suspend fun connect() = active.connect()

    override suspend fun send(message: BenshiMessage) = active.send(message)

    override suspend fun disconnect() = active.disconnect()
}
