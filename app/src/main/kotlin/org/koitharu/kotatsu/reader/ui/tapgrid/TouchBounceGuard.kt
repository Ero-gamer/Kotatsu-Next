package org.koitharu.kotatsu.reader.ui.tapgrid

/**
 * Cheap digitizers report a second "tap" a few milliseconds after a drag is released. A touch that lands within
 * [windowMs] of the end of a drag is treated as that bounce and must not turn pages / toggle the UI.
 * Plain Kotlin (times are `MotionEvent.getEventTime()` values), so it is unit-testable on the JVM.
 */
internal class TouchBounceGuard(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var lastDragEnd = NONE

    fun onDragEnd(eventTime: Long) {
        lastDragEnd = eventTime
    }

    /** True when a touch that starts at [downTime] is a bounce of the previous drag. */
    fun isBounce(downTime: Long): Boolean {
        val end = lastDragEnd
        return end != NONE && downTime - end in 0 until windowMs
    }

    fun reset() {
        lastDragEnd = NONE
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 150L
        private const val NONE = Long.MIN_VALUE
    }
}
