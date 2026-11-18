package io.github.tux4us.miriquidihub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.github.tux4us.miriquidihub.databinding.ActivityToolBinding

/**
 * Grundgerüst für Werkzeuge, die ihr Ergebnis als [DiagSection]-Liste anzeigen: Toolbar mit
 * Aktualisieren, Zusammenfassung, Fortschritt, optionaler Platz für eigene Ansichten
 * ([ActivityToolBinding.extraContainer]), zwei Aktionsschaltflächen sowie Kopieren/Teilen.
 * Unterklassen setzen [titleRes] und füllen die Ansicht in [onToolCreated].
 */
abstract class ToolActivity : AppCompatActivity() {

    protected lateinit var binding: ActivityToolBinding
    protected val isBindingInitialized: Boolean
        get() = ::binding.isInitialized

    /** Zuletzt angezeigte Abschnitte; Grundlage für Kopieren und Teilen. */
    protected var sections: List<DiagSection> = emptyList()

    @get:StringRes
    protected abstract val titleRes: Int

    /** Hinweistext unter den Ergebnissen (Datenschutz, Grenzen). 0 = keiner. */
    @get:StringRes
    protected open val footnoteRes: Int = 0

    /** Zeigt Kopieren/Teilen an. */
    protected open val offersCopyShare: Boolean = true

    protected abstract fun onToolCreated()

    /** Wird vom Aktualisieren-Symbol der Toolbar aufgerufen. */
    protected open fun onRefresh() {}

    protected open val hasRefreshAction: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityToolBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(titleRes)

        ViewCompat.setOnApplyWindowInsetsListener(binding.contentScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.rowCopyShare.visibility = if (offersCopyShare) View.VISIBLE else View.GONE
        binding.btnCopy.setOnClickListener { copyToClipboard() }
        binding.btnShare.setOnClickListener { shareText() }
        if (footnoteRes != 0) {
            binding.tvFootnote.setText(footnoteRes)
            binding.tvFootnote.visibility = View.VISIBLE
        }
        onToolCreated()
    }

    // ------------------------------------------------------------------
    // Hilfen für Unterklassen
    // ------------------------------------------------------------------

    protected fun showSections(list: List<DiagSection>) {
        sections = list
        DiagRenderer.render(this, binding.sectionsContainer, list)
    }

    protected fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnPrimary.isEnabled = !busy
        binding.btnSecondary.isEnabled = !busy
    }

    protected fun setSummary(status: DiagStatus?, text: String) {
        binding.cardSummary.visibility = View.VISIBLE
        if (status == null) {
            binding.summaryIcon.text = ""
        } else {
            binding.summaryIcon.text = DiagRenderer.symbolFor(status)
            binding.summaryIcon.setTextColor(DiagRenderer.colorFor(this, status, binding.summaryIcon))
        }
        binding.tvSummary.text = text
    }

    protected fun hideSummary() {
        binding.cardSummary.visibility = View.GONE
    }

    protected fun setPrimaryAction(@StringRes label: Int, action: () -> Unit) {
        binding.btnPrimary.setText(label)
        binding.btnPrimary.visibility = View.VISIBLE
        binding.btnPrimary.setOnClickListener { action() }
    }

    protected fun setSecondaryAction(@StringRes label: Int, action: () -> Unit) {
        binding.btnSecondary.setText(label)
        binding.btnSecondary.visibility = View.VISIBLE
        binding.btnSecondary.setOnClickListener { action() }
    }

    /** Text für Kopieren/Teilen. Unterklassen können überschreiben. */
    protected open fun exportText(): String =
        DiagRenderer.toPlainText(getString(titleRes) + " – MiriquidiHub", sections)

    private fun copyToClipboard() {
        if (sections.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(titleRes), exportText()))
        Toast.makeText(this, R.string.tool_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareText() {
        if (sections.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(titleRes) + " – MiriquidiHub")
            putExtra(Intent.EXTRA_TEXT, exportText())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.tool_share_chooser)))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (hasRefreshAction) menuInflater.inflate(R.menu.menu_tool, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_tool_refresh -> {
            onRefresh()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
