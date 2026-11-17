package io.github.tux4us.miriquidihub

/** Ein Wert aus dem Textblock eines Geräte-Reports ("--- ABSCHNITT ---" / "SCHLÜSSEL: Wert"). */
data class SpecEntry(val section: String, val key: String, val value: String)

/** Änderung zwischen zwei Reports. [old] bzw. [new] sind null bei entfernten bzw. neuen Einträgen. */
data class SpecChange(val section: String, val key: String, val old: String?, val new: String?) {
    val kind: Kind
        get() = when {
            old == null -> Kind.ADDED
            new == null -> Kind.REMOVED
            else -> Kind.CHANGED
        }

    enum class Kind { CHANGED, ADDED, REMOVED }
}

data class SpecDiffResult(val changes: List<SpecChange>, val unchanged: Int)

/**
 * Zerlegt den Report-Text in Einträge und vergleicht zwei Reports. Reine Logik ohne
 * Android-Abhängigkeit (lokal per Unit-Test prüfbar).
 */
object SpecParser {

    fun parse(text: String): List<SpecEntry> {
        val entries = mutableListOf<SpecEntry>()
        var section = ""
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("---") && line.endsWith("---") && line.length > 6) {
                section = line.trim('-', ' ')
                continue
            }
            val idx = line.indexOf(": ")
            if (idx <= 0) continue
            var key = line.substring(0, idx).trim()
            var value = line.substring(idx + 2).trim()
            // Testzeilen "[OK] Touchscreen: Detail": Status gehört zum Wert, nicht zum Schlüssel.
            if (key.startsWith("[")) {
                val close = key.indexOf("] ")
                if (close > 0) {
                    value = key.substring(0, close + 1) + " " + value
                    key = key.substring(close + 2)
                }
            }
            entries += SpecEntry(section, key, value)
        }
        return entries
    }

    fun diff(before: String, after: String): SpecDiffResult {
        val a = parse(before)
        val b = parse(after)
        val mapA = LinkedHashMap<Pair<String, String>, String>()
        a.forEach { mapA[it.section to it.key] = it.value }
        val mapB = LinkedHashMap<Pair<String, String>, String>()
        b.forEach { mapB[it.section to it.key] = it.value }

        val changes = mutableListOf<SpecChange>()
        var unchanged = 0
        for ((k, v) in mapA) {
            val other = mapB[k]
            when {
                other == null -> changes += SpecChange(k.first, k.second, v, null)
                other != v -> changes += SpecChange(k.first, k.second, v, other)
                else -> unchanged++
            }
        }
        for ((k, v) in mapB) {
            if (!mapA.containsKey(k)) changes += SpecChange(k.first, k.second, null, v)
        }
        return SpecDiffResult(changes, unchanged)
    }
}
