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
    @ColumnInfo(name = "cf_sharpening", defaultValue = "0") val cfSharpening: Float,
    /** Uniform saturation (ColorMatrix). Column name kept as cf_vibrance for DB compatibility. */
    @ColumnInfo(name = "cf_vibrance", defaultValue = "0") val cfSaturation: Float,
    /** GLSL vibrance (selective saturation boost, GPU bitmap pass). */
    @ColumnInfo(name = "cf_vibrance2", defaultValue = "0") val cfVibrance: Float,
    @ColumnInfo(name = "cf_invert") val cfInvert: Boolean,
    @ColumnInfo(name = "cf_grayscale") val cfGrayscale: Boolean,
    @ColumnInfo(name = "cf_book") val cfBookEffect: Boolean,
    @ColumnInfo(name = "title_override") val titleOverride: String?,
    @ColumnInfo(name = "cover_override") val coverUrlOverride: String?,
    @ColumnInfo(name = "content_rating_override") val contentRatingOverride: String?,
)
