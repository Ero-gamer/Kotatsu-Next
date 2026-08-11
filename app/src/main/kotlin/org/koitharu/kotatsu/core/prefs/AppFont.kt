package org.koitharu.kotatsu.core.prefs

import androidx.annotation.FontRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import org.koitharu.kotatsu.R

/**
 * Built-in font options available in Settings → Appearance → Font.
 *
 * Each entry has an optional [fontRes] pointing to a bundled font file in res/font/.
 * A null [fontRes] means "use the system default" (i.e. no custom typeface applied).
 *
 * The [key] is persisted to SharedPreferences and must never change between releases.
 */
@Keep
enum class AppFont(
	val key: String,
	@StringRes val titleResId: Int,
	@FontRes val fontRes: Int?,
	/** ThemeOverlay style resource to set this font app-wide, or 0 for system default */
	@StyleRes val themeOverlayRes: Int,
) {
	/** Android system default — no override applied */
	SYSTEM(
		key = "system",
		titleResId = R.string.font_system,
		fontRes = null,
		themeOverlayRes = 0,
	),

	/** Google Sans — clean modern sans-serif (bundled from commit, used by FloatingNavBar) */
	GOOGLE_SANS(
		key = "google_sans",
		titleResId = R.string.font_google_sans,
		fontRes = R.font.google_sans,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_GoogleSans,
	),

	/** Inter — versatile FOSS humanist sans-serif by Rasmus Andersson (Inter 4.0, OFL) */
	INTER(
		key = "inter",
		titleResId = R.string.font_inter,
		fontRes = R.font.inter_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_Inter,
	),

	/** Fira Sans — Mozilla FOSS humanist sans (OFL) */
	FIRA_SANS(
		key = "fira_sans",
		titleResId = R.string.font_fira_sans,
		fontRes = R.font.fira_sans_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_FiraSans,
	),

	/** DejaVu Serif — friendly open FOSS serif (Bitstream Vera derivative, OFL-compatible) */
	DEJAVU_SERIF(
		key = "dejavu_serif",
		titleResId = R.string.font_dejavu_serif,
		fontRes = R.font.dejavu_serif,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_DejaVuSerif,
	),

	/** DejaVu Sans Mono — clean FOSS monospace, great for source listings (OFL-compatible) */
	DEJAVU_SANS_MONO(
		key = "dejavu_sans_mono",
		titleResId = R.string.font_dejavu_sans_mono,
		fontRes = R.font.dejavu_sans_mono,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_DejaVuSansMono,
	),

	/** Bitstream Vera Sans — compact FOSS humanist sans, very legible at small sizes (OFL-compatible) */
	BITSTREAM_VERA(
		key = "bitstream_vera",
		titleResId = R.string.font_bitstream_vera,
		fontRes = R.font.bitstream_vera_sans,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_BitstreamVera,
	),

	/** OpenDyslexic — accessibility font designed to aid readers with dyslexia (OFL) */
	OPEN_DYSLEXIC(
		key = "open_dyslexic",
		titleResId = R.string.font_open_dyslexic,
		fontRes = R.font.open_dyslexic_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_OpenDyslexic,
	),

	/** JetBrains Mono — geometric monospace with ligatures (OFL) */
	JETBRAINS_MONO(
		key = "jetbrains_mono",
		titleResId = R.string.font_jetbrains_mono,
		fontRes = R.font.jetbrains_mono_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_JetBrainsMono,
	),
	;

	companion object {
		fun fromKey(key: String?): AppFont = entries.firstOrNull { it.key == key } ?: SYSTEM
	}
}
