package org.koitharu.kotatsu.core.network.imageproxy

import android.os.SystemClock
import android.util.Log
import androidx.collection.ArraySet
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.exceptions.CloudFlareBlockedException
import org.koitharu.kotatsu.core.util.ext.ensureSuccess
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.isHttpOrHttps
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.EOFException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

abstract class BaseImageProxyInterceptor : ImageProxyInterceptor {

    private val blacklist = Collections.synchronizedSet(ArraySet<String>())

    /**
     * Circuit breaker: when the proxy itself is unreachable, every image would wait out its timeout before the
     * direct fallback. After [MAX_PROXY_FAILURES] network failures in a row (each rescued by the direct request)
     * the proxy is skipped for [PROXY_DOWN_MS].
     */
    private val proxyFailures = AtomicInteger()

    @Volatile
    private var proxyDownUntil = 0L

    private fun isProxyDown() = SystemClock.elapsedRealtime() < proxyDownUntil

    private fun onProxyFailure(error: Throwable) {
        if (error.isNetworkFailure() && proxyFailures.incrementAndGet() >= MAX_PROXY_FAILURES) {
            proxyFailures.set(0)
            proxyDownUntil = SystemClock.elapsedRealtime() + PROXY_DOWN_MS
        }
    }

    private fun Throwable.isNetworkFailure(): Boolean = this is SocketTimeoutException ||
        this is SocketException || this is UnknownHostException || this is SSLException || this is EOFException

    final override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url: HttpUrl? = when (val data = request.data) {
            is HttpUrl -> data
            is String -> data.toHttpUrlOrNull()
            else -> null
        }
        if (url == null || !url.isHttpOrHttps || url.host in blacklist || isProxyDown()) {
            return chain.proceed()
        }
        val newRequest = onInterceptImageRequest(request, url)
        return when (val result = chain.withRequest(newRequest).proceed()) {
            is SuccessResult -> result.also { proxyFailures.set(0) }

            is ErrorResult -> {
                logDebug(result.throwable, newRequest.data)
                chain.proceed().also {
                    if (it is SuccessResult) {
                        if (result.throwable.isBlockedByServer()) {
                            blacklist.add(url.host)
                        } else {
                            onProxyFailure(result.throwable)
                        }
                    }
                }
            }
        }
    }

    final override suspend fun interceptPageRequest(request: Request, okHttp: OkHttpClient): Response {
        if (isProxyDown()) {
            return okHttp.doCall(request)
        }
        val newRequest = onInterceptPageRequest(request)
        return runCatchingCancellable {
            okHttp.doCall(newRequest).also { proxyFailures.set(0) }
        }.recover { error ->
            logDebug(error, newRequest.url)
            okHttp.doCall(request).also {
                if (error.isBlockedByServer()) {
                    blacklist.add(request.url.host)
                } else {
                    onProxyFailure(error)
                }
            }
        }.getOrThrow()
    }

    protected abstract suspend fun onInterceptImageRequest(request: ImageRequest, url: HttpUrl): ImageRequest

    protected abstract suspend fun onInterceptPageRequest(request: Request): Request

    private suspend fun OkHttpClient.doCall(request: Request): Response = newCall(request).await().ensureSuccess()

    private fun logDebug(e: Throwable, url: Any) {
        if (BuildConfig.DEBUG) {
            Log.w("ImageProxy", "${e.message}: $url", e)
        }
    }

    private fun Throwable.isBlockedByServer(): Boolean = this is CloudFlareBlockedException ||
        (this is HttpException && response.code == HttpURLConnection.HTTP_FORBIDDEN) ||
        (this is HttpStatusException && statusCode == HttpURLConnection.HTTP_FORBIDDEN)

    private companion object {
        const val MAX_PROXY_FAILURES = 3
        const val PROXY_DOWN_MS = 2 * 60 * 1000L
    }
}
