package com.hamradio.aaos.diag

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One Diagnostics link session (codex review #941). Each switch-on builds a new instance,
 * so an old session's cleanup, timer or clients can never touch a newer one.
 *
 * - [handshake] authenticates the peer (TLS client certificate) before anything else.
 * - Every connection must then be approved on the head unit ([approve]); nothing is
 *   sent before that, and the single client slot stays held while the prompt is up.
 * - One client at a time; any other connection is closed at once, unread and unwritten.
 * - Lines are capped at [maxLine] bytes; idle clients are dropped after [idleTimeoutMs].
 * - Commands run one at a time.
 * - [stop] (switch-off, `off`, or the [autoOffMs] expiry) closes the listener and the
 *   connected client, and no command is dispatched after it.
 */
class DiagServer(
    private val listener: ServerSocket,
    private val peerFilter: (InetAddress) -> Boolean,
    private val approve: suspend (InetAddress) -> Boolean,
    private val execute: suspend (DiagCommand) -> String,
    private val banner: String,
    private val autoOffMs: Long,
    private val idleTimeoutMs: Int = 5 * 60_000,
    private val approveTimeoutMs: Long = 60_000,
    private val handshakeTimeoutMs: Int = 15_000,
    private val maxLine: Int = 256,
    private val handshake: (Socket) -> Unit = {},
    private val onChange: (DiagServer) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val ops = Mutex()
    @Volatile private var client: Socket? = null
    @Volatile var active = true
        private set

    val localPort: Int get() = listener.localPort
    val clientConnected: Boolean get() = client != null

    fun start(): DiagServer {
        scope.launch { delay(autoOffMs); stop() }
        scope.launch { acceptLoop() }
        return this
    }

    fun stop() {
        val toClose: Socket?
        synchronized(lock) {
            if (!active) return
            active = false
            toClose = client
            client = null
        }
        runCatching { listener.close() }
        runCatching { toClose?.close() }
        scope.cancel()
        onChange(this)
    }

    private suspend fun acceptLoop() {
        while (active) {
            val sock = try { listener.accept() } catch (_: IOException) { return }
            if (!peerFilter(sock.inetAddress)) { runCatching { sock.close() }; continue }
            val accepted = synchronized(lock) {
                if (!active || client != null) false else { client = sock; true }
            }
            if (!accepted) {
                // Close without writing: a write on an SSLSocket would start a handshake here,
                // and a silent peer could stall the accept loop (codex recheck #942).
                runCatching { sock.close() }
                continue
            }
            onChange(this)
            scope.launch { serve(sock) }
        }
    }

    private suspend fun serve(sock: Socket) {
        try {
            sock.soTimeout = handshakeTimeoutMs
            handshake(sock)   // TLS client-cert check; throws for anyone but the pinned PC
            if (!awaitApproval(sock) || !active) return   // nothing is sent to an unapproved client
            sock.soTimeout = idleTimeoutMs
            val out = sock.getOutputStream()
            write(out, banner)
            val input = sock.getInputStream()
            while (active) {
                val line = readLine(input) ?: break
                if (!active) break
                val cmd = DiagCommand.parse(line)
                if (cmd is DiagCommand.Off) { write(out, "turning off"); stop(); break }
                val text = try {
                    ops.withLock { if (active) execute(cmd) else null }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "error: ${e.message ?: e.javaClass.simpleName}"
                } ?: break
                if (!active) break
                write(out, text)
            }
        } catch (_: IOException) {
            // failed handshake, idle timeout, oversized line, or closed by stop()
        } finally {
            runCatching { sock.close() }
            val changed = synchronized(lock) { if (client === sock) { client = null; true } else false }
            if (changed && active) onChange(this)
        }
    }

    /**
     * Waits for the on-screen answer while watching the connection: if the client hangs up
     * (or sends anything before approval) the prompt is withdrawn and the answer is no.
     */
    private suspend fun awaitApproval(sock: Socket): Boolean = coroutineScope {
        val decision = async { withTimeoutOrNull(approveTimeoutMs) { approve(sock.inetAddress) } ?: false }
        sock.soTimeout = APPROVAL_POLL_MS
        val input = sock.getInputStream()
        while (decision.isActive) {
            try {
                input.read()   // EOF or early data both end the request
                decision.cancel()
                return@coroutineScope false
            } catch (_: SocketTimeoutException) {
                // still connected and quiet; keep waiting for the answer
            }
        }
        decision.await()
    }

    private fun write(out: OutputStream, text: String) {
        out.write("$text\n$END\n".toByteArray())
        out.flush()
    }

    /** Reads one line of at most [maxLine] bytes; throws if longer. Null on EOF. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (buf.size() >= maxLine) throw IOException("line too long")
            buf.write(b)
        }
    }

    companion object {
        const val END = "<<END>>"
        private const val APPROVAL_POLL_MS = 300
    }
}
