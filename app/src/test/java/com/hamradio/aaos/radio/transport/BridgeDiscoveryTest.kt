package com.hamradio.aaos.radio.transport

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

class BridgeDiscoveryTest {
    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
    }

    @After fun teardown() = unmockkStatic(Log::class)

    @Test fun candidatesCoverTheSubnetExceptSelf() {
        val hosts = BridgeDiscovery.candidateHosts(byteArrayOf(192.toByte(), 168.toByte(), 1, 1), 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.2", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.1" in hosts)
    }

    @Test fun skipsNetworksTooWideToScan() {
        assertTrue(BridgeDiscovery.candidateHosts(byteArrayOf(10, 0, 0, 5), 8).isEmpty())
        assertEquals(1021, BridgeDiscovery.candidateHosts(byteArrayOf(10, 0, 0, 5), 22).size)
    }

    @Test fun findsTheHostWithThePortOpen() = runBlocking {
        ServerSocket(0).use { server ->
            assertEquals("127.0.0.1", BridgeDiscovery.find(listOf("127.0.0.1"), server.localPort))
        }
    }

    @Test fun reportsNoBridgeWhenNothingAnswers() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(BridgeDiscovery.find(listOf("127.0.0.1"), port))
    }

    @Test fun bareOrAutoAddressMeansScan() {
        assertTrue(BridgeTransport("BRIDGE:").autoDiscover)
        assertTrue(BridgeTransport("BRIDGE:AUTO").autoDiscover)
        assertFalse(BridgeTransport("bridge:192.168.4.1").autoDiscover)
    }
}
