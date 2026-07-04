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
import org.koitharu.kotatsu.core.db.entity.ColorFilterProfileEntity
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

	// ── Lock (immune to global filter changes) ──────────────────────────────

	suspend fun isColorFilterLocked(mangaId: Long): Boolean =
		db.getPreferencesDao().isLocked(mangaId) == true

	suspend fun setColorFilterLocked(manga: Manga, locked: Boolean) {
		db.withTransaction {
			storeManga(manga, replaceExisting = false)
			val entity = db.getPreferencesDao().find(manga.id) ?: newEntity(manga.id)
			db.getPreferencesDao().upsert(entity.copy(isLocked = locked))
		}
	}

	// ── Saved color filter profiles (per-manga list when mangaId != null, global list when null) ──

	fun observeColorFilterProfiles(mangaId: Long?): Flow<List<ColorFilterProfile>> {
		return db.getColorFilterProfilesDao().observe(mangaId).map { list -> list.map { it.toColorFilterProfile() } }
	}

	suspend fun getColorFilterProfiles(mangaId: Long?): List<ColorFilterProfile> {
		return db.getColorFilterProfilesDao().list(mangaId).map { it.toColorFilterProfile() }
	}

	/**
	 * Saves [filter] as a new profile named [name] in [mangaId]'s list (or the global list if
	 * null). Returns null if the scope is already at [ColorFilterProfile.MAX_PROFILES_PER_SCOPE].
	 */
	suspend fun saveColorFilterProfile(mangaId: Long?, name: String, filter: ReaderColorFilter): ColorFilterProfile? {
		val dao = db.getColorFilterProfilesDao()
		if (dao.count(mangaId) >= ColorFilterProfile.MAX_PROFILES_PER_SCOPE) return null
		val order = (dao.maxSortOrder(mangaId) ?: -1) + 1
		val entity = ColorFilterProfileEntity(
			id = 0,
			mangaId = mangaId,
			name = name,
			sortOrder = order,
			cfBrightness = filter.brightness,
			cfContrast = filter.contrast,
			cfSharpening = filter.sharpening,
			cfSaturation = filter.saturation,
			cfVibrance = filter.vibrance,
			cfInvert = filter.isInverted,
			cfGrayscale = filter.isGrayscale,
			cfBookEffect = filter.isBookBackground,
		)
		val id = dao.insert(entity)
		return entity.copy(id = id).toColorFilterProfile()
	}

	suspend fun renameColorFilterProfile(profile: ColorFilterProfile, newName: String) {
		val dao = db.getColorFilterProfilesDao()
		val current = dao.list(profile.mangaId).find { it.id == profile.id } ?: return
		dao.update(current.copy(name = newName))
	}

	/** Overwrites the saved profile's values with [filter] (name unchanged). */
	suspend fun overwriteColorFilterProfile(profile: ColorFilterProfile, filter: ReaderColorFilter) {
		val dao = db.getColorFilterProfilesDao()
		val current = dao.list(profile.mangaId).find { it.id == profile.id } ?: return
		dao.update(
			current.copy(
				cfBrightness = filter.brightness,
				cfContrast = filter.contrast,
				cfSharpening = filter.sharpening,
				cfSaturation = filter.saturation,
				cfVibrance = filter.vibrance,
				cfInvert = filter.isInverted,
				cfGrayscale = filter.isGrayscale,
				cfBookEffect = filter.isBookBackground,
			),
		)
	}

	suspend fun deleteColorFilterProfile(profile: ColorFilterProfile) {
		db.getColorFilterProfilesDao().delete(profile.id)
	}

	/**
	 * Copies [profile] into [targetMangaId]'s list (null = global list) as a new profile —
	 * covers both "push this series' profile to global" and "import a global profile into
	 * this series" (also usable series-to-series). Returns null if the target scope is full.
	 */
	suspend fun copyColorFilterProfile(profile: ColorFilterProfile, targetMangaId: Long?): ColorFilterProfile? {
		return saveColorFilterProfile(targetMangaId, profile.name, profile.filter)
	}

	/** Sets the app-wide default filter and overwrites every NON-LOCKED manga's own override with it. */
	suspend fun applyGlobalColorFilter(filter: ReaderColorFilter) {
		db.getPreferencesDao().applyToAllUnlocked(
			brightness = filter.brightness,
			contrast = filter.contrast,
			sharpening = filter.sharpening,
			saturation = filter.saturation,
			vibrance = filter.vibrance,
			invert = filter.isInverted,
			grayscale = filter.isGrayscale,
			book = filter.isBookBackground,
		)
	}

	private fun ColorFilterProfileEntity.toColorFilterProfile() = ColorFilterProfile(
		id = id,
		mangaId = mangaId,
		name = name,
		filter = ReaderColorFilter(
			brightness = cfBrightness,
			contrast = cfContrast,
			sharpening = cfSharpening,
			saturation = cfSaturation,
			vibrance = cfVibrance,
			isInverted = cfInvert,
			isGrayscale = cfGrayscale,
			isBookBackground = cfBookEffect,
		),
	)

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
			cfSaturation != 0f || cfVibrance != 0f || cfInvert || cfGrayscale || cfBookEffect
		) {
			ReaderColorFilter(
				brightness = cfBrightness,
				contrast = cfContrast,
				sharpening = cfSharpening,
				saturation = cfSaturation,
				vibrance = cfVibrance,
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
		cfInvert = ReaderColorFilter.EMPTY.isInverted,
		cfGrayscale = ReaderColorFilter.EMPTY.isGrayscale,
		cfBookEffect = ReaderColorFilter.EMPTY.isBookBackground,
		isLocked = false,
		titleOverride = null,
		coverUrlOverride = null,
		contentRatingOverride = null,
	)
}
