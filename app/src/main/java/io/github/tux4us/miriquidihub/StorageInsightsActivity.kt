package io.github.tux4us.miriquidihub

import android.os.Build
import android.os.Environment
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Auswertungen zur Speicherbelegung: größte Dateien, Duplikate, Filter (Typ/Größe/Alter),
 * Papierkorb und Belegungsverlauf. Der Modus wird über [EXTRA_MODE] gewählt. Löschvorgänge
 * verschieben zunächst in den app-eigenen Papierkorb ([TrashManager]).
 */
class StorageInsightsActivity : ToolActivity() {

    private val mode: String get() = intent.getStringExtra(EXTRA_MODE) ?: MODE_LARGEST

    override val titleRes: Int
        get() = when (mode) {
            MODE_DUPLICATES -> R.string.insights_title_duplicates
            MODE_FILTER -> R.string.insights_title_filter
            MODE_TRASH -> R.string.insights_title_trash
            MODE_HISTORY -> R.string.insights_title_history
            else -> R.string.insights_title_largest
        }

    override val footnoteRes: Int
        get() = when (mode) {
            MODE_DUPLICATES -> R.string.insights_footnote_duplicates
            MODE_TRASH -> R.string.insights_footnote_trash
            else -> 0
        }

    private val root: File get() = Environment.getExternalStorageDirectory()
    private val trash by lazy { TrashManager(File(root, TrashManager.FOLDER_NAME)) }
    private var loadJob: Job? = null

    override fun onToolCreated() {
        load()
    }

    override fun onRefresh() {
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
    }

    private fun hasStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun load() {
        loadJob?.cancel()
        binding.btnPrimary.visibility = View.GONE
        binding.btnSecondary.visibility = View.GONE
        if (mode != MODE_HISTORY && !hasStorageAccess()) {
            showSections(emptyList())
            setSummary(DiagStatus.ERROR, getString(R.string.insights_no_permission))
            return
        }
        setBusy(true)
        setSummary(null, getString(R.string.insights_working))
        loadJob = lifecycleScope.launch {
            try {
                when (mode) {
                    MODE_DUPLICATES -> showDuplicates()
                    MODE_FILTER -> showFiltered()
                    MODE_TRASH -> showTrash()
                    MODE_HISTORY -> showHistory()
                    else -> showFiles(InsightFilter(), R.string.insights_section_largest)
                }
            } finally {
                setBusy(false)
            }
        }
    }

    // ------------------------------------------------------------------
    // Modi
    // ------------------------------------------------------------------

    private suspend fun showFiles(filter: InsightFilter, sectionTitle: Int) {
        val files = withContext(Dispatchers.IO) {
            StorageInsights.scan(root, filter, MAX_ROWS) { !isActive }
        }
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val rows = files.map { entry -> fileRow(entry.file, entry.sizeBytes, entry.lastModified, dateFormat) }
        showSections(listOf(DiagSection(getString(sectionTitle), rows)))
        if (files.isEmpty()) {
            setSummary(DiagStatus.INFO, getString(R.string.insights_none_found))
        } else {
            setSummary(
                DiagStatus.INFO,
                getString(R.string.insights_files_summary, files.size, formatBytes(files.sumOf { it.sizeBytes }))
            )
        }
    }

    private suspend fun showFiltered() {
        val category = intent.getStringExtra(EXTRA_CATEGORY)?.takeIf { it.isNotEmpty() }
            ?.let { name -> StorageCategory.values().firstOrNull { it.name == name } }
        val filter = InsightFilter(
            categories = category?.let { setOf(it) },
            minBytes = intent.getIntExtra(EXTRA_MIN_MB, 0) * 1024L * 1024L,
            olderThanDays = intent.getIntExtra(EXTRA_OLDER_DAYS, 0).takeIf { it > 0 }
        )
        showFiles(filter, R.string.insights_section_filter)
    }

    private suspend fun showDuplicates() {
        val groups = withContext(Dispatchers.IO) {
            StorageInsights.findDuplicates(root) { !isActive }
        }
        val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val sections = groups.take(MAX_GROUPS).map { group ->
            DiagSection(
                getString(R.string.insights_group_title, formatBytes(group.sizeBytes), group.files.size, formatBytes(group.wastedBytes)),
                group.files.map { fileRow(it, group.sizeBytes, it.lastModified(), dateFormat) }
            )
        }
        showSections(sections)
        if (groups.isEmpty()) {
            setSummary(DiagStatus.OK, getString(R.string.insights_no_duplicates))
        } else {
            setSummary(
                DiagStatus.WARN,
                getString(R.string.insights_duplicates_summary, groups.size, formatBytes(groups.sumOf { it.wastedBytes }))
            )
        }
    }

