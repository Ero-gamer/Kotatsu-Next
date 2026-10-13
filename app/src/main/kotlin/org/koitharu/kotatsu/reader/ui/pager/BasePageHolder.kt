package org.koitharu.kotatsu.reader.ui.pager

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import android.view.View
import androidx.annotation.CallSuper
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.image.CoilImageView
import org.koitharu.kotatsu.core.image.ImageFormatProbe
import org.koitharu.kotatsu.core.image.codecs.NativeAnimatedDrawable
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.list.lifecycle.LifecycleAwareViewHolder
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isSerializable
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
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
) : LifecycleAwareViewHolder(binding.root, lifecycleOwner),
    DefaultOnImageEventListener,
    ComponentCallbacks2 {

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
    private val animToggle: MaterialButton? by lazy {
        itemView.findViewById(R.id.button_anim_toggle)
    }

    // Animated-page state. [animChoice] is the user's explicit play/pause (null = follow the autoplay policy).
    private var animatedJob: Job? = null
    private var isAnimatedPage = false
    private var animChoice: Boolean? = null
    private var isUnloadedBySaver = false

    protected val settings: ReaderSettings
        get() = viewModel.settingsProducer.value

    private var preparingStatusRunnable: Runnable? = null
    private var lastColorFilter: Any? = UNSET_SENTINEL
    private var tileLoadErrorCount = 0

    val context get() = itemView.context

    var boundData: ReaderPage? = null
        private set

    init {
        lifecycleScope.launch(Dispatchers.Main) {
            ssiv.bindToLifecycle(this@BasePageHolder)
            applyMemoryProfile()
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
        animatedView?.run {
            allowAnimation = true
            addImageRequestListener(
                object : ImageRequest.Listener {
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) = applyPlayback()
                },
            )
        }
        animToggle?.setOnClickListener {
            val running = (animatedView?.drawable as? Animatable)?.isRunning == true
            animChoice = !running
            applyPlayback()
        }
    }

    /**
     * Memory limits for decoding. Standard values unless the opt-in Memory Saver is on; with it on the
     * decode windows and the global off-screen tile budget are tightened and eager loading is off.
     */
    private fun applyMemoryProfile() {
        val saver = settings.isMemorySaver
        ssiv.isEagerLoadingEnabled = !saver
        ssiv.maxTilePixels = if (saver) TILE_PIXELS_CONSTRAINED else TILE_PIXELS_DEFAULT
        ssiv.baseLayerMaxPixels = if (saver) BASE_PIXELS_CONSTRAINED else BASE_PIXELS_DEFAULT
        ssiv.isTightSampling = saver
        SubsamplingScaleImageView.tileBudgetBytes = if (saver) TILE_BUDGET_CONSTRAINED else TILE_BUDGET_DEFAULT
    }

    @CallSuper
    protected open fun onConfigChanged(settings: ReaderSettings) {
        applyMemoryProfile()
        settings.applyBackground(itemView)
        val colorFilterChanged = lastColorFilter !== UNSET_SENTINEL && lastColorFilter != settings.effectiveColorFilter
        lastColorFilter = settings.effectiveColorFilter

        when {
            // BitmapConfig or GPU filter params changed: reinstall the region decoder factory
            // (GpuFilteringDecoder when any GPU filter is active) and reload SSIV tiles so
            // the new per-tile GPU shader pass takes effect immediately.
            settings.applyBitmapConfig(ssiv) -> reloadImage()

            // CPU ColorMatrix (brightness/contrast/saturation/grayscale/invert) changed while
            // the page is already displayed: re-apply paint filter to SSIV — instant, zero
            // re-decode cost, no tile reload needed.
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
        isUnloadedBySaver = false
        tileLoadErrorCount = 0
        ssiv.isVisible = true
        resetAnimated()
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
        applyPlayback()
        // Memory Saver releases a page's decoded image when it is left; bring it back when it is current again.
        if (isUnloadedBySaver) {
            isUnloadedBySaver = false
            if (viewModel.state.value is PageState.Shown) reloadImage()
        }
        ssiv.applyDownSampling(isForeground = true)
        if (viewModel.state.value is PageState.Error && !viewModel.isLoading()) {
            boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false) }
        }
    }

    override fun onPause() {
        super.onPause()
        stopAnimated()
        if (settings.isMemorySaver && !isAnimatedPage && !isUnloadedBySaver &&
            viewModel.state.value is PageState.Shown
        ) {
            ssiv.recycle()
            isUnloadedBySaver = true
        }
        ssiv.applyDownSampling(isForeground = false)
    }

    override fun onDestroy() {
        stopAnimated()
        context.unregisterComponentCallbacks(this)
        super.onDestroy()
    }

    open fun onAttachedToWindow() = Unit
    open fun onDetachedFromWindow() = stopAnimated()

    @CallSuper
    open fun onRecycled() {
        ssiv.removeCallbacks(preparingStatusRunnable)
        preparingStatusRunnable = null
        viewModel.onRecycle()
        ssiv.recycle()
        resetAnimated()
        lastColorFilter = UNSET_SENTINEL
        tileLoadErrorCount = 0
    }

    override fun onTileLoadError(e: Throwable) {
        tileLoadErrorCount++
        when {
            tileLoadErrorCount == TILE_ERROR_SOFT && viewModel.state.value is PageState.Shown ->
                reloadImage()

            tileLoadErrorCount >= TILE_ERROR_HARD && viewModel.state.value is PageState.Shown ->
                boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false) }
        }
    }

    /**
     * Releases decoded tiles under memory pressure (the GPU renderer keeps no tile cache, so the
     * tile bitmaps held by SSIV are the only thing to free). PageLoader's own file cache is
     * trimmed separately by its own ComponentCallbacks2.
     */
    override fun onTrimMemory(level: Int) = ssiv.trimMemory(level)

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    final override fun onLowMemory() = onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)

    protected open fun onStateChanged(state: PageState) {
        bindingInfo.layoutError.isVisible = state is PageState.Error
        bindingInfo.layoutProgress.isGone = state.isFinalState()
        val progress = (state as? PageState.Loading)?.progress ?: -1
        if (progress in 0..100) {
            bindingInfo.progressBar.isIndeterminate = false
            bindingInfo.progressBar.setProgressCompat(progress, true)
            bindingInfo.textViewStatus.text =
                context.getString(R.string.percent_string_pattern, progress.toString())
        } else {
            bindingInfo.progressBar.isIndeterminate = true
            bindingInfo.textViewStatus.setText(R.string.loading_)
        }
        when (state) {
            is PageState.Converting -> bindingInfo.textViewStatus.setText(R.string.processing_)

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
                routeLoaded(state)
            }

            is PageState.Loading -> {
                if (state.preview != null && ssiv.getState() == null) {
                    ssiv.setImage(state.preview)
                }
            }

            is PageState.Shown -> Unit
        }
    }

    // ── Color filter ──────────────────────────────────────────────────────────
    // GPU filters (denoise, vibrance, sharpening) are installed via GpuFilteringDecoder
    // in applyBitmapConfig and take effect per-tile on decode.
    // ssiv.colorFilter carries only the CPU/Canvas ColorMatrix:
    //   brightness / contrast / saturation / grayscale / invert / book-tint.

    private fun wantsPlayback(): Boolean {
        if (!isAnimatedPage || !isResumed() || !settings.isAnimatedImagesEnabled) return false
        return animChoice ?: (settings.isAnimatedAutoplay && !settings.isMemorySaver && context.isAnimationsEnabled)
    }

    /** Starts or stops the current animation according to focus, settings and the user's choice. */
    private fun applyPlayback() {
        val drawable = animatedView?.drawable as? Animatable
        if (drawable != null) {
            if (wantsPlayback()) {
                AnimationGate.claim(this) {
                    (animatedView?.drawable as? Animatable)?.takeIf { it.isRunning }?.stop()
                    updateToggle()
                }
                if (!drawable.isRunning) drawable.start()
            } else {
                if (drawable.isRunning) drawable.stop()
                AnimationGate.release(this)
            }
        }
        updateToggle()
    }

    private fun stopAnimated() {
        (animatedView?.drawable as? Animatable)?.takeIf { it.isRunning }?.stop()
        AnimationGate.release(this)
        updateToggle()
    }

    private fun resetAnimated() {
        animatedJob?.cancel()
        animatedJob = null
        stopAnimated()
        isAnimatedPage = false
        animChoice = null
        animatedView?.isVisible = false
        (animatedView?.drawable as? NativeAnimatedDrawable)?.release()
        animatedView?.disposeImage()
        animToggle?.isVisible = false
    }

    private fun updateToggle() {
        val button = animToggle ?: return
        button.isVisible = isAnimatedPage
        if (!isAnimatedPage) return
        val running = (animatedView?.drawable as? Animatable)?.isRunning == true
        button.setIconResource(if (running) R.drawable.ic_action_pause else R.drawable.ic_play)
        button.contentDescription = context.getString(if (running) R.string.animation_pause else R.string.animation_play)
    }

    protected fun applyColorFilter() {
        if (ssiv.isReady) {
            ssiv.colorFilter = settings.effectiveColorFilter?.toColorFilter()
        }
        if (isAnimatedPage) {
            // Only the cheap contrast/saturation matrix applies to animations; GPU filters never do.
            animatedView?.colorFilter = settings.effectiveColorFilter?.toColorFilter()
        }
    }

    private fun showStatic(state: PageState.Loaded) {
        bindingInfo.textViewStatus.setText(R.string.loading_)
        ssiv.setImage(state.source)
        ssiv.removeCallbacks(preparingStatusRunnable)
        preparingStatusRunnable = Runnable {
            if (viewModel.state.value is PageState.Loaded) {
                bindingInfo.textViewStatus.setText(R.string.preparing_)
            }
        }.also { ssiv.postDelayed(it, PREPARING_STATUS_DELAY_MS) }
    }

    /**
     * Decides animated vs static from the file content (header sniff, cached), never from the name,
     * so wrong extensions and animated WebP/AVIF/APNG/JXL are routed correctly.
     */
    private fun routeLoaded(state: PageState.Loaded) {
        val uri = (state.source as? ImageSource.Uri)?.uri
        val page = boundData
        if (uri == null || page == null || !settings.isAnimatedImagesEnabled || animatedView == null) {
            showStatic(state)
            return
        }
        animatedJob?.cancel()
        animatedJob = lifecycleScope.launch {
            val animated = withContext(Dispatchers.IO) { isAnimatedContent(uri) }
            if (viewModel.state.value !== state) return@launch
            if (animated) {
                showAnimated(page, state)
                bindingInfo.layoutProgress.isGone = true
            } else {
                showStatic(state)
            }
        }
    }

    private fun isAnimatedContent(uri: android.net.Uri): Boolean = try {
        when {
            uri.isFileUri() -> uri.toFileOrNull()?.let { ImageFormatProbe.probe(it).animated } == true

            uri.isZipUri() -> java.util.zip.ZipFile(uri.schemeSpecificPart).use { zip ->
                val entry = zip.getEntry(uri.fragment ?: return false) ?: return false
                val head = ByteArray(32 * 1024)
                val n = zip.getInputStream(entry).use { it.read(head).coerceAtLeast(0) }
                ImageFormatProbe.probe(head, n, truncated = entry.size > n).animated
            }

            else -> false
        }
    } catch (e: java.io.IOException) {
        false
    }

    private fun showAnimated(page: ReaderPage, loadedState: PageState.Loaded) {
        ssiv.isVisible = false
        isAnimatedPage = true
        animatedView?.let {
            it.colorFilter = settings.effectiveColorFilter?.toColorFilter()
            it.isVisible = true
            it.setImageAsync(page)
        }
        updateToggle()
        viewModel.state.update { currentState ->
            if (currentState is PageState.Loaded) {
                PageState.Shown(loadedState.source, loadedState.isConverted)
            } else {
                currentState
            }
        }
    }

    /**
     * Whether a page that is not in the foreground is re-decoded at a lower resolution. Costs a
     * full re-decode on every change, so a holder that is constantly shown and hidden while the
     * app stays in the foreground (webtoon) opts out.
     */
    protected open val usesBackgroundDownSampling: Boolean
        get() = true

    protected fun SubsamplingScaleImageView.applyDownSampling(isForeground: Boolean) {
        downSampling = when {
            isForeground || !usesBackgroundDownSampling || !settings.isReaderOptimizationEnabled -> 1
            BuildConfig.DEBUG -> 32
            settings.isMemorySaver -> 8
            else -> 4
        }
    }

    private companion object {
        private const val PREPARING_STATUS_DELAY_MS = 600L
        private const val TILE_ERROR_SOFT = 1
        private const val TILE_ERROR_HARD = 3
        private const val TILE_PIXELS_DEFAULT = 3_145_728
        private const val TILE_PIXELS_CONSTRAINED = 2_359_296
        private const val BASE_PIXELS_DEFAULT = 4_000_000
        private const val BASE_PIXELS_CONSTRAINED = 3_000_000
        private const val TILE_BUDGET_DEFAULT = 64L * 1024 * 1024
        private const val TILE_BUDGET_CONSTRAINED = 32L * 1024 * 1024
        private val UNSET_SENTINEL = Any()
    }
}
