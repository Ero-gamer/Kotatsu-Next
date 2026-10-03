package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `cf_deband`: intensity for the new deband filter (f3kdb/flash3kyuu_deband's documented
 * "square" mode, independently implemented from its published documentation — see
 * `deband.frag`'s doc comment for why this is not GPL-encumbered). A genuinely new filter, not a
 * replacement for anything — no prior column's value needs to be carried over.
 */
class Migration36To37 : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_deband` REAL NOT NULL DEFAULT 0")
    }
}
