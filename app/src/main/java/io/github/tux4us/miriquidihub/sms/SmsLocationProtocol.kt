package io.github.tux4us.miriquidihub.sms

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * Binäres SMS-Protokoll der Ortung ohne Internet.
 *
 * Aufbau: `MH1:` + Base64url( pairId[4] | nonce[12] | AES-256-GCM(Nutzdaten) mit 16-Byte-Tag ).
 * Jede Nachricht passt in eine SMS (GSM-7, höchstens [MAX_SMS_CHARS] Zeichen).
 *
 * Schlüssel: Aus dem 128-Bit-Kopplungsgeheimnis wird je Senderrolle ein eigener 256-Bit-Schlüssel
 * per HMAC-SHA256 abgeleitet. Eine zurückgespiegelte eigene Nachricht ist dadurch ungültig.
 *
 * Reine JVM-Logik ohne Android-Klassen, damit sie als Unit-Test prüfbar ist.
 */
object SmsLocationProtocol {

    const val PREFIX = "MH1:"
    const val MAX_SMS_CHARS = 160
    const val SECRET_BYTES = 16
    private const val PAIR_ID_BYTES = 4
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TYPE_REQUEST = 1
    private const val TYPE_RESPONSE = 2
    private const val REQUEST_BYTES = 9
    private const val RESPONSE_BYTES = 25
    private const val UNKNOWN_BYTE = 255
    private const val UNKNOWN_SHORT = 65535

    /** Rolle des Absenders: [INVITER] hat die Kopplung erzeugt, [JOINER] hat den Code übernommen. */
    enum class Role(val code: Int) {
        INVITER(0), JOINER(1);

        val peer: Role get() = if (this == INVITER) JOINER else INVITER
    }

    enum class Status(val code: Int) {
        /** Frischer Standort, keine weitere SMS. */
        FRESH(1),
        /** Letzter bekannter Standort, ein frischer Fix folgt in einer zweiten SMS. */
        LAST_KNOWN_MORE(2),
        /** Letzter bekannter Standort, es folgt nichts mehr (kein frischer Fix möglich). */
        LAST_KNOWN_FINAL(3),
        /** Kein Standort verfügbar. */
        NO_FIX(4),
        /** Standortermittlung ist am georteten Gerät ausgeschaltet. */
        LOCATION_OFF(5),
        /** Der App fehlt die Standortberechtigung. */
        NO_PERMISSION(6);

        companion object {
            fun fromCode(code: Int): Status? = values().firstOrNull { it.code == code }
        }
    }

    sealed class Message {
        abstract val requestId: Int
        abstract val sentAtSeconds: Long

        data class Request(override val requestId: Int, override val sentAtSeconds: Long) : Message()

        data class Response(
            override val requestId: Int,
            override val sentAtSeconds: Long,
            val status: Status,
            val position: Position?,
            val batteryPercent: Int?
        ) : Message()
    }

    /** [fixTimeSeconds] ist der Zeitpunkt der Positionsmessung, nicht der des Versands. */
    data class Position(
        val lat: Double,
        val lon: Double,
        val fixTimeSeconds: Long,
        val accuracyMeters: Int?
    )

    fun newSecret(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SECRET_BYTES).also { random.nextBytes(it) }

    /** Kennung der Kopplung. Aus dem Geheimnis abgeleitet, auf beiden Geräten gleich. */
    fun pairId(secret: ByteArray): String =
        hex(hmac(secret, "MH-SMS-v1 pair-id").copyOf(PAIR_ID_BYTES))

    fun looksLikeMessage(text: String): Boolean = text.trim().startsWith(PREFIX)

    /** Kennung aus einer empfangenen SMS, um die passende Kopplung zu finden. */
    fun peekPairId(text: String): String? {
        val raw = rawBytes(text) ?: return null
        return hex(raw.copyOf(PAIR_ID_BYTES))
    }

