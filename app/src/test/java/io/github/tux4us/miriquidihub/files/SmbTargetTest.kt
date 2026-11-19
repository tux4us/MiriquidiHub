package io.github.tux4us.miriquidihub.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbTargetTest {

    @Test fun plainHost() {
        assertEquals(SmbTarget("nas", 445, null), SmbTarget.parse("nas"))
        assertEquals(SmbTarget("nas.local", 445, null), SmbTarget.parse("  nas.local  "))
    }

    @Test fun ipWithPort() {
        assertEquals(SmbTarget("192.168.1.10", 4445, null), SmbTarget.parse("192.168.1.10:4445"))
    }

    @Test fun smbUrlWithShare() {
        assertEquals(SmbTarget("nas", 445, "Dokumente"), SmbTarget.parse("smb://nas/Dokumente"))
        assertEquals(SmbTarget("nas", 445, "Dokumente"), SmbTarget.parse("SMB://nas/Dokumente/Unterordner"))
    }

    @Test fun uncPath() {
        assertEquals(SmbTarget("nas", 445, "Fotos"), SmbTarget.parse("\\\\nas\\Fotos"))
    }

    @Test fun credentialsInUrlAreIgnored() {
        assertEquals(SmbTarget("nas", 445, "x"), SmbTarget.parse("smb://anna:geheim@nas/x"))
    }

    @Test fun ipv6() {
        assertEquals(SmbTarget("fe80::1", 445, null), SmbTarget.parse("[fe80::1]"))
        assertEquals(SmbTarget("fd00::5", 4445, null), SmbTarget.parse("[fd00::5]:4445"))
    }

    @Test fun invalidInput() {
        assertNull(SmbTarget.parse(""))
        assertNull(SmbTarget.parse("   "))
        assertNull(SmbTarget.parse("smb://"))
        assertNull(SmbTarget.parse("nas:abc"))
        assertNull(SmbTarget.parse("nas:0"))
        assertNull(SmbTarget.parse("nas:70000"))
        assertNull(SmbTarget.parse("na s"))
        assertNull(SmbTarget.parse("[fe80::1"))
        assertNull(SmbTarget.parse("[fe80::1]x"))
    }

    @Test fun hostSpec() {
        assertEquals("nas", SmbTarget("nas", 445, null).hostSpec())
        assertEquals("nas:4445", SmbTarget("nas", 4445, null).hostSpec())
        assertEquals("[fe80::1]", SmbTarget("fe80::1", 445, null).hostSpec())
        assertEquals("[fe80::1]:4445", SmbTarget("fe80::1", 4445, null).hostSpec())
    }

    @Test fun hostSpecRoundTrip() {
        for (t in listOf(SmbTarget("nas.local", 445, null), SmbTarget("10.0.0.2", 1445, null), SmbTarget("fd00::5", 445, null))) {
            assertEquals(t, SmbTarget.parse(t.hostSpec()))
        }
    }

    @Test fun shareValidation() {
        assertTrue(SmbTarget.isValidShare("Dokumente"))
        assertTrue(SmbTarget.isValidShare("Daten 2"))
        assertTrue(SmbTarget.isValidShare("backup$"))
        assertFalse(SmbTarget.isValidShare(""))
        assertFalse(SmbTarget.isValidShare("  "))
        assertFalse(SmbTarget.isValidShare("a/b"))
        assertFalse(SmbTarget.isValidShare("a\\b"))
        assertFalse(SmbTarget.isValidShare("a:b"))
        assertFalse(SmbTarget.isValidShare("a\u0001b"))
        assertFalse(SmbTarget.isValidShare("x".repeat(81)))
    }
}
