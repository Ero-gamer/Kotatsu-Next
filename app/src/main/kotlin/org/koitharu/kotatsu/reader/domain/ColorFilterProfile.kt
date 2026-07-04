package org.koitharu.kotatsu.reader.domain

/**
 * A named, saved [ReaderColorFilter] snapshot.
 * [mangaId] == null means this is a GLOBAL profile (Reader Settings -> Global filter profiles);
 * otherwise it belongs to that one manga's own saved-profiles list.
 */
data class ColorFilterProfile(
    val id: Long,
    val mangaId: Long?,
    val name: String,
    val filter: ReaderColorFilter,
) {
    companion object {
        /** Per-series and global lists are each capped at this many saved profiles. */
        const val MAX_PROFILES_PER_SCOPE = 10
    }
}
