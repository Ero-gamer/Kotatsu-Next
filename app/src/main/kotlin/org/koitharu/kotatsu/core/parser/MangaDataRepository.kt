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
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.entity.ColorFilterProfileEntity
import org.koitharu.kotatsu.reader.domain.ColorFilterProfile
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
					cfSharpening = colorFilter?.sharpening ?: 0f,
					cfSaturation = colorFilter?.saturation ?: 0f,
					cfVibrance = colorFilter?.vibrance ?: 0f,
					cfDenoise = colorFilter?.denoise ?: 0f,
					cfDither = colorFilter?.dither ?: 0f,
					cfGrain = colorFilter?.grain ?: 0f,
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

	suspend fun isColorFilterLocked(mangaId: Long): Boolean =
		db.getPreferencesDao().isLocked(mangaId) == true

	suspend fun setColorFilterLocked(manga: Manga, locked: Boolean) {
		db.withTransaction {
			storeManga(manga, replaceExisting = false)
			val entity = db.getPreferencesDao().find(manga.id) ?: newEntity(manga.id)
			db.getPreferencesDao().upsert(entity.copy(isLocked = locked))
		}
	}

	suspend fun getColorFilterProfiles(mangaId: Long?): List<ColorFilterProfile> =
		db.getColorFilterProfilesDao().list(mangaId).map(::entityToProfile)

	suspend fun saveColorFilterProfile(mangaId: Long?, name: String, filter: ReaderColorFilter): ColorFilterProfile? {
		val dao = db.getColorFilterProfilesDao()
		if (dao.count(mangaId) >= ColorFilterProfile.MAX_PROFILES_PER_SCOPE) return null
		val order = (dao.maxSortOrder(mangaId) ?: -1) + 1
		val entity = ColorFilterProfileEntity(0, mangaId, name, order,
			filter.brightness, filter.contrast, filter.sharpening, filter.saturation,
			filter.vibrance, filter.denoise, filter.dither, filter.grain,
			filter.isInverted, filter.isGrayscale, filter.isBookBackground)
		return entityToProfile(entity.copy(id = dao.insert(entity)))
	}

	suspend fun renameColorFilterProfile(profile: ColorFilterProfile, name: String) {
		val dao = db.getColorFilterProfilesDao()
		dao.list(profile.mangaId).find { it.id == profile.id }?.let { dao.update(it.copy(name = name)) }
	}

	suspend fun overwriteColorFilterProfile(profile: ColorFilterProfile, filter: ReaderColorFilter) {
		val dao = db.getColorFilterProfilesDao()
		dao.list(profile.mangaId).find { it.id == profile.id }?.let {
			dao.update(it.copy(cfBrightness=filter.brightness, cfContrast=filter.contrast,
				cfSharpening=filter.sharpening, cfSaturation=filter.saturation, cfVibrance=filter.vibrance,
				cfDenoise=filter.denoise, cfDither=filter.dither, cfGrain=filter.grain,
				cfInvert=filter.isInverted, cfGrayscale=filter.isGrayscale, cfBookEffect=filter.isBookBackground))
		}
	}

	suspend fun deleteColorFilterProfile(profile: ColorFilterProfile) =
		db.getColorFilterProfilesDao().delete(profile.id)

	suspend fun copyColorFilterProfile(profile: ColorFilterProfile, targetMangaId: Long?): ColorFilterProfile? =
		saveColorFilterProfile(targetMangaId, profile.name, profile.filter)

	suspend fun applyGlobalColorFilter(filter: ReaderColorFilter) {
		db.getPreferencesDao().applyToAllUnlocked(filter.brightness, filter.contrast, filter.sharpening,
			filter.saturation, filter.vibrance, filter.denoise, filter.dither, filter.grain,
			filter.isInverted, filter.isGrayscale, filter.isBookBackground)
	}

	private fun entityToProfile(e: ColorFilterProfileEntity) = ColorFilterProfile(e.id, e.mangaId, e.name,
		ReaderColorFilter(e.cfBrightness, e.cfContrast, e.cfSharpening, e.cfSaturation, e.cfVibrance,
			e.cfDenoise, e.cfDither, e.cfGrain, e.cfInvert, e.cfGrayscale, e.cfBookEffect))

	suspend fun getReaderMode(mangaId: Long): ReaderMode? {
		return db.getPreferencesDao().find(mangaId)?.let { ReaderMode.valueOf(it.mode) }
	}

	suspend fun getColorFilter(mangaId: Long): ReaderColorFilter? {
		return db.getPreferencesDao().find(mangaId)?.getColorFilterOrNull()
	}

	suspend fun getOverride(mangaId: Long): MangaOverride? {
		return db.getPreferencesDao().find(mangaId)?.getOverrideOrNull()
	}

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

	fun observeColorFilter(mangaId: Long): Flow<ReaderColorFilter?> {
		return db.getPreferencesDao().observe(mangaId)
			.map { it?.getColorFilterOrNull() }
			.distinctUntilChanged()
	}

	suspend fun findMangaById(mangaId: Long, withChapters: Boolean): Manga? {
		val chapters = if (withChapters) {
			db.getChaptersDao().findAll(mangaId).takeUnless { it.isEmpty() }
		} else {
			null
		}
		return db.getMangaDao().find(mangaId)?.toManga(chapters)
	}

	suspend fun findMangaByPublicUrl(publicUrl: String): Manga? {
		return db.getMangaDao().findByPublicUrl(publicUrl)?.toManga()
	}

	suspend fun resolveIntent(intent: MangaIntent, withChapters: Boolean): Manga? = when {
		intent.manga != null -> intent.manga.withCachedChaptersIfNeeded(withChapters)
		intent.mangaId != 0L -> findMangaById(intent.mangaId, withChapters)
		intent.uri != null -> resolverProvider.get().resolve(intent.uri).withCachedChaptersIfNeeded(withChapters)
		else -> null
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
						db.getChaptersDao().replaceAll(manga.id, chapters.withIndex().toEntities(manga.id))
					}
				}
			}
		}
	}

	suspend fun updateChapters(manga: Manga) {
		val chapters = manga.chapters
		if (!chapters.isNullOrEmpty() && manga.id in db.getMangaDao()) {
			db.getChaptersDao().replaceAll(manga.id, chapters.withIndex().toEntities(manga.id))
		}
	}

	suspend fun gcChaptersCache() {
		db.getChaptersDao().gc()
	}

	suspend fun findTags(source: MangaSource): Set<MangaTag> {
		return db.getTagsDao().findTags(source.name).toMangaTags()
	}

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

	private fun MangaPrefsEntity.getColorFilterOrNull(): ReaderColorFilter? {
		return if (cfBrightness != 0f || cfContrast != 0f || cfSharpening != 0f ||
			cfSaturation != 0f || cfVibrance != 0f || cfDenoise != 0f || cfDither != 0f || cfGrain != 0f || cfInvert || cfGrayscale || cfBookEffect
		) {
			ReaderColorFilter(
				brightness = cfBrightness,
				contrast = cfContrast,
				sharpening = cfSharpening,
				saturation = cfSaturation,
				vibrance = cfVibrance,
				denoise = cfDenoise,
				dither = cfDither,
				grain = cfGrain,
				isInverted = cfInvert,
				isGrayscale = cfGrayscale,
				isBookBackground = cfBookEffect,
			)
		} else {
			null
		}
	}

	private fun MangaPrefsEntity.getOverrideOrNull(): MangaOverride? {
		return if (titleOverride.isNullOrEmpty() && coverUrlOverride.isNullOrEmpty() && contentRatingOverride.isNullOrEmpty()) {
			null
		} else {
			MangaOverride(
				coverUrl = coverUrlOverride?.nullIfEmpty(),
				title = titleOverride?.nullIfEmpty(),
				contentRating = ContentRating(contentRatingOverride),
			)
		}
	}

	private fun newEntity(mangaId: Long) = MangaPrefsEntity(
		mangaId = mangaId,
		mode = -1,
		cfBrightness = ReaderColorFilter.EMPTY.brightness,
		cfContrast = ReaderColorFilter.EMPTY.contrast,
		cfSharpening = ReaderColorFilter.EMPTY.sharpening,
		cfSaturation = ReaderColorFilter.EMPTY.saturation,
		cfVibrance = ReaderColorFilter.EMPTY.vibrance,
		cfDenoise = ReaderColorFilter.EMPTY.denoise,
		cfDither = ReaderColorFilter.EMPTY.dither,
		cfGrain = ReaderColorFilter.EMPTY.grain,
		cfInvert = ReaderColorFilter.EMPTY.isInverted,
		isLocked = false,
		cfGrayscale = ReaderColorFilter.EMPTY.isGrayscale,
		cfBookEffect = ReaderColorFilter.EMPTY.isBookBackground,
		titleOverride = null,
		coverUrlOverride = null,
		contentRatingOverride = null,
	)
}
