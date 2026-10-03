package org.koitharu.kotatsu.core.prefs.mmkv

/**
 * The minimal persistence contract [MmkvSharedPreferences] needs from its backing store: string
 * keys to string values. Everything SharedPreferences promises beyond that (typed values, type
 * safety, edit batches, change notification, `getAll()`) is implemented — and unit-tested — once,
 * in [MmkvSharedPreferences], independent of the native MMKV library. [MmkvKeyValueStore] is the
 * production implementation.
 *
 * Every setting is ONE key holding ONE self-describing string (see [PrefCodec]), so a single
 * setting can never be left half-written (value without type, or the reverse) by a crash.
 */
internal interface KeyValueStore {
    fun allKeys(): Array<String>

    fun getString(key: String): String?

    /** Returns whether the store accepted the write. */
    fun putString(
        key: String,
        value: String,
    ): Boolean

    fun removeKeys(keys: Array<String>)

    fun clearAll()
}
