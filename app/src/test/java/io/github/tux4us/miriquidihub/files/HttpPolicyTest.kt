package io.github.tux4us.miriquidihub.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HttpPolicyTest {

    @Test
    fun privateIpv4Ranges() {
        listOf("192.168.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.254", "127.0.0.1", "169.254.1.1", "100.64.0.5")
            .forEach { assertTrue(it, HttpPolicy.isPrivateHost(it)) }
    }

    @Test
    fun publicIpv4Ranges() {
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.128.0.1", "1.2.3.4", "300.1.1.1")
            .forEach { assertFalse(it, HttpPolicy.isPrivateHost(it)) }
    }

    @Test
    fun localHostnames() {
        listOf("localhost", "nas", "raspberrypi", "nas.local", "server.lan", "fritz.box", "nas.fritz.box", "x.home.arpa")
            .forEach { assertTrue(it, HttpPolicy.isPrivateHost(it)) }
    }

    @Test
    fun publicHostnames() {
        listOf("cloud.example.org", "example.com", "192.168.0.1.evil.com", "localhost.example.com")
            .forEach { assertFalse(it, HttpPolicy.isPrivateHost(it)) }
    }

    @Test
    fun ipv6() {
        assertTrue(HttpPolicy.isPrivateHost("::1"))
        assertTrue(HttpPolicy.isPrivateHost("fe80::1"))
        assertTrue(HttpPolicy.isPrivateHost("fd12:3456::1"))
        assertTrue(HttpPolicy.isPrivateHost("[fd00::5]"))
        assertTrue(HttpPolicy.isPrivateHost("::ffff:192.168.1.5"))
        assertFalse(HttpPolicy.isPrivateHost("2001:4860:4860::8888"))
        assertFalse(HttpPolicy.isPrivateHost("::ffff:8.8.8.8"))
    }

    @Test
    fun check_appliesSchemeRules() {
        assertEquals(HttpPolicy.Verdict.OK, HttpPolicy.check("https", "example.org", false))
        assertEquals(HttpPolicy.Verdict.OK, HttpPolicy.check("http", "192.168.1.2", false))
        assertEquals(HttpPolicy.Verdict.NEEDS_CONFIRMATION, HttpPolicy.check("http", "example.org", false))
        assertEquals(HttpPolicy.Verdict.OK, HttpPolicy.check("http", "example.org", true))
        assertEquals(HttpPolicy.Verdict.INVALID, HttpPolicy.check("ftp", "192.168.1.2", true))
    }

    @Test
    fun requireSecureOrLan_blocksPublicHttpOnly() {
        HttpPolicy.requireSecureOrLan("https://example.org/dav")
        HttpPolicy.requireSecureOrLan("http://192.168.1.2:8080/dav")
        HttpPolicy.requireSecureOrLan("http://user:pw@nas.local/dav")
        HttpPolicy.requireSecureOrLan("http://[fd00::1]:8080/x")
        var blocked = false
        try { HttpPolicy.requireSecureOrLan("http://example.org/dav") } catch (e: IOException) { blocked = true }
        assertTrue(blocked)
        blocked = false
        try { HttpPolicy.requireSecureOrLan("http://192.168.1.2@example.org/dav") } catch (e: IOException) { blocked = true }
        assertTrue("userinfo darf den Host nicht verschleiern", blocked)
    }
}
