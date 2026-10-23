package com.hamradio.aaos.diag

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Where a [DiagServer] gets its connections from. */
interface SocketSource {
    /** Blocks until the next connection; throws [IOException] once [close] has been called. */
    fun next(): Socket

    /** Unblocks [next] and releases everything held. Safe to call more than once. */
    fun close()

    /**
     * True for a source that makes one connection at a time (dial-out): the server waits
     * for its client slot to free up before asking for another, instead of rejecting.
     */
    val waitsForSlot: Boolean
}

/** Listening socket (tests, adb forward). Excess clients are rejected by [DiagServer]. */
class ServerSocketSource(private val server: ServerSocket) : SocketSource {
    val localPort: Int get() = server.localPort
    override val waitsForSlot = false
    override fun next(): Socket = server.accept()
    override fun close() { runCatching { server.close() } }
}

/**
 * Dial-out (job #875): GM's head unit drops all inbound connections on Wi-Fi, so the app
 * connects out to a relay on the home LAN instead. The relay sends one GO byte (0x01) once
 * Lucas's PC has connected to its other side; only then is the socket handed to [wrap]
 * (TLS server mode with the pinned client certificate) and returned. The relay only pipes
 * bytes, so TLS still runs end to end between the app and the PC.
 */
class DialOutSource(
    private val host: String,
    private val port: Int,
    private val wrap: (Socket) -> Socket,
    private val connectTimeoutMs: Int = 10_000,
    private val retryMs: Long = 5_000,
) : SocketSource {
    @Volatile private var closed = false
    @Volatile private var pending: Socket? = null
    override val waitsForSlot = true

    override fun next(): Socket {
        while (!closed) {
            val raw = Socket()
            pending = raw
            try {
                if (closed) break
                raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
                raw.soTimeout = 0                       // wait as long as needed for the PC
                if (raw.getInputStream().read() == GO) {
                    pending = null
                    return wrap(raw)
                }
                runCatching { raw.close() }             // relay hung up or sent junk; dial again
            } catch (e: IOException) {
                runCatching { raw.close() }
                if (closed) break
                pause()
            }
        }
        throw IOException("dial-out closed")
    }

    override fun close() {
        closed = true
        pending?.let { runCatching { it.close() } }
    }

    private fun pause() {
        val until = System.currentTimeMillis() + retryMs
        while (!closed && System.currentTimeMillis() < until) Thread.sleep(100)
    }

    companion object {
        const val GO = 0x01
    }
}
