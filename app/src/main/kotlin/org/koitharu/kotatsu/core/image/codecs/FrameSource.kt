package org.koitharu.kotatsu.core.image.codecs

import android.graphics.Bitmap
import java.io.Closeable

/** A decoder of animation frames for [NativeAnimatedDrawable]. Calls are serialised by the drawable's worker. */
interface FrameSource : Closeable {
    val width: Int
    val height: Int
    val frameCount: Int
    val hasAlpha: Boolean

    /** Total plays: 0 = forever. */
    val loopCount: Int

    val isAnimated: Boolean
        get() = frameCount > 1

    fun frameDurationMs(frame: Int): Int

    /** Renders the whole canvas of [frame] at 1/[sample] into [bitmap] (exactly the sampled size). */
    fun decodeInto(frame: Int, sample: Int, bitmap: Bitmap): Boolean
}
