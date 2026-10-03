package org.koitharu.kotatsu.core.prefs.mmkv

/**
 * Self-describing string encoding of the six SharedPreferences value types: the first character is
 * the type tag, the rest the payload.
 *
 * `s<text>` String, `i<int>` Int, `l<long>` Long, `f<float>` Float (Kotlin's shortest round-trip
 * `toString`), `b0`/`b1` Boolean, `S` followed by `<length>:<text>` per element: a String set (length-prefixed, so any
 * character — separators included — is safe in an element).
 *
 * [decode] returns `null` for anything malformed instead of throwing: one damaged entry must cost
 * that single setting (its default applies again), never the whole store.
 */
internal object PrefCodec {
    fun encode(value: Any): String = when (value) {
        is String -> "s$value"
        is Int -> "i$value"
        is Long -> "l$value"
        is Float -> "f$value"
        is Boolean -> if (value) "b1" else "b0"
        is Set<*> -> encodeSet(value)
        else -> throw IllegalArgumentException("Unsupported preference type: ${value.javaClass.name}")
    }

    fun decode(raw: String): Any? {
        if (raw.isEmpty()) return null
        return try {
            when (raw[0]) {
                's' -> {
                    raw.substring(1)
                }

                'i' -> {
                    raw.substring(1).toInt()
                }

                'l' -> {
                    raw.substring(1).toLong()
                }

                'f' -> {
                    raw.substring(1).toFloat()
                }

                'b' -> {
                    when (raw) {
                        "b1" -> true
                        "b0" -> false
                        else -> null
                    }
                }

                'S' -> {
                    decodeSet(raw)
                }

                else -> {
                    null
                }
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun encodeSet(set: Set<*>): String {
        val sb = StringBuilder(1 + set.size * 8).append('S')
        for (e in set) {
            val s = e as? String ?: throw IllegalArgumentException("String set contains a non-string element")
            sb.append(s.length).append(':').append(s)
        }
        return sb.toString()
    }

    private fun decodeSet(raw: String): Set<String>? {
        val result = LinkedHashSet<String>()
        var i = 1
        while (i < raw.length) {
            val colon = raw.indexOf(':', i)
            if (colon < 0) return null
            val len = raw.substring(i, colon).toIntOrNull() ?: return null
            val end = colon + 1 + len
            if (len < 0 || end > raw.length) return null
            result.add(raw.substring(colon + 1, end))
            i = end
        }
        return result
    }
}
