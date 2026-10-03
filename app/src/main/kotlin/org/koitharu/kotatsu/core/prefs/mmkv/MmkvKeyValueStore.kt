package org.koitharu.kotatsu.core.prefs.mmkv

import com.tencent.mmkv.MMKV

/** Production [KeyValueStore]: a thin, allocation-free adapter over one [MMKV] instance. */
internal class MmkvKeyValueStore(
    private val kv: MMKV,
) : KeyValueStore {
    override fun allKeys(): Array<String> = kv.allKeys() ?: emptyArray()

    override fun getString(key: String): String? = kv.decodeString(key, null)

    override fun putString(
        key: String,
        value: String,
    ): Boolean = kv.encode(key, value)

    override fun removeKeys(keys: Array<String>) {
        when (keys.size) {
            0 -> Unit
            1 -> kv.removeValueForKey(keys[0])
            else -> kv.removeValuesForKeys(keys)
        }
    }

    override fun clearAll() = kv.clearAll()
}
