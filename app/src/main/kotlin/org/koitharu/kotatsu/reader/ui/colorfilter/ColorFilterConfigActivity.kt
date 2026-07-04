package org.koitharu.kotatsu.reader.ui.colorfilter

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Toast
import android.view.ViewGroup
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
import org.koitharu.kotatsu.reader.domain.ColorFilterProfile
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
        val unsignedFormatter = UnsignedPercentLabelFormatter(resources)

        viewBinding.sliderBrightness.addOnChangeListener(this)
        viewBinding.sliderContrast.addOnChangeListener(this)
        viewBinding.sliderSharpening?.addOnChangeListener(this)
        viewBinding.sliderSaturation?.addOnChangeListener(this)
        viewBinding.sliderVibrance?.addOnChangeListener(this)

        viewBinding.sliderBrightness.setLabelFormatter(percentFormatter)
        viewBinding.sliderContrast.setLabelFormatter(percentFormatter)
        // Sharpening's range is 0..1 (off..max), not -1..1 like Brightness/Contrast, so it
        // needs a plain 0%..100% formatter instead of the +1 offset one (which made 0 = "100%").
        viewBinding.sliderSharpening?.setLabelFormatter(unsignedFormatter)
        viewBinding.sliderSaturation?.setLabelFormatter(signedFormatter)
        viewBinding.sliderVibrance?.setLabelFormatter(signedFormatter)

        viewBinding.switchInvert.setOnCheckedChangeListener(this)
        viewBinding.switchGrayscale.setOnCheckedChangeListener(this)
        viewBinding.switchBook.setOnCheckedChangeListener(this)
        viewBinding.buttonDone.setOnClickListener(this)
        viewBinding.buttonReset.setOnClickListener(this)

        // Lock switch + Profiles button aren't in the XML layouts (two variants to keep in
        // sync otherwise) — inserted as siblings right after switchBook instead.
        val switchParent = viewBinding.switchBook.parent as ViewGroup
        val insertAt = switchParent.indexOfChild(viewBinding.switchBook) + 1
        val lockSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = getString(R.string.lock_filter)
            id = View.generateViewId()
        }
        switchParent.addView(lockSwitch, insertAt)
        lockSwitch.setOnCheckedChangeListener { _, isChecked -> viewModel.setLocked(isChecked) }

        val profilesButton = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.profiles)
            id = View.generateViewId()
        }
        switchParent.addView(profilesButton, insertAt + 1)
        profilesButton.setOnClickListener { showProfilesDialog() }

        viewModel.isLocked.observe(this) { locked -> lockSwitch.setChecked(locked, false) }

        onBackPressedDispatcher.addCallback(ColorFilterConfigBackPressedDispatcher(this, viewModel))

        // Tap a slider's label to enter an exact percent, pick a 25/50/75% preset, or
        // nudge the current value by ±10%. All three use each slider's own displayed-%
        // scale (same numbers the slider's tooltip already shows), so typing "50" always
        // matches what the label would read at that position.
        setupPercentEntry(viewBinding.sliderBrightness, viewBinding.textViewBrightness, offset = 1f, viewModel::setBrightness)
        setupPercentEntry(viewBinding.sliderContrast, viewBinding.textViewContrast, offset = 1f, viewModel::setContrast)
        viewBinding.sliderSaturation?.let { s ->
            findViewById<View>(R.id.textView_saturation)?.let { setupPercentEntry(s, it, offset = 0f, viewModel::setSaturation) }
        }
        viewBinding.sliderVibrance?.let { s ->
            findViewById<View>(R.id.textView_vibrance)?.let { setupPercentEntry(s, it, offset = 0f, viewModel::setVibrance) }
        }
        viewBinding.sliderSharpening?.let { s ->
            findViewById<View>(R.id.textView_sharpening)?.let { setupPercentEntry(s, it, offset = 0f, viewModel::setSharpening) }
        }

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
            R.id.slider_saturation -> viewModel.setSaturation(value)
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

    /** This series' saved-profiles list: apply / rename / delete / push-to-global / save-new / import-from-global. */
    private fun showProfilesDialog() {
        val profiles = viewModel.profiles.value
        val actions = ColorFilterProfilesDialog.Actions(
            onApply = viewModel::applyProfile,
            onRename = viewModel::renameProfile,
            onDelete = viewModel::deleteProfile,
            copyAction = getString(R.string.copy_to_global) to { profile: ColorFilterProfile ->
                viewModel.copyProfileToGlobal(profile) { ok ->
                    if (!ok) Toast.makeText(this, R.string.profiles_limit_reached, Toast.LENGTH_SHORT).show()
                }
            },
            saveNewAction = getString(R.string.save_as_profile) to { name: String, onResult: (Boolean) -> Unit ->
                viewModel.saveCurrentAsProfile(name, onResult)
            },
            extraAction = getString(R.string.import_from_global) to { showImportFromGlobalDialog() },
        )
        ColorFilterProfilesDialog.show(this, getString(R.string.profiles), profiles, actions)
    }

    private fun showImportFromGlobalDialog() {
        lifecycleScope.launch {
            val globalProfiles = viewModel.loadGlobalProfiles()
            val actions = ColorFilterProfilesDialog.Actions(
                onApply = { profile ->
                    viewModel.importProfile(profile) { ok ->
                        if (!ok) Toast.makeText(this@ColorFilterConfigActivity, R.string.profiles_limit_reached, Toast.LENGTH_SHORT).show()
                    }
                },
                onRename = { _, _ -> },
                onDelete = { },
            )
            ColorFilterProfilesDialog.show(this@ColorFilterConfigActivity, getString(R.string.import_from_global), globalProfiles, actions)
        }
    }

    private fun onColorFilterChanged(cf: ReaderColorFilter?) {
        viewBinding.sliderBrightness.setValueRounded(cf?.brightness ?: 0f)
        viewBinding.sliderContrast.setValueRounded(cf?.contrast ?: 0f)
        viewBinding.sliderSharpening?.setValueRounded(cf?.sharpening ?: 0f)
        viewBinding.sliderSaturation?.setValueRounded(cf?.saturation ?: 0f)
        viewBinding.sliderVibrance?.setValueRounded(cf?.vibrance ?: 0f)
        viewBinding.switchInvert.setChecked(cf?.isInverted == true, false)
        viewBinding.switchGrayscale.setChecked(cf?.isGrayscale == true, false)
        viewBinding.switchBook.setChecked(cf?.isBookBackground == true, false)

        if (!beforeImageReady) return

        val sharpening = cf?.sharpening ?: 0f
        val vibrance = cf?.vibrance ?: 0f
        if (sharpening > 0.01f || vibrance != 0f) {
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

    /** Bumped on every applyAfterFilter() call; lets in-flight jobs detect they're stale. */
    private var filterRequestId = 0

    /**
     * Applies sharpening and/or vibrance to [sourceBitmap] on a background thread.
     * Shows the unfiltered image immediately (with ColorMatrix paint) while processing,
     * then swaps to the filtered result when the job completes.
     *
     * Uses a request-id check (not job cancellation) to decide whether to show the
     * result: process() is a tight synchronous pixel loop with no suspension points,
     * so cancel() can't interrupt it — it always runs to completion. Relying on
     * isActive/cancellation here would mean withContext(Main) throws
     * CancellationException right as a freshly-computed result is about to be shown,
     * silently discarding it. The request-id check sidesteps that race: only the
     * single latest request is ever allowed to update the UI, regardless of completion
     * order, with zero risk of a finished result getting thrown away.
     *
     * Uses [sourceBitmap] directly — no Coil request, no cache writes,
     * no gallery thumbnail pollution.
     */
    private fun applyAfterFilter(cf: ReaderColorFilter?) {
        val sharpening = cf?.sharpening ?: 0f
        val vibrance = cf?.vibrance ?: 0f
        val source = sourceBitmap ?: return

        // Show unfiltered + ColorMatrix immediately so the panel is never blank.
        viewBinding.imageViewAfter.setImageBitmap(source)
        viewBinding.imageViewAfter.colorFilter = cf?.toColorFilter()

        sharpenJob?.cancel() // best-effort early exit if still waiting on the semaphore
        val requestId = ++filterRequestId
        sharpenJob = lifecycleScope.launch(Dispatchers.Default) {
            val result = runCatching {
                ImageFiltersTransformation(sharpening, vibrance)
                    .transform(source, Size.ORIGINAL)
            }.getOrNull() ?: return@launch

            if (requestId != filterRequestId) {
                // Superseded by a newer slider change while we were processing.
                if (result !== source) result.recycle()
                return@launch
            }

            withContext(Dispatchers.Main) {
                if (!isDestroyed && requestId == filterRequestId) {
                    viewBinding.imageViewAfter.setImageBitmap(result)
                    // Re-apply ColorMatrix paint after bitmap swap so it's never lost.
                    viewBinding.imageViewAfter.colorFilter = viewModel.colorFilter.value?.toColorFilter()
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
        // allowHardware(false): sourceBitmap is read pixel-by-pixel by ImageFiltersTransformation
        // (vibrance/sharpening preview). A HARDWARE-config bitmap requires a GPU readback to copy
        // out, which is unreliable on this device's driver and produces corrupted/noisy output
        // instead of a clean failure — same root cause as the earlier reader crash fix.
        setImageAsync(page, allowHardware = false)
    }

    private fun onLoadingChanged(isLoading: Boolean) {
        viewBinding.sliderBrightness.isEnabled  = !isLoading
        viewBinding.sliderContrast.isEnabled    = !isLoading
        viewBinding.sliderSharpening?.isEnabled = !isLoading
        viewBinding.sliderSaturation?.isEnabled = !isLoading
        viewBinding.sliderVibrance?.isEnabled   = !isLoading
        viewBinding.switchInvert.isEnabled      = !isLoading
        viewBinding.switchGrayscale.isEnabled   = !isLoading
        viewBinding.buttonDone.isEnabled        = !isLoading
    }

    // ─── Quick value entry (tap label -> exact %, presets, ±10%) ────────────

    /**
     * Wires [label] so tapping it opens a dialog to set [slider]'s value directly, using
     * the same displayed-percent scale as the slider's own tooltip formatter:
     *   displayedPercent = (value + offset) * 100
     * [offset] is 1f for Brightness/Contrast (their 0%..200% scale) and 0f for every
     * other slider (their -100%..100% or 0%..100% scale). [apply] is the ViewModel setter
     * to call — the slider's own displayed value then updates itself via the existing
     * colorFilter -> onColorFilterChanged round-trip, so we never touch slider.value here.
     */
    private fun setupPercentEntry(
        slider: Slider,
        label: View,
        offset: Float,
        apply: (Float) -> Unit,
    ) {
        label.setOnClickListener {
            showPercentEntryDialog(
                currentPercent = (slider.value + offset) * 100f,
                minPercent = (slider.valueFrom + offset) * 100f,
                maxPercent = (slider.valueTo + offset) * 100f,
            ) { percent -> apply(percent / 100f - offset) }
        }
    }

    private fun showPercentEntryDialog(
        currentPercent: Float,
        minPercent: Float,
        maxPercent: Float,
        onValue: (Float) -> Unit,
    ) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val editText = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(currentPercent.toInt().toString())
            setSelection(text.length)
        }

        fun clamped(p: Float) = p.coerceIn(minOf(minPercent, maxPercent), maxOf(minPercent, maxPercent))

        fun presetButton(text: String, percent: Float) = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            setOnClickListener { editText.setText(clamped(percent).toInt().toString()) }
        }

        val presetsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(presetButton("25%", 25f))
            addView(presetButton("50%", 50f))
            addView(presetButton("75%", 75f))
        }

        fun stepButton(text: String, delta: Float) = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            setOnClickListener {
                val base = editText.text.toString().toFloatOrNull() ?: currentPercent
                editText.setText(clamped(base + delta).toInt().toString())
            }
        }

        val stepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stepButton("-10%", -10f))
            addView(stepButton("+10%", 10f))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(0))
            addView(editText)
            addView(presetsRow)
            addView(stepRow)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_value)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val percent = editText.text.toString().toFloatOrNull() ?: return@setPositiveButton
                onValue(clamped(percent))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }



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

    /** For sliders whose native range is 0..1 (off..max), e.g. Sharpening — plain 0%..100%. */
    private class UnsignedPercentLabelFormatter(resources: Resources) : LabelFormatter {
        private val pattern = resources.getString(R.string.percent_string_pattern)
        override fun getFormattedValue(value: Float): String =
            pattern.format((value * 100).format(0))
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
