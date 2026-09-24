package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.TABLE_PREFERENCES

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
    @ColumnInfo(name = "cf_rcas_usm", defaultValue = "0") val cfRcasUsm: Float,
    @ColumnInfo(name = "cf_adaptive_smoothstep", defaultValue = "0") val cfAdaptiveSmoothstep: Float,
    @ColumnInfo(name = "cf_adaptive_sigmoid", defaultValue = "0") val cfAdaptiveSigmoid: Float,
    /** Legacy: from an early build that had Catmull-Rom/B-Spline as decode-time filters. Never read; always 0. */
    @ColumnInfo(name = "cf_catmull_rom", defaultValue = "0") val cfCatmullRom: Float,
    /** Legacy, see [cfCatmullRom]. */
    @ColumnInfo(name = "cf_bspline", defaultValue = "0") val cfBSpline: Float,
    @ColumnInfo(name = "cf_line_darken", defaultValue = "0") val cfLineDarken: Boolean,
    /** Per-manga master switch: filters stay saved but are not applied while true. */
    @ColumnInfo(name = "cf_disabled", defaultValue = "0") val cfDisabled: Boolean,
    @ColumnInfo(name = "title_override") val titleOverride: String?,
    @ColumnInfo(name = "cover_override") val coverUrlOverride: String?,
    @ColumnInfo(name = "content_rating_override") val contentRatingOverride: String?,
)
