package com.hamradio.aaos.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

object DiagTestSockets {
    /** EOF or an actual reset/close counts as closed; a read timeout is a failure. */
    fun closedByServer(read: () -> Boolean): Boolean = try {
        read()
    } catch (e: SocketTimeoutException) {
        false
    } catch (e: SocketException) {
        true
    } catch (e: SSLException) {
        true
    }
}

/** The Diagnostics link over a real TLS listener with client-certificate pinning. */
class DiagTlsServerTest {
    private val servers = mutableListOf<DiagServer>()
    private val sockets = mutableListOf<Socket>()

    @After fun tearDown() {
        servers.forEach { it.stop() }
        sockets.forEach { runCatching { it.close() } }
    }

    private fun keyStore(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
        DiagTlsServerTest::class.java.getResourceAsStream("/diag/$name.p12")!!.use { load(it, "testpass".toCharArray()) }
    }

    private fun cert(name: String) = keyStore(name).getCertificate(name) as X509Certificate

    private fun context(keyName: String?, trust: X509TrustManager): SSLContext {
        val kms = keyName?.let {
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore(it), "testpass".toCharArray()) }.keyManagers
        }
        return SSLContext.getInstance("TLS").apply { init(kms, arrayOf(trust), null) }
    }

    /** PC side: trusts only the test server certificate. */
    private val serverPin = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (!chain!!.first().encoded.contentEquals(cert("server").encoded)) throw CertificateException("pin")
        }
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    private val approvals = AtomicInteger()
    private val asked = CountDownLatch(1)

    private fun tlsServer(autoOffMs: Long = 60_000): DiagServer {
        val ctx = context("server", PinnedClientTrust(cert("client")))
        val listener = ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
        listener.needClientAuth = true
        return DiagServer(
            source = ServerSocketSource(listener),
            peerFilter = { true },
            approve = { approvals.incrementAndGet(); asked.countDown(); kotlinx.coroutines.awaitCancellation() },
            execute = { "ok" },
            banner = "hello",
            autoOffMs = autoOffMs,
            handshake = { (it as SSLSocket).startHandshake() },
        ).start().also { servers += it }
    }

    private fun tlsClient(port: Int, keyName: String?): SSLSocket =
        (context(keyName, serverPin).socketFactory.createSocket(InetAddress.getLoopbackAddress(), port) as SSLSocket)
            .apply { soTimeout = 5_000; sockets += this }

    private fun rawClient(port: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 3_000; sockets += this }

    @Test fun pinnedClientReachesApprovalAndGetsNothingBeforeIt() {
        val s = tlsServer()
        val c = tlsClient(s.localPort, "client")
        c.startHandshake()
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        c.soTimeout = 500
        assertFalse("nothing may be sent before Allow", DiagTestSockets.closedByServer { c.inputStream.read() == -1 })
    }

    @Test fun strangerAndCertlessClientsNeverReachApproval() {
        val s = tlsServer()
        for (key in listOf("stranger", null)) {
            val c = tlsClient(s.localPort, key)
            val refused = runCatching { c.startHandshake(); c.inputStream.read() }.fold({ it == -1 }, { true })
            assertTrue("client $key must be refused", refused)
        }
        Thread.sleep(300)
        assertEquals(0, approvals.get())
    }

    @Test fun silentSecondClientIsDroppedAndOffClosesEverything() {
        val s = tlsServer()
        val first = tlsClient(s.localPort, "client")
        first.startHandshake()
        assertTrue(asked.await(5, TimeUnit.SECONDS))

        val silent = rawClient(s.localPort)            // connects, never speaks TLS
        assertTrue("excess client closed at once", DiagTestSockets.closedByServer { silent.getInputStream().read() == -1 })
        val another = rawClient(s.localPort)           // accept loop still running
        assertTrue(DiagTestSockets.closedByServer { another.getInputStream().read() == -1 })

        s.stop()
        assertTrue("approved-pending client closed by Off",
            DiagTestSockets.closedByServer { first.inputStream.read() == -1 })
        assertFalse(s.active)
        assertEquals(1, approvals.get())
    }

    @Test fun expiryClosesPendingTlsClient() {
        val s = tlsServer(autoOffMs = 1_500)
        val c = tlsClient(s.localPort, "client")
        c.startHandshake()
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        assertTrue(DiagTestSockets.closedByServer { c.inputStream.read() == -1 })
        assertFalse(s.active)
    }
}
