package io.github.tux4us.miriquidihub

import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export und Import des Berichtsarchivs als ZIP: `reports.json` plus angehängte Bilder unter
 * `images/`. Beim Import werden Dateinamen bereinigt (kein Pfad aus dem Archiv wird verwendet)
 * und Größen begrenzt. Reine JVM-Logik.
 */
object ReportArchive {

    const val INDEX_NAME = "reports.json"
    const val FORMAT_VERSION = 1
    private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
    private const val MAX_INDEX_BYTES = 20L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024

    private data class Index(val version: Int, val reports: List<DeviceReport>)

    private val gson = Gson()

    /** Schreibt das Archiv; Rückgabe: Anzahl enthaltener Berichte. Nicht vorhandene Bilder werden ausgelassen. */
    fun write(reports: List<DeviceReport>, out: OutputStream): Int {
        ZipOutputStream(out).use { zip ->
            val exported = reports.mapIndexed { reportIndex, report ->
                val names = mutableListOf<String>()
                report.imagePaths.forEachIndexed { imageIndex, path ->
                    val file = File(path)
                    if (file.isFile) {
                        val name = "images/${reportIndex}_${imageIndex}_${sanitize(file.name)}"
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        names += name
                    }
                }
                report.copy(imagePaths = names)
            }
            zip.putNextEntry(ZipEntry(INDEX_NAME))
            zip.write(gson.toJson(Index(FORMAT_VERSION, exported)).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return reports.size
    }

    /**
     * Liest ein Archiv. Bilder werden nach [imageDir] geschrieben; die Berichte verweisen danach
     * auf diese Dateien. Wirft [IllegalArgumentException] bei fehlendem/ungültigem Index.
     */
    fun read(input: InputStream, imageDir: File): List<DeviceReport> {
        imageDir.mkdirs()
        var indexJson: String? = null
        val written = HashMap<String, File>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    if (name == INDEX_NAME) {
                        indexJson = readLimited(zip, MAX_INDEX_BYTES).toString(Charsets.UTF_8)
                    } else if (name.startsWith("images/")) {
                        val target = File(imageDir, "import_${System.nanoTime()}_${sanitize(name.substringAfterLast('/'))}")
                        target.outputStream().use { out ->
                            val bytes = readLimited(zip, MAX_IMAGE_BYTES)
                            total += bytes.size
                            require(total <= MAX_TOTAL_BYTES) { "Archiv zu groß" }
                            out.write(bytes)
                        }
                        written[name] = target
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val json = indexJson ?: throw IllegalArgumentException("reports.json fehlt")
        val index = try {
            gson.fromJson(json, Index::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Ungültiges Archiv", e)
        } ?: throw IllegalArgumentException("Ungültiges Archiv")
        require(index.version in 1..FORMAT_VERSION) { "Nicht unterstützte Version" }
        return index.reports.map { report ->
            report.copy(imagePaths = report.imagePaths.mapNotNull { written[it]?.absolutePath })
        }
    }

    fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "file" }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            require(total <= limit) { "Eintrag zu groß" }
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }
}
