package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds `crop_pages` / `crop_webtoon`: per-manga border crop override (0 global, 1 on, 2 off). */
class Migration44To45 : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE preferences ADD COLUMN `crop_pages` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE preferences ADD COLUMN `crop_webtoon` INTEGER NOT NULL DEFAULT 0")
    }
}
