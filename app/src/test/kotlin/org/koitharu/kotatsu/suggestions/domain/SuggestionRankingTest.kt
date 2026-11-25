package org.koitharu.kotatsu.suggestions.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class SuggestionRankingTest {

    private val flat: (String) -> Double = { 1.0 }

    @Test
    fun droppedNeedsLowProgressAndLongIdle() {
        assertTrue(SuggestionRanking.isDropped(0.05f, Duration.ofDays(30)))
        assertFalse(SuggestionRanking.isDropped(0.05f, Duration.ofDays(3)))
        assertFalse(SuggestionRanking.isDropped(0.5f, Duration.ofDays(90)))
        assertFalse(SuggestionRanking.isDropped(-1f, Duration.ofDays(90)))
    }

    @Test
    fun `scores past one keep their order instead of all becoming one`() {
        // Cut off at 1, these three were a tie, and most of a list came out in no order
        val scaled = SuggestionRanking.rescale(listOf(1.7, 0.85, 1.2))
        assertEquals(1f, scaled[0], 1e-6f)
        assertEquals(0.5f, scaled[1], 1e-6f)
        assertEquals(1.2f / 1.7f, scaled[2], 1e-6f)
        assertTrue(scaled[0] > scaled[2] && scaled[2] > scaled[1])
    }

    @Test
    fun `scores within one are stored as they are`() {
        assertEquals(listOf(0.2f, 1f, 0.5f), SuggestionRanking.rescale(listOf(0.2, 1.0, 0.5)))
        assertEquals(emptyList<Float>(), SuggestionRanking.rescale(emptyList()))
    }

    @Test
    fun `a score that is not a number, or below nothing, counts as nothing`() {
        assertEquals(listOf(0f, 0f, 0.4f), SuggestionRanking.rescale(listOf(Double.NaN, -0.3, 0.4)))
    }

    @Test
    fun distinctiveTagsPreferRareOverFrequentCommon() {
        val seed = listOf("Action", "Action", "Action", "Villainess", "Villainess")
        val rarity: (String) -> Double = { if (it.equals("action", true)) 1.0 else 5.0 }
        assertEquals(listOf("Villainess", "Action"), SuggestionRanking.distinctiveTags(seed, rarity, 10))
    }

    @Test
    fun singleOccurrenceTagComesAfterRecurringOnes() {
        val seed = listOf("Common", "Common", "Odd")
        val rarity: (String) -> Double = { if (it == "Odd") 100.0 else 1.0 }
        assertEquals(listOf("Common", "Odd"), SuggestionRanking.distinctiveTags(seed, rarity, 10))
    }

    @Test
    fun tagsAreNormalizedAndLimited() {
        val seed = listOf("Action", " action ", "ACTION", "", "Drama", "Drama")
        val result = SuggestionRanking.distinctiveTags(seed, flat, 1)
        assertEquals(listOf("Action"), result)
    }

    @Test
    fun weightedOverlapBasics() {
        assertEquals(0.0, SuggestionRanking.weightedOverlap(emptySet(), setOf("a"), flat), 1e-9)
        assertEquals(1.0, SuggestionRanking.weightedOverlap(setOf("a", "b"), setOf("a", "b"), flat), 1e-9)
        assertEquals(1.0 / 3.0, SuggestionRanking.weightedOverlap(setOf("a", "b"), setOf("b", "c"), flat), 1e-9)
    }

    @Test
    fun idfIsHigherForRarerTags() {
        assertTrue(SuggestionRanking.idf(1000, 1) > SuggestionRanking.idf(1000, 900))
        assertTrue(SuggestionRanking.idf(1000, 0) > 0.0)
    }
}
