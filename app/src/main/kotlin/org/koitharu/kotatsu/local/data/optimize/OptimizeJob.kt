package org.koitharu.kotatsu.local.data.optimize

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * A queued optimize run: the options and the archives to process. Stored as a small JSON file because
 * WorkManager input data is capped at 10 KB, far too little for a big library. The file outlives a
 * process restart, which is what lets an interrupted run resume.
 */
class OptimizeJob(
    val options: OptimizeOptions,
    val files: List<File>,
) {

    @Throws(IOException::class)
    fun write(target: File) {
        val json = JSONObject()
            .put(KEY_SPLIT, options.splitTall)
            .put(KEY_CONVERT, options.convertToWebp)
            .put(KEY_QUALITY, options.fallbackQuality)
            .put(KEY_AUTO, options.autoQuality)
            .put(KEY_FILES, JSONArray(files.map { it.absolutePath }))
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot write ${target.name}")
        }
    }

    companion object {

        private const val KEY_SPLIT = "split"
        private const val KEY_CONVERT = "convert"
        private const val KEY_QUALITY = "quality"
        private const val KEY_AUTO = "auto"
        private const val KEY_FILES = "files"

        /** Null when the file is missing or damaged. */
        fun read(source: File): OptimizeJob? = try {
            val json = JSONObject(source.readText())
            val array = json.getJSONArray(KEY_FILES)
            OptimizeJob(
                options = OptimizeOptions(
                    splitTall = json.optBoolean(KEY_SPLIT, false),
                    convertToWebp = json.optBoolean(KEY_CONVERT, false),
                    fallbackQuality = json.optInt(KEY_QUALITY, OptimizeOptions.DEFAULT_QUALITY).coerceIn(1, 100),
                    autoQuality = json.optBoolean(KEY_AUTO, false), // jobs queued before this key existed ran with fixed rules
                ),
                files = List(array.length()) { File(array.getString(it)) },
            )
        } catch (_: Exception) {
            null
        }
    }
}
