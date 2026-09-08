package org.koitharu.kotatsu.reader.ui.config

import android.graphics.Bitmap
import android.view.View
import androidx.annotation.CheckResult
import androidx.collection.scatterSetOf
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.BitmapQuality
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.GpuFilteringDecoder
import com.davemorrissey.labs.subscaleview.decoder.GpuTileRenderer
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
    val sharpening: Float,
    val isReaderOptimizationEnabled: Boolean,
    val bitmapConfig: Bitmap.Config,
    val is32BitEnabled: Boolean,
    val isPagesNumbersEnabled: Boolean,
    val isPagesCropEnabledStandard: Boolean,
    val isPagesCropEnabledWebtoon: Boolean,
) {

    private constructor(settings: AppSettings, colorFilterOverride: ReaderColorFilter?) : this(
        zoomMode = settings.zoomMode,
        background = settings.readerBackground,
        colorFilter = colorFilterOverride?.takeUnless { it.isEmpty } ?: settings.readerColorFilter,
        sharpening = (colorFilterOverride?.takeUnless { it.isEmpty })?.sharpening
            ?: settings.readerColorFilter?.sharpening ?: 0f,
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
    )

    fun applyBackground(view: View) {
        view.background = background.resolve(view.context)
        view.backgroundTintList = if (background.isLight(view.context)) {
            colorFilter?.getBackgroundTint()
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
     * Returns `true` if the decoder factory changed and SSIV tiles must be reloaded;
     * `false` when only GPU uniform values changed (takes effect on next natural tile load
     * without an explicit reload, which avoids a jarring full-image flash).
     *
     * **GPU filter mapping:**
     * - [ReaderColorFilter.sharpening] → RCAS+USM at ≤0.5, Adaptive at >0.5
     * - [ReaderColorFilter.vibrance]   → `u_enableVibrance`
     * - [ReaderColorFilter.denoise]    → `u_enableDenoise`
     * - Brightness / Contrast / Saturation → CPU ColorMatrix (no tile reload needed)
     *
     * No CPU pixel-loop filters are installed. [FilteringRegionDecoder] is deprecated.
     */
    @CheckResult
    fun applyBitmapConfig(ssiv: SubsamplingScaleImageView): Boolean {
        val isLowRam = ssiv.context.isLowRamDevice()
        val config = if (bitmapConfig == Bitmap.Config.ARGB_8888 && isLowRam && !is32BitEnabled) {
            Bitmap.Config.RGB_565
        } else {
            bitmapConfig
        }
        val quality = if (config == Bitmap.Config.RGB_565) BitmapQuality.MEMORY_SAVING
                      else BitmapQuality.STANDARD

        // ── GPU filter params ──────────────────────────────────────────────
        val cf          = colorFilter
        val gpuDenoise  = (cf?.denoise  ?: 0f) > 0.01f
        val gpuVibrance = (cf?.vibrance ?: 0f) != 0f
        val gpuSharpen  = cf?.sharpening ?: 0f
        val gpuMode     = when {
            gpuSharpen <= 0.01f -> 0
            gpuSharpen <= 0.5f  -> 1   // RCAS+USM
            else                -> 2   // Adaptive
        }
        val gpuAnyActive = gpuDenoise || gpuVibrance || gpuMode != 0

        // ── Factory change detection ───────────────────────────────────────
        val current       = ssiv.regionDecoderFactory
        val configChanged = current.bitmapConfig != config
        val gpuWasActive  = current is GpuFilteringDecoder.Factory
        val gpuToggled    = gpuWasActive != gpuAnyActive
        val factoryChanged = configChanged || gpuToggled

        if (factoryChanged) {
            val baseFactory = LiJpegTurboRegionDecoder.Factory(quality)
            val newFactory: DecoderFactory<out ImageRegionDecoder> = if (gpuAnyActive) {
                // Reuse existing renderer to avoid re-creating the EGL context.
                val renderer = (current as? GpuFilteringDecoder.Factory)?.renderer
                    ?: GpuTileRenderer(ssiv.context)
                GpuFilteringDecoder.Factory(
                    innerFactory   = baseFactory,
                    enableDenoise  = gpuDenoise,
                    enableDarken   = false,
                    enableVibrance = gpuVibrance,
                    sharpenMode    = gpuMode,
                    sharpness      = gpuSharpen.coerceIn(0f, 1f),
                    renderer       = renderer,
                )
            } else {
                // Release old renderer if switching GPU → base.
                (current as? GpuFilteringDecoder.Factory)?.renderer?.release()
                baseFactory
            }
            ssiv.regionDecoderFactory = newFactory
            // Use BitmapQuality constructor — Bitmap.Config constructor is @Deprecated.
            ssiv.bitmapDecoderFactory = SkiaImageDecoder.Factory(quality)
            return true
        }

        // Factory unchanged — update GPU uniform values live (no tile reload needed).
        if (gpuAnyActive && current is GpuFilteringDecoder.Factory) {
            current.renderer.apply {
                enableDenoise  = gpuDenoise
                enableVibrance = gpuVibrance
                sharpenMode    = gpuMode
                sharpness      = gpuSharpen.coerceIn(0f, 1f)
            }
        }
        return false
    }

    class Producer @AssistedInject constructor(
        @Assisted private val mangaId: Flow<Long>,
        private val settings: AppSettings,
        private val mangaDataRepository: MangaDataRepository,
    ) : MediatorStateFlow<ReaderSettings>(ReaderSettings(settings, null)) {

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
            AppSettings.KEY_CF_SHARPENING,
            AppSettings.KEY_CF_SATURATION,
            AppSettings.KEY_CF_VIBRANCE,
            AppSettings.KEY_CF_BOOK,
            AppSettings.KEY_CF_DENOISE,
            AppSettings.KEY_READER_CROP,
        )
        private var job: Job? = null

        override fun onActive() {
            assert(job?.isActive != true)
            job?.cancel()
            publishValue(ReaderSettings(settings, value.colorFilter))
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
                settings.observeChanges()
                    .filter { x -> x == null || x in settingsKeys }
                    .conflate()
                    .onStart { emit(null) },
            ) { mangaCf, _ ->
                ReaderSettings(settings, mangaCf)
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
