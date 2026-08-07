package org.koitharu.kotatsu.main.ui.nav

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.koitharu.kotatsu.R

/**
 * Google Sans as a variable family. Referencing the .ttf on its own renders the default (Regular)
 * instance whatever weight is asked for, so each weight has to pin the `wght` axis explicitly.
 * The face carries GRAD / opsz / wght only - there is no rounding axis to set.
 */
@OptIn(ExperimentalTextApi::class)
private val GoogleSans: FontFamily
	@Composable
	get() = remember {
		FontFamily(
			Font(R.font.google_sans, weight = FontWeight.Normal, variationSettings = weightOf(400)),
			Font(R.font.google_sans, weight = FontWeight.Medium, variationSettings = weightOf(500)),
			Font(R.font.google_sans, weight = FontWeight.SemiBold, variationSettings = weightOf(600)),
			Font(R.font.google_sans, weight = FontWeight.Bold, variationSettings = weightOf(700)),
		)
	}

private fun weightOf(weight: Int) = FontVariation.Settings(FontVariation.weight(weight))

/**
 * Only the styles the floating bar actually draws with are overridden; the rest fall back to the
 * Material defaults so this stays a nav-bar concern rather than an app-wide typography change.
 */
@Composable
private fun navTypography(family: FontFamily): Typography {
	val noPadding = PlatformTextStyle(includeFontPadding = false)
	val base = Typography()
	return base.copy(
		labelLarge = base.labelLarge.copy(
			fontFamily = family,
			fontWeight = FontWeight.Bold,
			platformStyle = noPadding,
		),
		labelMedium = base.labelMedium.copy(
			fontFamily = family,
			fontWeight = FontWeight.Bold,
			fontSize = 14.sp,
			lineHeight = 20.sp,
			platformStyle = noPadding,
		),
		labelSmall = base.labelSmall.copy(
			fontFamily = family,
			fontWeight = FontWeight.Medium,
			platformStyle = noPadding,
		),
	)
}

/**
 * MaterialTheme wrapper for Compose subtrees hosted inside the existing View hierarchy, so they
 * inherit the colours of whichever theme the user picked.
 */
@Composable
fun FloatingNavTheme(content: @Composable () -> Unit) {
	val context = LocalContext.current
	val isDark = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
		Configuration.UI_MODE_NIGHT_YES
	val scheme = remember(context, isDark) { composeColorSchemeFromTheme(context, isDark) }
	MaterialTheme(
		colorScheme = scheme,
		typography = navTypography(GoogleSans),
		content = content,
	)
}
