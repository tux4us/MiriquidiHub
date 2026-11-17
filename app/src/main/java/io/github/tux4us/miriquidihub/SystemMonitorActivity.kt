package io.github.tux4us.miriquidihub

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live-Monitor: Arbeitsspeicher, Netzwerkdurchsatz (TrafficStats), Akkutemperatur und
 * Thermal-Status. Misst nur, solange der Bildschirm sichtbar ist.
 */
class SystemMonitorActivity : ToolActivity() {

    override val titleRes: Int = R.string.sm_title
    override val footnoteRes: Int = R.string.sm_footnote
    override val hasRefreshAction: Boolean = false

    private val ramHistory = ArrayDeque<Float>()
    private val downHistory = ArrayDeque<Float>()
    private lateinit var ramChart: LineChartView
    private lateinit var netChart: LineChartView

    override fun onToolCreated() {
        ramChart = addChart(R.string.sm_chart_ram)
        netChart = addChart(R.string.sm_chart_net)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var prevRx = TrafficStats.getTotalRxBytes()
                var prevTx = TrafficStats.getTotalTxBytes()
                var prevTime = SystemClock.elapsedRealtime()
                while (isActive) {
                    delay(INTERVAL_MS)
                    val now = SystemClock.elapsedRealtime()
                    val rx = TrafficStats.getTotalRxBytes()
                    val tx = TrafficStats.getTotalTxBytes()
                    val down = RateCalculator.bytesPerSecond(prevRx, rx, now - prevTime)
                    val up = RateCalculator.bytesPerSecond(prevTx, tx, now - prevTime)
                    prevRx = rx; prevTx = tx; prevTime = now
                    sample(down, up)
                }
            }
        }
        sample(0, 0)
    }

    private fun addChart(titleRes: Int): LineChartView {
        binding.extraContainer.addView(
            TextView(this).apply { setText(titleRes); setPadding(0, 16, 0, 4) },
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val chart = LineChartView(this)
        binding.extraContainer.addView(chart, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return chart
    }

    private fun push(history: ArrayDeque<Float>, value: Float) {
        history.addLast(value)
        while (history.size > MAX_POINTS) history.removeFirst()
    }

    private fun sample(downBps: Long, upBps: Long) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val usedPercent = if (info.totalMem > 0) (info.totalMem - info.availMem) * 100f / info.totalMem else 0f
        push(ramHistory, usedPercent)
        push(downHistory, downBps / 1024f)

        val left = getString(R.string.sm_window, (ramHistory.size * INTERVAL_MS / 1000).toInt())
        val now = getString(R.string.bm_now)
        ramChart.setData(ramHistory.toList(), 0f, 100f, left, now) { "%.0f %%".format(it) }
        netChart.setData(downHistory.toList(), 0f, null, left, now) { "%.0f kB/s".format(it) }

        val rows = mutableListOf<DiagRow>()
        rows += DiagRow(
            getString(R.string.sm_ram),
            getString(R.string.sm_ram_value, formatBytes(info.totalMem - info.availMem), formatBytes(info.totalMem), usedPercent.toInt()),
            if (info.lowMemory) DiagStatus.WARN else DiagStatus.INFO
        )
        rows += DiagRow(getString(R.string.sm_download), formatRate(downBps))
        rows += DiagRow(getString(R.string.sm_upload), formatRate(upBps))

        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (temp != Int.MIN_VALUE) {
            rows += DiagRow(
                getString(R.string.bm_temperature), "%.1f °C".format(temp / 10f),
                if (temp >= 450) DiagStatus.ERROR else if (temp >= 400) DiagStatus.WARN else DiagStatus.INFO
            )
        }
        thermalRow()?.let { rows += it }
        showSections(listOf(DiagSection(getString(R.string.sm_section_live), rows)))
        setSummary(DiagStatus.INFO, getString(R.string.sm_summary, usedPercent.toInt()))
    }

    private fun thermalRow(): DiagRow? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val (labelRes, status) = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> R.string.sm_thermal_none to DiagStatus.OK
            PowerManager.THERMAL_STATUS_LIGHT -> R.string.sm_thermal_light to DiagStatus.INFO
            PowerManager.THERMAL_STATUS_MODERATE -> R.string.sm_thermal_moderate to DiagStatus.WARN
            PowerManager.THERMAL_STATUS_SEVERE -> R.string.sm_thermal_severe to DiagStatus.ERROR
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> R.string.sm_thermal_critical to DiagStatus.ERROR
            else -> R.string.sm_thermal_unknown to DiagStatus.INFO
        }
        return DiagRow(getString(R.string.sm_thermal), getString(labelRes), status)
    }

    private fun formatRate(bytesPerSecond: Long): String =
        if (bytesPerSecond >= 1024 * 1024) "%.1f MB/s".format(bytesPerSecond / 1048576f) else "%.0f kB/s".format(bytesPerSecond / 1024f)

    companion object {
        private const val INTERVAL_MS = 2_000L
        private const val MAX_POINTS = 60
    }
}
