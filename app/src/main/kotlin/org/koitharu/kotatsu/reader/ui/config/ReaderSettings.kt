package org.koitharu.kotatsu.reader.ui.config

import android.graphics.Bitmap
import android.view.View
import androidx.annotation.CheckResult
import androidx.collection.scatterSetOf
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
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderBackground
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.util.MediatorStateFlow
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter

data class ReaderSettings(
    val zoomMode: ZoomMode,
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
    val isPagesCropEnabledStandard: Boolean,
    val isPagesCropEnabledWebtoon: Boolean,
    /** Resampling used while zoomed in; applied to SSIV in [applyBitmapConfig]. */
    val scaler: ImageScaler,
) {

    private constructor(
        settings: AppSettings,
        colorFilterOverride: ReaderColorFilter?,
        isColorFilterDisabled: Boolean,
    ) : this(
        zoomMode = settings.zoomMode,
        background = settings.readerBackground,
        colorFilter = colorFilterOverride?.takeUnless { it.isEmpty } ?: settings.readerColorFilter,
        isColorFilterDisabled = isColorFilterDisabled,
        isReaderOptimizationEnabled = settings.isReaderOptimizationEnabled,
        bitmapConfig = if (!settings.is32BitColorsEnabled && settings.isReaderOptimizationEnabled) {
            Bitmap.Config.RGB_565
        } else {
            Bitmap.Config.ARGB_8888
        },
        is32BitEnabled = settings.is32BitColorsEnabled,
        isPagesNumbersEnabled = settings.isPagesNumbersEnabled,
        isPagesCropEnabledStandard = settings.isPagesCropEnabled(ReaderMode.STANDARD),
        isPagesCropEnabledWebtoon = settings.isPagesCropEnabled(ReaderMode.WEBTOON),
        scaler = settings.readerScaler,
    )

    /** The filter that is actually applied: `null` while filters are switched off. */
    val effectiveColorFilter: ReaderColorFilter?
        get() = if (isColorFilterDisabled) null else colorFilter

    fun applyBackground(view: View) {
        view.background = background.resolve(view.context)
        view.backgroundTintList = if (background.isLight(view.context)) {
            effectiveColorFilter?.getBackgroundTint()
        } else {
            null
        }
    }

    fun isPagesCropEnabled(isWebtoon: Boolean) = if (isWebtoon) {
        isPagesCropEnabledWebtoon
    } else {
        isPagesCropEnabledStandard
    }

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
     * - [ReaderColorFilter.rcasUsm]                → `u_enableRcasUsm` + `u_rcasUsmIntensity`
     * - [ReaderColorFilter.adaptiveSmoothstep]      → `u_enableAdaptiveSmoothstep` + `u_adaptiveSmoothstepIntensity`
     * - [ReaderColorFilter.adaptiveSigmoid]         → `u_enableAdaptiveSigmoid` + `u_adaptiveSigmoidIntensity`
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
        ssiv.scaler = scaler
        val isLowRam = ssiv.context.isLowRamDevice()
        val config = if (bitmapConfig == Bitmap.Config.ARGB_8888 && isLowRam && !is32BitEnabled) {
            Bitmap.Config.RGB_565
        } else {
            bitmapConfig
        }
        val quality = if (config == Bitmap.Config.RGB_565) {
            BitmapQuality.MEMORY_SAVING
        } else {
            BitmapQuality.STANDARD
        }

        // ── GPU filter params — read directly, no cross-field inference ─────
        val gpu = GpuParams.from(effectiveColorFilter)

        // ── Factory change detection ───────────────────────────────────────
        val current = ssiv.regionDecoderFactory
        val configChanged = current.bitmapConfig != config
        val gpuWasActive = current is GpuFilteringDecoder.Factory
        val gpuToggled = gpuWasActive != gpu.isAnyActive
        val factoryChanged = configChanged || gpuToggled

        if (factoryChanged) {
            val baseFactory = LiJpegTurboRegionDecoder.Factory(quality)
            val baseImageFactory = SkiaImageDecoder.Factory(quality)
            var newImageFactory: DecoderFactory<out ImageDecoder> = baseImageFactory
            val newFactory: DecoderFactory<out ImageRegionDecoder> = if (gpu.isAnyActive) {
                // Reuse existing renderer to avoid re-creating the EGL context.
                val renderer = (current as? GpuFilteringDecoder.Factory)?.renderer
                    ?: GpuTileRenderer(ssiv.context)
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
        val rcasUsm: Float,
        val adaptiveSmoothstep: Float,
        val adaptiveSigmoid: Float,
    ) {
        val isAnyActive: Boolean
            get() = denoise.isOn() || vibrance.isOn() || isLineDarken || rcasUsm.isOn() ||
                adaptiveSmoothstep.isOn() || adaptiveSigmoid.isOn()

        fun toFactory(
            innerFactory: DecoderFactory<out ImageRegionDecoder>,
            renderer: GpuTileRenderer,
        ) = GpuFilteringDecoder.Factory(
            innerFactory = innerFactory,
            enableDenoise = denoise.isOn(),
            enableDarken = isLineDarken,
            enableVibrance = vibrance.isOn(),
            denoiseStrength = denoise,
            vibranceIntensity = vibrance,
            enableRcasUsm = rcasUsm.isOn(),
            rcasUsmIntensity = rcasUsm,
            enableAdaptiveSmoothstep = adaptiveSmoothstep.isOn(),
            adaptiveSmoothstepIntensity = adaptiveSmoothstep,
            enableAdaptiveSigmoid = adaptiveSigmoid.isOn(),
            adaptiveSigmoidIntensity = adaptiveSigmoid,
            renderer = renderer,
        )

        fun applyTo(renderer: GpuTileRenderer) {
            renderer.apply {
                enableDenoise = denoise.isOn()
                enableDarken = isLineDarken
                enableVibrance = vibrance.isOn()
                denoiseStrength = denoise
                vibranceIntensity = vibrance
                enableRcasUsm = rcasUsm.isOn()
                rcasUsmIntensity = rcasUsm
                enableAdaptiveSmoothstep = adaptiveSmoothstep.isOn()
                adaptiveSmoothstepIntensity = adaptiveSmoothstep
                enableAdaptiveSigmoid = adaptiveSigmoid.isOn()
                adaptiveSigmoidIntensity = adaptiveSigmoid
            }
        }

        /** `true` if [renderer] already holds exactly these values (no reload needed). */
        fun matches(renderer: GpuTileRenderer): Boolean =
            renderer.enableDenoise == denoise.isOn() && renderer.denoiseStrength == denoise &&
                renderer.enableVibrance == vibrance.isOn() && renderer.vibranceIntensity == vibrance &&
                renderer.enableDarken == isLineDarken &&
                renderer.enableRcasUsm == rcasUsm.isOn() && renderer.rcasUsmIntensity == rcasUsm &&
                renderer.enableAdaptiveSmoothstep == adaptiveSmoothstep.isOn() &&
                renderer.adaptiveSmoothstepIntensity == adaptiveSmoothstep &&
                renderer.enableAdaptiveSigmoid == adaptiveSigmoid.isOn() &&
                renderer.adaptiveSigmoidIntensity == adaptiveSigmoid

        companion object {
            /** A filter counts as enabled above this intensity (same threshold as before the split). */
            private const val ON_THRESHOLD = 0.01f

            private fun Float.isOn() = this > ON_THRESHOLD

            fun from(cf: ReaderColorFilter?) = GpuParams(
                denoise = (cf?.denoise ?: 0f).coerceIn(0f, 1f),
                vibrance = (cf?.vibrance ?: 0f).coerceIn(0f, 1f),
                isLineDarken = cf?.isLineDarkenEnabled == true,
                rcasUsm = (cf?.rcasUsm ?: 0f).coerceIn(0f, 1f),
                adaptiveSmoothstep = (cf?.adaptiveSmoothstep ?: 0f).coerceIn(0f, 1f),
                adaptiveSigmoid = (cf?.adaptiveSigmoid ?: 0f).coerceIn(0f, 1f),
            )
        }
    }

    class Producer @AssistedInject constructor(
        @Assisted private val mangaId: Flow<Long>,
        private val settings: AppSettings,
        private val mangaDataRepository: MangaDataRepository,
    ) : MediatorStateFlow<ReaderSettings>(ReaderSettings(settings, null, false)) {

        private val settingsKeys = scatterSetOf(
            AppSettings.KEY_ZOOM_MODE,
            AppSettings.KEY_PAGES_NUMBERS,
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
            AppSettings.KEY_CF_RCAS_USM,
            AppSettings.KEY_CF_ADAPTIVE_SMOOTHSTEP,
            AppSettings.KEY_CF_ADAPTIVE_SIGMOID,
            AppSettings.KEY_READER_SCALER,
            AppSettings.KEY_CF_LINE_DARKEN,
            AppSettings.KEY_READER_CROP,
        )
        private var job: Job? = null

        override fun onActive() {
            assert(job?.isActive != true)
            job?.cancel()
            publishValue(ReaderSettings(settings, value.colorFilter, value.isColorFilterDisabled))
            job = processLifecycleScope.launch(Dispatchers.Default) {
                observeImpl()
            }
        }

        override fun onInactive() {
            job?.cancel()
            job = null
        }

        private suspend fun observeImpl() {
            combine(
                mangaId.flatMapLatest { mangaDataRepository.observeColorFilter(it) }
                    .onStart { emit(value.colorFilter) },
                mangaId.flatMapLatest { mangaDataRepository.observeColorFilterDisabled(it) }
                    .onStart { emit(value.isColorFilterDisabled) },
                settings.observeChanges()
                    .filter { x -> x == null || x in settingsKeys }
                    .conflate()
                    .onStart { emit(null) },
            ) { mangaCf, isDisabled, _ ->
                ReaderSettings(settings, mangaCf, isDisabled)
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
