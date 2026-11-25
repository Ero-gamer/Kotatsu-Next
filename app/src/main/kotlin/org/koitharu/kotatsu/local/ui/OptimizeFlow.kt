package org.koitharu.kotatsu.local.ui

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.image.WebpEncoding
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.optimize.ImageOptimizer
import org.koitharu.kotatsu.local.data.optimize.OptimizeOptions
import java.io.File

/**
 * The user-facing steps of a manual image optimization, shared by the details menu, the chapter
 * selection and the settings screen:
 * 1. choose what to do (split tall images / convert to WebP);
 * 2. a header-only scan of the archives; when nothing qualifies a toast says so and nothing runs;
 * 3. a confirmation with the numbers (originals are replaced);
 * 4. the background run is queued ([OptimizeWorker]: progress, pause, resume, cancel in its notification).
 */
object OptimizeFlow {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {

        fun scheduler(): OptimizeScheduler

        fun settings(): AppSettings
    }

    /**
     * @param loadFiles resolves the archives to process; an empty list means "no downloaded chapters".
     */
    fun start(
        context: Context,
        scope: CoroutineScope,
        title: String,
        mangaId: Long,
        loadFiles: suspend (OptimizeScheduler) -> List<File>,
    ) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val canConvert = WebpEncoding.isNativeAvailable
        val labels = buildList {
            add(context.getString(R.string.optimize_split))
            if (canConvert) add(context.getString(R.string.optimize_convert))
        }.toTypedArray()
        val checked = BooleanArray(labels.size) { it == 0 }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.optimize_images)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val options = OptimizeOptions(
                    splitTall = checked[0],
                    convertToWebp = canConvert && checked.size > 1 && checked[1],
                    fallbackQuality = deps.settings().tallSplitQuality,
                    autoQuality = deps.settings().isWebpQualityAuto,
                )
                if (options.isNoop) {
                    Toast.makeText(context, R.string.optimize_select_option, Toast.LENGTH_SHORT).show()
                } else {
                    scan(context, scope, deps.scheduler(), options, title, mangaId, loadFiles)
                }
            }
            .show()
    }

    private fun scan(
        context: Context,
        scope: CoroutineScope,
        scheduler: OptimizeScheduler,
        options: OptimizeOptions,
        title: String,
        mangaId: Long,
        loadFiles: suspend (OptimizeScheduler) -> List<File>,
    ) {
        var job: Job? = null
        val progress = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.optimize_images)
            .setMessage(R.string.optimize_scanning)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .show()
        job = scope.launch {
            try {
                val files = loadFiles(scheduler)
                if (files.isEmpty()) {
                    progress.dismiss()
                    toast(context, R.string.optimize_no_downloads)
                    return@launch
                }
                val result = ImageOptimizer.scan(files, options)
                progress.dismiss()
                if (result.isEmpty) {
                    toast(context, if (options.convertToWebp) R.string.optimize_nothing else R.string.optimize_nothing_split)
                    return@launch
                }
                confirm(context, scope, scheduler, options, title, mangaId, files, result.imagesToSplit, result.imagesToConvert, result.archivesToChange, result.imagesTooLarge)
            } catch (e: CancellationException) {
                progress.dismiss()
                throw e
            } catch (e: Exception) {
                e.printStackTraceDebug()
                progress.dismiss()
                toast(context, R.string.error_occurred)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun confirm(
        context: Context,
        scope: CoroutineScope,
        scheduler: OptimizeScheduler,
        options: OptimizeOptions,
        title: String,
        mangaId: Long,
        files: List<File>,
        split: Int,
        convert: Int,
        archives: Int,
        tooLarge: Int,
    ) {
        val message = buildString {
            append(context.getString(R.string.optimize_confirm, split + convert, archives, split, convert))
            if (tooLarge > 0) {
                append("\n\n")
                append(context.getString(R.string.optimize_too_large, tooLarge))
            }
            val free = files.firstOrNull()?.parentFile?.usableSpace
            if (free != null) {
                append("\n\n")
                append(context.getString(R.string.optimize_free_space, Formatter.formatFileSize(context, free)))
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.optimize_images)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.optimize_start) { _, _ ->
                scope.launch {
                    try {
                        when (scheduler.enqueue(files, options, title, mangaId)) {
                            OptimizeScheduler.EnqueueResult.STARTED -> toast(context, R.string.optimize_started)
                            OptimizeScheduler.EnqueueResult.DUPLICATE -> toast(context, R.string.optimize_already_queued)
                            OptimizeScheduler.EnqueueResult.EMPTY -> Unit
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.printStackTraceDebug()
                        toast(context, R.string.error_occurred)
                    }
                }
            }
            .show()
    }

    private fun toast(context: Context, res: Int) {
        Toast.makeText(context, res, Toast.LENGTH_LONG).show()
    }
}
