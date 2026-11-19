package io.github.tux4us.miriquidihub.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadTest {
    private val items = listOf(
        UploadItem("a.txt", 3) { java.io.ByteArrayInputStream("abc".toByteArray()) },
        UploadItem("b.txt", 2) { java.io.ByteArrayInputStream("xy".toByteArray()) }
    )

    @Test
    fun upload_writesAllItems() {
        val m = MemorySource().apply { putFile("/dir/old.txt", "o") }
        val r = TransferEngine().upload(items, m, "/dir", ConflictPolicy.RENAME)
        assertEquals(2, r.done)
        assertEquals("abc", m.text("/dir/a.txt"))
    }

    @Test
    fun upload_appliesPolicies() {
        val m = MemorySource().apply { putFile("/a.txt", "alt") }
        assertEquals(1, TransferEngine().upload(items, m, "/", ConflictPolicy.SKIP).skipped)
        assertEquals("alt", m.text("/a.txt"))
        TransferEngine().upload(items, m, "/", ConflictPolicy.RENAME)
        assertEquals("abc", m.text("/a (1).txt"))
        TransferEngine().upload(items, m, "/", ConflictPolicy.OVERWRITE)
        assertEquals("abc", m.text("/a.txt"))
    }

    @Test
    fun upload_rejectsPathTraversalNames() {
        val m = MemorySource()
        val bad = listOf(UploadItem("../evil.txt", 1) { java.io.ByteArrayInputStream("x".toByteArray()) })
        val r = TransferEngine().upload(bad, m, "/dir", ConflictPolicy.RENAME)
        assertEquals(1, r.failures.size)
        assertNull(m.text("/evil.txt"))
    }
}
