package org.koitharu.kotatsu.core.model

/** Horizontal position a page opens at when it is wider than the screen. */
enum class ZoomStart {
    /** Follows the reading direction: right edge for right-to-left readers, left edge otherwise. */
    AUTO,
    LEFT,
    RIGHT,
    CENTER,
}
