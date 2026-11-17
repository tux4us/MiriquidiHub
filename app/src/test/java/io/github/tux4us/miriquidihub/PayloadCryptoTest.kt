package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadCryptoTest {

    @Test
    fun pbkdf2_matchesKnownVectors() {
        val one = PayloadCrypto.pbkdf2Sha256("password".toByteArray(), "salt".toByteArray(), 1, 32)
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", PayloadCrypto.toHex(one))
        val two = PayloadCrypto.pbkdf2Sha256("password".toByteArray(), "salt".toByteArray(), 2, 32)
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43", PayloadCrypto.toHex(two))
    }

    @Test
    fun roundTrip_returnsOriginal() {
        val json = """{"deviceId":"x","lat":52.5,"lon":13.4}"""
        val encrypted = PayloadCrypto.encrypt(json, "geheim")
        assertTrue(PayloadCrypto.isEnvelope(encrypted))
        assertFalse(encrypted.contains("52.5"))
        assertEquals(json, PayloadCrypto.decrypt(encrypted, "geheim"))
    }

    @Test
    fun decrypt_failsWithWrongOrEmptyPassphrase() {
        val encrypted = PayloadCrypto.encrypt("data", "richtig")
        assertNull(PayloadCrypto.decrypt(encrypted, "falsch"))
        assertNull(PayloadCrypto.decrypt(encrypted, ""))
    }

    @Test
    fun decrypt_detectsTampering() {
        val encrypted = PayloadCrypto.encrypt("data", "pw")
        val ctStart = encrypted.indexOf("\"ct\":\"") + 6
        val flipped = if (encrypted[ctStart] == '0') '1' else '0'
        val tampered = encrypted.substring(0, ctStart) + flipped + encrypted.substring(ctStart + 1)
        assertNull(PayloadCrypto.decrypt(tampered, "pw"))
    }

    @Test
    fun encrypt_usesFreshSaltAndIv() {
        assertNotEquals(PayloadCrypto.encrypt("data", "pw"), PayloadCrypto.encrypt("data", "pw"))
    }

    @Test
    fun isEnvelope_distinguishesPlaintextPayloads() {
        assertFalse(PayloadCrypto.isEnvelope("""{"deviceId":"x","label":"a","lat":1.0,"lon":2.0,"timestamp":5}"""))
        assertFalse(PayloadCrypto.isEnvelope("kein json"))
    }

    @Test
    fun hex_roundTrip() {
        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        assertEquals(PayloadCrypto.toHex(bytes), "00017f80ff")
        assertTrue(bytes.contentEquals(PayloadCrypto.fromHex("00017f80ff")))
    }
}
