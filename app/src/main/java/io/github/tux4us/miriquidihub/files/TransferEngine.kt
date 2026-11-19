package io.github.tux4us.miriquidihub.files

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Verhalten, wenn das Ziel bereits existiert. */
enum class ConflictPolicy { SKIP, OVERWRITE, RENAME }

data class TransferProgress(
    val currentName: String,
    /** Bereits abgeschlossene Elemente (Dateien und Ordner). */
    val itemsDone: Int,
    /** Insgesamt übertragene Byte dieses Vorgangs. */
    val bytesDone: Long,
    /** Größe der aktuellen Datei, -1 wenn unbekannt. */
    val currentSize: Long,
    val currentDone: Long
)

data class TransferFailure(val path: String, val message: String)

data class TransferResult(
    val done: Int,
    val skipped: Int,
    val failures: List<TransferFailure>,
    val cancelled: Boolean
) {
    val hasProblems: Boolean get() = skipped > 0 || failures.isNotEmpty() || cancelled
}

class CancelledException : IOException("Abgebrochen")

/** Eine Datei aus einer beliebigen Strom-Quelle (z. B. Systemauswahl), die hochgeladen werden soll. */
class UploadItem(val name: String, val size: Long, val open: () -> InputStream)

/**
 * Kopieren, Verschieben und Löschen über beliebige [FileSource]-Paare hinweg, auch rekursiv.
 * Läuft blockierend (IO-Thread). [isCancelled] wird zwischen Elementen und bei jedem Lesevorgang
 * geprüft. Reine Logik ohne Android-Abhängigkeit.
 *
 * Konfliktbehandlung: [ConflictPolicy] gilt für den gesamten Vorgang einschließlich verschachtelter
 * Elemente. Ein Ordner, der am Ziel schon existiert, wird bei SKIP übersprungen, bei OVERWRITE
 * zusammengeführt und bei RENAME unter neuem Namen angelegt.
 */
class TransferEngine(
    private val isCancelled: () -> Boolean = { false },
    private val onProgress: (TransferProgress) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private var done = 0
    private var skipped = 0
    private var bytesDone = 0L
    private var currentName = ""
    private var currentSize = -1L
    private var currentDone = 0L
    private var lastEmit = 0L
    private val failures = ArrayList<TransferFailure>()

    fun transfer(
        from: FileSource,
        entries: List<FileEntry>,
        to: FileSource,
        targetDir: String,
        move: Boolean,
        policy: ConflictPolicy
    ): TransferResult {
        reset()
        var cancelled = false
        try {
            for (e in entries) {
                checkCancelled()
                try {
                    transferEntry(from, e, to, PathUtil.normalize(targetDir), move, policy)
                } catch (c: CancelledException) {
                    throw c
                } catch (ex: IOException) {
                    if (isCancelled()) throw CancelledException()
                    fail(e.path, ex)
                }
            }
        } catch (c: CancelledException) {
            cancelled = true
        }
        return TransferResult(done, skipped, failures.toList(), cancelled)
    }

    /** Schreibt Strom-Quellen als Dateien nach [targetDir]. Konfliktbehandlung wie bei [transfer]. */
    fun upload(items: List<UploadItem>, to: FileSource, targetDir: String, policy: ConflictPolicy): TransferResult {
        reset()
        var cancelled = false
        val dir = PathUtil.normalize(targetDir)
        try {
            for (item in items) {
                checkCancelled()
                try {
                    if (!PathUtil.isValidName(item.name)) throw IOException("Ungültiger Dateiname: ${item.name}")
                    var target = PathUtil.child(dir, item.name)
                    var overwrite = false
                    val existing = to.stat(target)
                    if (existing != null) {
                        when (policy) {
                            ConflictPolicy.SKIP -> { skipped++; continue }
                            ConflictPolicy.RENAME -> {
                                val unique = PathUtil.uniqueName(item.name) { to.exists(PathUtil.child(dir, it)) }
                                target = PathUtil.child(dir, unique)
                            }
                            ConflictPolicy.OVERWRITE -> {
                                if (existing.isDirectory) throw IOException("Am Ziel existiert ein Ordner mit diesem Namen")
                                overwrite = true
                            }
                        }
                    }
                    currentName = item.name; currentSize = item.size; currentDone = 0L
                    emit(force = true)
                    item.open().use { raw -> to.write(target, counting(raw), item.size, overwrite) }
                    done++
                    emit(force = true)
                } catch (c: CancelledException) {
                    throw c
                } catch (ex: IOException) {
                    if (isCancelled()) throw CancelledException()
                    fail(item.name, ex)
                }
            }
        } catch (c: CancelledException) {
            cancelled = true
        }
        return TransferResult(done, skipped, failures.toList(), cancelled)
    }

    fun delete(source: FileSource, entries: List<FileEntry>): TransferResult {
        reset()
        var cancelled = false
        try {
            for (e in entries) {
                checkCancelled()
                currentName = e.name
                emit(force = true)
                try {
                    source.delete(e.path)
                    done++
                } catch (ex: IOException) {
                    fail(e.path, ex)
                }
            }
        } catch (c: CancelledException) {
            cancelled = true
        }
        return TransferResult(done, skipped, failures.toList(), cancelled)
    }

    /** Für eigene Vorgänge (z. B. Packen): meldet den aktuellen Namen und prüft auf Abbruch. */
    fun step(name: String) {
        checkCancelled()
        currentName = name
        currentSize = -1L
        currentDone = 0L
        emit(force = true)
    }

    fun isCancelRequested(): Boolean = isCancelled()

    private fun reset() {
        done = 0; skipped = 0; bytesDone = 0L; currentName = ""; currentSize = -1L; currentDone = 0L
        lastEmit = 0L; failures.clear()
    }

    private fun fail(path: String, ex: Exception) {
        if (failures.size < MAX_FAILURES) failures.add(TransferFailure(path, ex.message ?: ex.javaClass.simpleName))
    }

    private fun checkCancelled() {
        if (isCancelled()) throw CancelledException()
    }

    private fun emit(force: Boolean = false) {
        val t = now()
        if (!force && t - lastEmit < EMIT_INTERVAL_MS) return
        lastEmit = t
        onProgress(TransferProgress(currentName, done, bytesDone, currentSize, currentDone))
    }

    private fun transferEntry(
        from: FileSource,
        entry: FileEntry,
        to: FileSource,
        dstDir: String,
        move: Boolean,
        policy0: ConflictPolicy
    ) {
        checkCancelled()
        val sameSource = from.id == to.id
        var policy = policy0
        var target = PathUtil.child(dstDir, entry.name)

        if (entry.isDirectory && sameSource && PathUtil.isInside(dstDir, entry.path)) {
            throw IOException("Der Zielordner liegt innerhalb von „${entry.name}“")
        }
        if (sameSource && PathUtil.normalize(entry.path) == target) {
            if (move) { skipped++; return }
            policy = ConflictPolicy.RENAME // Kopie im selben Ordner nie über das Original schreiben
        }

        var existing = to.stat(target)
        if (existing != null) {
            when (policy) {
                ConflictPolicy.SKIP -> { skipped++; return }
                ConflictPolicy.RENAME -> {
                    val unique = PathUtil.uniqueName(entry.name) { to.exists(PathUtil.child(dstDir, it)) }
                    target = PathUtil.child(dstDir, unique)
                    existing = null
                }
                ConflictPolicy.OVERWRITE -> if (existing.isDirectory != entry.isDirectory) {
                    to.delete(target)
                    existing = null
                }
            }
        }

        currentName = entry.name
        currentSize = entry.size
        currentDone = 0L
        emit(force = true)

        if (existing == null && sameSource) {
            val fast = if (move) from.tryMove(entry.path, target) else from.tryCopy(entry.path, target)
            if (fast) { done++; emit(force = true); return }
        }

        if (entry.isDirectory) {
            to.ensureDir(target)
            val problemsBefore = skipped + failures.size
            for (child in from.list(entry.path)) {
                checkCancelled()
                try {
                    transferEntry(from, child, to, target, move, policy)
                } catch (c: CancelledException) {
                    throw c
                } catch (ex: IOException) {
                    if (isCancelled()) throw CancelledException()
                    fail(child.path, ex)
                }
            }
            val clean = skipped + failures.size == problemsBefore
            if (move && clean) from.delete(entry.path)
            done++
        } else {
            copyStream(from, entry, to, target, overwrite = existing != null)
            if (move) from.delete(entry.path)
            done++
        }
        emit(force = true)
    }

    private fun copyStream(from: FileSource, entry: FileEntry, to: FileSource, target: String, overwrite: Boolean) {
        from.open(entry.path).use { raw -> to.write(target, counting(raw), entry.size, overwrite) }
    }

    /** Umhüllt einen Strom: zählt Byte, meldet Fortschritt und prüft bei jedem Lesen auf Abbruch. */
    private fun counting(raw: InputStream): InputStream = object : FilterInputStream(raw) {
        override fun read(): Int {
            checkCancelled()
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            checkCancelled()
            val n = super.read(buf, off, len)
            if (n > 0) count(n)
            return n
        }

        override fun markSupported(): Boolean = false
    }

    private fun count(n: Int) {
        bytesDone += n
        currentDone += n
        emit()
    }

    companion object {
        private const val MAX_FAILURES = 50
        private const val EMIT_INTERVAL_MS = 150L
    }
}

