package org.koitharu.kotatsu.core.util

import android.os.Build
import android.webkit.MimeTypeMap
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.removeSuffix
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.nio.file.Files
import coil3.util.MimeTypeMap as CoilMimeTypeMap

object MimeTypes {

    /** Image types older Android / Coil tables don't know; used only when they return nothing. */
    private val EXTRA_TYPES = mapOf(
        "jxl" to "image/jxl",
        "avif" to "image/avif",
        "apng" to "image/apng",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "jfif" to "image/jpeg",
        "jpe" to "image/jpeg",
        "pjpeg" to "image/jpeg",
    )

    fun getMimeTypeFromExtension(fileName: String): MimeType? {
        val ext = getNormalizedExtension(fileName) ?: return null
        return (CoilMimeTypeMap.getMimeTypeFromExtension(ext) ?: EXTRA_TYPES[ext])?.toMimeTypeOrNull()
    }

    fun getMimeTypeFromUrl(url: String): MimeType? {
        CoilMimeTypeMap.getMimeTypeFromUrl(url)?.toMimeTypeOrNull()?.let { return it }
        val path = url.substringBefore('#').substringBefore('?')
        return EXTRA_TYPES[path.substringAfterLast('.', "").lowercase()]?.toMimeTypeOrNull()
    }

    fun getExtension(mimeType: MimeType?): String? {
        val value = mimeType?.toString() ?: return null
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(value)?.nullIfEmpty()
            ?: EXTRA_TYPES.entries.firstOrNull { it.value == value }?.key
    }

    @Blocking
    fun probeMimeType(file: File): MimeType? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatchingCancellable {
                Files.probeContentType(file.toPath())?.toMimeTypeOrNull()
            }.getOrNull()?.let { return it }
        }
        return getMimeTypeFromExtension(file.name)
    }

    fun getNormalizedExtension(name: String): String? = name
        .lowercase()
        .removeSuffix('~')
        .removeSuffix(".tmp")
        .substringAfterLast('.', "")
        .takeIf { it.length in 2..5 }
}
