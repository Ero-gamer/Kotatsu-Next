package org.koitharu.kotatsu.reader.ui.colorfilter

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

/**
 * Groups the color-filter screen's controls into named categories (Item 10 of the color-filter
 * work: "category grouping, with better names than the raw filter list").
 */
enum class FilterCategory(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
) {
    COLOR_AND_TONE(R.string.filter_category_color_tone, R.string.filter_category_color_tone_subtitle),
    CLARITY(R.string.filter_category_clarity, R.string.filter_category_clarity_subtitle),
    CLEANUP(R.string.filter_category_cleanup, R.string.filter_category_cleanup_subtitle),
}

/**
 * Metadata backing each filter's (i) info dialog: a brief explanation, best-use-cases, a few
 * named "stat" axes rated out of 10, and caveats. Ratings and stats are this project's own
 * product judgment for helping someone choose between filters — NOT a benchmarked or
 * empirically-measured claim, and only meaningful when comparing filters WITHIN the same
 * [category] (a Cleanup filter's "8/10 strength" and a Clarity filter's "8/10 strength" are not
 * on a shared scale — see [FilterInfoDialog] which enforces this by never showing filters from
 * two categories side by side).
 */
class FilterInfo(
    val id: FilterId,
    val category: FilterCategory,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val bestForRes: Int,
    @StringRes val caveatsRes: Int,
    /** Out of 10; see the class doc — only comparable within [category]. */
    val rating: Int,
    /** Named stat axes (e.g. "Sharpness", "Detail preservation"), each out of 10. Same caveat as
     *  [rating]: this project's own product judgment, not a measured benchmark. */
    val stats: List<Pair<Int, Int>>, // (labelStringRes, valueOutOf10)
)

enum class FilterId {
    BRIGHTNESS,
    CONTRAST,
    SATURATION,
    VIBRANCE,
    INVERT,
    GRAYSCALE,
    BOOK_BACKGROUND,
    RCAS,
    ADAPTIVE_SHARPEN,
    DEBAND,
    DENOISE,
    LINE_DARKEN,
}

