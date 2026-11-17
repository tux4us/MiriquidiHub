package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReportArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun report(id: String, images: List<String> = emptyList()) = DeviceReport(
        id = id, timestamp = 1L, title = "T$id", note = "N", specs = "--- A ---\nK: V",
        shelterActive = false, securityModeActive = true, vpnActive = false, imagePaths = images
    )

    @Test
    fun roundTrip_keepsReportsAndImages() {
        val img = File(tmp.root, "shot.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val out = ByteArrayOutputStream()
        val count = ReportArchive.write(listOf(report("a", listOf(img.absolutePath, "/missing.png")), report("b")), out)
        assertEquals(2, count)

        val dir = File(tmp.root, "imported")
        val read = ReportArchive.read(ByteArrayInputStream(out.toByteArray()), dir)
        assertEquals(listOf("a", "b"), read.map { it.id })
        assertEquals("Ta", read[0].title)
        assertEquals(1, read[0].imagePaths.size)
        assertTrue(File(read[0].imagePaths[0]).readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(File(read[0].imagePaths[0]).parentFile!!.canonicalFile == dir.canonicalFile)
        assertTrue(read[1].imagePaths.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun read_rejectsArchiveWithoutIndex() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("other.txt")); it.write(1); it.closeEntry() }
        ReportArchive.read(ByteArrayInputStream(out.toByteArray()), File(tmp.root, "x"))
    }

    @Test
    fun read_neverWritesOutsideImageDir() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("images/../../evil.txt")); zip.write(9); zip.closeEntry()
            zip.putNextEntry(ZipEntry(ReportArchive.INDEX_NAME))
            zip.write("""{"version":1,"reports":[]}""".toByteArray()); zip.closeEntry()
        }
        val dir = File(tmp.root, "safe")
        ReportArchive.read(ByteArrayInputStream(out.toByteArray()), dir)
        assertTrue(!File(tmp.root, "evil.txt").exists())
        assertTrue(dir.listFiles()!!.all { it.canonicalFile.parentFile == dir.canonicalFile })
    }

    @Test
    fun sanitize_replacesUnsafeCharacters() {
        assertEquals("a_b_c.png", ReportArchive.sanitize("a b/c.png"))
        assertEquals("file", ReportArchive.sanitize(""))
    }
}
