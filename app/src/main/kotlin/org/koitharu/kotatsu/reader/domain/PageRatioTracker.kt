package org.koitharu.kotatsu.reader.domain

/**
 * Median height/width ratio of the last [capacity] pages that were shown. A webtoon page that has not arrived yet
 * is given the height such a page usually has, so the strip does not jump when the real image replaces a thin
 * placeholder. The median (not the mean) so one odd page - a cover, a double spread - does not skew it.
 */
class PageRatioTracker(private val capacity: Int = CAPACITY) {

    private val ratios = ArrayDeque<Float>()

    @Synchronized
    fun record(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        ratios.addLast(height.toFloat() / width)
        while (ratios.size > capacity) ratios.removeFirst()
    }

    /** Median ratio, or null while nothing has been recorded. */
    @Synchronized
    fun median(): Float? {
        if (ratios.isEmpty()) return null
        val sorted = ratios.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /** Height in px of a placeholder [widthPx] wide, or 0 when no ratio is known (no placeholder). */
    fun placeholderHeight(widthPx: Int): Int {
        val ratio = median() ?: return 0
        if (widthPx <= 0) return 0
        return (widthPx * ratio.coerceIn(MIN_RATIO, MAX_RATIO)).toInt()
    }

    @Synchronized
    fun clear() = ratios.clear()

    companion object {
        const val CAPACITY = 16
        const val MIN_RATIO = 0.3f
        const val MAX_RATIO = 6f
    }
}
