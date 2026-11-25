package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds `cf_auto_contrast`: the per-manga "Auto contrast" color-filter switch. */
class Migration38To39 : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_auto_contrast` INTEGER NOT NULL DEFAULT 0")
    }
}
