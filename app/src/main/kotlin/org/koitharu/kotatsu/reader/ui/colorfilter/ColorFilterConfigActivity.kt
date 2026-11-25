package org.koitharu.kotatsu.reader.ui.colorfilter

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.image.ImageFiltersTransformation
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.drawableEnd
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.setChecked
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.core.util.progress.ImageRequestIndicatorListener
import org.koitharu.kotatsu.databinding.ActivityColorFilterBinding
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.format
import org.koitharu.kotatsu.reader.domain.PageStats
import org.koitharu.kotatsu.reader.domain.PageStatsEngine
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import org.koitharu.kotatsu.reader.domain.applyOverlay
import kotlin.math.roundToInt

@AndroidEntryPoint
class ColorFilterConfigActivity :
    BaseActivity<ActivityColorFilterBinding>(),
    Slider.OnChangeListener,
    View.OnClickListener,
    CompoundButton.OnCheckedChangeListener {
    private val viewModel: ColorFilterConfigViewModel by viewModels()

    private var sourceBitmap: Bitmap? = null
    private var previewStats: PageStats? = null
    private var previewJob: Job? = null
    private var beforeImageReady = false

    private val percentFormatter by lazy(LazyThreadSafetyMode.NONE) { PercentLabelFormatter(resources) }
    private val signedFormatter by lazy(LazyThreadSafetyMode.NONE) { SignedPercentLabelFormatter(resources) }
    private val unsignedFormatter by lazy(LazyThreadSafetyMode.NONE) { UnsignedPercentLabelFormatter(resources) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityColorFilterBinding.inflate(layoutInflater))
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)

        viewBinding.sliderBrightness.addOnChangeListener(this)
        viewBinding.sliderContrast.addOnChangeListener(this)
        viewBinding.sliderRcas.addOnChangeListener(this)
        viewBinding.sliderAdaptiveSharpen.addOnChangeListener(this)
        viewBinding.sliderDeband.addOnChangeListener(this)
        viewBinding.sliderBinarize.addOnChangeListener(this)
        viewBinding.sliderSaturation.addOnChangeListener(this)
        viewBinding.sliderVibrance.addOnChangeListener(this)
        viewBinding.sliderDenoise.addOnChangeListener(this)
        viewBinding.sliderOverlayRed.addOnChangeListener(this)
        viewBinding.sliderOverlayGreen.addOnChangeListener(this)
        viewBinding.sliderOverlayBlue.addOnChangeListener(this)
        viewBinding.sliderOverlayAlpha.addOnChangeListener(this)
        // Dither and Grain sliders removed — those CPU filters are eliminated

        viewBinding.sliderBrightness.setLabelFormatter(percentFormatter)
        viewBinding.sliderContrast.setLabelFormatter(percentFormatter)
        viewBinding.sliderRcas.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderAdaptiveSharpen.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderDeband.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderBinarize.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderSaturation.setLabelFormatter(signedFormatter)
        // Vibrance is a pure boost magnitude (0..1), not a signed adjustment — unsigned
        // formatter matches the sharpen / resample intensities and Denoise.
        viewBinding.sliderVibrance.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderOverlayRed.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderOverlayGreen.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderOverlayBlue.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderOverlayAlpha.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderDenoise.setLabelFormatter(unsignedFormatter)

        viewBinding.switchInvert.setOnCheckedChangeListener(this)
        viewBinding.switchGrayscale.setOnCheckedChangeListener(this)
        viewBinding.switchLineDarken.setOnCheckedChangeListener(this)
        viewBinding.switchBook.setOnCheckedChangeListener(this)
        viewBinding.switchAutoContrast.setOnCheckedChangeListener(this)
        viewBinding.switchAutoBrightness.setOnCheckedChangeListener(this)
        viewBinding.switchAutoSaturation.setOnCheckedChangeListener(this)
        viewBinding.buttonDone.setOnClickListener(this)
        viewBinding.buttonReset.setOnClickListener(this)

        setupInfoButtons()
        setupSliderControls()
        setupSections(savedInstanceState?.getInt(KEY_OPEN_SECTION, 0) ?: 0)

        onBackPressedDispatcher.addCallback(ColorFilterConfigBackPressedDispatcher(this, viewModel))

        viewModel.colorFilter.observe(this, this::onColorFilterChanged)
        viewModel.isLoading.observe(this, this::onLoadingChanged)
        viewModel.onDismiss.observeEvent(this) { finishAfterTransition() }

        loadPreview(viewModel.preview)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_OPEN_SECTION, openSection)
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
            R.id.slider_binarize -> viewModel.setBinarize(value)
            R.id.slider_saturation -> viewModel.setSaturation(value)
            R.id.slider_vibrance -> viewModel.setVibrance(value)
            R.id.slider_denoise -> viewModel.setDenoise(value)
            R.id.slider_overlay_red -> viewModel.setOverlayRed(value)
            R.id.slider_overlay_green -> viewModel.setOverlayGreen(value)
            R.id.slider_overlay_blue -> viewModel.setOverlayBlue(value)
            R.id.slider_overlay_alpha -> viewModel.setOverlayAlpha(value)
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
        val titleId: Int,
    )

    private val sliderControls by lazy(LazyThreadSafetyMode.NONE) {
        listOf(
            SliderControl(
                R.id.slider_brightness,
                R.id.button_brightness_minus,
                R.id.button_brightness_plus,
                R.string.brightness,
                Scale.OFFSET,
                R.id.textView_brightness,
            ),
            SliderControl(
                R.id.slider_contrast,
                R.id.button_contrast_minus,
                R.id.button_contrast_plus,
                R.string.contrast,
                Scale.OFFSET,
                R.id.textView_contrast,
            ),
            SliderControl(
                R.id.slider_saturation,
                R.id.button_saturation_minus,
                R.id.button_saturation_plus,
                R.string.saturation,
                Scale.DIRECT,
                R.id.textView_saturation,
            ),
            SliderControl(
                R.id.slider_vibrance,
                R.id.button_vibrance_minus,
                R.id.button_vibrance_plus,
                R.string.vibrance,
                Scale.DIRECT,
                R.id.textView_vibrance,
            ),
            SliderControl(
                R.id.slider_rcas,
                R.id.button_rcas_minus,
                R.id.button_rcas_plus,
                R.string.sharpen_rcas,
                Scale.DIRECT,
                R.id.textView_rcas,
            ),
            SliderControl(
                R.id.slider_adaptive_sharpen,
                R.id.button_adaptive_sharpen_minus,
                R.id.button_adaptive_sharpen_plus,
                R.string.sharpen_adaptive_sharpen,
                Scale.DIRECT,
                R.id.textView_adaptive_sharpen,
            ),
            SliderControl(
                R.id.slider_overlay_red,
                R.id.button_overlay_red_minus,
                R.id.button_overlay_red_plus,
                R.string.overlay_red,
                Scale.DIRECT,
                R.id.textView_overlay_red,
            ),
            SliderControl(
                R.id.slider_overlay_green,
                R.id.button_overlay_green_minus,
                R.id.button_overlay_green_plus,
                R.string.overlay_green,
                Scale.DIRECT,
                R.id.textView_overlay_green,
            ),
            SliderControl(
                R.id.slider_overlay_blue,
                R.id.button_overlay_blue_minus,
                R.id.button_overlay_blue_plus,
                R.string.overlay_blue,
                Scale.DIRECT,
                R.id.textView_overlay_blue,
            ),
            SliderControl(
                R.id.slider_overlay_alpha,
                R.id.button_overlay_alpha_minus,
                R.id.button_overlay_alpha_plus,
                R.string.overlay_alpha,
                Scale.DIRECT,
                R.id.textView_overlay_alpha,
            ),
            SliderControl(
                R.id.slider_deband,
                R.id.button_deband_minus,
                R.id.button_deband_plus,
                R.string.deband,
                Scale.DIRECT,
                R.id.textView_deband,
            ),
            SliderControl(
                R.id.slider_binarize,
                R.id.button_binarize_minus,
                R.id.button_binarize_plus,
                R.string.binarize,
                Scale.DIRECT,
                R.id.textView_binarize,
            ),
            SliderControl(
                R.id.slider_denoise,
                R.id.button_denoise_minus,
                R.id.button_denoise_plus,
                R.string.denoise,
                Scale.DIRECT,
                R.id.textView_denoise,
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
                R.id.info_auto_contrast to FilterId.AUTO_CONTRAST,
                R.id.info_auto_brightness to FilterId.AUTO_BRIGHTNESS,
                R.id.info_auto_saturation to FilterId.AUTO_SATURATION,
                R.id.info_overlay_red to FilterId.OVERLAY,
                R.id.info_overlay_green to FilterId.OVERLAY,
                R.id.info_overlay_blue to FilterId.OVERLAY,
                R.id.info_overlay_alpha to FilterId.OVERLAY,
                R.id.info_saturation to FilterId.SATURATION,
                R.id.info_vibrance to FilterId.VIBRANCE,
                R.id.info_rcas to FilterId.RCAS,
                R.id.info_adaptive_sharpen to FilterId.ADAPTIVE_SHARPEN,
                R.id.info_deband to FilterId.DEBAND,
                R.id.info_binarize to FilterId.BINARIZE,
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
            // Double tap opens the value dialog; a long press (finger held still) resets to default.
            var resetOnRelease = false
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

                        override fun onLongPress(e: MotionEvent) {
                            if (slider.isEnabled) {
                                resetOnRelease = true
                                slider.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                resetSlider(slider)
                            }
                        }
                    },
                )
            slider.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                val action = event.actionMasked
                if (resetOnRelease && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                    // The slider is still tracking the finger and may have nudged the value while it
                    // was held; apply the reset once more after it has handled the release.
                    resetOnRelease = false
                    slider.post { resetSlider(slider) }
                }
                false
            }
        }
    }

    /** Puts [slider] back to its default (0 for every filter slider). */
    private fun resetSlider(slider: Slider) {
        val target = 0f.coerceIn(slider.valueFrom, slider.valueTo)
        if (target != slider.value) {
            slider.value = target
            dispatchValue(slider.id, target)
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
            R.id.switch_auto_contrast -> viewModel.setAutoContrast(isChecked)
            R.id.switch_auto_brightness -> viewModel.setAutoBrightness(isChecked)
            R.id.switch_auto_saturation -> viewModel.setAutoSaturation(isChecked)
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

    /** A collapsible group of controls: tapping its header opens it and closes the others. */
    private class Section(
        val headerId: Int,
        val titleRes: Int,
        val sliderIds: IntArray,
        val switchIds: IntArray,
        val memberIds: IntArray,
    )

    private var openSection = 0

    private val sections by lazy(LazyThreadSafetyMode.NONE) {
        fun row(title: Int, info: Int, minus: Int, slider: Int, plus: Int) = intArrayOf(title, info, minus, slider, plus)
        val brightness = row(R.id.textView_brightness, R.id.info_brightness, R.id.button_brightness_minus, R.id.slider_brightness, R.id.button_brightness_plus)
        val contrast = row(R.id.textView_contrast, R.id.info_contrast, R.id.button_contrast_minus, R.id.slider_contrast, R.id.button_contrast_plus)
        val saturation = row(R.id.textView_saturation, R.id.info_saturation, R.id.button_saturation_minus, R.id.slider_saturation, R.id.button_saturation_plus)
        val vibrance = row(R.id.textView_vibrance, R.id.info_vibrance, R.id.button_vibrance_minus, R.id.slider_vibrance, R.id.button_vibrance_plus)
        val rcas = row(R.id.textView_rcas, R.id.info_rcas, R.id.button_rcas_minus, R.id.slider_rcas, R.id.button_rcas_plus)
        val adaptive = row(
            R.id.textView_adaptive_sharpen,
            R.id.info_adaptive_sharpen,
            R.id.button_adaptive_sharpen_minus,
            R.id.slider_adaptive_sharpen,
            R.id.button_adaptive_sharpen_plus,
        )
        val deband = row(R.id.textView_deband, R.id.info_deband, R.id.button_deband_minus, R.id.slider_deband, R.id.button_deband_plus)
        val binarize = row(R.id.textView_binarize, R.id.info_binarize, R.id.button_binarize_minus, R.id.slider_binarize, R.id.button_binarize_plus)
        val denoise = row(R.id.textView_denoise, R.id.info_denoise, R.id.button_denoise_minus, R.id.slider_denoise, R.id.button_denoise_plus)
        val overlayRed = row(R.id.textView_overlay_red, R.id.info_overlay_red, R.id.button_overlay_red_minus, R.id.slider_overlay_red, R.id.button_overlay_red_plus)
        val overlayGreen = row(R.id.textView_overlay_green, R.id.info_overlay_green, R.id.button_overlay_green_minus, R.id.slider_overlay_green, R.id.button_overlay_green_plus)
        val overlayBlue = row(R.id.textView_overlay_blue, R.id.info_overlay_blue, R.id.button_overlay_blue_minus, R.id.slider_overlay_blue, R.id.button_overlay_blue_plus)
        val overlayAlpha = row(R.id.textView_overlay_alpha, R.id.info_overlay_alpha, R.id.button_overlay_alpha_minus, R.id.slider_overlay_alpha, R.id.button_overlay_alpha_plus)
        listOf(
            Section(
                headerId = R.id.header_color_tone,
                titleRes = R.string.filter_category_color_tone,
                sliderIds = intArrayOf(R.id.slider_brightness, R.id.slider_contrast, R.id.slider_saturation, R.id.slider_vibrance),
                switchIds = intArrayOf(R.id.switch_invert, R.id.switch_grayscale, R.id.switch_book, R.id.switch_auto_contrast, R.id.switch_auto_brightness, R.id.switch_auto_saturation),
                memberIds = intArrayOf(
                    R.id.subtitle_color_tone,
                    R.id.switch_invert, R.id.info_invert,
                    R.id.switch_grayscale, R.id.info_grayscale,
                    R.id.switch_book, R.id.info_book,
                    R.id.switch_auto_contrast, R.id.info_auto_contrast,
                    R.id.switch_auto_brightness, R.id.info_auto_brightness,
                    R.id.switch_auto_saturation, R.id.info_auto_saturation,
                ) + brightness + contrast + saturation + vibrance,
            ),
            Section(
                headerId = R.id.header_clarity,
                titleRes = R.string.filter_category_clarity,
                sliderIds = intArrayOf(R.id.slider_rcas, R.id.slider_adaptive_sharpen),
                switchIds = intArrayOf(),
                memberIds = intArrayOf(R.id.subtitle_clarity) + rcas + adaptive,
            ),
            Section(
                headerId = R.id.header_cleanup,
                titleRes = R.string.filter_category_cleanup,
                sliderIds = intArrayOf(R.id.slider_deband, R.id.slider_binarize, R.id.slider_denoise),
                switchIds = intArrayOf(R.id.switch_line_darken),
                memberIds = intArrayOf(R.id.subtitle_cleanup, R.id.switch_line_darken, R.id.info_line_darken) +
                    deband + binarize + denoise,
            ),
            Section(
                headerId = R.id.header_overlay,
                titleRes = R.string.filter_category_overlay,
                sliderIds = intArrayOf(R.id.slider_overlay_red, R.id.slider_overlay_green, R.id.slider_overlay_blue, R.id.slider_overlay_alpha),
                switchIds = intArrayOf(),
                memberIds = intArrayOf(R.id.subtitle_overlay) + overlayRed + overlayGreen + overlayBlue + overlayAlpha,
            ),
        )
    }

    // Views touched on every slider step are resolved once (findViewById walks the tree each time).
    private val resolvedViews = android.util.SparseArray<View>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> cached(id: Int): T {
        var view = resolvedViews.get(id)
        if (view == null) {
            view = viewBinding.root.findViewById(id)
            resolvedViews.put(id, view)
        }
        return view as T
    }

    // Last text / state written per control and header: unchanged ones are not rewritten.
    private val counterTexts by lazy(LazyThreadSafetyMode.NONE) { arrayOfNulls<String>(sliderControls.size) }
    private val headerTexts by lazy(LazyThreadSafetyMode.NONE) { arrayOfNulls<String>(sections.size) }
    private val headerOpen by lazy(LazyThreadSafetyMode.NONE) { arrayOfNulls<Boolean>(sections.size) }

    private fun setupSections(initiallyOpen: Int) {
        openSection = initiallyOpen.coerceIn(-1, sections.lastIndex)
        sections.forEachIndexed { index, section ->
            viewBinding.root.findViewById<TextView>(section.headerId).setOnClickListener {
                openSection = if (openSection == index) -1 else index
                applySections()
            }
        }
        applySections()
    }

    /** Shows only the open section's controls; each header shows how many of its filters are active. */
    private fun applySections() {
        sections.forEachIndexed { index, section ->
            val open = index == openSection
            for (id in section.memberIds) {
                cached<View>(id).isVisible = open
            }
            var active = 0
            for (id in section.sliderIds) {
                if (cached<Slider>(id).value != 0f) active++
            }
            for (id in section.switchIds) {
                if (cached<CompoundButton>(id).isChecked) active++
            }
            val header = cached<TextView>(section.headerId)
            val text = if (active > 0) {
                getString(R.string.filter_value_counter, getString(section.titleRes), getString(R.string.filter_section_active, active))
            } else {
                getString(section.titleRes)
            }
            if (headerTexts[index] != text) {
                headerTexts[index] = text
                header.text = text
            }
            if (headerOpen[index] != open) {
                headerOpen[index] = open
                header.drawableEnd = ContextCompat.getDrawable(this, if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
            }
        }
    }

    /** The manual slider of a filter is ignored while its auto switch is on, so it is shown disabled. */
    private fun applyAutoSliderState(
        auto: Boolean,
        loading: Boolean,
        slider: Slider,
        minus: View,
        plus: View,
        title: View,
    ) {
        val enabled = !auto && !loading
        slider.isEnabled = enabled
        minus.isEnabled = enabled
        plus.isEnabled = enabled
        title.alpha = if (auto) DISABLED_ALPHA else 1f
    }

    private fun applyAutoStates(loading: Boolean = viewModel.isLoading.value) {
        with(viewBinding) {
            applyAutoSliderState(
                switchAutoContrast.isChecked, loading,
                sliderContrast, buttonContrastMinus, buttonContrastPlus, textViewContrast,
            )
            applyAutoSliderState(
                switchAutoBrightness.isChecked, loading,
                sliderBrightness, buttonBrightnessMinus, buttonBrightnessPlus, textViewBrightness,
            )
            applyAutoSliderState(
                switchAutoSaturation.isChecked, loading,
                sliderSaturation, buttonSaturationMinus, buttonSaturationPlus, textViewSaturation,
            )
        }
    }

    /** Appends each slider's current value to its title, e.g. "Brightness · 120%", or "OFF" at its default. */
    private fun updateCounters() {
        sliderControls.forEachIndexed { index, control ->
            val slider = cached<Slider>(control.sliderId)
            val formatter = when {
                control.scale == Scale.OFFSET -> percentFormatter
                slider.valueFrom < 0f -> signedFormatter
                else -> unsignedFormatter
            }
            val shown = if (slider.value == 0f) getString(R.string.filter_value_off) else formatter.getFormattedValue(slider.value)
            val text = getString(R.string.filter_value_counter, getString(control.nameRes), shown)
            if (counterTexts[index] != text) {
                counterTexts[index] = text
                cached<TextView>(control.titleId).text = text
            }
        }
    }

    private fun onColorFilterChanged(cf: ReaderColorFilter?) {
        viewBinding.sliderBrightness.setValueRounded(cf?.brightness ?: 0f)
        viewBinding.sliderContrast.setValueRounded(cf?.contrast ?: 0f)
        viewBinding.sliderRcas.setValueRounded(cf?.rcas ?: 0f)
        viewBinding.sliderAdaptiveSharpen.setValueRounded(cf?.adaptiveSharpen ?: 0f)
        viewBinding.sliderDeband.setValueRounded(cf?.deband ?: 0f)
        viewBinding.sliderBinarize.setValueRounded(cf?.binarize ?: 0f)
        viewBinding.sliderSaturation.setValueRounded(cf?.saturation ?: 0f)
        viewBinding.sliderVibrance.setValueRounded(cf?.vibrance ?: 0f)
        viewBinding.sliderDenoise.setValueRounded(cf?.denoise ?: 0f)
        viewBinding.sliderOverlayRed.setValueRounded(cf?.overlayRed ?: 0f)
        viewBinding.sliderOverlayGreen.setValueRounded(cf?.overlayGreen ?: 0f)
        viewBinding.sliderOverlayBlue.setValueRounded(cf?.overlayBlue ?: 0f)
        viewBinding.sliderOverlayAlpha.setValueRounded(cf?.overlayAlpha ?: 0f)
        updateCounters()
        viewBinding.switchInvert.setChecked(cf?.isInverted == true, false)
        viewBinding.switchGrayscale.setChecked(cf?.isGrayscale == true, false)
        viewBinding.switchLineDarken.setChecked(cf?.isLineDarkenEnabled == true, false)
        viewBinding.switchBook.setChecked(cf?.isBookBackground == true, false)
        viewBinding.switchAutoContrast.setChecked(cf?.isAutoContrast == true, false)
        viewBinding.switchAutoBrightness.setChecked(cf?.isAutoBrightness == true, false)
        viewBinding.switchAutoSaturation.setChecked(cf?.isAutoSaturation == true, false)
        applyAutoStates()
        applySections()

        if (!beforeImageReady) return

        // The CPU preview is a documented approximation: one generic USM kernel driven by the
        // strongest of the two sharpen filters. Denoise, line darkening, deband and binarize are GPU-only
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

    /** Bitmap the "after" view shows now: set again only when it changes (a new drawable + invalidate each time). */
    private var shownAfterBitmap: Bitmap? = null

    private fun showAfterBitmap(bitmap: Bitmap) {
        if (shownAfterBitmap !== bitmap) {
            shownAfterBitmap = bitmap
            viewBinding.imageViewAfter.setImageBitmap(bitmap)
        }
    }

    private fun showSourceWithColorMatrix(cf: ReaderColorFilter?) {
        val bmp = sourceBitmap ?: return
        showAfterBitmap(bmp)
        viewBinding.imageViewAfter.colorFilter = cf?.toColorFilter(previewStats)
        viewBinding.imageViewAfter.applyOverlay(cf?.overlayColor ?: 0)
    }

    private var filterRequestId = 0
    private val previewLock = Mutex()

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

        // The previous preview stays up while the new one is computed (the source is only the first frame),
        // so dragging a slider does not flash the unfiltered image on every step.
        if (shownAfterBitmap == null) showAfterBitmap(source)
        viewBinding.imageViewAfter.colorFilter = cf?.toColorFilter(previewStats)
        viewBinding.imageViewAfter.applyOverlay(cf?.overlayColor ?: 0)

        previewJob?.cancel()
        val requestId = ++filterRequestId
        previewJob =
            lifecycleScope.launch(Dispatchers.Default) {
                // Debounce: a cancel during the delay is free, a running transform cannot be interrupted.
                delay(PREVIEW_DEBOUNCE_MS)
                // One full-bitmap transform at a time; superseded requests drop out before starting.
                val result = previewLock.withLock {
                    if (requestId != filterRequestId) return@withLock null
                    runCatching {
                        ImageFiltersTransformation(sharpening, vibrance)
                            .transform(source, Size.ORIGINAL)
                    }.getOrNull()
                } ?: return@launch

                if (requestId != filterRequestId) {
                    if (result !== source) result.recycle()
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (!isDestroyed && requestId == filterRequestId) {
                        showAfterBitmap(result)
                        viewBinding.imageViewAfter.colorFilter =
                            viewModel.colorFilter.value?.toColorFilter(previewStats)
                        viewBinding.imageViewAfter.applyOverlay(viewModel.colorFilter.value?.overlayColor ?: 0)
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
        viewBinding.sliderBinarize.isEnabled = !isLoading
        viewBinding.sliderSaturation.isEnabled = !isLoading
        viewBinding.sliderVibrance.isEnabled = !isLoading
        viewBinding.sliderDenoise.isEnabled = !isLoading
        viewBinding.sliderOverlayRed.isEnabled = !isLoading
        viewBinding.sliderOverlayGreen.isEnabled = !isLoading
        viewBinding.sliderOverlayBlue.isEnabled = !isLoading
        viewBinding.sliderOverlayAlpha.isEnabled = !isLoading
        viewBinding.switchInvert.isEnabled = !isLoading
        viewBinding.switchGrayscale.isEnabled = !isLoading
        viewBinding.switchLineDarken.isEnabled = !isLoading
        viewBinding.switchBook.isEnabled = !isLoading
        viewBinding.switchAutoContrast.isEnabled = !isLoading
        viewBinding.switchAutoBrightness.isEnabled = !isLoading
        viewBinding.switchAutoSaturation.isEnabled = !isLoading
        for (control in sliderControls) {
            cached<View>(control.minusId).isEnabled = !isLoading
            cached<View>(control.plusId).isEnabled = !isLoading
        }
        applyAutoStates(isLoading)
        viewBinding.buttonDone.isEnabled = !isLoading
    }

    private companion object {
        /** 10 percentage points per ± press: values live on a 0.1 grid. */
        const val STEP_DIVISOR = 10f
        const val PREVIEW_DEBOUNCE_MS = 100L
        const val KEY_OPEN_SECTION = "open_section"
        const val DISABLED_ALPHA = 0.38f
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
            val bitmap = (result.image.asDrawable(resources) as? BitmapDrawable)?.bitmap
            sourceBitmap = bitmap
            viewBinding.imageViewAfter.setImageDrawable(result.image.asDrawable(resources))
            // The page statistics scan the whole bitmap: off the main thread, the preview starts after them.
            lifecycleScope.launch {
                previewStats = bitmap?.let { withContext(Dispatchers.Default) { PageStatsEngine.fromAnyBitmap(it) } }
                beforeImageReady = true
                onColorFilterChanged(viewModel.colorFilter.value)
            }
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
