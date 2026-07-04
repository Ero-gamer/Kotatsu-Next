package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.TABLE_COLOR_FILTER_PROFILES

/**
 * A saved snapshot of [org.koitharu.kotatsu.reader.domain.ReaderColorFilter].
 *
 * [mangaId] == null means this is a GLOBAL profile (managed from Reader Settings, applicable
 * to every non-locked manga at once). [mangaId] != null means it's a per-series saved profile,
 * scoped to that one manga's own list (max 10 enforced in [org.koitharu.kotatsu.core.parser.MangaDataRepository],
 * not in the DB layer, so the cap can be changed without a migration).
 *
 * No FK on [mangaId] to [MangaEntity] on purpose: a null mangaId (global rows) can't reference
 * a row that doesn't exist, and Room FKs can't be conditionally applied. Orphaned per-manga
 * profile rows (manga removed from library) are cleaned up explicitly — see
 * MangaDataRepository.deleteMangaProfiles, called from the same place manga deletion happens.
 */
@Entity(tableName = TABLE_COLOR_FILTER_PROFILES, indices = [Index("manga_id")])
data class ColorFilterProfileEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long,
    @ColumnInfo(name = "manga_id") val mangaId: Long?,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "cf_brightness") val cfBrightness: Float,
    @ColumnInfo(name = "cf_contrast") val cfContrast: Float,
    @ColumnInfo(name = "cf_sharpening") val cfSharpening: Float,
    @ColumnInfo(name = "cf_saturation") val cfSaturation: Float,
    @ColumnInfo(name = "cf_vibrance") val cfVibrance: Float,
    @ColumnInfo(name = "cf_invert") val cfInvert: Boolean,
    @ColumnInfo(name = "cf_grayscale") val cfGrayscale: Boolean,
    @ColumnInfo(name = "cf_book") val cfBookEffect: Boolean,
)
