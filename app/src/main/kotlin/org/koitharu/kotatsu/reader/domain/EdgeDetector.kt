package org.koitharu.kotatsu.reader.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.graphics.Point
import android.graphics.Rect
import androidx.annotation.ColorInt
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.decoder.BitmapQuality
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageRegionDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.image.ImageFormatProbe
import org.koitharu.kotatsu.core.image.ImageInfo
import org.koitharu.kotatsu.core.image.ImageInspector
import org.koitharu.kotatsu.core.util.SynchronizedSieveCache
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class EdgeDetector(
    private val context: Context,
    /** Read live so a settings change takes effect without recreating the detector. */
    private val toleranceProvider: () -> Int,
) {

    private val mutex = Mutex()
    private val cache = SynchronizedSieveCache<ImageSource, Rect>(CACHE_SIZE)

    suspend fun getBounds(imageSource: ImageSource): Rect? {
        cache[imageSource]?.let { rect ->
            return if (rect.isEmpty) null else rect
        }
        return mutex.withLock {
            // Another caller may have computed this source while we were waiting for the lock.
            cache[imageSource]?.let { rect ->
                return@withLock if (rect.isEmpty) null else rect
            }
            withContext(Dispatchers.IO) {
                // One decode of the whole page: a pool would only start extra threads and native decoders.
                val decoder = SkiaImageRegionDecoder(
                    // RGB_565 has no alpha: transparent pixels would turn black and read as content.
                    if (mayHaveAlpha(imageSource)) BitmapQuality.STANDARD else BitmapQuality.MEMORY_SAVING,
                )
                try {
                    val size = runInterruptible {
                        decoder.init(context, imageSource)
                    }
                    val scaleFactor = calculateScaleFactor(size)
                    val sampleSize = (1f / scaleFactor).toInt().coerceAtLeast(1)

                    val fullBitmap = decoder.decodeRegion(
                        Rect(0, 0, size.x, size.y),
                        sampleSize,
                    )

                    try {
                        val black = isBlackBackground(fullBitmap, toleranceProvider())
                        val edges = coroutineScope {
                            listOf(
                                async { detectLeftRightEdge(fullBitmap, size, sampleSize, isLeft = true, black) },
                                async { detectTopBottomEdge(fullBitmap, size, sampleSize, isTop = true, black) },
                                async { detectLeftRightEdge(fullBitmap, size, sampleSize, isLeft = false, black) },
                                async { detectTopBottomEdge(fullBitmap, size, sampleSize, isTop = false, black) },
                            ).awaitAll()
                        }
                        var hasEdges = false
                        for (edge in edges) {
                            if (edge > 0) {
                                hasEdges = true
                            } else if (edge < 0) {
                                return@withContext null
                            }
                        }
                        if (hasEdges) {
                            Rect(edges[0], edges[1], size.x - edges[2], size.y - edges[3])
                        } else {
                            null
                        }
                    } finally {
                        fullBitmap.recycle()
                    }
                } finally {
                    decoder.recycle()
                }
            }.also {
                cache.put(imageSource, it ?: EMPTY_RECT)
            }
        }
    }

    /**
     * Whether the page has black margins: at least 5 of 8 points around its edge (corners and edge midpoints)
     * are black and fewer than 5 are white. White stays the default, so pages with white margins crop as before.
     */
    private fun isBlackBackground(bitmap: Bitmap, tolerance: Int): Boolean {
        val w = bitmap.width - 1
        val h = bitmap.height - 1
        if (w < 1 || h < 1) return false
        val xs = intArrayOf(0, w, 0, w, w / 2, w / 2, 0, w)
        val ys = intArrayOf(0, 0, h, h, 0, h, h / 2, h / 2)
        var black = 0
        var white = 0
        for (i in xs.indices) {
            val pixel = bitmap.getPixel(xs[i], ys[i])
            if (!pixel.isNotBlack(tolerance)) black++
            if (!pixel.isNotWhite(tolerance)) white++
        }
        return black >= BACKGROUND_VOTES && white < BACKGROUND_VOTES
    }

    private fun detectLeftRightEdge(bitmap: Bitmap, size: Point, sampleSize: Int, isLeft: Boolean, black: Boolean): Int {
        val tolerance = toleranceProvider()
        var width = size.x
        val rectCount = size.x / BLOCK_SIZE
        val maxRect = rectCount / 3
        val blockPixels = IntArray(BLOCK_SIZE * BLOCK_SIZE)

        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height

        for (i in 0 until rectCount) {
            if (i > maxRect) {
                return -1
            }
            var dd = BLOCK_SIZE
            for (j in 0 until size.y / BLOCK_SIZE) {
                val regionX = if (isLeft) i * BLOCK_SIZE else size.x - (i + 1) * BLOCK_SIZE
                val regionY = j * BLOCK_SIZE

                // Convert to bitmap coordinates
                val bitmapX = regionX / sampleSize
                val bitmapY = regionY / sampleSize
                val blockWidth = min(BLOCK_SIZE / sampleSize, bitmapWidth - bitmapX)
                val blockHeight = min(BLOCK_SIZE / sampleSize, bitmapHeight - bitmapY)

                if (blockWidth > 0 && blockHeight > 0) {
                    bitmap.getPixels(blockPixels, 0, blockWidth, bitmapX, bitmapY, blockWidth, blockHeight)

                    for (ii in 0 until minOf(blockWidth, dd / sampleSize)) {
                        for (jj in 0 until blockHeight) {
                            val bi = if (isLeft) ii else blockWidth - ii - 1
                            val pixel = blockPixels[jj * blockWidth + bi]
                            if (if (black) pixel.isNotBlack(tolerance) else pixel.isNotWhite(tolerance)) {
                                width = minOf(width, BLOCK_SIZE * i + ii * sampleSize)
                                dd -= sampleSize
                                break
                            }
                        }
                    }
                }
                if (dd == 0) {
                    break
                }
            }
            if (dd < BLOCK_SIZE) {
                break // We have already found vertical field or it is not exist
            }
        }
        return width
    }

    private fun detectTopBottomEdge(bitmap: Bitmap, size: Point, sampleSize: Int, isTop: Boolean, black: Boolean): Int {
        val tolerance = toleranceProvider()
        var height = size.y
        val rectCount = size.y / BLOCK_SIZE
        val maxRect = rectCount / 3
        val blockPixels = IntArray(BLOCK_SIZE * BLOCK_SIZE)

        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height

        for (j in 0 until rectCount) {
            if (j > maxRect) {
                return -1
            }
            var dd = BLOCK_SIZE
            for (i in 0 until size.x / BLOCK_SIZE) {
                val regionX = i * BLOCK_SIZE
                val regionY = if (isTop) j * BLOCK_SIZE else size.y - (j + 1) * BLOCK_SIZE

                // Convert to bitmap coordinates
                val bitmapX = regionX / sampleSize
                val bitmapY = regionY / sampleSize
                val blockWidth = min(BLOCK_SIZE / sampleSize, bitmapWidth - bitmapX)
                val blockHeight = min(BLOCK_SIZE / sampleSize, bitmapHeight - bitmapY)

                if (blockWidth > 0 && blockHeight > 0) {
                    bitmap.getPixels(blockPixels, 0, blockWidth, bitmapX, bitmapY, blockWidth, blockHeight)

                    for (jj in 0 until minOf(blockHeight, dd / sampleSize)) {
                        for (ii in 0 until blockWidth) {
                            val bj = if (isTop) jj else blockHeight - jj - 1
                            val pixel = blockPixels[bj * blockWidth + ii]
                            if (if (black) pixel.isNotBlack(tolerance) else pixel.isNotWhite(tolerance)) {
                                height = minOf(height, BLOCK_SIZE * j + jj * sampleSize)
                                dd -= sampleSize
                                break
                            }
                        }
                    }
                }
                if (dd == 0) {
                    break
                }
            }
            if (dd < BLOCK_SIZE) {
                break // We have already found vertical field or it is not exist
            }
        }
        return height
    }

    /**
     * Calculate scale factor for performance optimization.
     * Large images can be downscaled for edge detection without losing accuracy.
     */
    /** False only when the header proves the image is opaque; unknown formats / sources count as alpha. */
    private fun mayHaveAlpha(source: ImageSource): Boolean {
        val uri = (source as? ImageSource.Uri)?.uri ?: return true
        val info = runCatching {
            when {
                uri.isZipUri() -> probeZipEntry(uri)
                uri.isFileUri() -> uri.toFileOrNull()?.let { ImageFormatProbe.probe(it) }
                else -> null
            }
        }.getOrNull()
        return info?.hasAlpha != false
    }

    private fun probeZipEntry(uri: Uri): ImageInfo? = ZipFile(uri.schemeSpecificPart).use { zip ->
        val entry = zip.getEntry(uri.fragment ?: return null) ?: return null
        val head = ByteArray(HEAD_PROBE_BYTES)
        val read = zip.getInputStream(entry).use { ImageInspector.readHead(it, head) }
        ImageFormatProbe.probe(head, read, truncated = true)
    }

    private fun calculateScaleFactor(size: Point): Float {
        val maxDimension = max(size.x, size.y)
        return when {
            maxDimension <= 1024 -> 1.0f
            maxDimension <= 2048 -> 0.75f
            maxDimension <= 4096 -> 0.5f
            else -> 0.25f
        }
    }

    companion object {

        private const val BLOCK_SIZE = 100
        private const val BACKGROUND_VOTES = 5
        private const val BLACK_MIN_TOLERANCE = 12
        private const val CACHE_SIZE = 24
        private const val HEAD_PROBE_BYTES = 64 * 1024
        private val EMPTY_RECT = Rect(0, 0, 0, 0)

        fun isColorTheSame(@ColorInt a: Int, @ColorInt b: Int, tolerance: Int): Boolean = abs(a.red - b.red) <= tolerance &&
            abs(a.green - b.green) <= tolerance &&
            abs(a.blue - b.blue) <= tolerance &&
            abs(a.alpha - b.alpha) <= tolerance

        /** Not-black after compositing over black; near-black scan noise counts as black. */
        private fun Int.isNotBlack(tolerance: Int): Boolean {
            val t = maxOf(tolerance, BLACK_MIN_TOLERANCE)
            val a = alpha
            return red * a / 255 > t || green * a / 255 > t || blue * a / 255 > t
        }

        /** Not-white after compositing over white, so transparent margins count as blank. */
        private fun Int.isNotWhite(tolerance: Int): Boolean {
            val a = alpha
            if (a == 255) return !isColorTheSame(this, Color.WHITE, tolerance)
            val inv = 255 - a
            return 255 - (red * a + 255 * inv) / 255 > tolerance ||
                255 - (green * a + 255 * inv) / 255 > tolerance ||
                255 - (blue * a + 255 * inv) / 255 > tolerance
        }
    }
}
