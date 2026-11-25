package org.koitharu.kotatsu.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.tracker.data.TrackEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity

/** Tracker state of one manga with its feed log. */
@Serializable
class FeedBackup(
    @SerialName("manga") val manga: MangaBackup,
    @SerialName("last_chapter_id") val lastChapterId: Long,
    @SerialName("chapters_new") val newChapters: Int,
    @SerialName("last_check_time") val lastCheckTime: Long,
    @SerialName("last_chapter_date") val lastChapterDate: Long,
    @SerialName("last_result") val lastResult: Int,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("logs") val logs: List<Log> = emptyList(),
) {

    @Serializable
    class Log(
        @SerialName("chapters") val chapters: String,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("unread") val isUnread: Boolean,
    )

    constructor(manga: MangaWithTags, track: TrackEntity, logs: List<TrackLogEntity>) : this(
        manga = MangaBackup(manga),
        lastChapterId = track.lastChapterId,
        newChapters = track.newChapters,
        lastCheckTime = track.lastCheckTime,
        lastChapterDate = track.lastChapterDate,
        lastResult = track.lastResult,
        lastError = track.lastError,
        logs = logs.map { Log(it.chapters, it.createdAt, it.isUnread) },
    )

    fun toTrackEntity() = TrackEntity(
        mangaId = manga.id,
        lastChapterId = lastChapterId,
        newChapters = newChapters,
        lastCheckTime = lastCheckTime,
        lastChapterDate = lastChapterDate,
        lastResult = lastResult,
        lastError = lastError,
    )

    fun toLogEntity(log: Log) = TrackLogEntity(
        mangaId = manga.id,
        chapters = log.chapters,
        createdAt = log.createdAt,
        isUnread = log.isUnread,
    )
}
