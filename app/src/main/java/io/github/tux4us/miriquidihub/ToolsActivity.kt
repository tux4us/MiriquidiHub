package io.github.tux4us.miriquidihub

import android.content.Intent
import android.widget.GridLayout
import io.github.tux4us.miriquidihub.databinding.ItemToolButtonBinding
import io.github.tux4us.miriquidihub.databinding.ItemToolSectionBinding

/**
 * Übersicht aller Werkzeuge, gruppiert nach Aufgabe. Jedes Werkzeug ist eine Glas-Schaltfläche mit Titel,
 * Kurzbeschreibung und Pfeil; die Spaltenzahl richtet sich nach Breite und Schriftgröße.
 */
class ToolsActivity : ToolActivity() {

    override val titleRes: Int = R.string.tools_title
    override val offersCopyShare: Boolean = false
    override val hasRefreshAction: Boolean = false

    private class Tool(val titleRes: Int, val descRes: Int, val target: Class<*>)

    private val monitoring = listOf(
        Tool(R.string.bm_title, R.string.tools_bm_desc, BatteryMonitorActivity::class.java),
        Tool(R.string.sm_title, R.string.tools_sm_desc, SystemMonitorActivity::class.java),
        Tool(R.string.se_title, R.string.tools_se_desc, SensorExplorerActivity::class.java)
    )
    private val analysis = listOf(
        Tool(R.string.sc_title, R.string.tools_sc_desc, SecurityCheckActivity::class.java),
        Tool(R.string.po_title, R.string.tools_po_desc, PermissionOverviewActivity::class.java),
        Tool(R.string.ai_title, R.string.tools_ai_desc, AppInventoryActivity::class.java),
        Tool(R.string.wa_title, R.string.tools_wa_desc, WifiAnalysisActivity::class.java)
    )
    private val support = listOf(
        Tool(R.string.br_title, R.string.tools_br_desc, BugReportActivity::class.java),
        Tool(R.string.al_title, R.string.tools_al_desc, AppLogActivity::class.java),
        Tool(R.string.lc_title, R.string.tools_lc_desc, LogcatExportActivity::class.java),
        Tool(R.string.rc_title, R.string.tools_rc_desc, ReportCompareActivity::class.java)
    )

    override fun onToolCreated() {
        val columns = GridColumns.count(this, MIN_TILE_DP, HORIZONTAL_INSET_DP)
        val container = binding.sectionsContainer
        container.removeAllViews()
        addSection(R.string.tools_group_monitoring, monitoring, columns)
        addSection(R.string.tools_group_analysis, analysis, columns)
        addSection(R.string.tools_group_support, support, columns)
    }

    private fun addSection(titleRes: Int, tools: List<Tool>, columns: Int) {
        val container = binding.sectionsContainer
        val section = ItemToolSectionBinding.inflate(layoutInflater, container, false)
        section.tvSectionTitle.setText(titleRes)
        section.toolGrid.columnCount = columns
        tools.forEach { tool ->
            val button = ItemToolButtonBinding.inflate(layoutInflater, section.toolGrid, false)
            button.toolTitle.setText(tool.titleRes)
            button.toolDesc.setText(tool.descRes)
            button.root.setOnClickListener { startActivity(Intent(this, tool.target)) }
            // Spalten teilen sich die Breite gleichmäßig, Kacheln einer Zeile werden auf gleiche Höhe gestreckt
            (button.root.layoutParams as GridLayout.LayoutParams).apply {
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL)
            }
            section.toolGrid.addView(button.root)
        }
        container.addView(section.root)
    }

    private companion object {
        /** Mindestbreite einer Schaltfläche (Titel plus zweizeilige Beschreibung) bei Standardschrift. */
        const val MIN_TILE_DP = 300f

        /** Seitenrand 2 × 16 dp abzüglich 2 × 6 dp, die das Grid über den Rand hinausreicht. */
        const val HORIZONTAL_INSET_DP = 20
    }
}
