package io.github.tux4us.miriquidihub

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sicherheitsprüfung mit Zusammenfassung (Fehler/Warnungen) und Kopieren/Teilen. */
class SecurityCheckActivity : ToolActivity() {

    override val titleRes: Int = R.string.sc_title
    override val footnoteRes: Int = R.string.sc_footnote

    override fun onToolCreated() {
        run()
    }

    override fun onRefresh() {
        run()
    }

    private fun run() {
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { SecurityCheck.collect(this@SecurityCheckActivity) }
            showSections(result)
            val rows = result.flatMap { it.rows }
            val errors = rows.count { it.status == DiagStatus.ERROR }
            val warnings = rows.count { it.status == DiagStatus.WARN }
            when {
                errors > 0 -> setSummary(DiagStatus.ERROR, getString(R.string.netdiag_summary_error, errors, warnings))
                warnings > 0 -> setSummary(DiagStatus.WARN, getString(R.string.netdiag_summary_warn, warnings))
                else -> setSummary(DiagStatus.OK, getString(R.string.sc_summary_ok))
            }
            setBusy(false)
        }
    }
}
