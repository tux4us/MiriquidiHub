package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecParserTest {

    private val sample = """
        Erstellt mit MiriquidiHub v0.2.0

        --- BASIS DATEN ---
        HERSTELLER: Volla
        MODELL: Phone 22

        --- HARDWARE-SELBSTTEST ---
        [OK] Touchscreen: Alle Felder erkannt
        [FAIL] Lautsprecher
    """.trimIndent()

    @Test
    fun parse_readsSectionsAndKeyValues() {
        val entries = SpecParser.parse(sample)
        assertEquals(3, entries.size)
        assertEquals(SpecEntry("BASIS DATEN", "HERSTELLER", "Volla"), entries[0])
        assertEquals(SpecEntry("BASIS DATEN", "MODELL", "Phone 22"), entries[1])
    }

    @Test
    fun parse_movesTestStatusFromKeyToValue() {
        val entry = SpecParser.parse(sample).last()
        assertEquals("HARDWARE-SELBSTTEST", entry.section)
        assertEquals("Touchscreen", entry.key)
        assertEquals("[OK] Alle Felder erkannt", entry.value)
    }

    @Test
    fun parse_ignoresLinesWithoutSeparator() {
        assertTrue(SpecParser.parse("nur Text\n\n--- A ---\nohne Doppelpunkt").isEmpty())
    }

    @Test
    fun diff_detectsChangedAddedRemovedAndUnchanged() {
        val a = "--- S ---\nA: 1\nB: 2\nC: 3"
        val b = "--- S ---\nA: 1\nB: 9\nD: 4"
        val result = SpecParser.diff(a, b)
        assertEquals(1, result.unchanged)
        val byKey = result.changes.associateBy { it.key }
        assertEquals(SpecChange.Kind.CHANGED, byKey.getValue("B").kind)
        assertEquals("2", byKey.getValue("B").old)
        assertEquals("9", byKey.getValue("B").new)
        assertEquals(SpecChange.Kind.REMOVED, byKey.getValue("C").kind)
        assertEquals(SpecChange.Kind.ADDED, byKey.getValue("D").kind)
    }

    @Test
    fun diff_sameKeyInDifferentSectionsIsNotConfused() {
        val result = SpecParser.diff("--- X ---\nK: 1\n--- Y ---\nK: 2", "--- X ---\nK: 1\n--- Y ---\nK: 3")
        assertEquals(1, result.unchanged)
        assertEquals(1, result.changes.size)
        assertEquals("Y", result.changes[0].section)
    }
}
