package org.koitharu.kotatsu.reader.domain

/** A named saved snapshot of [ReaderColorFilter]. mangaId == null means global profile. */
data class ColorFilterProfile(
    val id: Long,
    val mangaId: Long?,
    val name: String,
    val filter: ReaderColorFilter,
) {
    companion object {
        const val MAX_PROFILES_PER_SCOPE = 10
    }
}
