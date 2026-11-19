package io.github.tux4us.miriquidihub.files

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Hilfen für Server mit selbstsigniertem Zertifikat (typisch im Heimnetz). Der Nutzer bestätigt
 * den SHA-256-Fingerabdruck einmalig; danach wird nur noch genau dieses Zertifikat akzeptiert.
 */
object CertTools {

    /** SHA-256 des Zertifikats als Hex-Text in Großbuchstaben ohne Trennzeichen. */
    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

    /** "AB:CD:…" für die Anzeige. */
    fun pretty(hex: String): String = hex.chunked(2).joinToString(":")

    fun normalize(fingerprint: String): String =
        fingerprint.filter { it.isLetterOrDigit() }.uppercase()

    /**
     * Holt das Serverzertifikat ohne es zu prüfen. Nur zur Anzeige und Bestätigung durch den Nutzer;
     * über diese Verbindung werden keine Daten übertragen.
     */
    fun fetchLeaf(host: String, port: Int, timeoutMs: Int = 8000): X509Certificate {
        var captured: X509Certificate? = null
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                captured = chain?.firstOrNull()
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = timeoutMs
        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.use { it.startHandshake() }
        return captured ?: throw IOException("Kein Zertifikat erhalten")
    }
}

/** Akzeptiert ausschließlich das Zertifikat mit dem hinterlegten Fingerabdruck. */
class PinnedTrustManager(pinnedSha256: String) : X509TrustManager {
    private val pin = CertTools.normalize(pinnedSha256)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Clientzertifikate werden nicht unterstützt")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Keine Zertifikatskette")
        if (CertTools.sha256(leaf) != pin) {
            throw CertificateException("Zertifikat stimmt nicht mit dem bestätigten Fingerabdruck überein")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
