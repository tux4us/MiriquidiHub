package io.github.tux4us.miriquidihub

import android.content.Intent

/** Übersicht aller Werkzeuge, gruppiert nach Aufgabe. Jede Zeile öffnet das jeweilige Werkzeug. */
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
        Tool(R.string.lc_title, R.string.tools_lc_desc, LogcatExportActivity::class.java),
        Tool(R.string.rc_title, R.string.tools_rc_desc, ReportCompareActivity::class.java)
    )

    override fun onToolCreated() {
        showSections(
            listOf(
                section(R.string.tools_group_monitoring, monitoring),
                section(R.string.tools_group_analysis, analysis),
                section(R.string.tools_group_support, support)
            )
        )
    }

    private fun section(titleRes: Int, tools: List<Tool>) = DiagSection(
        getString(titleRes),
        tools.map { tool ->
            DiagRow(getString(tool.titleRes), getString(tool.descRes), DiagStatus.INFO) {
                startActivity(Intent(this, tool.target))
            }
        }
    )
}
