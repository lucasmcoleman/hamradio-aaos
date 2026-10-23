package com.hamradio.aaos.radio.transport

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.hamradio.aaos.radio.protocol.RadioCommands
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RfcommTransportTest {
    private val context = mockk<Context>()
    private val manager = mockk<BluetoothManager>()
    private val adapter = mockk<BluetoothAdapter>()
    private val device = mockk<BluetoothDevice>()
    private val socket = mockk<BluetoothSocket>()
    private val input = mockk<InputStream>()
    private val output = mockk<OutputStream>()
    private lateinit var scope: CoroutineScope
    @Before fun setup() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        every { manager.adapter } returns adapter
        every { adapter.cancelDiscovery() } returns true
        every { adapter.getRemoteDevice(any<String>()) } returns device
        every { device.bondState } returns BluetoothDevice.BOND_BONDED
        every { device.createRfcommSocketToServiceRecord(any()) } returns socket
        every { socket.inputStream } returns input
        every { socket.outputStream } returns output
        every { input.available() } returns 0
        every { output.flush() } just Runs
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }
    @After fun cleanup() { scope.cancel(); unmockkAll() }

    @Test fun disconnectClosesPendingSocketAndLateSuccessCannotResurrect() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        every { socket.connect() } answers {
            entered.complete(Unit)
            check(release.await(3, TimeUnit.SECONDS))
        }
        every { socket.close() } answers { release.countDown() }
        val transport = RfcommTransport(context, "00:11:22:33:44:55", scope)
        val connect = launch(Dispatchers.IO) { transport.connect() }
        withTimeout(3000) { entered.await() }
        transport.disconnect()
        withTimeout(3000) { connect.join() }
        assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)
        assertFalse(transport.isConnected)
        verify(atLeast = 1) { socket.close() }
    }

    @Test fun coroutineCancellationClosesBlockingConnect() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        every { socket.connect() } answers {
            entered.complete(Unit)
            check(release.await(3, TimeUnit.SECONDS))
        }
        every { socket.close() } answers { release.countDown() }
        val transport = RfcommTransport(context, "00:11:22:33:44:55", scope)
        val connect = launch(Dispatchers.IO) { transport.connect() }
        withTimeout(3000) { entered.await() }
        withTimeout(3000) { connect.cancelAndJoin() }
        assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)
        verify(atLeast = 1) { socket.close() }
    }

    @Test fun cancellationInterruptsBlockedWriteAndMarksSessionFailed() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        every { socket.connect() } just Runs
        every { socket.close() } answers { release.countDown() }
        every { output.write(any<ByteArray>()) } answers {
            entered.complete(Unit)
            check(release.await(3, TimeUnit.SECONDS))
        }
        val transport = RfcommTransport(context, "00:11:22:33:44:55", scope)
        transport.connect()
        val writer = launch(Dispatchers.IO) { transport.send(RadioCommands.getHtStatus()) }
        withTimeout(3000) { entered.await() }
        withTimeout(3000) { writer.cancelAndJoin() }
        assertEquals(ConnectionState.ERROR, transport.connectionState.value)
        assertFalse(transport.isConnected)
        transport.disconnect()
    }

    @Test fun secureConnectFailureFallsBackToInsecure() = runBlocking {
        val insecure = mockk<BluetoothSocket>()
        every { socket.connect() } throws java.io.IOException("read failed, socket might closed")
        every { socket.close() } just Runs
        every { device.createInsecureRfcommSocketToServiceRecord(any()) } returns insecure
        every { insecure.connect() } just Runs
        every { insecure.inputStream } returns input
        every { insecure.outputStream } returns output
        every { insecure.close() } just Runs
        val transport = RfcommTransport(context, "00:11:22:33:44:55", scope)
        transport.connect()
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)
        verify { insecure.connect() }
        transport.disconnect()
    }

    @Test fun unpairedRadioIsBondedBeforeConnecting() = runBlocking {
        var state = BluetoothDevice.BOND_NONE
        every { device.bondState } answers { state }
        every { device.createBond() } answers { state = BluetoothDevice.BOND_BONDED; true }
        every { socket.connect() } just Runs
        every { socket.close() } just Runs
        val transport = RfcommTransport(context, "00:11:22:33:44:55", scope)
        transport.connect()
        verifyOrder { device.createBond(); socket.connect() }
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)
        transport.disconnect()
    }

    @Test fun declinedPairingSurfacesAnError() = runBlocking {
        every { device.bondState } returns BluetoothDevice.BOND_NONE
        every { device.createBond() } returns false
        val transport = RfcommTransport(context, "00:11:22:33:44:55", scope)
        val error = runCatching { transport.connect() }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("pairing"))
        assertEquals(ConnectionState.ERROR, transport.connectionState.value)
    }

    @Test fun unpairedRadioTriesInsecureBeforePairing() = runBlocking {
        val insecure = mockk<BluetoothSocket>()
        every { device.bondState } returns BluetoothDevice.BOND_NONE
        every { device.createInsecureRfcommSocketToServiceRecord(any()) } returns insecure
        every { insecure.connect() } just Runs
        every { insecure.inputStream } returns input
        every { insecure.outputStream } returns output
        every { insecure.close() } just Runs
        val transport = RfcommTransport(context, "00:11:22:33:44:55", scope)
        transport.connect()
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)
        verify(exactly = 0) { device.createBond() }
        transport.disconnect()
    }
}
