package org.koitharu.kotatsu.reader.ui.info

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.image.ImageFormat
import org.koitharu.kotatsu.core.image.ImageInspector
import org.koitharu.kotatsu.core.image.RawImageDetails
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.databinding.SheetImageInfoBinding
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

    private var rawText: String? = null

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
        val report = if (page == null) {
            null
        } else {
            runCatching {
                val uri = pageLoader.loadPage(page, force = false)
                withContext(Dispatchers.IO) { ImageInspector.inspect(context, uri) }
            }.getOrNull()
        }
        if (viewBinding == null) return
        if (report == null) {
            binding.textStatus.setText(R.string.image_info_unavailable)
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
        if (info.format == ImageFormat.JPEG) {
            rows += context.getString(R.string.image_info_encoding) to
                context.getString(if (info.progressive) R.string.image_info_progressive else R.string.image_info_baseline)
        }
        if (info.orientation > 1) {
            rows += context.getString(R.string.image_info_orientation) to
                context.getString(R.string.image_info_exif_orientation, info.orientation)
        }
        bindRows(binding.containerInfo, rows)
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

    private fun bindRows(container: LinearLayout, rows: List<Pair<String, String>>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        for ((label, value) in rows) {
            val row = inflater.inflate(R.layout.item_image_info_row, container, false)
            row.findViewById<TextView>(R.id.text_label).text = label
            row.findViewById<TextView>(R.id.text_value).text = value
            container.addView(row)
        }
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
