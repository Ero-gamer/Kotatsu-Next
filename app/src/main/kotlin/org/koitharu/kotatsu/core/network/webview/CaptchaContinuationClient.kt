package org.koitharu.kotatsu.core.network.webview

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.toString

class CaptchaContinuationClient(
    private val cookieJar: MutableCookieJar,
    private val targetUrl: String,
    continuation: Continuation<Unit>,
) : ContinuationResumeWebViewClient(continuation) {

    // One client for all intercepted requests (a client per request leaked a pool and threads each time). Plain on
    // purpose: no interceptors, so the challenge page itself reaches the WebView instead of being turned into an error.
    private val client = sharedClient

    private val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)

    override fun onPageFinished(view: WebView?, url: String?) = Unit

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        checkClearance(view)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request == null) return null

        try {
            // Skip POST requests
            if (request.method == "POST") {
                return super.shouldInterceptRequest(view, request)
            }

            val requestBuilder = Request.Builder()
                .url(request.url.toString())
                .method(request.method, null)

            val blockedHeaders = setOf(
                "sec-ch-ua",
                "sec-ch-ua-mobile",
                "sec-ch-ua-platform",
                "x-requested-with",
            )
            // Add all headers except blocked
            request.requestHeaders.forEach { (key, value) ->
                if (key.lowercase() !in blockedHeaders) {
                    requestBuilder.addHeader(key, value)
                }
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val contentType = response.header("Content-Type", "text/html")
            val mimeType = contentType?.split(";")?.get(0)?.trim() ?: "text/html"
            val charset = contentType?.substringAfter("charset=", "UTF-8")?.trim() ?: "UTF-8"

            val body = response.body ?: run {
                response.close()
                return null
            }
            // The WebView reads and closes the stream.
            return WebResourceResponse(mimeType, charset, body.byteStream()).apply {
                val headers = mutableMapOf<String, String>()
                response.headers.forEach { headers[it.first] = it.second }
                setResponseHeaders(headers)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun checkClearance(view: WebView?) {
        val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
        if (clearance != null && clearance != oldClearance) {
            resumeContinuation(view)
        }
    }

    private companion object {

        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
