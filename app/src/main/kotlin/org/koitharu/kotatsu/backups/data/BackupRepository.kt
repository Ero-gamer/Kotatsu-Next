package org.koitharu.kotatsu.backups.data

import android.content.Context
import androidx.collection.ArrayMap
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.backups.data.model.BackupIndex
import org.koitharu.kotatsu.backups.data.model.BookmarkBackup
import org.koitharu.kotatsu.backups.data.model.CategoryBackup
import org.koitharu.kotatsu.backups.data.model.FavouriteBackup
import org.koitharu.kotatsu.backups.data.model.FeedBackup
import org.koitharu.kotatsu.backups.data.model.MangaPrefsBackup
import org.koitharu.kotatsu.backups.data.model.SourceSettingsBackup
import org.koitharu.kotatsu.backups.data.model.DismissedBackup
import org.koitharu.kotatsu.backups.data.model.HiddenImageBackup
import org.koitharu.kotatsu.backups.data.model.HistoryBackup
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.backups.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backups.data.model.SourceBackup
import org.koitharu.kotatsu.backups.data.model.StatisticBackup
import org.koitharu.kotatsu.backups.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.CompositeResult
import org.koitharu.kotatsu.core.util.progress.Progress
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.filter.data.PersistableFilter
import org.koitharu.kotatsu.filter.data.SavedFiltersRepository
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.discover.data.DiscoverDismissals
import org.koitharu.kotatsu.reader.data.erase.HiddenImagesRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@Reusable
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
    private val settings: AppSettings,
    private val tapGridSettings: TapGridSettings,
    private val mangaSourcesRepository: MangaSourcesRepository,
    private val savedFiltersRepository: SavedFiltersRepository,
    /** Nullable: a backup agent has no Hilt graph, and writes an empty section. */
    private val hiddenImages: HiddenImagesRepository? = null,
    /** Nullable for the same reason: a backup agent writes an empty section and restores nothing. */
    private val dismissals: DiscoverDismissals? = null,
) {

    private val json = Json {
        allowSpecialFloatingPointValues = true
        coerceInputValues = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        useAlternativeNames = false
    }

    suspend fun createBackup(
        output: ZipOutputStream,
        progress: FlowCollector<Progress>?,
    ) {
        progress?.emit(Progress.INDETERMINATE)
        var commonProgress = Progress(0, BackupSection.entries.size)
        for (section in BackupSection.entries) {
            when (section) {
                BackupSection.INDEX -> output.writeJsonArray(
                    section = BackupSection.INDEX,
                    data = flowOf(BackupIndex()),
                    serializer = serializer(),
                )

                BackupSection.HISTORY -> output.writeJsonArray(
                    section = BackupSection.HISTORY,
                    data = database.getHistoryDao().dump().map { HistoryBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.CATEGORIES -> output.writeJsonArray(
                    section = BackupSection.CATEGORIES,
                    data = database.getFavouriteCategoriesDao().findAll().asFlow().map { CategoryBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.FAVOURITES -> output.writeJsonArray(
                    section = BackupSection.FAVOURITES,
                    data = database.getFavouritesDao().dump().map { FavouriteBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.SETTINGS -> output.writeString(
                    section = BackupSection.SETTINGS,
                    data = dumpSettings(),
                )

                BackupSection.SETTINGS_READER_GRID -> output.writeString(
                    section = BackupSection.SETTINGS_READER_GRID,
                    data = dumpReaderGridSettings(),
                )

                BackupSection.BOOKMARKS -> output.writeJsonArray(
                    section = BackupSection.BOOKMARKS,
                    data = database.getBookmarksDao().dump().map { BookmarkBackup(it.first, it.second) },
                    serializer = serializer(),
                )

                BackupSection.HIDDEN_IMAGES -> output.writeJsonArray(
                    section = BackupSection.HIDDEN_IMAGES,
                    data = hiddenImages?.dump().orEmpty().asFlow().map { HiddenImageBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.SOURCES -> output.writeJsonArray(
                    section = BackupSection.SOURCES,
                    data = database.getSourcesDao().dumpEnabled().map { SourceBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.SCROBBLING -> output.writeJsonArray(
                    section = BackupSection.SCROBBLING,
                    data = database.getScrobblingDao().dumpEnabled().map { ScrobblingBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.STATS -> output.writeJsonArray(
                    section = BackupSection.STATS,
                    data = database.getStatsDao().dumpEnabled().map { StatisticBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.DISMISSED -> output.writeJsonArray(
                    section = BackupSection.DISMISSED,
                    data = dismissals?.get()?.entries.orEmpty().entries.asFlow().map { DismissedBackup.of(it.key, it.value) },
                    serializer = serializer(),
                )

                BackupSection.MANGA_PREFS -> output.writeJsonArray(
                    section = BackupSection.MANGA_PREFS,
                    data = database.getPreferencesDao().dump().map { MangaPrefsBackup(it.first, it.second) },
                    serializer = serializer(),
                )

                BackupSection.SOURCE_SETTINGS -> output.writeJsonArray(
                    section = BackupSection.SOURCE_SETTINGS,
                    data = dumpSourceSettings().asFlow(),
                    serializer = serializer(),
                )

                BackupSection.FEED -> output.writeJsonArray(
                    section = BackupSection.FEED,
                    data = database.getTracksDao().dump().map {
                        FeedBackup(it.first, it.second, database.getTrackLogsDao().findByManga(it.first.manga.id))
                    },
                    serializer = serializer(),
                )

                BackupSection.SAVED_FILTERS -> {
                    val sources = mangaSourcesRepository.getEnabledSources()
                    val filters = sources.flatMap { source ->
                        savedFiltersRepository.getAll(source)
                    }
                    output.writeJsonArray(
                        section = BackupSection.SAVED_FILTERS,
                        data = filters.asFlow(),
                        serializer = serializer(),
                    )
                }
            }
            progress?.emit(commonProgress)
            commonProgress++
        }
        progress?.emit(commonProgress)
    }

    suspend fun restoreBackup(
        input: ZipInputStream,
        sections: Set<BackupSection>,
        progress: FlowCollector<Progress>?,
    ): CompositeResult {
        progress?.emit(Progress.INDETERMINATE)
        var commonProgress = Progress(0, sections.size)
        var entry = input.nextEntry
        var result = CompositeResult.EMPTY
        while (entry != null) {
            val section = BackupSection.of(entry)
            if (section in sections) {
                // A damaged section must not take the rest of the backup down with it
                result += runCatchingCancellable {
                    when (section) {
                    BackupSection.INDEX -> CompositeResult.EMPTY

                    // useless in our case
                    BackupSection.HISTORY -> input.readJsonArray<HistoryBackup>(serializer()).restoreToDb {
                        upsertManga(it.manga)
                        getHistoryDao().upsert(it.toEntity())
                    }

                    BackupSection.CATEGORIES -> input.readJsonArray<CategoryBackup>(serializer()).restoreToDb {
                        getFavouriteCategoriesDao().upsert(it.toEntity())
                    }

                    BackupSection.FAVOURITES -> input.readJsonArray<FavouriteBackup>(serializer()).restoreToDb {
                        upsertManga(it.manga)
                        getFavouritesDao().upsert(it.toEntity())
                    }

                    BackupSection.SETTINGS -> input.readMap().let {
                        settings.upsertAll(it)
                        CompositeResult.success()
                    }

                    BackupSection.SETTINGS_READER_GRID -> input.readMap().let {
                        tapGridSettings.upsertAll(it)
                        CompositeResult.success()
                    }

                    BackupSection.BOOKMARKS -> input.readJsonArray<BookmarkBackup>(serializer()).restoreToDb {
                        upsertManga(it.manga)
                        getBookmarksDao().upsert(it.bookmarks.map { b -> b.toEntity() })
                    }

                    BackupSection.SOURCES -> input.readJsonArray<SourceBackup>(serializer()).restoreToDb {
                        getSourcesDao().upsert(it.toEntity())
                    }

                    BackupSection.SCROBBLING -> input.readJsonArray<ScrobblingBackup>(serializer()).restoreToDb {
                        getScrobblingDao().upsert(it.toEntity())
                    }

                    BackupSection.STATS -> input.readJsonArray<StatisticBackup>(serializer()).restoreToDb {
                        // statistics hang on the history row: without one (history not restored) there is nowhere to put it
                        if (getHistoryDao().find(it.mangaId) != null) {
                            getStatsDao().upsert(it.toEntity())
                        }
                    }

                    BackupSection.SAVED_FILTERS -> input.readJsonArray<PersistableFilter>(serializer())
                        .restoreWithoutTransaction {
                            savedFiltersRepository.save(it)
                        }

                    BackupSection.HIDDEN_IMAGES -> input.readJsonArray<HiddenImageBackup>(serializer())
                        .restoreWithoutTransaction {
                            hiddenImages?.restore(it.toEntity())
                        }

                    // Merged with what is here: a pass already on this device is kept as it is
                    BackupSection.DISMISSED -> input.readJsonArray<DismissedBackup>(serializer())
                        .restoreWithoutTransaction {
                            dismissals?.store?.restore(listOf(it.key to it.entry))
                        }

                    BackupSection.MANGA_PREFS -> input.readJsonArray<MangaPrefsBackup>(serializer()).restoreToDb {
                        upsertManga(it.manga)
                        getPreferencesDao().upsert(it.toEntity())
                    }

                    BackupSection.SOURCE_SETTINGS -> input.readJsonArray<SourceSettingsBackup>(serializer())
                        .restoreWithoutTransaction {
                            restoreSourceSettings(it)
                        }

                    BackupSection.FEED -> input.readJsonArray<FeedBackup>(serializer()).restoreToDb {
                        upsertManga(it.manga)
                        getTracksDao().upsert(it.toTrackEntity())
                        val logs = getTrackLogsDao()
                        for (log in it.logs) {
                            // restoring the same backup twice must not double the feed
                            if (logs.countAt(it.manga.id, log.createdAt) == 0) {
                                logs.insert(it.toLogEntity(log))
                            }
                        }
                    }

                    null -> CompositeResult.EMPTY // skip unknown entries
                    }
                }.getOrElse { CompositeResult.failure(it) }
                progress?.emit(commonProgress)
                commonProgress++
            }
            input.closeEntry()
            entry = input.nextEntry
        }
        progress?.emit(commonProgress)
        return result
    }

    private suspend fun <T> ZipOutputStream.writeJsonArray(
        section: BackupSection,
        data: Flow<T>,
        serializer: SerializationStrategy<T>,
    ) {
        data.onStart {
            putNextEntry(ZipEntry(section.entryName))
            write("[")
        }.onCompletion { error ->
            if (error == null) {
                write("]")
            }
            closeEntry()
            flush()
        }.collectIndexed { index, value ->
            if (index > 0) {
                write(",")
            }
            json.encodeToStream(serializer, value, this)
        }
    }

    private fun <T> InputStream.readJsonArray(
        serializer: DeserializationStrategy<T>,
    ): Sequence<T> = json.decodeToSequence(this, serializer, DecodeSequenceMode.ARRAY_WRAPPED)

    private fun InputStream.readMap(): Map<String, Any?> {
        val jo = JSONArray(readString()).getJSONObject(0)
        val map = ArrayMap<String, Any?>(jo.length())
        val keys = jo.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = jo.get(key)
        }
        return map
    }

    private fun ZipOutputStream.writeString(
        section: BackupSection,
        data: String,
    ) {
        putNextEntry(ZipEntry(section.entryName))
        try {
            write("[")
            write(data)
            write("]")
        } finally {
            closeEntry()
            flush()
        }
    }

    private fun OutputStream.write(str: String) = write(str.toByteArray())

    private fun InputStream.readString(): String = readBytes().decodeToString()

    private fun dumpSettings(): String {
        return JSONObject(settings.getBackupValues()).toString()
    }

    /** Only sources that have a prefs file: asking for one creates (and keeps in memory) an empty prefs object. */
    private fun dumpSourceSettings(): List<SourceSettingsBackup> {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val result = ArrayList<SourceSettingsBackup>()
        for (source in mangaSourcesRepository.allMangaSources) {
            val fileName = source.name.replace(File.separatorChar, '$')
            if (!File(prefsDir, "$fileName.xml").exists()) {
                continue
            }
            val values = HashMap<String, kotlinx.serialization.json.JsonElement>()
            for ((key, value) in context.getSharedPreferences(fileName, Context.MODE_PRIVATE).all) {
                if (key == SourceSettings.KEY_CF_USER_AGENT) {
                    continue // tied to this device's WebView and its Cloudflare clearance
                }
                values[key] = when (value) {
                    is Boolean -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    is String -> JsonPrimitive(value)
                    is Set<*> -> JsonArray(value.filterIsInstance<String>().map { JsonPrimitive(it) })
                    else -> continue
                }
            }
            if (values.isNotEmpty()) {
                result += SourceSettingsBackup(source.name, JsonObject(values))
            }
        }
        return result
    }

    /** Only the file of a source we know: a name from a backup file is never used as a path as it is. */
    private fun restoreSourceSettings(backup: SourceSettingsBackup) {
        val source = mangaSourcesRepository.allMangaSources.firstOrNull { it.name == backup.source } ?: return
        val prefs = context.getSharedPreferences(source.name.replace(File.separatorChar, '$'), Context.MODE_PRIVATE)
        prefs.edit {
            for (key in prefs.all.keys) {
                if (key != SourceSettings.KEY_CF_USER_AGENT) {
                    remove(key)
                }
            }
            for ((key, element) in backup.values) {
                if (key == SourceSettings.KEY_CF_USER_AGENT) {
                    continue
                }
                when (element) {
                    is JsonArray -> putStringSet(key, element.mapNotNullTo(HashSet()) { (it as? JsonPrimitive)?.contentOrNull })

                    is JsonPrimitive -> {
                        val bool = element.booleanOrNull
                        val text = element.contentOrNull
                        when {
                            element.isString -> putString(key, text)
                            bool != null -> putBoolean(key, bool)
                            text?.toIntOrNull() != null -> putInt(key, text.toInt())
                            text?.toLongOrNull() != null -> putLong(key, text.toLong())
                            text?.toFloatOrNull() != null -> putFloat(key, text.toFloat())
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun dumpReaderGridSettings(): String = JSONObject(tapGridSettings.getAllValues()).toString()

    private suspend fun MangaDatabase.upsertManga(manga: MangaBackup) {
        val tags = manga.tags.map { it.toEntity() }
        getTagsDao().upsert(tags)
        getMangaDao().upsert(manga.toEntity(), tags)
    }

    private suspend inline fun <T> Sequence<T>.restoreToDb(crossinline block: suspend MangaDatabase.(T) -> Unit): CompositeResult = restoreEach { item ->
        database.withTransaction {
            database.block(item)
        }
    }

    private suspend inline fun <T> Sequence<T>.restoreWithoutTransaction(crossinline block: suspend (T) -> Unit): CompositeResult = restoreEach { item ->
        block(item)
    }

    /**
     * Every record on its own: a failed one is counted, the rest go on. A record that cannot even be read
     * ends the section (the stream is not trustworthy after it), but what was restored before it is kept.
     */
    private suspend inline fun <T> Sequence<T>.restoreEach(crossinline block: suspend (T) -> Unit): CompositeResult {
        var result = CompositeResult.EMPTY
        val iterator = iterator()
        while (true) {
            val item = runCatchingCancellable {
                if (iterator.hasNext()) iterator.next() else null
            }.getOrElse { e ->
                return result + CompositeResult.failure(e)
            } ?: return result
            result += runCatchingCancellable { block(item) }
        }
    }
}
