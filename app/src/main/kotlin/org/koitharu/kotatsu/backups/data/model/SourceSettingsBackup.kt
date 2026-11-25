package org.koitharu.kotatsu.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
class SourceSettingsBackup(
    @SerialName("source") val source: String,
    @SerialName("values") val values: JsonObject,
)
