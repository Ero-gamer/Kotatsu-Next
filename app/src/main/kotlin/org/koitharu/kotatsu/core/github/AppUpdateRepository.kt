package org.koitharu.kotatsu.core.github

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.os.AppValidator
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.asArrayList
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val CONTENT_TYPE_APK = "application/vnd.android.package-archive"
private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

@Singleton
class AppUpdateRepository @Inject constructor(
    // Reserved for a future release-signature gate on self-updates; see AppValidator.isOriginalApp.
    @Suppress("UnusedPrivateProperty")
    private val appValidator: AppValidator,
    private val settings: AppSettings,
    @BaseHttpClient private val okHttp: OkHttpClient,
    @ApplicationContext context: Context,
) {

    private val availableUpdate = MutableStateFlow<AppVersion?>(null)

    @Volatile
    private var lastCheckAt = 0L
    private val releasesUrl = buildString {
        append("https://api.github.com/repos/")
        append(context.getString(R.string.github_updates_repo))
        append("/releases?page=1&per_page=10")
    }

    val isUpdateAvailable: Boolean
        get() = availableUpdate.value != null

    fun observeAvailableUpdate() = availableUpdate.asStateFlow()

    suspend fun getAvailableVersions(): List<AppVersion> {
        val request = Request.Builder()
            .get()
            .url(releasesUrl)
        val jsonArray = okHttp.newCall(request.build()).await().parseJsonArray()
        return jsonArray.mapJSONNotNull { json ->
            val asset = json.optJSONArray("assets")?.find { jo ->
                jo.optString("content_type") == CONTENT_TYPE_APK
            } ?: return@mapJSONNotNull null
            AppVersion(
                id = json.getLong("id"),
                url = json.getString("html_url"),
                name = json.getString("tag_name").removePrefix("v"),
                apkSize = asset.getLong("size"),
                apkUrl = asset.getString("browser_download_url"),
                description = json.getString("body"),
            )
        }
    }

    /** Startup check: GitHub allows 60 anonymous requests per hour, so a new screen does not ask again for hours. */
    suspend fun fetchUpdateIfDue(): AppVersion? {
        val last = lastCheckAt
        if (last != 0L && SystemClock.elapsedRealtime() - last < CHECK_INTERVAL_MS) {
            return availableUpdate.value
        }
        return fetchUpdate()
    }

    suspend fun fetchUpdate(): AppVersion? = withContext(Dispatchers.Default) {
        if (!isUpdateSupported()) {
            return@withContext null
        }
        runCatchingCancellable {
            val currentVersion = VersionId(BuildConfig.VERSION_NAME)
            val available = getAvailableVersions().asArrayList()
            if (currentVersion.isStable && !settings.isUnstableUpdatesAllowed) {
                available.retainAll { it.versionId.isStable }
            }
            available.maxByOrNull { it.versionId }?.takeIf { it.versionId > currentVersion }
        }.onFailure {
            it.printStackTraceDebug()
        }.onSuccess {
            lastCheckAt = SystemClock.elapsedRealtime()
            availableUpdate.value = it
        }.getOrNull()
    }

    @Suppress("KotlinConstantConditions", "FunctionOnlyReturningConstant")
    fun isUpdateSupported(): Boolean = true

    private inline fun JSONArray.find(predicate: (JSONObject) -> Boolean): JSONObject? {
        val size = length()
        for (i in 0 until size) {
            val jo = getJSONObject(i)
            if (predicate(jo)) {
                return jo
            }
        }
        return null
    }
}
