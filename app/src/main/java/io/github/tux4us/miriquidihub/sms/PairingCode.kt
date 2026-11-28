package io.github.tux4us.miriquidihub.sms

/**
 * Kopplungscode zum Abtippen und Inhalt des QR-Codes.
 *
 * Code: 128-Bit-Geheimnis plus 16-Bit-Prüfsumme in Crockford-Base32 (29 Zeichen in Gruppen).
 * Beim Eintippen werden Groß-/Kleinschreibung, Leerzeichen und Bindestriche ignoriert,
 * O wird als 0 gelesen, I und L als 1.
 *
 * QR-Inhalt: `MHPAIR1|<Code>|<Rufnummer des Einladenden>|<Name des Einladenden>`.
 */
object PairingCode {

    private const val QR_PREFIX = "MHPAIR1"
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val CODE_CHARS = 29

    data class Invite(val secret: ByteArray, val number: String?, val name: String?)

    fun format(secret: ByteArray): String {
        require(secret.size == SmsLocationProtocol.SECRET_BYTES)
        val data = secret + SmsLocationProtocol.checksum(secret)
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString().chunked(5).joinToString("-")
    }

    /** null bei Tippfehler (Prüfsumme), falscher Länge oder ungültigen Zeichen. */
    fun parseCode(text: String): ByteArray? {
        val clean = text.uppercase(java.util.Locale.ROOT)
            .filter { !it.isWhitespace() && it != '-' }
            .replace('O', '0').replace('I', '1').replace('L', '1')
        if (clean.length != CODE_CHARS) return null
        val out = ByteArray(SmsLocationProtocol.SECRET_BYTES + 2)
        var buffer = 0
        var bits = 0
        var pos = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                if (pos >= out.size) return null
                out[pos++] = (buffer shr bits).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        // Das letzte Zeichen enthält Füllbits, die 0 sein müssen.
        if (pos != out.size || buffer != 0) return null
        val secret = out.copyOf(SmsLocationProtocol.SECRET_BYTES)
        val expected = SmsLocationProtocol.checksum(secret)
        return if (out.copyOfRange(secret.size, out.size).contentEquals(expected)) secret else null
    }

    fun qrContent(secret: ByteArray, number: String, name: String): String =
        listOf(QR_PREFIX, format(secret), number.replace("|", ""), name.replace("|", " ")).joinToString("|")

    /** Akzeptiert den vollständigen QR-Inhalt oder nur den Code. */
    fun parseInvite(text: String): Invite? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("$QR_PREFIX|")) {
            return parseCode(trimmed)?.let { Invite(it, null, null) }
        }
        val parts = trimmed.split('|', limit = 4)
        if (parts.size != 4) return null
        val secret = parseCode(parts[1]) ?: return null
        return Invite(secret, PhoneNumbers.normalize(parts[2]), parts[3].take(40).ifBlank { null })
    }
}

object PhoneNumbers {
    /** Entfernt Trennzeichen. null, wenn keine plausible Rufnummer übrig bleibt. */
    fun normalize(input: String): String? {
        val compact = input.trim().filter { it !in " -/(). " }
        val withPlus = if (compact.startsWith("00")) "+" + compact.drop(2) else compact
        return if (Regex("\\+?[0-9]{5,15}").matches(withPlus)) withPlus else null
    }
}
