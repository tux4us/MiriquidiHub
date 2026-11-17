package io.github.tux4us.miriquidihub

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import io.github.tux4us.miriquidihub.databinding.ActivityNetworkDiagnosticBinding
import io.github.tux4us.miriquidihub.databinding.ItemDiagRowBinding
import io.github.tux4us.miriquidihub.databinding.ItemDiagSectionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Netzwerk-Diagnose: zeigt Verbindungsstatus, Netzwerkkonfiguration, WLAN-/Mobilfunk-
 * Details und führt DNS-, TCP- und HTTPS-Tests gegen mehrere öffentliche Testhosts aus. Das
 * Ergebnis lässt sich kopieren oder teilen (z. B. für Support-Anfragen).
 * Die eigentliche Logik steckt in [NetworkDiagnostics].
 */
class NetworkDiagnosticActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkDiagnosticBinding
    private lateinit var diagnostics: NetworkDiagnostics
    private lateinit var hostPrefs: NetworkHostPrefs
    private lateinit var history: NetworkHistory

    private var sections: List<DiagSection> = emptyList()
    private var running = false

    private val phonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Unabhängig vom Ergebnis neu auswerten; ohne Berechtigung erscheint wieder der Hinweis.
            startDiagnostics()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.btn_network_diagnostic)

        ViewCompat.setOnApplyWindowInsetsListener(binding.contentScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        hostPrefs = NetworkHostPrefs(this)
        history = NetworkHistory(this)
        diagnostics = NetworkDiagnostics(this, hostPrefs.hosts())

        binding.btnRun.setOnClickListener { startDiagnostics() }
        binding.btnCopy.setOnClickListener { copyToClipboard() }
        binding.btnShare.setOnClickListener { shareResult() }
        binding.btnGrantPhone.setOnClickListener {
            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }

        startDiagnostics()
    }

    override fun onDestroy() {
        super.onDestroy()
        diagnostics.close()
    }

    private fun startDiagnostics() {
        if (running) return
        running = true
        setUiRunning(true)

        val staticSections = diagnostics.collectStatic()
        sections = staticSections
        render(staticSections)
        binding.cardPhonePermission.visibility =
            if (diagnostics.needsPhonePermission()) View.VISIBLE else View.GONE

        lifecycleScope.launch {
            try {
                sections = staticSections + diagnostics.runTests()
                val (errors, warnings) = diagnostics.countIssues(sections)
                history.add(
                    NetRun(System.currentTimeMillis(), errors, warnings, diagnostics.lastAvgDnsMs, diagnostics.lastAvgTcpMs)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                sections = staticSections + DiagSection(
                    getString(R.string.netdiag_section_hints),
                    listOf(DiagRow(getString(R.string.netdiag_hint_label), getString(R.string.error_generic, message), DiagStatus.ERROR))
                )
            } finally {
                running = false
                setUiRunning(false)
            }
            render(sections)
            updateSummary()
        }
    }

    private fun setUiRunning(isRunning: Boolean) {
        binding.progress.visibility = if (isRunning) View.VISIBLE else View.GONE
        binding.btnRun.isEnabled = !isRunning
        binding.btnCopy.isEnabled = !isRunning
        binding.btnShare.isEnabled = !isRunning
        binding.btnRun.setText(if (isRunning) R.string.netdiag_running else R.string.netdiag_rerun)
        if (isRunning) {
            binding.summaryIcon.text = ""
            binding.tvSummary.setText(R.string.netdiag_running)
        }
    }

    private fun updateSummary() {
        val (errors, warnings) = diagnostics.countIssues(sections)
        val status = when {
            errors > 0 -> DiagStatus.ERROR
            warnings > 0 -> DiagStatus.WARN
            else -> DiagStatus.OK
        }
        binding.summaryIcon.text = symbolFor(status)
        binding.summaryIcon.setTextColor(colorFor(status, binding.summaryIcon))
        binding.tvSummary.text = when (status) {
            DiagStatus.ERROR -> getString(R.string.netdiag_summary_error, errors, warnings)
            DiagStatus.WARN -> getString(R.string.netdiag_summary_warn, warnings)
            else -> getString(R.string.netdiag_summary_ok)
        }
    }

    private fun render(list: List<DiagSection>) {
        val container = binding.sectionsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (section in list) {
            val sectionBinding = ItemDiagSectionBinding.inflate(inflater, container, false)
            sectionBinding.tvSectionTitle.text = section.title
            for (row in section.rows) {
                val rowBinding = ItemDiagRowBinding.inflate(inflater, sectionBinding.rowsContainer, false)
                rowBinding.tvStatus.text = symbolFor(row.status)
                rowBinding.tvStatus.setTextColor(colorFor(row.status, rowBinding.tvStatus))
                rowBinding.tvLabel.text = row.label
                rowBinding.tvValue.text = row.value
                row.onClick?.let { action -> rowBinding.root.setOnClickListener { action() } }
                sectionBinding.rowsContainer.addView(rowBinding.root)
            }
            container.addView(sectionBinding.root)
        }
    }

    private fun symbolFor(status: DiagStatus): String = when (status) {
        DiagStatus.OK -> "✔"
        DiagStatus.WARN -> "▲"
        DiagStatus.ERROR -> "✖"
        DiagStatus.INFO -> "•"
    }

    private fun colorFor(status: DiagStatus, anchor: View): Int = when (status) {
        DiagStatus.OK -> ContextCompat.getColor(this, R.color.diag_ok)
        DiagStatus.WARN -> ContextCompat.getColor(this, R.color.diag_warn)
        DiagStatus.ERROR -> ContextCompat.getColor(this, R.color.diag_error)
        DiagStatus.INFO -> MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOutline)
    }

    private fun copyToClipboard() {
        if (sections.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.netdiag_share_subject), diagnostics.toPlainText(sections))
        )
        Toast.makeText(this, R.string.netdiag_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareResult() {
        if (sections.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.netdiag_share_subject))
            putExtra(Intent.EXTRA_TEXT, diagnostics.toPlainText(sections))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.netdiag_share_chooser)))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_network_diagnostic, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_run_diagnostic -> {
                startDiagnostics()
                true
            }
            R.id.action_net_hosts -> {
                showHostsDialog()
                true
            }
            R.id.action_net_throughput -> {
                showThroughputDialog()
                true
            }
            R.id.action_net_history -> {
                showHistoryDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showHostsDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            minLines = 4
            setText(hostPrefs.hostsAsText())
            hint = getString(R.string.netdiag_hosts_hint, NetworkHostPrefs.MAX_HOSTS)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.netdiag_hosts_title)
            .setMessage(getString(R.string.netdiag_hosts_hint, NetworkHostPrefs.MAX_HOSTS))
            .setView(input, pad, 0, pad, 0)
            .setPositiveButton(R.string.save, null)
            .setNeutralButton(R.string.netdiag_hosts_reset) { _, _ ->
                hostPrefs.resetHosts()
                applyHostsAndRerun()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = NetworkHostPrefs.parse(input.text.toString())
                if (parsed.invalid.isNotEmpty()) {
                    input.error = getString(R.string.netdiag_hosts_invalid, parsed.invalid.joinToString(", "))
                } else if (parsed.valid.isEmpty()) {
                    hostPrefs.resetHosts()
                    dialog.dismiss()
                    applyHostsAndRerun()
                } else {
                    hostPrefs.saveHosts(parsed.valid)
                    dialog.dismiss()
                    applyHostsAndRerun()
                }
            }
        }
        dialog.show()
    }

    private fun applyHostsAndRerun() {
        if (running) return
        diagnostics.close()
        diagnostics = NetworkDiagnostics(this, hostPrefs.hosts())
        startDiagnostics()
    }

    private fun showHistoryDialog() {
        val runs = history.list()
        val builder = AlertDialog.Builder(this).setTitle(R.string.netdiag_history_title)
        if (runs.isEmpty()) {
            builder.setMessage(R.string.netdiag_history_empty)
        } else {
            val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            fun ms(value: Long) = if (value < 0) "–" else getString(R.string.netdiag_ms_value, value.toInt())
            builder.setItems(
                runs.map {
                    getString(
                        R.string.netdiag_history_line,
                        format.format(Date(it.timestamp)), it.errors, it.warnings, ms(it.avgDnsMs), ms(it.avgTcpMs)
                    )
                }.toTypedArray(),
                null
            )
            builder.setNeutralButton(R.string.netdiag_history_clear) { _, _ -> history.clear() }
        }
        builder.setPositiveButton(R.string.close, null).show()
    }

    private fun showThroughputDialog() {
        if (running) return
        val metered = (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(hostPrefs.lastThroughputUrl())
            hint = "https://"
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val maxMb = (ThroughputTest.MAX_BYTES / (1024 * 1024)).toInt()
        val message = StringBuilder(
            getString(R.string.netdiag_tp_hint, maxMb, (ThroughputTest.MAX_MILLIS / 1000).toInt())
        )
        if (metered) message.append("\n\n").append(getString(R.string.netdiag_tp_metered_warn, maxMb))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.netdiag_tp_title)
            .setMessage(message.toString())
            .setView(input, pad, 0, pad, 0)
            .setPositiveButton(R.string.netdiag_tp_start, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text.toString().trim()
                if (!ThroughputTest.isAllowedUrl(url)) {
                    input.error = getString(R.string.netdiag_tp_url_invalid)
                } else {
                    hostPrefs.saveThroughputUrl(url)
                    dialog.dismiss()
                    runThroughput(url)
                }
            }
        }
        dialog.show()
    }

    private fun runThroughput(url: String) {
        if (running) return
        running = true
        setUiRunning(true)
        binding.tvSummary.setText(R.string.netdiag_tp_running)
        lifecycleScope.launch {
            val section = try {
                val r = ThroughputTest.run(url)
                val rows = mutableListOf(
                    DiagRow(
                        getString(R.string.netdiag_tp_speed),
                        getString(R.string.netdiag_tp_speed_value, r.megabitPerSecond),
                        if (r.httpCode in 200..299) DiagStatus.OK else DiagStatus.WARN
                    ),
                    DiagRow(
                        getString(R.string.netdiag_tp_volume),
                        getString(R.string.netdiag_tp_volume_value, r.bytes / (1024.0 * 1024.0), r.millis.toInt())
                    ),
                    DiagRow(getString(R.string.netdiag_tp_first_byte), getString(R.string.netdiag_ms_value, r.firstByteMillis.toInt())),
                    DiagRow(getString(R.string.netdiag_tp_http), r.httpCode.toString(), if (r.httpCode in 200..299) DiagStatus.OK else DiagStatus.WARN)
                )
                if (!r.completed) rows += DiagRow(getString(R.string.netdiag_tp_title), getString(R.string.netdiag_tp_limited))
                DiagSection(getString(R.string.netdiag_section_tp), rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagSection(
                    getString(R.string.netdiag_section_tp),
                    listOf(DiagRow(getString(R.string.netdiag_tp_title), getString(R.string.netdiag_tp_error, e.message ?: e.javaClass.simpleName), DiagStatus.ERROR))
                )
            } finally {
                running = false
                setUiRunning(false)
            }
            sections = sections.filter { it.title != section.title } + section
            render(sections)
            updateSummary()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
