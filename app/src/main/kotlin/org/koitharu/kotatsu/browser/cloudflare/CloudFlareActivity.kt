package org.koitharu.kotatsu.browser.cloudflare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.webkit.CookieManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.browser.BaseBrowserActivity
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaAutoResolveCoordinator
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.webview.CF_STATE_JS
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import kotlin.coroutines.resume

@AndroidEntryPoint
open class CloudFlareActivity : BaseBrowserActivity(), CloudFlareCallback {
	protected open val isHiddenAutoResolveActivity = false


	private var pendingResult = RESULT_CANCELED
	private val isAutoResolve: Boolean by lazy { intent?.getBooleanExtra(EXTRA_AUTO_RESOLVE, false) == true }
	private var resultNotified = false
	private val autoRecreateCount: Int by lazy {
		intent?.getIntExtra(EXTRA_AUTO_RECREATE_COUNT, 0) ?: 0
	}
	private var recreateRequested = false
	private var clearanceAtLaunch: String? = null
	private var clearanceUpdateObservedAt = 0L
	private var resolveJob: Job? = null
	private var isHiddenPresentation = false
	private var lastChallengeState: String? = null

	@Inject
	lateinit var cookieJar: MutableCookieJar

	@Inject
	lateinit var captchaHandler: CaptchaHandler

	@Inject
	lateinit var captchaAutoResolveCoordinator: CaptchaAutoResolveCoordinator

	private lateinit var cfClient: CloudFlareClient

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
		if (isHiddenAutoResolveActivity) {
			isHiddenPresentation = true
			// Keep the window focused and the WebView rendered: Turnstile treats a non-focused or
			// detached WebView differently. The translucent theme exposes the previous screen.
			viewBinding.appbar.isGone = true
			viewBinding.root.alpha = HIDDEN_RENDER_ALPHA
			window.setDimAmount(0f)
			window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
			(this as? CloudFlareHiddenActivity)?.let {
				captchaAutoResolveCoordinator.registerHiddenActivity(it)
			}
		} else {
			setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		}
		val url = intent?.dataString
		if (url.isNullOrEmpty()) {
			finishAfterTransition()
			return
		}
		clearanceAtLaunch = CloudFlareHelper.getClearanceCookie(cookieJar, url)

		// Check if source needs header interception
		val needsInterception = shouldUseInterception(source, repository)
		Log.d(TAG, "Source: ${source.name}, needsInterception: $needsInterception")

		cfClient = if (needsInterception) {
			Log.d(TAG, "Using CloudFlareInterceptClient with header filtering")
			CloudFlareInterceptClient(cookieJar, this, url)
		} else {
			Log.d(TAG, "Using regular CloudFlareClient (no interception)")
			CloudFlareClient(cookieJar, this, url)
		}

