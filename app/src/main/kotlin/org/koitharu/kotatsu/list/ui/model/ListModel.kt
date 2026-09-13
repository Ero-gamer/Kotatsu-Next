package org.koitharu.kotatsu.list.ui.model

interface ListModel {

	@Suppress("EqualsWithHashCodeExist") // abstract contract only; no implementation to pair with hashCode here
	override fun equals(other: Any?): Boolean

	fun areItemsTheSame(other: ListModel): Boolean

	fun getChangePayload(previousState: ListModel): Any? = null
}
