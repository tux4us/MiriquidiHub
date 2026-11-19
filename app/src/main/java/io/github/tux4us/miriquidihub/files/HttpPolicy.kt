package io.github.tux4us.miriquidihub.files

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Regel für unverschlüsseltes HTTP. Die Netzwerk-Sicherheitskonfiguration der App erlaubt Klartext
 * technisch für alle Hosts (Android kann keine IP-Bereiche freigeben). Diese Regel schränkt es
 * auf App-Ebene auf das Heimnetz ein: HTTP ist ohne Rückfrage nur für private Adressen erlaubt.
 * Hostnamen werden nicht aufgelöst, es zählt allein die Schreibweise.
 */
object HttpPolicy {

    enum class Verdict { OK, NEEDS_CONFIRMATION, INVALID }

    private val PRIVATE_SUFFIXES = listOf(
        ".local", ".lan", ".home.arpa", ".localdomain", ".home", ".fritz.box", ".internal"
    )
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "fritz.box") return true
        if (PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true
        val m = IPV4.matchEntire(h)
        if (m != null) {
            val o = m.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return false
            return isPrivateV4(o[0], o[1])
        }
        if (h.contains(':')) return isPrivateV6(h)
        // Einzelner Name ohne Punkt (z. B. "nas", "raspberrypi") gilt als lokal.
        return !h.contains('.')
    }

    private fun isPrivateV4(a: Int, b: Int): Boolean = when {
        a == 10 -> true
        a == 127 -> true
        a == 169 && b == 254 -> true
        a == 172 && b in 16..31 -> true
        a == 192 && b == 168 -> true
        a == 100 && b in 64..127 -> true // CGNAT, u. a. Tailscale
        else -> false
    }

    private fun isPrivateV6(literal: String): Boolean = try {
        // Nur Literale (enthalten ':'), daher keine DNS-Auflösung.
        val addr = InetAddress.getByName(literal)
        if (addr is Inet4Address) {
            val b = addr.address
            isPrivateV4(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF)
        } else {
            val b = addr.address
            addr.isLoopbackAddress || addr.isLinkLocalAddress || (b[0].toInt() and 0xFE) == 0xFC
        }
    } catch (e: Exception) {
        false
    }

    /** Bewertet Schema und Host. [allowInsecure] ist die ausdrückliche Bestätigung des Nutzers. */
    fun check(scheme: String, host: String, allowInsecure: Boolean): Verdict = when (scheme.lowercase()) {
        "https" -> Verdict.OK
        "http" -> if (isPrivateHost(host) || allowInsecure) Verdict.OK else Verdict.NEEDS_CONFIRMATION
        else -> Verdict.INVALID
    }

    /** true, wenn die Verbindung unverschlüsselt ist (für Warnhinweise in der Oberfläche). */
    fun isCleartext(scheme: String): Boolean = scheme.equals("http", ignoreCase = true)

    /**
     * Schutz für Code, der beliebige URLs aufruft (z. B. die Standortfreigabe über WebDAV):
     * HTTP nur für private Hosts. Wirft [IOException] sonst.
     */
    fun requireSecureOrLan(url: String) {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("http://")) return
        val host = lower.removePrefix("http://").substringBefore('/').substringBefore('?')
            .substringAfterLast('@').let { if (it.startsWith("[")) it.substringBefore(']') else it.substringBefore(':') }
        if (!isPrivateHost(host)) throw IOException("Unverschlüsseltes HTTP ist nur im lokalen Netz erlaubt.")
    }
}
