package org.koitharu.kotatsu.settings.sources.catalog

import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.ContentType

data class SourceCatalogPage(
    val type: ContentType,
    val items: List<SourceCatalogItem>,
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean = other is SourceCatalogPage && other.type == type

    override fun getChangePayload(previousState: ListModel): Any = ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
}
