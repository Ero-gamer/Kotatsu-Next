package org.koitharu.kotatsu.reader.domain

/**
 * The chapters to download while [currentId] is read: the next [count] ones of [chapterIds] (in reading
 * order) that are not saved yet. Nothing unless the chapter being read is saved itself, so reading online
 * never starts a download.
 */
fun selectDownloadAhead(
    chapterIds: List<Long>,
    currentId: Long,
    downloadedIds: Set<Long>,
    count: Int,
): List<Long> {
    if (count <= 0 || currentId !in downloadedIds) return emptyList()
    val index = chapterIds.indexOf(currentId)
    if (index < 0) return emptyList()
    return chapterIds.subList(index + 1, minOf(chapterIds.size, index + 1 + count))
        .filter { it !in downloadedIds }
}
