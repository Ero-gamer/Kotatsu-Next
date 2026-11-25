package org.koitharu.kotatsu.core.network.webview.adblock

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.WorkerThread
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.sink
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.isNotEmpty
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.renameReplacing
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.requireBody
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@Reusable
class AdBlock @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {

    private var rules: RulesList? = null

    /** After a failed parse (list not downloaded yet) no new attempt is made before this time. */
    private var nextParseAt = 0L

    @WorkerThread
    fun shouldLoadUrl(url: String, baseUrl: String?): Boolean {
        return shouldLoadUrl(
            url.lowercase().toHttpUrlOrNull() ?: return true,
            baseUrl?.lowercase()?.toHttpUrlOrNull(),
        )
    }

    @WorkerThread
    fun shouldLoadUrl(url: HttpUrl, baseUrl: HttpUrl?): Boolean {
        if (!settings.isAdBlockEnabled) {
            return true
        }
        return getRules()?.let {
            val rule = it[url, baseUrl]
            if (rule != null) {
                Log.i(TAG, "Blocked $url by $rule")
            }
            rule == null
        } ?: true
    }

    private fun getRules(): RulesList? = synchronized(this) {
        rules ?: run {
            val now = SystemClock.elapsedRealtime()
            if (now < nextParseAt) return@run null
            parseRules().also {
                rules = it
                if (it == null) nextParseAt = now + PARSE_RETRY_MS
            }
        }
    }

    @WorkerThread
    private fun parseRules() = runCatchingCancellable {
        listFile(context).useLines { lines ->
            val rules = RulesList()
            lines.forEach { line -> rules.add(line) }
            rules.trimToSize()
            rules
        }
    }.onFailure { e ->
        e.printStackTraceDebug()
    }.getOrNull()

    class Updater @Inject constructor(
        @ApplicationContext private val context: Context,
        @BaseHttpClient private val okHttpClient: OkHttpClient,
    ) {

        suspend fun updateList() {
            val file = listFile(context)
            val dateFormat = SimpleDateFormat(CommonHeaders.DATE_FORMAT, Locale.ENGLISH)
            val requestBuilder = Request.Builder()
                .url(EASYLIST_URL)
                .get()
            if (file.exists() && file.isNotEmpty()) {
                val lastModified = file.lastModified()
                requestBuilder.header(CommonHeaders.IF_MODIFIED_SINCE, dateFormat.format(Date(lastModified)))
            }
            okHttpClient.newCall(
                requestBuilder.build(),
            ).await().use { response ->
                if (response.code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    return
                }
                val lastModified = response.header(CommonHeaders.LAST_MODIFIED)?.let {
                    runCatching {
                        dateFormat.parse(it)
                    }.getOrNull()
                }?.time ?: System.currentTimeMillis()
                // Download beside the list and swap it in: a dropped connection must not leave a truncated list.
                val part = File(file.parentFile, file.name + ".tmp")
                try {
                    response.requireBody().source().use { source ->
                        part.sink().use { sink ->
                            source.readAll(sink)
                        }
                    }
                    part.setLastModified(lastModified)
                    part.renameReplacing(file)
                } catch (e: Throwable) {
                    part.delete()
                    throw e
                }
            }
        }
    }

    private companion object {

        fun listFile(context: Context): File {
            val root = File(context.externalCacheDir ?: context.cacheDir, LIST_DIR)
            root.mkdir()
            return File(root, LIST_FILENAME)
        }

        private const val LIST_FILENAME = "easylist.txt"
        private const val LIST_DIR = "adblock"
        private const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
        private const val TAG = "AdBlock"
        private const val PARSE_RETRY_MS = 5 * 60 * 1000L
    }
}