    fun encode(
        message: Message,
        secret: ByteArray,
        sender: Role,
        random: SecureRandom = SecureRandom()
    ): String {
        val header = unhex(pairId(secret))
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, directionKey(secret, sender), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(header))
        val ct = cipher.doFinal(serialize(message))
        return PREFIX + Base64Url.encode(header + nonce + ct)
    }

    /**
     * Entschlüsselt eine Nachricht, die von der Rolle [sender] stammen muss.
     * null bei falschem Schlüssel, falscher Richtung, Manipulation oder defektem Format.
     */
    fun decode(text: String, secret: ByteArray, sender: Role): Message? {
        val raw = rawBytes(text) ?: return null
        val header = raw.copyOf(PAIR_ID_BYTES)
        if (hex(header) != pairId(secret)) return null
        return try {
            val nonce = raw.copyOfRange(PAIR_ID_BYTES, PAIR_ID_BYTES + NONCE_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, directionKey(secret, sender), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(header))
            deserialize(cipher.doFinal(raw, PAIR_ID_BYTES + NONCE_BYTES, raw.size - PAIR_ID_BYTES - NONCE_BYTES))
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private fun rawBytes(text: String): ByteArray? {
        val t = text.trim()
        if (!t.startsWith(PREFIX) || t.length > MAX_SMS_CHARS) return null
        val raw = Base64Url.decode(t.substring(PREFIX.length)) ?: return null
        return if (raw.size > PAIR_ID_BYTES + NONCE_BYTES + TAG_BITS / 8) raw else null
    }

    private fun serialize(message: Message): ByteArray = when (message) {
        is Message.Request -> Writer(REQUEST_BYTES).apply {
            byte(TYPE_REQUEST)
            int(message.sentAtSeconds.toInt())
            int(message.requestId)
        }.data

        is Message.Response -> Writer(RESPONSE_BYTES).apply {
            byte(TYPE_RESPONSE)
            int(message.sentAtSeconds.toInt())
            int(message.requestId)
            byte(message.status.code)
            val p = message.position
            int(p?.let { (it.lat * 1_000_000).roundToInt() } ?: Int.MIN_VALUE)
            int(p?.let { (it.lon * 1_000_000).roundToInt() } ?: Int.MIN_VALUE)
            int(p?.fixTimeSeconds?.toInt() ?: 0)
            short(p?.accuracyMeters?.coerceIn(0, UNKNOWN_SHORT - 1) ?: UNKNOWN_SHORT)
            byte(message.batteryPercent?.takeIf { it in 0..100 } ?: UNKNOWN_BYTE)
        }.data
    }

    private fun deserialize(data: ByteArray): Message? {
        if (data.isEmpty()) return null
        val r = Reader(data)
        return when (r.byte()) {
            TYPE_REQUEST -> {
                if (data.size != REQUEST_BYTES) return null
                val sent = r.unsignedInt()
                Message.Request(r.int(), sent)
            }
            TYPE_RESPONSE -> {
                if (data.size != RESPONSE_BYTES) return null
                val sent = r.unsignedInt()
                val requestId = r.int()
                val status = Status.fromCode(r.byte()) ?: return null
                val latE6 = r.int()
                val lonE6 = r.int()
                val fixTime = r.unsignedInt()
                val accuracy = r.short()
                val battery = r.byte()
                val position = if (latE6 == Int.MIN_VALUE || lonE6 == Int.MIN_VALUE) {
                    null
                } else {
                    val lat = latE6 / 1_000_000.0
                    val lon = lonE6 / 1_000_000.0
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                    Position(lat, lon, fixTime, accuracy.takeIf { it != UNKNOWN_SHORT })
                }
                Message.Response(requestId, sent, status, position, battery.takeIf { it in 0..100 })
            }
            else -> null
        }
    }

    private fun aad(header: ByteArray): ByteArray = PREFIX.toByteArray(Charsets.US_ASCII) + header

    private fun directionKey(secret: ByteArray, sender: Role) =
        SecretKeySpec(hmac(secret, "MH-SMS-v1 key " + sender.code), "AES")

    private fun hmac(secret: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.US_ASCII))
    }

    internal fun checksum(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data).copyOf(2)

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    internal fun unhex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private class Writer(size: Int) {
        val data = ByteArray(size)
        private var pos = 0
        fun byte(v: Int) { data[pos++] = v.toByte() }
        fun short(v: Int) { byte(v ushr 8); byte(v) }
        fun int(v: Int) { short(v ushr 16); short(v) }
    }

    private class Reader(private val data: ByteArray) {
        private var pos = 0
        fun byte(): Int = data[pos++].toInt() and 0xFF
        fun short(): Int = (byte() shl 8) or byte()
        fun int(): Int = (short() shl 16) or short()
        fun unsignedInt(): Long = int().toLong() and 0xFFFFFFFFL
    }
}

/** Base64url ohne Auffüllzeichen. Eigene Umsetzung, weil java.util.Base64 erst ab API 26 verfügbar ist. */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(data: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                out.append(ALPHABET[(buffer shr bits) and 0x3F])
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (6 - bits)) and 0x3F])
        return out.toString()
    }

    fun decode(text: String): ByteArray? {
        if (text.length % 4 == 1) return null
        val out = ByteArray(text.length * 6 / 8)
        var buffer = 0
        var bits = 0
        var pos = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[pos++] = (buffer shr bits).toByte()
            }
        }
        return out
    }
}
