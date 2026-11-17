package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkHostPrefsTest {

    @Test
    fun parse_acceptsSeparatorsAndStripsSchemeAndPath() {
        val r = NetworkHostPrefs.parse("https://F-Droid.org/packages\ngithub.com, example.org;mozilla.org")
        assertEquals(listOf("f-droid.org", "github.com", "example.org", "mozilla.org"), r.valid)
        assertEquals(emptyList<String>(), r.invalid)
    }

    @Test
    fun parse_reportsInvalidEntriesAndDeduplicates() {
        val r = NetworkHostPrefs.parse("example.org example.org localhost -bad.org 1.2.3")
        assertEquals(listOf("example.org"), r.valid)
        assertEquals(listOf("localhost", "-bad.org", "1.2.3"), r.invalid)
    }

    @Test
    fun parse_limitsNumberOfHosts() {
        val text = (1..12).joinToString("\n") { "host$it.example.org" }
        assertEquals(NetworkHostPrefs.MAX_HOSTS, NetworkHostPrefs.parse(text).valid.size)
    }
}
