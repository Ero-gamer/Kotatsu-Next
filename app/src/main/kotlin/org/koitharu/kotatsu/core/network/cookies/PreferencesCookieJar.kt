package org.koitharu.kotatsu.core.network.cookies

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import androidx.collection.ArrayMap
import androidx.core.content.edit
import androidx.core.util.Predicate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug

private const val PREFS_NAME = "cookies"

class PreferencesCookieJar(
    context: Context,
) : MutableCookieJar {

    private val cache = ArrayMap<String, CookieWrapper>()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var isLoaded = false

    @WorkerThread
    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        loadPersistent()
        val expired = HashSet<String>()
        val result = ArrayList<Cookie>()
        for ((key, cookie) in cache) {
            if (cookie.isExpired()) {
                expired += key
            } else if (cookie.cookie.matches(url)) {
                result += cookie.cookie
            }
        }
        if (expired.isNotEmpty()) {
            cache.removeAll(expired)
            removePersistent(expired)
        }
        return result
    }

    @WorkerThread
    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) {
            return
        }
        // Load first: a lazy load after this save would overwrite the newer in-memory cookie with the stored one.
        loadPersistent()
        var editor: SharedPreferences.Editor? = null
        for (cookie in cookies) {
            val wrapped = CookieWrapper(cookie)
            val key = wrapped.key()
            val old = cache.put(key, wrapped)
            if (cookie.persistent) {
                val encoded = wrapped.encode()
                // Most responses repeat the cookies already stored: write only what changed.
                if (old == null || !old.cookie.persistent || old.encode() != encoded) {
                    (editor ?: prefs.edit().also { editor = it }).putString(key, encoded)
                }
            }
        }
        editor?.apply() // memory is already updated; the disk write need not block the network thread
    }

    @Synchronized
    @WorkerThread
    override fun removeCookies(url: HttpUrl, predicate: Predicate<Cookie>?) {
        loadPersistent()
        val toRemove = HashSet<String>()
        for ((key, cookie) in cache) {
            if (cookie.isExpired() || cookie.cookie.matches(url)) {
                if (predicate == null || predicate.test(cookie.cookie)) {
                    toRemove += key
                }
            }
        }
        if (toRemove.isNotEmpty()) {
            cache.removeAll(toRemove)
            removePersistent(toRemove)
        }
    }

    override suspend fun clear(): Boolean {
        cache.clear()
        withContext(Dispatchers.IO) {
            prefs.edit(commit = true) { clear() }
        }
        return true
    }

    @Synchronized
    private fun loadPersistent() {
        if (!isLoaded) {
            val map = prefs.all
            cache.ensureCapacity(map.size)
            for ((k, v) in map) {
                val cookie = try {
                    CookieWrapper(v as String)
                } catch (e: Exception) {
                    e.printStackTraceDebug()
                    continue
                }
                cache[k] = cookie
            }
            isLoaded = true
        }
    }

    private fun removePersistent(keys: Collection<String>) {
        prefs.edit {
            for (key in keys) {
                remove(key)
            }
        }
    }
}
