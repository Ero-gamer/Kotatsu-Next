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
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Dispatchers
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

	private var preparingStatusRunnable: Runnable? = null

// Add here:
    private var lastColorFilter: Any? = UNSET_SENTINEL
	private var tileLoadErrorCount = 0

	val context
		get() = itemView.context

	var boundData: ReaderPage? = null
		private set

	init {
		lifecycleScope.launch(Dispatchers.Main) {
			ssiv.bindToLifecycle(this@BasePageHolder)
			ssiv.isEagerLoadingEnabled = !context.isLowRamDevice()
			// Dispatchers.Default is a single pool shared across the WHOLE app, sized to
			// CPU core count (4 on this device's Cortex-A53). Every visible/cached webtoon
			// holder's tile decodes compete for the same pool, so up to 4 tiles can decode
			// in parallel at once — each one allocating a tile bitmap plus the src/out
			// IntArray filter pools simultaneously. That's a direct multiplier on peak
			// memory, independent of how many pages are bound. Capping this SSIV instance's
			// decode dispatcher to 2 concurrent tiles bounds that peak without changing
			// decode correctness or image quality — tiles simply queue slightly more before
			// running. No tradeoff: applied unconditionally on low-RAM devices.
			if (context.isLowRamDevice()) {
				ssiv.backgroundDispatcher = lowRamTileDecodeDispatcher
			}
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
		val colorFilterChanged = lastColorFilter !== UNSET_SENTINEL && lastColorFilter != settings.colorFilter
		lastColorFilter = settings.colorFilter
		when {
			// BitmapConfig or filter params (sharpening/vibrance) changed: reinstall the
			// region decoder (now a FilteringRegionDecoder when filters are active) and
			// reload SSIV tiles so the new per-tile filter takes effect immediately.
			settings.applyBitmapConfig(ssiv) -> reloadImage()

			// ColorFilter (contrast/saturation/brightness/etc) changed while page is displayed:
			// re-apply ColorMatrix paint filter to SSIV — instant, zero re-decode cost.
			colorFilterChanged && viewModel.state.value is PageState.Shown -> onReady()
		}
		ssiv.applyDownSampling(isResumed())
	}

	fun reloadImage() {
		val source = (viewModel.state.value as? PageState.Shown)?.source ?: return
		ssiv.setImage(source)
	}

	fun bind(data: ReaderPage) {
		boundData = data
		tileLoadErrorCount = 0
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
	}

	override fun onPause() {
		super.onPause()
		ssiv.applyDownSampling(isForeground = false)
	}

	override fun onDestroy() {
		context.unregisterComponentCallbacks(this)
		super.onDestroy()
	}

	open fun onAttachedToWindow() = Unit

	open fun onDetachedFromWindow() = Unit

	@CallSuper
	open fun onRecycled() {
		ssiv.removeCallbacks(preparingStatusRunnable)
		preparingStatusRunnable = null
		viewModel.onRecycle()
		ssiv.recycle()
		animatedView?.disposeImage()
		lastColorFilter = UNSET_SENTINEL
		tileLoadErrorCount = 0
	}

	// BUG FIX (silent-unfiltered-image, safety net): if tile decoding keeps failing for
	// this page (e.g. a race left the region decoder in a bad state for this specific
	// image), SSIV's default behavior is to swallow the error and never retry — the
	// unfiltered base/preview bitmap stays visible forever with no visible error state.
	// One bounded, debounced reload gives the decoder a fresh instance via reloadImage()
	// (init() re-runs from scratch). Capped at 1 retry per bind so a genuinely broken
	// file can't loop forever; onStateChanged already surfaces a real Error state via
	// the normal load pipeline for anything reloadImage() can't fix.
	override fun onTileLoadError(e: Throwable) {
		tileLoadErrorCount++
		when {
			tileLoadErrorCount == TILE_ERROR_RELOAD_THRESHOLD_SOFT &&
				viewModel.state.value is PageState.Shown -> {
				// First attempt: reload SSIV tiles only — keeps the cached bitmap, just
				// reinstalls the decoder and re-decodes every tile from the same source.
				reloadImage()
			}
			tileLoadErrorCount >= TILE_ERROR_RELOAD_THRESHOLD_HARD &&
				viewModel.state.value is PageState.Shown -> {
				// Second attempt: full page retry via PageLoader — forces a fresh download/
				// decode cycle if the cached file was corrupted or the descriptor invalidated.
				boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false) }
			}
		}
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
					ssiv.removeCallbacks(preparingStatusRunnable)
					preparingStatusRunnable = Runnable {
						if (viewModel.state.value is PageState.Loaded) {
							bindingInfo.textViewStatus.setText(R.string.preparing_)
						}
					}.also { ssiv.postDelayed(it, PREPARING_STATUS_DELAY_MS) }
				}
			}

			is PageState.Loading -> {
				if (state.preview != null && ssiv.getState() == null) {
					ssiv.setImage(state.preview)
				}
			}

			is PageState.Shown -> Unit
		}
	}

	// ── Color filter ─────────────────────────────────────────────────────────
	// Vibrance and sharpening are NOT part of this — they are applied per-tile inside
	// FilteringRegionDecoder (installed in applyBitmapConfig when filters are active).
	// ssiv.colorFilter only ever carries brightness/contrast/saturation/grayscale/invert.

	/**
	 * Sets ssiv.colorFilter from the current settings. Call this any time the base
	 * settings change — never set ssiv.colorFilter directly elsewhere.
	 */
	protected fun applyColorFilter() {
		if (ssiv.isReady) {
			ssiv.colorFilter = settings.colorFilter?.toColorFilter()
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
		// Soft threshold: triggers ssiv.setImage() tile-only reload (fast path).
		private const val TILE_ERROR_RELOAD_THRESHOLD_SOFT = 1
		// Hard threshold: triggers full viewModel.retry() page re-fetch (slow path).
		private const val TILE_ERROR_RELOAD_THRESHOLD_HARD = 3

		// Single process-wide instance, NOT one per holder/page. limitedParallelism()
		// creates a bounded "view" over the underlying Dispatchers.Default pool — sharing
		// one instance across every SSIV ensures the cap of 2 applies globally (e.g. 3
		// visible/cached webtoon pages all decoding tiles still never exceed 2 concurrent
        // tile decodes app-wide). A separate limitedParallelism(2) per holder would instead
		// allow 2× the number of holders concurrently, defeating the purpose.
		@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
		private val lowRamTileDecodeDispatcher = Dispatchers.Default.limitedParallelism(2)
		private val UNSET_SENTINEL = Any()
	}
}
