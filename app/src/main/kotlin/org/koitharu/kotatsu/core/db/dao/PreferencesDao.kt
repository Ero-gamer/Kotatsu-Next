package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity

@Dao
abstract class PreferencesDao {

    @Query("SELECT * FROM preferences WHERE manga_id = :mangaId")
    abstract suspend fun find(mangaId: Long): MangaPrefsEntity?

    @Query("SELECT * FROM preferences WHERE manga_id = :mangaId")
    abstract fun observe(mangaId: Long): Flow<MangaPrefsEntity?>

    @Query("SELECT * FROM preferences WHERE title_override IS NOT NULL OR cover_override IS NOT NULL OR content_rating_override IS NOT NULL")
    abstract suspend fun getOverrides(): List<MangaPrefsEntity>

    @Query("UPDATE preferences SET cf_brightness=0, cf_contrast=0, cf_invert=0, cf_grayscale=0, cf_sharpening=0, cf_vibrance=0, cf_vibrance2=0, cf_denoise=0, cf_dither=0, cf_grain=0, cf_book=0 WHERE is_locked=0")
    abstract suspend fun resetColorFilters()

    @Query("UPDATE preferences SET cf_brightness=:brightness, cf_contrast=:contrast, cf_sharpening=:sharpening, cf_vibrance=:saturation, cf_vibrance2=:vibrance, cf_denoise=:denoise, cf_dither=:dither, cf_grain=:grain, cf_invert=:invert, cf_grayscale=:grayscale, cf_book=:book WHERE is_locked=0")
    abstract suspend fun applyToAllUnlocked(
        brightness: Float, contrast: Float, sharpening: Float,
        saturation: Float, vibrance: Float, denoise: Float, dither: Float, grain: Float,
        invert: Boolean, grayscale: Boolean, book: Boolean,
    )

    @Query("UPDATE preferences SET is_locked=:locked WHERE manga_id=:mangaId")
    abstract suspend fun setLocked(mangaId: Long, locked: Boolean)

    @Query("SELECT is_locked FROM preferences WHERE manga_id=:mangaId")
    abstract suspend fun isLocked(mangaId: Long): Boolean?

    @Upsert
    abstract suspend fun upsert(pref: MangaPrefsEntity)
}
