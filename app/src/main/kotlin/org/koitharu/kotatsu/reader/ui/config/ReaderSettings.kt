package org.koitharu.kotatsu.reader.ui.config

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.View
import androidx.annotation.CheckResult
import androidx.collection.scatterSetOf
import com.davemorrissey.labs.subscaleview.ImageDownscaler
import com.davemorrissey.labs.subscaleview.ImageScaler
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.BitmapQuality
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.GpuFilteringDecoder
import com.davemorrissey.labs.subscaleview.decoder.GpuFilteringImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.GpuTileRenderer
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.LiJpegTurboRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageDecoder
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.image.codecs.AvifAwareImageDecoder
import org.koitharu.kotatsu.core.image.codecs.AvifAwareRegionDecoder
import org.koitharu.kotatsu.core.image.codecs.NativeAwareImageDecoder
import org.koitharu.kotatsu.core.image.codecs.NativeAwareRegionDecoder
import org.koitharu.kotatsu.core.image.codecs.NativePngMode
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.model.ZoomStart
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderBackground
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.util.MediatorStateFlow
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.reader.domain.CropOverrides
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import org.koitharu.kotatsu.reader.ui.erase.MaskedRegionDecoder

data class ReaderSettings(
    val zoomMode: ZoomMode,
    val zoomStart: ZoomStart,
    val isTapToPan: Boolean,
    val isDoubleTapZoom: Boolean,
    val doubleTapZoomDuration: Int,
    val isLandscapeAutoZoom: Boolean,
    val background: ReaderBackground,
    val colorFilter: ReaderColorFilter?,
    /**
     * Per-manga master switch. When true every image filter (CPU and GPU) is bypassed but
     * [colorFilter] keeps the saved values, so switching back on restores them exactly.
     */
    val isColorFilterDisabled: Boolean,
    val isReaderOptimizationEnabled: Boolean,
    val bitmapConfig: Bitmap.Config,
    val is32BitEnabled: Boolean,
    val isPagesNumbersEnabled: Boolean,
    val isAnimatedImagesEnabled: Boolean,
    /** Opt-in low-RAM mode (see [AppSettings.isMemorySaverEnabled]). */
    val isMemorySaver: Boolean,
    val isAnimatedAutoplay: Boolean,
    val isAnimatedControls: Boolean,
    val isAnimatedAdvance: Boolean,
    val animatedSpeed: Float,
    val animatedLoops: Int,
    val pngDecoderMode: Int,
    val isPagesCropEnabledStandard: Boolean,
    val isPagesCropEnabledWebtoon: Boolean,
    /** Resampling used while zoomed in; applied to SSIV in [applyBitmapConfig]. */
    val scaler: ImageScaler,
    /** Resampling used while zoomed out; applied to SSIV in [applyBitmapConfig]. */
    val downscaler: ImageDownscaler,
    /** Screentone-aware sharpen capping (see GpuFilteringDecoder). */
    val isSmartSharpenEnabled: Boolean,
    /** SSIV's minimum tile DPI (180/240/320); lower values use fewer, blurrier tiles. */
    val tileDpi: Int,
    /** Empty space left and right of a webtoon page, percentage of display width per side. */
    val webtoonSidePadding: Int,
    /** Empty space between the two pages of a double-page spread, percentage of display width. */
    val doublePageGap: Int,
    /** Per-manga border crop; wins over the global setting for the reading style it is set for. */
    val cropOverrides: CropOverrides,
) {
    private constructor(
        settings: AppSettings,
        colorFilterOverride: ReaderColorFilter?,
        isColorFilterDisabled: Boolean,
        cropOverrides: CropOverrides,
    ) : this(
        zoomMode = settings.zoomMode,
        zoomStart = settings.zoomStart,
        isTapToPan = settings.isTapToPan,
        isDoubleTapZoom = settings.isDoubleTapZoomEnabled,
        doubleTapZoomDuration = settings.doubleTapZoomDuration,
        isLandscapeAutoZoom = settings.isLandscapeAutoZoom,
        background = settings.readerBackground,
        colorFilter = colorFilterOverride?.takeUnless { it.isEmpty } ?: settings.readerColorFilter,
        isColorFilterDisabled = isColorFilterDisabled,
        isReaderOptimizationEnabled = settings.isReaderOptimizationEnabled,
        bitmapConfig =
        if (!settings.is32BitColorsEnabled && settings.isReaderOptimizationEnabled) {
            Bitmap.Config.RGB_565
        } else {
            Bitmap.Config.ARGB_8888
        },
        is32BitEnabled = settings.is32BitColorsEnabled,
        isPagesNumbersEnabled = settings.isPagesNumbersEnabled,
        isAnimatedImagesEnabled = settings.isAnimatedImagesEnabled,
        isMemorySaver = settings.isMemorySaverEnabled,
        isAnimatedAutoplay = settings.isAnimatedAutoplay,
        isAnimatedControls = settings.isAnimatedControls,
        isAnimatedAdvance = settings.isAnimatedAdvance,
        animatedSpeed = settings.animatedSpeed,
        animatedLoops = settings.animatedLoops,
        pngDecoderMode = settings.pngDecoderMode,
        isPagesCropEnabledStandard = cropOverrides.pages ?: settings.isPagesCropEnabled(ReaderMode.STANDARD),
        isPagesCropEnabledWebtoon = cropOverrides.webtoon ?: settings.isPagesCropEnabled(ReaderMode.WEBTOON),
        scaler = settings.readerScaler,
        downscaler = settings.readerDownscaler,
        isSmartSharpenEnabled = settings.isSmartSharpenEnabled,
        tileDpi = settings.readerTileDpi,
        webtoonSidePadding = settings.webtoonSidePadding,
        doublePageGap = settings.doublePageGap,
        cropOverrides = cropOverrides,
    )

    /** The filter that is actually applied: `null` while filters are switched off. */
    val effectiveColorFilter: ReaderColorFilter?
        get() = if (isColorFilterDisabled) null else colorFilter

    fun applyBackground(view: View) {
        val tint = if (background.isLight(view.context)) effectiveColorFilter?.getBackgroundTint()?.defaultColor else null
        // Every settings emission (colour slider drags included) reaches every holder: rebuild the drawable only on a
        // real change. AUTO is repainted per page by the holder, so it is always re-applied.
        val key = Pair(background, tint)
        if (background != ReaderBackground.AUTO && view.getTag(R.id.tag_background) == key) return
        view.background = background.resolve(view.context)
        view.backgroundTintList = tint?.let { ColorStateList.valueOf(it) }
        view.setTag(R.id.tag_background, key)
    }

    fun isPagesCropEnabled(isWebtoon: Boolean) = if (isWebtoon) {
        isPagesCropEnabledWebtoon
    } else {
        isPagesCropEnabledStandard
    }

    /** Whether any GPU shader filter runs on the decoded tiles (see [applyBitmapConfig]). */
    val isGpuFilterActive: Boolean
        get() = GpuParams.from(effectiveColorFilter, isSmartSharpenEnabled).isAnyActive

    /**
     * Applies bitmap quality and GPU filter configuration to [ssiv].
     *
     * Returns `true` if the decoder factory changed OR any GPU filter value changed, so SSIV
     * must reload: GPU filters are baked into tile bitmaps at decode time, so already-decoded
     * tiles never pick up a new value on their own. Returns `false` when nothing GPU-related
     * changed.
     *
     * Also applies [scaler] to [ssiv]. That is a draw-time property (no reload, it just
     * invalidates the view).
     *
     * When [isColorFilterDisabled] is set, [effectiveColorFilter] is `null`, so no GPU filter is
     * active and the GPU decoders are uninstalled (tiles reload once); the saved values are kept.
     *
     * The GPU pass is installed on BOTH SSIV decode paths — the tiled [ImageRegionDecoder] and the
     * whole-bitmap [com.davemorrissey.labs.subscaleview.decoder.ImageDecoder] (single-tile images,
     * e.g. landscape pages) — sharing one [GpuTileRenderer].
     *
     * **GPU filter mapping** — every value is taken directly from an explicit [ReaderColorFilter]
     * field, never inferred from another field's magnitude:
     * - [ReaderColorFilter.deband]                  → f3kdb-documented deband (independent impl.)
     * - [ReaderColorFilter.rcas]                    → real AMD FidelityFX RCAS
     * - [ReaderColorFilter.adaptiveSharpen]          → real bacondither Adaptive-Sharpen
     * - [ReaderColorFilter.vibrance]                → `u_enableVibrance` + `u_vibranceIntensity`
     * - [ReaderColorFilter.denoise]                 → `u_enableDenoise` + `u_denoiseStrength`
     * - [ReaderColorFilter.isLineDarkenEnabled]     → `u_enableDarken`
     * - Brightness / Contrast / Saturation → CPU ColorMatrix (no tile reload needed)
     *
     * All GPU filters are independent and stackable; none excludes another.
     *
     * No CPU pixel-loop filters are installed. [FilteringRegionDecoder] is deprecated.
     */
    @CheckResult
    fun applyBitmapConfig(ssiv: SubsamplingScaleImageView): Boolean {
        NativePngMode.mode = pngDecoderMode
        ssiv.scaler = scaler
        ssiv.downscaler = downscaler
        // setMinimumTileDpi() resets the view and re-decodes every tile, so only call it on a real change.
        if (ssiv.getTag(R.id.tag_tile_dpi) != tileDpi) {
            ssiv.setMinimumTileDpi(tileDpi)
            ssiv.setTag(R.id.tag_tile_dpi, tileDpi)
        }
        // ── GPU filter params — read directly, no cross-field inference ─────
        val gpu = GpuParams.from(effectiveColorFilter, isSmartSharpenEnabled)

        // With Memory Saver on, pages decode as RGB_565 (half the memory) unless the user turned
        // "32-bit color mode" on, which always wins (dithered 32-bit output is kept). The GPU pass uploads
        // RGB_565 as it is and hands the result back as RGB_565, so the saving holds under filters too;
        // only deband is excluded, because its fine grain would be re-quantised to 5-6 bits.
        val isAutoRgb565 = isMemorySaver && !is32BitEnabled && !gpu.isDebandActive
        val config =
            if (bitmapConfig == Bitmap.Config.ARGB_8888 && isAutoRgb565) {
                Bitmap.Config.RGB_565
            } else {
                bitmapConfig
            }
        val quality =
            if (config == Bitmap.Config.RGB_565) {
                BitmapQuality.MEMORY_SAVING
            } else {
                BitmapQuality.STANDARD
            }

        // ── Factory change detection ───────────────────────────────────────
        // A page with something hidden wraps the factory (see BasePageHolder.applyMask); it is put back before the next image
        val current = ssiv.regionDecoderFactory.let { (it as? MaskedRegionDecoder.Factory)?.delegate ?: it }
        val configChanged = current.bitmapConfig != config
        val gpuWasActive = current is GpuFilteringDecoder.Factory
        val gpuToggled = gpuWasActive != gpu.isAnyActive
        val factoryChanged = configChanged || gpuToggled

        if (factoryChanged) {
            // JPEG XL natively, still AVIF through libavif in memory (below Android 12), the rest as before
            val baseFactory = NativeAwareRegionDecoder.Factory(AvifAwareRegionDecoder.Factory(LiJpegTurboRegionDecoder.Factory(quality)))
            val baseImageFactory = NativeAwareImageDecoder.Factory(AvifAwareImageDecoder.Factory(SkiaImageDecoder.Factory(quality)))
            var newImageFactory: DecoderFactory<out ImageDecoder> = baseImageFactory
            val newFactory: DecoderFactory<out ImageRegionDecoder> =
                if (gpu.isAnyActive) {
                    // Reuse existing renderer to avoid re-creating the EGL context.
                    val renderer =
                        (current as? GpuFilteringDecoder.Factory)?.renderer
                            ?: GpuTileRenderer(ssiv.context.applicationContext)
                    // Same renderer on the whole-bitmap path, so single-tile images are filtered too.
                    newImageFactory = GpuFilteringImageDecoder.Factory(baseImageFactory, renderer)
                    gpu.toFactory(baseFactory, renderer)
                } else {
                    // Release old renderer if switching GPU → base.
                    (current as? GpuFilteringDecoder.Factory)?.renderer?.release()
                    baseFactory
                }
            ssiv.regionDecoderFactory = newFactory
            ssiv.bitmapDecoderFactory = newImageFactory
            return true
        }

        // Factory unchanged — if a GPU value changed, update the shared renderer and ask for a
        // reload, otherwise already-decoded tiles would keep showing the old look.
        if (gpu.isAnyActive && current is GpuFilteringDecoder.Factory && !gpu.matches(current.renderer)) {
            gpu.applyTo(current.renderer)
            return true
        }
        return false
    }

    /** Snapshot of every GPU shader uniform derived from a [ReaderColorFilter]. */
    private data class GpuParams(
        val denoise: Float,
        val vibrance: Float,
        val isLineDarken: Boolean,
        val rcas: Float,
        val adaptiveSharpen: Float,
        val deband: Float,
        val binarize: Float,
        val smartSharpen: Boolean,
    ) {
        val isDebandActive: Boolean
            get() = deband.isOn()

        val isAnyActive: Boolean
            get() =
                denoise.isOn() || vibrance.isOn() || isLineDarken || rcas.isOn() ||
                    adaptiveSharpen.isOn() || deband.isOn() || binarize.isOn()

        fun toFactory(
            innerFactory: DecoderFactory<out ImageRegionDecoder>,
            renderer: GpuTileRenderer,
        ) = GpuFilteringDecoder.Factory(
            innerFactory = innerFactory,
            enableDeband = deband.isOn(),
            debandIntensity = deband,
            enableDenoise = denoise.isOn(),
            enableDarken = isLineDarken,
            enableVibrance = vibrance.isOn(),
            denoiseStrength = denoise,
            vibranceIntensity = vibrance,
            enableRcas = rcas.isOn(),
            rcasIntensity = rcas,
            enableAdaptiveSharpen = adaptiveSharpen.isOn(),
            adaptiveSharpenIntensity = adaptiveSharpen,
            enableScreentoneCap = smartSharpen,
            enableBinarize = binarize.isOn(),
            binarizeStrength = binarize,
            renderer = renderer,
        )

        fun applyTo(renderer: GpuTileRenderer) {
            renderer.apply {
                enableDeband = deband.isOn()
                debandIntensity = deband
                enableDenoise = denoise.isOn()
                enableDarken = isLineDarken
                enableVibrance = vibrance.isOn()
                denoiseStrength = denoise
                vibranceIntensity = vibrance
                enableRcas = rcas.isOn()
                rcasIntensity = rcas
                enableAdaptiveSharpen = adaptiveSharpen.isOn()
                adaptiveSharpenIntensity = adaptiveSharpen
                enableScreentoneCap = smartSharpen
                enableBinarize = binarize.isOn()
                binarizeStrength = binarize
            }
        }

        /** `true` if [renderer] already holds exactly these values (no reload needed). */
        fun matches(renderer: GpuTileRenderer): Boolean = renderer.enableDeband == deband.isOn() && renderer.debandIntensity == deband &&
            renderer.enableDenoise == denoise.isOn() && renderer.denoiseStrength == denoise &&
            renderer.enableVibrance == vibrance.isOn() && renderer.vibranceIntensity == vibrance &&
            renderer.enableDarken == isLineDarken &&
            renderer.enableRcas == rcas.isOn() && renderer.rcasIntensity == rcas &&
            renderer.enableAdaptiveSharpen == adaptiveSharpen.isOn() &&
            renderer.adaptiveSharpenIntensity == adaptiveSharpen &&
            renderer.enableScreentoneCap == smartSharpen &&
            renderer.enableBinarize == binarize.isOn() && renderer.binarizeStrength == binarize

        companion object {
            /** A filter counts as enabled above this intensity (same threshold as before the split). */
            private const val ON_THRESHOLD = 0.01f

            private fun Float.isOn() = this > ON_THRESHOLD

            fun from(
                cf: ReaderColorFilter?,
                smartSharpen: Boolean,
            ) = GpuParams(
                denoise = (cf?.denoise ?: 0f).coerceIn(0f, 1f),
                vibrance = (cf?.vibrance ?: 0f).coerceIn(0f, 1f),
                isLineDarken = cf?.isLineDarkenEnabled == true,
                rcas = (cf?.rcas ?: 0f).coerceIn(0f, 1f),
                adaptiveSharpen = (cf?.adaptiveSharpen ?: 0f).coerceIn(0f, 1f),
                deband = (cf?.deband ?: 0f).coerceIn(0f, 1f),
                binarize = (cf?.binarize ?: 0f).coerceIn(0f, 1f),
                smartSharpen = smartSharpen,
            )
        }
    }

    class Producer
    @AssistedInject
    constructor(
        @Assisted private val mangaId: Flow<Long>,
        private val settings: AppSettings,
        private val mangaDataRepository: MangaDataRepository,
    ) : MediatorStateFlow<ReaderSettings>(ReaderSettings(settings, null, false, CropOverrides.NONE)) {
        private val settingsKeys =
            scatterSetOf(
                AppSettings.KEY_ZOOM_MODE,
                AppSettings.KEY_PAGES_NUMBERS,
                AppSettings.KEY_ANIMATED_IMAGES,
                AppSettings.KEY_MEMORY_SAVER,
                AppSettings.KEY_ANIMATED_AUTOPLAY,
                AppSettings.KEY_ANIMATED_CONTROLS,
                AppSettings.KEY_ANIMATED_ADVANCE,
                AppSettings.KEY_ANIMATED_SPEED,
                AppSettings.KEY_ANIMATED_LOOPS,
                AppSettings.KEY_PNG_DECODER,
                AppSettings.KEY_READER_BACKGROUND,
                AppSettings.KEY_32BIT_COLOR,
                AppSettings.KEY_READER_OPTIMIZE,
                AppSettings.KEY_CF_CONTRAST,
                AppSettings.KEY_CF_BRIGHTNESS,
                AppSettings.KEY_CF_INVERTED,
                AppSettings.KEY_CF_GRAYSCALE,
                AppSettings.KEY_CF_SATURATION,
                AppSettings.KEY_CF_VIBRANCE,
                AppSettings.KEY_CF_BOOK,
                AppSettings.KEY_CF_DENOISE,
                AppSettings.KEY_CF_RCAS,
                AppSettings.KEY_CF_ADAPTIVE_SHARPEN,
                AppSettings.KEY_CF_DEBAND,
                AppSettings.KEY_CF_BINARIZE,
                AppSettings.KEY_READER_SCALER,
                AppSettings.KEY_READER_DOWNSCALER,
                AppSettings.KEY_READER_SMART_SHARPEN,
                AppSettings.KEY_CF_LINE_DARKEN,
                AppSettings.KEY_CF_AUTO_CONTRAST,
                AppSettings.KEY_CF_AUTO_BRIGHTNESS,
                AppSettings.KEY_CF_AUTO_SATURATION,
                AppSettings.KEY_CF_OVERLAY_R,
                AppSettings.KEY_CF_OVERLAY_G,
                AppSettings.KEY_CF_OVERLAY_B,
                AppSettings.KEY_CF_OVERLAY_A,
                AppSettings.KEY_READER_CROP,
                AppSettings.KEY_DOUBLE_TAP_ZOOM,
                AppSettings.KEY_DOUBLE_TAP_SPEED,
                AppSettings.KEY_DOUBLE_PAGE_GAP,
                AppSettings.KEY_LANDSCAPE_ZOOM,
                AppSettings.KEY_TAP_TO_PAN,
                AppSettings.KEY_ZOOM_START,
                AppSettings.KEY_READER_TILE_DPI,
                AppSettings.KEY_WEBTOON_SIDE_PADDING,
            )
        private var job: Job? = null

        override fun onActive() {
            assert(job?.isActive != true)
            job?.cancel()
            publishValue(ReaderSettings(settings, value.colorFilter, value.isColorFilterDisabled, value.cropOverrides))
            job =
                processLifecycleScope.launch(Dispatchers.Default) {
                    observeImpl()
                }
        }

        override fun onInactive() {
            job?.cancel()
            job = null
        }

        private suspend fun observeImpl() {
            combine(
                mangaId
                    .flatMapLatest { mangaDataRepository.observeColorFilter(it) }
                    .onStart { emit(value.colorFilter) },
                mangaId
                    .flatMapLatest { mangaDataRepository.observeColorFilterDisabled(it) }
                    .onStart { emit(value.isColorFilterDisabled) },
                mangaId
                    .flatMapLatest { mangaDataRepository.observeCropOverrides(it) }
                    .onStart { emit(value.cropOverrides) },
                settings
                    .observeChanges()
                    .filter { x -> x == null || x in settingsKeys }
                    .conflate()
                    .onStart { emit(null) },
            ) { mangaCf, isDisabled, crop, _ ->
                ReaderSettings(settings, mangaCf, isDisabled, crop)
            }.collect {
                publishValue(it)
            }
        }

        @AssistedFactory
        interface Factory {
            fun create(mangaId: Flow<Long>): Producer
        }
    }
}
