package io.github.tux4us.miriquidihub

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exportiert die Logcat-Einträge einer gewählten App für ein Zeitfenster. Fremde Einträge sind nur
 * mit erweitertem Zugriff lesbar (READ_LOGS per ADB oder Shizuku); ohne ihn werden nur die Einträge
 * der eigenen App geliefert. Nichts wird automatisch gesendet.
 */
class AppLogActivity : ToolActivity() {

    override val titleRes: Int = R.string.al_title
    override val footnoteRes: Int = R.string.al_footnote

    private class AppChoice(val label: String, val packageName: String, val uid: Int, val system: Boolean)

    private var selectedPackage: String? = null
    private var appLabel: String = ""
    private var appUid: Int = -1

    /** Fenstergröße in Minuten; 0 bedeutet „ab eigener Startzeit“. */
    private var windowMinutes = 15
    private var customStart = 0L
    private var minLevel = 'V'

    private var mode = LogAccessMode.OWN_ONLY
    private var filteredLines: List<String> = emptyList()
    private var totalRead = 0
    private var windowStart = 0L
    private var windowEnd = 0L
    private var loaded = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refreshAccess() }
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = fileText()
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(this@AppLogActivity, if (ok) R.string.lc_saved else R.string.editor_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onToolCreated() {
        setPrimaryAction(R.string.al_choose_app) { chooseApp(false) }
        setSecondaryAction(R.string.al_choose_window) { chooseWindow() }
        addExtraButtons()
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
            // Shizuku-Bibliothek nicht nutzbar – nur ADB-Weg verfügbar
        }
        refreshAccess()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) refreshAccess()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
            // ignorieren
        }
        super.onDestroy()
    }

    override fun onRefresh() {
        load()
    }

    /** Für Kopieren und Teilen nur die letzten Zeilen. */
    override fun exportText(): String = header() + "\n" + filteredLines.takeLast(SHARE_LINES).joinToString("\n")

    private fun fileText(): String = header() + "\n" + filteredLines.joinToString("\n")

    private fun header(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            appendLine("# MiriquidiHub app log")
            appendLine("# Package: ${selectedPackage.orEmpty()}")
            appendLine("# Window: ${fmt.format(Date(windowStart))} - ${fmt.format(Date(windowEnd))}")
            appendLine("# Minimum level: $minLevel")
            append("# Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
    }

    private fun addExtraButtons() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(label: Int, action: () -> Unit) {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.setText(label)
            b.setOnClickListener { action() }
            column.addView(b, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        button(R.string.al_setup) { showSetup() }
        button(R.string.lc_choose_level) { chooseLevel() }
        button(R.string.lc_save_file) { saveFile() }
        binding.extraContainer.addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun refreshAccess() {
        mode = AppLogAccess.bestMode(this)
        render()
    }

    // --- Auswahl -------------------------------------------------------------------------

    private fun chooseApp(includeSystem: Boolean) {
        setBusy(true)
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { listApps() }
            setBusy(false)
            val shown = apps.filter { includeSystem || !it.system }
            val names = shown.map { "${it.label}\n${it.packageName}" }.toTypedArray()
            AlertDialog.Builder(this@AppLogActivity)
                .setTitle(R.string.al_choose_app)
                .setItems(names) { _, which ->
                    val choice = shown[which]
                    selectedPackage = choice.packageName
                    appLabel = choice.label
                    appUid = choice.uid
                    load()
                }
                .setNeutralButton(if (includeSystem) R.string.po_hide_system else R.string.po_show_system) { _, _ ->
                    chooseApp(!includeSystem)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun listApps(): List<AppChoice> {
        val pm = packageManager
        return AppQueries.installedPackages(pm).mapNotNull { info ->
            val ai = info.applicationInfo ?: return@mapNotNull null
            AppChoice(AppQueries.label(pm, info.packageName, ai), info.packageName, ai.uid, AppQueries.isSystemApp(ai))
        }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun chooseWindow() {
        val names = arrayOf(
            getString(R.string.al_window_5m),
            getString(R.string.al_window_15m),
            getString(R.string.al_window_1h),
            getString(R.string.al_window_6h),
            getString(R.string.al_window_custom)
        )
        val minutes = intArrayOf(5, 15, 60, 360, 0)
        AlertDialog.Builder(this)
            .setTitle(R.string.al_choose_window)
            .setSingleChoiceItems(names, minutes.indexOf(windowMinutes).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                if (minutes[which] == 0) pickStartTime() else {
                    windowMinutes = minutes[which]
                    if (selectedPackage != null) load() else render()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickStartTime() {
        val now = Calendar.getInstance()
        TimePickerDialog(this, { _, hour, minute ->
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, hour)
            c.set(Calendar.MINUTE, minute)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis > System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, -1)
            customStart = c.timeInMillis
            windowMinutes = 0
            if (selectedPackage != null) load() else render()
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
    }

    private fun chooseLevel() {
        val levels = listOf('V', 'D', 'I', 'W', 'E')
        val names = arrayOf("V – Verbose", "D – Debug", "I – Info", "W – Warning", "E – Error")
        AlertDialog.Builder(this)
            .setTitle(R.string.lc_choose_level)
            .setSingleChoiceItems(names, levels.indexOf(minLevel).coerceAtLeast(0)) { dialog, which ->
                minLevel = levels[which]
                dialog.dismiss()
                if (selectedPackage != null) load() else render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Zugriff einrichten ----------------------------------------------------------------

    private fun showSetup() {
        val command = AppLogAccess.grantCommand(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.al_setup)
            .setMessage(getString(R.string.al_setup_message, command))
            .setPositiveButton(R.string.al_copy_command) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", command))
                Toast.makeText(this, R.string.al_command_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.al_use_shizuku) { _, _ -> useShizuku() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun useShizuku() {
        when (AppLogAccess.shizukuState(this)) {
            ShizukuState.UNAVAILABLE -> Toast.makeText(this, R.string.al_shizuku_missing, Toast.LENGTH_LONG).show()
            ShizukuState.NOT_RUNNING -> Toast.makeText(this, R.string.al_shizuku_not_running, Toast.LENGTH_LONG).show()
            ShizukuState.NEEDS_PERMISSION -> try {
                AppLogAccess.requestShizuku()
            } catch (e: Throwable) {
                Toast.makeText(this, R.string.al_shizuku_not_running, Toast.LENGTH_LONG).show()
            }
            ShizukuState.READY -> refreshAccess()
        }
    }

    // --- Laden und Anzeigen ----------------------------------------------------------------

    private fun saveFile() {
        val pkg = selectedPackage
        if (pkg == null || !loaded) {
            Toast.makeText(this, R.string.al_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        createDocument.launch("log_${pkg}_$stamp.txt")
    }

    private fun load() {
        val pkg = selectedPackage ?: return
        mode = AppLogAccess.bestMode(this)
        val uid = appUid
        val now = System.currentTimeMillis()
        val start = if (windowMinutes == 0) customStart else now - windowMinutes * 60_000L
        val level = minLevel
        val currentMode = mode
        setBusy(true)
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) { AppLogAccess.readLines(currentMode) }
            val result = withContext(Dispatchers.Default) {
                AppLogFilter.filter(raw, pkg, uid, start, now, level, now, TimeZone.getDefault())
            }
            totalRead = raw.size
            filteredLines = result
            windowStart = start
            windowEnd = now
            loaded = true
            render()
            setBusy(false)
        }
    }

    private fun windowText(): String = when (windowMinutes) {
        0 -> getString(R.string.al_window_since, SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault()).format(Date(customStart)))
        5 -> getString(R.string.al_window_5m)
        15 -> getString(R.string.al_window_15m)
        60 -> getString(R.string.al_window_1h)
        360 -> getString(R.string.al_window_6h)
        else -> "$windowMinutes min"
    }

    private fun render() {
        val pkg = selectedPackage
        val accessText = when (mode) {
            LogAccessMode.READ_LOGS -> getString(R.string.al_access_read_logs)
            LogAccessMode.SHIZUKU -> getString(R.string.al_access_shizuku)
            LogAccessMode.OWN_ONLY -> getString(R.string.al_access_own_only)
        }
        val accessStatus = if (mode == LogAccessMode.OWN_ONLY) DiagStatus.WARN else DiagStatus.OK
        val selection = listOf(
            DiagRow(getString(R.string.al_access), accessText, accessStatus),
            DiagRow(getString(R.string.al_app), if (pkg == null) getString(R.string.al_none_selected) else "$appLabel ($pkg)"),
            DiagRow(getString(R.string.al_window), windowText()),
            DiagRow(getString(R.string.al_level), minLevel.toString())
        )
        val list = mutableListOf(DiagSection(getString(R.string.al_section_selection), selection))
        if (pkg != null && loaded) {
            val preview = filteredLines.takeLast(PREVIEW_LINES)
            val value = if (preview.isEmpty()) getString(R.string.al_empty) else preview.joinToString("\n")
            list += DiagSection(getString(R.string.al_section_log, pkg), listOf(DiagRow(getString(R.string.lc_preview), value)))
        }
        showSections(list)
        val needsAccess = mode == LogAccessMode.OWN_ONLY && pkg != null && pkg != getPackageName()
        when {
            pkg == null -> setSummary(DiagStatus.INFO, getString(R.string.al_none_selected))
            needsAccess -> setSummary(DiagStatus.WARN, getString(R.string.al_need_access))
            !loaded -> hideSummary()
            filteredLines.isEmpty() -> setSummary(DiagStatus.WARN, getString(R.string.al_empty))
            else -> setSummary(DiagStatus.INFO, getString(R.string.al_summary, filteredLines.size, totalRead))
        }
    }

    companion object {
        private const val PREVIEW_LINES = 40
        private const val SHARE_LINES = 500
    }
}
