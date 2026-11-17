package io.github.tux4us.miriquidihub

import androidx.appcompat.app.AlertDialog
import java.text.DateFormat
import java.util.Date

/**
 * Vergleicht zwei gespeicherte Geräteberichte. Standardmäßig werden die zwei neuesten
 * verglichen. Veränderliche Werte (Akkustand, Laufzeit, freier Speicher) weichen naturgemäß ab.
 */
class ReportCompareActivity : ToolActivity() {

    override val titleRes: Int = R.string.rc_title
    override val footnoteRes: Int = R.string.rc_footnote
    override val hasRefreshAction: Boolean = false

    private var reports: List<DeviceReport> = emptyList()
    private var older: DeviceReport? = null
    private var newer: DeviceReport? = null

    override fun onToolCreated() {
        reports = ReportStorage(this).getAllReports().sortedByDescending { it.timestamp }
        if (reports.size < 2) {
            setSummary(DiagStatus.WARN, getString(R.string.rc_need_two))
            return
        }
        newer = reports[0]
        older = reports[1]
        setPrimaryAction(R.string.rc_choose_older) { choose(true) }
        setSecondaryAction(R.string.rc_choose_newer) { choose(false) }
        compare()
    }

    private fun choose(forOlder: Boolean) {
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = reports.map { "${it.title} – ${format.format(Date(it.timestamp))}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (forOlder) R.string.rc_choose_older else R.string.rc_choose_newer)
            .setItems(labels) { _, which ->
                if (forOlder) older = reports[which] else newer = reports[which]
                compare()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun compare() {
        val a = older ?: return
        val b = newer ?: return
        val diff = SpecParser.diff(a.specs, b.specs)
        val changed = diff.changes.filter { it.kind == SpecChange.Kind.CHANGED }
        val added = diff.changes.filter { it.kind == SpecChange.Kind.ADDED }
        val removed = diff.changes.filter { it.kind == SpecChange.Kind.REMOVED }

        val sections = mutableListOf<DiagSection>()
        if (changed.isNotEmpty()) {
            sections += DiagSection(
                getString(R.string.rc_section_changed, changed.size),
                changed.map { DiagRow("${it.section}: ${it.key}", "${it.old}  →  ${it.new}", DiagStatus.WARN) }
            )
        }
        if (added.isNotEmpty()) {
            sections += DiagSection(
                getString(R.string.rc_section_added, added.size),
                added.map { DiagRow("${it.section}: ${it.key}", it.new.orEmpty(), DiagStatus.INFO) }
            )
        }
        if (removed.isNotEmpty()) {
            sections += DiagSection(
                getString(R.string.rc_section_removed, removed.size),
                removed.map { DiagRow("${it.section}: ${it.key}", it.old.orEmpty(), DiagStatus.INFO) }
            )
        }
        showSections(sections)
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        setSummary(
            if (diff.changes.isEmpty()) DiagStatus.OK else DiagStatus.INFO,
            getString(
                R.string.rc_summary,
                format.format(Date(a.timestamp)), format.format(Date(b.timestamp)), diff.changes.size, diff.unchanged
            )
        )
    }
}
