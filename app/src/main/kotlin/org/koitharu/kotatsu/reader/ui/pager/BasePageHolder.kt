package org.koitharu.kotatsu.reader.ui.pager

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.res.Configuration
import android.view.View
import androidx.annotation.CallSuper
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.image.CoilImageView
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.image.VibranceProcessor
import org.koitharu.kotatsu.core.ui.list.lifecycle.LifecycleAwareViewHolder
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isAnimatedImage
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.isSerializable
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.LayoutPageInfoBinding
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.vm.PageState
import org.koitharu.kotatsu.reader.ui.pager.vm.PageViewModel
import org.koitharu.kotatsu.reader.ui.pager.webtoon.WebtoonHolder

abstract class BasePageHolder<B : ViewBinding>(
	protected val binding: B,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
	lifecycleOwner: LifecycleOwner,
) : LifecycleAwareViewHolder(binding.root, lifecycleOwner), DefaultOnImageEventListener, ComponentCallbacks2 {

	protected val viewModel = PageViewModel(
		loader = loader,
		settingsProducer = readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
		isWebtoon = this is WebtoonHolder,
	)
	protected val bindingInfo = LayoutPageInfoBinding.bind(binding.root)
	protected abstract val ssiv: SubsamplingScaleImageView

	protected val animatedView: CoilImageView? by lazy {
		itemView.findViewById(R.id.animatedView)
	}

	protected val settings: ReaderSettings
		get() = viewModel.settingsProducer.value

	private var lastSharpening = Float.MIN_VALUE
	/** Sentinel: MIN_VALUE means "not yet applied", distinguishing from a legitimate null filter. */
	private var lastColorFilter: Any? = UNSET_SENTINEL

	val context
		get() = itemView.context

	var boundData: ReaderPage? = null
		private set

	init {
		lifecycleScope.launch(Dispatchers.Main) {
			ssiv.bindToLifecycle(this@BasePageHolder)
			ssiv.isEagerLoadingEnabled = !context.isLowRamDevice()
			ssiv.addOnImageEventListener(viewModel)
			ssiv.addOnImageEventListener(this@BasePageHolder)
		}
		val clickListener = View.OnClickListener { v ->
			when (v.id) {
				R.id.button_retry -> viewModel.retry(
					page = boundData?.toMangaPage() ?: return@OnClickListener,
					isFromUser = true,
				)

				R.id.button_error_details -> viewModel.showErrorDetails(boundData?.url)
			}
		}
		bindingInfo.buttonRetry.setOnClickListener(clickListener)
		bindingInfo.buttonErrorDetails.setOnClickListener(clickListener)
	}

	@CallSuper
	protected open fun onConfigChanged(settings: ReaderSettings) {
		settings.applyBackground(itemView)
		val sharpeningChanged = lastSharpening != Float.MIN_VALUE && lastSharpening != settings.sharpening
		lastSharpening = settings.sharpening

		val colorFilterChanged = lastColorFilter !== UNSET_SENTINEL && lastColorFilter != settings.colorFilter
		lastColorFilter = settings.colorFilter
		when {
			// Sharpening changed: re-process bitmap using cached source file (no re-download).
			sharpeningChanged -> boundData?.let { viewModel.reapplySharpening(it.toMangaPage()) }

			// BitmapConfig changed: reload SSIV tiles with new config.
			settings.applyBitmapConfig(ssiv) -> reloadImage()

			// ColorFilter (contrast/saturation/brightness/etc) changed while page is displayed:
			// re-apply ColorMatrix paint filter to SSIV — instant, zero re-decode cost.
			colorFilterChanged && viewModel.state.value is PageState.Shown -> onReady()
		}
		// Vibrance slider changed while visible — re-apply
		if (colorFilterChanged && isResumed()) {
			reapplyVibrance()
		}
		ssiv.applyDownSampling(isResumed())
	}

	fun reloadImage() {
		val source = (viewModel.state.value as? PageState.Shown)?.source ?: return
		ssiv.setImage(source)
	}

	fun bind(data: ReaderPage) {
		boundData = data
		ssiv.isVisible = true
		animatedView?.isVisible = false
		animatedView?.disposeImage()
		viewModel.onBind(data.toMangaPage())
		onBind(data)
	}

	@CallSuper
	protected open fun onBind(data: ReaderPage) = Unit

	override fun onCreate() {
		super.onCreate()
		context.registerComponentCallbacks(this)
		viewModel.state.observe(this, ::onStateChanged)
		viewModel.settingsProducer.observe(this, ::onConfigChanged)
	}

	override fun onResume() {
		super.onResume()
		ssiv.applyDownSampling(isForeground = true)
		if (viewModel.state.value is PageState.Error && !viewModel.isLoading()) {
			boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false) }
		}
		// Apply GLSL vibrance now that this page is on-screen
		reapplyVibrance()
	}

	override fun onPause() {
		super.onPause()
		ssiv.applyDownSampling(isForeground = false)
		// Cancel any in-progress GPU work and release vibrance bitmap
		clearVibrance()
	}

	override fun onDestroy() {
		context.unregisterComponentCallbacks(this)
		super.onDestroy()
	}

	open fun onAttachedToWindow() = Unit

	open fun onDetachedFromWindow() = Unit

	@CallSuper
	open fun onRecycled() {
		clearVibrance()
		viewModel.onRecycle()
		ssiv.recycle()
		animatedView?.disposeImage()
		// Reset sentinels so the next bind treats settings as fresh and applies all filters.
		lastSharpening = Float.MIN_VALUE
		lastColorFilter = UNSET_SENTINEL
	}

	override fun onTrimMemory(level: Int) {
		if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
			VibranceProcessor.trimMemory()
		}
	}

	override fun onConfigurationChanged(newConfig: Configuration) = Unit

	@Deprecated("Deprecated in Java")
	final override fun onLowMemory() = onTrimMemory(TRIM_MEMORY_COMPLETE)

	protected open fun onStateChanged(state: PageState) {
		bindingInfo.layoutError.isVisible = state is PageState.Error
		bindingInfo.layoutProgress.isGone = state.isFinalState()
		val progress = (state as? PageState.Loading)?.progress ?: -1
		if (progress in 0..100) {
			bindingInfo.progressBar.isIndeterminate = false
			bindingInfo.progressBar.setProgressCompat(progress, true)
			bindingInfo.textViewStatus.text = context.getString(R.string.percent_string_pattern, progress.toString())
		} else {
			bindingInfo.progressBar.isIndeterminate = true
			bindingInfo.textViewStatus.setText(R.string.loading_)
		}
		val isAnimated = boundData?.url?.isAnimatedImage() == true
		when (state) {
			is PageState.Converting -> {
				bindingInfo.textViewStatus.setText(R.string.processing_)
			}

			is PageState.Empty -> Unit

			is PageState.Error -> {
				val e = state.error
				bindingInfo.textViewError.text = e.getDisplayMessage(context.resources)
				bindingInfo.buttonRetry.setText(
					ExceptionResolver.getResolveStringId(e).ifZero { R.string.try_again },
				)
				bindingInfo.buttonErrorDetails.isVisible = e.isSerializable()
				bindingInfo.layoutError.isVisible = true
				bindingInfo.progressBar.hide()
			}

			is PageState.Loaded -> {
				if (isAnimated) {
					showAnimated(boundData!!, state)
					bindingInfo.layoutProgress.isGone = true
				} else {
					bindingInfo.textViewStatus.setText(R.string.loading_)
					ssiv.setImage(state.source)
					ssiv.postDelayed({
						if (viewModel.state.value is PageState.Loaded) {
							bindingInfo.textViewStatus.setText(R.string.preparing_)
						}
					}, PREPARING_STATUS_DELAY_MS)
				}
			}

			is PageState.Loading -> {
				if (state.preview != null && ssiv.getState() == null) {
					ssiv.setImage(state.preview)
				}
			}

			is PageState.Shown -> {
				// Page just became fully shown — apply vibrance if holder is already on-screen
				if (isResumed()) reapplyVibrance()
			}
		}
	}

	// ── Vibrance ──────────────────────────────────────────────────────────────
	// Vibrance is never a standalone ColorFilter — it is always composited into
	// ReaderColorFilter.toColorFilter(vibranceBoost) alongside brightness/contrast/
	// saturation/grayscale/invert. ssiv.colorFilter is only ever set via [applyColorFilter]
	// below so the full pipeline is always present together.

	/** Most recently computed/cached vibrance boost for the currently shown page, or 0. */
	private var currentVibranceBoost = 0f
	private var activeVibranceKey: String? = null
	private var vibranceJob: Job? = null

	/**
	 * Sets ssiv.colorFilter using the full ReaderColorFilter pipeline plus the current
	 * vibrance boost. Call this any time either the base settings OR the vibrance boost
	 * changes — never set ssiv.colorFilter directly elsewhere.
	 */
	protected fun applyColorFilter() {
		if (ssiv.isReady) {
			ssiv.colorFilter = settings.colorFilter?.toColorFilter(currentVibranceBoost)
		}
	}

	/**
	 * Computes/reuses the vibrance boost for the current page and re-applies the
	 * full color filter pipeline. Does nothing if vibrance == 0 (boost reset to 0
	 * and filter re-applied without it) or the page isn't yet shown.
	 *
	 * - Uses [VibranceProcessor] (Semaphore(1)) so only one page analyses at a time.
	 * - On success: composites the boost into the SAME ColorMatrix as all other
	 *   active filters — never replaces ssiv.colorFilter with vibrance alone.
	 * - On pause/recycle: job is cancelled before reaching the semaphore.
	 */
	private fun reapplyVibrance() {
		val vibrance = settings.colorFilter?.vibrance ?: 0f
		val shownState = viewModel.state.value as? PageState.Shown

		vibranceJob?.cancel()
		vibranceJob = null

		if (vibrance == 0f || shownState == null) {
			if (currentVibranceBoost != 0f || activeVibranceKey != null) {
				activeVibranceKey?.let { VibranceProcessor.releaseEntry(it) }
				activeVibranceKey = null
				currentVibranceBoost = 0f
				applyColorFilter()
			}
			return
		}

		val pageUri = (shownState.source as? ImageSource.Uri)?.uri ?: return
		val key = VibranceProcessor.cacheKey(pageUri.toString(), vibrance)

		if (activeVibranceKey == key) return  // already showing this exact boost

		val cachedBoost = VibranceProcessor.getCached(key)
		if (cachedBoost != null) {
			activeVibranceKey = key
			currentVibranceBoost = cachedBoost
			applyColorFilter()
			return
		}

		activeVibranceKey = key
		vibranceJob = lifecycleScope.launch(Dispatchers.IO) {
			val boost = VibranceProcessor.computeBoost(pageUri, vibrance, key) ?: return@launch
			launch(Dispatchers.Main) {
				if (!isResumed() || activeVibranceKey != key) return@launch
				currentVibranceBoost = boost
				applyColorFilter()
			}
		}
	}

	private fun clearVibrance() {
		vibranceJob?.cancel()
		vibranceJob = null
		if (activeVibranceKey != null) {
			VibranceProcessor.releaseEntry(activeVibranceKey!!)
			activeVibranceKey = null
		}
		if (currentVibranceBoost != 0f) {
			currentVibranceBoost = 0f
			applyColorFilter()
		}
	}

	private fun showAnimated(page: ReaderPage, loadedState: PageState.Loaded) {
		ssiv.isVisible = false
		animatedView?.let {
			it.isVisible = true
			it.setImageAsync(page)
		}
		viewModel.state.update { currentState ->
			if (currentState is PageState.Loaded) {
				PageState.Shown(loadedState.source, loadedState.isConverted)
			} else {
				currentState
			}
		}
	}

	protected fun SubsamplingScaleImageView.applyDownSampling(isForeground: Boolean) {
		downSampling = when {
			isForeground || !settings.isReaderOptimizationEnabled -> 1
			BuildConfig.DEBUG -> 32
			context.isLowRamDevice() -> 8
			else -> 4
		}
	}

	private companion object {
		private const val PREPARING_STATUS_DELAY_MS = 600L
		private val UNSET_SENTINEL = Any()
	}
}
