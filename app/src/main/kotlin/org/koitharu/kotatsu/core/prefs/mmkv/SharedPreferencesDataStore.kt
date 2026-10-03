package org.koitharu.kotatsu.core.prefs.mmkv

import android.content.SharedPreferences
import androidx.preference.PreferenceDataStore

/**
 * Routes `androidx.preference` screens to a [SharedPreferences] that is not the one owned by their
 * `PreferenceManager` (here: the MMKV-backed app settings). Set it on a fragment's
 * `preferenceManager` before inflating, and every Preference reads and writes through it.
 */
internal class SharedPreferencesDataStore(
    private val prefs: SharedPreferences,
) : PreferenceDataStore() {
    override fun putString(
        key: String,
        value: String?,
    ) = prefs.edit().putString(key, value).apply()

    override fun putStringSet(
        key: String,
        values: Set<String>?,
    ) = prefs.edit().putStringSet(key, values).apply()

    override fun putInt(
        key: String,
        value: Int,
    ) = prefs.edit().putInt(key, value).apply()

    override fun putLong(
        key: String,
        value: Long,
    ) = prefs.edit().putLong(key, value).apply()

    override fun putFloat(
        key: String,
        value: Float,
    ) = prefs.edit().putFloat(key, value).apply()

    override fun putBoolean(
        key: String,
        value: Boolean,
    ) = prefs.edit().putBoolean(key, value).apply()

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = prefs.getString(key, defValue)

    override fun getStringSet(
        key: String,
        defValues: Set<String>?,
    ): Set<String>? = prefs.getStringSet(key, defValues)

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = prefs.getInt(key, defValue)

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = prefs.getLong(key, defValue)

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = prefs.getFloat(key, defValue)

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = prefs.getBoolean(key, defValue)
}
