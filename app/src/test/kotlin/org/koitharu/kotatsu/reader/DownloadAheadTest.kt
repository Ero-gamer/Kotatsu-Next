package org.koitharu.kotatsu.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.reader.domain.selectDownloadAhead

class DownloadAheadTest {

    private val chapters = listOf(1L, 2L, 3L, 4L, 5L)

    @Test
    fun nextUnsavedChaptersAfterASavedOne() {
        assertEquals(listOf(3L, 4L), selectDownloadAhead(chapters, 2, setOf(1, 2), 2))
    }

    @Test
    fun savedChaptersInBetweenAreSkippedAndCountStaysAWindow() {
        assertEquals(listOf(4L), selectDownloadAhead(chapters, 2, setOf(2, 3), 2))
    }

    @Test
    fun nothingWhileReadingAChapterThatIsNotSaved() {
        assertEquals(emptyList<Long>(), selectDownloadAhead(chapters, 2, setOf(1), 3))
    }

    @Test
    fun nothingPastTheLastChapterOrWhenOff() {
        assertEquals(emptyList<Long>(), selectDownloadAhead(chapters, 5, setOf(5), 3))
        assertEquals(emptyList<Long>(), selectDownloadAhead(chapters, 2, setOf(2), 0))
        assertEquals(emptyList<Long>(), selectDownloadAhead(chapters, 9, setOf(9), 2))
    }
}
