package org.koitharu.kotatsu.local.data.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import org.koitharu.kotatsu.core.exceptions.UnsupportedFileException
import org.koitharu.kotatsu.core.util.ext.openSource
import org.koitharu.kotatsu.core.util.ext.renameReplacing
import org.koitharu.kotatsu.core.util.ext.resolveName
import org.koitharu.kotatsu.core.util.ext.writeAllCancellable
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.hasZipExtension
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.io.File
import java.io.IOException
import javax.inject.Inject

@Reusable
class SingleMangaImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageManager: LocalStorageManager,
    private val pdfConverter: PdfToCbzConverter,
    @LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) {

    private val contentResolver = context.contentResolver

    /** @param onProgress (done, total) for imports that take a while (PDF pages) */
    suspend fun import(uri: Uri, onProgress: ((Int, Int) -> Unit)? = null): LocalManga {
        val result = if (isDirectory(uri)) {
            importDirectory(uri)
        } else {
            importFile(uri, onProgress)
        }
        localStorageChanges.emit(result)
        return result
    }

    private suspend fun importFile(uri: Uri, onProgress: ((Int, Int) -> Unit)?): LocalManga = withContext(Dispatchers.IO) {
        val contentResolver = storageManager.contentResolver
        val name = contentResolver.resolveName(uri) ?: throw IOException("Cannot fetch name from uri: $uri")
        if (name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)) {
            return@withContext importPdf(uri, name, onProgress)
        }
        if (!hasZipExtension(name)) {
            throw UnsupportedFileException("Unsupported file $name on $uri")
        }
        // The display name comes from another app: never let it point outside the library directory.
        val safeName = File(name).name.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: throw IOException("Bad file name: $name")
        val dest = File(getOutputDir(), safeName)
        // Copy to a .tmp next to the target, so a cancelled or failed import never leaves a truncated archive.
        val part = File(dest.parentFile, safeName + ".tmp")
        try {
            runInterruptible {
                contentResolver.openSource(uri)
            }.use { source ->
                part.sink().buffer().use { output ->
                    output.writeAllCancellable(source)
                }
            }
            part.renameReplacing(dest)
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        LocalMangaParser(dest).getManga(withDetails = false)
    }

    /** A PDF becomes a .cbz of the same name, written to a .tmp first so a cancelled import leaves nothing. */
    private suspend fun importPdf(uri: Uri, name: String, onProgress: ((Int, Int) -> Unit)?): LocalManga {
        val title = File(name).nameWithoutExtension.trim().ifEmpty { "PDF" }
        val safeName = File("$title.cbz").name.takeIf { it != ".cbz" && it != "..cbz" } ?: "PDF.cbz"
        val dest = File(getOutputDir(), safeName)
        val part = File(dest.parentFile, "$safeName.tmp")
        try {
            pdfConverter.convert(uri, part, onProgress)
            part.renameReplacing(dest)
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        return LocalMangaParser(dest).getManga(withDetails = false)
    }

    private suspend fun importDirectory(uri: Uri): LocalManga {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) {
            "Provided uri $uri is not a tree"
        }
        val dest = File(getOutputDir(), root.requireName())
        dest.mkdir()
        for (docFile in root.listFiles()) {
            docFile.copyTo(dest)
        }
        return LocalMangaParser(dest).getManga(withDetails = false)
    }

    private suspend fun DocumentFile.copyTo(destDir: File) {
        if (isDirectory) {
            val subDir = File(destDir, requireName())
            subDir.mkdir()
            for (docFile in listFiles()) {
                docFile.copyTo(subDir)
            }
        } else {
            source().use { input ->
                File(destDir, requireName()).sink().buffer().use { output ->
                    output.writeAllCancellable(input)
                }
            }
        }
    }

    private suspend fun getOutputDir(): File = storageManager.getDefaultWriteableDir() ?: throw IOException("External files dir unavailable")

    private suspend fun DocumentFile.source() = runInterruptible(Dispatchers.IO) {
        contentResolver.openSource(uri)
    }

    private fun DocumentFile.requireName(): String = name ?: throw IOException("Cannot fetch name from uri: $uri")

    private fun isDirectory(uri: Uri): Boolean = runCatching {
        DocumentFile.fromTreeUri(context, uri)
    }.isSuccess
}
