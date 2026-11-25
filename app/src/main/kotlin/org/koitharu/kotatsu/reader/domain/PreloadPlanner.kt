package org.koitharu.kotatsu.reader.domain

/** How many pages the reader fetches ahead of and behind the current one. */
data class PreloadPlan(val ahead: Int, val behind: Int)

/**
 * Remembers how fast the reader is turning pages: the gaps between the last few page changes. A gap longer than
 * [maxGapMs] is a break (phone put down), not reading speed, so it is not counted. Plain Kotlin; time comes from
 * the caller (`SystemClock.elapsedRealtime()`), so it can be tested without a clock.
 */
class ReadingSpeedTracker(
    private val window: Int = WINDOW,
    private val maxGapMs: Long = MAX_GAP_MS,
) {

    private val gaps = ArrayDeque<Long>()
    private var lastKey = NO_KEY
    private var lastTime = -1L

    /** The page identified by [key] is now the one being read. The same page again is not a transition. */
    @Synchronized
    fun onPage(key: Long, nowMs: Long) {
        if (key == lastKey) return
        if (lastTime >= 0 && lastKey != NO_KEY) {
            val gap = nowMs - lastTime
            if (gap in 1..maxGapMs) {
                gaps.addLast(gap)
                while (gaps.size > window) gaps.removeFirst()
            }
        }
        lastKey = key
        lastTime = nowMs
    }

    /** Mean gap between the last page changes in ms, or null until [MIN_SAMPLES] of them are known. */
    @Synchronized
    fun averageGapMs(): Long? = if (gaps.size < MIN_SAMPLES) null else gaps.sum() / gaps.size

    @Synchronized
    fun reset() {
        gaps.clear()
        lastKey = NO_KEY
        lastTime = -1L
    }

    companion object {
        const val WINDOW = 5
        const val MAX_GAP_MS = 120_000L
        const val MIN_SAMPLES = 2
        private const val NO_KEY = Long.MIN_VALUE
    }
}

/** Decides the prefetch window from reading speed, the connection and the device. */
object PreloadPlanner {

    const val FAST_GAP_MS = 1_500L
    const val NORMAL_GAP_MS = 4_000L
    const val SLOW_GAP_MS = 10_000L

    private const val UNKNOWN_AHEAD = 6
    private const val FAST_AHEAD = 8
    private const val NORMAL_AHEAD = 6
    private const val SLOW_AHEAD = 4
    private const val IDLE_AHEAD = 3
    private const val BEHIND = 2
    private const val BEHIND_CONSTRAINED = 1
    private const val CONSTRAINED_AHEAD = 2
    private const val CONSTRAINED_FAST_AHEAD = 3
    private const val METERED_MAX_AHEAD = 3
    private const val METERED_MAX_BEHIND = 1

    /**
     * @param avgGapMs mean time per page (see [ReadingSpeedTracker]) or null when not known yet
     * @param metered the active network is metered (mobile data): fetch less, a skipped page is paid traffic
     * @param constrained a small-RAM device (memory saver): small window whatever the speed
     */
    fun plan(avgGapMs: Long?, metered: Boolean, constrained: Boolean): PreloadPlan {
        var ahead = when {
            avgGapMs == null -> UNKNOWN_AHEAD
            avgGapMs < FAST_GAP_MS -> FAST_AHEAD
            avgGapMs < NORMAL_GAP_MS -> NORMAL_AHEAD
            avgGapMs < SLOW_GAP_MS -> SLOW_AHEAD
            else -> IDLE_AHEAD
        }
        var behind = BEHIND
        if (constrained) {
            ahead = if (avgGapMs != null && avgGapMs < NORMAL_GAP_MS) CONSTRAINED_FAST_AHEAD else CONSTRAINED_AHEAD
            behind = BEHIND_CONSTRAINED
        }
        if (metered) {
            ahead = minOf(ahead, METERED_MAX_AHEAD)
            behind = minOf(behind, METERED_MAX_BEHIND)
        }
        return PreloadPlan(ahead, behind)
    }
}
