package io.github.tux4us.miriquidihub

import com.google.gson.Gson
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ende-zu-Ende-Verschlüsselung der Standortdateien auf dem WebDAV-Server.
 *
 * Verfahren: AES-256-GCM, Schlüssel per PBKDF2-HMAC-SHA256 (100 000 Iterationen) aus der
 * gemeinsamen Passphrase und einem zufälligen 16-Byte-Salt je Nachricht. Die Datei enthält
 * `{"v":2,"salt":…,"iv":…,"ct":…}` (hexadezimal). PBKDF2 ist über HmacSHA256 selbst
 * implementiert, weil `PBKDF2WithHmacSHA256` erst ab Android 8 (API 26) verfügbar ist.
 *
 * Reine JVM-Logik ohne Android-Klassen, damit sie als Unit-Test prüfbar ist.
 */
object PayloadCrypto {

    const val VERSION = 2
    const val ITERATIONS = 100_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private val AAD = "MiriquidiHub-location-v2".toByteArray(Charsets.UTF_8)
    private val gson = Gson()

    private data class Envelope(val v: Int, val salt: String, val iv: String, val ct: String)

    /** True, wenn [text] ein verschlüsselter Umschlag (und kein Klartext-JSON) ist. */
    fun isEnvelope(text: String): Boolean = try {
        val e = gson.fromJson(text, Envelope::class.java)
        e != null && e.v >= 2 && !e.ct.isNullOrEmpty()
    } catch (ex: Exception) {
        false
    }

    fun encrypt(plaintext: String, passphrase: String, random: SecureRandom = SecureRandom()): String {
        require(passphrase.isNotEmpty()) { "Passphrase fehlt" }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val key = pbkdf2Sha256(passphrase.toByteArray(Charsets.UTF_8), salt, ITERATIONS, KEY_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return gson.toJson(Envelope(VERSION, toHex(salt), toHex(iv), toHex(ct)))
    }

    /** Entschlüsselt einen Umschlag. null bei falscher Passphrase, Manipulation oder defektem Format. */
    fun decrypt(envelopeJson: String, passphrase: String): String? {
        if (passphrase.isEmpty()) return null
        return try {
            val e = gson.fromJson(envelopeJson, Envelope::class.java) ?: return null
            if (e.v != VERSION) return null
            val key = pbkdf2Sha256(passphrase.toByteArray(Charsets.UTF_8), fromHex(e.salt), ITERATIONS, KEY_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, fromHex(e.iv)))
            cipher.updateAAD(AAD)
            String(cipher.doFinal(fromHex(e.ct)), Charsets.UTF_8)
        } catch (ex: GeneralSecurityException) {
            null
        } catch (ex: IllegalArgumentException) {
            null
        } catch (ex: Exception) {
            null
        }
    }

    /** PBKDF2 nach RFC 8018 mit HMAC-SHA256. */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (length + hLen - 1) / hLen
        val out = ByteArray(length)
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val offset = (block - 1) * hLen
            System.arraycopy(t, 0, out, offset, minOf(hLen, length - offset))
        }
        return out
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Ungerade Hex-Länge" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
