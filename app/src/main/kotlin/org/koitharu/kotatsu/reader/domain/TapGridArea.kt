package org.koitharu.kotatsu.reader.domain

enum class TapGridArea {

    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    CENTER_LEFT,
    CENTER,
    CENTER_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
    ;

    /** The area seen after mirroring the grid left-right ([horizontal]) and / or top-bottom ([vertical]). */
    fun mirrored(horizontal: Boolean, vertical: Boolean): TapGridArea {
        if (!horizontal && !vertical) return this
        val o = ordinal
        var col = o % 3
        var row = o / 3
        if (horizontal) col = 2 - col
        if (vertical) row = 2 - row
        return entries[row * 3 + col]
    }
}