object FilterInfoRegistry {
    val ALL: List<FilterInfo> =
        listOf(
            FilterInfo(
                id = FilterId.BRIGHTNESS,
                category = FilterCategory.COLOR_AND_TONE,
                nameRes = R.string.brightness,
                descriptionRes = R.string.filter_info_brightness_desc,
                bestForRes = R.string.filter_info_brightness_best_for,
                caveatsRes = R.string.filter_info_brightness_caveats,
                rating = 10,
                stats = listOf(R.string.stat_predictability to 10, R.string.stat_detail_preservation to 10),
            ),
            FilterInfo(
                id = FilterId.CONTRAST,
                category = FilterCategory.COLOR_AND_TONE,
                nameRes = R.string.contrast,
                descriptionRes = R.string.filter_info_contrast_desc,
                bestForRes = R.string.filter_info_contrast_best_for,
                caveatsRes = R.string.filter_info_contrast_caveats,
                rating = 9,
                stats = listOf(R.string.stat_predictability to 9, R.string.stat_detail_preservation to 7),
            ),
            FilterInfo(
                id = FilterId.SATURATION,
                category = FilterCategory.COLOR_AND_TONE,
                nameRes = R.string.saturation,
                descriptionRes = R.string.filter_info_saturation_desc,
                bestForRes = R.string.filter_info_saturation_best_for,
                caveatsRes = R.string.filter_info_saturation_caveats,
                rating = 7,
                stats = listOf(R.string.stat_predictability to 10, R.string.stat_detail_preservation to 10),
            ),
            FilterInfo(
                id = FilterId.VIBRANCE,
                category = FilterCategory.COLOR_AND_TONE,
                nameRes = R.string.vibrance,
                descriptionRes = R.string.filter_info_vibrance_desc,
                bestForRes = R.string.filter_info_vibrance_best_for,
                caveatsRes = R.string.filter_info_vibrance_caveats,
                rating = 8,
                stats = listOf(R.string.stat_predictability to 8, R.string.stat_detail_preservation to 10),
            ),
            FilterInfo(
                id = FilterId.INVERT,
                category = FilterCategory.COLOR_AND_TONE,
                nameRes = R.string.invert_colors,
                descriptionRes = R.string.filter_info_invert_desc,
                bestForRes = R.string.filter_info_invert_best_for,
                caveatsRes = R.string.filter_info_invert_caveats,
                rating = 6,
                stats = listOf(R.string.stat_predictability to 10, R.string.stat_detail_preservation to 10),
            ),
            FilterInfo(
                id = FilterId.GRAYSCALE,
                category = FilterCategory.COLOR_AND_TONE,
                nameRes = R.string.grayscale,
                descriptionRes = R.string.filter_info_grayscale_desc,
                bestForRes = R.string.filter_info_grayscale_best_for,
                caveatsRes = R.string.filter_info_grayscale_caveats,
                rating = 5,
                stats = listOf(R.string.stat_predictability to 10, R.string.stat_detail_preservation to 10),
            ),
            FilterInfo(
                id = FilterId.BOOK_BACKGROUND,
                category = FilterCategory.COLOR_AND_TONE,
                nameRes = R.string.book_effect,
                descriptionRes = R.string.filter_info_book_desc,
                bestForRes = R.string.filter_info_book_best_for,
                caveatsRes = R.string.filter_info_book_caveats,
                rating = 6,
                stats = listOf(R.string.stat_predictability to 10, R.string.stat_detail_preservation to 10),
            ),
            FilterInfo(
                id = FilterId.RCAS,
                category = FilterCategory.CLARITY,
                nameRes = R.string.sharpen_rcas,
                descriptionRes = R.string.filter_info_rcas_desc,
                bestForRes = R.string.filter_info_rcas_best_for,
                caveatsRes = R.string.filter_info_rcas_caveats,
                rating = 9,
                stats =
                listOf(
                    R.string.stat_sharpness to 6,
                    R.string.stat_detail_preservation to 9,
                    R.string.stat_ringing_risk to 2,
                ),
            ),
            FilterInfo(
                id = FilterId.ADAPTIVE_SHARPEN,
                category = FilterCategory.CLARITY,
                nameRes = R.string.sharpen_adaptive_sharpen,
                descriptionRes = R.string.filter_info_adaptive_sharpen_desc,
                bestForRes = R.string.filter_info_adaptive_sharpen_best_for,
                caveatsRes = R.string.filter_info_adaptive_sharpen_caveats,
                rating = 7,
                stats =
                listOf(
                    R.string.stat_sharpness to 9,
                    R.string.stat_detail_preservation to 7,
                    R.string.stat_ringing_risk to 5,
                ),
            ),
            FilterInfo(
                id = FilterId.DEBAND,
                category = FilterCategory.CLEANUP,
                nameRes = R.string.deband,
                descriptionRes = R.string.filter_info_deband_desc,
                bestForRes = R.string.filter_info_deband_best_for,
                caveatsRes = R.string.filter_info_deband_caveats,
                rating = 8,
                stats = listOf(R.string.stat_detail_preservation to 8, R.string.stat_edge_safety to 7),
            ),
            FilterInfo(
                id = FilterId.DENOISE,
                category = FilterCategory.CLEANUP,
                nameRes = R.string.denoise,
                descriptionRes = R.string.filter_info_denoise_desc,
                bestForRes = R.string.filter_info_denoise_best_for,
                caveatsRes = R.string.filter_info_denoise_caveats,
                rating = 9,
                stats = listOf(R.string.stat_detail_preservation to 9, R.string.stat_edge_safety to 9),
            ),
            FilterInfo(
                id = FilterId.LINE_DARKEN,
                category = FilterCategory.CLEANUP,
                nameRes = R.string.line_darkening,
                descriptionRes = R.string.filter_info_line_darken_desc,
                bestForRes = R.string.filter_info_line_darken_best_for,
                caveatsRes = R.string.filter_info_line_darken_caveats,
                rating = 5,
                stats = listOf(R.string.stat_detail_preservation to 6, R.string.stat_edge_safety to 5),
            ),
        )

    fun byId(id: FilterId): FilterInfo = ALL.first { it.id == id }
}
