package com.hamradio.aaos.radio.transport

import android.util.Log
import com.hamradio.aaos.radio.protocol.BenshiMessage
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException

class FailoverTransportTest {
    private class FakeTransport(private val works: Boolean, private val name: String) : IRadioTransport {
        val state = MutableStateFlow(ConnectionState.DISCONNECTED)
        var connects = 0
        override val connectionState: StateFlow<ConnectionState> = state
        override val inboundMessages: Flow<BenshiMessage> = emptyFlow()
        override val isConnected get() = state.value == ConnectionState.CONNECTED
        override suspend fun connect() {
            connects++
            if (works) state.value = ConnectionState.CONNECTED
            else { state.value = ConnectionState.ERROR; throw IOException("$name refused") }
        }
        override suspend fun send(message: BenshiMessage) {}
        override suspend fun disconnect() { state.value = ConnectionState.DISCONNECTED }
    }

    private lateinit var scope: CoroutineScope
    @Before fun setup() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
    }
    @After fun cleanup() { scope.cancel(); unmockkAll() }

    @Test fun usesClassicWhenItWorks() = runBlocking {
        val classic = FakeTransport(true, "classic"); val ble = FakeTransport(true, "ble")
        val t = FailoverTransport(classic, ble, scope)
        t.connect()
        assertSame(classic, t.activeTransport)
        assertEquals(0, ble.connects)
        assertEquals(ConnectionState.CONNECTED, t.connectionState.value)
    }

    @Test fun fallsBackToBleAndPrefersItNextTime() = runBlocking {
        val classic = FakeTransport(false, "classic"); val ble = FakeTransport(true, "ble")
        val t = FailoverTransport(classic, ble, scope)
        t.connect()
        assertSame(ble, t.activeTransport)
        assertTrue(t.isConnected)
        t.disconnect()
        t.connect()
        assertEquals(1, classic.connects)
        assertEquals(2, ble.connects)
    }

    @Test fun reportsBothErrorsWhenNeitherWorks() = runBlocking {
        val t = FailoverTransport(FakeTransport(false, "classic"), FakeTransport(false, "ble"), scope)
        val error = runCatching { t.connect() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("classic: classic refused"))
        assertTrue(error.message!!.contains("BLE: ble refused"))
    }
}
