package io.github.tux4us.miriquidihub

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Belegung des Datenspeichers an einem Tag (day = yyyy-MM-dd). */
data class StorageSnapshot(val day: String, val usedBytes: Long, val totalBytes: Long)

/** Tägliche Momentaufnahmen der Speicherbelegung für den Verlauf. */
class StorageHistory(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("storage_history", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun list(): List<StorageSnapshot> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            gson.fromJson<List<StorageSnapshot>>(json, object : TypeToken<List<StorageSnapshot>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Legt den Wert für heute an bzw. aktualisiert ihn (höchstens ein Eintrag je Tag). */
    fun record(usedBytes: Long, totalBytes: Long, timeMillis: Long = System.currentTimeMillis()) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timeMillis))
        prefs.edit().putString(KEY, gson.toJson(merge(list(), StorageSnapshot(day, usedBytes, totalBytes)))).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "snapshots"
        const val MAX_DAYS = 120

        /** Ersetzt einen vorhandenen Eintrag desselben Tages, sortiert aufsteigend und kürzt auf [MAX_DAYS]. */
        fun merge(existing: List<StorageSnapshot>, snapshot: StorageSnapshot): List<StorageSnapshot> =
            (existing.filterNot { it.day == snapshot.day } + snapshot).sortedBy { it.day }.takeLast(MAX_DAYS)
    }
}
