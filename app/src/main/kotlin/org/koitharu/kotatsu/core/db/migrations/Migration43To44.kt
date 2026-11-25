package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds `cf_binarize`: strength of the local-threshold black & white (binarize) reader filter. */
class Migration43To44 : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_binarize` REAL NOT NULL DEFAULT 0")
    }
}
