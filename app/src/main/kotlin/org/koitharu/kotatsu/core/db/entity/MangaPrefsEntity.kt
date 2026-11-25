package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.core.db.TABLE_PREFERENCES

@Serializable // backup: MANGA_PREFS
@Entity(
    tableName = TABLE_PREFERENCES,
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["manga_id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MangaPrefsEntity(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "manga_id")
    val mangaId: Long,
    @ColumnInfo(name = "mode") val mode: Int,
    @ColumnInfo(name = "cf_brightness") val cfBrightness: Float,
    @ColumnInfo(name = "cf_contrast") val cfContrast: Float,
    /** Legacy (pre-v35) shared sharpen intensity. Migrated to the per-filter columns below; always written as 0. */
    @ColumnInfo(name = "cf_sharpening", defaultValue = "0") val cfSharpening: Float,
    @ColumnInfo(name = "cf_vibrance", defaultValue = "0") val cfSaturation: Float,
    @ColumnInfo(name = "cf_vibrance2", defaultValue = "0") val cfVibrance: Float,
    @ColumnInfo(name = "cf_invert") val cfInvert: Boolean,
    @ColumnInfo(name = "cf_grayscale") val cfGrayscale: Boolean,
    @ColumnInfo(name = "cf_book") val cfBookEffect: Boolean,
    @ColumnInfo(name = "cf_denoise", defaultValue = "0") val cfDenoise: Float,
    @ColumnInfo(name = "cf_dither", defaultValue = "0") val cfDither: Float,
    @ColumnInfo(name = "cf_grain", defaultValue = "0") val cfGrain: Float,
    /** Legacy (pre-v35) sharpen-mode selector (0 off, 1 RCAS+USM, 2 adaptive). Migrated; always written as 0. */
    @ColumnInfo(name = "cf_sharpen_mode", defaultValue = "0") val cfSharpenMode: Int,
    /** Legacy (pre-v36) mislabeled sharpen intensity. Migrated to [cfRcas]; always written as 0. */
    @ColumnInfo(name = "cf_rcas_usm", defaultValue = "0") val cfRcasUsm: Float,
    /** Legacy (pre-v36) mislabeled sharpen intensity. Migrated to [cfAdaptiveSharpen]; always written as 0. */
    @ColumnInfo(name = "cf_adaptive_smoothstep", defaultValue = "0") val cfAdaptiveSmoothstep: Float,
    /** Legacy (pre-v36) mislabeled sharpen intensity. Migrated to [cfAdaptiveSharpen]; always written as 0. */
    @ColumnInfo(name = "cf_adaptive_sigmoid", defaultValue = "0") val cfAdaptiveSigmoid: Float,
    /** Sharpen intensity, real AMD FidelityFX RCAS. */
    @ColumnInfo(name = "cf_rcas", defaultValue = "0") val cfRcas: Float,
    /** Sharpen intensity, real bacondither Adaptive-Sharpen. */
    @ColumnInfo(name = "cf_adaptive_sharpen", defaultValue = "0") val cfAdaptiveSharpen: Float,
    /** Deband intensity — f3kdb-documented "square" mode, independent implementation (see
     *  `deband.frag`'s doc comment; not GPL-encumbered). */
    @ColumnInfo(name = "cf_deband", defaultValue = "0") val cfDeband: Float,
    /** Legacy: from an early build that had Catmull-Rom/B-Spline as decode-time filters. Never read; always 0. */
    @ColumnInfo(name = "cf_catmull_rom", defaultValue = "0") val cfCatmullRom: Float,
    /** Legacy, see [cfCatmullRom]. */
    @ColumnInfo(name = "cf_bspline", defaultValue = "0") val cfBSpline: Float,
    @ColumnInfo(name = "cf_line_darken", defaultValue = "0") val cfLineDarken: Boolean,
    /** Per-manga master switch: filters stay saved but are not applied while true. */
    @ColumnInfo(name = "cf_disabled", defaultValue = "0") val cfDisabled: Boolean,
    /** Auto contrast: per-page levels stretch instead of the manual contrast slider. */
    @ColumnInfo(name = "cf_auto_contrast", defaultValue = "0") val cfAutoContrast: Boolean = false,
    /** Auto brightness: per-page exposure gain instead of the manual brightness slider. */
    @ColumnInfo(name = "cf_auto_brightness", defaultValue = "0") val cfAutoBrightness: Boolean = false,
    /** Auto saturation: per-page saturation boost (never below 1) instead of the manual saturation slider. */
    @ColumnInfo(name = "cf_auto_saturation", defaultValue = "0") val cfAutoSaturation: Boolean = false,
    /** Color overlay (red, green, blue 0..1 and its opacity); inactive while [cfOverlayA] is 0. */
    @ColumnInfo(name = "cf_overlay_r", defaultValue = "0") val cfOverlayR: Float = 0f,
    @ColumnInfo(name = "cf_overlay_g", defaultValue = "0") val cfOverlayG: Float = 0f,
    @ColumnInfo(name = "cf_overlay_b", defaultValue = "0") val cfOverlayB: Float = 0f,
    @ColumnInfo(name = "cf_overlay_a", defaultValue = "0") val cfOverlayA: Float = 0f,
    /** Binarize (local-threshold black & white) strength. */
    @ColumnInfo(name = "cf_binarize", defaultValue = "0") val cfBinarize: Float = 0f,
    /** Per-manga border crop for paged readers / webtoon: 0 = follow the global setting, 1 = on, 2 = off. */
    @ColumnInfo(name = "crop_pages", defaultValue = "0") val cropPages: Int = 0,
    @ColumnInfo(name = "crop_webtoon", defaultValue = "0") val cropWebtoon: Int = 0,
    @ColumnInfo(name = "title_override") val titleOverride: String?,
    @ColumnInfo(name = "cover_override") val coverUrlOverride: String?,
    @ColumnInfo(name = "content_rating_override") val contentRatingOverride: String?,
)
