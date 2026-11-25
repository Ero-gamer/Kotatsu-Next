package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds `page_stats.owner`: 0 = streamed page (expires), else the hash of the local archive it belongs to. */
class Migration40To41 : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE page_stats ADD COLUMN `owner` INTEGER NOT NULL DEFAULT 0")
    }
}
