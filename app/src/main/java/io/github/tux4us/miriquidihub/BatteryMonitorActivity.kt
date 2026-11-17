package io.github.tux4us.miriquidihub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Akku-Monitor: zeichnet Ladestand, Temperatur und Spannung auf, solange der Bildschirm
 * geöffnet ist (kein Hintergrunddienst), und wertet Verbrauch und Ladegeschwindigkeit aus.
 */
class BatteryMonitorActivity : ToolActivity() {

    override val titleRes: Int = R.string.bm_title
    override val footnoteRes: Int = R.string.bm_footnote

    private lateinit var store: BatterySampleStore
    private lateinit var levelChart: LineChartView
    private lateinit var tempChart: LineChartView
    private var lastIntent: Intent? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) record(intent)
        }
    }

    override fun onToolCreated() {
        store = BatterySampleStore(File(filesDir, "battery_samples.json"))
        levelChart = addChart(R.string.bm_chart_level)
        tempChart = addChart(R.string.bm_chart_temp)
        setSecondaryAction(R.string.bm_clear) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.bm_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    store.clear()
                    lastIntent?.let { record(it) } ?: refresh()
                }
                .show()
        }
        val sticky = registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) record(sticky) else refresh()
    }

    override fun onRefresh() {
        lastIntent?.let { record(it) } ?: refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // nicht registriert
        }
    }

    private fun addChart(titleRes: Int): LineChartView {
        val title = TextView(this).apply {
            setText(titleRes)
            setPadding(0, 16, 0, 4)
        }
        val chart = LineChartView(this)
        binding.extraContainer.addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        binding.extraContainer.addView(chart, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return chart
    }

    private fun record(intent: Intent) {
        lastIntent = intent
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            store.add(
                BatterySample(
                    time = System.currentTimeMillis(),
                    level = level * 100 / scale,
                    tempTenthC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
                    voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
                    charging = isCharging(intent)
                )
            )
        }
        refresh()
    }

    private fun isCharging(intent: Intent): Boolean {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun refresh() {
        val samples = store.list()
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val left = samples.firstOrNull()?.let { format.format(Date(it.time)) } ?: ""
        val right = getString(R.string.bm_now)
        levelChart.setData(samples.map { it.level.toFloat() }, 0f, 100f, left, right) { "%.0f %%".format(it) }
        tempChart.setData(samples.map { it.tempTenthC / 10f }, null, null, left, right) { "%.1f °C".format(it) }

        val sections = mutableListOf<DiagSection>()
        lastIntent?.let { sections += currentSection(it) }
        sections += statsSection(samples)
        showSections(sections)
        val latest = samples.lastOrNull()
        setSummary(
            DiagStatus.INFO,
            if (latest == null) getString(R.string.bm_no_data) else getString(R.string.bm_summary, latest.level, samples.size)
        )
    }

    private fun currentSection(intent: Intent): DiagSection {
        val rows = mutableListOf<DiagRow>()
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) rows += DiagRow(getString(R.string.bm_level), "${level * 100 / scale} %")

        val statusRes = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> R.string.bm_status_charging
            BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.bm_status_discharging
            BatteryManager.BATTERY_STATUS_FULL -> R.string.bm_status_full
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.bm_status_not_charging
            else -> R.string.bm_status_unknown
        }
        rows += DiagRow(getString(R.string.bm_status), getString(statusRes))

        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (temp != Int.MIN_VALUE) {
            rows += DiagRow(
                getString(R.string.bm_temperature), "%.1f °C".format(temp / 10f),
                when {
                    temp >= 450 -> DiagStatus.ERROR
                    temp >= 400 -> DiagStatus.WARN
                    else -> DiagStatus.INFO
                }
            )
        }
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        if (voltage > 0) rows += DiagRow(getString(R.string.bm_voltage), "$voltage mV")

        val manager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentUa = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (currentUa != Int.MIN_VALUE && currentUa != 0) {
            rows += DiagRow(getString(R.string.bm_current), "${kotlin.math.abs(currentUa) / 1000} mA")
        }
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        rows += DiagRow(
            getString(R.string.bm_health), BatteryHealth.label(this, health),
            if (BatteryHealth.isCritical(health)) DiagStatus.ERROR else DiagStatus.INFO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val cycles = intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            if (cycles >= 0) rows += DiagRow(getString(R.string.bm_cycles), cycles.toString())
        }
        return DiagSection(getString(R.string.bm_section_current), rows)
    }

    private fun statsSection(samples: List<BatterySample>): DiagSection {
        val rows = mutableListOf<DiagRow>()
        val notAvailable = getString(R.string.bm_not_enough_data)
        rows += DiagRow(
            getString(R.string.bm_drain),
            BatteryStats.drainPerHour(samples)?.let { getString(R.string.bm_percent_per_hour, it) } ?: notAvailable
        )
        rows += DiagRow(
            getString(R.string.bm_charge_rate),
            BatteryStats.chargePerHour(samples)?.let { getString(R.string.bm_percent_per_hour, it) } ?: notAvailable
        )
        BatteryStats.temperatureRange(samples)?.let { (min, max) ->
            rows += DiagRow(getString(R.string.bm_temp_range), "%.1f … %.1f °C".format(min, max))
        }
        rows += DiagRow(getString(R.string.bm_samples), samples.size.toString())
        return DiagSection(getString(R.string.bm_section_stats), rows)
    }
}
