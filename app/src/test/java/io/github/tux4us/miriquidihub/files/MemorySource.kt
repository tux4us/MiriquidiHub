package io.github.tux4us.miriquidihub.files

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** In-Memory-Quelle für Tests; verhält sich wie ein Server ohne serverseitiges Kopieren/Verschieben. */
class MemorySource(override val id: String = "mem", override val label: String = "Memory") : FileSource {
    override val isRemote = true
    private val dirs = linkedSetOf("/")
    private val files = linkedMapOf<String, ByteArray>()
    var failOnWriteOf: String? = null

    fun putFile(path: String, text: String) {
        val p = PathUtil.normalize(path)
        var parent = PathUtil.parent(p)
        while (parent != null) { dirs.add(parent); parent = PathUtil.parent(parent) }
        files[p] = text.toByteArray()
    }

    fun text(path: String): String? = files[PathUtil.normalize(path)]?.toString(Charsets.UTF_8)
    fun hasDir(path: String) = PathUtil.normalize(path) in dirs

    override fun list(path: String): List<FileEntry> {
        val p = PathUtil.normalize(path)
        if (p !in dirs) throw RemoteException("nicht gefunden", 404)
        val out = ArrayList<FileEntry>()
        dirs.filter { it != "/" && PathUtil.parent(it) == p }
            .forEach { out.add(FileEntry(PathUtil.name(it), it, true, -1, 0)) }
        files.filter { PathUtil.parent(it.key) == p }
            .forEach { out.add(FileEntry(PathUtil.name(it.key), it.key, false, it.value.size.toLong(), 0)) }
        return out
    }

    override fun stat(path: String): FileEntry? {
        val p = PathUtil.normalize(path)
        if (p in dirs) return FileEntry(PathUtil.name(p), p, true, -1, 0)
        return files[p]?.let { FileEntry(PathUtil.name(p), p, false, it.size.toLong(), 0) }
    }

    override fun open(path: String): InputStream =
        ByteArrayInputStream(files[PathUtil.normalize(path)] ?: throw RemoteException("nicht gefunden", 404))

    override fun write(path: String, input: InputStream, length: Long, overwrite: Boolean) {
        val p = PathUtil.normalize(path)
        if (!overwrite && (p in files || p in dirs)) throw AlreadyExistsException(p)
        if (PathUtil.parent(p) !in dirs) throw RemoteException("Elternordner fehlt", 409)
        val data = input.readBytes()
        if (failOnWriteOf == PathUtil.name(p)) throw IOException("Schreibfehler")
        files[p] = data
    }

    override fun mkdir(path: String) {
        val p = PathUtil.normalize(path)
        if (p in dirs || p in files) throw AlreadyExistsException(p)
        if (PathUtil.parent(p) !in dirs) throw RemoteException("Elternordner fehlt", 409)
        dirs.add(p)
    }

    override fun delete(path: String) {
        val p = PathUtil.normalize(path)
        files.keys.filter { PathUtil.isInside(it, p) }.forEach { files.remove(it) }
        dirs.removeAll(dirs.filter { PathUtil.isInside(it, p) && it != "/" }.toSet())
    }

    override fun rename(path: String, newName: String): String = throw UnsupportedOperationException()
}
