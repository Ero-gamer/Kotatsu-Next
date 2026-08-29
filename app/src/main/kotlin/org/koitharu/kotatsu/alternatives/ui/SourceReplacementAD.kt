package org.koitharu.kotatsu.alternatives.ui

import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.databinding.ItemSourceReplacementBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.MangaSource

fun sourceReplacementAD(
	onClick: (MangaSource?) -> Unit,
) = adapterDelegateViewBinding<SourceReplacementModel, ListModel, ItemSourceReplacementBinding>(
	{ inflater, parent -> ItemSourceReplacementBinding.inflate(inflater, parent, false) },
) {
	binding.root.setOnClickListener { onClick(item.source) }

	bind {
		val source = item.source
		binding.textViewTitle.text = source?.getTitle(context)
			?: context.getString(R.string.handle_remaining_manually)
		binding.textViewSubtitle.text = if (source == null) {
			context.getString(R.string.handle_remaining_manually_summary)
		} else {
			context.getString(
				R.string.source_match_rate_summary,
				item.matched,
				item.total,
			)
		}
		binding.textViewPercent.isVisible = source != null
		binding.textViewPercent.text = context.getString(R.string.percent_format, item.matchPercent)
		binding.progressMatch.isVisible = source != null
		binding.progressMatch.setProgressCompat(item.matchPercent, false)
		binding.imageViewSelected.isVisible = item.isSelected
		if (source == null) {
			binding.imageViewIcon.setImageResource(R.drawable.ic_web)
		} else {
			binding.imageViewIcon.setImageAsync(source)
		}
	}
}
