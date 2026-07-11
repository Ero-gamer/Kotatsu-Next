package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds cf_denoise, cf_dither, cf_grain columns to preferences table. */
class Migration30To31 : Migration(30, 31) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_denoise` REAL NOT NULL DEFAULT 0")
		db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_dither` REAL NOT NULL DEFAULT 0")
		db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_grain` REAL NOT NULL DEFAULT 0")
	}
}
