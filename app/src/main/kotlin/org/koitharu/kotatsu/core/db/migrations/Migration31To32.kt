package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Recreates preferences table with correct v32 schema.
 * Handles both old broken-v31 (had is_locked, no cf_denoise/dither/grain)
 * and clean-v31 (has cf_denoise/dither/grain, no is_locked).
 */
class Migration31To32 : Migration(31, 32) {
	override fun migrate(db: SupportSQLiteDatabase) {
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
		// Do NOT select cf_denoise/dither/grain from source — they may not exist (old broken v31).
		// The DEFAULT 0 in preferences_new fills them automatically.
		db.execSQL(
			"""INSERT INTO `preferences_new` (
				manga_id, mode, cf_brightness, cf_contrast,
				cf_sharpening, cf_vibrance, cf_vibrance2,
				cf_invert, cf_grayscale, cf_book,
				title_override, cover_override, content_rating_override
			) SELECT
				manga_id, mode, cf_brightness, cf_contrast,
				cf_sharpening, cf_vibrance, cf_vibrance2,
				cf_invert, cf_grayscale, cf_book,
				title_override, cover_override, content_rating_override
			FROM `preferences`"""
		)
		db.execSQL("DROP TABLE `preferences`")
		db.execSQL("ALTER TABLE `preferences_new` RENAME TO `preferences`")
	}
}
