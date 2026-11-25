package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Repairs `page_stats` on development databases created while its column was still spelled `coloured`.
 * A table that already has the `colored` column is healthy and left untouched. The table only holds measured
 * page statistics, so recreating it loses nothing that cannot be measured again.
 */
class Migration42To43 : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = db.query("PRAGMA table_info(`page_stats`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
        }
        if ("colored" in columns) return
        db.execSQL("DROP TABLE IF EXISTS `page_stats`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `page_stats` (`page_key` INTEGER NOT NULL, `lo` INTEGER NOT NULL, " +
                "`hi` INTEGER NOT NULL, `p5` INTEGER NOT NULL, `p95` INTEGER NOT NULL, `median` INTEGER NOT NULL, " +
                "`chroma_mean` INTEGER NOT NULL, `chroma_p99` INTEGER NOT NULL, `colored` INTEGER NOT NULL, " +
                "`owner` INTEGER NOT NULL DEFAULT 0, `version` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`page_key`))",
        )
    }
}
