package com.hamradio.aaos.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The app dials out to the relay because the truck drops inbound connections (job #875). */
class DialOutSourceTest {
    private val relay = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val dials = LinkedBlockingQueue<Socket>()
    private val dialCount = AtomicInteger()
    private val cleanup = mutableListOf<() -> Unit>()

    init {
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { relay.accept() }.getOrNull() ?: break
                dialCount.incrementAndGet()
                dials.put(s)
            }
        }
    }

    @After fun tearDown() {
        cleanup.forEach { runCatching { it() } }
        relay.close()
    }

    private fun source(wrapped: MutableList<Socket> = mutableListOf()) =
        DialOutSource("127.0.0.1", relay.localPort, wrap = { wrapped += it; it }, retryMs = 100)
            .also { s -> cleanup += { s.close() } }

    private fun nextDial(): Socket = dials.poll(5, TimeUnit.SECONDS)!!

    @Test fun handsOverOnlyAfterGo() {
        val wrapped = mutableListOf<Socket>()
        val src = source(wrapped)
        val result = LinkedBlockingQueue<Socket>()
        thread(isDaemon = true) { runCatching { result.put(src.next()) } }
        val relaySide = nextDial()
        Thread.sleep(300)
        assertTrue("nothing handed over before GO", wrapped.isEmpty())
        relaySide.getOutputStream().write(DialOutSource.GO)
        val got = result.poll(5, TimeUnit.SECONDS)!!
        assertEquals(1, wrapped.size)
        got.getOutputStream().write("hi\n".toByteArray())
        assertEquals("hi", BufferedReader(InputStreamReader(relaySide.getInputStream())).readLine())
    }

    @Test fun redialsWhenRelayHangsUpBeforeGo() {
        val src = source()
        thread(isDaemon = true) { runCatching { src.next() } }
        nextDial().close()
        val second = nextDial()
        second.getOutputStream().write(DialOutSource.GO)
        assertTrue(dialCount.get() >= 2)
    }

    @Test fun backsOffAfterRepeatedHangupsAndJunk() {
        val src = DialOutSource("127.0.0.1", relay.localPort, wrap = { it }, retryMs = 500)
        cleanup += { src.close() }
        thread(isDaemon = true) { runCatching { src.next() } }
        val until = System.currentTimeMillis() + 1_600
        var junk = false
        while (System.currentTimeMillis() < until) {
            val s = dials.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (junk) s.getOutputStream().write(0x7F)   // anything but GO
            junk = !junk
            s.close()
        }
        // 1.6 s with a 500 ms back-off allows at most 4 dials; a busy loop would make hundreds.
        assertTrue("dials=${dialCount.get()}", dialCount.get() in 2..4)
    }

    @Test fun offDuringBackoffAfterHangup() {
        val src = DialOutSource("127.0.0.1", relay.localPort, wrap = { it }, retryMs = 60_000)
        val failed = LinkedBlockingQueue<Throwable>()
        thread(isDaemon = true) { runCatching { src.next() }.exceptionOrNull()?.let { failed.put(it) } }
        nextDial().close()                    // relay hangs up -> source backs off for 60 s
        Thread.sleep(300)
        src.close()
        assertTrue(failed.poll(5, TimeUnit.SECONDS) is IOException)
        assertEquals(1, dialCount.get())
    }

    @Test fun closeUnblocksWaitForGo() {
        val src = source()
        val failed = LinkedBlockingQueue<Throwable>()
        thread(isDaemon = true) { runCatching { src.next() }.exceptionOrNull()?.let { failed.put(it) } }
        nextDial()
        src.close()
        assertTrue(failed.poll(5, TimeUnit.SECONDS) is IOException)
    }

    @Test fun closeUnblocksRetryPause() {
        val dead = ServerSocket(0).apply { close() }.localPort   // nothing listening
        val src = DialOutSource("127.0.0.1", dead, wrap = { it }, retryMs = 60_000)
        val failed = LinkedBlockingQueue<Throwable>()
        thread(isDaemon = true) { runCatching { src.next() }.exceptionOrNull()?.let { failed.put(it) } }
        Thread.sleep(500)
        src.close()
        assertTrue(failed.poll(5, TimeUnit.SECONDS) is IOException)
    }

    @Test fun serverDoesNotDialAgainWhileClientIsLive() {
        val server = DiagServer(
            source = source(), peerFilter = { true }, approve = { true }, execute = { "ok" },
            banner = "hello", autoOffMs = 60_000,
        ).start()
        cleanup += { server.stop() }
        val first = nextDial()
        first.getOutputStream().write(DialOutSource.GO)
        val reader = BufferedReader(InputStreamReader(first.getInputStream()))
        assertEquals("hello", reader.readLine())
        Thread.sleep(800)
        assertEquals("one live connection, no extra dials", 1, dialCount.get())
        first.close()                                  // PC side gone: the app dials again
        val again = nextDial()
        assertFalse(again.isClosed)
        server.stop()
        assertEquals(-1, again.getInputStream().read())   // Off closes the pending dial too
    }
}
