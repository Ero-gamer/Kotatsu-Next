package org.koitharu.kotatsu.core.util.iterator

// next() delegates to `upstream`, which already throws NoSuchElementException when
// exhausted (per the Iterator contract); detekt can't see through the delegation statically.
@Suppress("IteratorNotThrowingNoSuchElementException")
class MappingIterator<T, R>(
	private val upstream: Iterator<T>,
	private val mapper: (T) -> R,
) : Iterator<R> {

	override fun hasNext(): Boolean = upstream.hasNext()

	override fun next(): R = mapper(upstream.next())
}
