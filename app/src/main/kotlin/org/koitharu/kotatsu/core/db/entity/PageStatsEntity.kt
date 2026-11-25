package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Measured tone / color statistics of one page image, kept so the auto color filters read each image only
 * once. [pageKey] is a 64-bit key of the page's identity. [owner] is the hash of the local archive the page
 * lives in (kept until that archive is deleted), or 0 for a streamed page (expires after a day). The values
 * mirror `PageStats`; [version] invalidates rows when the measuring algorithm changes.
 */
@Entity(tableName = "page_stats")
class PageStatsEntity(
    @PrimaryKey @ColumnInfo(name = "page_key") val pageKey: Long,
    @ColumnInfo(name = "lo") val lo: Int,
    @ColumnInfo(name = "hi") val hi: Int,
    @ColumnInfo(name = "p5") val p5: Int,
    @ColumnInfo(name = "p95") val p95: Int,
    @ColumnInfo(name = "median") val median: Int,
    @ColumnInfo(name = "chroma_mean") val chromaMean: Int,
    @ColumnInfo(name = "chroma_p99") val chromaP99: Int,
    @ColumnInfo(name = "colored") val colored: Int,
    @ColumnInfo(name = "owner", defaultValue = "0") val owner: Long,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
