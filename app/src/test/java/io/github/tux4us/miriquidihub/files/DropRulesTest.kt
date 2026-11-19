package io.github.tux4us.miriquidihub.files

import io.github.tux4us.miriquidihub.files.DropRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class DropRulesTest {

    private fun file(path: String) = FileEntry(PathUtil.name(path), path, false, 1, 0)
    private fun dir(path: String) = FileEntry(PathUtil.name(path), path, true, -1, 0)

    @Test fun okAcrossSources() {
        assertEquals(Verdict.OK, DropRules.check("a", listOf(file("/x/f.txt")), "b", "/", true))
    }

    @Test fun moveIntoSameFolderDoesNothing() {
        assertEquals(Verdict.NOTHING_TO_DO, DropRules.check("a", listOf(file("/x/f.txt"), dir("/x/d")), "a", "/x", true))
    }

    @Test fun copyIntoSameFolderIsAllowed() {
        assertEquals(Verdict.OK, DropRules.check("a", listOf(file("/x/f.txt")), "a", "/x", false))
    }

    @Test fun sameFolderOnDifferentSourceIsOk() {
        assertEquals(Verdict.OK, DropRules.check("a", listOf(file("/x/f.txt")), "b", "/x", true))
    }

    @Test fun folderIntoItselfAndDescendants() {
        assertEquals(Verdict.INTO_ITSELF, DropRules.check("a", listOf(dir("/x/d")), "a", "/x/d", false))
        assertEquals(Verdict.INTO_ITSELF, DropRules.check("a", listOf(dir("/x/d")), "a", "/x/d/sub/deep", true))
    }

    @Test fun folderIntoSiblingWithSimilarNameIsOk() {
        assertEquals(Verdict.OK, DropRules.check("a", listOf(dir("/x/d")), "a", "/x/d2", true))
    }

    @Test fun fileIntoOwnParentFolderRulesApplyOnlyToFolders() {
        assertEquals(Verdict.OK, DropRules.check("a", listOf(file("/x/f.txt")), "a", "/y", true))
    }

    @Test fun emptySelectionDoesNothing() {
        assertEquals(Verdict.NOTHING_TO_DO, DropRules.check("a", emptyList(), "a", "/", false))
    }

    @Test fun rootAsTargetForRootChildren() {
        assertEquals(Verdict.NOTHING_TO_DO, DropRules.check("a", listOf(file("/f.txt")), "a", "/", true))
    }
}
