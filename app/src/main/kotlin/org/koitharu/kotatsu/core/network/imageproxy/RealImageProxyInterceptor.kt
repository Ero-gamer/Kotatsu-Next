package org.koitharu.kotatsu.core.network.imageproxy

import coil3.intercept.Interceptor as CoilInterceptor
import coil3.request.ImageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.ensureSuccess
import org.koitharu.kotatsu.parsers.util.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealImageProxyInterceptor @Inject constructor(
	private val settings: AppSettings,
) : ImageProxyInterceptor {

	private val scope = CoroutineScope(Dispatchers.Default)

	// settings.observe() filters to KEY_IMAGES_PROXY changes only, emits null on start
	// so the delegate is always rebuilt fresh when the flow starts or the setting changes.
	private val delegate: StateFlow<BaseImageProxyInterceptor?> = settings.observe(
		AppSettings.KEY_IMAGES_PROXY,
	)
		.map { createDelegate() }
		.stateIn(scope, SharingStarted.Eagerly, createDelegate())

	/**
	 * Called by Coil for thumbnail/cover image loading.
	 * Delegates to the active proxy if one is selected, otherwise passes through.
	 */
	override suspend fun intercept(chain: CoilInterceptor.Chain): ImageResult {
		return delegate.value?.intercept(chain) ?: chain.proceed()
	}

	/**
	 * Called by PageLoader, MangaPageFetcher, DownloadWorker for raw page fetching.
	 * Delegates to the active proxy if one is selected, otherwise direct OkHttp call.
	 */
	override suspend fun interceptPageRequest(request: Request, okHttp: OkHttpClient): Response {
		return delegate.value?.interceptPageRequest(request, okHttp)
			?: okHttp.newCall(request).await().ensureSuccess()
	}

	private fun createDelegate(): BaseImageProxyInterceptor? {
		// Values must match constants.xml values_image_proxies array:
		// -1 = none, 0 = wsrv.nl, 1 = 0ms.dev
		return when (settings.imagesProxy) {
			0 -> WsrvNlProxyInterceptor()
			1 -> ZeroMsProxyInterceptor()
			else -> null  // -1 = disabled, or any unknown/stale value
		}
	}
}
