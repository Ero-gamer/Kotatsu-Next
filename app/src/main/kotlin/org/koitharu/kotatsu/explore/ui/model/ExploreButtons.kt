package org.koitharu.kotatsu.explore.ui.model

import org.koitharu.kotatsu.list.ui.model.ListModel

data class ExploreButtons(
    val isRandomLoading: Boolean,
    val activePresetName: String? = null,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean = other is ExploreButtons
}
