package org.koitharu.kotatsu.reader.ui.colorfilter

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
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
import kotlin.math.roundToInt

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

        val percentFormatter = PercentLabelFormatter(resources)
        val signedFormatter = SignedPercentLabelFormatter(resources)
        val unsignedFormatter = UnsignedPercentLabelFormatter(resources)

        viewBinding.sliderBrightness.addOnChangeListener(this)
        viewBinding.sliderContrast.addOnChangeListener(this)
        viewBinding.sliderRcas.addOnChangeListener(this)
        viewBinding.sliderAdaptiveSharpen.addOnChangeListener(this)
        viewBinding.sliderDeband.addOnChangeListener(this)
        viewBinding.sliderSaturation.addOnChangeListener(this)
        viewBinding.sliderVibrance.addOnChangeListener(this)
        viewBinding.sliderDenoise.addOnChangeListener(this)
        // Dither and Grain sliders removed — those CPU filters are eliminated

        viewBinding.sliderBrightness.setLabelFormatter(percentFormatter)
        viewBinding.sliderContrast.setLabelFormatter(percentFormatter)
        viewBinding.sliderRcas.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderAdaptiveSharpen.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderDeband.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderSaturation.setLabelFormatter(signedFormatter)
        // Vibrance is a pure boost magnitude (0..1), not a signed adjustment — unsigned
        // formatter matches the sharpen / resample intensities and Denoise.
        viewBinding.sliderVibrance.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderDenoise.setLabelFormatter(unsignedFormatter)

        viewBinding.switchInvert.setOnCheckedChangeListener(this)
        viewBinding.switchGrayscale.setOnCheckedChangeListener(this)
        viewBinding.switchLineDarken.setOnCheckedChangeListener(this)
        viewBinding.switchBook.setOnCheckedChangeListener(this)
        viewBinding.buttonDone.setOnClickListener(this)
        viewBinding.buttonReset.setOnClickListener(this)

        setupInfoButtons()
        setupSliderControls()

        onBackPressedDispatcher.addCallback(ColorFilterConfigBackPressedDispatcher(this, viewModel))

        viewModel.colorFilter.observe(this, this::onColorFilterChanged)
        viewModel.isLoading.observe(this, this::onLoadingChanged)
        viewModel.onDismiss.observeEvent(this) { finishAfterTransition() }

        loadPreview(viewModel.preview)
    }

    override fun onApplyWindowInsets(
        v: View,
        insets: WindowInsetsCompat,
    ): WindowInsetsCompat {
        val barsInsets = insets.systemBarsInsets
        viewBinding.root.setPadding(barsInsets.left, barsInsets.top, barsInsets.right, barsInsets.bottom)
        return insets.consumeAllSystemBarsInsets()
    }

    override fun onValueChange(
        slider: Slider,
        value: Float,
        fromUser: Boolean,
    ) {
        if (fromUser) dispatchValue(slider.id, value)
    }

    private fun dispatchValue(
        sliderId: Int,
        value: Float,
    ) {
        when (sliderId) {
            R.id.slider_brightness -> viewModel.setBrightness(value)
            R.id.slider_contrast -> viewModel.setContrast(value)
            R.id.slider_rcas -> viewModel.setRcas(value)
            R.id.slider_adaptive_sharpen -> viewModel.setAdaptiveSharpen(value)
            R.id.slider_deband -> viewModel.setDeband(value)
            R.id.slider_saturation -> viewModel.setSaturation(value)
            R.id.slider_vibrance -> viewModel.setVibrance(value)
            R.id.slider_denoise -> viewModel.setDenoise(value)
        }
    }

    // ── Info dialogs, ± steppers, double-tap value entry ─────────────────────

    /** How a slider's raw value maps to the percentage its label shows (see the label formatters). */
    private enum class Scale {
        /** Label = (value + 1) * 100, e.g. brightness/contrast: 0..200. */
        OFFSET,

        /** Label = value * 100, signed or not: saturation -100..100, others 0..100. */
        DIRECT,
    }

    private class SliderControl(
        val sliderId: Int,
        val minusId: Int,
        val plusId: Int,
        val nameRes: Int,
        val scale: Scale,
    )

    private val sliderControls by lazy(LazyThreadSafetyMode.NONE) {
        listOf(
            SliderControl(
                R.id.slider_brightness,
                R.id.button_brightness_minus,
                R.id.button_brightness_plus,
                R.string.brightness,
                Scale.OFFSET,
            ),
            SliderControl(
                R.id.slider_contrast,
                R.id.button_contrast_minus,
                R.id.button_contrast_plus,
                R.string.contrast,
                Scale.OFFSET,
            ),
            SliderControl(
                R.id.slider_saturation,
                R.id.button_saturation_minus,
                R.id.button_saturation_plus,
                R.string.saturation,
                Scale.DIRECT,
            ),
            SliderControl(
                R.id.slider_vibrance,
                R.id.button_vibrance_minus,
                R.id.button_vibrance_plus,
                R.string.vibrance,
                Scale.DIRECT,
            ),
            SliderControl(
                R.id.slider_rcas,
                R.id.button_rcas_minus,
                R.id.button_rcas_plus,
                R.string.sharpen_rcas,
                Scale.DIRECT,
            ),
            SliderControl(
                R.id.slider_adaptive_sharpen,
                R.id.button_adaptive_sharpen_minus,
                R.id.button_adaptive_sharpen_plus,
                R.string.sharpen_adaptive_sharpen,
                Scale.DIRECT,
            ),
            SliderControl(
                R.id.slider_deband,
                R.id.button_deband_minus,
                R.id.button_deband_plus,
                R.string.deband,
                Scale.DIRECT,
            ),
            SliderControl(
                R.id.slider_denoise,
                R.id.button_denoise_minus,
                R.id.button_denoise_plus,
                R.string.denoise,
                Scale.DIRECT,
            ),
        )
    }

    private fun setupInfoButtons() {
        val infos =
            listOf(
                R.id.info_invert to FilterId.INVERT,
                R.id.info_grayscale to FilterId.GRAYSCALE,
                R.id.info_book to FilterId.BOOK_BACKGROUND,
                R.id.info_brightness to FilterId.BRIGHTNESS,
                R.id.info_contrast to FilterId.CONTRAST,
                R.id.info_saturation to FilterId.SATURATION,
                R.id.info_vibrance to FilterId.VIBRANCE,
                R.id.info_rcas to FilterId.RCAS,
                R.id.info_adaptive_sharpen to FilterId.ADAPTIVE_SHARPEN,
                R.id.info_deband to FilterId.DEBAND,
                R.id.info_denoise to FilterId.DENOISE,
                R.id.info_line_darken to FilterId.LINE_DARKEN,
            )
        for ((viewId, filterId) in infos) {
            val button = viewBinding.root.findViewById<ImageButton>(viewId)
            button.contentDescription =
                getString(R.string.filter_info_button, getString(FilterInfoRegistry.byId(filterId).nameRes))
            button.setOnClickListener { FilterInfoDialog.show(this, filterId) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSliderControls() {
        for (control in sliderControls) {
            val slider = viewBinding.root.findViewById<Slider>(control.sliderId)
            val name = getString(control.nameRes)
            viewBinding.root.findViewById<ImageButton>(control.minusId).apply {
                contentDescription = getString(R.string.filter_value_decrease, name)
                setOnClickListener { stepSlider(slider, -1) }
            }
            viewBinding.root.findViewById<ImageButton>(control.plusId).apply {
                contentDescription = getString(R.string.filter_value_increase, name)
                setOnClickListener { stepSlider(slider, +1) }
            }
            // Observes touches only (always returns false) so the slider keeps its own drag/tap handling.
            val detector =
                GestureDetector(
                    this,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                            if (e.actionMasked == MotionEvent.ACTION_UP &&
                                slider.isEnabled
                            ) {
                                showValueDialog(control, slider)
                            }
                            return false
                        }
                    },
                )
            slider.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                false
            }
        }
    }

    /** Moves [slider] by one 10-percentage-point step, snapped to the 0.1 grid. */
    private fun stepSlider(
        slider: Slider,
        direction: Int,
    ) {
        val target =
            (((slider.value * STEP_DIVISOR).roundToInt() + direction) / STEP_DIVISOR)
                .coerceIn(slider.valueFrom, slider.valueTo)
        if (target != slider.value) {
            slider.value = target
            dispatchValue(slider.id, target)
        }
    }

    private fun showValueDialog(
        control: SliderControl,
        slider: Slider,
    ) {
        val minDisplay = toDisplay(control.scale, slider.valueFrom).roundToInt()
        val maxDisplay = toDisplay(control.scale, slider.valueTo).roundToInt()
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    (if (minDisplay < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
                hint = getString(R.string.filter_value_enter_hint, minDisplay, maxDisplay)
                setText(toDisplay(control.scale, slider.value).roundToInt().toString())
                setSelectAllOnFocus(true)
            }
        val padding = resources.getDimensionPixelSize(R.dimen.margin_normal)
        val container =
            FrameLayout(this).apply {
                setPadding(padding, padding / 2, padding, 0)
                addView(input)
            }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.filter_value_enter_title, getString(control.nameRes)))
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed =
                    input.text
                        ?.toString()
                        ?.trim()
                        ?.replace(',', '.')
                        ?.toFloatOrNull()
                if (typed != null && typed.isFinite()) {
                    val target = fromDisplay(control.scale, typed).coerceIn(slider.valueFrom, slider.valueTo)
                    if (target != slider.value) {
                        slider.value = target
                        dispatchValue(slider.id, target)
                    }
                }
            }.show()
    }

    private fun toDisplay(
        scale: Scale,
        value: Float,
    ) = if (scale == Scale.OFFSET) (value + 1f) * 100f else value * 100f

    private fun fromDisplay(
        scale: Scale,
        display: Float,
    ) = if (scale ==
        Scale.OFFSET
    ) {
        display / 100f - 1f
    } else {
        display / 100f
    }

    override fun onCheckedChanged(
        buttonView: CompoundButton,
        isChecked: Boolean,
    ) {
        when (buttonView.id) {
            R.id.switch_invert -> viewModel.setInversion(isChecked)
            R.id.switch_grayscale -> viewModel.setGrayscale(isChecked)
            R.id.switch_line_darken -> viewModel.setLineDarkenEnabled(isChecked)
            R.id.switch_book -> viewModel.setBookEffect(isChecked)
        }
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.button_done -> showSaveConfirmation()
            R.id.button_reset -> viewModel.reset()
        }
    }

    fun showSaveConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.apply)
            .setMessage(R.string.color_correction_apply_text)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.this_manga) { _, _ -> viewModel.save() }
            .setNeutralButton(R.string.globally) { _, _ -> viewModel.saveGlobally() }
            .show()
    }

    private fun onColorFilterChanged(cf: ReaderColorFilter?) {
        viewBinding.sliderBrightness.setValueRounded(cf?.brightness ?: 0f)
        viewBinding.sliderContrast.setValueRounded(cf?.contrast ?: 0f)
        viewBinding.sliderRcas.setValueRounded(cf?.rcas ?: 0f)
        viewBinding.sliderAdaptiveSharpen.setValueRounded(cf?.adaptiveSharpen ?: 0f)
        viewBinding.sliderDeband.setValueRounded(cf?.deband ?: 0f)
        viewBinding.sliderSaturation.setValueRounded(cf?.saturation ?: 0f)
        viewBinding.sliderVibrance.setValueRounded(cf?.vibrance ?: 0f)
        viewBinding.sliderDenoise.setValueRounded(cf?.denoise ?: 0f)
        viewBinding.switchInvert.setChecked(cf?.isInverted == true, false)
        viewBinding.switchGrayscale.setChecked(cf?.isGrayscale == true, false)
        viewBinding.switchLineDarken.setChecked(cf?.isLineDarkenEnabled == true, false)
        viewBinding.switchBook.setChecked(cf?.isBookBackground == true, false)

        if (!beforeImageReady) return

        // The CPU preview is a documented approximation: one generic USM kernel driven by the
        // strongest of the two sharpen filters. Denoise, line darkening and deband are GPU-only
        // and not modelled in the preview.
        val sharpening = maxOf(cf?.rcas ?: 0f, cf?.adaptiveSharpen ?: 0f)
        val vibrance = cf?.vibrance ?: 0f
        if (sharpening > 0.01f || vibrance > 0.01f) {
            applyAfterFilter(cf, sharpening)
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
    private fun applyAfterFilter(
        cf: ReaderColorFilter?,
        sharpening: Float,
    ) {
        val vibrance = cf?.vibrance ?: 0f
        val source = sourceBitmap ?: return

        viewBinding.imageViewAfter.setImageBitmap(source)
        viewBinding.imageViewAfter.colorFilter = cf?.toColorFilter()

        previewJob?.cancel()
        val requestId = ++filterRequestId
        previewJob =
            lifecycleScope.launch(Dispatchers.Default) {
                val result =
                    runCatching {
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
        viewBinding.sliderBrightness.isEnabled = !isLoading
        viewBinding.sliderContrast.isEnabled = !isLoading
        viewBinding.sliderRcas.isEnabled = !isLoading
        viewBinding.sliderAdaptiveSharpen.isEnabled = !isLoading
        viewBinding.sliderDeband.isEnabled = !isLoading
        viewBinding.sliderSaturation.isEnabled = !isLoading
        viewBinding.sliderVibrance.isEnabled = !isLoading
        viewBinding.sliderDenoise.isEnabled = !isLoading
        viewBinding.switchInvert.isEnabled = !isLoading
        viewBinding.switchGrayscale.isEnabled = !isLoading
        viewBinding.switchLineDarken.isEnabled = !isLoading
        viewBinding.switchBook.isEnabled = !isLoading
        for (control in sliderControls) {
            viewBinding.root.findViewById<View>(control.minusId).isEnabled = !isLoading
            viewBinding.root.findViewById<View>(control.plusId).isEnabled = !isLoading
        }
        viewBinding.buttonDone.isEnabled = !isLoading
    }

    private companion object {
        /** 10 percentage points per ± press: values live on a 0.1 grid. */
        const val STEP_DIVISOR = 10f
    }

    // ── Label formatters ──────────────────────────────────────────────────────

    private class PercentLabelFormatter(
        resources: Resources,
    ) : LabelFormatter {
        private val pattern = resources.getString(R.string.percent_string_pattern)

        override fun getFormattedValue(value: Float): String = pattern.format(((value + 1f) * 100).format(0))
    }

    private class SignedPercentLabelFormatter(
        resources: Resources,
    ) : LabelFormatter {
        private val pattern = resources.getString(R.string.percent_string_pattern)

        override fun getFormattedValue(value: Float): String {
            val pct = (value * 100).toInt()
            return pattern.format("${if (pct >= 0) "+" else ""}$pct")
        }
    }

    private class UnsignedPercentLabelFormatter(
        resources: Resources,
    ) : LabelFormatter {
        private val pattern = resources.getString(R.string.percent_string_pattern)

        override fun getFormattedValue(value: Float): String = pattern.format((value * 100).format(0))
    }

    // ── Before-image listener ─────────────────────────────────────────────────

    private inner class BeforeImageListener : ImageRequest.Listener {
        override fun onSuccess(
            request: ImageRequest,
            result: SuccessResult,
        ) {
            sourceBitmap = (result.image.asDrawable(resources) as? BitmapDrawable)?.bitmap
            beforeImageReady = true
            viewBinding.imageViewAfter.setImageDrawable(result.image.asDrawable(resources))
            onColorFilterChanged(viewModel.colorFilter.value)
        }

        override fun onError(
            request: ImageRequest,
            result: ErrorResult,
        ) {
            viewBinding.imageViewAfter.setImageDrawable(result.image?.asDrawable(resources))
            beforeImageReady = true
        }
    }
}
