package com.hamradio.aaos.radio.transport

import com.hamradio.aaos.radio.protocol.BenshiMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * No-op transport used when no device address is configured and mock mode is off.
 * Stays permanently disconnected — all sends are silently dropped.
 */
class DisconnectedTransport : IRadioTransport {
    override val isConnected: Boolean = false
    override val inboundMessages: Flow<BenshiMessage> = emptyFlow()
    override val connectionState: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.DISCONNECTED)

    override suspend fun connect() { /* no-op */ }
    override suspend fun send(message: BenshiMessage) { /* no-op */ }
    override suspend fun disconnect() { /* no-op */ }
}
