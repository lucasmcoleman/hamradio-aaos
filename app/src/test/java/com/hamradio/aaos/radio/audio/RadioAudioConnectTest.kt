package com.hamradio.aaos.radio.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

class RadioAudioConnectTest {
    private val context = mockk<Context>()
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val device = mockk<BluetoothDevice>()

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        val manager = mockk<BluetoothManager>()
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        every { manager.adapter } returns adapter
        every { adapter.getRemoteDevice(any<String>()) } returns device
    }

    @After fun tearDown() = unmockkAll()

    @Test fun audioConnectFailureFallsBackToInsecureSocket() = runBlocking {
        val secure = mockk<BluetoothSocket>(relaxed = true)
        val insecure = mockk<BluetoothSocket>(relaxed = true)
        every { secure.connect() } throws IOException("read failed")
        every { device.createRfcommSocketToServiceRecord(any()) } returns secure
        every { device.createInsecureRfcommSocketToServiceRecord(any()) } returns insecure
        val channel = RadioAudioChannel(context, "38:D2:00:01:68:A8")
        try {
            channel.connect()
            verify { secure.connect() }
            verify { secure.close() }
            verify { insecure.connect() }
        } finally { channel.close() }
    }

    @Test fun allAudioAttemptsFailingTriesEveryKind() = runBlocking {
        val sock = mockk<BluetoothSocket>(relaxed = true)
        every { sock.connect() } throws IOException("refused")
        every { device.createRfcommSocketToServiceRecord(any()) } returns sock
        every { device.createInsecureRfcommSocketToServiceRecord(any()) } returns sock
        val channel = RadioAudioChannel(context, "38:D2:00:01:68:A8")
        try {
            channel.connect()
            verify(exactly = 3) { sock.connect() }
        } finally { channel.close() }
    }
}
