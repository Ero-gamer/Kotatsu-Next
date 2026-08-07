package org.koitharu.kotatsu.main.ui.nav

import android.content.Context
import android.util.TypedValue
import androidx.appcompat.R as appcompatR
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.R as materialR

/**
 * Builds a Material3 [ColorScheme] by resolving the host theme's colour attributes
 * (?attr/colorPrimary etc). Reading them from the host Context's theme means the user's chosen
 * colour scheme - Monet, Expressive, AMOLED, any of the named ones - carries into the Compose
 * subtree without reimplementing that resolution here.
 */
fun composeColorSchemeFromTheme(context: Context, isDark: Boolean): ColorScheme {
	fun c(attr: Int, fallback: Color): Color {
		val tv = TypedValue()
		val resolved = context.theme.resolveAttribute(attr, tv, true)
		if (!resolved) return fallback
		val argb = if (tv.resourceId != 0) {
			ResourcesCompat.getColor(context.resources, tv.resourceId, context.theme)
		} else {
			tv.data
		}
		return Color(argb)
	}

	val base = if (isDark) darkColorScheme() else lightColorScheme()
	return base.copy(
		primary = c(appcompatR.attr.colorPrimary, base.primary),
		onPrimary = c(materialR.attr.colorOnPrimary, base.onPrimary),
		primaryContainer = c(materialR.attr.colorPrimaryContainer, base.primaryContainer),
		onPrimaryContainer = c(materialR.attr.colorOnPrimaryContainer, base.onPrimaryContainer),
		secondary = c(materialR.attr.colorSecondary, base.secondary),
		onSecondary = c(materialR.attr.colorOnSecondary, base.onSecondary),
		secondaryContainer = c(materialR.attr.colorSecondaryContainer, base.secondaryContainer),
		onSecondaryContainer = c(materialR.attr.colorOnSecondaryContainer, base.onSecondaryContainer),
		tertiary = c(materialR.attr.colorTertiary, base.tertiary),
		onTertiary = c(materialR.attr.colorOnTertiary, base.onTertiary),
		surface = c(materialR.attr.colorSurface, base.surface),
		onSurface = c(materialR.attr.colorOnSurface, base.onSurface),
		surfaceContainer = c(materialR.attr.colorSurfaceContainer, base.surfaceContainer),
		surfaceContainerHigh = c(materialR.attr.colorSurfaceContainerHigh, base.surfaceContainerHigh),
		surfaceContainerHighest = c(materialR.attr.colorSurfaceContainerHighest, base.surfaceContainerHighest),
		onSurfaceVariant = c(materialR.attr.colorOnSurfaceVariant, base.onSurfaceVariant),
		outline = c(materialR.attr.colorOutline, base.outline),
		outlineVariant = c(materialR.attr.colorOutlineVariant, base.outlineVariant),
	)
}
