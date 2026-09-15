package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds explicit columns for the GPU sharpen-mode choice and line-darkening switch.
 * Both were previously either inferred (sharpen mode, from a float threshold) or
 * entirely unavailable (line darkening) — see ReaderColorFilter for the honest
 * replacement fields.
 */
class Migration32To33 : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_sharpen_mode` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_line_darken` INTEGER NOT NULL DEFAULT 0")
    }
}
