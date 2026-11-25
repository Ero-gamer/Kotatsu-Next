package org.koitharu.kotatsu.reader.domain

import android.graphics.BitmapFactory
import android.util.Size
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import java.io.InputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.math.roundToInt

class DetectReaderModeUseCase @Inject constructor(
    private val dataRepository: MangaDataRepository,
    private val settings: AppSettings,
    private val mangaRepositoryFactory: MangaRepository.Factory,
    @MangaHttpClient private val okHttpClient: OkHttpClient,
    private val imageProxyInterceptor: ImageProxyInterceptor,
) {

    suspend operator fun invoke(manga: Manga, state: ReaderState?): ReaderMode {
        dataRepository.getReaderMode(manga.id)?.let { return it }
        val defaultMode = settings.defaultReaderMode
        if (!settings.isReaderModeDetectionEnabled || defaultMode == ReaderMode.WEBTOON) {
            return defaultMode
        }
        val chapter = state?.let { manga.findChapterById(it.chapterId) }
            ?: manga.chapters?.firstOrNull()
            ?: error("There are no chapters in this manga")
        val repo = mangaRepositoryFactory.create(manga.source)
        val pages = repo.getPages(chapter)
        return runCatchingCancellable {
            val isWebtoon = guessMangaIsWebtoon(repo, pages)
            if (isWebtoon) ReaderMode.WEBTOON else defaultMode
        }.onSuccess {
            dataRepository.saveReaderMode(manga, it)
        }.onFailure {
            it.printStackTraceDebug()
        }.getOrDefault(defaultMode)
    }

    /**
     * Automatic determine type of manga by page size: samples pages at 30/50/70 % and takes the
     * majority (stops as soon as it is decided, so usually two requests). Pages that fail to load are
     * skipped; it fails only when none could be measured.
     * @return true if most sampled pages are tall (webtoon)
     */
    private suspend fun guessMangaIsWebtoon(repository: MangaRepository, pages: List<MangaPage>): Boolean {
        require(pages.isNotEmpty()) { "No pages" }
        val indices = SAMPLE_POSITIONS.map { (pages.size * it).roundToInt().coerceIn(0, pages.lastIndex) }.distinct()
        var tall = 0
        var total = 0
        for (index in indices) {
            val isTall = runCatchingCancellable {
                val size = measurePage(repository, pages[index])
                size.width * MIN_WEBTOON_RATIO < size.height
            }.getOrNull() ?: continue
            total++
            if (isTall) tall++
            if (tall * 2 > indices.size || (total - tall) * 2 >= indices.size) break
        }
        check(total > 0) { "No page could be measured" }
        return tall * 2 > total
    }

    private suspend fun measurePage(repository: MangaRepository, page: MangaPage): Size {
        val url = repository.getPageUrl(page)
        val uri = url.toUri()

        return when {
            uri.isZipUri() -> runInterruptible(Dispatchers.IO) {
                ZipFile(uri.schemeSpecificPart).use { zip ->
                    val entry = zip.getEntry(uri.fragment)
                    zip.getInputStream(entry).use {
                        getBitmapSize(it)
                    }
                }
            }

            uri.isFileUri() -> runInterruptible(Dispatchers.IO) {
                uri.toFile().inputStream().use {
                    getBitmapSize(it)
                }
            }

            else -> {
                val request = PageLoader.createPageRequest(url, page.source)
                imageProxyInterceptor.interceptPageRequest(request, okHttpClient).use {
                    runInterruptible(Dispatchers.IO) {
                        getBitmapSize(it.body?.byteStream())
                    }
                }
            }
        }
    }

    companion object {

        private const val MIN_WEBTOON_RATIO = 1.8
        private val SAMPLE_POSITIONS = doubleArrayOf(0.3, 0.5, 0.7)

        private fun getBitmapSize(input: InputStream?): Size {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(input, null, options)?.recycle()
            val imageHeight: Int = options.outHeight
            val imageWidth: Int = options.outWidth
            check(imageHeight > 0 && imageWidth > 0)
            return Size(imageWidth, imageHeight)
        }
    }
}
