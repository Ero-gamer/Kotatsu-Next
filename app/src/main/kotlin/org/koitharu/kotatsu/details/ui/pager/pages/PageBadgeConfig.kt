package org.koitharu.kotatsu.details.ui.pager.pages

import org.koitharu.kotatsu.core.image.ImageFormat
import org.koitharu.kotatsu.core.prefs.AppSettings

/** What the page-preview badges should show (all three come from settings). */
data class PageBadgeConfig(
    val showFormat: Boolean,
    val showAnimated: Boolean,
    val hideCommon: Boolean,
) {

    companion object {

        fun from(settings: AppSettings) = PageBadgeConfig(
            showFormat = settings.isPageFormatBadgeEnabled,
            showAnimated = settings.isPageAnimatedBadgeEnabled,
            hideCommon = settings.isPageBadgeHideCommon,
        )
    }
}

internal val ImageFormat.badgeLabel: String
    get() = when (this) {
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.PNG -> "PNG"
        ImageFormat.WEBP -> "WEBP"
        ImageFormat.GIF -> "GIF"
        ImageFormat.AVIF -> "AVIF"
        ImageFormat.JXL -> "JXL"
        ImageFormat.BMP -> "BMP"
        ImageFormat.UNKNOWN -> ""
    }

internal val ImageFormat.isCommon: Boolean
    get() = this == ImageFormat.JPEG || this == ImageFormat.PNG
