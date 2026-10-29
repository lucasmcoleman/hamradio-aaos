package com.hamradio.aaos.radio.transport

import android.util.Log
import com.hamradio.aaos.radio.protocol.BenshiMessage
import com.hamradio.aaos.radio.protocol.GaiaFrame
import com.hamradio.aaos.radio.protocol.RadioCommands
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** A local ServerSocket stands in for the ESP32 bridge's control port. */
class BridgeTransportTest {
    private lateinit var server: ServerSocket

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        server = ServerSocket(0)
    }

    @After fun teardown() {
        server.close()
        unmockkStatic(Log::class)
    }

    @Test fun parsesHostAndPort() {
        assertEquals("192.168.4.1" to 8701, BridgeTransport("bridge:192.168.4.1").let { it.host to it.port })
        assertEquals("bridge.local" to 9000, BridgeTransport("bridge:bridge.local:9000").let { it.host to it.port })
        assertEquals("192.168.4.1" to 8701, BridgeTransport("BRIDGE:192.168.4.1").let { it.host to it.port })
    }

    @Test fun sendsGaiaFramesAndDecodesReplies() = runBlocking {
        val reply = BenshiMessage(2, 4, true, byteArrayOf(0, 1, 2, 3))
        val expected = GaiaFrame.encode(RadioCommands.getDevInfo())
        var received = ByteArray(0)
        val fake = thread {
            server.accept().use { s ->
                val buf = ByteArray(expected.size)
                var n = 0
                while (n < buf.size) n += s.getInputStream().read(buf, n, buf.size - n)
                received = buf
                // Split the reply across two writes, as Wi-Fi may deliver it.
                val bytes = GaiaFrame.encode(reply)
                s.getOutputStream().write(bytes, 0, 3); s.getOutputStream().flush()
                Thread.sleep(50)
                s.getOutputStream().write(bytes, 3, bytes.size - 3); s.getOutputStream().flush()
                Thread.sleep(500)
            }
        }
        val t = BridgeTransport("bridge:127.0.0.1:${server.localPort}")
        t.connect()
        assertEquals(ConnectionState.CONNECTED, t.connectionState.value)
        t.send(RadioCommands.getDevInfo())
        val got = withTimeout(5_000) { t.inboundMessages.first() }
        assertEquals(reply, got)
        fake.join(5_000)
        assertArrayEquals(expected, received)
        t.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, t.connectionState.value)
    }

    @Test fun bridgeClosingIsReportedAsError() = runBlocking {
        val fake = thread { server.accept().close() }
        val t = BridgeTransport("bridge:127.0.0.1:${server.localPort}")
        t.connect()
        fake.join(5_000)
        withTimeout(5_000) { t.connectionState.first { it == ConnectionState.ERROR } }
        Unit
    }
}
