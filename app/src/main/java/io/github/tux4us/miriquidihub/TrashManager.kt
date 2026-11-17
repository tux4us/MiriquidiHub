package io.github.tux4us.miriquidihub

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/** Eintrag im app-internen Papierkorb. */
data class TrashEntry(
    val id: String,
    val originalPath: String,
    val name: String,
    val sizeBytes: Long,
    val deletedAt: Long,
    val isDirectory: Boolean
)

/**
 * App-eigener Papierkorb für den Speicheranalyse-Bereich. Gelöschte Elemente werden in
 * [trashDir]/items/<id> verschoben (gleiches Dateisystem, daher ohne Kopieren) und über
 * index.json verwaltet. Eine `.nomedia`-Datei verhindert, dass die Medienanzeige den Inhalt listet.
 *
 * Reine Datei-Logik ohne Android-Abhängigkeit, damit sie als JVM-Test prüfbar ist.
 */
class TrashManager(
    private val trashDir: File,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val gson = Gson()
    private val itemsDir get() = File(trashDir, ITEMS)
    private val indexFile get() = File(trashDir, INDEX)

    fun list(): List<TrashEntry> {
        if (!indexFile.isFile) return emptyList()
        val parsed: List<TrashEntry> = try {
            gson.fromJson<List<TrashEntry>>(indexFile.readText(), object : TypeToken<List<TrashEntry>>() {}.type)
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return parsed.filter { File(itemsDir, it.id).exists() }.sortedByDescending { it.deletedAt }
    }

    fun totalBytes(): Long = list().sumOf { it.sizeBytes }

    /** Verschiebt [file] in den Papierkorb. null bei Fehler (z. B. geschützter Pfad). */
    fun moveToTrash(file: File): TrashEntry? {
        if (!file.exists()) return null
        // Der Papierkorb darf sich nicht selbst enthalten.
        if (file.canonicalFile.startsWith(trashDir.canonicalFile)) return null
        prepare()
        val id = UUID.randomUUID().toString()
        val target = File(itemsDir, id)
        val size = sizeOf(file)
        val isDir = file.isDirectory
        val moved = file.renameTo(target) || copyThenDelete(file, target)
        if (!moved) return null
        val entry = TrashEntry(id, file.absolutePath, file.name, size, now(), isDir)
        write(list() + entry)
        return entry
    }

    /**
     * Stellt ein Element am ursprünglichen Ort wieder her. Existiert dort bereits etwas,
     * wird ein Zähler an den Namen angehängt. Rückgabe: Zielpfad oder null bei Fehler.
     */
    fun restore(id: String): File? {
        val entry = list().firstOrNull { it.id == id } ?: return null
        val source = File(itemsDir, id)
        if (!source.exists()) return null
        var target = File(entry.originalPath)
        target.parentFile?.mkdirs()
        var counter = 1
        while (target.exists()) {
            target = File(target.parentFile, restoredName(entry.name, counter++))
        }
        val moved = source.renameTo(target) || copyThenDelete(source, target)
        if (!moved) return null
        write(list().filterNot { it.id == id })
        return target
    }

    fun delete(id: String): Boolean {
        val item = File(itemsDir, id)
        val ok = !item.exists() || item.deleteRecursively()
        if (ok) write(list().filterNot { it.id == id })
        return ok
    }

    /** Löscht alle Elemente endgültig. Rückgabe: Anzahl entfernter Einträge. */
    fun empty(): Int {
        val entries = list()
        entries.forEach { File(itemsDir, it.id).deleteRecursively() }
        write(emptyList())
        return entries.size
    }

    /** Entfernt Elemente, die älter als [retentionDays] Tage sind. Rückgabe: Anzahl. */
    fun purgeExpired(retentionDays: Int = RETENTION_DAYS): Int {
        val limit = now() - retentionDays * DAY_MS
        val expired = list().filter { it.deletedAt < limit }
        expired.forEach { delete(it.id) }
        return expired.size
    }

    private fun prepare() {
        itemsDir.mkdirs()
        val marker = File(trashDir, ".nomedia")
        if (!marker.exists()) marker.createNewFile()
    }

    private fun write(entries: List<TrashEntry>) {
        prepare()
        indexFile.writeText(gson.toJson(entries))
    }

    private fun copyThenDelete(source: File, target: File): Boolean = try {
        val copied = if (source.isDirectory) source.copyRecursively(target, overwrite = false) else {
            source.copyTo(target, overwrite = false)
            true
        }
        copied && (source.deleteRecursively())
    } catch (e: Exception) {
        target.deleteRecursively()
        false
    }

    private fun sizeOf(file: File): Long =
        if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()

    companion object {
        const val RETENTION_DAYS = 30
        const val FOLDER_NAME = ".MiriquidiTrash"
        private const val ITEMS = "items"
        private const val INDEX = "index.json"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun restoredName(name: String, counter: Int): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} ($counter)${name.substring(dot)}" else "$name ($counter)"
        }
    }
}
