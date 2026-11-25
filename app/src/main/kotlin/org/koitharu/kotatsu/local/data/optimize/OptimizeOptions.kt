package org.koitharu.kotatsu.local.data.optimize

/**
 * What an optimize run does to the pages of downloaded archives.
 *
 * @property splitTall cut very tall images (height over 3x width) into several WebP pages.
 * @property convertToWebp re-encode JPEG / PNG / BMP / still GIF / AVIF / JPEG XL pages as WebP (WebP and animated pages are
 * never touched).
 * @property fallbackQuality lossy WebP quality used when the source quality cannot be read.
 */
data class OptimizeOptions(
    val splitTall: Boolean,
    val convertToWebp: Boolean,
    val fallbackQuality: Int = DEFAULT_QUALITY,
    /** Follow the source quality (see WebpPolicy) instead of the fixed rules. */
    val autoQuality: Boolean = true,
) {

    val isNoop: Boolean
        get() = !splitTall && !convertToWebp

    companion object {

        const val DEFAULT_QUALITY = 95
    }
}

/** Result of a dry run over a set of archives (headers only, nothing is decoded or written). */
data class ScanResult(
    val archives: Int,
    /** Archives with at least one page to change. */
    val archivesToChange: Int,
    val imagesToSplit: Int,
    val imagesToConvert: Int,
    /** Pages the selected mode would like to convert but cannot (over 16383 px or too big to decode). */
    val imagesTooLarge: Int,
) {

    val isEmpty: Boolean
        get() = imagesToSplit == 0 && imagesToConvert == 0
}

/** Counters of one archive or a whole run. */
data class OptimizeStats(
    val archives: Int = 0,
    val changedArchives: Int = 0,
    val failedArchives: Int = 0,
    val splitImages: Int = 0,
    val createdParts: Int = 0,
    val convertedImages: Int = 0,
    /** Pages that were selected but kept as they were (decode / encode failure, no size gain, name clash). */
    val keptImages: Int = 0,
    val bytesBefore: Long = 0L,
    val bytesAfter: Long = 0L,
) {

    operator fun plus(o: OptimizeStats) = OptimizeStats(
        archives = archives + o.archives,
        changedArchives = changedArchives + o.changedArchives,
        failedArchives = failedArchives + o.failedArchives,
        splitImages = splitImages + o.splitImages,
        createdParts = createdParts + o.createdParts,
        convertedImages = convertedImages + o.convertedImages,
        keptImages = keptImages + o.keptImages,
        bytesBefore = bytesBefore + o.bytesBefore,
        bytesAfter = bytesAfter + o.bytesAfter,
    )

    val bytesSaved: Long
        get() = bytesBefore - bytesAfter
}
