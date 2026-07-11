package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Handles devices on old v31 (which had is_locked + color_filter_profiles from a broken migration).
 * Recreates the preferences table with the correct schema (dropping is_locked) and adds
 * cf_denoise, cf_dither, cf_grain columns. Devices on clean v31 (from Migration30To31) also pass
 * through safely since all data is preserved via INSERT SELECT.
 */
class Migration31To32 : Migration(31, 32) {
	override fun migrate(db: SupportSQLiteDatabase) {
		// Recreate preferences with the exact schema Room expects at v32.
		// This also drops the stray is_locked column left by the old broken Migration30To31.
		db.execSQL(
			"""CREATE TABLE IF NOT EXISTS `preferences_new` (
				`manga_id` INTEGER NOT NULL,
				`mode` INTEGER NOT NULL,
				`cf_brightness` REAL NOT NULL,
				`cf_contrast` REAL NOT NULL,
				`cf_sharpening` REAL NOT NULL DEFAULT 0,
				`cf_vibrance` REAL NOT NULL DEFAULT 0,
				`cf_vibrance2` REAL NOT NULL DEFAULT 0,
				`cf_invert` INTEGER NOT NULL,
				`cf_grayscale` INTEGER NOT NULL,
				`cf_book` INTEGER NOT NULL,
				`cf_denoise` REAL NOT NULL DEFAULT 0,
				`cf_dither` REAL NOT NULL DEFAULT 0,
				`cf_grain` REAL NOT NULL DEFAULT 0,
				`title_override` TEXT,
				`cover_override` TEXT,
				`content_rating_override` TEXT,
				PRIMARY KEY(`manga_id`),
				FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE
			)"""
		)
		// Copy all existing data; cf_denoise/dither/grain default to 0 for existing rows.
		// is_locked is intentionally excluded.
		db.execSQL(
			"""INSERT INTO `preferences_new` (
				manga_id, mode, cf_brightness, cf_contrast,
				cf_sharpening, cf_vibrance, cf_vibrance2,
				cf_invert, cf_grayscale, cf_book,
				cf_denoise, cf_dither, cf_grain,
				title_override, cover_override, content_rating_override
			) SELECT
				manga_id, mode, cf_brightness, cf_contrast,
				cf_sharpening, cf_vibrance, cf_vibrance2,
				cf_invert, cf_grayscale, cf_book,
				COALESCE(cf_denoise, 0), COALESCE(cf_dither, 0), COALESCE(cf_grain, 0),
				title_override, cover_override, content_rating_override
			FROM `preferences`"""
		)
		db.execSQL("DROP TABLE `preferences`")
		db.execSQL("ALTER TABLE `preferences_new` RENAME TO `preferences`")
	}
}
