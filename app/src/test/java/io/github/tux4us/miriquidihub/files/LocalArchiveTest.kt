package io.github.tux4us.miriquidihub.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String, text: String) = File(tmp.root, path).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun zipThenUnzip_roundtrip() {
        file("src/a.txt", "A"); file("src/sub/b.txt", "BB"); file("single.txt", "S")
        val out = File(tmp.root, "out.zip")
        val n = LocalArchive.zip(listOf(File(tmp.root, "src"), File(tmp.root, "single.txt")), out, { false })
        assertEquals(3, n)
        val dest = File(tmp.root, "dest")
        assertEquals(3, LocalArchive.unzip(out, dest, { false }))
        assertEquals("A", File(dest, "src/a.txt").readText())
        assertEquals("BB", File(dest, "src/sub/b.txt").readText())
        assertEquals("S", File(dest, "single.txt").readText())
    }

    @Test
    fun zip_refusesToOverwriteAndDoesNotPackItself() {
        file("a/x.txt", "x")
        val out = File(tmp.root, "a/self.zip")
        assertEquals(1, LocalArchive.zip(listOf(File(tmp.root, "a")), out, { false }))
        try { LocalArchive.zip(listOf(File(tmp.root, "a")), out, { false }); fail() } catch (e: AlreadyExistsException) {}
        val names = java.util.zip.ZipFile(out).use { z -> z.entries().toList().map { it.name } }
        assertFalse(names.any { it.endsWith("self.zip") })
        assertFalse(File(tmp.root, "a").list()!!.any { it.endsWith(LocalSource.PART_SUFFIX) })
    }

    @Test
    fun unzip_blocksZipSlip() {
        val evil = File(tmp.root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../escaped.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val dest = File(tmp.root, "dest")
        try { LocalArchive.unzip(evil, dest, { false }); fail() } catch (e: IOException) {}
        assertFalse(File(tmp.root, "escaped.txt").exists())
    }

    @Test
    fun unzip_doesNotOverwriteExisting() {
        file("src/a.txt", "neu")
        val z = File(tmp.root, "z.zip")
        LocalArchive.zip(listOf(File(tmp.root, "src")), z, { false })
        val dest = file("dest/src/a.txt", "alt").parentFile!!.parentFile!!
        try { LocalArchive.unzip(z, dest, { false }); fail() } catch (e: AlreadyExistsException) {}
        assertEquals("alt", File(dest, "src/a.txt").readText())
    }

    @Test
    fun zip_cancel_removesPartialOutput() {
        file("a.txt", "x".repeat(1000))
        val out = File(tmp.root, "c.zip")
        try { LocalArchive.zip(listOf(File(tmp.root, "a.txt")), out, { true }); fail() } catch (e: CancelledException) {}
        assertFalse(out.exists())
        assertTrue(tmp.root.list()!!.none { it.endsWith(LocalSource.PART_SUFFIX) })
    }

    @Test
    fun folderNameFor() {
        assertEquals("bilder", LocalArchive.folderNameFor("bilder.zip"))
        assertEquals("BILDER", LocalArchive.folderNameFor("BILDER.ZIP"))
        assertEquals("x.tar_entpackt", LocalArchive.folderNameFor("x.tar"))
    }
}
