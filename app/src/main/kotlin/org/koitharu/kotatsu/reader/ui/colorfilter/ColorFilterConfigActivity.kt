package org.koitharu.kotatsu.reader.ui.colorfilter

import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.transformations
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.image.ImageFiltersTransformation
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.setChecked
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.core.util.progress.ImageRequestIndicatorListener
import org.koitharu.kotatsu.databinding.ActivityColorFilterBinding
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.format
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import javax.inject.Inject

@AndroidEntryPoint
class ColorFilterConfigActivity :
    BaseActivity<ActivityColorFilterBinding>(),
    Slider.OnChangeListener,
    View.OnClickListener,
    CompoundButton.OnCheckedChangeListener {

    @Inject
    lateinit var coil: ImageLoader

    private val viewModel: ColorFilterConfigViewModel by viewModels()

    /** Tracks the in-flight Coil request for the sharpening preview so it can be cancelled. */
    private var gpuPreviewDisposable: coil3.request.Disposable? = null

    /**
     * True once the before-image has finished loading into [imageViewBefore].
     * [updateGpuPreview] is deferred until this is true so we don't fire a Coil
     * request before the source image is available in memory cache.
     */
    private var beforeImageReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityColorFilterBinding.inflate(layoutInflater))
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)

        val percentFormatter = PercentLabelFormatter(resources)
        val signedFormatter  = SignedPercentLabelFormatter(resources)

        viewBinding.sliderBrightness.addOnChangeListener(this)
        viewBinding.sliderContrast.addOnChangeListener(this)
        viewBinding.sliderSharpening?.addOnChangeListener(this)
        viewBinding.sliderVibrance?.addOnChangeListener(this)

        viewBinding.sliderBrightness.setLabelFormatter(percentFormatter)
        viewBinding.sliderContrast.setLabelFormatter(percentFormatter)
        viewBinding.sliderSharpening?.setLabelFormatter(percentFormatter)
        viewBinding.sliderVibrance?.setLabelFormatter(signedFormatter)

        viewBinding.switchInvert.setOnCheckedChangeListener(this)
        viewBinding.switchGrayscale.setOnCheckedChangeListener(this)
        viewBinding.switchBook.setOnCheckedChangeListener(this)
        viewBinding.buttonDone.setOnClickListener(this)
        viewBinding.buttonReset.setOnClickListener(this)

        onBackPressedDispatcher.addCallback(ColorFilterConfigBackPressedDispatcher(this, viewModel))

        // Wait for isReady before reacting to colorFilter — avoids the brief "all sliders at
        // zero" flash that happens while the DB is loading the real filter value.
        viewModel.isReady.observe(this) { ready ->
            if (ready) {
                viewModel.colorFilter.observe(this, this::onColorFilterChanged)
            }
        }
        viewModel.isLoading.observe(this, this::onLoadingChanged)
        viewModel.onDismiss.observeEvent(this) { finishAfterTransition() }

        loadPreview(viewModel.preview)
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val barsInsets = insets.systemBarsInsets
        viewBinding.root.setPadding(barsInsets.left, barsInsets.top, barsInsets.right, barsInsets.bottom)
        return insets.consumeAllSystemBarsInsets()
    }

    override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
        if (!fromUser) return
        when (slider.id) {
            R.id.slider_brightness -> viewModel.setBrightness(value)
            R.id.slider_contrast   -> viewModel.setContrast(value)
            R.id.slider_sharpening -> viewModel.setSharpening(value)
            R.id.slider_vibrance   -> viewModel.setVibrance(value)
        }
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        when (buttonView.id) {
            R.id.switch_invert    -> viewModel.setInversion(isChecked)
            R.id.switch_grayscale -> viewModel.setGrayscale(isChecked)
            R.id.switch_book      -> viewModel.setBookEffect(isChecked)
        }
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.button_done  -> showSaveConfirmation()
            R.id.button_reset -> viewModel.reset()
        }
    }

    fun showSaveConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.apply)
            .setMessage(R.string.color_correction_apply_text)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.this_manga)  { _, _ -> viewModel.save() }
            .setNeutralButton(R.string.globally)     { _, _ -> viewModel.saveGlobally() }
            .show()
    }

    private fun onColorFilterChanged(cf: ReaderColorFilter?) {
        // Update sliders and switches
        viewBinding.sliderBrightness.setValueRounded(cf?.brightness ?: 0f)
        viewBinding.sliderContrast.setValueRounded(cf?.contrast ?: 0f)
        viewBinding.sliderSharpening?.setValueRounded(cf?.sharpening ?: 0f)
        viewBinding.sliderVibrance?.setValueRounded(cf?.vibrance ?: 0f)
        viewBinding.switchInvert.setChecked(cf?.isInverted == true, false)
        viewBinding.switchGrayscale.setChecked(cf?.isGrayscale == true, false)
        viewBinding.switchBook.setChecked(cf?.isBookBackground == true, false)

        // Apply real-time ColorMatrix (brightness, contrast, vibrance, invert, etc.) directly
        // to imageViewAfter as a paint colorFilter — instant, zero Coil overhead
        viewBinding.imageViewAfter.colorFilter = cf?.toColorFilter()

        // Fire sharpening GPU preview only after before-image has loaded (avoids racing
        // with ShadowImageListener which copies imageViewBefore → imageViewAfter)
        if (beforeImageReady) {
            updateSharpeningPreview(cf)
        }
    }

    /**
     * Reloads [imageViewAfter] through Coil with [ImageFiltersTransformation] applied
     * so sharpening is previewed accurately. All other filters (brightness, contrast, vibrance
     * etc.) are shown instantly via [imageViewAfter]'s colorFilter paint — no Coil reload needed.
     *
     * The memory cache key includes the page URL so cached results are never shared across
     * different manga/chapters.
     */
    private fun updateSharpeningPreview(cf: ReaderColorFilter?) {
        val sharpening = cf?.sharpening ?: 0f
        // Include page URL in the key — without it, Coil returns a cached result from a
        // different manga that happened to have the same sharpening value (the original bug).
        val cacheKey = "cf_preview_${viewModel.preview.url.hashCode()}_s${sharpening}"

        val builder = ImageRequest.Builder(this)
            .data(viewModel.preview)
            .memoryCacheKey(cacheKey)
            .target(
                onStart = { placeholder ->
                    // Only show placeholder if we have nothing yet; don't wipe a good preview
                    if (viewBinding.imageViewAfter.drawable == null) {
                        viewBinding.imageViewAfter.setImageDrawable(
                            placeholder?.asDrawable(resources),
                        )
                    }
                },
                onSuccess = { result ->
                    viewBinding.imageViewAfter.setImageDrawable(result.asDrawable(resources))
                    // Re-apply ColorMatrix paint after drawable swap so it's never lost
                    viewBinding.imageViewAfter.colorFilter = viewModel.colorFilter.value?.toColorFilter()
                },
                onError = { error ->
                    viewBinding.imageViewAfter.setImageDrawable(
                        error?.asDrawable(resources),
                    )
                },
            )

        if (sharpening > 0.01f) {
            builder.transformations(ImageFiltersTransformation(applicationContext, sharpening))
        }

        gpuPreviewDisposable?.dispose()
        gpuPreviewDisposable = coil.enqueue(builder.build())
    }

    private fun loadPreview(page: MangaPage) = with(viewBinding.imageViewBefore) {
        addImageRequestListener(
            ImageRequestIndicatorListener(listOf(viewBinding.progressBefore, viewBinding.progressAfter)),
        )
        addImageRequestListener(BeforeImageListener())
        setImageAsync(page)
    }

    private fun onLoadingChanged(isLoading: Boolean) {
        viewBinding.sliderBrightness.isEnabled  = !isLoading
        viewBinding.sliderContrast.isEnabled    = !isLoading
        viewBinding.sliderSharpening?.isEnabled = !isLoading
        viewBinding.sliderVibrance?.isEnabled   = !isLoading
        viewBinding.switchInvert.isEnabled      = !isLoading
        viewBinding.switchGrayscale.isEnabled   = !isLoading
        viewBinding.buttonDone.isEnabled        = !isLoading
    }

    // ─── Label formatters ────────────────────────────────────────────────────

    private class PercentLabelFormatter(resources: Resources) : LabelFormatter {
        private val pattern = resources.getString(R.string.percent_string_pattern)
        override fun getFormattedValue(value: Float): String =
            pattern.format(((value + 1f) * 100).format(0))
    }

    private class SignedPercentLabelFormatter(resources: Resources) : LabelFormatter {
        private val pattern = resources.getString(R.string.percent_string_pattern)
        override fun getFormattedValue(value: Float): String {
            val pct = (value * 100).toInt()
            return pattern.format("${if (pct >= 0) "+" else ""}$pct")
        }
    }

    // ─── Before-image listener ───────────────────────────────────────────────

    /**
     * Listens for the before-image loading in [imageViewBefore].
     * On success: copies the result to [imageViewAfter] (shadow image), then fires the
     * sharpening preview and re-applies ColorMatrix so everything is in sync.
     * Replaces the old [ShadowImageListener] + [updateGpuPreview] split that caused races.
     */
    private inner class BeforeImageListener : ImageRequest.Listener {
        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            // Copy the unfiltered page to the "after" pane as the base image
            viewBinding.imageViewAfter.setImageDrawable(result.image.asDrawable(resources))
            beforeImageReady = true
            // Now apply all filters on top
            val cf = viewModel.colorFilter.value
            viewBinding.imageViewAfter.colorFilter = cf?.toColorFilter()
            updateSharpeningPreview(cf)
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            viewBinding.imageViewAfter.setImageDrawable(
                result.image?.asDrawable(resources),
            )
            beforeImageReady = true
        }
    }
}
