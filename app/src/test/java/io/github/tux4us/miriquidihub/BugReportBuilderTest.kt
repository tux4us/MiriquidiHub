package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportBuilderTest {

    private val labels = BugReportLabels(
        "Beschreibung", "Schritte", "Erwartet", "Tatsächlich", "Umgebung", "App-Version", "Gerät", "Hardware", "Sicherheit"
    )

    @Test
    fun numberedSteps_normalizesBullets() {
        assertEquals("1. Öffnen\n2. Tippen\n3. Warten", BugReportBuilder.numberedSteps("1. Öffnen\n- Tippen\n\n3) Warten"))
        assertEquals("", BugReportBuilder.numberedSteps("  \n "))
    }

    @Test
    fun build_containsSectionsAndDetails() {
        val md = BugReportBuilder.build(
            BugReportInput("Absturz", "Die App stürzt ab", "Start\nTippen", "Kein Absturz", "Absturz", "0.2.0", deviceBlock = "Modell: X"),
            labels
        )
        assertTrue(md.startsWith("## Absturz\n"))
        assertTrue(md.contains("### Schritte\n\n1. Start\n2. Tippen"))
        assertTrue(md.contains("- App-Version: 0.2.0"))
        assertTrue(md.contains("<summary>Gerät</summary>"))
        assertTrue(md.contains("Modell: X"))
        assertFalse(md.contains("<summary>Hardware</summary>"))
    }

    @Test
    fun build_skipsEmptySections() {
        val md = BugReportBuilder.build(BugReportInput("", "", "", "", "", "1"), labels)
        assertTrue(md.startsWith("## –"))
        assertFalse(md.contains("### Beschreibung"))
        assertFalse(md.contains("### Schritte"))
    }
}
