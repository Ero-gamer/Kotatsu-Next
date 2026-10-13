package org.koitharu.kotatsu.reader.ui.pager

/**
 * App-wide "only one animation plays at a time" gate (main thread only).
 * A new claimant immediately stops the previous one; owners release when they leave the screen.
 */
object AnimationGate {

    private var owner: Any? = null
    private var stop: (() -> Unit)? = null

    fun claim(newOwner: Any, onStop: () -> Unit) {
        if (owner !== newOwner) {
            val previousStop = stop
            owner = newOwner
            stop = onStop
            previousStop?.invoke()
        } else {
            stop = onStop
        }
    }

    fun release(oldOwner: Any) {
        if (owner === oldOwner) {
            owner = null
            stop = null
        }
    }

    fun isOwner(candidate: Any): Boolean = owner === candidate
}
