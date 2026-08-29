package org.koitharu.kotatsu.alternatives.ui

import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.MangaSource

data class SourceReplacementModel(
	val source: MangaSource?,
	val matched: Int,
	val total: Int,
	val matchPercent: Int,
	val isSelected: Boolean,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is SourceReplacementModel && other.source?.name == source?.name
	}
}
