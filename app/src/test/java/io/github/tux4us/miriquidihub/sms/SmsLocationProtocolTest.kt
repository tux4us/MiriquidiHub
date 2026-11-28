package io.github.tux4us.miriquidihub.sms

import io.github.tux4us.miriquidihub.sms.SmsLocationProtocol.Message
import io.github.tux4us.miriquidihub.sms.SmsLocationProtocol.Position
import io.github.tux4us.miriquidihub.sms.SmsLocationProtocol.Role
import io.github.tux4us.miriquidihub.sms.SmsLocationProtocol.Status
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmsLocationProtocolTest {

    private val secret = ByteArray(16) { it.toByte() }
    private val now = 1_800_000_000L

    private fun response(position: Position?, status: Status = Status.FRESH, battery: Int? = 57) =
        Message.Response(-12345, now, status, position, battery)

    @Test
    fun request_roundTrip() {
        val msg = Message.Request(Int.MIN_VALUE, now)
        val sms = SmsLocationProtocol.encode(msg, secret, Role.INVITER)
        assertEquals(msg, SmsLocationProtocol.decode(sms, secret, Role.INVITER))
    }

    @Test
    fun response_roundTripWithExtremeCoordinates() {
        for (p in listOf(
            Position(50.5, 13.25, now - 30, 8),
            Position(-89.999999, -179.999999, now, null),
            Position(90.0, 180.0, now, 65534)
        )) {
            val msg = response(p)
            val sms = SmsLocationProtocol.encode(msg, secret, Role.JOINER)
            assertEquals(msg, SmsLocationProtocol.decode(sms, secret, Role.JOINER))
        }
    }

    @Test
    fun response_withoutPositionAndBattery() {
        val msg = response(null, Status.NO_FIX, null)
        val sms = SmsLocationProtocol.encode(msg, secret, Role.JOINER)
        assertEquals(msg, SmsLocationProtocol.decode(sms, secret, Role.JOINER))
    }

    @Test
    fun messages_fitIntoOneSmsWithGsmSafeCharacters() {
        val sms = SmsLocationProtocol.encode(response(Position(-45.0, -120.0, now, 100)), secret, Role.JOINER)
        assertTrue("Länge ${sms.length}", sms.length <= 160)
        assertTrue(Regex("MH1:[A-Za-z0-9_-]+").matches(sms))
    }

    @Test
    fun sameMessage_encryptsDifferently() {
        val msg = Message.Request(1, now)
        assertNotEquals(
            SmsLocationProtocol.encode(msg, secret, Role.INVITER),
            SmsLocationProtocol.encode(msg, secret, Role.INVITER)
        )
    }

    @Test
    fun decode_rejectsWrongKeyAndReflectedMessage() {
        val sms = SmsLocationProtocol.encode(Message.Request(1, now), secret, Role.INVITER)
        assertNull(SmsLocationProtocol.decode(sms, ByteArray(16) { 9 }, Role.INVITER))
        assertNull(SmsLocationProtocol.decode(sms, secret, Role.JOINER))
    }

    @Test
    fun decode_rejectsEveryManipulatedCharacter() {
        val sms = SmsLocationProtocol.encode(Message.Request(1, now), secret, Role.INVITER)
        val original = Base64Url.decode(sms.removePrefix("MH1:"))!!
        for (i in original.indices) {
            val changed = original.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull(SmsLocationProtocol.decode("MH1:" + Base64Url.encode(changed), secret, Role.INVITER))
        }
    }

    @Test
    fun decode_ignoresForeignOrBrokenText() {
        for (text in listOf("", "Hallo", "MH1:", "MH1:!!!!", "MH1:abc", "MH1:" + "A".repeat(300))) {
            assertNull(SmsLocationProtocol.decode(text, secret, Role.INVITER))
        }
    }

    @Test
    fun peekPairId_matchesSecret() {
        val sms = SmsLocationProtocol.encode(Message.Request(1, now), secret, Role.INVITER)
        assertEquals(SmsLocationProtocol.pairId(secret), SmsLocationProtocol.peekPairId("  $sms\n"))
    }

    @Test
    fun base64Url_matchesJdk() {
        val rnd = Random(1)
        for (len in 0..70) {
            val data = rnd.nextBytes(len)
            val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)
            assertEquals(expected, Base64Url.encode(data))
            assertArrayEquals(data, Base64Url.decode(expected))
        }
    }

    @Test
    fun pairingCode_roundTripAndTolerantInput() {
        val rnd = Random(2)
        repeat(500) {
            val s = rnd.nextBytes(16)
            val code = PairingCode.format(s)
            assertEquals(29, code.replace("-", "").length)
            assertArrayEquals(s, PairingCode.parseCode(code))
            val sloppy = code.lowercase().replace("-", " ").replace('0', 'o').replace('1', 'l')
            assertArrayEquals(s, PairingCode.parseCode(sloppy))
        }
    }

    @Test
    fun pairingCode_detectsSingleTypos() {
        val code = PairingCode.format(secret).replace("-", "")
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var accepted = 0
        var total = 0
        for (i in code.indices) for (c in alphabet) {
            if (c == code[i]) continue
            total++
            if (PairingCode.parseCode(code.substring(0, i) + c + code.substring(i + 1)) != null) accepted++
        }
        // 16-Bit-Prüfsumme: unerkannte Fehler sind praktisch ausgeschlossen.
        assertTrue("$accepted von $total", accepted <= 1)
        assertNull(PairingCode.parseCode(code.dropLast(1)))
        assertNull(PairingCode.parseCode(code + "A"))
    }

    @Test
    fun invite_fromQrContent() {
        val invite = PairingCode.parseInvite(PairingCode.qrContent(secret, "+49 171 2345678", "Anna|B"))
        assertNotNull(invite)
        assertArrayEquals(secret, invite!!.secret)
        assertEquals("+491712345678", invite.number)
        assertEquals("Anna B", invite.name)
        assertNull(PairingCode.parseInvite("MHPAIR1|falsch|123456|x"))
        assertNull(PairingCode.parseInvite("https://example.org"))
    }

    @Test
    fun phoneNumbers_normalize() {
        assertEquals("+491712345678", PhoneNumbers.normalize("0049 (171) 234-5678"))
        assertEquals("01712345678", PhoneNumbers.normalize("0171/2345678"))
        assertNull(PhoneNumbers.normalize("abc"))
        assertNull(PhoneNumbers.normalize("12"))
        assertNull(PhoneNumbers.normalize("*100#"))
    }

    @Test
    fun rules_expiryAndClockSkew() {
        val r = SmsLocationRules
        assertEquals(SmsLocationRules.Verdict.ACCEPT, r.checkRequest(1, now - r.MAX_REQUEST_AGE_S, now, emptyList()))
        assertEquals(SmsLocationRules.Verdict.EXPIRED, r.checkRequest(1, now - r.MAX_REQUEST_AGE_S - 1, now, emptyList()))
        assertEquals(SmsLocationRules.Verdict.EXPIRED, r.checkRequest(1, now + r.MAX_CLOCK_AHEAD_S + 1, now, emptyList()))
    }

    @Test
    fun rules_replayStaysBlockedForWholeValidityWindow() {
        val r = SmsLocationRules
        val sent = now + r.MAX_CLOCK_AHEAD_S
        val seen = listOf(SmsLocationRules.Seen(7, sent, now))
        var t = now + r.MIN_ANSWER_GAP_S
        while (t <= sent + r.MAX_REQUEST_AGE_S + 7200) {
            assertNotEquals(SmsLocationRules.Verdict.ACCEPT, r.checkRequest(7, sent, t, r.pruneSeen(seen, t)))
            t += 600
        }
    }

    @Test
    fun rules_rateLimit() {
        val r = SmsLocationRules
        assertEquals(SmsLocationRules.Verdict.RATE_LIMITED, r.checkRequest(2, now, now, listOf(SmsLocationRules.Seen(1, now, now - 59))))
        assertEquals(SmsLocationRules.Verdict.ACCEPT, r.checkRequest(2, now, now, listOf(SmsLocationRules.Seen(1, now, now - 60))))
        val six = (1..6).map { SmsLocationRules.Seen(it, now, now - it * 300L) }
        assertEquals(SmsLocationRules.Verdict.RATE_LIMITED, r.checkRequest(99, now, now, six))
        assertEquals(SmsLocationRules.Verdict.ACCEPT, r.checkRequest(99, now, now + 3600, six))
    }

    @Test
    fun rules_currentFixAndOrdering() {
        assertTrue(SmsLocationRules.isCurrentFix(60_000, 20f))
        assertTrue(!SmsLocationRules.isCurrentFix(121_000, 20f))
        assertTrue(!SmsLocationRules.isCurrentFix(1_000, 51f))
        assertTrue(!SmsLocationRules.isCurrentFix(1_000, null))
        assertTrue(SmsLocationRules.shouldReplacePosition(null, 1))
        assertTrue(!SmsLocationRules.shouldReplacePosition(100, 99))
        assertTrue(SmsLocationRules.shouldReplacePosition(100, 100))
    }
}
