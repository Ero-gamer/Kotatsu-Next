package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Singleton that caches the currently-active [Typeface] for the user-selected font.
 *
 * Why this exists:
 * - Bundled fonts (AppFont entries with fontRes) work fine through the theme overlay
 *   (`setTheme()` in BaseActivity.onCreate), which covers ~90%+ of screens.
 * - Device/system fonts selected via the "system:Name" key have NO static theme overlay —
 *   the Typeface can only be resolved at runtime.  The previous implementation walked the
 *   view tree once with `decorView.post {}`, which races with layout and never reaches
 *   views inflated afterwards (new fragments, dialogs, bottom sheets, etc.).
 *
 * The fix:
 * - Resolve and cache the Typeface here on first use.
 * - Inject it at *inflation time* via a [FontInflaterFactory2] installed in every host
 *   (BaseActivity, BaseAdaptiveSheet, AlertDialogFragment).
 * - For SYSTEM_FONT (the "use actual device OEM font" option), the correct Typeface is
 *   [Typeface.DEFAULT], which IS the device's system font (set by the OEM / user via
 *   manufacturer font picker).  The old ThemeOverlay that set `android:fontFamily=sans-serif`
 *   was wrong — `sans-serif` maps to Roboto, not the real device font.
 */
object FontTypefaceHolder {

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cachedTypeface: Typeface? = null

    /**
     * Returns the [Typeface] that should be applied to every TextView, or `null` when
     * the user has chosen APP_DEFAULT (no override needed — theme handles it).
     *
     * Thread-safe via double-checked locking on the key.
     */
    fun resolve(context: Context, fontKey: String): Typeface? {
        // Fast path: cache hit
        if (fontKey == cachedKey) return cachedTypeface
        synchronized(this) {
            if (fontKey == cachedKey) return cachedTypeface
            val tf = buildTypeface(context, fontKey)
            cachedKey = fontKey
            cachedTypeface = tf
            return tf
        }
    }

    /** Call when the user changes the font so the next [resolve] rebuilds the cache. */
    fun invalidate() {
        synchronized(this) {
            cachedKey = null
            cachedTypeface = null
        }
    }

    private fun buildTypeface(context: Context, fontKey: String): Typeface? {
        return when {
            fontKey == AppFont.APP_DEFAULT.key -> {
                // No override — theme's default typeface is used.
                null
            }

            fontKey == AppFont.SYSTEM_FONT.key -> {
                // Typeface.DEFAULT is the actual device system font (set by OEM or user).
                // Do NOT use "sans-serif" here — that always maps to Roboto and defeats the purpose.
                Typeface.DEFAULT
            }

            fontKey.startsWith("system:") -> {
                // Key format (new): "system:DisplayName:/absolute/path/to/font.ttf"
                // Key format (old): "system:DisplayName"  (legacy — no path embedded)
                val parts = fontKey.split(":", limit = 3)
                if (parts.size == 3 && parts[2].isNotEmpty()) {
                    // New format: load the specific file directly — no scanning, no iteration.
                    runCatching { Typeface.createFromFile(java.io.File(parts[2])) }.getOrNull()
                } else {
                    // Legacy format: cache-only lookup. Never scans disk.
                    val fontName = fontKey.removePrefix("system:")
                    SystemFontScanner.getCachedTypefaceForDisplayName(fontName)
                }
            }

            else -> {
                // Built-in bundled AppFont — resolve from res/font/.
                val appFont = AppFont.fromKey(fontKey)
                val fontRes = appFont.fontRes ?: return null
                runCatching {
                    ResourcesCompat.getFont(context, fontRes)
                }.getOrNull()
            }
        }
    }
}
