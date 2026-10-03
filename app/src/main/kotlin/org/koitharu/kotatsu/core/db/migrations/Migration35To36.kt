package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Replaces the three mislabeled "sharpen" filters (`cf_rcas_usm` was a clamped unsharp mask, not
 * RCAS; `cf_adaptive_smoothstep` / `cf_adaptive_sigmoid` were homemade edge-weighted contrast
 * curves, not the Adaptive-Sharpen algorithm) with the two real algorithms they were named after:
 * real AMD FidelityFX RCAS (`cf_rcas`) and real bacondither Adaptive-Sharpen
 * (`cf_adaptive_sharpen`, one filter — the old smoothstep/sigmoid split was never two distinct
 * algorithms). The legacy columns are kept in place (never dropped) and simply stop being read.
 *
 * Existing values are carried over on a best-effort basis, since the algorithms underneath are
 * different from what was previously stored: `cf_rcas_usm` -> `cf_rcas` directly, and whichever
 * of `cf_adaptive_smoothstep` / `cf_adaptive_sigmoid` was larger -> `cf_adaptive_sharpen` (the two
 * were near-duplicates of each other, so this keeps the more prominent of the pair rather than
 * summing or averaging them into a value the user never actually chose).
 */
class Migration35To36 : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_rcas` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE preferences ADD COLUMN `cf_adaptive_sharpen` REAL NOT NULL DEFAULT 0")
        db.execSQL("UPDATE preferences SET cf_rcas = cf_rcas_usm")
        db.execSQL(
            "UPDATE preferences SET cf_adaptive_sharpen = " +
                "MAX(cf_adaptive_smoothstep, cf_adaptive_sigmoid)",
        )
    }
}
