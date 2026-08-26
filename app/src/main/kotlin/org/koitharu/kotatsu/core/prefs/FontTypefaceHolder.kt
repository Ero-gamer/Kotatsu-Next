package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Singleton that caches the currently-active [Typeface] for the user-selected font.
 *
 * For bundled fonts, the theme overlay (setTheme in BaseActivity.onCreate) handles
 * everything — this holder is not involved.
 *
 * For runtime fonts (SYSTEM_FONT and "system:Name" device fonts), this holder resolves
 * and caches the [Typeface]. It is used by [FontInflaterFactory2] which is installed
 * AFTER super.onCreate() in BaseActivity.
 *
 * Key design: for "system:Name" fonts, only the ONE selected font is loaded — not all
 * system fonts. This avoids blocking the main thread loading hundreds of fonts on startup,
 * which could trigger native crashes on some OEM devices with corrupt or restricted fonts.
 */
object FontTypefaceHolder {

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cachedTypeface: Typeface? = null

    /**
     * Returns the [Typeface] for [fontKey], or `null` when APP_DEFAULT (no override needed).
     * Thread-safe via double-checked locking.
     */
    fun resolve(context: Context, fontKey: String): Typeface? {
        if (fontKey == cachedKey) return cachedTypeface
        synchronized(this) {
            if (fontKey == cachedKey) return cachedTypeface
            val tf = buildTypeface(context, fontKey)
            cachedKey = fontKey
            cachedTypeface = tf
            return tf
        }
    }

    /** Clears the cache so the next [resolve] rebuilds it. Call when the user changes the font. */
    fun invalidate() {
        synchronized(this) {
            cachedKey = null
            cachedTypeface = null
        }
    }

    private fun buildTypeface(context: Context, fontKey: String): Typeface? {
        return when {
            fontKey == AppFont.APP_DEFAULT.key -> {
                null // Theme handles it
            }

            fontKey == AppFont.SYSTEM_FONT.key -> {
                // Typeface.DEFAULT IS the real device system font set by the OEM or user.
                // Do NOT use "sans-serif" — that maps to Roboto, defeating the purpose.
                Typeface.DEFAULT
            }

            fontKey.startsWith("system:") -> {
                // Load ONLY the one selected font — targeted scan, no full font list load.
                val fontName = fontKey.removePrefix("system:")
                runCatching {
                    SystemFontScanner.getTypefaceForDisplayName(fontName)
                }.getOrNull()
            }

            else -> {
                // Bundled AppFont — resolve from res/font/.
                val appFont = AppFont.fromKey(fontKey)
                val fontRes = appFont.fontRes ?: return null
                runCatching {
                    ResourcesCompat.getFont(context, fontRes)
                }.getOrNull()
            }
        }
    }
}
