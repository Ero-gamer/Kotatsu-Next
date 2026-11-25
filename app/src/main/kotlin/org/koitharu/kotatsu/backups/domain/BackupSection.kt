package org.koitharu.kotatsu.backups.domain

import java.util.Locale
import java.util.zip.ZipEntry

enum class BackupSection(
    val entryName: String,
) {

    INDEX("index"),
    HISTORY("history"),
    CATEGORIES("categories"),
    FAVOURITES("favourites"),
    SETTINGS("settings"),
    SETTINGS_READER_GRID("reader_grid"),
    BOOKMARKS("bookmarks"),
    SOURCES("sources"),
    SCROBBLING("scrobbling"),
    STATS("statistics"),
    SAVED_FILTERS("saved_filters"),

    /** Images erased in the reader: "never again" should survive changing phones. */
    HIDDEN_IMAGES("hidden_images"),

    /** What the reader said they are not interested in on the Explore screen. */
    DISMISSED("dismissed"),

    /** Per-manga reader settings: colour filters, crop, reader mode, title / rating overrides. */
    MANGA_PREFS("manga_prefs"),

    /** Per-source options (domain, sort order, captcha flags...). */
    SOURCE_SETTINGS("source_settings"),

    /** Tracker state and the feed log: "new chapters" survive a restore. */
    FEED("feed"),
    ;

    companion object {

        fun of(entry: ZipEntry): BackupSection? {
            if (entry.isDirectory) {
                return null
            }
            // By file name alone: unzipping a backup and zipping it again usually puts it all in a folder
            val name = entry.name.replace('\\', '/')
                .substringAfterLast('/')
                .removeSuffix(".json")
                .lowercase(Locale.ROOT)
            return entries.find { x -> x.entryName == name }
        }
    }
}
