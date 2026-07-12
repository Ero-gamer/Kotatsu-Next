package org.koitharu.kotatsu.reader.ui.config

import android.graphics.Bitmap
import android.view.View
import androidx.annotation.CheckResult
import androidx.collection.scatterSetOf
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.BitmapQuality
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.FilteringRegionDecoder
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
import org.koitharu.kotatsu.core.util.ext.isConstrainedDevice
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
		// COLOR DEPTH FIX: ARGB_8888 is always the default. RGB_565 is only used when
		// the user explicitly enables "Reduce memory usage" OR the device is a low-RAM device.
		// Previously the logic was inverted: the setting defaulted to false → RGB_565,
		// meaning all users got washed-out 16-bit colors unless they found the obscure setting.
		// Note: "32-bit color mode" = ARGB_8888 (8 bits × 4 channels). They are the same
		// thing — enabling that setting just ensures the correct format is used.
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

	@CheckResult
	fun applyBitmapConfig(ssiv: SubsamplingScaleImageView): Boolean {
		val isLowRam = ssiv.context.isConstrainedDevice()
		// Auto-downgrade to RGB_565 on constrained devices only when the user has NOT
		// explicitly enabled 32-bit color. If they turned it on, honour their choice.
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

		val baseFactory = LiJpegTurboRegionDecoder.Factory(quality)
		val activeSharpening = sharpening
		val activeVibrance = colorFilter?.vibrance ?: 0f
		val activeDenoise  = colorFilter?.denoise  ?: 0f
		val activeDither   = colorFilter?.dither   ?: 0f
		val activeGrain    = colorFilter?.grain    ?: 0f
		// Only wrap with FilteringRegionDecoder when a CPU-side filter is actually active.
		// Contrast/brightness/saturation/invert/grayscale/book are handled by ssiv.colorFilter
		// (ColorMatrix Paint) at zero memory cost — no extra bitmaps or IntArray pools needed.
		// Installing FilteringRegionDecoder unconditionally for any colorFilter wastes ~32MB
		// per tile on srcPool+outPool IntArrays for filters that do nothing (all params zero).
		val needsCpuFilter = activeSharpening > 0.01f || activeVibrance != 0f ||
			activeDenoise > 0.01f || activeDither > 0.01f || activeGrain > 0.01f
		val newFactory: DecoderFactory<out ImageRegionDecoder> =
			if (needsCpuFilter) {
				FilteringRegionDecoder.Factory(baseFactory, activeSharpening, activeVibrance, activeDenoise, activeDither, activeGrain)
			} else {
				baseFactory
			}

		// Detect any change: bitmap config, filter on/off toggle, or filter params.
		val current = ssiv.regionDecoderFactory
		val configChanged  = current.bitmapConfig != config
		val filterToggled  = (current is FilteringRegionDecoder.Factory) !=
			(newFactory is FilteringRegionDecoder.Factory)
		val filterChanged  = !filterToggled &&
			current is FilteringRegionDecoder.Factory &&
			newFactory is FilteringRegionDecoder.Factory &&
			(current.sharpening != newFactory.sharpening || current.vibrance != newFactory.vibrance ||
			current.denoise != newFactory.denoise || current.dither != newFactory.dither || current.grain != newFactory.grain)

		return if (configChanged || filterToggled || filterChanged) {
			ssiv.regionDecoderFactory = newFactory
			ssiv.bitmapDecoderFactory = SkiaImageDecoder.Factory(config)
			true
		} else {
			false
		}
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
			AppSettings.KEY_CF_VIBRANCE,
			AppSettings.KEY_CF_BOOK,
			AppSettings.KEY_READER_CROP,
		)
		private var job: Job? = null

		override fun onActive() {
			assert(job?.isActive != true)
			job?.cancel()
			// Eagerly re-publish a fresh value so there is zero stale-value window between
			// re-subscription and the first emission from observeImpl(). Without this,
			// a colorFilter change made while inactive (e.g. during orientation change or
			// RecyclerView rebind) would briefly serve the old frozen StateFlow value,
			// causing contrast/vibrance to appear reset until the first DB query completes.
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
				// onStart emits the last-known colorFilter immediately so combine() fires
				// without waiting for Room's async query. This eliminates the brief visual
				// reset flash (colorFilter=null) that appears on process restart or
				// RecyclerView rebind when the manga has a per-manga filter but no global one.
				// Room's real value arrives shortly after and corrects any stale per-manga state.
				mangaId.flatMapLatest { mangaDataRepository.observeColorFilter(it) }
					.onStart { emit(value.colorFilter) },
				// conflate() collapses multiple rapid settings-change events into one.
				// When readerColorFilter setter writes 7 keys atomically (commit=true), the
				// SharedPreferences listener fires once per key. Without conflate(), combine()
				// would rebuild ReaderSettings 7 times for a single logical CF change.
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
