package org.koitharu.kotatsu.core.prefs

import androidx.annotation.FontRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import org.koitharu.kotatsu.R

@Keep
enum class AppFont(
	val key: String,
	@StringRes val titleResId: Int,
	@FontRes val fontRes: Int?,
	@StyleRes val themeOverlayRes: Int,
) {
	APP_DEFAULT(
		key            = "app_default",
		titleResId     = R.string.font_app_default,
		fontRes        = null,
		themeOverlayRes = 0,
	),
	GOOGLE_SANS(
		key            = "google_sans",
		titleResId     = R.string.font_google_sans,
		fontRes        = R.font.google_sans,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_GoogleSans,
	),
	INTER(
		key            = "inter",
		titleResId     = R.string.font_inter,
		fontRes        = R.font.inter_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_Inter,
	),
	NUNITO(
		key            = "nunito",
		titleResId     = R.string.font_nunito,
		fontRes        = R.font.nunito_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_Nunito,
	),
	SOURCE_SANS(
		key            = "source_sans",
		titleResId     = R.string.font_source_sans,
		fontRes        = R.font.source_sans_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_SourceSans,
	),
	LEXEND(
		key            = "lexend",
		titleResId     = R.string.font_lexend,
		fontRes        = R.font.lexend_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_Lexend,
	),
	NOTO_SANS(
		key            = "noto_sans",
		titleResId     = R.string.font_noto_sans,
		fontRes        = R.font.noto_sans_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_NotoSans,
	),
	UBUNTU(
		key            = "ubuntu",
		titleResId     = R.string.font_ubuntu,
		fontRes        = R.font.ubuntu_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_Ubuntu,
	),
	FIRA_SANS(
		key            = "fira_sans",
		titleResId     = R.string.font_fira_sans,
		fontRes        = R.font.fira_sans_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_FiraSans,
	),
	DEJAVU_SERIF(
		key            = "dejavu_serif",
		titleResId     = R.string.font_dejavu_serif,
		fontRes        = R.font.dejavu_serif,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_DejaVuSerif,
	),
	DEJAVU_SANS_MONO(
		key            = "dejavu_sans_mono",
		titleResId     = R.string.font_dejavu_sans_mono,
		fontRes        = R.font.dejavu_sans_mono,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_DejaVuSansMono,
	),
	BITSTREAM_VERA(
		key            = "bitstream_vera",
		titleResId     = R.string.font_bitstream_vera,
		fontRes        = R.font.bitstream_vera_sans,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_BitstreamVera,
	),
	OPEN_DYSLEXIC(
		key            = "open_dyslexic",
		titleResId     = R.string.font_open_dyslexic,
		fontRes        = R.font.open_dyslexic_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_OpenDyslexic,
	),
	JETBRAINS_MONO(
		key            = "jetbrains_mono",
		titleResId     = R.string.font_jetbrains_mono,
		fontRes        = R.font.jetbrains_mono_regular,
		themeOverlayRes = R.style.ThemeOverlay_Kotatsu_Font_JetBrainsMono,
	),
	;

	companion object {
		fun fromKey(key: String?): AppFont = entries.firstOrNull { it.key == key } ?: APP_DEFAULT
	}
}
