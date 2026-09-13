package org.koitharu.kotatsu.list.ui.model

// EqualsWithHashCodeExist: `equals` here is an abstract contract declaration only,
// re-declared per-implementer; there's no single hashCode to pair it with at this level.
@Suppress("EqualsWithHashCodeExist")
interface ListModel {

	override fun equals(other: Any?): Boolean

	fun areItemsTheSame(other: ListModel): Boolean

	fun getChangePayload(previousState: ListModel): Any? = null
}
