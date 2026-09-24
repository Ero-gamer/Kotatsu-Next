package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Splits the single sharpen intensity + mode selector into independent per-filter intensities.
 * The legacy `cf_sharpening` / `cf_sharpen_mode` columns are kept in place (never dropped) and
 * simply stop being read. `cf_catmull_rom` / `cf_bspline` are also added and kept as unused
 * legacy columns: an early build shipped them as decode-time filters before they became the
 * draw-time scaler setting, and removing them now would break databases already migrated.
 *
 * Existing values are carried over: mode 1 (RCAS + USM) -> `cf_rcas_usm`,
 * mode 2 (Adaptive, smoothstep) -> `cf_adaptive_smoothstep`, both using the old intensity.
 */
class Migration34To35 : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_rcas_usm` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_adaptive_smoothstep` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_adaptive_sigmoid` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_catmull_rom` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_bspline` REAL NOT NULL DEFAULT 0")
        db.execSQL("UPDATE preferences SET cf_rcas_usm = cf_sharpening WHERE cf_sharpen_mode = 1")
        db.execSQL("UPDATE preferences SET cf_adaptive_smoothstep = cf_sharpening WHERE cf_sharpen_mode = 2")
    }
}
