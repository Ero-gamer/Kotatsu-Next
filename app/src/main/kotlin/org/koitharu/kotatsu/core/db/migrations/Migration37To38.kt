package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds `optimize_tasks`: history of the "Image optimizer" runs (split tall images / convert to WebP). */
class Migration37To38 : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `optimize_tasks` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`job_id` TEXT NOT NULL, " +
                "`work_id` TEXT NOT NULL, " +
                "`manga_id` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`split` INTEGER NOT NULL, " +
                "`convert` INTEGER NOT NULL, " +
                "`state` INTEGER NOT NULL, " +
                "`total` INTEGER NOT NULL, " +
                "`done` INTEGER NOT NULL, " +
                "`changed` INTEGER NOT NULL, " +
                "`failed` INTEGER NOT NULL, " +
                "`saved_bytes` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`finished_at` INTEGER NOT NULL)",
        )
    }
}