    private suspend fun showTrash() {
        val entries = withContext(Dispatchers.IO) {
            trash.purgeExpired()
            trash.list()
        }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val rows = entries.map { entry ->
            DiagRow(
                entry.name,
                getString(R.string.insights_trash_row, formatBytes(entry.sizeBytes), dateFormat.format(Date(entry.deletedAt)), entry.originalPath),
                DiagStatus.INFO
            ) { showTrashActions(entry) }
        }
        showSections(listOf(DiagSection(getString(R.string.insights_section_trash), rows)))
        if (entries.isEmpty()) {
            setSummary(DiagStatus.INFO, getString(R.string.insights_trash_empty))
        } else {
            setSummary(
                DiagStatus.INFO,
                getString(R.string.insights_trash_summary, entries.size, formatBytes(entries.sumOf { it.sizeBytes }), TrashManager.RETENTION_DAYS)
            )
            setPrimaryAction(R.string.insights_trash_empty_action) { confirmEmptyTrash() }
        }
    }

    private suspend fun showHistory() {
        val snapshots = withContext(Dispatchers.IO) { StorageHistory(this@StorageInsightsActivity).list() }
        if (snapshots.isEmpty()) {
            showSections(emptyList())
            setSummary(DiagStatus.INFO, getString(R.string.insights_history_empty))
            return
        }
        val rows = snapshots.asReversed().mapIndexed { index, snap ->
            val previous = snapshots.getOrNull(snapshots.size - 2 - index)
            val percent = if (snap.totalBytes > 0) (snap.usedBytes * 100 / snap.totalBytes).toInt() else 0
            val delta = previous?.let { snap.usedBytes - it.usedBytes }
            val deltaText = when {
                delta == null -> ""
                delta >= 0 -> " (+${formatBytes(delta)})"
                else -> " (−${formatBytes(-delta)})"
            }
            DiagRow(
                snap.day,
                getString(R.string.insights_history_row, formatBytes(snap.usedBytes), formatBytes(snap.totalBytes), percent) + deltaText,
                if (percent >= 90) DiagStatus.WARN else DiagStatus.INFO
            )
        }
        showSections(listOf(DiagSection(getString(R.string.insights_section_history), rows)))
        val first = snapshots.first()
        val last = snapshots.last()
        val change = last.usedBytes - first.usedBytes
        val changeText = if (change >= 0) "+${formatBytes(change)}" else "−${formatBytes(-change)}"
        setSummary(
            DiagStatus.INFO,
            getString(R.string.insights_history_summary, snapshots.size, changeText, first.day, last.day)
        )
        setSecondaryAction(R.string.insights_history_clear) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.insights_history_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    StorageHistory(this).clear()
                    load()
                }
                .show()
        }
    }

    // ------------------------------------------------------------------
    // Zeilen und Aktionen
    // ------------------------------------------------------------------

    private fun fileRow(file: File, size: Long, modified: Long, dateFormat: DateFormat): DiagRow {
        val relative = file.absolutePath.removePrefix(root.absolutePath).trimStart('/')
        return DiagRow(
            file.name,
            getString(R.string.insights_file_row, formatBytes(size), dateFormat.format(Date(modified)), relative),
            DiagStatus.INFO
        ) { showFileActions(file) }
    }

    private fun showFileActions(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMessage(file.absolutePath)
            .setPositiveButton(R.string.insights_action_trash) { _, _ -> moveToTrash(file) }
            .setNeutralButton(R.string.insights_action_delete) { _, _ -> confirmPermanentDelete(file) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun moveToTrash(file: File) {
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) { trash.moveToTrash(file) }
            toast(if (entry != null) R.string.insights_moved_to_trash else R.string.node_delete_failed_toast, file.name)
            load()
        }
    }

    private fun confirmPermanentDelete(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMessage(R.string.insights_delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            if (file.isDirectory) file.deleteRecursively() else file.delete()
                        } catch (e: Exception) {
                            false
                        }
                    }
                    toast(if (ok) R.string.node_deleted_toast else R.string.node_delete_failed_toast, file.name)
                    load()
                }
            }
            .show()
    }

    private fun showTrashActions(entry: TrashEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setMessage(entry.originalPath)
            .setPositiveButton(R.string.insights_action_restore) { _, _ ->
                lifecycleScope.launch {
                    val restored = withContext(Dispatchers.IO) { trash.restore(entry.id) }
                    toast(if (restored != null) R.string.insights_restored else R.string.insights_restore_failed, entry.name)
                    load()
                }
            }
            .setNeutralButton(R.string.insights_action_delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { trash.delete(entry.id) }
                    toast(if (ok) R.string.node_deleted_toast else R.string.node_delete_failed_toast, entry.name)
                    load()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmEmptyTrash() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.insights_trash_empty_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { trash.empty() }
                    load()
                }
            }
            .show()
    }

    private fun toast(messageRes: Int, name: String) {
        android.widget.Toast.makeText(this, getString(messageRes, name), android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_MIN_MB = "min_mb"
        const val EXTRA_OLDER_DAYS = "older_days"

        const val MODE_LARGEST = "largest"
        const val MODE_DUPLICATES = "duplicates"
        const val MODE_FILTER = "filter"
        const val MODE_TRASH = "trash"
        const val MODE_HISTORY = "history"

        private const val MAX_ROWS = 50
        private const val MAX_GROUPS = 30
    }
}