		viewBinding.webView.webViewClient = cfClient
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null || autoRecreateCount > 0) {
				if (isAutoResolve && autoRecreateCount == 0) {
					url.toHttpUrlOrNull()?.let {
						clearRejectedClearance(it)
						CookieManager.getInstance().flush()
						Log.d(TAG, "Removed rejected cf_clearance before automatic challenge")
					}
				}
				awaitChallengeViewport()
				onTitleChanged(getString(R.string.loading_), url)
				viewBinding.webView.loadUrl(url)
			}
		}
		// A cf_clearance change is not a completion signal. Cloudflare can update it while the
		// interstitial is still running, so only the loaded page state may complete this flow.
		resolveJob = lifecycleScope.launch { runResolveLoop() }
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		if (isHiddenPresentation) return false
		menuInflater.inflate(R.menu.opt_captcha, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_retry -> {
			restartCheck()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun finish() {
		setResult(pendingResult)
		// In auto-resolve mode the originating Fragment / Activity may already be dead, so its
		// ActivityResultLauncher won't deliver the result. Notify the singleton coordinator instead so
		// the result reaches every screen that's still awaiting it.
		if (isAutoResolve && !resultNotified) {
			resultNotified = true
			val sourceName = intent?.getStringExtra(AppRouter.KEY_SOURCE)
			if (sourceName != null) {
				captchaAutoResolveCoordinator.notifyResolveResult(
					MangaSource(sourceName),
					pendingResult == RESULT_OK,
				)
			}
		}
		super.finish()
	}

	override fun onDestroy() {
		(this as? CloudFlareHiddenActivity)?.let {
			captchaAutoResolveCoordinator.unregisterHiddenActivity(it)
		}
		super.onDestroy()
	}

	/** True only while this same WebView should remain invisible and at the top of the task. */
	fun shouldStayHiddenAndFocused(): Boolean =
		isAutoResolve && isHiddenPresentation && !isFinishing && !isDestroyed

	/** Cancels the automatic session without leaving a hidden Activity or WebView behind. */
	fun cancelAutomaticResolve() {
		if (!isAutoResolve || isFinishing || isDestroyed) return
		resolveJob?.cancel()
		viewBinding.webView.stopLoading()
		finishAfterTransition()
	}

	override fun onLoadingStateChanged(isLoading: Boolean) = Unit

	/** Do not start Turnstile until this Activity has the same real viewport as a manual Solve launch. */
	private suspend fun awaitChallengeViewport() {
		while (!viewBinding.webView.hasWindowFocus()) {
			delay(VIEWPORT_POLL_INTERVAL_MS)
		}
		suspendCancellableCoroutine { cont ->
			viewBinding.webView.doOnLayout {
				if (cont.isActive) cont.resume(Unit)
			}
		}
		suspendCancellableCoroutine { cont ->
			viewBinding.webView.postOnAnimation {
				if (cont.isActive) cont.resume(Unit)
			}
		}
	}

	override fun onPageLoaded() {
		viewBinding.progressBar.isInvisible = true
	}

	override fun onLoopDetected() = Unit

	// Record profile priming now, but recreate only if the parent page remains challenged after a grace period.
	override fun onCheckPassed() {
		if (isAutoResolve && autoRecreateCount < MAX_AUTO_RECREATE_COUNT) {
			markClearanceUpdateObserved()
		}
	}

	/**
	 * Drives the visible auto-resolve to completion. Runs on the main thread, so WebView calls are safe.
	 *
	 * The target page must report [CF_STATE_JS] == "ok" for several consecutive probes. Requiring a
	 * stable page avoids accepting the short DOM gap between two managed-challenge stages.
	 *
	 * The page-state probe is a passive, read-only DOM query of the parent challenge page (it cannot reach
	 * into Turnstile's cross-origin iframe), so polling it does not perturb the challenge.
	 */
	private suspend fun runResolveLoop() {
		val retryAt = System.currentTimeMillis() + if (autoRecreateCount < MAX_AUTO_RECREATE_COUNT) {
			AUTO_RETRY_DELAY_MS
		} else {
			MANUAL_FALLBACK_DELAY_MS
		}
		var consecutivePasses = 0
		val requiredStablePasses = if (isHiddenAutoResolveActivity && autoRecreateCount > 0) {
			HIDDEN_RECREATED_STABLE_PASSES
		} else {
			REQUIRED_STABLE_PASSES
		}
		while (true) {
			delay(RESOLVE_POLL_INTERVAL_MS)
			if (isAutoResolve && autoRecreateCount < MAX_AUTO_RECREATE_COUNT) {
				val clearance = intent.dataString?.let {
					CloudFlareHelper.getClearanceCookie(cookieJar, it)
				}
				if (clearance != null && clearance != clearanceAtLaunch) {
					markClearanceUpdateObserved()
				}
			}
			val challengeState = probeChallengeState()
			if (challengeState != lastChallengeState) {
				lastChallengeState = challengeState
				Log.d(
					TAG,
					"Challenge state: $challengeState, url=${viewBinding.webView.url}, title=${viewBinding.webView.title}",
				)
			}
			if (challengeState == CF_STATE_OK) {
				consecutivePasses++
				if (consecutivePasses >= requiredStablePasses) {
					finishSuccess()
					return
				}
			} else {
				consecutivePasses = 0
				val now = System.currentTimeMillis()
				if (
					clearanceUpdateObservedAt != 0L &&
					now - clearanceUpdateObservedAt >= CLEARANCE_RECREATE_GRACE_MS
				) {
					requestAutoRecreate("clearance updated but challenge remained")
					return
				}
				if (
					isAutoResolve &&
					autoRecreateCount < MAX_AUTO_RECREATE_COUNT &&
					now >= retryAt
				) {
					requestAutoRecreate("profile warm-up timeout")
					return
				}
				if (
					isAutoResolve &&
					autoRecreateCount >= MAX_AUTO_RECREATE_COUNT &&
					isHiddenPresentation &&
					now >= retryAt
				) {
					revealForManualCompletion()
				}
			}
		}
	}


	private fun markClearanceUpdateObserved() {
		if (clearanceUpdateObservedAt == 0L) {
			clearanceUpdateObservedAt = System.currentTimeMillis()
		}
	}

	private fun requestAutoRecreate(reason: String) {
		if (recreateRequested || autoRecreateCount >= MAX_AUTO_RECREATE_COUNT) return
		recreateRequested = true
		resolveJob?.cancel()
		CookieManager.getInstance().flush()
		Log.d(TAG, "Recreating automatic challenge after $reason with preserved browser profile")
		intent.putExtra(EXTRA_AUTO_RECREATE_COUNT, autoRecreateCount + 1)
		recreate()
	}

	/** Falls back to manual completion without discarding the warmed browser profile or WebView. */
	private fun revealForManualCompletion() {
		if (!isHiddenPresentation) return
		isHiddenPresentation = false
		viewBinding.root.alpha = 1f
		viewBinding.appbar.isGone = false
		window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		invalidateOptionsMenu()
		Log.d(TAG, "Automatic challenge needs user interaction; revealing the existing solver")
	}
	private suspend fun probeChallengeState(): String = suspendCancellableCoroutine { cont ->
		viewBinding.webView.evaluateJavascript(CF_STATE_JS) { raw ->
			if (cont.isActive) {
				cont.resume(raw?.removeSurrounding("\"") ?: CF_STATE_WAIT)
			}
		}
	}

	private fun finishSuccess() {
		if (pendingResult == RESULT_OK) return
		pendingResult = RESULT_OK
		resolveJob?.cancel()
		lifecycleScope.launch {
			// Persist the final cookie state before the Activity destroys the WebView and before the
			// parser retries its OkHttp request.
			CookieManager.getInstance().flush()
			val source = intent?.getStringExtra(AppRouter.KEY_SOURCE)
			if (source != null) {
				runCatchingCancellable {
					captchaHandler.discard(MangaSource(source))
				}.onFailure {
					it.printStackTraceDebug()
				}
			}
			finishAfterTransition()
		}
	}

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
		setTitle(title)
		supportActionBar?.subtitle = subtitle?.toString()?.toHttpUrlOrNull()?.host.ifNullOrEmpty { subtitle }
	}

	private fun restartCheck() {
		lifecycleScope.launch {
			resolveJob?.cancel()
			viewBinding.webView.stopLoading()
			yield()
			cfClient.reset()
			val targetUrl = intent?.dataString?.toHttpUrlOrNull()
			if (targetUrl != null) {
				clearCfCookies(targetUrl)
				viewBinding.webView.loadUrl(targetUrl.toString())
				resolveJob = lifecycleScope.launch { runResolveLoop() }
			}
		}
	}

	private suspend fun clearRejectedClearance(url: HttpUrl) = runInterruptible(Dispatchers.Default) {
		cookieJar.removeCookies(url) { cookie ->
			cookie.name == CLEARANCE_COOKIE_NAME
		}
	}

	private suspend fun clearCfCookies(url: HttpUrl) = runInterruptible(Dispatchers.Default) {
		cookieJar.removeCookies(url) { cookie ->
			CloudFlareHelper.isCloudFlareCookie(cookie.name)
		}
	}

	private fun shouldUseInterception(source: MangaSource, repository: ParserMangaRepository?): Boolean {
		Log.d(TAG, "shouldUseInterception called for source: ${source.name}")
		Log.d(TAG, "Repository type: ${repository?.javaClass?.simpleName}")

		if (repository !is ParserMangaRepository) {
			Log.d(TAG, "Repository is not ParserMangaRepository, returning false")
			return false
		}

		// Check if parser has InterceptCloudflare ConfigKey
		val configKeys = repository.getConfigKeys()
		Log.d(TAG, "Config keys count: ${configKeys.size}")
		Log.d(TAG, "Config keys: ${configKeys.map { it.javaClass.simpleName }}")

		val interceptKey = configKeys.filterIsInstance<ConfigKey.InterceptCloudflare>().firstOrNull()
		Log.d(TAG, "InterceptCloudflare key found: ${interceptKey != null}")
		if (interceptKey != null) {
			Log.d(TAG, "InterceptCloudflare defaultValue: ${interceptKey.defaultValue}")
		}

		val result = interceptKey?.defaultValue == true
		Log.d(TAG, "Returning: $result")
		return result
	}

	class Contract : ActivityResultContract<CloudFlareProtectedException, Boolean>() {
		override fun createIntent(context: Context, input: CloudFlareProtectedException): Intent {
			return AppRouter.cloudFlareResolveIntent(context, input)
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
			return resultCode == RESULT_OK
		}
	}

	companion object {

		const val TAG = "CloudFlareActivity"
		const val EXTRA_AUTO_RESOLVE = "auto_resolve"
		private const val VIEWPORT_POLL_INTERVAL_MS = 16L
		private const val EXTRA_AUTO_RECREATE_COUNT = "auto_recreate_count"
		private const val RESOLVE_POLL_INTERVAL_MS = 800L
		private const val AUTO_RETRY_DELAY_MS = 6_000L
		private const val MANUAL_FALLBACK_DELAY_MS = 15_000L
		private const val CLEARANCE_RECREATE_GRACE_MS = 500L
		private const val REQUIRED_STABLE_PASSES = 3
		private const val MAX_AUTO_RECREATE_COUNT = 1
		private const val CLEARANCE_COOKIE_NAME = "cf_clearance"
		private const val HIDDEN_RECREATED_STABLE_PASSES = 1
		private const val HIDDEN_RENDER_ALPHA = 0.01f
		private const val CF_STATE_OK = "ok"
		private const val CF_STATE_WAIT = "wait"
	}
}
