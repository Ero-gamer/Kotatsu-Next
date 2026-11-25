package org.koitharu.kotatsu.core.util.ext

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.CheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Source
import okio.source
import org.koitharu.kotatsu.core.util.CancellableSource
import org.koitharu.kotatsu.core.util.progress.ProgressResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

fun ResponseBody.withProgress(progressState: MutableStateFlow<Float>): ResponseBody = ProgressResponseBody(this, progressState)

suspend fun Source.cancellable(): Source {
    val job = currentCoroutineContext()[Job]
    return CancellableSource(job, this)
}

suspend fun BufferedSink.writeAllCancellable(source: Source) = withContext(Dispatchers.IO) {
    writeAll(source.cancellable())
}

/** Largest image file read fully into memory for a decoder that cannot stream (libavif, system fallbacks). */
const val MAX_DECODE_INPUT_BYTES = 64L * 1024 * 1024

/** Reads everything, but fails with [IOException] when the source has more than [max] bytes (never allocates past it). */
@Throws(IOException::class)
fun BufferedSource.readByteArrayAtMost(max: Long = MAX_DECODE_INPUT_BYTES): ByteArray {
    if (request(max + 1)) throw IOException("Input is larger than $max bytes")
    return readByteArray()
}

/**
 * Renames this file over [target]. rename(2) replaces an existing target atomically, so a crash never leaves
 * neither file; falls back to delete + rename and fails loudly instead of ignoring a false result.
 */
@Throws(IOException::class)
fun File.renameReplacing(target: File) {
    if (renameTo(target)) return
    if (target.exists() && !target.delete()) throw IOException("Cannot replace ${target.name}")
    if (!renameTo(target)) throw IOException("Cannot rename $name to ${target.name}")
}

fun BufferedSource.readByteBuffer(): ByteBuffer {
    val bytes = readByteArrayAtMost()
    return ByteBuffer.allocateDirect(bytes.size)
        .put(bytes)
        .rewind() as ByteBuffer
}

@Deprecated("")
fun InputStream.toByteBuffer(): ByteBuffer {
    val outStream = ByteArrayOutputStream(available().coerceIn(0, 1 shl 20))
    val chunk = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        total += n
        if (total > MAX_DECODE_INPUT_BYTES) throw IOException("Input is larger than $MAX_DECODE_INPUT_BYTES bytes")
        outStream.write(chunk, 0, n)
    }
    val bytes = outStream.toByteArray()
    return ByteBuffer.allocateDirect(bytes.size).put(bytes).position(0) as ByteBuffer
}

fun FileSystem.isDirectory(path: Path) = try {
    metadataOrNull(path)?.isDirectory == true
} catch (_: IOException) {
    false
}

fun FileSystem.isRegularFile(path: Path) = try {
    metadataOrNull(path)?.isRegularFile == true
} catch (_: IOException) {
    false
}

@CheckResult
fun ContentResolver.openSource(uri: Uri): Source = checkNotNull(openInputStream(uri)) {
    "Cannot open input stream from $uri"
}.source()
