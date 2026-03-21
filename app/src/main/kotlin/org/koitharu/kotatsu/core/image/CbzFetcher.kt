package org.koitharu.kotatsu.core.image

import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toAndroidUri
import kotlinx.coroutines.runInterruptible
import okio.Path.Companion.toPath
import okio.openZip
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isZipUri
import coil3.Uri as CoilUri

class CbzFetcher(
    private val uri: Uri,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): SourceFetchResult = runInterruptible {
        // Safe path resolution: use path for file:// or schemeSpecificPart for others
        val pathString = if (uri.scheme == "file") uri.path else uri.schemeSpecificPart
        val filePath = requireNotNull(pathString) { "URI path is null: $uri" }.toPath()
        val entryName = requireNotNull(uri.fragment) { "Zip entry name (fragment) is missing: $uri" }
        
        // Open the zip filesystem. 
        // Note: Coil's ImageSource will close the FileSystem when the source is closed
        val fs = options.fileSystem.openZip(filePath)
        
        SourceFetchResult(
            source = ImageSource(
                file = entryName.toPath(),
                fileSystem = fs,
                metadata = null
            ),
            mimeType = MimeTypes.getMimeTypeFromExtension(entryName)?.toString() ?: "image/*",
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<CoilUri> {

        override fun create(
            data: CoilUri,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            val androidUri = data.toAndroidUri()
            return if (androidUri.isZipUri()) {
                CbzFetcher(androidUri, options)
            } else {
                null
            }
        }
    }
}
