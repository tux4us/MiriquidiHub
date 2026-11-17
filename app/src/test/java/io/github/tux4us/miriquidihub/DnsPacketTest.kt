package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketTest {

    @Test
    fun buildQuery_hasHeaderQuestionAndLabels() {
        val q = DnsPacket.buildQuery("f-droid.org", 0x1234)
        assertEquals(0x12, q[0].toInt() and 0xFF)
        assertEquals(0x34, q[1].toInt() and 0xFF)
        assertEquals(0x01, q[2].toInt() and 0xFF) // RD
        assertEquals(1, q[5].toInt()) // QDCOUNT
        assertEquals(7, q[12].toInt()) // "f-droid"
        assertEquals('f'.code, q[13].toInt())
        assertEquals(3, q[20].toInt()) // "org"
        assertEquals(0, q[24].toInt()) // Terminator
        assertEquals(DnsPacket.TYPE_A, q[25].toInt() shl 8 or q[26].toInt())
        assertEquals(29, q.size)
    }

    @Test
    fun buildQuery_trailingDotIsIgnored() {
        assertTrue(DnsPacket.buildQuery("example.org.", 1).contentEquals(DnsPacket.buildQuery("example.org", 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildQuery_rejectsEmptyLabel() {
        DnsPacket.buildQuery("example..org", 1)
    }

    @Test
    fun parseResponse_readsIdRcodeAndAnswers() {
        val data = ByteArray(12)
        data[0] = 0xAB.toByte(); data[1] = 0xCD.toByte()
        data[2] = 0x81.toByte() // QR + RD
        data[3] = 0x83.toByte() // RA + RCODE 3
        data[7] = 2
        val r = DnsPacket.parseResponse(data)
        assertNotNull(r)
        assertEquals(0xABCD, r!!.id)
        assertEquals(3, r.rcode)
        assertEquals("NXDOMAIN", r.rcodeName)
        assertEquals(2, r.answers)
    }

    @Test
    fun parseResponse_rejectsQueriesAndShortPackets() {
        val query = DnsPacket.buildQuery("example.org", 5)
        assertNull(DnsPacket.parseResponse(query))
        assertNull(DnsPacket.parseResponse(ByteArray(5)))
    }
}
