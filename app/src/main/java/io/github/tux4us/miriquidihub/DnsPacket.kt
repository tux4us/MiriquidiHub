package io.github.tux4us.miriquidihub

import java.io.ByteArrayOutputStream

/**
 * Minimale DNS-Nachrichten (RFC 1035) für den Vergleich der DNS-Server des Netzwerks.
 * Reine Logik ohne Android-Abhängigkeit.
 */
object DnsPacket {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    data class Response(val id: Int, val rcode: Int, val answers: Int, val truncated: Boolean) {
        val rcodeName: String
            get() = when (rcode) {
                0 -> "NOERROR"
                1 -> "FORMERR"
                2 -> "SERVFAIL"
                3 -> "NXDOMAIN"
                4 -> "NOTIMP"
                5 -> "REFUSED"
                else -> "RCODE$rcode"
            }
    }

    /** Baut eine Standardabfrage mit gesetztem "Recursion Desired"-Bit. */
    fun buildQuery(host: String, id: Int, type: Int = TYPE_A): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01) // Flags: RD
        out.write(0x00)
        out.write(0x00); out.write(0x01) // QDCOUNT = 1
        repeat(6) { out.write(0x00) } // ANCOUNT, NSCOUNT, ARCOUNT = 0
        for (label in host.trim().trim('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.isNotEmpty() && bytes.size <= 63) { "Ungültiges Label: $label" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)
        out.write((type shr 8) and 0xFF); out.write(type and 0xFF)
        out.write(0x00); out.write(0x01) // Klasse IN
        return out.toByteArray()
    }

    /** Liest Kopfzeile einer Antwort. null, wenn das Paket zu kurz ist oder keine Antwort (QR-Bit) darstellt. */
    fun parseResponse(data: ByteArray, length: Int = data.size): Response? {
        if (length < 12) return null
        fun u16(i: Int) = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        val flags = u16(2)
        if (flags and 0x8000 == 0) return null
        return Response(
            id = u16(0),
            rcode = flags and 0x000F,
            answers = u16(6),
            truncated = flags and 0x0200 != 0
        )
    }
}
