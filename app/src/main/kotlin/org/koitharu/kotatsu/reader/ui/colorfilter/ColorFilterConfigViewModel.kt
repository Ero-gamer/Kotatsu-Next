package org.koitharu.kotatsu.reader.ui.colorfilter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.model.parcelable.ParcelableMangaPage
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.reader.domain.ColorFilterProfile
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

    /** null = not yet loaded from DB; use [isReady] to distinguish "loaded null" from "not yet loaded". */
    private val _colorFilter = MutableStateFlow<ReaderColorFilter?>(null)
    val colorFilter = _colorFilter.asStateFlow()

    val onDismiss = MutableEventFlow<Unit>()

    private val _isLocked = MutableStateFlow(false)
    val isLocked = _isLocked.asStateFlow()

    private val _profiles = MutableStateFlow<List<ColorFilterProfile>>(emptyList())
    val profiles = _profiles.asStateFlow()

    init {
        launchLoadingJob {
            _isLocked.value = mangaDataRepository.isColorFilterLocked(manga.id)
        }
        refreshProfiles()
    }

    fun setLocked(locked: Boolean) {
        _isLocked.value = locked
        viewModelScope.launch(Dispatchers.Default) {
            mangaDataRepository.setColorFilterLocked(manga, locked)
        }
    }

    private fun refreshProfiles() {
        viewModelScope.launch(Dispatchers.Default) {
            _profiles.value = mangaDataRepository.getColorFilterProfiles(manga.id)
        }
    }

    /** Saves the current [colorFilter] as a new profile. Returns false if the 10-profile cap is hit. */
    fun saveCurrentAsProfile(name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            val saved = mangaDataRepository.saveColorFilterProfile(manga.id, name, _colorFilter.value ?: ReaderColorFilter.EMPTY)
            refreshProfiles()
            withContext(Dispatchers.Main) { onResult(saved != null) }
        }
    }

    fun applyProfile(profile: ColorFilterProfile) {
        _colorFilter.value = profile.filter.takeUnless { it.isEmpty }
    }

    fun overwriteProfile(profile: ColorFilterProfile) {
        viewModelScope.launch(Dispatchers.Default) {
            mangaDataRepository.overwriteColorFilterProfile(profile, _colorFilter.value ?: ReaderColorFilter.EMPTY)
            refreshProfiles()
        }
    }

    fun renameProfile(profile: ColorFilterProfile, newName: String) {
        viewModelScope.launch(Dispatchers.Default) {
            mangaDataRepository.renameColorFilterProfile(profile, newName)
            refreshProfiles()
        }
    }

    fun deleteProfile(profile: ColorFilterProfile) {
        viewModelScope.launch(Dispatchers.Default) {
            mangaDataRepository.deleteColorFilterProfile(profile)
            refreshProfiles()
        }
    }

    /** Copies [profile] into the global profiles list. */
    fun copyProfileToGlobal(profile: ColorFilterProfile, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            val copied = mangaDataRepository.copyColorFilterProfile(profile, targetMangaId = null)
            withContext(Dispatchers.Main) { onResult(copied != null) }
        }
    }

    /** Loads the global profiles list, for the "import from global" picker. */
    suspend fun loadGlobalProfiles(): List<ColorFilterProfile> = mangaDataRepository.getColorFilterProfiles(null)

    /** Imports [profile] (expected to be from the global list) into this manga's own list. */
    fun importProfile(profile: ColorFilterProfile, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            val copied = mangaDataRepository.copyColorFilterProfile(profile, targetMangaId = manga.id)
            refreshProfiles()
            withContext(Dispatchers.Main) { onResult(copied != null) }
        }
    }

    /**
     * True once the initial color filter has been loaded from the DB.
     * The Activity uses this to skip the initial null emission from [colorFilter],
     * which would otherwise momentarily reset all sliders to zero.
     */
    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    val isChanged: Boolean
        get() = _colorFilter.value != initialColorFilter

    init {
        launchLoadingJob {
            val loaded = runCatching {
                mangaDataRepository.getColorFilter(manga.id)
                    ?: settings.readerColorFilter
            }.getOrElse {
                // Fallback to SharedPrefs on DB error so the UI is never stuck in
                // a "not ready" state with all sliders locked.
                settings.readerColorFilter
            }
            initialColorFilter = loaded
            _colorFilter.value = loaded
            _isReady.value = true
        }
    }

    fun setBrightness(brightness: Float) = updateColorFilter { it.copy(brightness = brightness) }
    fun setContrast(contrast: Float)     = updateColorFilter { it.copy(contrast = contrast) }
    fun setSharpening(sharpening: Float) = updateColorFilter { it.copy(sharpening = sharpening) }
    fun setSaturation(saturation: Float)  = updateColorFilter { it.copy(saturation = saturation) }
    fun setVibrance(vibrance: Float)      = updateColorFilter { it.copy(vibrance = vibrance) }
    fun setInversion(invert: Boolean)    = updateColorFilter { it.copy(isInverted = invert) }
    fun setGrayscale(grayscale: Boolean) = updateColorFilter { it.copy(isGrayscale = grayscale) }
    fun setBookEffect(book: Boolean)     = updateColorFilter { it.copy(isBookBackground = book) }

    fun reset() {
        _colorFilter.value = null
    }

    fun save() {
        launchLoadingJob(Dispatchers.Default) {
            mangaDataRepository.saveColorFilter(manga, _colorFilter.value)
            onDismiss.call(Unit)
        }
    }

    fun saveGlobally() {
        launchLoadingJob(Dispatchers.Default) {
            settings.readerColorFilter = _colorFilter.value
            mangaDataRepository.resetColorFilters()
            onDismiss.call(Unit)
        }
    }

    private inline fun updateColorFilter(block: (ReaderColorFilter) -> ReaderColorFilter) {
        _colorFilter.value = block(_colorFilter.value ?: ReaderColorFilter.EMPTY)
            .takeUnless { it.isEmpty }
    }
}
