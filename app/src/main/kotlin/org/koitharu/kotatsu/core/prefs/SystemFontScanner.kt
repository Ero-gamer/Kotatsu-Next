package org.koitharu.kotatsu.core.prefs

import android.graphics.Typeface
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import android.os.Build
import java.io.File

/**
 * Scans fonts available on the device and returns them as [SystemFontEntry] objects.
 *
 * On API 29+ we use [SystemFonts.getAvailableFonts] which gives us structured metadata.
 * On API 21–28 we fall back to scanning /system/fonts/ directly.
 *
 * Results are cached after the first call (fonts don't change without a reboot).
 */
object SystemFontScanner {

	@Volatile
	private var cache: List<SystemFontEntry>? = null

	/**
	 * Returns the [Typeface] for [displayName] from the in-memory cache ONLY.
	 * Never triggers a disk scan — safe to call at any time including startup.
	 * Returns null when the cache is cold (no font picker visit yet this process).
	 */
	fun getCachedTypefaceForDisplayName(displayName: String): Typeface? =
		cache?.firstOrNull { it.name == displayName }?.typeface

	/** Returns a sorted, deduplicated list of system fonts.  Thread-safe via double-checked locking. */
	fun getSystemFonts(): List<SystemFontEntry> {
		cache?.let { return it }
		synchronized(this) {
			cache?.let { return it }
			val result = buildList()
			cache = result
			return result
		}
	}

	private fun buildList(): List<SystemFontEntry> {
		val entries = LinkedHashMap<String, SystemFontEntry>(64)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			// API 29+: structured font metadata
			for (font in SystemFonts.getAvailableFonts()) {
				val file = font.file ?: continue
				if (!file.exists()) continue
				// Only regular weight (400) non-italic to avoid duplicates
				if (font.style.weight != 400 || font.style.slant != FontStyle.FONT_SLANT_UPRIGHT) continue
				// Build a display name from the filename
				val name = fontFileToDisplayName(file) ?: continue
				if (name in entries) continue
				val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull() ?: continue
				entries[name] = SystemFontEntry(name = name, file = file, typeface = typeface)
			}
		} else {
			// API 21-28: scan /system/fonts/
			val dir = File("/system/fonts")
			if (dir.isDirectory) {
				for (file in dir.listFiles() ?: emptyArray()) {
					if (!file.name.endsWith(".ttf") && !file.name.endsWith(".otf")) continue
					// Only regular variants (no Bold, Italic, Thin, etc.)
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
	 *      "DroidSans.ttf"        → "Droid Sans"
	 */
	private fun fontFileToDisplayName(file: File): String? {
		var name = file.nameWithoutExtension
		// Strip common weight/style suffixes
		name = name
			.replace(Regex("-Regular$", RegexOption.IGNORE_CASE), "")
			.replace(Regex("-Roman$",   RegexOption.IGNORE_CASE), "")
			.replace(Regex("Regular$",  RegexOption.IGNORE_CASE), "")
		// Insert space before uppercase letter preceded by lowercase (CamelCase → Camel Case)
		name = name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
			.replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
			.trim()
		// Ignore system/UI internals
		if (name.isBlank() || name.startsWith(".") || name.contains("Emoji") || name.contains("NotoColorEmoji")) return null
		return name.ifBlank { null }
	}

	/** Returns true if the filename contains a weight/style qualifier that means it's not the regular face. */
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
