package io.github.tux4us.miriquidihub

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Messpunkt des Akku-Monitors. [tempTenthC] in Zehntel °C, [voltageMv] in Millivolt. */
data class BatterySample(
    val time: Long,
    val level: Int,
    val tempTenthC: Int,
    val voltageMv: Int,
    val charging: Boolean
)

/** Speichert Akku-Messpunkte in einer JSON-Datei (höchstens [maxSamples], älteste fallen heraus). */
class BatterySampleStore(private val file: File, private val maxSamples: Int = 2000) {

    private val gson = Gson()

    fun list(): List<BatterySample> {
        if (!file.isFile) return emptyList()
        return try {
            gson.fromJson<List<BatterySample>>(file.readText(), object : TypeToken<List<BatterySample>>() {}.type)
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fügt einen Messpunkt hinzu. Innerhalb von [minIntervalMs] nach dem letzten Punkt wird nur
     * gespeichert, wenn sich Ladestand oder Ladezustand geändert haben. Rückgabe: gespeichert?
     */
    fun add(sample: BatterySample, minIntervalMs: Long = 60_000L): Boolean {
        val current = list()
        val last = current.lastOrNull()
        if (last != null && sample.time - last.time < minIntervalMs &&
            last.level == sample.level && last.charging == sample.charging
        ) return false
        file.writeText(gson.toJson((current + sample).takeLast(maxSamples)))
        return true
    }

    fun clear() {
        file.delete()
    }
}

object BatteryStats {

    private const val MAX_GAP_MS = 6L * 60 * 60 * 1000
    private const val MIN_SPAN_MS = 10L * 60 * 1000

    /** Verbrauch in Prozentpunkten pro Stunde über alle Entladephasen; null bei zu wenig Daten. */
    fun drainPerHour(samples: List<BatterySample>): Double? = rate(samples, charging = false)

    /** Ladegeschwindigkeit in Prozentpunkten pro Stunde über alle Ladephasen; null bei zu wenig Daten. */
    fun chargePerHour(samples: List<BatterySample>): Double? = rate(samples, charging = true)

    private fun rate(samples: List<BatterySample>, charging: Boolean): Double? {
        var points = 0
        var millis = 0L
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = b.time - a.time
            if (dt <= 0 || dt > MAX_GAP_MS) continue
            if (a.charging != charging || b.charging != charging) continue
            val delta = if (charging) b.level - a.level else a.level - b.level
            if (delta < 0) continue
            points += delta
            millis += dt
        }
        if (millis < MIN_SPAN_MS) return null
        return points / (millis / 3_600_000.0)
    }

    /** Höchste und niedrigste Temperatur in °C; null ohne Daten. */
    fun temperatureRange(samples: List<BatterySample>): Pair<Float, Float>? {
        if (samples.isEmpty()) return null
        val temps = samples.map { it.tempTenthC / 10f }
        return temps.min() to temps.max()
    }
}
