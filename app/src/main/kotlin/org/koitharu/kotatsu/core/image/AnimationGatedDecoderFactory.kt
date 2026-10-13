package org.koitharu.kotatsu.core.image

import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import org.koitharu.kotatsu.core.util.ext.allowAnimationKey

/**
 * Lets [delegate] (an animated decoder) run only for requests flagged with [allowAnimationKey].
 * Thumbnails, covers and previews therefore fall through to the plain bitmap decoder and show the
 * first frame, and never pay for an animation.
 */
class AnimationGatedDecoderFactory(private val delegate: Decoder.Factory) : Decoder.Factory {

    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? = if (options.extras[allowAnimationKey]) delegate.create(result, options, imageLoader) else null
}
