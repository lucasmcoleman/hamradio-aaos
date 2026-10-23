package com.hamradio.aaos.radio.transport

import android.util.Log
import com.hamradio.aaos.radio.protocol.BenshiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private const val TAG = "FailoverTransport"

/**
 * Tries classic RFCOMM first (control + audio), then BLE (control only).
 *
 * GM's head unit refuses classic Bluetooth to non-phone devices, so BLE is the only
 * link that works in the truck. After BLE succeeds once, later reconnects try it first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailoverTransport(
    val primary: IRadioTransport,
    val fallback: IRadioTransport,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : IRadioTransport {

    private val active = MutableStateFlow(primary)

    /** The transport currently carrying the link (or the last one tried). */
    val activeTransport: IRadioTransport get() = active.value

    @Volatile private var preferFallback = false

    override val connectionState: StateFlow<ConnectionState> =
        active.flatMapLatest { it.connectionState }
            .stateIn(scope, SharingStarted.Eagerly, ConnectionState.DISCONNECTED)

    override val inboundMessages: Flow<BenshiMessage> = merge(primary.inboundMessages, fallback.inboundMessages)

    override val isConnected: Boolean get() = active.value.isConnected

    override suspend fun connect() {
        val order = if (preferFallback) listOf(fallback, primary) else listOf(primary, fallback)
        val errors = mutableListOf<String>()
        for (transport in order) {
            active.value = transport
            try {
                transport.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "${label(transport)}: ${describe(e)}"
            }
            if (transport.isConnected) {
                preferFallback = transport === fallback
                Log.i(TAG, "Connected over ${label(transport)}")
                return
            }
            transport.disconnect()
        }
        throw IOException(errors.joinToString(" | ").ifEmpty { "radio did not connect" })
    }

    override suspend fun send(message: BenshiMessage) = active.value.send(message)

    override suspend fun disconnect() {
        primary.disconnect()
        fallback.disconnect()
    }

    private fun label(transport: IRadioTransport) = if (transport === fallback) "BLE" else "classic"
}
