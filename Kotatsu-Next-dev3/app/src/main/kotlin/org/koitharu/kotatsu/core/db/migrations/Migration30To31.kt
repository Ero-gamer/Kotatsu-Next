package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds is_locked column to preferences + creates color_filter_profiles table. */
class Migration30To31 : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE preferences ADD COLUMN `is_locked` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `color_filter_profiles` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `manga_id` INTEGER,
                `name` TEXT NOT NULL,
                `sort_order` INTEGER NOT NULL,
                `cf_brightness` REAL NOT NULL,
                `cf_contrast` REAL NOT NULL,
                `cf_sharpening` REAL NOT NULL,
                `cf_saturation` REAL NOT NULL,
                `cf_vibrance` REAL NOT NULL,
                `cf_denoise` REAL NOT NULL DEFAULT 0,
                `cf_dither` REAL NOT NULL DEFAULT 0,
                `cf_grain` REAL NOT NULL DEFAULT 0,
                `cf_invert` INTEGER NOT NULL,
                `cf_grayscale` INTEGER NOT NULL,
                `cf_book` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_color_filter_profiles_manga_id` ON `color_filter_profiles` (`manga_id`)")
    }
}
