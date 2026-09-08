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

    private var sourceBitmap: Bitmap? = null
    private var previewJob: Job? = null
    private var beforeImageReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityColorFilterBinding.inflate(layoutInflater))
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)

        val percentFormatter  = PercentLabelFormatter(resources)
        val signedFormatter   = SignedPercentLabelFormatter(resources)
        val unsignedFormatter = UnsignedPercentLabelFormatter(resources)

        viewBinding.sliderBrightness.addOnChangeListener(this)
        viewBinding.sliderContrast.addOnChangeListener(this)
        viewBinding.sliderSharpening.addOnChangeListener(this)
        viewBinding.sliderSaturation.addOnChangeListener(this)
        viewBinding.sliderVibrance.addOnChangeListener(this)
        viewBinding.sliderDenoise.addOnChangeListener(this)
        // Dither and Grain sliders removed — those CPU filters are eliminated

        viewBinding.sliderBrightness.setLabelFormatter(percentFormatter)
        viewBinding.sliderContrast.setLabelFormatter(percentFormatter)
        viewBinding.sliderSharpening.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderSaturation.setLabelFormatter(signedFormatter)
        viewBinding.sliderVibrance.setLabelFormatter(signedFormatter)
        viewBinding.sliderDenoise.setLabelFormatter(unsignedFormatter)

        viewBinding.switchInvert.setOnCheckedChangeListener(this)
        viewBinding.switchGrayscale.setOnCheckedChangeListener(this)
        viewBinding.switchBook.setOnCheckedChangeListener(this)
        viewBinding.buttonDone.setOnClickListener(this)
        viewBinding.buttonReset.setOnClickListener(this)

        onBackPressedDispatcher.addCallback(ColorFilterConfigBackPressedDispatcher(this, viewModel))

        viewModel.colorFilter.observe(this, this::onColorFilterChanged)
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
            R.id.slider_saturation -> viewModel.setSaturation(value)
            R.id.slider_vibrance   -> viewModel.setVibrance(value)
            R.id.slider_denoise    -> viewModel.setDenoise(value)
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
        viewBinding.sliderSharpening.setValueRounded(cf?.sharpening ?: 0f)
        viewBinding.sliderSaturation.setValueRounded(cf?.saturation ?: 0f)
        viewBinding.sliderVibrance.setValueRounded(cf?.vibrance ?: 0f)
        viewBinding.sliderDenoise.setValueRounded(cf?.denoise ?: 0f)
        viewBinding.switchInvert.setChecked(cf?.isInverted == true, false)
        viewBinding.switchGrayscale.setChecked(cf?.isGrayscale == true, false)
        viewBinding.switchBook.setChecked(cf?.isBookBackground == true, false)

        if (!beforeImageReady) return

        val sharpening = cf?.sharpening ?: 0f
        val vibrance   = cf?.vibrance   ?: 0f
        if (sharpening > 0.01f || vibrance != 0f) {
            applyAfterFilter(cf)
        } else {
            previewJob?.cancel()
            showSourceWithColorMatrix(cf)
        }
    }

    private fun showSourceWithColorMatrix(cf: ReaderColorFilter?) {
        val bmp = sourceBitmap ?: return
        viewBinding.imageViewAfter.setImageBitmap(bmp)
        viewBinding.imageViewAfter.colorFilter = cf?.toColorFilter()
    }

    private var filterRequestId = 0

    /**
     * Applies sharpening/vibrance to [sourceBitmap] on a background thread for the preview.
     * Uses [ImageFiltersTransformation] (CPU, allocation-light) for the preview bitmap only —
     * actual reader tiles go through the GPU shader.
     */
    private fun applyAfterFilter(cf: ReaderColorFilter?) {
        val sharpening = cf?.sharpening ?: 0f
        val vibrance   = cf?.vibrance   ?: 0f
        val source     = sourceBitmap ?: return

        viewBinding.imageViewAfter.setImageBitmap(source)
        viewBinding.imageViewAfter.colorFilter = cf?.toColorFilter()

        previewJob?.cancel()
        val requestId = ++filterRequestId
        previewJob = lifecycleScope.launch(Dispatchers.Default) {
            val result = runCatching {
                ImageFiltersTransformation(sharpening, vibrance)
                    .transform(source, Size.ORIGINAL)
            }.getOrNull() ?: return@launch

            if (requestId != filterRequestId) {
                if (result !== source) result.recycle()
                return@launch
            }

            withContext(Dispatchers.Main) {
                if (!isDestroyed && requestId == filterRequestId) {
                    viewBinding.imageViewAfter.setImageBitmap(result)
                    viewBinding.imageViewAfter.colorFilter =
                        viewModel.colorFilter.value?.toColorFilter()
                } else if (result !== source) {
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
        setImageAsync(page, allowHardware = false)
    }

    private fun onLoadingChanged(isLoading: Boolean) {
        viewBinding.sliderBrightness.isEnabled  = !isLoading
        viewBinding.sliderContrast.isEnabled    = !isLoading
        viewBinding.sliderSharpening.isEnabled = !isLoading
        viewBinding.sliderSaturation.isEnabled = !isLoading
        viewBinding.sliderVibrance.isEnabled   = !isLoading
        viewBinding.sliderDenoise.isEnabled    = !isLoading
        viewBinding.switchInvert.isEnabled      = !isLoading
        viewBinding.switchGrayscale.isEnabled   = !isLoading
        viewBinding.buttonDone.isEnabled        = !isLoading
    }

    // ── Label formatters ──────────────────────────────────────────────────────

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

    private class UnsignedPercentLabelFormatter(resources: Resources) : LabelFormatter {
        private val pattern = resources.getString(R.string.percent_string_pattern)
        override fun getFormattedValue(value: Float): String =
            pattern.format((value * 100).format(0))
    }

    // ── Before-image listener ─────────────────────────────────────────────────

    private inner class BeforeImageListener : ImageRequest.Listener {
        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            sourceBitmap = (result.image.asDrawable(resources) as? BitmapDrawable)?.bitmap
            beforeImageReady = true
            viewBinding.imageViewAfter.setImageDrawable(result.image.asDrawable(resources))
            onColorFilterChanged(viewModel.colorFilter.value)
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            viewBinding.imageViewAfter.setImageDrawable(result.image?.asDrawable(resources))
            beforeImageReady = true
        }
    }
}
