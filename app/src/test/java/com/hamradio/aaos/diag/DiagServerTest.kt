package com.hamradio.aaos.diag

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Lifecycle and limits of the Diagnostics link session (codex review #941). */
class DiagServerTest {
    private val servers = mutableListOf<DiagServer>()
    private val dispatched = CopyOnWriteArrayList<String>()

    @After fun tearDown() = servers.forEach { it.stop() }

    private fun server(
        approve: suspend (InetAddress) -> Boolean = { true },
        execute: suspend (DiagCommand) -> String = { dispatched += it.toString(); "ok" },
        handshake: (Socket) -> Unit = {},
        autoOffMs: Long = 60_000,
        idleTimeoutMs: Int = 60_000,
        approveTimeoutMs: Long = 60_000,
    ) = DiagServer(
        listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress()),
        peerFilter = { true }, approve = approve, execute = execute, banner = "hello",
        autoOffMs = autoOffMs, idleTimeoutMs = idleTimeoutMs, approveTimeoutMs = approveTimeoutMs,
        handshake = handshake,
    ).start().also { servers += it }

    private class Client(port: Int) {
        val sock = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 5_000 }
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        fun send(line: String) {
            sock.getOutputStream().write("$line\n".toByteArray())
            sock.getOutputStream().flush()
        }
        fun block(): String? {
            val lines = mutableListOf<String>()
            while (true) {
                val l = reader.readLine() ?: return null
                if (l == DiagServer.END) return lines.joinToString("\n")
                lines += l
            }
        }
        /** True once the server has closed the connection (EOF or reset); a read timeout fails. */
        fun closedByServer(): Boolean = DiagTestSockets.closedByServer { reader.readLine() == null }
    }

    private fun waitFor(cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test fun offClosesIdleClientAndNothingRunsAfter() {
        val s = server()
        val c = Client(s.localPort)
        assertEquals("hello", c.block())
        s.stop()
        assertTrue(c.closedByServer())
        runCatching { c.send("info") }
        Thread.sleep(200)
        assertTrue(dispatched.isEmpty())
        assertFalse(s.active)
    }

    @Test fun offClosesClientDuringHandshake() {
        val inHandshake = CountDownLatch(1)
        val s = server(handshake = { inHandshake.countDown(); it.getInputStream().read() })
        val c = Client(s.localPort)
        assertTrue(inHandshake.await(5, TimeUnit.SECONDS))
        s.stop()
        assertTrue(c.closedByServer())
    }

    @Test fun offClosesClientWaitingForApprovalAndSendsNothing() {
        val asked = CountDownLatch(1)
        val s = server(approve = { asked.countDown(); awaitCancellation() })
        val c = Client(s.localPort)
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        s.stop()
        assertTrue(c.closedByServer())
        assertTrue(dispatched.isEmpty())
    }

    @Test fun clientHangingUpWithdrawsApprovalPrompt() {
        val asked = CountDownLatch(1)
        val withdrawn = CompletableDeferred<Unit>()
        var calls = 0
        val s = server(approve = {
            if (++calls > 1) return@server true
            asked.countDown()
            try { awaitCancellation() } finally { withdrawn.complete(Unit) }
        })
        val c = Client(s.localPort)
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        c.sock.close()
        runBlocking { withTimeout(5_000) { withdrawn.await() } }
        assertTrue(s.active)
        waitFor { !s.clientConnected }
        val next = Client(s.localPort)   // slot is free again
        assertEquals("hello", next.block())
    }

    @Test fun deniedClientGetsNothing() {
        val s = server(approve = { false })
        val c = Client(s.localPort)
        assertNull(c.block())   // closed without a banner
    }

    @Test fun approvalTimesOut() {
        val s = server(approve = { awaitCancellation() }, approveTimeoutMs = 200)
        val c = Client(s.localPort)
        assertNull(c.block())
    }

    @Test fun offCancelsRunningCommandAndClosesClient() {
        val started = CountDownLatch(1)
        val cancelled = CompletableDeferred<Unit>()
        val s = server(execute = {
            started.countDown()
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        val c = Client(s.localPort)
        c.block()
        c.send("bonded")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        s.stop()
        assertTrue(c.closedByServer())
        runBlocking { withTimeout(5_000) { cancelled.await() } }
    }

    @Test fun expiryClosesClient() {
        val s = server(autoOffMs = 300)
        val c = Client(s.localPort)
        c.block()
        assertTrue(c.closedByServer())
        waitFor { !s.active }
    }

    @Test fun secondClientIsClosedEvenWhileFirstAwaitsApproval() {
        val asked = CountDownLatch(1)
        val s = server(approve = { asked.countDown(); awaitCancellation() })
        Client(s.localPort)
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        val second = Client(s.localPort)
        assertTrue(second.closedByServer())
    }

    @Test fun oversizedLineDropsClient() {
        val s = server()
        val c = Client(s.localPort)
        c.block()
        c.send("x".repeat(1000))
        assertTrue(c.closedByServer())
        assertTrue(dispatched.isEmpty())
    }

    @Test fun idleClientIsDropped() {
        val s = server(idleTimeoutMs = 300)
        val c = Client(s.localPort)
        c.block()
        assertTrue(c.closedByServer())
    }

    @Test fun offCommandStopsOnlyItsOwnSession() {
        val old = server()
        val oldClient = Client(old.localPort)
        oldClient.block()
        val fresh = server()
        oldClient.send("off")
        assertEquals("turning off", oldClient.block())
        waitFor { !old.active }
        assertTrue(fresh.active)
        val c = Client(fresh.localPort)
        assertEquals("hello", c.block())
        c.send("info")
        assertEquals("ok", c.block())
    }

    @Test fun lateCleanupOfOldSessionLeavesNewSessionUsable() {
        val release = CountDownLatch(1)
        val running = CountDownLatch(1)
        val old = server(execute = {
            running.countDown()
            while (release.count > 0) Thread.sleep(10)
            "late"
        })
        val oldClient = Client(old.localPort)
        oldClient.block()
        oldClient.send("info")
        assertTrue(running.await(5, TimeUnit.SECONDS))
        old.stop()                       // old command still finishing in the background
        val fresh = server()
        release.countDown()              // old session's cleanup runs now
        Thread.sleep(200)
        assertTrue(fresh.active)
        val c = Client(fresh.localPort)
        assertEquals("hello", c.block())
        c.send("bonded")
        assertEquals("ok", c.block())
    }

    @Test fun onlySavedRadioIsATarget() {
        val radio = "38:D2:00:01:68:A8"
        assertNull(DiagCommand.targetRefusal(DiagCommand.parse("uuids $radio"), radio))
        assertNull(DiagCommand.targetRefusal(DiagCommand.Info, null))
        assertTrue(DiagCommand.targetRefusal(DiagCommand.parse("bond AA:BB:CC:DD:EE:FF"), radio)!!.startsWith("refused"))
        assertTrue(DiagCommand.targetRefusal(DiagCommand.parse("rfcomm $radio 1101 secure"), null)!!.startsWith("refused"))
    }
}
