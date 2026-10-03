package org.koitharu.kotatsu.filter.ui.model

import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.parsers.model.SortOrder

data class FilterHeaderModel(
    val chips: Collection<ChipsView.ChipModel>,
    val sortOrder: SortOrder?,
    val isFilterApplied: Boolean,
)
