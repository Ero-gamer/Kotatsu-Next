package org.koitharu.kotatsu.local.data.importer

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.getSystemService
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.exceptions.UnsupportedFileException
import org.koitharu.kotatsu.core.image.ImageFormat
import org.koitharu.kotatsu.core.image.LossyQuality
import org.koitharu.kotatsu.core.image.WebpEncoding
import org.koitharu.kotatsu.core.image.WebpTarget
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF -> CBZ. Every page is rendered with the platform [PdfRenderer] and stored as a WebP (JPEG if the encoder
 * fails). Quality of the output: scanned PDFs hold their pages as JPEG streams, so the quantisation tables of a
 * few of them tell how the pages were compressed; the median of that is used for the WebP ([FALLBACK_QUALITY]
 * when no embedded JPEG is found or none can be read).
 *
 * Memory: a page is rendered at most [maxSide] px on its long side, into two reusable bitmaps (one is encoded
 * on another thread while the next page is rendered into the other).
 */
@Reusable
class PdfToCbzConverter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val maxSide: Int = run {
        val am = context.getSystemService<ActivityManager>()
        if (am == null || am.isLowRamDevice || am.memoryClass < 192) MAX_SIDE_LOW_RAM else MAX_SIDE
    }

    /** @return the number of pages written. [onProgress] gets (done, total). */
    suspend fun convert(uri: Uri, dest: File, onProgress: ((Int, Int) -> Unit)?): Int = withContext(Dispatchers.IO) {
        val quality = detectQuality(uri) ?: FALLBACK_QUALITY
        var copy: File? = null
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                renderer = pfd?.let { PdfRenderer(it) }
            } catch (_: IOException) {
                // Not seekable (a stream from another app) or damaged: PdfRenderer needs a real file, retry on a copy.
                renderer?.close()
                pfd?.close()
                renderer = null
                pfd = null
            }
            if (renderer == null) {
                val file = File.createTempFile("pdf_import", ".pdf", context.cacheDir)
                copy = file
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } ?: throw IOException("Cannot open $uri")
                val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = opened
                renderer = PdfRenderer(opened)
            }
            writeCbz(checkNotNull(renderer), dest, quality, onProgress)
        } catch (e: SecurityException) {
            throw UnsupportedFileException("PDF is password protected or cannot be opened: ${e.message}")
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            copy?.delete()
        }
    }

    private suspend fun writeCbz(
        renderer: PdfRenderer,
        dest: File,
        quality: Int,
        onProgress: ((Int, Int) -> Unit)?,
    ): Int = coroutineScope {
        val total = renderer.pageCount
        if (total <= 0) {
            throw UnsupportedFileException("PDF has no pages")
        }
        val digits = total.toString().length.coerceAtLeast(4)
        val buffers = arrayOfNulls<Bitmap>(2)
        var written = 0
        try {
            ZipOutputStream(dest.outputStream().buffered(OUT_BUFFER)).use { zip ->
                zip.setLevel(0)
                var pending: Deferred<Encoded?>? = null
                var pendingIndex = -1

                suspend fun flushPending() {
                    val job = pending ?: return
                    pending = null
                    val encoded = job.await() ?: return
                    zip.putStored("%0${digits}d.${encoded.extension}".format(pendingIndex + 1), encoded.data)
                    written++
                    onProgress?.invoke(written, total)
                }

                for (index in 0 until total) {
                    ensureActive()
                    val slot = index % 2
                    // The buffer used two pages ago is free: its page was flushed at the previous step.
                    val bitmap = try {
                        renderPage(renderer, index, buffers, slot)
                    } catch (e: IOException) {
                        e.printStackTraceDebug() // a damaged page is skipped, the rest of the file is still good
                        null
                    } catch (e: IllegalStateException) {
                        e.printStackTraceDebug()
                        null
                    }
                    flushPending() // writes the previous page while this one has just been rendered
                    if (bitmap != null) {
                        pendingIndex = index
                        pending = async(Dispatchers.Default) { encode(bitmap, quality) }
                    }
                }
                flushPending()
            }
            if (written == 0) {
                throw IOException("No page of the PDF could be rendered")
            }
            written
        } catch (e: Throwable) {
            dest.delete()
            throw e
        } finally {
            for (b in buffers) {
                b?.recycle()
            }
        }
    }

    /** Renders a page into buffer [slot] (created or resized on demand) and returns it. */
    private fun renderPage(renderer: PdfRenderer, index: Int, buffers: Array<Bitmap?>, slot: Int): Bitmap {
        renderer.openPage(index).use { page ->
            val scale = (maxSide.toFloat() / max(page.width, page.height).coerceAtLeast(1)).coerceIn(0.05f, 4f)
            val w = (page.width * scale).roundToInt().coerceAtLeast(1)
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            var bitmap = buffers[slot]
            if (bitmap != null && !bitmap.isRecycled && bitmap.allocationByteCount >= w * h * 4) {
                bitmap.reconfigure(w, h, Bitmap.Config.ARGB_8888)
            } else {
                bitmap?.recycle()
                buffers[slot] = null
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                buffers[slot] = bitmap
            }
            // PdfRenderer draws onto what is there and leaves the background transparent
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    private class Encoded(val extension: String, val data: ByteArray)

    private fun encode(bitmap: Bitmap, quality: Int): Encoded? {
        WebpEncoding.encode(bitmap, hasAlpha = false, target = WebpTarget.Lossy(quality))?.let {
            return Encoded("webp", it)
        }
        return ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) Encoded("jpg", out.toByteArray()) else null
        }
    }

    /** Images are already compressed: the entry is stored, not deflated again. */
    private fun ZipOutputStream.putStored(name: String, data: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = data.size.toLong()
        entry.compressedSize = data.size.toLong()
        entry.crc = CRC32().apply { update(data) }.value
        putNextEntry(entry)
        write(data)
        closeEntry()
    }

    /**
     * Median quality of up to [MAX_SAMPLES] embedded JPEG streams found in the first [SCAN_LIMIT] bytes of the
     * file (DCTDecode streams are plain JPEG files inside the PDF), or null. Sequential read, no PDF parsing:
     * only the start of each JPEG (its quantisation tables) is looked at.
     */
    private fun detectQuality(uri: Uri): Int? = try {
        val samples = ArrayList<Int>(MAX_SAMPLES)
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(SCAN_CHUNK)
            var filled = 0
            var total = 0L
            var eof = false
            while (!eof && total < SCAN_LIMIT && samples.size < MAX_SAMPLES) {
                while (filled < buf.size) {
                    val n = input.read(buf, filled, buf.size - filled)
                    if (n < 0) {
                        eof = true
                        break
                    }
                    filled += n
                    total += n
                }
                // Starts too close to the end are examined with the next chunk (unless there is none)
                val stop = if (eof) filled - 3 else filled - JPEG_HEAD
                var i = 0
                while (i < stop && samples.size < MAX_SAMPLES) {
                    if (buf[i] == FF && buf[i + 1] == SOI && buf[i + 2] == FF) {
                        val len = min(JPEG_HEAD, filled - i)
                        val r = LossyQuality.analyze(buf.copyOfRange(i, i + len), len, ImageFormat.JPEG)
                        if (r != null && r.kind != LossyQuality.Kind.LOSSLESS) {
                            samples += r.quality.coerceIn(1, 100)
                        }
                        i += 3
                    } else {
                        i++
                    }
                }
                if (!eof) {
                    System.arraycopy(buf, i, buf, 0, filled - i)
                    filled -= i
                }
            }
        }
        if (samples.isEmpty()) null else samples.sorted()[samples.size / 2]
    } catch (e: IOException) {
        e.printStackTraceDebug()
        null
    } catch (e: SecurityException) {
        null
    }

    private companion object {

        const val FALLBACK_QUALITY = 90
        const val MAX_SIDE = 2048
        const val MAX_SIDE_LOW_RAM = 1600
        const val MAX_SAMPLES = 8
        const val SCAN_LIMIT = 32L * 1024 * 1024
        const val SCAN_CHUNK = 1 shl 20
        const val JPEG_HEAD = 16 * 1024
        const val OUT_BUFFER = 64 * 1024
        const val FF: Byte = 0xFF.toByte()
        const val SOI: Byte = 0xD8.toByte()
    }
}
