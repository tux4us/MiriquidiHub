package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrashManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000_000_000L

    private fun manager() = TrashManager(File(tmp.root, TrashManager.FOLDER_NAME)) { clock }

    private fun file(name: String, content: String = "data"): File =
        File(tmp.root, name).apply { parentFile?.mkdirs(); writeText(content) }

    @Test
    fun moveToTrash_removesOriginalAndListsEntry() {
        val f = file("a/photo.jpg", "12345")
        val m = manager()
        val entry = m.moveToTrash(f)
        assertNotNull(entry)
        assertFalse(f.exists())
        assertEquals(1, m.list().size)
        assertEquals(5L, m.totalBytes())
        assertTrue(File(tmp.root, "${TrashManager.FOLDER_NAME}/.nomedia").exists())
    }

    @Test
    fun moveToTrash_supportsDirectoriesAndCountsSize() {
        file("dir/one.txt", "abc")
        file("dir/sub/two.txt", "defgh")
        val m = manager()
        val entry = m.moveToTrash(File(tmp.root, "dir"))!!
        assertTrue(entry.isDirectory)
        assertEquals(8L, entry.sizeBytes)
    }

    @Test
    fun moveToTrash_refusesMissingFileAndTrashItself() {
        val m = manager()
        assertNull(m.moveToTrash(File(tmp.root, "missing.txt")))
        file("x.txt")
        m.moveToTrash(File(tmp.root, "x.txt"))
        assertNull(m.moveToTrash(File(tmp.root, TrashManager.FOLDER_NAME)))
    }

    @Test
    fun restore_putsFileBackAndAvoidsOverwriting() {
        val f = file("docs/report.pdf", "v1")
        val m = manager()
        val id = m.moveToTrash(f)!!.id
        file("docs/report.pdf", "v2") // neue Datei am alten Ort
        val restored = m.restore(id)!!
        assertEquals("report (1).pdf", restored.name)
        assertEquals("v1", restored.readText())
        assertEquals("v2", File(tmp.root, "docs/report.pdf").readText())
        assertTrue(m.list().isEmpty())
    }

    @Test
    fun purgeExpired_removesOnlyOldEntries() {
        val m = manager()
        m.moveToTrash(file("old.txt"))
        clock += 31L * 24 * 60 * 60 * 1000
        m.moveToTrash(file("new.txt"))
        assertEquals(1, m.purgeExpired())
        assertEquals(listOf("new.txt"), m.list().map { it.name })
    }

    @Test
    fun empty_removesEverything() {
        val m = manager()
        m.moveToTrash(file("1.txt"))
        m.moveToTrash(file("2.txt"))
        assertEquals(2, m.empty())
        assertTrue(m.list().isEmpty())
    }

    @Test
    fun restoredName_insertsCounterBeforeExtension() {
        assertEquals("a (2).txt", TrashManager.restoredName("a.txt", 2))
        assertEquals("folder (1)", TrashManager.restoredName("folder", 1))
        assertEquals(".hidden (1)", TrashManager.restoredName(".hidden", 1))
    }
}
