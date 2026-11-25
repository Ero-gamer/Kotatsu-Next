package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the `page_stats` table and the per-manga "Auto brightness" / "Auto saturation" switches. */
class Migration39To40 : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `page_stats` (`page_key` INTEGER NOT NULL, `lo` INTEGER NOT NULL, " +
                "`hi` INTEGER NOT NULL, `p5` INTEGER NOT NULL, `p95` INTEGER NOT NULL, `median` INTEGER NOT NULL, " +
                "`chroma_mean` INTEGER NOT NULL, `chroma_p99` INTEGER NOT NULL, `colored` INTEGER NOT NULL, " +
                "`version` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`page_key`))",
        )
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_auto_brightness` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_auto_saturation` INTEGER NOT NULL DEFAULT 0")
    }
}
