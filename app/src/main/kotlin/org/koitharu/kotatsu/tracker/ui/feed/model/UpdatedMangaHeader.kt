package org.koitharu.kotatsu.tracker.ui.feed.model

import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel

data class UpdatedMangaHeader(
    val list: List<MangaListModel>,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean = other is UpdatedMangaHeader

    override fun getChangePayload(previousState: ListModel): Any = ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
}
