package io.github.tux4us.miriquidihub

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Benutzerdefinierte Testhosts und zuletzt genutzte Adresse des Durchsatztests. */
class NetworkHostPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("netdiag_settings", Context.MODE_PRIVATE)

    fun hosts(): List<String> {
        val raw = prefs.getString(KEY_HOSTS, null) ?: return DEFAULT_HOSTS
        val parsed = parse(raw).valid
        return parsed.ifEmpty { DEFAULT_HOSTS }
    }

    fun hostsAsText(): String = hosts().joinToString("\n")

    fun saveHosts(hosts: List<String>) {
        prefs.edit().putString(KEY_HOSTS, hosts.joinToString("\n")).apply()
    }

    fun resetHosts() {
        prefs.edit().remove(KEY_HOSTS).apply()
    }

    fun lastThroughputUrl(): String = prefs.getString(KEY_THROUGHPUT_URL, "") ?: ""

    fun saveThroughputUrl(url: String) {
        prefs.edit().putString(KEY_THROUGHPUT_URL, url).apply()
    }

    data class ParseResult(val valid: List<String>, val invalid: List<String>)

    companion object {
        private const val KEY_HOSTS = "hosts"
        private const val KEY_THROUGHPUT_URL = "throughput_url"
        const val MAX_HOSTS = 8
        val DEFAULT_HOSTS: List<String> = listOf("f-droid.org", "github.com", "wikipedia.org", "mozilla.org")

        private val HOST_REGEX =
            Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

        /** Zerlegt eine Eingabe (Zeilen, Leerzeichen oder Kommas) in gültige und ungültige Hostnamen. */
        fun parse(text: String): ParseResult {
            val valid = LinkedHashSet<String>()
            val invalid = mutableListOf<String>()
            for (token in text.split('\n', ' ', ',', ';', '\t')) {
                var t = token.trim().lowercase()
                if (t.isEmpty()) continue
                t = t.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
                if (HOST_REGEX.matches(t)) valid += t else invalid += token.trim()
            }
            return ParseResult(valid.take(MAX_HOSTS), invalid)
        }
    }
}

/** Zusammenfassung eines Diagnoselaufs für den Verlauf. Nicht ermittelbare Mittelwerte sind -1. */
data class NetRun(
    val timestamp: Long,
    val errors: Int,
    val warnings: Int,
    val avgDnsMs: Long,
    val avgTcpMs: Long
)

class NetworkHistory(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("netdiag_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun list(): List<NetRun> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            gson.fromJson<List<NetRun>>(json, object : TypeToken<List<NetRun>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(run: NetRun) {
        val updated = (listOf(run) + list()).take(MAX_ENTRIES)
        prefs.edit().putString(KEY, gson.toJson(updated)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "history_json"
        private const val MAX_ENTRIES = 50
    }
}
