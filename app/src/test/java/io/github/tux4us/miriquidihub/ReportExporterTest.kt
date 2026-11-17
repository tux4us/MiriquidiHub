package io.github.tux4us.miriquidihub

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExporterTest {

    private val labels = ExportLabels(
        heading = "Geräte-Report",
        createdWith = { v -> "Erstellt mit MiriquidiHub $v" },
        note = "Beschreibung",
        shelter = "Shelter",
        securityMode = "Sicherheitsmodus",
        vpn = "VPN",
        yes = "Ja",
        no = "Nein",
        affectedApps = "Betroffene Apps",
        imagesAttached = { n -> "Bilder: $n" },
        systemData = "Systemdaten"
    )

    private val report = DeviceReport(
        id = "id-1",
        timestamp = 0L,
        title = "Absturz beim Start",
        note = "Die App schließt sich sofort.",
        specs = "--- BASIS DATEN ---\nHERSTELLER: Volla\nMODELL: Phone 22",
        shelterActive = false,
        securityModeActive = true,
        vpnActive = false,
        imagePaths = listOf("/data/user/0/app/files/report_img_1.jpg"),
        selectedApps = listOf(AppInfo("Browser", "org.example.browser", "1.2.3"))
    )

    @Test
    fun markdown_containsTitleNoteAppsAndSpecsBlock() {
        val md = ReportExporter.toMarkdown(report, "0.2.0", labels)
        assertTrue(md.startsWith("# Geräte-Report"))
        assertTrue(md.contains("**Absturz beim Start**"))
        assertTrue(md.contains("Erstellt mit MiriquidiHub 0.2.0"))
        assertTrue(md.contains("- Sicherheitsmodus: Ja"))
        assertTrue(md.contains("- Browser (`org.example.browser`) 1.2.3"))
        assertTrue(md.contains("```text\n--- BASIS DATEN ---"))
        assertTrue(md.contains("Bilder: 1"))
    }

    @Test
    fun markdown_neverContainsLocalImagePaths() {
        assertFalse(ReportExporter.toMarkdown(report, "0.2.0", labels).contains("report_img_1.jpg"))
    }

    @Test
    fun json_isValidGroupsSpecsAndOmitsPaths() {
        val json = ReportExporter.toJson(report, "0.2.0")
        assertFalse(json.contains("report_img_1.jpg"))
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(1, root.get("schema").asInt)
        assertEquals("1970-01-01T00:00:00Z", root.get("createdAt").asString)
        assertEquals(1, root.get("imageCount").asInt)
        val base = root.getAsJsonObject("specs").getAsJsonObject("BASIS DATEN")
        assertEquals("Volla", base.get("HERSTELLER").asString)
        assertEquals("org.example.browser", root.getAsJsonArray("affectedApps")[0].asJsonObject.get("packageName").asString)
    }
}
