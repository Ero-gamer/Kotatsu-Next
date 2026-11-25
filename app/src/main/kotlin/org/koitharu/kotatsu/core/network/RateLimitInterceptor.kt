package org.koitharu.kotatsu.core.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

class RateLimitInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 429) {
            val request = response.request
            response.closeQuietly()
            throw TooManyRequestExceptions(
                url = request.url.toString(),
                retryAfter = response.header(CommonHeaders.RETRY_AFTER)?.parseRetryAfter() ?: 0L,
            )
        }
        return response
    }
}

/**
 * Retry-After header to a delay in milliseconds (what [TooManyRequestExceptions] expects).
 * Accepts delta-seconds or an HTTP date; a past date gives 0, anything unparsable gives 0 (= unknown).
 */
internal fun String.parseRetryAfter(nowMillis: Long = System.currentTimeMillis()): Long {
    val value = trim()
    value.toLongOrNull()?.let { return TimeUnit.SECONDS.toMillis(it.coerceAtLeast(0L)) }
    return try {
        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis)
            .coerceAtLeast(0L)
    } catch (ignored: DateTimeParseException) {
        0L
    }
}
