package org.koitharu.kotatsu.details.ui.pager.pages

import androidx.core.view.isVisible
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.image.PageFormatRegistry
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.setTextColorAttr
import org.koitharu.kotatsu.databinding.ItemPageThumbBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import com.google.android.material.R as materialR

fun pageThumbnailAD(
    clickListener: OnListItemClickListener<PageThumbnail>,
    badgeConfig: () -> PageBadgeConfig,
) = adapterDelegateViewBinding<PageThumbnail, ListModel, ItemPageThumbBinding>(
    { inflater, parent -> ItemPageThumbBinding.inflate(inflater, parent, false) },
) {
    val gridWidth = itemView.context.resources.getDimensionPixelSize(R.dimen.preferred_grid_width)
    binding.imageViewThumb.exactImageSize = Size(
        width = gridWidth,
        height = (gridWidth / 13f * 18f).toInt(),
    )

    AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)

    fun updateBadges() {
        val config = badgeConfig()
        val badge = PageFormatRegistry.lookup(item.page.id)
        val showFormat = badge != null && config.showFormat && !(config.hideCommon && badge.format.isCommon)
        binding.textViewFormat.isVisible = showFormat
        if (showFormat) binding.textViewFormat.text = badge?.format?.badgeLabel
        binding.textViewAnimated.isVisible = config.showAnimated && badge?.animated == true
    }

    // The format is only known once the page file was fetched, so refresh the badges when loading ends.
    binding.imageViewThumb.addImageRequestListener(
        object : ImageRequest.Listener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) = updateBadges()
        },
    )

    bind {
        binding.imageViewThumb.setImageAsync(item.page)
        updateBadges()
        with(binding.textViewNumber) {
            setBackgroundResource(if (item.isCurrent) R.drawable.bg_badge_accent else R.drawable.bg_badge_empty)
            setTextColorAttr(if (item.isCurrent) materialR.attr.colorOnTertiary else android.R.attr.textColorPrimary)
            text = item.number.toString()
        }
    }
}
