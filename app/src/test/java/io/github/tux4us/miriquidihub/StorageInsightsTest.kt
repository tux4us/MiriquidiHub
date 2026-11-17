package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageInsightsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String, size: Int, fill: Char = 'a', modified: Long? = null): File =
        File(tmp.root, path).apply {
            parentFile?.mkdirs()
            writeText(fill.toString().repeat(size))
            if (modified != null) setLastModified(modified)
        }

    @Test
    fun categoryFor_mapsExtensions() {
        assertEquals(StorageCategory.IMAGES, StorageInsights.categoryFor("IMG_1.JPG"))
        assertEquals(StorageCategory.VIDEOS, StorageInsights.categoryFor("clip.mkv"))
        assertEquals(StorageCategory.AUDIO, StorageInsights.categoryFor("song.flac"))
        assertEquals(StorageCategory.DOCUMENTS, StorageInsights.categoryFor("a.pdf"))
        assertEquals(StorageCategory.APPS, StorageInsights.categoryFor("app.apk"))
        assertEquals(StorageCategory.OTHER, StorageInsights.categoryFor("noextension"))
    }

    @Test
    fun scan_returnsLargestFirstWithLimit() {
        file("a.bin", 10); file("b.bin", 300); file("sub/c.bin", 200)
        val r = StorageInsights.scan(tmp.root, limit = 2)
        assertEquals(listOf("b.bin", "c.bin"), r.map { it.file.name })
    }

    @Test
    fun scan_appliesCategoryAndSizeFilter() {
        file("a.jpg", 50); file("b.jpg", 500); file("c.mp4", 900)
        val r = StorageInsights.scan(tmp.root, InsightFilter(setOf(StorageCategory.IMAGES), minBytes = 100))
        assertEquals(listOf("b.jpg"), r.map { it.file.name })
    }

    @Test
    fun scan_appliesAgeFilter() {
        val now = 2_000_000_000_000L
        val day = 24L * 60 * 60 * 1000
        file("old.bin", 10, modified = now - 400 * day)
        file("new.bin", 10, modified = now - 5 * day)
        val r = StorageInsights.scan(tmp.root, InsightFilter(olderThanDays = 365), now = now)
        assertEquals(listOf("old.bin"), r.map { it.file.name })
    }

    @Test
    fun scan_skipsTrashFolder() {
        file("${TrashManager.FOLDER_NAME}/items/x", 999)
        file("keep.bin", 5)
        assertEquals(listOf("keep.bin"), StorageInsights.scan(tmp.root).map { it.file.name })
    }

    @Test
    fun findDuplicates_groupsOnlyIdenticalContent() {
        file("a/one.bin", 2000, 'x')
        file("b/two.bin", 2000, 'x')
        file("c/three.bin", 2000, 'y') // gleiche Größe, anderer Inhalt
        file("d/small.bin", 10, 'x') // unter Mindestgröße
        file("e/small.bin", 10, 'x')
        val groups = StorageInsights.findDuplicates(tmp.root, minBytes = 1000)
        assertEquals(1, groups.size)
        assertEquals(setOf("one.bin", "two.bin"), groups[0].files.map { it.name }.toSet())
        assertEquals(2000L, groups[0].wastedBytes)
    }

    @Test
    fun findDuplicates_detectsDifferenceAfterPartialHashRange() {
        val size = 200 * 1024
        val a = file("a.bin", size, 'x')
        val b = file("b.bin", size, 'x')
        b.writeBytes(b.readBytes().also { it[size - 1] = 'z'.code.toByte() })
        assertTrue(StorageInsights.findDuplicates(tmp.root, minBytes = 1024).isEmpty())
        assertTrue(a.exists())
    }

    @Test
    fun hash_matchesForEqualContent() {
        val a = file("a.txt", 100, 'q')
        val b = file("b.txt", 100, 'q')
        assertEquals(StorageInsights.hash(a, Long.MAX_VALUE), StorageInsights.hash(b, Long.MAX_VALUE))
        assertEquals(64, StorageInsights.hash(a, 10)!!.length)
    }
}
