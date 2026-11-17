package io.github.tux4us.miriquidihub

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Listet alle Sensoren des Geräts mit technischen Daten; Antippen zeigt Live-Werte. */
class SensorExplorerActivity : ToolActivity() {

    override val titleRes: Int = R.string.se_title
    override val footnoteRes: Int = R.string.se_footnote
    override val hasRefreshAction: Boolean = false

    override fun onToolCreated() {
        val manager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = manager.getSensorList(Sensor.TYPE_ALL).sortedWith(compareBy({ it.stringType }, { it.name }))
        val rows = sensors.map { sensor ->
            DiagRow(
                sensor.name,
                getString(
                    R.string.se_row,
                    typeName(sensor), sensor.vendor, sensor.maximumRange, sensor.resolution, sensor.power
                ),
                DiagStatus.INFO
            ) { showLive(manager, sensor) }
        }
        showSections(listOf(DiagSection(getString(R.string.se_section, sensors.size), rows)))
        setSummary(DiagStatus.INFO, getString(R.string.se_summary, sensors.size))
    }

    private fun typeName(sensor: Sensor): String = sensor.stringType.removePrefix("android.sensor.")

    private fun showLive(manager: SensorManager, sensor: Sensor) {
        val view = TextView(this).apply {
            setPadding(48, 24, 48, 0)
            textSize = 16f
            setText(R.string.se_waiting)
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                view.text = event.values.take(MAX_VALUES).mapIndexed { i, v -> "[$i] %.4f".format(v) }.joinToString("\n")
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        val oneShot = sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT
        val registered = !oneShot && manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        if (!registered) view.setText(if (oneShot) R.string.se_one_shot else R.string.se_unavailable)
        AlertDialog.Builder(this)
            .setTitle(sensor.name)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .setOnDismissListener { manager.unregisterListener(listener) }
            .show()
    }

    companion object {
        private const val MAX_VALUES = 8
    }
}
