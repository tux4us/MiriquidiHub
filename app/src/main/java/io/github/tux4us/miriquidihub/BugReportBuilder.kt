package io.github.tux4us.miriquidihub

/** Beschriftungen des Fehlerberichts (aus Ressourcen, damit DE/EN gelten). */
data class BugReportLabels(
    val description: String,
    val steps: String,
    val expected: String,
    val actual: String,
    val environment: String,
    val appVersion: String,
    val device: String,
    val hardware: String,
    val security: String
)

data class BugReportInput(
    val title: String,
    val description: String,
    val steps: String,
    val expected: String,
    val actual: String,
    val appVersion: String,
    val deviceBlock: String? = null,
    val hardwareBlock: String? = null,
    val securityBlock: String? = null
)

/** Erzeugt einen Markdown-Fehlerbericht für GitHub/GitLab. Reine Logik, ohne Android-Klassen. */
object BugReportBuilder {

    fun build(input: BugReportInput, labels: BugReportLabels): String {
        val sb = StringBuilder()
        sb.append("## ").append(input.title.trim().ifEmpty { "–" }).append("\n\n")
        section(sb, labels.description, input.description)
        val steps = numberedSteps(input.steps)
        if (steps.isNotEmpty()) sb.append("### ").append(labels.steps).append("\n\n").append(steps).append("\n\n")
        section(sb, labels.expected, input.expected)
        section(sb, labels.actual, input.actual)
        sb.append("### ").append(labels.environment).append("\n\n")
        sb.append("- ").append(labels.appVersion).append(": ").append(input.appVersion).append('\n')
        sb.append('\n')
        details(sb, labels.device, input.deviceBlock)
        details(sb, labels.hardware, input.hardwareBlock)
        details(sb, labels.security, input.securityBlock)
        return sb.toString().trimEnd() + "\n"
    }

    /** Nummeriert Zeilen neu; vorhandene Aufzählungszeichen ("1.", "2)", "-", "*") werden entfernt. */
    fun numberedSteps(text: String): String {
        val lines = text.lines().map { it.trim().replace(Regex("""^(\d+[.)]|[-*•])\s*"""), "") }.filter { it.isNotEmpty() }
        return lines.mapIndexed { i, line -> "${i + 1}. $line" }.joinToString("\n")
    }

    private fun section(sb: StringBuilder, heading: String, body: String) {
        val text = body.trim()
        if (text.isEmpty()) return
        sb.append("### ").append(heading).append("\n\n").append(text).append("\n\n")
    }

    private fun details(sb: StringBuilder, summary: String, block: String?) {
        if (block.isNullOrBlank()) return
        sb.append("<details>\n<summary>").append(summary).append("</summary>\n\n```\n")
            .append(block.trim()).append("\n```\n\n</details>\n\n")
    }
}
