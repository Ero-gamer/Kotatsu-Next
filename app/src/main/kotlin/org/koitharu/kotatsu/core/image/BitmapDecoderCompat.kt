package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import com.github.awxkee.avifcoder.HeifCoder // FIXED: Updated to modern dependency
import okio.IOException
import okio.buffer
import okio.source
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.readByteBuffer
import org.koitharu.kotatsu.core.util.ext.toByteBuffer
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

object BitmapDecoderCompat {

    private const val FORMAT_AVIF = "avif"
    private val heifCoder = HeifCoder()

    @Blocking
    fun decode(file: File): Bitmap = when (val format = probeMimeType(file)?.subtype) {
        FORMAT_AVIF -> file.source().buffer().use { decodeAvif(it.readByteBuffer()) }
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file))
        } else {
            checkBitmapNotNull(BitmapFactory.decodeFile(file.absolutePath), format)
        }
    }

    @Blocking
    fun decode(stream: InputStream, type: MimeType?, isMutable: Boolean = false): Bitmap {
        val format = type?.subtype
        val byteBuffer = stream.toByteBuffer()
        
        // Check for AVIF magic bytes or subtype
        if (format == FORMAT_AVIF || isAvif(byteBuffer)) {
            return decodeAvif(byteBuffer)
        }

        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val opts = BitmapFactory.Options()
            opts.inMutable = isMutable
            // Reset position if we read from it for the AVIF check
            byteBuffer.position(0)
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            checkBitmapNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts), format)
        } else {
            byteBuffer.position(0)
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(byteBuffer), DecoderConfigListener(isMutable))
        }
    }

    @Blocking
    fun createRegionDecoder(inoutStream: InputStream): BitmapRegionDecoder? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(inoutStream)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(inoutStream, false)
        }
    } catch (e: IOException) {
        e.printStackTraceDebug()
        null
    }

    @Blocking
    fun probeMimeType(file: File): MimeType? {
        return MimeTypes.probeMimeType(file) ?: detectBitmapType(file)
    }

    @Blocking
    private fun detectBitmapType(file: File): MimeType? = runCatchingCancellable {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.path, options)
        options.outMimeType?.toMimeTypeOrNull()
    }.getOrNull()

    private fun checkBitmapNotNull(bitmap: Bitmap?, format: String?): Bitmap =
        bitmap ?: throw ImageDecodeException(null, format)

    private fun isAvif(byteBuffer: ByteBuffer): Boolean {
        byteBuffer.mark()
        val bytes = ByteArray(12)
        if (byteBuffer.remaining() < 12) return false
        byteBuffer.get(bytes)
        byteBuffer.reset()
        val brand = String(bytes, 8, 4)
        return brand == "avif" || brand == "avis"
    }

    private fun decodeAvif(buffer: ByteBuffer): Bitmap {
        val bytes = if (buffer.hasArray()) {
            buffer.array()
        } else {
            val array = ByteArray(buffer.remaining())
            buffer.get(array)
            array
        }

        return try {
            heifCoder.decode(bytes) ?: throw ImageDecodeException(null, FORMAT_AVIF, "HeifCoder returned null")
        } catch (e: Exception) {
            throw ImageDecodeException(null, FORMAT_AVIF, e.message)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private class DecoderConfigListener(
        private val isMutable: Boolean,
    ) : ImageDecoder.OnHeaderDecodedListener {

        override fun onHeaderDecoded(
            decoder: ImageDecoder,
            info: ImageDecoder.ImageInfo,
            source: ImageDecoder.Source
        ) {
            decoder.isMutableRequired = isMutable
        }
    }
}
