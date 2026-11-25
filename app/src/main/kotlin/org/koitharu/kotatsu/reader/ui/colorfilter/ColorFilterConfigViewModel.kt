package org.koitharu.kotatsu.reader.ui.colorfilter

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.model.parcelable.ParcelableMangaPage
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import javax.inject.Inject

@HiltViewModel
class ColorFilterConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val settings: AppSettings,
    private val mangaDataRepository: MangaDataRepository,
) : BaseViewModel() {

    private val manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga

    val preview = savedStateHandle.require<ParcelableMangaPage>(AppRouter.KEY_PAGES).page

    private var initialColorFilter: ReaderColorFilter? = null
    val colorFilter = MutableStateFlow<ReaderColorFilter?>(null)
    val onDismiss = MutableEventFlow<Unit>()

    val isChanged: Boolean
        get() = colorFilter.value != initialColorFilter

    init {
        launchLoadingJob {
            // Filters switched off for this manga mean "defaults" here, not the (possibly tweaked) global values.
            val ownFilter = mangaDataRepository.getColorFilter(manga.id)
            val isDisabled = mangaDataRepository.observeColorFilterDisabled(manga.id).first()
            initialColorFilter = ownFilter ?: settings.readerColorFilter.takeUnless { isDisabled }
            colorFilter.value = initialColorFilter
        }
    }

    fun setBrightness(brightness: Float) = updateColorFilter { it.copy(brightness = brightness) }
    fun setContrast(contrast: Float) = updateColorFilter { it.copy(contrast = contrast) }
    fun setRcas(intensity: Float) = updateColorFilter { it.copy(rcas = intensity) }
    fun setAdaptiveSharpen(intensity: Float) = updateColorFilter { it.copy(adaptiveSharpen = intensity) }
    fun setDeband(intensity: Float) = updateColorFilter { it.copy(deband = intensity) }
    fun setBinarize(strength: Float) = updateColorFilter { it.copy(binarize = strength) }
    fun setSaturation(saturation: Float) = updateColorFilter { it.copy(saturation = saturation) }
    fun setVibrance(vibrance: Float) = updateColorFilter { it.copy(vibrance = vibrance) }
    fun setDenoise(denoise: Float) = updateColorFilter { it.copy(denoise = denoise) }
    fun setLineDarkenEnabled(enabled: Boolean) = updateColorFilter { it.copy(isLineDarkenEnabled = enabled) }

    // setDither / setGrain removed — filters eliminated from GPU pipeline
    fun setInversion(invert: Boolean) = updateColorFilter { it.copy(isInverted = invert) }
    fun setGrayscale(grayscale: Boolean) = updateColorFilter { it.copy(isGrayscale = grayscale) }
    fun setAutoContrast(enabled: Boolean) = updateColorFilter { it.copy(isAutoContrast = enabled) }
    fun setAutoBrightness(enabled: Boolean) = updateColorFilter { it.copy(isAutoBrightness = enabled) }
    fun setAutoSaturation(enabled: Boolean) = updateColorFilter { it.copy(isAutoSaturation = enabled) }
    fun setOverlayRed(value: Float) = updateColorFilter { it.copy(overlayRed = value) }
    fun setOverlayGreen(value: Float) = updateColorFilter { it.copy(overlayGreen = value) }
    fun setOverlayBlue(value: Float) = updateColorFilter { it.copy(overlayBlue = value) }
    fun setOverlayAlpha(value: Float) = updateColorFilter { it.copy(overlayAlpha = value) }
    fun setBookEffect(book: Boolean) = updateColorFilter { it.copy(isBookBackground = book) }

    fun reset() {
        colorFilter.value = null
    }

    fun save() {
        launchLoadingJob(Dispatchers.Default) {
            val filter = colorFilter.value
            mangaDataRepository.saveColorFilter(manga, filter)
            // No stored values would fall back to the global filter; an empty result (e.g. after Reset) must
            // mean defaults for this manga, so the per-manga "filters off" flag is kept in step with it.
            mangaDataRepository.setColorFilterDisabled(manga, isDisabled = filter == null)
            onDismiss.call(Unit)
        }
    }

    fun saveGlobally() {
        launchLoadingJob(Dispatchers.Default) {
            settings.readerColorFilter = colorFilter.value
            mangaDataRepository.resetColorFilters()
            onDismiss.call(Unit)
        }
    }

    private inline fun updateColorFilter(block: (ReaderColorFilter) -> ReaderColorFilter) {
        colorFilter.value = block(
            colorFilter.value ?: ReaderColorFilter.EMPTY,
        ).takeUnless { it.isEmpty }
    }
}
