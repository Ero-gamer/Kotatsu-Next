package org.koitharu.kotatsu.reader.ui.pager

import android.view.animation.Interpolator

/** Page-turn motion shared by the readers: Android's own pager / list curve. */
object ReaderMotion {

    /** `(t - 1)^5 + 1`: most of the way at once, then a long soft landing (what Android lists use by default). */
    val quinticOut: Interpolator = Interpolator { t -> quintic(t) }

    internal fun quintic(t: Float): Float {
        val u = t - 1f
        return u * u * u * u * u + 1f
    }
}
