package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the per-manga "image filters disabled" master switch. Saved filter values are kept in
 * their own columns and are not modified when the switch is toggled.
 */
class Migration33To34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_disabled` INTEGER NOT NULL DEFAULT 0")
    }
}
