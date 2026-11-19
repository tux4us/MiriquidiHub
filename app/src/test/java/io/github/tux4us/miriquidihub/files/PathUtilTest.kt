package io.github.tux4us.miriquidihub.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathUtilTest {

    @Test
    fun normalize_collapsesSeparatorsAndDots() {
        assertEquals("/", PathUtil.normalize(""))
        assertEquals("/", PathUtil.normalize("///"))
        assertEquals("/a/b", PathUtil.normalize("a//b/./"))
        assertEquals("/a/b", PathUtil.normalize("\\a\\b"))
    }

    @Test
    fun normalize_neverEscapesRoot() {
        assertEquals("/", PathUtil.normalize("/.."))
        assertEquals("/etc", PathUtil.normalize("/../../etc"))
        assertEquals("/a", PathUtil.normalize("/a/b/.."))
    }

    @Test
    fun parentAndName() {
        assertNull(PathUtil.parent("/"))
        assertEquals("/", PathUtil.parent("/a"))
        assertEquals("/a", PathUtil.parent("/a/b"))
        assertEquals("b", PathUtil.name("/a/b"))
        assertEquals("", PathUtil.name("/"))
    }

    @Test
    fun child_joinsAndNormalizes() {
        assertEquals("/a", PathUtil.child("/", "a"))
        assertEquals("/a/b", PathUtil.child("/a/", "b"))
        assertEquals("/b", PathUtil.child("/a", "../b"))
    }

    @Test
    fun isInside_matchesWholeSegmentsOnly() {
        assertTrue(PathUtil.isInside("/a/b", "/a"))
        assertTrue(PathUtil.isInside("/a", "/a"))
        assertFalse(PathUtil.isInside("/ab", "/a"))
        assertTrue(PathUtil.isInside("/x", "/"))
    }

    @Test
    fun isValidName_rejectsSeparatorsAndSpecials() {
        assertTrue(PathUtil.isValidName("Bild 1.jpg"))
        assertFalse(PathUtil.isValidName(""))
        assertFalse(PathUtil.isValidName("   "))
        assertFalse(PathUtil.isValidName("."))
        assertFalse(PathUtil.isValidName(".."))
        assertFalse(PathUtil.isValidName("a/b"))
        assertFalse(PathUtil.isValidName("a\\b"))
        assertFalse(PathUtil.isValidName("a\u0000b"))
        assertFalse(PathUtil.isValidName("x".repeat(256)))
    }

    @Test
    fun uniqueName_numbersBeforeExtension() {
        assertEquals("a.txt", PathUtil.uniqueName("a.txt") { false })
        assertEquals("a (1).txt", PathUtil.uniqueName("a.txt") { it == "a.txt" })
        assertEquals("a (2).txt", PathUtil.uniqueName("a.txt") { it == "a.txt" || it == "a (1).txt" })
        assertEquals("Ordner (1)", PathUtil.uniqueName("Ordner") { it == "Ordner" })
        assertEquals(".bashrc (1)", PathUtil.uniqueName(".bashrc") { it == ".bashrc" })
    }
}
