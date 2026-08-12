package org.koitharu.kotatsu.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.util.find

@Keep
enum class ColorScheme(
	@StyleRes val styleResId: Int,
	@StringRes val titleResId: Int,
) {

	DEFAULT(R.style.ThemeOverlay_Kotatsu_Totoro, R.string.theme_name_totoro),
	MONET(R.style.ThemeOverlay_Kotatsu_Monet, R.string.theme_name_dynamic),
	EXPRESSIVE(R.style.ThemeOverlay_Kotatsu_Expressive, R.string.theme_name_expressive),
	MIKU(R.style.ThemeOverlay_Kotatsu_Miku, R.string.theme_name_miku),
	RENA(R.style.ThemeOverlay_Kotatsu_Asuka, R.string.theme_name_asuka),
	FROG(R.style.ThemeOverlay_Kotatsu_Mion, R.string.theme_name_mion),
	BLUEBERRY(R.style.ThemeOverlay_Kotatsu_Rikka, R.string.theme_name_rikka),
	SAKURA(R.style.ThemeOverlay_Kotatsu_Sakura, R.string.theme_name_sakura),
	MAMIMI(R.style.ThemeOverlay_Kotatsu_Mamimi, R.string.theme_name_mamimi),
	KANADE(R.style.ThemeOverlay_Kotatsu_Kanade, R.string.theme_name_kanade),
	ITSUKA(R.style.ThemeOverlay_Kotatsu_Itsuka, R.string.theme_name_itsuka),

	// ── Tadami themes ──
	TOKYO_NIGHT(R.style.ThemeOverlay_Kotatsu_TokyoNight, R.string.theme_name_tokyo_night),
	NORD(R.style.ThemeOverlay_Kotatsu_Nord, R.string.theme_name_nord),
	MIDNIGHT_DUSK(R.style.ThemeOverlay_Kotatsu_MidnightDusk, R.string.theme_name_midnight_dusk),
	GREEN_APPLE(R.style.ThemeOverlay_Kotatsu_GreenApple, R.string.theme_name_green_apple),
	LAVENDER(R.style.ThemeOverlay_Kotatsu_Lavender, R.string.theme_name_lavender),
	STRAWBERRY(R.style.ThemeOverlay_Kotatsu_Strawberry, R.string.theme_name_strawberry),
	TAKO(R.style.ThemeOverlay_Kotatsu_Tako, R.string.theme_name_tako),
	TEAL_TURQUOISE(R.style.ThemeOverlay_Kotatsu_TealTurqoise, R.string.theme_name_teal_turquoise),
	TIDAL_WAVE(R.style.ThemeOverlay_Kotatsu_TidalWave, R.string.theme_name_tidal_wave),
	YIN_YANG(R.style.ThemeOverlay_Kotatsu_YinYang, R.string.theme_name_yin_yang),
	YOTSUBA(R.style.ThemeOverlay_Kotatsu_Yotsuba, R.string.theme_name_yotsuba),
	MONOCHROME(R.style.ThemeOverlay_Kotatsu_Monochrome, R.string.theme_name_monochrome),
	MOCHA(R.style.ThemeOverlay_Kotatsu_Mocha, R.string.theme_name_mocha),
	COTTON_CANDY(R.style.ThemeOverlay_Kotatsu_CottonCandy, R.string.theme_name_cotton_candy),
	SAPPHIRE(R.style.ThemeOverlay_Kotatsu_Sapphire, R.string.theme_name_sapphire),
	VOID_RED(R.style.ThemeOverlay_Kotatsu_VoidRed, R.string.theme_name_void_red),
	DOOM(R.style.ThemeOverlay_Kotatsu_Doom, R.string.theme_name_doom),
	EVENT_HORIZON(R.style.ThemeOverlay_Kotatsu_EventHorizon, R.string.theme_name_event_horizon),
	NEBULA_TIDE(R.style.ThemeOverlay_Kotatsu_NebulaTide, R.string.theme_name_nebula_tide),
	ONYX_GOLD(R.style.ThemeOverlay_Kotatsu_OnyxGold, R.string.theme_name_onyx_gold),
	SAKURA_NOIR(R.style.ThemeOverlay_Kotatsu_SakuraNoir, R.string.theme_name_sakura_noir),
	CLOUDFLARE(R.style.ThemeOverlay_Kotatsu_Cloudflare, R.string.theme_name_cloudflare),
	MATRIX(R.style.ThemeOverlay_Kotatsu_Matrix, R.string.theme_name_matrix),
	;

	companion object {

		val default: ColorScheme
			get() = if (DynamicColors.isDynamicColorAvailable()) {
				MONET
			} else {
				DEFAULT
			}

		fun getAvailableList(): List<ColorScheme> {
			val list = ColorScheme.entries.toMutableList()
			if (!DynamicColors.isDynamicColorAvailable()) {
				list.remove(MONET)
				list.remove(EXPRESSIVE)
			}
			return list
		}

		fun safeValueOf(name: String): ColorScheme? {
			return ColorScheme.entries.find(name)
		}
	}
}
