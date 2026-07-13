package org.koitharu.kotatsu.core.exceptions.resolve

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.browser.cloudflare.CloudFlareActivity
import org.koitharu.kotatsu.browser.cloudflare.CloudFlareHiddenActivity
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import org.koitharu.kotatsu.core.ui.util.ForegroundActivityHolder
import org.koitharu.kotatsu.core.util.ext.findCloudFlareException
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the single Cloudflare verification session allowed in the app process. */
@Singleton
class CaptchaAutoResolveCoordinator @Inject constructor(
	@ApplicationContext private val context: Context,
	private val foregroundActivityHolder: ForegroundActivityHolder,
	private val settings: AppSettings,
) : DefaultActivityLifecycleCallbacks, DefaultLifecycleObserver {

	private val stateMutex = Mutex()
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val recentSuccessAt = ConcurrentHashMap<MangaSource, Long>()
	private val mainHandler = Handler(Looper.getMainLooper())
	private val reorderPending = AtomicBoolean(false)

	@Volatile
	private var activeSession: ResolveSession? = null

	@Volatile
	private var hiddenActivityRef: WeakReference<CloudFlareHiddenActivity>? = null

	init {
		mainHandler.post {
			ProcessLifecycleOwner.get().lifecycle.addObserver(this)
		}
	}

	fun registerHiddenActivity(activity: CloudFlareHiddenActivity) {
		hiddenActivityRef = WeakReference(activity)
		reorderPending.set(false)
	}

	fun unregisterHiddenActivity(activity: CloudFlareHiddenActivity) {
		if (hiddenActivityRef?.get() === activity) {
			hiddenActivityRef = null
		}
	}

	/** Called by the current solver Activity when it finishes or is manually cancelled. */
	fun notifyResolveResult(source: MangaSource, success: Boolean) {
		activeSession
			?.takeIf { it.source == source }
			?.activityResult
			?.complete(success)
	}

	fun isResolveActive(source: MangaSource): Boolean = activeSession != null

	/** Waits for whichever global session is active. This method never starts verification. */
	suspend fun awaitActiveResolve(source: MangaSource): Boolean? = activeSession?.result?.await()

	/**
	 * Runs an operation in sync with the global solver. Background and prefetch callers pass
	 * [mayStartVerification] as false: they may wait for an existing session but never create one.
	 */
	suspend fun <T> runWithVerification(
		source: MangaSource,
		mayStartVerification: Boolean,
		block: suspend () -> T,
	): T {
		activeSession?.result?.await()
		var retryCount = 0
		while (true) {
			try {
				return block()
			} catch (e: Exception) {
				val cf = e.findCloudFlareException()
				if (
					cf !is CloudFlareProtectedException ||
					!mayStartVerification ||
					retryCount++ >= MAX_REQUEST_RETRIES ||
					!resolveIfEnabled(cf)
				) throw e
			}
		}
	}

	/** Resolves [exception] only when automatic solving is enabled for its source. */
	suspend fun resolveIfEnabled(exception: CloudFlareProtectedException): Boolean {
		// Global kill-switch: if the user disabled auto-resolve in Settings → Network, bail immediately.
		if (settings.isCfAutoSolveDisabled) return false
		if (SourceSettings(context, exception.source).isCaptchaAutoResolveDisabled) return false
		val lastSuccess = recentSuccessAt[exception.source]
		if (lastSuccess != null && System.currentTimeMillis() - lastSuccess < RECENT_SUCCESS_COOLDOWN_MS) {
			return true
		}
		return resolve(exception.source, exception)
	}

	/** Joins the current global session or atomically creates the only allowed solver session. */
	suspend fun resolve(source: MangaSource, exception: CloudFlareProtectedException): Boolean {
		if (source == UnknownMangaSource) return false
		val claim = stateMutex.withLock {
			activeSession?.let { return@withLock SessionClaim(it, isOwner = false) }
			val session = ResolveSession(
				source = source,
				exception = exception,
				activityResult = CompletableDeferred(),
				result = CompletableDeferred(),
			)
			activeSession = session
			SessionClaim(session, isOwner = true)
		}
		if (claim.isOwner) {
			showSolvingToast()
			scope.launch { runSession(claim.session) }
		}
		return claim.session.result.await()
	}

	private suspend fun runSession(session: ResolveSession) {
		var success = false
		try {
			launch(session)
			success = session.activityResult.await()
			if (success) recentSuccessAt[session.source] = System.currentTimeMillis()
		} catch (e: Throwable) {
			e.printStackTraceDebug()
		} finally {
			stateMutex.withLock {
				if (activeSession === session) activeSession = null
			}
			hiddenActivityRef = null
			reorderPending.set(false)
			// Clear ownership before releasing waiters. A waiter for another source can now retry,
			// observe that it is still protected, and atomically claim the next session.
			session.result.complete(success)
		}
	}

	private fun launch(session: ResolveSession) {
		val intent = AppRouter.cloudFlareResolveIntent(context, session.exception, hidden = true).apply {
			putExtra(CloudFlareActivity.EXTRA_AUTO_RESOLVE, true)
		}
		val launcher = foregroundActivityHolder.current
		if (launcher != null) {
			launcher.startActivity(intent)
		} else {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			context.startActivity(intent)
		}
	}

	/** Keep the existing hidden WebView above newly opened app screens without constructing another one. */
	override fun onActivityResumed(activity: Activity) {
		if (activity is CloudFlareHiddenActivity) {
			reorderPending.set(false)
			return
		}
		val solver = hiddenActivityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
		if (!solver.shouldStayHiddenAndFocused() || !reorderPending.compareAndSet(false, true)) return
		mainHandler.post {
			if (!solver.shouldStayHiddenAndFocused() || solver.isFinishing || solver.isDestroyed) {
				reorderPending.set(false)
				return@post
			}
			activity.startActivity(
				Intent(activity, CloudFlareHiddenActivity::class.java).addFlags(
					Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
				),
			)
		}
	}

	/** App backgrounding cancels the one session and therefore releases every waiter with failure. */
	override fun onStop(owner: LifecycleOwner) {
		val session = activeSession ?: return
		session.activityResult.complete(false)
		mainHandler.post {
			hiddenActivityRef?.get()?.cancelAutomaticResolve()
		}
	}

	private fun showSolvingToast() {
		mainHandler.post {
			Toast.makeText(context, R.string.captcha_solving, Toast.LENGTH_LONG).show()
		}
	}

	private data class ResolveSession(
		val source: MangaSource,
		val exception: CloudFlareProtectedException,
		val activityResult: CompletableDeferred<Boolean>,
		val result: CompletableDeferred<Boolean>,
	)

	private data class SessionClaim(
		val session: ResolveSession,
		val isOwner: Boolean,
	)

	private companion object {
		const val RECENT_SUCCESS_COOLDOWN_MS = 30_000L
		const val MAX_REQUEST_RETRIES = 2
	}
}
