package org.koitharu.kotatsu.details.ui.model

import org.koitharu.kotatsu.details.ui.model.ChapterListItem.Companion.FLAG_BOOKMARKED
import org.koitharu.kotatsu.details.ui.model.ChapterListItem.Companion.FLAG_CURRENT
import org.koitharu.kotatsu.details.ui.model.ChapterListItem.Companion.FLAG_DOWNLOADED
import org.koitharu.kotatsu.details.ui.model.ChapterListItem.Companion.FLAG_GRID
import org.koitharu.kotatsu.details.ui.model.ChapterListItem.Companion.FLAG_NEW
import org.koitharu.kotatsu.details.ui.model.ChapterListItem.Companion.FLAG_OPTIMIZED
import org.koitharu.kotatsu.details.ui.model.ChapterListItem.Companion.FLAG_UNREAD
import org.koitharu.kotatsu.parsers.model.MangaChapter
import kotlin.experimental.or

fun MangaChapter.toListItem(
    isCurrent: Boolean,
    isUnread: Boolean,
    isNew: Boolean,
    isDownloaded: Boolean,
    isBookmarked: Boolean,
    isGrid: Boolean,
    isOptimized: Boolean = false,
): ChapterListItem {
    var flags: Byte = 0
    if (isCurrent) flags = flags or FLAG_CURRENT
    if (isUnread) flags = flags or FLAG_UNREAD
    if (isNew) flags = flags or FLAG_NEW
    if (isBookmarked) flags = flags or FLAG_BOOKMARKED
    if (isDownloaded) flags = flags or FLAG_DOWNLOADED
    if (isGrid) flags = flags or FLAG_GRID
    if (isOptimized) flags = flags or FLAG_OPTIMIZED
    return ChapterListItem(
        chapter = this,
        flags = flags,
    )
}
