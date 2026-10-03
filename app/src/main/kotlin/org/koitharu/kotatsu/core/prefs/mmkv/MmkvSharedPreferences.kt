package org.koitharu.kotatsu.core.prefs.mmkv

import android.content.SharedPreferences
import java.util.Collections
import java.util.WeakHashMap

/**
 * A [SharedPreferences] backed by a [KeyValueStore] (MMKV in production) with the semantics the
 * app relies on:
 *
 * - **Lock-free reads** from an immutable snapshot map, like `SharedPreferencesImpl`'s in-memory map.
 * - **Type safety**: a typed getter on a key holding another type throws [ClassCastException]
 *   (the app's `getFloatCompat`/`getLongCompat` helpers depend on that).
 * - **`getAll()`** (backup export) and **change listeners** (live reader settings), both of which
 *   MMKV's own SharedPreferences adapter deliberately does not support.
 * - **Editor semantics of Android**: `clear()` is applied first whatever the call order; `null`
 *   for `putString`/`putStringSet` removes the key; `apply()` updates memory immediately;
 *   listeners hear only about keys whose value really changed (or that were removed), on the main
 *   thread, and are held weakly.
 *
 * Not provided (and not needed here): atomicity of one edit across several keys on a process
 * crash. Each key is persisted on its own as one self-describing string ([PrefCodec]).
 */
internal class MmkvSharedPreferences(
    private val store: KeyValueStore,
    private val dispatch: (Runnable) -> Unit = MainThreadDispatcher::dispatch,
) : SharedPreferences {
    @Volatile
    private var snapshot: Map<String, Any> = load()

    private val writeLock = Any()
    private val listeners = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Any>()

    // ── Reads ────────────────────────────────────────────────────────────────

    override fun getAll(): MutableMap<String, *> = HashMap(snapshot)

    override fun getString(
        key: String,
        defValue: String?,
    ): String? {
        val v = snapshot[key] ?: return defValue
        return v as? String ?: throw typeMismatch(key, v, "String")
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        val v = snapshot[key] ?: return defValues
        if (v !is Set<*>) throw typeMismatch(key, v, "Set<String>")
        return v as MutableSet<String>
    }

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = get(key, defValue, Int::class.javaObjectType)

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = get(key, defValue, Long::class.javaObjectType)

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = get(key, defValue, Float::class.javaObjectType)

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = get(key, defValue, Boolean::class.javaObjectType)

    override fun contains(key: String): Boolean = snapshot.containsKey(key)

    private fun <T> get(
        key: String,
        defValue: T,
        type: Class<T>,
    ): T {
        val v = snapshot[key] ?: return defValue
        if (!type.isInstance(v)) throw typeMismatch(key, v, type.simpleName)
        return type.cast(v) as T
    }

    private fun typeMismatch(
        key: String,
        actual: Any,
        expected: String,
    ) = ClassCastException("Preference '$key' holds ${actual.javaClass.simpleName}, not $expected")

    // ── Listeners ────────────────────────────────────────────────────────────

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(listeners) { listeners[listener] = this }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    /**
     * Replaces the whole content with [values] (a legacy `SharedPreferences.getAll()`), without
     * notifying listeners. Returns `false` if the store rejected any write. Used by migration only.
     */
    fun importAll(values: Map<String, *>): Boolean = synchronized(writeLock) {
        val next = HashMap<String, Any>(values.size * 2)
        var ok = true
        for ((key, value) in values) {
            if (value == null || key.startsWith(INTERNAL_PREFIX)) continue
            val stored = normalize(value)
            next[key] = stored
            ok = store.putString(key, PrefCodec.encode(stored)) && ok
        }
        snapshot = next
        ok
    }

    /** Empties the store and the in-memory content, without notifying listeners. Migration only. */
    fun resetStorage() = synchronized(writeLock) {
        store.clearAll()
        snapshot = emptyMap()
    }

    fun isMigrated(): Boolean = store.getString(MIGRATED_KEY) == MIGRATED_VALUE

    fun markMigrated(): Boolean = store.putString(MIGRATED_KEY, MIGRATED_VALUE)

    private fun load(): Map<String, Any> {
        val keys = store.allKeys()
        val map = HashMap<String, Any>(keys.size * 2)
        for (key in keys) {
            if (key.startsWith(INTERNAL_PREFIX)) continue
            val value = store.getString(key)?.let(PrefCodec::decode) ?: continue
            map[key] = value
        }
        return map
    }

    /** Immutable defensive copy for sets; every other supported type is already immutable. */
    private fun normalize(value: Any): Any = if (value is Set<*>) {
        Collections.unmodifiableSet(LinkedHashSet<Any?>(value))
    } else {
        value
    }

    /** Applies one edit; returns whether every store write was accepted. */
    private fun applyEdit(
        pending: Map<String, Any>,
        clear: Boolean,
    ): Boolean {
        val changed: List<String>
        var ok = true
        synchronized(writeLock) {
            val old = snapshot
            val next = HashMap(old)
            if (clear) next.clear()
            for ((key, value) in pending) {
                if (value === REMOVED) next.remove(key) else next[key] = value
            }
            val removed = ArrayList<String>()
            val updated = ArrayList<String>()
            for (key in old.keys) if (!next.containsKey(key)) removed.add(key)
            for ((key, value) in next) if (old[key] != value) updated.add(key)
            if (removed.isEmpty() && updated.isEmpty()) return true

            if (removed.isNotEmpty()) store.removeKeys(removed.toTypedArray())
            for (key in updated) ok = store.putString(key, PrefCodec.encode(next.getValue(key))) && ok
            snapshot = next
            changed = removed + updated
        }
        notifyChanged(changed)
        return ok
    }

    private fun notifyChanged(keys: List<String>) {
        val targets = synchronized(listeners) { ArrayList(listeners.keys) }
        if (targets.isEmpty()) return
        dispatch(
            Runnable {
                for (key in keys) {
                    for (listener in targets) listener.onSharedPreferenceChanged(this, key)
                }
            },
        )
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any>()
        private var clear = false

        @Synchronized
        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor {
            pending[key] = value ?: REMOVED
            return this
        }

        @Synchronized
        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            pending[key] = if (values == null) REMOVED else normalize(values)
            return this
        }

        @Synchronized
        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        @Synchronized
        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        @Synchronized
        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        @Synchronized
        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        @Synchronized
        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = REMOVED
            return this
        }

        @Synchronized
        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            val (ops, cleared) = drain()
            return applyEdit(ops, cleared)
        }

        override fun apply() {
            val (ops, cleared) = drain()
            applyEdit(ops, cleared)
        }

        @Synchronized
        private fun drain(): Pair<Map<String, Any>, Boolean> {
            val ops = LinkedHashMap(pending)
            val cleared = clear
            pending.clear()
            clear = false
            return ops to cleared
        }
    }

    companion object {
        /** Keys starting with this are the wrapper's own bookkeeping, never user preferences. */
        const val INTERNAL_PREFIX = "\u0001"
        private const val MIGRATED_KEY = INTERNAL_PREFIX + "migrated"
        private const val MIGRATED_VALUE = "1"

        private val REMOVED = Any()
    }
}
