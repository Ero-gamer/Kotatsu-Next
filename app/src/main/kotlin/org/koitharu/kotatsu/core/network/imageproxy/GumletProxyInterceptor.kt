package org.koitharu.kotatsu.core.network.imageproxy

import coil3.request.ImageRequest
import okhttp3.HttpUrl
import okhttp3.Request
import java.net.URLEncoder

class GumletProxyInterceptor : BaseImageProxyInterceptor() {

	private val proxyHost = "ero2.gumlet.io"

	override suspend fun onInterceptImageRequest(request: ImageRequest, url: HttpUrl): ImageRequest {
		if (url.host == proxyHost) return request
		val newUrl = buildProxyUrl(url.toString()) ?: return request
		return request.newBuilder()
			.data(newUrl)
			.build()
	}

	override suspend fun onInterceptPageRequest(request: Request): Request {
		val originalUrl = request.url
		if (originalUrl.host == proxyHost) return request
		val newUrl = buildProxyUrl(originalUrl.toString()) ?: return request
		return request.newBuilder()
			.url(newUrl)
			.build()
	}

	private fun buildProxyUrl(sourceUrl: String): HttpUrl? {
		return try {
			// Step 1: URL-encode the original image URL per Gumlet Fetch CDN format:
			//   https://ero2.gumlet.io/fetch/{URL-encoded-original-image-url}
			//
			// Step 2: We must embed this encoded URL into the OkHttp path WITHOUT
			// OkHttp re-encoding the % characters. The trick is to use HttpUrl.Builder
			// with addEncodedPathSegments(), which treats the input as already-encoded
			// and preserves the % signs as-is.
			val encodedUrl = URLEncoder.encode(sourceUrl, "UTF-8")

			HttpUrl.Builder()
				.scheme("https")
				.host(proxyHost)
				// addEncodedPathSegments keeps existing percent-encoding intact,
				// so https%3A%2F%2F... is preserved and NOT double-encoded to %253A%252F...
				.addEncodedPathSegments("fetch/$encodedUrl")
				.addQueryParameter("format", "webp")
				// Note: quality (q) is intentionally omitted here.
				// Configure it directly on the Gumlet dashboard under your image source settings.
				.build()
		} catch (e: Exception) {
			null
		}
	}
}
