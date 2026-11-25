package org.koitharu.kotatsu.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.db.entity.MangaWithTags

/** The manga travels with its settings: the row has a foreign key to it. */
@Serializable
class MangaPrefsBackup(
    @SerialName("manga") val manga: MangaBackup,
    @SerialName("prefs") val prefs: MangaPrefsEntity,
) {

    constructor(manga: MangaWithTags, prefs: MangaPrefsEntity) : this(MangaBackup(manga), prefs)

    /** A custom cover is a file of this device: it is not carried over, the original one stays. */
    fun toEntity(): MangaPrefsEntity {
        val cover = prefs.coverUrlOverride
        return if (cover != null && !cover.startsWith("http://") && !cover.startsWith("https://")) {
            prefs.copy(mangaId = manga.id, coverUrlOverride = null)
        } else {
            prefs.copy(mangaId = manga.id)
        }
    }
}
