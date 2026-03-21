package org.koitharu.kotatsu.core.util.ext

import android.graphics.drawable.Drawable
import androidx.annotation.CheckResult
import coil3.Extras
import coil3.ImageLoader
import coil3.asDrawable
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.bitmapConfig
import coil3.toBitmap
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.core.image.RegionBitmapDecoder
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource

fun ImageRequest.Builder.enqueueWith(loader: ImageLoader) = loader.enqueue(build())

fun ImageResult.getDrawableOrThrow(): Drawable = when (this) {
    is SuccessResult -> image.asDrawable(request.context.resources)
    is ErrorResult -> throw throwable
}

val ImageResult.drawable: Drawable?
    get() = image?.asDrawable(request.context.resources)

fun ImageResult.toBitmapOrNull(): android.graphics.Bitmap? = when (this) {
    is SuccessResult -> try {
        // Coil 3 toBitmap uses image dimensions and config from the result
        image.toBitmap() 
    } catch (_: Throwable) {
        null
    }
    is ErrorResult -> null
}

fun ImageRequest.Builder.decodeRegion(
    scroll: Int = RegionBitmapDecoder.SCROLL_UNDEFINED,
): ImageRequest.Builder = apply {
    decoderFactory(RegionBitmapDecoder.Factory)
    // Use extras.set() for cleaner syntax in Coil 3
    extras[RegionBitmapDecoder.regionScrollKey] = scroll
}

fun ImageRequest.Builder.mangaSourceExtra(source: MangaSource?): ImageRequest.Builder = apply {
    extras[mangaSourceKey] = source
}

fun ImageRequest.Builder.mangaExtra(manga: Manga?): ImageRequest.Builder = apply {
    extras[mangaKey] = manga
    mangaSourceExtra(manga?.source)
}

fun ImageRequest.Builder.bookmarkExtra(bookmark: Bookmark): ImageRequest.Builder = apply {
    extras[bookmarkKey] = bookmark
    mangaSourceExtra(bookmark.manga.source)
}

suspend fun ImageLoader.fetch(data: Any, options: Options): FetchResult? {
    // FIXED: Ensure we pass the correct data type to the fetcher
    val mappedData = components.map(data, options)
    val fetcher = components.newFetcher(mappedData, options, this)?.first
    return fetcher?.fetch()
}

// Fixed Key definitions to use non-nullable types where possible, or explicit nulls
val mangaKey = Extras.Key<Manga?>(null)
val bookmarkKey = Extras.Key<Bookmark?>(null)
val mangaSourceKey = Extras.Key<MangaSource?>(null)

@CheckResult
fun SourceFetchResult.copyWithNewSource(): SourceFetchResult = SourceFetchResult(
    source = ImageSource(
        source = source.source(), // FIXED: Access the source directly rather than re-opening from filesystem
        fileSystem = source.fileSystem,
        metadata = source.metadata,
    ),
    mimeType = mimeType,
    dataSource = dataSource,
)
