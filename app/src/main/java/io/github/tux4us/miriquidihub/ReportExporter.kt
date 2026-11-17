package io.github.tux4us.miriquidihub

import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Beschriftungen für den Markdown-Export (aus Ressourcen übergeben, damit die Klasse ohne Context testbar bleibt). */
data class ExportLabels(
    val heading: String,
    val createdWith: (String) -> String,
    val note: String,
    val shelter: String,
    val securityMode: String,
    val vpn: String,
    val yes: String,
    val no: String,
    val affectedApps: String,
    val imagesAttached: (Int) -> String,
    val systemData: String
)

/**
 * Exportiert einen Geräte-Report als Markdown (für Issue-Tracker wie GitHub/GitLab) oder JSON.
 * Der Export enthält bewusst keine lokalen Dateipfade der angehängten Bilder.
 */
object ReportExporter {

    fun toMarkdown(report: DeviceReport, appVersion: String, labels: ExportLabels): String {
        val sb = StringBuilder()
        sb.append("# ").append(labels.heading).append("\n\n")
        if (report.title.isNotBlank()) sb.append("**").append(report.title.trim()).append("**\n\n")
        sb.append("_").append(labels.createdWith(appVersion)).append("_\n\n")
        if (report.note.isNotBlank()) {
            sb.append("## ").append(labels.note).append("\n\n").append(report.note.trim()).append("\n\n")
        }
        fun yn(b: Boolean) = if (b) labels.yes else labels.no
        sb.append("- ").append(labels.shelter).append(": ").append(yn(report.shelterActive)).append('\n')
        sb.append("- ").append(labels.securityMode).append(": ").append(yn(report.securityModeActive)).append('\n')
        sb.append("- ").append(labels.vpn).append(": ").append(yn(report.vpnActive)).append("\n\n")
        if (report.selectedApps.isNotEmpty()) {
            sb.append("## ").append(labels.affectedApps).append("\n\n")
            report.selectedApps.forEach { sb.append("- ").append(it.name).append(" (`").append(it.packageName).append("`) ").append(it.version).append('\n') }
            sb.append('\n')
        }
        if (report.imagePaths.isNotEmpty()) {
            sb.append(labels.imagesAttached(report.imagePaths.size)).append("\n\n")
        }
        sb.append("<details>\n<summary>").append(labels.systemData).append("</summary>\n\n```text\n")
        sb.append(report.specs.trim()).append("\n```\n\n</details>\n")
        return sb.toString()
    }

    /** JSON-Struktur des Exports. Schema-Version erlaubt spätere Erweiterungen. */
    data class ExportDocument(
        val schema: Int,
        val app: String,
        val appVersion: String,
        val createdAt: String,
        val title: String,
        val note: String,
        val shelterActive: Boolean,
        val securityModeActive: Boolean,
        val vpnActive: Boolean,
        val affectedApps: List<AppInfo>,
        val imageCount: Int,
        val specs: Map<String, Map<String, String>>
    )

    fun toJson(report: DeviceReport, appVersion: String): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val specs = LinkedHashMap<String, MutableMap<String, String>>()
        SpecParser.parse(report.specs).forEach {
            specs.getOrPut(it.section) { LinkedHashMap() }[it.key] = it.value
        }
        val document = ExportDocument(
            schema = 1,
            app = "MiriquidiHub",
            appVersion = appVersion,
            createdAt = iso.format(Date(report.timestamp)),
            title = report.title,
            note = report.note,
            shelterActive = report.shelterActive,
            securityModeActive = report.securityModeActive,
            vpnActive = report.vpnActive,
            affectedApps = report.selectedApps,
            imageCount = report.imagePaths.size,
            specs = specs
        )
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(document)
    }
}
