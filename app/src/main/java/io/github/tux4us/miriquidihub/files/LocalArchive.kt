package io.github.tux4us.miriquidihub.files

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** ZIP-Packen und -Entpacken für lokale Dateien. Reine Java-IO-Logik. */
object LocalArchive {

    private const val BUFFER = 64 * 1024

    /**
     * Packt [sources] (Dateien und Ordner) in [out]. Geschrieben wird zuerst in eine Teildatei,
     * die erst bei Erfolg umbenannt wird. Gibt die Anzahl gepackter Dateien zurück.
     */
    fun zip(sources: List<File>, out: File, isCancelled: () -> Boolean, onFile: (String) -> Unit = {}): Int {
        if (out.exists()) throw AlreadyExistsException(out.path)
        val part = File(out.parentFile, out.name + LocalSource.PART_SUFFIX)
        var count = 0
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(part))).use { zos ->
                fun add(file: File, entryName: String) {
                    if (isCancelled()) throw CancelledException()
                    // Die Zieldatei selbst nie in sich packen; Symlinks nicht verfolgen.
                    if (file.canonicalFile == out.canonicalFile || file.canonicalFile == part.canonicalFile) return
                    if (LocalSource.isSymlink(file)) return
                    if (file.isDirectory) {
                        zos.putNextEntry(ZipEntry("$entryName/").apply { time = file.lastModified() })
                        zos.closeEntry()
                        file.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$entryName/${it.name}") }
                    } else if (file.isFile) {
                        onFile(file.name)
                        zos.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                        BufferedInputStream(FileInputStream(file)).use { input ->
                            val buf = ByteArray(BUFFER)
                            while (true) {
                                if (isCancelled()) throw CancelledException()
                                val n = input.read(buf)
                                if (n < 0) break
                                zos.write(buf, 0, n)
                            }
                        }
                        zos.closeEntry()
                        count++
                    }
                }
                for (f in sources) add(f, f.name)
            }
            if (!part.renameTo(out)) throw IOException("ZIP-Datei nicht schreibbar: ${out.path}")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        return count
    }

    /**
     * Entpackt [zip] nach [destDir]. Einträge mit Pfaden außerhalb von [destDir] ("Zip Slip") und
     * Einträge, die vorhandene Dateien überschreiben würden, werden abgelehnt.
     * Gibt die Anzahl entpackter Dateien zurück.
     */
    fun unzip(zip: File, destDir: File, isCancelled: () -> Boolean, onFile: (String) -> Unit = {}): Int {
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("Zielordner nicht anlegbar")
        val rootPath = destDir.canonicalPath
        var count = 0
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                if (isCancelled()) throw CancelledException()
                val e = entries.nextElement()
                val target = File(destDir, e.name)
                val canonical = target.canonicalPath
                if (canonical != rootPath && !canonical.startsWith(rootPath + File.separator)) {
                    throw IOException("Ungültiger Pfad im Archiv: ${e.name}")
                }
                if (e.isDirectory) {
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("Ordner nicht anlegbar: ${e.name}")
                    continue
                }
                if (target.exists()) throw AlreadyExistsException(e.name)
                target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Ordner nicht anlegbar") }
                onFile(target.name)
                val part = File(target.parentFile, target.name + LocalSource.PART_SUFFIX)
                try {
                    zf.getInputStream(e).use { input ->
                        BufferedOutputStream(FileOutputStream(part)).use { out ->
                            val buf = ByteArray(BUFFER)
                            while (true) {
                                if (isCancelled()) throw CancelledException()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    if (e.time > 0) part.setLastModified(e.time)
                    if (!part.renameTo(target)) throw IOException("Datei nicht schreibbar: ${e.name}")
                    count++
                } catch (ex: Exception) {
                    part.delete()
                    throw ex
                }
            }
        }
        return count
    }

    /** "bilder.zip" → "bilder"; ohne .zip-Endung → "Name_entpackt". */
    fun folderNameFor(zipName: String): String {
        val base = if (zipName.lowercase().endsWith(".zip")) zipName.dropLast(4) else zipName + "_entpackt"
        return base.ifBlank { "Archiv" }
    }
}
