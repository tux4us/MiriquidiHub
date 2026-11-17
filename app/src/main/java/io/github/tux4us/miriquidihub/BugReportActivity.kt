package io.github.tux4us.miriquidihub

import android.os.Build
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.github.tux4us.miriquidihub.databinding.ViewBugReportFormBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fehlerbericht-Assistent: erzeugt aus einem Formular einen Markdown-Text für GitHub/GitLab,
 * optional mit Geräteinformationen, Hardware-Testergebnis und Sicherheitsübersicht. Es wird
 * nichts gesendet; der Text wird kopiert, geteilt oder als Bericht gespeichert.
 */
class BugReportActivity : ToolActivity() {

    override val titleRes: Int = R.string.br_title
    override val footnoteRes: Int = R.string.br_footnote
    override val hasRefreshAction: Boolean = false

    private lateinit var form: ViewBugReportFormBinding
    private var markdown: String = ""
    private var deviceBlock: String = ""

    override fun onToolCreated() {
        form = ViewBugReportFormBinding.inflate(layoutInflater, binding.extraContainer, true)
        setPrimaryAction(R.string.br_create) { create() }
        setSecondaryAction(R.string.br_save) { saveAsReport() }
    }

    override fun exportText(): String = markdown

    private fun create() {
        setBusy(true)
        lifecycleScope.launch {
            val input = withContext(Dispatchers.IO) { collectInput() }
            markdown = BugReportBuilder.build(input, labels())
            showSections(listOf(DiagSection(getString(R.string.br_preview), listOf(DiagRow(getString(R.string.br_markdown), markdown)))))
            setSummary(DiagStatus.OK, getString(R.string.br_ready, markdown.length))
            setBusy(false)
        }
    }

    private fun collectInput(): BugReportInput {
        val ctx = this
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        val device = if (form.cbBugDevice.isChecked) {
            val sections = DeviceInfo.collect(ctx)
            "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                DeviceInfo.formatReportBlock(sections)
        } else null
        deviceBlock = device.orEmpty()
        val hardware = if (form.cbBugHardware.isChecked) HardwareTestStore(ctx).formatReportBlock(ctx) else null
        val security = if (form.cbBugSecurity.isChecked) {
            DiagRenderer.toPlainText(getString(R.string.sc_title), SecurityCheck.collect(ctx))
        } else null
        return BugReportInput(
            title = form.etBugTitle.text?.toString().orEmpty(),
            description = form.etBugDescription.text?.toString().orEmpty(),
            steps = form.etBugSteps.text?.toString().orEmpty(),
            expected = form.etBugExpected.text?.toString().orEmpty(),
            actual = form.etBugActual.text?.toString().orEmpty(),
            appVersion = version,
            deviceBlock = device,
            hardwareBlock = hardware,
            securityBlock = security
        )
    }

    private fun labels() = BugReportLabels(
        description = getString(R.string.br_h_description),
        steps = getString(R.string.br_h_steps),
        expected = getString(R.string.br_h_expected),
        actual = getString(R.string.br_h_actual),
        environment = getString(R.string.br_h_environment),
        appVersion = getString(R.string.br_h_app_version),
        device = getString(R.string.br_h_device),
        hardware = getString(R.string.br_h_hardware),
        security = getString(R.string.br_h_security)
    )

    private fun saveAsReport() {
        if (markdown.isEmpty()) {
            Toast.makeText(this, R.string.br_create_first, Toast.LENGTH_SHORT).show()
            return
        }
        val title = form.etBugTitle.text?.toString()?.trim().orEmpty().ifEmpty { getString(R.string.br_title) }
        ReportStorage(this).saveReport(
            DeviceReport(
                title = title, note = markdown, specs = deviceBlock,
                shelterActive = false, securityModeActive = false, vpnActive = false
            )
        )
        Toast.makeText(this, R.string.br_saved, Toast.LENGTH_SHORT).show()
    }
}
