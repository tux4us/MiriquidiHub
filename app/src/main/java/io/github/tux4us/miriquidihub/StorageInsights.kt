package io.github.tux4us.miriquidihub

import java.io.File
import java.security.MessageDigest

data class FileEntry(val file: File, val sizeBytes: Long, val lastModified: Long)

data class DuplicateGroup(val sizeBytes: Long, val files: List<File>) {
    /** Speicher, der durch Entfernen aller Kopien bis auf eine frei würde. */
    val wastedBytes: Long get() = sizeBytes * (files.size - 1)
}

/** Filterkriterien für [StorageInsights.scan]. Leere/Null-Werte bedeuten „keine Einschränkung“. */
data class InsightFilter(
    val categories: Set<StorageCategory>? = null,
    val minBytes: Long = 0L,
    val olderThanDays: Int? = null
)

/**
 * Dateianalyse (größte Dateien, Duplikate, Filter) ohne Android-Abhängigkeit. Der Papierkorb
 * wird übersprungen; symbolische Verknüpfungen von Ordnern werden nicht verfolgt.
 * [isCancelled] wird regelmäßig geprüft, damit die Suche abbrechbar bleibt.
 */
object StorageInsights {

    private const val PARTIAL_HASH_BYTES = 64 * 1024L

    fun categoryFor(fileName: String): StorageCategory = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "svg" -> StorageCategory.IMAGES
        "mp4", "mkv", "webm", "avi", "mov", "3gp" -> StorageCategory.VIDEOS
        "mp3", "flac", "ogg", "opus", "wav", "m4a", "aac" -> StorageCategory.AUDIO
        "pdf", "doc", "docx", "txt", "odt", "ods", "xls", "xlsx", "epub", "md" -> StorageCategory.DOCUMENTS
        "apk", "xapk", "apks" -> StorageCategory.APPS
        else -> StorageCategory.OTHER
    }

    /** Liefert die [limit] größten Dateien, die dem Filter entsprechen, absteigend nach Größe. */
    fun scan(
        root: File,
        filter: InsightFilter = InsightFilter(),
        limit: Int = 50,
        now: Long = System.currentTimeMillis(),
        isCancelled: () -> Boolean = { false }
    ): List<FileEntry> {
        val cutoff = filter.olderThanDays?.let { now - it * 24L * 60 * 60 * 1000 }
        val result = ArrayList<FileEntry>()
        for (file in files(root, isCancelled)) {
            val size = file.length()
            if (size < filter.minBytes) continue
            if (filter.categories != null && categoryFor(file.name) !in filter.categories) continue
            val modified = file.lastModified()
            if (cutoff != null && modified >= cutoff) continue
            result += FileEntry(file, size, modified)
            // Speicherbedarf begrenzen: bei großen Treffermengen nur die größten behalten.
            if (result.size > limit * 20) {
                result.sortByDescending { it.sizeBytes }
                while (result.size > limit) result.removeAt(result.lastIndex)
            }
        }
        return result.sortedByDescending { it.sizeBytes }.take(limit)
    }

    /**
     * Findet identische Dateien: zuerst Gruppierung nach Größe, dann Hash der ersten 64 KiB,
     * zuletzt SHA-256 über die gesamte Datei. Dateien unter [minBytes] werden ignoriert.
     * Ergebnis absteigend nach vermeidbarem Speicher.
     */
    fun findDuplicates(
        root: File,
        minBytes: Long = 1024L * 1024,
        isCancelled: () -> Boolean = { false }
    ): List<DuplicateGroup> {
        val bySize = HashMap<Long, MutableList<File>>()
        for (file in files(root, isCancelled)) {
            val size = file.length()
            if (size >= minBytes && size > 0) bySize.getOrPut(size) { mutableListOf() } += file
        }
        val groups = ArrayList<DuplicateGroup>()
        for ((size, candidates) in bySize) {
            if (candidates.size < 2) continue
            if (isCancelled()) break
            val byPartial = candidates.groupBy { hash(it, PARTIAL_HASH_BYTES) }.filterKeys { it != null }
            for ((_, partialMatches) in byPartial) {
                if (partialMatches.size < 2) continue
                val byFull = partialMatches.groupBy { hash(it, Long.MAX_VALUE) }.filterKeys { it != null }
                for ((_, same) in byFull) {
                    if (same.size >= 2) groups += DuplicateGroup(size, same.sortedBy { it.lastModified() })
                }
            }
        }
        return groups.sortedByDescending { it.wastedBytes }
    }

    /** SHA-256 über höchstens [maxBytes] Bytes; null bei Lesefehler. */
    fun hash(file: File, maxBytes: Long): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    private fun files(root: File, isCancelled: () -> Boolean): Sequence<File> =
        root.walkTopDown()
            .onEnter { dir -> !isCancelled() && dir.name != TrashManager.FOLDER_NAME && !isSymbolicLink(dir) }
            .filter { it.isFile }

    private fun isSymbolicLink(dir: File): Boolean = try {
        val parent = dir.parentFile
        parent != null && dir.canonicalFile != File(parent.canonicalFile, dir.name)
    } catch (e: Exception) {
        true
    }
}
