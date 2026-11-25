package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the per-manga color overlay (red, green, blue, alpha) to `preferences`. */
class Migration41To42 : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (column in listOf("cf_overlay_r", "cf_overlay_g", "cf_overlay_b", "cf_overlay_a")) {
            db.execSQL("ALTER TABLE preferences ADD COLUMN `$column` REAL NOT NULL DEFAULT 0")
        }
    }
}
