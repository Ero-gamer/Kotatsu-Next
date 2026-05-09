package org.koitharu.kotatsu.reader.ui.colorfilter

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil3.asDrawable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@AndroidEntryPoint
class ColorFilterConfigActivity :
    BaseActivity<ActivityColorFilterBinding>(),
    Slider.OnChangeListener,
    View.OnClickListener,
    CompoundButton.OnCheckedChangeListener {

    private val viewModel: ColorFilterConfigViewModel by viewModels()

    /**
     * The source bitmap extracted from imageViewBefore after it loads.
     * Owned by Coil's BitmapDrawable — never recycled here.
     * Coil3 does not use a bitmap pool by default, so this reference is safe to hold.
     */
    private var sourceBitmap: Bitmap? = null

    /**
     * In-flight sharpening coroutine. Cancelled when the user moves the slider
     * again before the previous GPU pass finishes.
     */
    private var sharpenJob: Job? = null

    /** True once the before-image has loaded and [sourceBitmap] is populated. */
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

        // Wait for isReady so the initial null emission doesn't flash sliders to zero.
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
        viewBinding.sliderBrightness.setValueRounded(cf?.brightness ?: 0f)
        viewBinding.sliderContrast.setValueRounded(cf?.contrast ?: 0f)
        viewBinding.sliderSharpening?.setValueRounded(cf?.sharpening ?: 0f)
        viewBinding.sliderVibrance?.setValueRounded(cf?.vibrance ?: 0f)
        viewBinding.switchInvert.setChecked(cf?.isInverted == true, false)
        viewBinding.switchGrayscale.setChecked(cf?.isGrayscale == true, false)
        viewBinding.switchBook.setChecked(cf?.isBookBackground == true, false)

        if (!beforeImageReady) return

        val sharpening = cf?.sharpening ?: 0f
        if (sharpening > 0.01f) {
            applyAfterFilter(cf)
        } else {
            sharpenJob?.cancel()
            showSourceWithColorMatrix(cf)
        }
    }

    /**
     * Shows [sourceBitmap] on imageViewAfter with [cf]'s ColorMatrix applied as
     * a paint colorFilter — zero GPU work, instant update.
     */
    private fun showSourceWithColorMatrix(cf: ReaderColorFilter?) {
        val bmp = sourceBitmap ?: return
        viewBinding.imageViewAfter.setImageBitmap(bmp)
        viewBinding.imageViewAfter.colorFilter = cf?.toColorFilter()
    }

    /**
     * Applies GPU sharpening to [sourceBitmap] on a background thread.
     * Shows the unfiltered image immediately (with ColorMatrix paint) while processing,
     * then swaps to the sharpened result when the job completes.
     *
     * Cancels any previous in-flight job before starting.
     * Uses [sourceBitmap] directly — no Coil request, no cache writes,
     * no gallery thumbnail pollution.
     */
    private fun applyAfterFilter(cf: ReaderColorFilter?) {
        val sharpening = cf?.sharpening ?: 0f
        val source = sourceBitmap ?: return

        // Show unfiltered + ColorMatrix immediately so the panel is never blank.
        viewBinding.imageViewAfter.setImageBitmap(source)
        viewBinding.imageViewAfter.colorFilter = cf?.toColorFilter()

        sharpenJob?.cancel()
        sharpenJob = lifecycleScope.launch(Dispatchers.Default) {
            var result: Bitmap? = null
            try {
                result = runCatching {
                    ImageFiltersTransformation(applicationContext, sharpening)
                        .transform(source, Size.ORIGINAL)
                }.getOrNull() ?: return@launch

                val sharpenedBitmap = result
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) {
                        viewBinding.imageViewAfter.setImageBitmap(sharpenedBitmap)
                        // Re-apply ColorMatrix paint after bitmap swap so it's never lost.
                        viewBinding.imageViewAfter.colorFilter =
                            viewModel.colorFilter.value?.toColorFilter()
                    }
                }
            } finally {
                // If the coroutine was cancelled after transform() completed but before
                // the Main-thread swap, `result` would otherwise leak. Recycle it here
                // unless it was successfully handed to the ImageView (which retains it).
                // We can safely recycle if the job was cancelled — the ImageView still
                // holds `source` (set above) so the panel shows the unsharpened preview.
                if (!isActive && result != null && result !== source) {
                    result.recycle()
                }
            }
        }
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
     * Listens for the before-image finishing load in [imageViewBefore].
     *
     * On success:
     *   1. Extracts the Bitmap for use in sharpening preview coroutines.
     *   2. Copies the image to imageViewAfter as the baseline (unfiltered state).
     *   3. Applies the current filter state (ColorMatrix + optional GPU sharpening).
     *
     * Does NOT fire any additional Coil requests — after-panel updates use the
     * already-loaded bitmap directly, so Coil's cache is never written with a
     * transformed result, and gallery thumbnail loading is completely unaffected.
     */
    private inner class BeforeImageListener : ImageRequest.Listener {

        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            sourceBitmap = (result.image.asDrawable(resources) as? BitmapDrawable)?.bitmap
            beforeImageReady = true
            viewBinding.imageViewAfter.setImageDrawable(result.image.asDrawable(resources))
            // Apply the current filter state now that the source is ready.
            onColorFilterChanged(viewModel.colorFilter.value)
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            viewBinding.imageViewAfter.setImageDrawable(result.image?.asDrawable(resources))
            beforeImageReady = true
        }
    }
}
