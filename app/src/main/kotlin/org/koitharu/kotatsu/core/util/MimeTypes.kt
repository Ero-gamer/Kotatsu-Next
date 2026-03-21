package org.koitharu.kotatsu.core.util

import android.os.Build
import android.webkit.MimeTypeMap
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.nio.file.Files
import coil3.util.MimeTypeMap as CoilMimeTypeMap

object MimeTypes {

    private const val EXT_AVIF = "avif"
    private const val MIME_AVIF = "image/avif"

    fun getMimeTypeFromExtension(fileName: String): MimeType? {
        val extension = getNormalizedExtension(fileName) ?: return null
        
        // Manual override for AVIF as some older Android MimeTypeMaps don't recognize it
        if (extension == EXT_AVIF) return MIME_AVIF.toMimeTypeOrNull()

        return CoilMimeTypeMap.getMimeTypeFromExtension(extension)
            ?.toMimeTypeOrNull()
    }

    fun getMimeTypeFromUrl(url: String): MimeType? {
        // Handle URLs ending in .avif before passing to Coil
        if (url.substringBefore('?').lowercase().endsWith(".$EXT_AVIF")) {
            return MIME_AVIF.toMimeTypeOrNull()
        }
        return CoilMimeTypeMap.getMimeTypeFromUrl(url)?.toMimeTypeOrNull()
    }

    fun getExtension(mimeType: MimeType?): String? {
        val mimeString = mimeType?.toString() ?: return null
        if (mimeString == MIME_AVIF) return EXT_AVIF
        
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeString)?.nullIfEmpty()
    }

    @Blocking
    fun probeMimeType(file: File): MimeType? {
        // On Android 14+ (SDK 34), Files.probeContentType is quite reliable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatchingCancellable {
                Files.probeContentType(file.toPath())?.toMimeTypeOrNull()
            }.getOrNull()?.let { return it }
        }
        
        // Fallback to extension check
        return getMimeTypeFromExtension(file.name)
    }

    fun getNormalizedExtension(name: String): String? {
        val cleaned = name.lowercase()
            .substringBeforeLast('~') // Handles things like 'image.jpg~tmp'
            .substringBeforeLast(".tmp")
            
        val ext = cleaned.substringAfterLast('.', "")
        
        // AVIF is 4 chars, most are 3-4. We allow 2..5 to cover .ico to .jpeg
        return ext.takeIf { it.length in 2..5 }
    }
}
