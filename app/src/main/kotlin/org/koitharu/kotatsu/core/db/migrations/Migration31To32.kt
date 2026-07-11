package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Handles devices already on v31 from the old migration (which added is_locked +
 * color_filter_profiles). Adds cf_denoise, cf_dither, cf_grain to preferences.
 * The extra is_locked column and color_filter_profiles table are ignored by Room
 * since neither is mapped to a registered entity.
 */
class Migration31To32 : Migration(31, 32) {
	override fun migrate(db: SupportSQLiteDatabase) {
		// Use a shadow-table approach isn't needed — SQLite silently keeps extra columns.
		// We only need to add the 3 missing filter columns. Guard against the rare case
		// where they were already added by running Migration30To31 on a clean v30 device
		// that was then re-migrated (shouldn't happen, but be safe).
		val cursor = db.query("PRAGMA table_info(preferences)")
		val existingColumns = mutableSetOf<String>()
		val nameIndex = cursor.getColumnIndex("name")
		while (cursor.moveToNext()) {
			existingColumns.add(cursor.getString(nameIndex))
		}
		cursor.close()

		if ("cf_denoise" !in existingColumns) {
			db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_denoise` REAL NOT NULL DEFAULT 0")
		}
		if ("cf_dither" !in existingColumns) {
			db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_dither` REAL NOT NULL DEFAULT 0")
		}
		if ("cf_grain" !in existingColumns) {
			db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_grain` REAL NOT NULL DEFAULT 0")
		}
	}
}
