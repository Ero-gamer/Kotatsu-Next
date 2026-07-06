package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.entity.ColorFilterProfileEntity

@Dao
abstract class ColorFilterProfilesDao {
    @Query("SELECT * FROM color_filter_profiles WHERE manga_id IS :mangaId ORDER BY sort_order")
    abstract suspend fun list(mangaId: Long?): List<ColorFilterProfileEntity>

    @Query("SELECT * FROM color_filter_profiles WHERE manga_id IS :mangaId ORDER BY sort_order")
    abstract fun observe(mangaId: Long?): Flow<List<ColorFilterProfileEntity>>

    @Query("SELECT COUNT(*) FROM color_filter_profiles WHERE manga_id IS :mangaId")
    abstract suspend fun count(mangaId: Long?): Int

    @Query("SELECT MAX(sort_order) FROM color_filter_profiles WHERE manga_id IS :mangaId")
    abstract suspend fun maxSortOrder(mangaId: Long?): Int?

    @Insert abstract suspend fun insert(entity: ColorFilterProfileEntity): Long
    @Update abstract suspend fun update(entity: ColorFilterProfileEntity)

    @Query("DELETE FROM color_filter_profiles WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("DELETE FROM color_filter_profiles WHERE manga_id = :mangaId")
    abstract suspend fun deleteAllForManga(mangaId: Long)
}
