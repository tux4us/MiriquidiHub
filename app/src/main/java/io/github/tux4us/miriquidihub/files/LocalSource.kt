package io.github.tux4us.miriquidihub.files

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Lokaler Speicher. Der virtuelle Pfad "/" entspricht [rootDir] (z. B. der interne Speicher oder
 * eine SD-Karte); über die Wurzel hinaus kann nicht navigiert werden.
 */
class LocalSource(
    override val id: String,
    override val label: String,
    val rootDir: File
) : FileSource {

    override val isRemote: Boolean get() = false

    private val rootCanonical: File = rootDir.canonicalFile

    /** Wandelt einen virtuellen Pfad in eine echte Datei um. */
    fun toFile(path: String): File {
        val p = PathUtil.normalize(path)
        return if (p == PathUtil.ROOT) rootDir else File(rootDir, p.substring(1))
    }

    fun toPath(file: File): String {
        val base = rootCanonical.path
        val c = file.canonicalPath
        if (c == base) return PathUtil.ROOT
        if (!c.startsWith("$base/")) throw IOException("Pfad liegt außerhalb des Speichers")
        return PathUtil.normalize(c.removePrefix(base))
    }

    private fun entryOf(parentPath: String, f: File): FileEntry {
        val dir = f.isDirectory
        return FileEntry(
            name = f.name,
            path = PathUtil.child(parentPath, f.name),
            isDirectory = dir,
            size = if (dir) -1L else f.length(),
            modified = f.lastModified()
        )
    }

    override fun list(path: String): List<FileEntry> {
        val dir = toFile(path)
        if (!dir.isDirectory) throw RemoteException("Kein Ordner: $path", 404)
        val children = dir.listFiles() ?: throw RemoteException("Zugriff verweigert: $path", 403)
        val parent = PathUtil.normalize(path)
        return children.map { entryOf(parent, it) }
    }

    override fun stat(path: String): FileEntry? {
        val f = toFile(path)
        if (!f.exists() && !isSymlink(f)) return null
        val p = PathUtil.normalize(path)
        return FileEntry(
            name = if (p == PathUtil.ROOT) label else f.name,
            path = p,
            isDirectory = f.isDirectory,
            size = if (f.isDirectory) -1L else f.length(),
            modified = f.lastModified()
        )
    }

    override fun open(path: String): InputStream {
        val f = toFile(path)
        if (!f.isFile) throw RemoteException("Keine Datei: $path", 404)
        return FileInputStream(f)
    }

    override fun write(path: String, input: InputStream, length: Long, overwrite: Boolean) {
        val target = toFile(path)
        if (!overwrite && (target.exists() || isSymlink(target))) throw AlreadyExistsException(path)
        val parent = target.parentFile ?: throw IOException("Kein übergeordneter Ordner")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Ordner nicht anlegbar: ${parent.path}")
        // Erst in eine Teildatei schreiben, damit ein Abbruch kein halbes Ziel hinterlässt.
        val part = File(parent, target.name + PART_SUFFIX)
        try {
            FileOutputStream(part).use { out -> input.copyTo(out, BUFFER) }
            if (overwrite && target.exists() && !target.isDirectory) target.delete()
            if (!part.renameTo(target)) throw IOException("Datei nicht schreibbar: $path")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    override fun mkdir(path: String) {
        val d = toFile(path)
        if (d.exists()) throw AlreadyExistsException(path)
        if (!d.mkdir()) throw IOException("Ordner nicht anlegbar: $path")
    }

    override fun delete(path: String) {
        val f = toFile(path)
        if (PathUtil.normalize(path) == PathUtil.ROOT) throw IOException("Die Wurzel kann nicht gelöscht werden")
        if (!f.exists() && !isSymlink(f)) return
        if (!deleteRecursive(f)) throw IOException("Löschen fehlgeschlagen: $path")
    }

    override fun rename(path: String, newName: String): String {
        if (!PathUtil.isValidName(newName)) throw IOException("Ungültiger Name: $newName")
        val src = toFile(path)
        if (!src.exists()) throw RemoteException("Nicht gefunden: $path", 404)
        val parent = PathUtil.parent(path) ?: throw IOException("Die Wurzel kann nicht umbenannt werden")
        val newPath = PathUtil.child(parent, newName)
        val dst = toFile(newPath)
        if (dst.exists() && !src.canonicalFile.equals(dst.canonicalFile)) throw AlreadyExistsException(newPath)
        if (!src.renameTo(dst)) throw IOException("Umbenennen fehlgeschlagen: $path")
        return newPath
    }

    override fun tryMove(src: String, dst: String): Boolean {
        val s = toFile(src)
        val d = toFile(dst)
        if (d.exists()) return false
        d.parentFile?.mkdirs()
        return s.renameTo(d)
    }

    private fun deleteRecursive(f: File): Boolean {
        if (f.isDirectory && !isSymlink(f)) {
            f.listFiles()?.forEach { if (!deleteRecursive(it)) return false }
        }
        return f.delete()
    }

    companion object {
        const val PART_SUFFIX = ".fm-part"
        private const val BUFFER = 64 * 1024

        /** Erkennt symbolische Links ohne java.nio (minSdk 24). */
        fun isSymlink(f: File): Boolean = try {
            val parent = f.parentFile
            val canonical = if (parent == null) f.canonicalFile else File(parent.canonicalFile, f.name)
            canonical.canonicalFile != canonical.absoluteFile
        } catch (e: IOException) {
            false
        }
    }
}
