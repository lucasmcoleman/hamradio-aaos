package com.hamradio.aaos.diag

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.hamradio.aaos.R
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

private const val KEY_ALIAS = "hamradio-diag-link"

/**
 * TLS for the Diagnostics link (codex review #941).
 *
 * Server side: an EC key generated in the head unit's AndroidKeyStore (never exported).
 * Its certificate's SHA-256 fingerprint is shown on screen so the PC can pin it.
 * Client side: only the PC certificate packaged in res/raw/diag_pc_client.crt is trusted;
 * its private key stays on the PC. There is no trust-all path.
 */
object DiagTls {

    class Server(val context: SSLContext, val fingerprint: String)

    fun serverContext(app: Context): Server {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) generateKey()
        val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)
        val pinned = app.resources.openRawResource(R.raw.diag_pc_client).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(PinnedClientTrust(pinned)), null)
        return Server(ctx, fingerprint(cert))
    }

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    private fun generateKey() {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=hamradio-diag-truck"))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
    }
}

/** Accepts exactly one client certificate, compared byte for byte. */
class PinnedClientTrust(private val pinned: X509Certificate) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no client certificate")
        if (!leaf.encoded.contentEquals(pinned.encoded)) throw CertificateException("client certificate not pinned")
        leaf.checkValidity()
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("server certificates are not trusted here")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(pinned)
}
