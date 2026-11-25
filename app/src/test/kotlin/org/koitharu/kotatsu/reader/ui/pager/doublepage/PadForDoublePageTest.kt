package org.koitharu.kotatsu.reader.ui.pager.doublepage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

class PadForDoublePageTest {

    private fun pages(chapterId: Long, count: Int) = List(count) {
        ReaderPage(chapterId * 1000 + it, "u$it", null, chapterId, it, TestMangaSource)
    }

    private fun List<ReaderPage>.spreads() = chunked(2)

    @Test
    fun emptyStaysEmpty() {
        assertTrue(emptyList<ReaderPage>().padForDoublePage(true).isEmpty())
    }

    @Test
    fun oddChapterIsPaddedSoChaptersNeverShareASpread() {
        val result = (pages(1, 3) + pages(2, 2)).padForDoublePage(false)
        assertEquals(6, result.size)
        result.spreads().forEach { spread ->
            assertEquals(1, spread.map { it.chapterId }.distinct().size)
        }
    }

    @Test
    fun evenChaptersGetNoPadding() {
        val result = (pages(1, 2) + pages(2, 4)).padForDoublePage(false)
        assertEquals(6, result.size)
        assertTrue(result.none { it.index == -1 })
    }

    @Test
    fun coverPageStartsEveryChapterSolo() {
        val result = (pages(1, 3) + pages(2, 2)).padForDoublePage(true)
        result.spreads().forEach { spread ->
            assertEquals(1, spread.map { it.chapterId }.distinct().size)
        }
        val firstOfChapter2 = result.indexOfFirst { it.chapterId == 2L && it.index == 0 }
        assertEquals(1, firstOfChapter2 % 2)
        assertEquals(-1, result[firstOfChapter2 - 1].index)
        assertEquals(-1, result[0].index)
        assertEquals(0, result[1].index)
    }

    @Test
    fun realPagesKeepTheirOrder() {
        val input = pages(1, 3) + pages(2, 3)
        val result = input.padForDoublePage(true).filter { it.index != -1 }
        assertEquals(input, result)
    }
}
