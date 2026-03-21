package org.koitharu.kotatsu.core.util.ext

import okhttp3.MediaType
import java.util.Locale

private const val TYPE_IMAGE = "image"
// Updated Regex to be slightly more permissive for modern sub-types (like +xml or .avif)
private val REGEX_MIME = Regex("""^[\w.+-]+/[\w.+-]+$""", RegexOption.IGNORE_CASE)

@JvmInline
value class MimeType(val value: String) {

    val type: String?
        get() = value.substringBefore('/', "").takeIfSpecified()

    val subtype: String?
        get() = value.substringAfterLast('/', "").takeIfSpecified()

    private fun String.takeIfSpecified(): String? = takeUnless {
        it.isEmpty() || it == "*"
    }

    override fun toString(): String = value
}

/**
 * Converts OkHttp MediaType to our value class, stripping parameters 
 * to keep only the base type/subtype.
 */
fun MediaType.toMimeType(): MimeType = MimeType("${type}/${subtype}")

/**
 * Parses a string into a MimeType, ensuring it follows the type/subtype format.
 */
fun String.toMimeTypeOrNull(): MimeType? = if (REGEX_MIME.matches(this)) {
    MimeType(this.lowercase(Locale.ROOT))
} else {
    null
}

val MimeType.isImage: Boolean
    get() = type == TYPE_IMAGE
