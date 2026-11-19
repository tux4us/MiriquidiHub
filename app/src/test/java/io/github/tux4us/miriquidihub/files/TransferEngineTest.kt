package io.github.tux4us.miriquidihub.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransferEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun local(id: String): LocalSource {
        val root = File(tmp.root, id).apply { mkdirs() }
        return LocalSource(id, id, root)
    }

    private fun put(src: LocalSource, path: String, text: String) {
        val f = src.toFile(path)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    private fun entry(src: FileSource, path: String) = src.stat(path)!!

    private fun engine(cancel: () -> Boolean = { false }) = TransferEngine(isCancelled = cancel)

    // ---- Lokal → Lokal (verschiedene Quellen) ----

    @Test
    fun copyFile_toOtherSource() {
        val a = local("a"); val b = local("b")
        put(a, "/x.txt", "hallo")
        val r = engine().transfer(a, listOf(entry(a, "/x.txt")), b, "/", move = false, policy = ConflictPolicy.RENAME)
        assertEquals(1, r.done)
        assertFalse(r.hasProblems)
        assertEquals("hallo", b.toFile("/x.txt").readText())
        assertTrue(a.toFile("/x.txt").exists())
    }

    @Test
    fun copyDirectory_isRecursive() {
        val a = local("a"); val b = local("b")
        put(a, "/d/1.txt", "1"); put(a, "/d/sub/2.txt", "22")
        val r = engine().transfer(a, listOf(entry(a, "/d")), b, "/", false, ConflictPolicy.RENAME)
        assertFalse(r.hasProblems)
        assertEquals("1", b.toFile("/d/1.txt").readText())
        assertEquals("22", b.toFile("/d/sub/2.txt").readText())
    }

    @Test
    fun moveDirectory_removesSourceWhenClean() {
        val a = local("a"); val b = local("b")
        put(a, "/d/1.txt", "1"); put(a, "/d/sub/2.txt", "22")
        val r = engine().transfer(a, listOf(entry(a, "/d")), b, "/", true, ConflictPolicy.RENAME)
        assertFalse(r.hasProblems)
        assertFalse(a.toFile("/d").exists())
        assertEquals("22", b.toFile("/d/sub/2.txt").readText())
    }

    @Test
    fun move_keepsSourceWhenChildSkipped() {
        val a = local("a"); val b = local("b")
        put(a, "/d/keep.txt", "neu"); put(a, "/d/other.txt", "o")
        put(b, "/d/keep.txt", "alt")
        val r = engine().transfer(a, listOf(entry(a, "/d")), b, "/", true, ConflictPolicy.SKIP)
        // Ordner existiert am Ziel → bei SKIP wird er komplett übersprungen.
        assertEquals(1, r.skipped)
        assertTrue(a.toFile("/d/keep.txt").exists())
        assertEquals("alt", b.toFile("/d/keep.txt").readText())
    }

    @Test
    fun overwrite_mergesFoldersAndReplacesFiles() {
        val a = local("a"); val b = local("b")
        put(a, "/d/keep.txt", "neu"); put(a, "/d/other.txt", "o")
        put(b, "/d/keep.txt", "alt"); put(b, "/d/extra.txt", "e")
        val r = engine().transfer(a, listOf(entry(a, "/d")), b, "/", false, ConflictPolicy.OVERWRITE)
        assertFalse(r.hasProblems)
        assertEquals("neu", b.toFile("/d/keep.txt").readText())
        assertEquals("o", b.toFile("/d/other.txt").readText())
        assertEquals("e", b.toFile("/d/extra.txt").readText())
    }

    @Test
    fun rename_keepsBothAndNumbers() {
        val a = local("a"); val b = local("b")
        put(a, "/x.txt", "neu"); put(b, "/x.txt", "alt"); put(b, "/x (1).txt", "alt1")
        engine().transfer(a, listOf(entry(a, "/x.txt")), b, "/", false, ConflictPolicy.RENAME)
        assertEquals("alt", b.toFile("/x.txt").readText())
        assertEquals("alt1", b.toFile("/x (1).txt").readText())
        assertEquals("neu", b.toFile("/x (2).txt").readText())
    }

    @Test
    fun skip_leavesTargetUntouched() {
        val a = local("a"); val b = local("b")
        put(a, "/x.txt", "neu"); put(b, "/x.txt", "alt")
        val r = engine().transfer(a, listOf(entry(a, "/x.txt")), b, "/", false, ConflictPolicy.SKIP)
        assertEquals(1, r.skipped)
        assertEquals(0, r.done)
        assertEquals("alt", b.toFile("/x.txt").readText())
    }

    // ---- Gleiche Quelle ----

    @Test
    fun copyIntoSameFolder_neverOverwritesOriginal() {
        val a = local("a")
        put(a, "/x.txt", "orig")
        val r = engine().transfer(a, listOf(entry(a, "/x.txt")), a, "/", false, ConflictPolicy.OVERWRITE)
        assertFalse(r.hasProblems)
        assertEquals("orig", a.toFile("/x.txt").readText())
        assertEquals("orig", a.toFile("/x (1).txt").readText())
    }

    @Test
    fun moveIntoSameFolder_isSkipped() {
        val a = local("a")
        put(a, "/x.txt", "orig")
        val r = engine().transfer(a, listOf(entry(a, "/x.txt")), a, "/", true, ConflictPolicy.OVERWRITE)
        assertEquals(1, r.skipped)
        assertEquals("orig", a.toFile("/x.txt").readText())
    }

    @Test
    fun moveWithinSource_usesRename() {
        val a = local("a")
        put(a, "/d/x.txt", "1"); a.toFile("/t").mkdirs()
        val r = engine().transfer(a, listOf(entry(a, "/d")), a, "/t", true, ConflictPolicy.RENAME)
        assertFalse(r.hasProblems)
        assertEquals("1", a.toFile("/t/d/x.txt").readText())
        assertFalse(a.toFile("/d").exists())
    }

    @Test
    fun copyFolderIntoItself_isRejected() {
        val a = local("a")
        put(a, "/d/x.txt", "1")
        val r = engine().transfer(a, listOf(entry(a, "/d")), a, "/d", false, ConflictPolicy.RENAME)
        assertEquals(1, r.failures.size)
        assertFalse(a.toFile("/d/d").exists())
    }

    @Test
    fun copyFolderIntoOwnSubfolder_isRejected() {
        val a = local("a")
        put(a, "/d/sub/x.txt", "1")
        val r = engine().transfer(a, listOf(entry(a, "/d")), a, "/d/sub", true, ConflictPolicy.RENAME)
        assertEquals(1, r.failures.size)
        assertTrue(a.toFile("/d/sub/x.txt").exists())
    }

    // ---- Zwischen Quellen ohne Schnellpfad ----

    @Test
    fun localToRemote_andBack() {
        val a = local("a"); val m = MemorySource()
        put(a, "/d/1.txt", "eins"); put(a, "/d/s/2.txt", "zwei")
        val up = engine().transfer(a, listOf(entry(a, "/d")), m, "/", false, ConflictPolicy.RENAME)
        assertFalse(up.hasProblems)
        assertEquals("zwei", m.text("/d/s/2.txt"))
        val down = engine().transfer(m, listOf(entry(m, "/d")), a, "/", true, ConflictPolicy.RENAME)
        assertFalse(down.hasProblems)
        assertTrue(a.toFile("/d (1)/1.txt").exists())
        assertNull(m.stat("/d"))
    }

    @Test
    fun failureOnOneFile_doesNotStopOthers_andKeepsSourceOnMove() {
        val a = local("a"); val m = MemorySource().apply { failOnWriteOf = "bad.txt" }
        put(a, "/d/ok.txt", "ok"); put(a, "/d/bad.txt", "bad")
        val r = engine().transfer(a, listOf(entry(a, "/d")), m, "/", true, ConflictPolicy.RENAME)
        assertEquals(1, r.failures.size)
        assertEquals("ok", m.text("/d/ok.txt"))
        assertTrue("Quelle muss bei Fehlern erhalten bleiben", a.toFile("/d/bad.txt").exists())
        assertTrue(a.toFile("/d").exists())
    }

    // ---- Abbruch, Fortschritt, Löschen ----

    @Test
    fun cancel_stopsAndReports() {
        val a = local("a"); val b = local("b")
        for (i in 1..5) put(a, "/f$i.txt", "x".repeat(10))
        var started = 0
        val eng = TransferEngine(isCancelled = { started >= 2 }, onProgress = { if (it.currentName.isNotEmpty()) started++ })
        val r = eng.transfer(a, a.list("/"), b, "/", false, ConflictPolicy.RENAME)
        assertTrue(r.cancelled)
        assertTrue(b.list("/").size < 5)
        assertTrue("Keine Teildateien zurücklassen", b.list("/").none { it.name.endsWith(LocalSource.PART_SUFFIX) })
    }

    @Test
    fun progress_reportsBytes() {
        val a = local("a"); val b = local("b")
        put(a, "/big.bin", "y".repeat(100_000))
        var last: TransferProgress? = null
        val eng = TransferEngine(onProgress = { last = it }, now = { System.nanoTime() / 1000 })
        eng.transfer(a, listOf(entry(a, "/big.bin")), b, "/", false, ConflictPolicy.RENAME)
        assertEquals(100_000L, last!!.bytesDone)
        assertEquals(1, last!!.itemsDone)
    }

    @Test
    fun delete_removesTreesAndCounts() {
        val a = local("a")
        put(a, "/d/1.txt", "1"); put(a, "/d/s/2.txt", "2"); put(a, "/z.txt", "z")
        val r = engine().delete(a, listOf(entry(a, "/d"), entry(a, "/z.txt")))
        assertEquals(2, r.done)
        assertTrue(a.list("/").isEmpty())
    }

    // ---- FileTools ----

    @Test
    fun search_findsNestedNamesCaseInsensitive() {
        val a = local("a")
        put(a, "/Urlaub/Strand.JPG", "1"); put(a, "/Urlaub/x/strandkorb.txt", "2"); put(a, "/Anderes/b.txt", "3")
        val hits = ArrayList<String>()
        val n = FileTools.search(a, "/", "strand", { false }) { hits.add(it.path) }
        assertEquals(2, n)
        assertTrue(hits.contains("/Urlaub/Strand.JPG"))
    }

    @Test
    fun search_respectsLimit() {
        val a = local("a")
        for (i in 1..20) put(a, "/f$i.txt", "x")
        assertEquals(5, FileTools.search(a, "/", "f", { false }, limit = 5) {})
    }

    @Test
    fun measure_sumsFilesAndBytes() {
        val a = local("a")
        put(a, "/d/1.txt", "12"); put(a, "/d/s/2.txt", "345")
        val (files, bytes) = FileTools.measure(a, entry(a, "/d")) { false }
        assertEquals(2, files)
        assertEquals(5L, bytes)
    }
}
