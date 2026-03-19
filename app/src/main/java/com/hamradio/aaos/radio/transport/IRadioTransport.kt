package com.hamradio.aaos.radio.transport

import com.hamradio.aaos.radio.protocol.BenshiMessage
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the Bluetooth transport (BLE or RFCOMM mock).
 * Implementations: [BleTransport], [MockTransport].
 */
interface IRadioTransport {

    /** True when a connection is established. */
    val isConnected: Boolean

    /** Flow of inbound messages from the radio. */
    val inboundMessages: Flow<BenshiMessage>

    /** Flow of connection state changes. */
    val connectionState: Flow<ConnectionState>

    /** Attempt to connect to the radio. */
    suspend fun connect()

    /** Send a message to the radio. */
    suspend fun send(message: BenshiMessage)

    /** Disconnect and release resources. */
    suspend fun disconnect()
}

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    ERROR,
}
