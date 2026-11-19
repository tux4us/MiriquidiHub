package io.github.tux4us.miriquidihub.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class LocalSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun src() = LocalSource("l", "Lokal", File(tmp.root, "root").apply { mkdirs() })

    @Test
    fun toFile_cannotEscapeRoot() {
        val s = src()
        assertEquals(File(s.rootDir, "etc/passwd").path, s.toFile("/../../etc/passwd").path)
        assertEquals(s.rootDir.path, s.toFile("/..").path)
    }

    @Test
    fun writeListStatRoundtrip() {
        val s = src()
        s.mkdir("/d")
        s.write("/d/a.txt", ByteArrayInputStream("hi".toByteArray()), 2, overwrite = false)
        val list = s.list("/d")
        assertEquals(1, list.size)
        assertEquals("/d/a.txt", list[0].path)
        assertEquals(2L, list[0].size)
        assertTrue(s.stat("/d")!!.isDirectory)
        assertNull(s.stat("/nix"))
        assertFalse(File(s.toFile("/d"), "a.txt" + LocalSource.PART_SUFFIX).exists())
    }

    @Test
    fun write_withoutOverwrite_failsOnExisting() {
        val s = src()
        s.write("/a.txt", ByteArrayInputStream("1".toByteArray()), 1, false)
        try {
            s.write("/a.txt", ByteArrayInputStream("2".toByteArray()), 1, false)
            fail()
        } catch (e: AlreadyExistsException) {
            assertEquals("1", s.toFile("/a.txt").readText())
        }
        s.write("/a.txt", ByteArrayInputStream("2".toByteArray()), 1, true)
        assertEquals("2", s.toFile("/a.txt").readText())
    }

    @Test
    fun write_failure_removesPartFile() {
        val s = src()
        val broken = object : java.io.InputStream() {
            override fun read(): Int = throw IOException("kaputt")
        }
        try { s.write("/a.txt", broken, -1, false); fail() } catch (e: IOException) {}
        assertTrue(s.list("/").isEmpty())
    }

    @Test
    fun rename_validatesAndDetectsConflicts() {
        val s = src()
        s.write("/a.txt", ByteArrayInputStream(ByteArray(0)), 0, false)
        s.write("/b.txt", ByteArrayInputStream(ByteArray(0)), 0, false)
        assertEquals("/c.txt", s.rename("/a.txt", "c.txt"))
        try { s.rename("/b.txt", "c.txt"); fail() } catch (e: AlreadyExistsException) {}
        try { s.rename("/b.txt", "../x"); fail() } catch (e: IOException) {}
        try { s.rename("/", "x"); fail() } catch (e: IOException) {}
    }

    @Test
    fun delete_isRecursiveAndRefusesRoot() {
        val s = src()
        s.mkdir("/d"); s.mkdir("/d/e")
        s.write("/d/e/f.txt", ByteArrayInputStream("x".toByteArray()), 1, false)
        s.delete("/d")
        assertFalse(s.toFile("/d").exists())
        s.delete("/d") // nicht vorhanden: kein Fehler
        try { s.delete("/"); fail() } catch (e: IOException) {}
        assertTrue(s.rootDir.exists())
    }

    @Test
    fun delete_doesNotFollowSymlinks() {
        val s = src()
        val outside = File(tmp.root, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("x")
        val link = File(s.rootDir, "link")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            return // Dateisystem ohne Symlinks
        }
        assertTrue(LocalSource.isSymlink(link))
        s.delete("/link")
        assertFalse(link.exists())
        assertTrue("Ziel des Links muss unberührt bleiben", File(outside, "keep.txt").exists())
    }

    @Test
    fun list_ofMissingFolder_throwsNotFound() {
        try { src().list("/nix"); fail() } catch (e: RemoteException) { assertTrue(e.isNotFound) }
    }

    @Test
    fun toPath_roundtrip() {
        val s = src()
        s.mkdir("/a")
        assertEquals("/a", s.toPath(s.toFile("/a")))
        assertEquals("/", s.toPath(s.rootDir))
        assertNotNull(s.stat("/"))
    }
}
