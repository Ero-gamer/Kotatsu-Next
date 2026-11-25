package org.koitharu.kotatsu.reader.domain

/** Per-manga border crop for each reading style; `null` follows the global setting. */
data class CropOverrides(
    val pages: Boolean? = null,
    val webtoon: Boolean? = null,
) {

    fun forMode(isWebtoon: Boolean): Boolean? = if (isWebtoon) webtoon else pages

    companion object {

        val NONE = CropOverrides()

        fun decode(value: Int): Boolean? = when (value) {
            1 -> true
            2 -> false
            else -> null
        }

        fun encode(value: Boolean?): Int = when (value) {
            true -> 1
            false -> 2
            null -> 0
        }
    }
}
