package org.koitharu.kotatsu.core.prefs

import android.graphics.Typeface
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import android.os.Build
import java.io.File

/**
 * Scans system fonts and returns them as [SystemFontEntry] objects.
 *
 * ## Two access paths
 *
 * ### [getSystemFonts] — full list for the Settings font-picker UI
 * Loads all regular-weight system fonts and caches the result. Only called from
 * [FontChooserPreference] when the user opens the font chooser. Never called on startup.
 *
 * ### [getTypefaceForDisplayName] — targeted lookup for runtime font application
 * Called on startup (via [FontTypefaceHolder]) when a "system:Name" font is selected.
 * **Only the ONE matching font is loaded via [Typeface.createFromFile]**, preventing
 * the crash-on-startup caused by loading hundreds of fonts (including potentially
 * corrupt or OEM-restricted ones) on the main thread.
 */
object SystemFontScanner {

    @Volatile
    private var cache: List<SystemFontEntry>? = null

    /** Full font list for the settings UI. Thread-safe via double-checked locking. */
    fun getSystemFonts(): List<SystemFontEntry> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val result = buildFullList()
            cache = result
            return result
        }
    }

    /**
     * Returns the [Typeface] for the font whose display name equals [displayName],
     * loading only that one font file.
     *
     * If the full cache is already populated (e.g. user just came from the font picker),
     * the cache is used and no file I/O occurs. Otherwise, the system font descriptors
     * are iterated (metadata-only, fast) until the match is found, then only that file
     * is loaded via [Typeface.createFromFile].
     *
     * All failures are surfaced to the caller via `null`; the caller wraps in runCatching.
     */
    fun getTypefaceForDisplayName(displayName: String): Typeface? {
        // Fast path: cache hit (common case when fonts were already loaded for the picker)
        cache?.let { list ->
            return list.firstOrNull { it.name == displayName }?.typeface
        }

        // Targeted scan: iterate descriptors (no Typeface loading) until we find the match,
        // then load ONLY that one file.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var result: Typeface? = null
            for (font in SystemFonts.getAvailableFonts()) {
                val file = font.file ?: continue
                if (!file.exists()) continue
                if (font.style.weight != 400 || font.style.slant != FontStyle.FONT_SLANT_UPRIGHT) continue
                val name = fontFileToDisplayName(file) ?: continue
                if (name == displayName) {
                    result = Typeface.createFromFile(file)
                    break
                }
            }
            result
        } else {
            var result: Typeface? = null
            val dir = File("/system/fonts")
            if (dir.isDirectory) {
                for (file in dir.listFiles() ?: emptyArray()) {
                    if (!file.name.endsWith(".ttf") && !file.name.endsWith(".otf")) continue
                    if (hasWeightSuffix(file.name)) continue
                    val name = fontFileToDisplayName(file) ?: continue
                    if (name == displayName) {
                        result = Typeface.createFromFile(file)
                        break
                    }
                }
            }
            result
        }
    }

    private fun buildFullList(): List<SystemFontEntry> {
        val entries = LinkedHashMap<String, SystemFontEntry>(64)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (font in SystemFonts.getAvailableFonts()) {
                val file = font.file ?: continue
                if (!file.exists()) continue
                if (font.style.weight != 400 || font.style.slant != FontStyle.FONT_SLANT_UPRIGHT) continue
                val name = fontFileToDisplayName(file) ?: continue
                if (name in entries) continue
                val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: continue
                entries[name] = SystemFontEntry(name = name, file = file, typeface = typeface)
            }
        } else {
            val dir = File("/system/fonts")
            if (dir.isDirectory) {
                for (file in dir.listFiles() ?: emptyArray()) {
                    if (!file.name.endsWith(".ttf") && !file.name.endsWith(".otf")) continue
                    if (hasWeightSuffix(file.name)) continue
                    val name = fontFileToDisplayName(file) ?: continue
                    if (name in entries) continue
                    val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: continue
                    entries[name] = SystemFontEntry(name = name, file = file, typeface = typeface)
                }
            }
        }

        return entries.values
            .sortedBy { it.name }
            .distinctBy { it.name }
    }

    /**
     * Converts a font filename to a human-readable display name.
     * e.g. "NotoSans-Regular.ttf" → "Noto Sans"
     */
    private fun fontFileToDisplayName(file: File): String? {
        var name = file.nameWithoutExtension
        name = name
            .replace(Regex("-Regular$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("-Roman$",   RegexOption.IGNORE_CASE), "")
            .replace(Regex("Regular$",  RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .trim()
        if (name.isBlank() || name.startsWith(".") ||
            name.contains("Emoji") || name.contains("NotoColorEmoji")) return null
        return name.ifBlank { null }
    }

    private fun hasWeightSuffix(filename: String): Boolean {
        val lower = filename.lowercase()
        return weightSuffixes.any { lower.contains(it) }
    }

    private val weightSuffixes = listOf(
        "bold", "italic", "light", "thin", "medium", "black",
        "semibold", "condensed", "expanded", "narrow", "oblique",
        "extrabold", "extralight", "heavy", "-bd", "-it", "-bi",
    )
}

data class SystemFontEntry(
    val name: String,
    val file: java.io.File,
    val typeface: Typeface,
)
