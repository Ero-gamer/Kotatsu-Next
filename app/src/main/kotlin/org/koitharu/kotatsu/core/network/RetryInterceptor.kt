package org.koitharu.kotatsu.core.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.closeQuietly
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Innermost application interceptor: retries idempotent GET/HEAD requests on transient
 * transport failures (timeout, reset, EOF) and 502/504 with short exponential backoff.
 *
 * Deliberately NOT retried: 503 (Cloudflare challenge — [CloudFlareInterceptor] must see it),
 * 429 (handled by [RateLimitInterceptor]), any response carrying Retry-After, DNS/SSL
 * failures (deterministic), and cancelled calls.
 */
class RetryInterceptor(
    private val maxRetries: Int = 2,
    private val baseDelayMs: Long = 400L,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" && request.method != "HEAD") {
            return chain.proceed(request)
        }
        var attempt = 0
        while (true) {
            val call = chain.call()
            try {
                val response = chain.proceed(request)
                val retryable =
                    (response.code == 502 || response.code == 504) &&
                        response.header(CommonHeaders.RETRY_AFTER) == null
                if (!retryable || attempt >= maxRetries || call.isCanceled()) {
                    return response
                }
                response.closeQuietly()
            } catch (e: IOException) {
                if (attempt >= maxRetries || call.isCanceled() || !e.isTransient()) {
                    throw e
                }
            }
            attempt++
            try {
                Thread.sleep(baseDelayMs shl (attempt - 1))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Retry interrupted")
            }
            if (call.isCanceled()) {
                throw IOException("Canceled")
            }
        }
    }

    /**
     * Timeouts, resets, EOF and a connection that went away (HTTP/2 stream reset, ping timeout, shutdown of a stale
     * pooled socket) are transient. DNS, TLS and cancellation are deterministic: no retry.
     */
    private fun IOException.isTransient(): Boolean = when (this) {
        is SocketTimeoutException, is SocketException, is EOFException -> true
        is UnknownHostException, is SSLException -> false
        is InterruptedIOException -> false
        else -> javaClass.simpleName.let { it == "StreamResetException" || it == "ConnectionShutdownException" }
    }
}