/** Rekursive Hilfen: Suche und Größenermittlung über beliebige Quellen. */
object FileTools {

    /**
     * Sucht ab [root] rekursiv nach Namen, die [query] enthalten (ohne Beachtung der Groß-/Kleinschreibung).
     * Begrenzt Treffer, Tiefe und besuchte Ordner, damit die Suche auf großen oder entfernten Bäumen endet.
     */
    fun search(
        source: FileSource,
        root: String,
        query: String,
        isCancelled: () -> Boolean,
        limit: Int = 500,
        maxDepth: Int = 12,
        maxFolders: Int = 3000,
        onHit: (FileEntry) -> Unit
    ): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        var hits = 0
        var folders = 0
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(PathUtil.normalize(root) to 0)
        while (queue.isNotEmpty() && hits < limit && folders < maxFolders) {
            if (isCancelled()) break
            val (dir, depth) = queue.removeFirst()
            val children = try { source.list(dir) } catch (e: IOException) { continue }
            folders++
            for (c in children) {
                if (c.name.lowercase().contains(q)) {
                    onHit(c)
                    if (++hits >= limit) break
                }
                if (c.isDirectory && depth < maxDepth) queue.add(c.path to depth + 1)
            }
        }
        return hits
    }

    /** Anzahl Dateien und Gesamtgröße unterhalb von [entry]. */
    fun measure(source: FileSource, entry: FileEntry, isCancelled: () -> Boolean): Pair<Int, Long> {
        if (!entry.isDirectory) return 1 to maxOf(entry.size, 0L)
        var files = 0
        var bytes = 0L
        val stack = ArrayDeque<String>()
        stack.add(entry.path)
        while (stack.isNotEmpty()) {
            if (isCancelled()) break
            val dir = stack.removeLast()
            val children = try { source.list(dir) } catch (e: IOException) { continue }
            for (c in children) {
                if (c.isDirectory) stack.add(c.path) else { files++; bytes += maxOf(c.size, 0L) }
            }
        }
        return files to bytes
    }
}
