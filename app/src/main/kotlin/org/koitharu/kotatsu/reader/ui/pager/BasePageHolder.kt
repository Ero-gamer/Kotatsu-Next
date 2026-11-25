package org.koitharu.kotatsu.reader.ui.pager

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import android.net.Uri
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
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
import kotlinx.coroutines.flow.drop
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
import org.koitharu.kotatsu.core.prefs.ReaderBackground
import org.koitharu.kotatsu.core.ui.list.lifecycle.LifecycleAwareViewHolder
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isMemoryConstrained
import org.koitharu.kotatsu.core.util.ext.isSerializable
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.databinding.LayoutPageInfoBinding
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.reader.domain.PageEdgeColor
import org.koitharu.kotatsu.reader.domain.PageStats
import org.koitharu.kotatsu.reader.domain.PageStatsEngine
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import org.koitharu.kotatsu.reader.domain.applyOverlay
import org.koitharu.kotatsu.reader.domain.erase.PageMask
import org.koitharu.kotatsu.reader.ui.ReaderControlDelegate
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.erase.ErasePageTarget
import org.koitharu.kotatsu.reader.ui.erase.MaskedRegionDecoder
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
    private val pageLoader = loader
    private val hiddenImageChanges = loader.imageHider.rulesChanges

    /** The page told about being on screen, so that it is told when it no longer is, whatever this holder moved on to. */
    private var pageOnScreen: Long? = null
    protected val bindingInfo = LayoutPageInfoBinding.bind(binding.root)
    protected abstract val ssiv: SubsamplingScaleImageView

    protected val animatedView: CoilImageView? by lazy {
        itemView.findViewById(R.id.animatedView)
    }
    private val animToggle: MaterialButton? by lazy {
        itemView.findViewById(R.id.button_anim_toggle)
    }
    private val animSeek: SeekBar? by lazy {
        itemView.findViewById(R.id.seek_anim)
    }
    private val animControls: View? by lazy { itemView.findViewById(R.id.layout_anim_controls) }
    private val animTime: TextView? by lazy { itemView.findViewById(R.id.text_anim_time) }
    private val animSpeedButton: TextView? by lazy { itemView.findViewById(R.id.button_anim_speed) }
    private var animSpeed = 1f
    private var animTimeSecond = -1L
    private var animAdvanced = false
    private var visibleEnough = true
    private val animListener = object : NativeAnimatedDrawable.Listener {
        override fun onStateChanged() {
            if ((animatedView?.drawable as? Animatable)?.isRunning != true) AnimationGate.release(this@BasePageHolder)
            updateToggle()
        }

        override fun onFrameShown(index: Int) {
            if (!isSeeking) animSeek?.progress = index
            updateAnimTime()
        }

        override fun onLoopCompleted() {
            if (!settings.isAnimatedAdvance || animAdvanced || !isResumed()) return
            animAdvanced = true
            (context.findActivity() as? ReaderControlDelegate.OnInteractionListener)?.switchPageBy(1)
        }
    }
    private var isSeeking = false

    /** Pinch / double-tap zoom of animated pages; webtoon scrolls and zooms the whole list instead. */
    private val animZoom: AnimatedZoomHelper? by lazy {
        if (this is WebtoonHolder) {
            null
        } else {
            animatedView?.let { view -> AnimatedZoomHelper(view).also { view.setOnTouchListener(it) } }
        }
    }

    // Animated-page state. [animChoice] is the user's explicit play/pause (null = follow the autoplay policy).
    private var animatedJob: Job? = null
    private var backgroundJob: Job? = null

    // Auto contrast: this page's own brightness range, measured once from a thumbnail of the loaded file.
    private var levelsJob: Job? = null
    private var levelsUri: Uri? = null
    private var levelsResolvedFor: Uri? = null
    private var autoStats: PageStats? = null
    private var isAnimatedPage = false
    private var animChoice: Boolean? = null
    private var isUnloadedBySaver = false

    protected val settings: ReaderSettings
        get() = viewModel.settingsProducer.value

    private var preparingStatusRunnable: Runnable? = null
    private var lastColorFilter: Any? = UNSET_SENTINEL
    private var tileLoadErrorCount = 0
    private var autoRetryCount = 0
    private var lastToggleRunning: Boolean? = null

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
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        (drawable as? NativeAnimatedDrawable)?.let {
                            it.listener = animListener
                            animSpeed = settings.animatedSpeed
                            it.speed = animSpeed
                            it.loopLimit = settings.animatedLoops
                        }
                        animZoom?.reset()
                        applyPlayback()
                    }
                },
            )
        }
        animSeek?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    animChoice = false
                    (animatedView?.drawable as? NativeAnimatedDrawable)?.seekTo(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    isSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    isSeeking = false
                }
            },
        )
        itemView.findViewById<View>(R.id.button_anim_prev)?.setOnClickListener { stepAnimation(-1) }
        itemView.findViewById<View>(R.id.button_anim_next)?.setOnClickListener { stepAnimation(1) }
        animSpeedButton?.setOnClickListener {
            val native = animatedView?.drawable as? NativeAnimatedDrawable ?: return@setOnClickListener
            animSpeed = ANIM_SPEEDS.firstOrNull { it > animSpeed + 0.01f } ?: ANIM_SPEEDS.first()
            native.speed = animSpeed
            updateToggle()
        }
        itemView.findViewById<View>(R.id.button_anim_save)?.setOnClickListener {
            val frame = (animatedView?.drawable as? NativeAnimatedDrawable)?.snapshot() ?: return@setOnClickListener
            val listener = context.findActivity() as? ReaderControlDelegate.OnInteractionListener
            if (listener != null) listener.onSaveFrameClick(frame) else frame.recycle()
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
        // A GPU-filtered tile costs ~16 B/px of GL buffers plus the source, read-back and result bitmaps
        // (~100 MB for a 3 Mpx tile), so smaller tiles are decoded while a GPU filter is on.
        val gpu = settings.isGpuFilterActive
        ssiv.maxTilePixels = when {
            gpu -> TILE_PIXELS_GPU
            saver -> TILE_PIXELS_CONSTRAINED
            else -> TILE_PIXELS_DEFAULT
        }
        // The base layer only has to cover the screen at fit zoom (tiles take over when zooming in), so it
        // scales with the display instead of a flat 4 Mpx: ~1.1 Mpx screens (720x1560) need ~1.7 Mpx.
        val dm = ssiv.resources.displayMetrics
        val screenBased = (dm.widthPixels.toLong() * dm.heightPixels * 3 / 2)
            .coerceIn(BASE_PIXELS_MIN.toLong(), BASE_PIXELS_DEFAULT.toLong()).toInt()
        ssiv.baseLayerMaxPixels = when {
            gpu -> minOf(screenBased, BASE_PIXELS_GPU)
            saver -> minOf(screenBased, BASE_PIXELS_CONSTRAINED)
            else -> screenBased
        }
        // Tight sampling (decode no finer than needed) is the right default on weak devices too, not only
        // when the user switched Memory Saver on.
        ssiv.isTightSampling = saver || ssiv.context.isMemoryConstrained()
        SubsamplingScaleImageView.tileBudgetBytes = if (saver) TILE_BUDGET_CONSTRAINED else TILE_BUDGET_DEFAULT
    }

    @CallSuper
    protected open fun onConfigChanged(settings: ReaderSettings) {
        applyMemoryProfile()
        viewModel.onSettingsChanged(settings)
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
        if (isAnimatedPage) updateToggle()
    }

    fun reloadImage() {
        val state = viewModel.state.value as? PageState.Shown ?: return
        applyMask(state.mask)
        ssiv.setImage(state.source)
    }

    fun bind(data: ReaderPage) {
        boundData?.let { pageLoader.onPageUnbound(it.id) }
        pageLoader.onPageBound(data.id)
        boundData = data
        isUnloadedBySaver = false
        tileLoadErrorCount = 0
        autoRetryCount = 0
        ssiv.isVisible = true
        resetAnimated()
        backgroundJob?.cancel()
        resetAutoLevels()
        settings.applyBackground(itemView)
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
        // The first emission is the rules this page was already checked against
        hiddenImageChanges.drop(1).observe(this) { viewModel.recheck() }
    }

    private fun setPageOnScreen(pageId: Long?) {
        pageOnScreen?.takeIf { it != pageId }?.let { pageLoader.setOnScreen(it, false) }
        pageId?.let { pageLoader.setOnScreen(it, true) }
        pageOnScreen = pageId
    }

    override fun onResume() {
        super.onResume()
        setPageOnScreen(boundData?.id)
        applyPlayback()
        // Memory Saver releases a page's decoded image when it is left; bring it back when it is current again.
        if (isUnloadedBySaver) {
            isUnloadedBySaver = false
            if (viewModel.state.value is PageState.Shown) reloadImage()
        }
        ssiv.applyDownSampling(isForeground = true)
        // At most MAX_AUTO_RETRIES silent retries per bind; the retry button is always available.
        if (viewModel.state.value is PageState.Error && !viewModel.isLoading() && autoRetryCount < MAX_AUTO_RETRIES) {
            autoRetryCount++
            boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false) }
        }
    }

    override fun onPause() {
        super.onPause()
        setPageOnScreen(null)
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
        setPageOnScreen(null)
        boundData?.let { pageLoader.onPageUnbound(it.id) }
        boundData = null
        ssiv.removeCallbacks(preparingStatusRunnable)
        preparingStatusRunnable = null
        viewModel.onRecycle()
        ssiv.recycle()
        resetAnimated()
        resetAutoLevels()
        lastColorFilter = UNSET_SENTINEL
        tileLoadErrorCount = 0
        autoRetryCount = 0
    }

    override fun onTileLoadError(e: Throwable) {
        tileLoadErrorCount++
        if (viewModel.state.value !is PageState.Shown) return
        // Each escalation fires once per bind (== not >=): a tile that can never decode must not turn
        // into an endless reload / forced re-download loop.
        when (tileLoadErrorCount) {
            TILE_ERROR_SOFT -> reloadImage()
            TILE_ERROR_HARD -> boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false) }
        }
    }

    /**
     * Releases decoded tiles under memory pressure (the GPU renderer keeps no tile cache, so the
     * tile bitmaps held by SSIV are the only thing to free). PageLoader's own file cache is
     * trimmed separately by its own ComponentCallbacks2.
     */
    override fun onTrimMemory(level: Int) {
        ssiv.trimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) (animatedView?.drawable as? NativeAnimatedDrawable)?.trimCache()
    }

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
                    applyMask(null)
                    ssiv.setImage(state.preview)
                }
            }

            is PageState.Shown -> Unit

            is PageState.Hidden -> ssiv.recycle()
        }
    }

    /** This page as something a scrub can act on, or null while nothing erasable is on it. */
    fun createEraseTarget(): ErasePageTarget? {
        val page = boundData ?: return null
        val uri = viewModel.shownImage ?: return null
        if (!ssiv.isReady || isAnimatedPage) {
            return null
        }
        return ErasePageTarget(page, uri, ssiv, viewModel.shownRegion, viewModel.shownMask, viewModel.state)
    }

    /**
     * Has to run before every image is set, not only when it changes. Applying the bitmap setting swaps the
     * factory for a plain one, and an image set after that would show exactly what was hidden.
     */
    private fun applyMask(mask: PageMask?) {
        val current = ssiv.regionDecoderFactory
        val plain = (current as? MaskedRegionDecoder.Factory)?.delegate ?: current
        ssiv.regionDecoderFactory = if (mask != null) MaskedRegionDecoder.Factory(plain, mask) else plain
    }

    // ── Color filter ──────────────────────────────────────────────────────────
    // GPU filters (denoise, vibrance, sharpening) are installed via GpuFilteringDecoder
    // in applyBitmapConfig and take effect per-tile on decode.
    // ssiv.colorFilter carries only the CPU/Canvas ColorMatrix:
    //   brightness / contrast / saturation / grayscale / invert / book-tint.

    private fun wantsPlayback(): Boolean {
        if (!isAnimatedPage || !isResumed() || !settings.isAnimatedImagesEnabled || !updateVisibleEnough()) return false
        return animChoice ?: (settings.isAnimatedAutoplay && !settings.isMemorySaver && context.isAnimationsEnabled)
    }

    /** Fraction of the page (0..1) that is on screen; only lists where pages scroll partly out of view override it. */
    protected open fun visibleFraction(): Float = 1f

    /** Plays only while at least half visible and keeps playing until it drops under 30% (no flicker at the edge). */
    private fun updateVisibleEnough(): Boolean {
        val fraction = visibleFraction()
        visibleEnough = if (visibleEnough) fraction >= VISIBLE_STOP else fraction >= VISIBLE_START
        return visibleEnough
    }

    /** Called by a scrolling list whenever it moved, so a half-hidden animation can pause / resume. */
    fun onListScrolled() {
        if (isAnimatedPage) applyPlayback(fromScroll = true)
    }

    /** Starts or stops the current animation according to focus, settings and the user's choice. */
    private fun applyPlayback(fromScroll: Boolean = false) {
        val drawable = animatedView?.drawable as? Animatable
        if (drawable != null) {
            val wanted = wantsPlayback()
            // A scroll tick that changes nothing (already playing / already paused) must cost nothing.
            if (fromScroll && wanted == drawable.isRunning) return
            if (wanted) {
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

    private fun stepAnimation(delta: Int) {
        animChoice = false
        (animatedView?.drawable as? NativeAnimatedDrawable)?.step(delta)
    }

    private fun updateAnimTime() {
        val native = animatedView?.drawable as? NativeAnimatedDrawable ?: return
        val time = animTime?.takeIf { animControls?.isVisible == true } ?: return
        val second = native.elapsedMs(native.currentFrame) / 1000
        if (second == animTimeSecond) return
        animTimeSecond = second
        time.text = "${formatClock(second)} / ${formatClock(native.totalMs / 1000)}"
    }

    private fun formatClock(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

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
        animAdvanced = false
        visibleEnough = true
        animatedView?.isVisible = false
        (animatedView?.drawable as? NativeAnimatedDrawable)?.release()
        animatedView?.disposeImage()
        animToggle?.isVisible = false
        animSeek?.isVisible = false
        animControls?.isVisible = false
        animTimeSecond = -1L
        isSeeking = false
        lastToggleRunning = null
    }

    private fun updateToggle() {
        val button = animToggle ?: return
        button.isVisible = isAnimatedPage
        if (!isAnimatedPage) return
        val native = animatedView?.drawable as? NativeAnimatedDrawable
        animSeek?.let { seek ->
            seek.isVisible = native != null && native.frameCount > 1
            if (seek.isVisible) {
                seek.max = native!!.frameCount - 1
                if (!isSeeking) seek.progress = native.currentFrame
            }
        }
        animControls?.let { controls ->
            controls.isVisible = native != null && native.frameCount > 1 && settings.isAnimatedControls
            if (controls.isVisible) {
                animSpeedButton?.text = formatSpeed(animSpeed)
                animTimeSecond = -1L
                updateAnimTime()
            }
        }
        val running = (animatedView?.drawable as? Animatable)?.isRunning == true
        if (lastToggleRunning == running) return
        lastToggleRunning = running
        button.setIconResource(if (running) R.drawable.ic_action_pause else R.drawable.ic_play)
        button.contentDescription = context.getString(if (running) R.string.animation_pause else R.string.animation_play)
    }

    private fun formatSpeed(speed: Float) = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "×"

    protected fun applyColorFilter() {
        val filter = settings.effectiveColorFilter
        if (ssiv.isReady) {
            ssiv.colorFilter = filter?.toColorFilter(autoStats)
            ssiv.overlayColor = filter?.overlayColor ?: 0
        }
        if (isAnimatedPage) {
            animatedView?.colorFilter = animatedColorFilter()
            animatedView?.applyOverlay(filter?.overlayColor ?: 0)
        }
        if (ssiv.isReady || isAnimatedPage) ensureAutoLevels(filter)
    }

    /**
     * Only the cheap color matrix applies to animations (GPU filters never do). The auto filters use the stats of
     * the first frame: one constant matrix for the whole animation, so nothing flickers between frames.
     */
    private fun animatedColorFilter(): android.graphics.ColorFilter? {
        val filter = settings.effectiveColorFilter ?: return null
        return if (filter.hasAuto && autoStats != null) filter.toColorFilter(autoStats) else filter.toCpuColorFilterOrNull()
    }

    private fun resetAutoLevels() {
        levelsJob?.cancel()
        levelsJob = null
        levelsUri = null
        levelsResolvedFor = null
        autoStats = null
    }

    /** Gets the page's stats (stored, or measured once off the main thread) while an auto filter is on, then re-applies the filter. */
    private fun ensureAutoLevels(filter: ReaderColorFilter?) {
        val uri = levelsUri ?: return
        if (filter?.hasAuto != true || levelsResolvedFor == uri || levelsJob?.isActive == true) return
        levelsJob = lifecycleScope.launch {
            val stats = PageStatsEngine.get(uri)
            if (levelsUri != uri) return@launch
            levelsResolvedFor = uri
            autoStats = stats
            if (stats != null) applyColorFilter()
        }
    }

    private fun applyAutoBackground(state: PageState.Loaded) {
        backgroundJob?.cancel()
        if (settings.background != ReaderBackground.AUTO) return
        val uri = (state.source as? ImageSource.Uri)?.uri ?: return
        backgroundJob = lifecycleScope.launch {
            val color = withContext(Dispatchers.IO) { PageEdgeColor.sample(uri) } ?: return@launch
            if (viewModel.state.value is PageState.Empty) return@launch
            itemView.backgroundTintList = null
            itemView.setBackgroundColor(color)
        }
    }

    private fun showStatic(state: PageState.Loaded) {
        applyAutoBackground(state)
        levelsUri = (state.source as? ImageSource.Uri)?.uri
        bindingInfo.textViewStatus.setText(R.string.loading_)
        applyMask(state.mask)
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

    /** Content sniffs are cached per source: opening a big CBZ's central directory again on every rebind is costly. */
    private fun isAnimatedContent(uri: android.net.Uri): Boolean {
        val key = animatedKey(uri) ?: return probeAnimated(uri)
        synchronized(animatedCache) { animatedCache[key] }?.let { return it }
        return probeAnimated(uri).also { synchronized(animatedCache) { animatedCache[key] = it } }
    }

    private fun animatedKey(uri: android.net.Uri): String? {
        val file = when {
            uri.isFileUri() -> uri.toFileOrNull()
            uri.isZipUri() -> java.io.File(uri.schemeSpecificPart)
            else -> null
        } ?: return null
        return "${uri.fragment}|${file.path}|${file.length()}|${file.lastModified()}"
    }

    private fun probeAnimated(uri: android.net.Uri): Boolean = try {
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
        levelsUri = (loadedState.source as? ImageSource.Uri)?.uri
        animatedView?.let {
            it.colorFilter = animatedColorFilter()
            it.applyOverlay(settings.effectiveColorFilter?.overlayColor ?: 0)
            it.isVisible = true
            it.setImageAsync(page)
        }
        ensureAutoLevels(settings.effectiveColorFilter)
        updateToggle()
        viewModel.state.update { currentState ->
            if (currentState is PageState.Loaded) {
                PageState.Shown(loadedState.source, loadedState.isConverted, loadedState.mask)
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
        private const val MAX_AUTO_RETRIES = 2
        private const val TILE_PIXELS_DEFAULT = 3_145_728
        private const val TILE_PIXELS_CONSTRAINED = 2_359_296
        private const val BASE_PIXELS_DEFAULT = 4_000_000
        private const val BASE_PIXELS_MIN = 1_500_000
        private const val BASE_PIXELS_CONSTRAINED = 3_000_000
        private const val TILE_PIXELS_GPU = 1_048_576
        private const val BASE_PIXELS_GPU = 2_000_000
        private const val TILE_BUDGET_DEFAULT = 64L * 1024 * 1024
        private const val TILE_BUDGET_CONSTRAINED = 32L * 1024 * 1024
        private const val VISIBLE_START = 0.5f
        private const val VISIBLE_STOP = 0.3f
        private val UNSET_SENTINEL = Any()

        // Source -> "is animated", small LRU shared by all holders.
        private val animatedCache = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 256
        }
        private val ANIM_SPEEDS = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f)
    }
}
