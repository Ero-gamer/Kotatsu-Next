package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter

class ChapterNavigationTest {

    private fun ch(id: Long, number: Float, volume: Int = 0, branch: String? = null) = MangaChapter(
        id = id,
        title = null,
        number = number,
        volume = volume,
        url = "u$id",
        scanlator = null,
        uploadDate = 0L,
        branch = branch,
        source = TestMangaSource,
    )

    @Test
    fun neighbourWithoutSkipIsPlainStep() {
        val list = listOf(ch(1, 1f), ch(2, 1f), ch(3, 2f))
        assertEquals(1, list.neighbourIndex(0, 1, false))
        assertEquals(-1, list.neighbourIndex(0, -1, false))
    }

    @Test
    fun neighbourSkipsCopiesOfSameNumber() {
        val list = listOf(ch(1, 1f), ch(2, 1f), ch(3, 1f), ch(4, 2f))
        assertEquals(3, list.neighbourIndex(0, 1, true))
        assertEquals(-1, list.neighbourIndex(2, -1, true))
    }

    @Test
    fun neighbourNeverSkipsUnnumberedChapters() {
        val list = listOf(ch(1, 0f), ch(2, 0f), ch(3, 0f))
        assertEquals(1, list.neighbourIndex(0, 1, true))
    }

    @Test
    fun neighbourSkipPastEndIsOutOfRange() {
        val list = listOf(ch(1, 1f), ch(2, 1f))
        assertEquals(2, list.neighbourIndex(0, 1, true))
    }

    @Test
    fun missingCountsWholeGap() {
        assertEquals(2, missingChaptersBetween(ch(1, 3f), ch(2, 6f)))
        assertEquals(2, missingChaptersBetween(ch(2, 6f), ch(1, 3f)))
    }

    @Test
    fun missingZeroWhenAdjacentOrDecimal() {
        assertEquals(0, missingChaptersBetween(ch(1, 3f), ch(2, 4f)))
        assertEquals(0, missingChaptersBetween(ch(1, 3f), ch(2, 3.5f)))
    }

    @Test
    fun missingZeroWhenUnknownOrDifferentBranchOrVolume() {
        assertEquals(0, missingChaptersBetween(ch(1, 0f), ch(2, 5f)))
        assertEquals(0, missingChaptersBetween(ch(1, 1f, branch = "a"), ch(2, 5f, branch = "b")))
        assertEquals(0, missingChaptersBetween(ch(1, 1f, volume = 1), ch(2, 5f, volume = 2)))
    }

    @Test
    fun formatDropsTrailingZero() {
        assertEquals("12", formatChapterNumber(12f))
        assertEquals("12.5", formatChapterNumber(12.5f))
    }
}
