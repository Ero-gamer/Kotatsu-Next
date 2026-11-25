package org.koitharu.kotatsu.reader.ui.info

import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.image.CpuFeatures
import org.koitharu.kotatsu.core.image.ImageFormat
import org.koitharu.kotatsu.core.image.ImageInspector
import org.koitharu.kotatsu.core.image.LossyQuality
import org.koitharu.kotatsu.core.image.PixelQuality
import org.koitharu.kotatsu.core.image.RawImageDetails
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.databinding.SheetImageInfoBinding
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import java.util.Locale
import javax.inject.Inject

/**
 * Header-level details of the page currently being read (any reading mode).
 * "Info" shows the standard facts; "Raw" shows the hex header, container structure and EXIF.
 * Nothing is decoded: the file / CBZ entry is only inspected.
 */
@AndroidEntryPoint
class ImageInfoSheet :
    BaseAdaptiveSheet<SheetImageInfoBinding>(),
    TabLayout.OnTabSelectedListener {

    private val viewModel by activityViewModels<ReaderViewModel>()

    @Inject
    lateinit var pageLoader: PageLoader

    private companion object {
        const val PIXEL_MAX_BYTES = 24 * 1024 * 1024
    }

    private var rawText: String? = null
    private var baseRows: List<Pair<String, String>> = emptyList()
    private val hints = HashMap<String, String>(2)
    private var headerQuality: LossyQuality.Result? = null
    private var imageFormat = ImageFormat.UNKNOWN
    private var pageUri: Uri? = null
    private var pixelRows: List<Pair<String, String>> = emptyList()
    private var pixelRunning = false

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): SheetImageInfoBinding = SheetImageInfoBinding.inflate(inflater, container, false)

    override fun onViewBindingCreated(binding: SheetImageInfoBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.image_info_tab_info), true)
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.image_info_tab_raw), false)
        binding.tabs.addOnTabSelectedListener(this)
        viewLifecycleScope.launch { load(binding) }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val typeMask = WindowInsetsCompat.Type.systemBars()
        viewBinding?.scrollView?.updatePadding(
            bottom = resources.getDimensionPixelOffset(R.dimen.margin_normal) + insets.getInsets(typeMask).bottom,
        )
        return insets.consume(v, typeMask, bottom = true)
    }

    override fun onDestroyView() {
        viewBinding?.tabs?.removeOnTabSelectedListener(this)
        super.onDestroyView()
    }

    override fun onTabSelected(tab: TabLayout.Tab) = updateTab()

    override fun onTabUnselected(tab: TabLayout.Tab) = Unit

    override fun onTabReselected(tab: TabLayout.Tab) = Unit

    private fun updateTab() {
        val binding = viewBinding ?: return
        val raw = binding.tabs.selectedTabPosition == 1
        val ready = binding.textStatus.isVisible.not()
        binding.containerInfo.isVisible = !raw && ready
        binding.textRaw.isVisible = raw && ready
    }

    private suspend fun load(binding: SheetImageInfoBinding) {
        val context = binding.root.context
        val page = viewModel.getCurrentPage()
        var error: Throwable? = null
        val report = if (page == null) {
            null
        } else {
            runCatchingCancellable {
                val uri = pageLoader.loadPage(page, force = false)
                pageUri = uri
                withContext(Dispatchers.IO) { ImageInspector.inspect(context, uri) }
            }.onFailure {
                it.printStackTraceDebug()
                error = it
            }.getOrNull()
        }
        if (viewBinding == null) return
        if (report == null) {
            val reason = error?.let { "\n${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
            binding.textStatus.text = context.getString(R.string.image_info_unavailable) + reason
            return
        }
        val info = report.info
        val rows = ArrayList<Pair<String, String>>(16)
        val resolution = if (info.width > 0 && info.height > 0) {
            val mp = info.width.toLong() * info.height / 1_000_000.0
            String.format(Locale.getDefault(), "%d × %d  (%.2f MP)", info.width, info.height, mp)
        } else {
            context.getString(R.string.unknown)
        }
        rows += context.getString(R.string.image_info_resolution) to resolution
        rows += context.getString(R.string.image_info_file_name) to report.fileName.ifEmpty { "—" }
        rows += context.getString(R.string.image_info_extension) to report.extension.ifEmpty { "—" }
        val realFormat = if (info.isKnown) info.format.label() else context.getString(R.string.unknown)
        rows += context.getString(R.string.image_info_format) to realFormat
        if (report.isExtensionMismatch) {
            rows += context.getString(R.string.image_info_warning) to
                context.getString(R.string.image_info_ext_mismatch, info.format.label())
        }
        info.format.mimeType?.let { rows += context.getString(R.string.image_info_mime) to it }
        rows += context.getString(R.string.image_info_file_size) to
            "${Formatter.formatShortFileSize(context, info.fileSize)}  (${info.fileSize} B)"
        rows += context.getString(R.string.image_info_container) to report.container
        rows += context.getString(R.string.image_info_animation) to animationText(binding, report)
        if (info.bitDepth > 0) {
            rows += context.getString(R.string.image_info_bit_depth) to
                context.getString(R.string.image_info_bits_per_channel, info.bitDepth)
        }
        colorText(report)?.let { rows += context.getString(R.string.image_info_color) to it }
        RawImageDetails.hasIccProfile(report.head, report.headLength, info.format)?.let { icc ->
            rows += context.getString(R.string.image_info_icc) to
                context.getString(if (icc) R.string.image_info_icc_yes else R.string.image_info_icc_no)
        }
        if (info.format == ImageFormat.JPEG) {
            rows += context.getString(R.string.image_info_encoding) to
                context.getString(if (info.progressive) R.string.image_info_progressive else R.string.image_info_baseline)
        }
        val qualityLabel = context.getString(R.string.image_info_quality)
        hints.clear()
        imageFormat = info.format
        headerQuality = LossyQuality.analyze(report.head, report.headLength, info.format)
        headerQuality?.let { q ->
            val text = when (q.kind) {
                LossyQuality.Kind.LOSSLESS -> context.getString(R.string.image_info_quality_lossless)

                LossyQuality.Kind.EXACT -> context.getString(R.string.image_info_quality_value, q.quality)

                LossyQuality.Kind.ESTIMATE -> {
                    hints[qualityLabel] = context.getString(R.string.image_info_quality_estimate_hint, q.detail ?: "-")
                    context.getString(R.string.image_info_quality_estimate, q.quality)
                }
            }
            rows += qualityLabel to text
        }
        if (info.orientation > 1) {
            rows += context.getString(R.string.image_info_orientation) to
                context.getString(R.string.image_info_exif_orientation, info.orientation)
        }
        rows += context.getString(R.string.image_info_cpu) to CpuFeatures.describe()
        baseRows = rows
        showRows(binding.containerInfo)
        rawText = withContext(Dispatchers.Default) {
            RawImageDetails.build(report.head, report.headLength, info.format)
        }
        binding.textRaw.text = rawText
        binding.textStatus.isVisible = false
        updateTab()
    }

    private fun animationText(binding: SheetImageInfoBinding, report: ImageInspector.Report): String {
        val ctx = binding.root.context
        val info = report.info
        if (!info.animated) return ctx.getString(R.string.image_info_static)
        val parts = ArrayList<String>(4)
        parts += ctx.getString(R.string.image_info_animated)
        if (info.frameCount > 0) parts += ctx.getString(R.string.image_info_frames, info.frameCount)
        if (info.durationMs > 0) {
            parts += String.format(Locale.getDefault(), "%.2f s", info.durationMs / 1000.0)
            if (info.frameCount > 0) {
                parts += String.format(Locale.getDefault(), "%.1f fps", info.frameCount * 1000.0 / info.durationMs)
            }
        }
        parts += ctx.getString(
            R.string.image_info_loops,
            when {
                info.loopCount < 0 -> ctx.getString(R.string.unknown)
                info.loopCount == 0 -> ctx.getString(R.string.image_info_loop_forever)
                else -> info.loopCount.toString()
            },
        )
        return parts.joinToString(" · ")
    }

    private fun colorText(report: ImageInspector.Report): String? {
        val info = report.info
        val ctx = viewBinding?.root?.context ?: return null
        val sb = StringBuilder()
        if (info.components > 0) sb.append(ctx.getString(R.string.image_info_channels, info.components))
        info.hasAlpha?.let {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(ctx.getString(if (it) R.string.image_info_alpha_yes else R.string.image_info_alpha_no))
        }
        return sb.takeIf { it.isNotEmpty() }?.toString()
    }

    private fun bindRows(
        container: LinearLayout,
        rows: List<Pair<String, String>>,
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        for ((label, value) in rows) {
            val row = inflater.inflate(R.layout.item_image_info_row, container, false)
            row.findViewById<TextView>(R.id.text_label).text = label
            row.findViewById<TextView>(R.id.text_value).text = value
            val hintText = hints[label]
            if (hintText != null) {
                row.findViewById<ImageButton>(R.id.button_info).apply {
                    isVisible = true
                    setOnClickListener {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(label)
                            .setMessage(hintText)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
            container.addView(row)
        }
    }

    private fun showRows(container: LinearLayout) {
        bindRows(container, baseRows + pixelRows)
        if (pixelRows.isNotEmpty() || !imageFormat.canAnalyzePixels()) return
        val button = MaterialButton(
            container.context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        )
        button.setText(if (pixelRunning) R.string.image_info_pixels_running else R.string.image_info_pixels_run)
        button.isEnabled = !pixelRunning
        button.setOnClickListener { analyzePixels() }
        container.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun ImageFormat.canAnalyzePixels() = this == ImageFormat.JPEG || this == ImageFormat.PNG ||
        this == ImageFormat.WEBP || this == ImageFormat.BMP

    private fun analyzePixels() {
        val binding = viewBinding ?: return
        val uri = pageUri ?: return
        if (pixelRunning) return
        pixelRunning = true
        showRows(binding.containerInfo)
        viewLifecycleScope.launch {
            val context = binding.root.context
            val result = withContext(Dispatchers.Default) {
                runCatchingCancellable {
                    ImageInspector.readAll(context, uri, PIXEL_MAX_BYTES)?.let { PixelQuality.analyze(it) }
                }.onFailure { it.printStackTraceDebug() }.getOrNull()
            }
            pixelRunning = false
            if (viewBinding == null) return@launch
            val label = context.getString(R.string.image_info_pixels)
            hints[label] = context.getString(R.string.image_info_pixels_hint)
            val rows = ArrayList<Pair<String, String>>(2)
            rows += label to pixelText(context, result)
            if (result?.status == PixelQuality.Status.FOUND) {
                combinedText(context, result)?.let {
                    rows += context.getString(R.string.image_info_quality_combined) to it
                }
            }
            pixelRows = rows
            showRows(binding.containerInfo)
        }
    }

    private fun pixelText(context: android.content.Context, result: PixelQuality.Result?): String {
        if (result == null) return context.getString(R.string.image_info_pixels_unavailable)
        return when (result.status) {
            PixelQuality.Status.INCONCLUSIVE -> context.getString(R.string.image_info_pixels_inconclusive)

            PixelQuality.Status.NOT_FOUND -> context.getString(R.string.image_info_pixels_none)

            PixelQuality.Status.FOUND -> {
                val value = context.getString(R.string.image_info_quality_estimate, result.quality)
                val header = headerQuality
                val note = when {
                    header == null -> R.string.image_info_pixels_note_earlier

                    header.kind == LossyQuality.Kind.LOSSLESS -> R.string.image_info_pixels_note_lossless

                    imageFormat != ImageFormat.JPEG -> R.string.image_info_pixels_note_earlier

                    header.kind == LossyQuality.Kind.EXACT && kotlin.math.abs(header.quality - result.quality) > 5 ->
                        R.string.image_info_pixels_note_reencoded

                    else -> null
                }
                if (note == null) value else value + "\n" + context.getString(note)
            }
        }
    }

    /** Average of header and pixel estimates (pixels count double when the dip is very clear); JPEG estimates only. */
    private fun combinedText(context: android.content.Context, result: PixelQuality.Result): String? {
        val header = headerQuality ?: return null
        if (imageFormat != ImageFormat.JPEG || header.kind != LossyQuality.Kind.ESTIMATE) return null
        val weight = if (result.score >= 0.5f) 2 else 1
        val q = (header.quality + weight * result.quality + (1 + weight) / 2) / (1 + weight)
        return context.getString(R.string.image_info_quality_estimate, q)
    }

    private fun ImageFormat.label(): String = when (this) {
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.PNG -> "PNG"
        ImageFormat.WEBP -> "WebP"
        ImageFormat.GIF -> "GIF"
        ImageFormat.AVIF -> "AVIF"
        ImageFormat.JXL -> "JPEG XL"
        ImageFormat.BMP -> "BMP"
        ImageFormat.UNKNOWN -> "?"
    }
}
