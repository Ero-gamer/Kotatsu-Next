package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.TABLE_COLOR_FILTER_PROFILES

@Entity(tableName = TABLE_COLOR_FILTER_PROFILES, indices = [Index("manga_id")])
data class ColorFilterProfileEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "manga_id") val mangaId: Long?,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "cf_brightness") val cfBrightness: Float,
    @ColumnInfo(name = "cf_contrast") val cfContrast: Float,
    @ColumnInfo(name = "cf_sharpening") val cfSharpening: Float,
    @ColumnInfo(name = "cf_saturation") val cfSaturation: Float,
    @ColumnInfo(name = "cf_vibrance") val cfVibrance: Float,
    @ColumnInfo(name = "cf_denoise", defaultValue = "0") val cfDenoise: Float,
    @ColumnInfo(name = "cf_dither", defaultValue = "0") val cfDither: Float,
    @ColumnInfo(name = "cf_grain", defaultValue = "0") val cfGrain: Float,
    @ColumnInfo(name = "cf_invert") val cfInvert: Boolean,
    @ColumnInfo(name = "cf_grayscale") val cfGrayscale: Boolean,
    @ColumnInfo(name = "cf_book") val cfBookEffect: Boolean,
)
