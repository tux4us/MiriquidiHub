package io.github.tux4us.miriquidihub

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exportiert das Systemprotokoll (logcat) der eigenen App. Android gibt Apps ohne Root nur die
 * eigenen Einträge frei. Der Text kann Nutzungsdaten enthalten und wird nur auf Nutzeraktion
 * gespeichert oder geteilt.
 */
class LogcatExportActivity : ToolActivity() {

    override val titleRes: Int = R.string.lc_title
    override val footnoteRes: Int = R.string.lc_footnote

    private var lines: List<String> = emptyList()
    private var minLevel = 'V'

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(filtered().joinToString("\n").toByteArray()) } != null
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(this@LogcatExportActivity, if (ok) R.string.lc_saved else R.string.editor_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onToolCreated() {
        setPrimaryAction(R.string.lc_save_file) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            createDocument.launch("miriquidihub_log_$stamp.txt")
        }
        setSecondaryAction(R.string.lc_choose_level) { chooseLevel() }
        load()
    }

    override fun onRefresh() {
        load()
    }

    /** Für Teilen/Kopieren nur die letzten Zeilen, damit die Übergabe klein bleibt. */
    override fun exportText(): String = filtered().takeLast(SHARE_LINES).joinToString("\n")

    private fun filtered(): List<String> = LogFilter.filter(lines, minLevel)

    private fun load() {
        setBusy(true)
        lifecycleScope.launch {
            lines = withContext(Dispatchers.IO) { readLog() }
            render()
            setBusy(false)
        }
    }

    private fun readLog(): List<String> = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
        val all = process.inputStream.bufferedReader().use { reader -> reader.readLines() }
        process.waitFor()
        all.takeLast(MAX_LINES)
    } catch (e: Exception) {
        emptyList()
    }

    private fun render() {
        val shown = filtered()
        val preview = shown.takeLast(PREVIEW_LINES)
        val rows = if (preview.isEmpty()) {
            listOf(DiagRow(getString(R.string.lc_preview), getString(R.string.lc_empty)))
        } else {
            listOf(DiagRow(getString(R.string.lc_preview), preview.joinToString("\n")))
        }
        showSections(listOf(DiagSection(getString(R.string.lc_section, minLevel.toString()), rows)))
        setSummary(
            if (lines.isEmpty()) DiagStatus.WARN else DiagStatus.INFO,
            if (lines.isEmpty()) getString(R.string.lc_empty) else getString(R.string.lc_summary, shown.size, lines.size)
        )
    }

    private fun chooseLevel() {
        val levels = listOf('V', 'D', 'I', 'W', 'E')
        val names = arrayOf("V – Verbose", "D – Debug", "I – Info", "W – Warning", "E – Error")
        AlertDialog.Builder(this)
            .setTitle(R.string.lc_choose_level)
            .setSingleChoiceItems(names, levels.indexOf(minLevel).coerceAtLeast(0)) { dialog, which ->
                minLevel = levels[which]
                dialog.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val MAX_LINES = 20_000
        private const val PREVIEW_LINES = 40
        private const val SHARE_LINES = 500
    }
}
