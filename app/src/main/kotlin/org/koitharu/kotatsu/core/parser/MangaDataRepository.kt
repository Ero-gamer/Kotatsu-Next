package org.koitharu.kotatsu.core.parser

import androidx.collection.LongObjectMap
import androidx.collection.MutableLongObjectMap
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.TABLE_PREFERENCES
import org.koitharu.kotatsu.core.db.entity.ContentRating
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaChapter
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.findSameChapter
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.reader.domain.CropOverrides
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class MangaDataRepository @Inject constructor(
    private val db: MangaDatabase,
    private val resolverProvider: Provider<MangaLinkResolver>,
    private val appShortcutManagerProvider: Provider<AppShortcutManager>,
) {

    suspend fun saveReaderMode(manga: Manga, mode: ReaderMode) {
        db.withTransaction {
            storeManga(manga, replaceExisting = false)
            val entity = db.getPreferencesDao().find(manga.id) ?: newEntity(manga.id)
            db.getPreferencesDao().upsert(entity.copy(mode = mode.id))
        }
    }

    suspend fun saveColorFilter(manga: Manga, colorFilter: ReaderColorFilter?) {
        db.withTransaction {
            storeManga(manga, replaceExisting = false)
            val entity = db.getPreferencesDao().find(manga.id) ?: newEntity(manga.id)
            db.getPreferencesDao().upsert(
                entity.copy(
                    cfBrightness = colorFilter?.brightness ?: 0f,
                    cfContrast = colorFilter?.contrast ?: 0f,
                    cfSharpening = 0f, // legacy column, superseded by the per-filter columns
                    cfSaturation = colorFilter?.saturation ?: 0f,
                    cfVibrance = colorFilter?.vibrance ?: 0f,
                    cfDenoise = colorFilter?.denoise ?: 0f,
                    cfDither = colorFilter?.dither ?: 0f,
                    cfGrain = colorFilter?.grain ?: 0f,
                    cfSharpenMode = 0, // legacy column, superseded by the per-filter columns
                    cfRcasUsm = 0f, // legacy column, superseded by cfRcas
                    cfAdaptiveSmoothstep = 0f, // legacy column, superseded by cfAdaptiveSharpen
                    cfAdaptiveSigmoid = 0f, // legacy column, superseded by cfAdaptiveSharpen
                    cfRcas = colorFilter?.rcas ?: 0f,
                    cfAdaptiveSharpen = colorFilter?.adaptiveSharpen ?: 0f,
                    cfDeband = colorFilter?.deband ?: 0f,
                    cfBinarize = colorFilter?.binarize ?: 0f,
                    cfCatmullRom = 0f, // legacy column (early resampler filters), never read
                    cfBSpline = 0f, // legacy column (early resampler filters), never read
                    cfLineDarken = colorFilter?.isLineDarkenEnabled == true,
                    cfAutoContrast = colorFilter?.isAutoContrast == true,
                    cfAutoBrightness = colorFilter?.isAutoBrightness == true,
                    cfAutoSaturation = colorFilter?.isAutoSaturation == true,
                    cfOverlayR = colorFilter?.overlayRed ?: 0f,
                    cfOverlayG = colorFilter?.overlayGreen ?: 0f,
                    cfOverlayB = colorFilter?.overlayBlue ?: 0f,
                    cfOverlayA = colorFilter?.overlayAlpha ?: 0f,
                    cfInvert = colorFilter?.isInverted == true,
                    cfGrayscale = colorFilter?.isGrayscale == true,
                    cfBookEffect = colorFilter?.isBookBackground == true,
                ),
            )
        }
    }

    suspend fun resetColorFilters() {
        db.getPreferencesDao().resetColorFilters()
    }

    suspend fun getReaderMode(mangaId: Long): ReaderMode? = db.getPreferencesDao().find(mangaId)?.let { ReaderMode.valueOf(it.mode) }

    suspend fun getColorFilter(mangaId: Long): ReaderColorFilter? = db.getPreferencesDao().find(mangaId)?.getColorFilterOrNull()

    suspend fun getOverride(mangaId: Long): MangaOverride? = db.getPreferencesDao().find(mangaId)?.getOverrideOrNull()

    suspend fun getOverrides(): LongObjectMap<MangaOverride> {
        val entities = db.getPreferencesDao().getOverrides()
        val map = MutableLongObjectMap<MangaOverride>(entities.size)
        for (entity in entities) {
            map[entity.mangaId] = entity.getOverrideOrNull() ?: continue
        }
        return map
    }

    suspend fun setOverride(manga: Manga, override: MangaOverride?) {
        db.withTransaction {
            storeManga(manga, replaceExisting = false)
            val dao = db.getPreferencesDao()
            val entity = dao.find(manga.id) ?: newEntity(manga.id)
            dao.upsert(
                entity.copy(
                    titleOverride = override?.title?.nullIfEmpty(),
                    coverUrlOverride = override?.coverUrl?.nullIfEmpty(),
                    contentRatingOverride = override?.contentRating?.name,
                ),
            )
        }
    }

    fun observeColorFilter(mangaId: Long): Flow<ReaderColorFilter?> = db.getPreferencesDao().observe(mangaId)
        .map { it?.getColorFilterOrNull() }
        .distinctUntilChanged()

    fun observeCropOverrides(mangaId: Long): Flow<CropOverrides> = db.getPreferencesDao().observe(mangaId)
        .map { it?.let { e -> CropOverrides(CropOverrides.decode(e.cropPages), CropOverrides.decode(e.cropWebtoon)) } ?: CropOverrides.NONE }
        .distinctUntilChanged()

    /** Sets the border crop of one reading style for [manga]; `null` goes back to the global setting. */
    suspend fun setCropOverride(manga: Manga, isWebtoon: Boolean, isEnabled: Boolean?) {
        db.withTransaction {
            storeManga(manga, replaceExisting = false)
            val dao = db.getPreferencesDao()
            val entity = dao.find(manga.id) ?: newEntity(manga.id)
            val code = CropOverrides.encode(isEnabled)
            dao.upsert(if (isWebtoon) entity.copy(cropWebtoon = code) else entity.copy(cropPages = code))
        }
    }

    /** Whether all image filters are switched off for [mangaId]. Saved filter values are untouched. */
    fun observeColorFilterDisabled(mangaId: Long): Flow<Boolean> = db.getPreferencesDao().observe(mangaId)
        .map { it?.cfDisabled == true }
        .distinctUntilChanged()

    suspend fun setColorFilterDisabled(manga: Manga, isDisabled: Boolean) {
        db.withTransaction {
            storeManga(manga, replaceExisting = false)
            val dao = db.getPreferencesDao()
            val entity = dao.find(manga.id) ?: newEntity(manga.id)
            dao.upsert(entity.copy(cfDisabled = isDisabled))
        }
    }

    suspend fun findMangaById(mangaId: Long, withChapters: Boolean): Manga? {
        val chapters = if (withChapters) {
            db.getChaptersDao().findAll(mangaId).takeUnless { it.isEmpty() }
        } else {
            null
        }
        return db.getMangaDao().find(mangaId)?.toManga(chapters)
    }

    suspend fun findMangaByPublicUrl(publicUrl: String): Manga? = db.getMangaDao().findByPublicUrl(publicUrl)?.toManga()

    suspend fun resolveIntent(intent: MangaIntent, withChapters: Boolean): Manga? = when {
        intent.manga != null -> intent.manga.withCachedChaptersIfNeeded(withChapters)
        intent.mangaId != 0L -> findMangaById(intent.mangaId, withChapters)
        intent.uri != null -> resolverProvider.get().resolve(intent.uri).withCachedChaptersIfNeeded(withChapters)
        else -> null
    }

    suspend fun findLibraryMangaBySource(source: MangaSource): List<Manga> = db.getMangaDao().findLibraryBySource(source.name).map { it.toManga() }

    suspend fun findAllMangaBySource(source: MangaSource): List<Manga> = db.getMangaDao().findAllBySource(source.name).map { it.toManga() }

    /** Keeps the cover a source has now, if the manga is stored. Overrides are stored apart from this. */
    suspend fun updateCover(manga: Manga) {
        val coverUrl = manga.coverUrl
        if (!manga.isLocal && !coverUrl.isNullOrEmpty()) {
            db.getMangaDao().updateCover(manga.id, coverUrl, manga.largeCoverUrl)
        }
    }

    suspend fun storeManga(manga: Manga, replaceExisting: Boolean) {
        if (!replaceExisting && db.getMangaDao().find(manga.id) != null) {
            return
        }
        db.withTransaction {
            // avoid storing local manga if remote one is already stored
            val existing = if (manga.isLocal) {
                db.getMangaDao().find(manga.id)?.manga
            } else {
                null
            }
            if (existing == null || existing.source == manga.source.name) {
                val tags = manga.tags.toEntities()
                db.getTagsDao().upsert(tags)
                db.getMangaDao().upsert(manga.toEntity(), tags)
                if (!manga.isLocal) {
                    manga.chapters?.let { chapters ->
                        keepHistoryChapter(manga.id, chapters)
                        db.getChaptersDao().replaceAll(manga.id, chapters.withIndex().toEntities(manga.id))
                    }
                }
            }
        }
    }

    suspend fun updateChapters(manga: Manga) {
        val chapters = manga.chapters
        if (!chapters.isNullOrEmpty() && manga.id in db.getMangaDao()) {
            db.withTransaction {
                keepHistoryChapter(manga.id, chapters)
                db.getChaptersDao().replaceAll(manga.id, chapters.withIndex().toEntities(manga.id))
            }
        }
    }

    /**
     * The chapter as it was in the chapter list last stored for [mangaId], which may hold ids that a fresher load of
     * the source no longer has.
     */
    suspend fun findCachedChapter(mangaId: Long, chapterId: Long): MangaChapter? = db.getChaptersDao().find(mangaId, chapterId)?.toMangaChapter()

    /**
     * History only keeps a chapter id. When the source gives the history chapter a new id, the stored list is the last
     * place that still says which chapter it was, so move the history onto that chapter of [newChapters] before the
     * list is replaced. Otherwise the reading position is later guessed from a percentage, possibly in another branch.
     */
    private suspend fun keepHistoryChapter(mangaId: Long, newChapters: List<MangaChapter>) {
        val history = db.getHistoryDao().find(mangaId) ?: return
        if (newChapters.findById(history.chapterId) != null) {
            return
        }
        val previous = findCachedChapter(mangaId, history.chapterId) ?: return
        val same = newChapters.findSameChapter(previous) ?: return
        db.getHistoryDao().update(history.copy(chapterId = same.id))
    }

    suspend fun gcChaptersCache() {
        db.getChaptersDao().gc()
    }

    suspend fun findTags(source: MangaSource): Set<MangaTag> = db.getTagsDao().findTags(source.name).toMangaTags()

    suspend fun cleanupLocalManga() {
        val dao = db.getMangaDao()
        val broken = dao.findAllBySource(LocalMangaSource.name)
            .filter { x -> x.manga.url.toUri().toFileOrNull()?.exists() == false }
        if (broken.isNotEmpty()) {
            dao.delete(broken.map { it.manga })
        }
    }

    suspend fun cleanupDatabase() {
        db.withTransaction {
            gcChaptersCache()
            val idsFromShortcuts = appShortcutManagerProvider.get().getMangaShortcuts()
            db.getMangaDao().cleanup(idsFromShortcuts)
        }
    }

    fun observeOverridesTrigger(emitInitialState: Boolean) = db.invalidationTracker.createFlow(
        tables = arrayOf(TABLE_PREFERENCES),
        emitInitialState = emitInitialState,
    )

    fun observeFavoritesTrigger(emitInitialState: Boolean) = db.invalidationTracker.createFlow(
        tables = arrayOf(TABLE_FAVOURITES, TABLE_FAVOURITE_CATEGORIES),
        emitInitialState = emitInitialState,
    )

    private suspend fun Manga.withCachedChaptersIfNeeded(flag: Boolean): Manga = if (flag && !isLocal && chapters.isNullOrEmpty()) {
        val cachedChapters = db.getChaptersDao().findAll(id)
        if (cachedChapters.isEmpty()) {
            this
        } else {
            copy(chapters = cachedChapters.toMangaChapters())
        }
    } else {
        this
    }

    private fun MangaPrefsEntity.getColorFilterOrNull(): ReaderColorFilter? = if (cfBrightness != 0f || cfContrast != 0f ||
        cfSaturation != 0f || cfVibrance != 0f || cfDenoise != 0f || cfDither != 0f || cfGrain != 0f ||
        cfRcas != 0f || cfAdaptiveSharpen != 0f || cfDeband != 0f || cfBinarize != 0f ||
        cfLineDarken || cfInvert || cfGrayscale || cfBookEffect || cfAutoContrast ||
        cfAutoBrightness || cfAutoSaturation || cfOverlayR != 0f || cfOverlayG != 0f || cfOverlayB != 0f ||
        cfOverlayA != 0f
    ) {
        ReaderColorFilter(
            brightness = cfBrightness,
            contrast = cfContrast,
            saturation = cfSaturation,
            vibrance = cfVibrance,
            denoise = cfDenoise,
            dither = cfDither,
            grain = cfGrain,
            rcas = cfRcas,
            adaptiveSharpen = cfAdaptiveSharpen,
            deband = cfDeband,
            binarize = cfBinarize,
            isInverted = cfInvert,
            isGrayscale = cfGrayscale,
            isBookBackground = cfBookEffect,
            isLineDarkenEnabled = cfLineDarken,
            isAutoContrast = cfAutoContrast,
            isAutoBrightness = cfAutoBrightness,
            isAutoSaturation = cfAutoSaturation,
            overlayRed = cfOverlayR,
            overlayGreen = cfOverlayG,
            overlayBlue = cfOverlayB,
            overlayAlpha = cfOverlayA,
        )
    } else {
        null
    }

    private fun MangaPrefsEntity.getOverrideOrNull(): MangaOverride? = if (titleOverride.isNullOrEmpty() && coverUrlOverride.isNullOrEmpty() && contentRatingOverride.isNullOrEmpty()) {
        null
    } else {
        MangaOverride(
            coverUrl = coverUrlOverride?.nullIfEmpty(),
            title = titleOverride?.nullIfEmpty(),
            contentRating = ContentRating(contentRatingOverride),
        )
    }

    private fun newEntity(mangaId: Long) = MangaPrefsEntity(
        mangaId = mangaId,
        mode = -1,
        cfBrightness = ReaderColorFilter.EMPTY.brightness,
        cfContrast = ReaderColorFilter.EMPTY.contrast,
        cfSharpening = 0f,
        cfSaturation = ReaderColorFilter.EMPTY.saturation,
        cfVibrance = ReaderColorFilter.EMPTY.vibrance,
        cfDenoise = ReaderColorFilter.EMPTY.denoise,
        cfDither = ReaderColorFilter.EMPTY.dither,
        cfGrain = ReaderColorFilter.EMPTY.grain,
        cfSharpenMode = 0,
        cfRcasUsm = 0f,
        cfAdaptiveSmoothstep = 0f,
        cfAdaptiveSigmoid = 0f,
        cfRcas = ReaderColorFilter.EMPTY.rcas,
        cfAdaptiveSharpen = ReaderColorFilter.EMPTY.adaptiveSharpen,
        cfDeband = ReaderColorFilter.EMPTY.deband,
        cfBinarize = ReaderColorFilter.EMPTY.binarize,
        cfCatmullRom = 0f,
        cfBSpline = 0f,
        cfLineDarken = ReaderColorFilter.EMPTY.isLineDarkenEnabled,
        cfAutoContrast = ReaderColorFilter.EMPTY.isAutoContrast,
        cfAutoBrightness = ReaderColorFilter.EMPTY.isAutoBrightness,
        cfAutoSaturation = ReaderColorFilter.EMPTY.isAutoSaturation,
        cfOverlayR = ReaderColorFilter.EMPTY.overlayRed,
        cfOverlayG = ReaderColorFilter.EMPTY.overlayGreen,
        cfOverlayB = ReaderColorFilter.EMPTY.overlayBlue,
        cfOverlayA = ReaderColorFilter.EMPTY.overlayAlpha,
        cfDisabled = false,
        cfInvert = ReaderColorFilter.EMPTY.isInverted,
        cfGrayscale = ReaderColorFilter.EMPTY.isGrayscale,
        cfBookEffect = ReaderColorFilter.EMPTY.isBookBackground,
        titleOverride = null,
        coverUrlOverride = null,
        contentRatingOverride = null,
    )
}
