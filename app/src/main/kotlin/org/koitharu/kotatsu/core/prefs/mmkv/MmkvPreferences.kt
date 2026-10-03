package org.koitharu.kotatsu.core.prefs.mmkv

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.tencent.mmkv.MMKV

/**
 * Entry point for MMKV-backed preferences. [open] returns ONE shared instance per [id] for the
 * process (so every consumer sees the same snapshot and listeners), migrating the legacy
 * SharedPreferences XML file into MMKV on first use.
 *
 * Safety net: if MMKV cannot be loaded or the migration cannot be verified, the **legacy
 * SharedPreferences** is returned instead — settings are never lost, only not accelerated.
 */
internal object MmkvPreferences {
    private const val TAG = "MmkvPreferences"

    private val instances = HashMap<String, SharedPreferences>()
    private var mmkvReady = false

    /** The app-wide default preferences (what `PreferenceManager.getDefaultSharedPreferences` returned). */
    fun openAppSettings(context: Context): SharedPreferences = open(context, "app_settings", context.packageName + "_preferences")

    @Synchronized
    fun open(
        context: Context,
        id: String,
        legacyName: String,
    ): SharedPreferences {
        instances[id]?.let { return it }
        val app = context.applicationContext
        val prefs =
            try {
                migrateOrOpen(app, id, legacyName)
            } catch (e: Throwable) {
                // includes UnsatisfiedLinkError from a failed native load
                Log.e(TAG, "MMKV unavailable for '$id', using SharedPreferences", e)
                app.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            }
        instances[id] = prefs
        return prefs
    }

    private fun migrateOrOpen(
        app: Context,
        id: String,
        legacyName: String,
    ): SharedPreferences {
        if (!mmkvReady) {
            MMKV.initialize(app)
            mmkvReady = true
        }
        val store = MmkvKeyValueStore(MMKV.mmkvWithID(id))
        val prefs = MmkvSharedPreferences(store)
        if (prefs.isMigrated()) return prefs

        val legacy = app.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val legacyValues = legacy.all
        prefs.resetStorage()
        if (!prefs.importAll(legacyValues)) {
            prefs.resetStorage()
            error("import into MMKV failed")
        }
        // Verify against a fresh read of what is actually persisted before touching the legacy file.
        if (MmkvSharedPreferences(store).all != legacyValues) {
            prefs.resetStorage()
            error("verification of the MMKV copy failed")
        }
        if (!prefs.markMigrated()) {
            prefs.resetStorage()
            error("could not mark migration complete")
        }
        legacy.edit().clear().commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            app.deleteSharedPreferences(legacyName)
        }
        return prefs
    }
}
