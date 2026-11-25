package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.db.entity.MangaWithTags

@Dao
abstract class PreferencesDao {

    @Query("SELECT * FROM preferences WHERE manga_id = :mangaId")
    abstract suspend fun find(mangaId: Long): MangaPrefsEntity?

    @Query("SELECT * FROM preferences WHERE manga_id = :mangaId")
    abstract fun observe(mangaId: Long): Flow<MangaPrefsEntity?>

    @Query("SELECT * FROM preferences WHERE title_override IS NOT NULL OR cover_override IS NOT NULL OR content_rating_override IS NOT NULL")
    abstract suspend fun getOverrides(): List<MangaPrefsEntity>

    @Query(
        "UPDATE preferences SET cf_brightness=0, cf_contrast=0, cf_invert=0, cf_grayscale=0, " +
            "cf_sharpening=0, cf_vibrance=0, cf_vibrance2=0, cf_denoise=0, cf_dither=0, cf_grain=0, " +
            "cf_sharpen_mode=0, cf_line_darken=0, cf_book=0, cf_disabled=0, cf_rcas_usm=0, " +
            "cf_adaptive_smoothstep=0, cf_adaptive_sigmoid=0, cf_catmull_rom=0, cf_bspline=0, " +
            "cf_rcas=0, cf_adaptive_sharpen=0, cf_deband=0, cf_binarize=0, cf_auto_contrast=0, cf_auto_brightness=0, cf_auto_saturation=0, cf_overlay_r=0, cf_overlay_g=0, cf_overlay_b=0, cf_overlay_a=0",
    )
    abstract suspend fun resetColorFilters()

    @Upsert
    abstract suspend fun upsert(pref: MangaPrefsEntity)

    @Transaction
    @Query(
        "SELECT * FROM manga JOIN preferences ON preferences.manga_id = manga.manga_id ORDER BY manga.manga_id LIMIT :limit OFFSET :offset",
    )
    protected abstract suspend fun findAllWithManga(offset: Int, limit: Int): Map<MangaWithTags, List<MangaPrefsEntity>>

    fun dump(): Flow<Pair<MangaWithTags, MangaPrefsEntity>> = flow {
        val window = 10
        var offset = 0
        while (currentCoroutineContext().isActive) {
            val list = findAllWithManga(offset, window)
            if (list.isEmpty()) {
                break
            }
            offset += window
            list.forEach { (manga, prefs) -> prefs.forEach { emit(manga to it) } }
        }
    }
}
