package org.koitharu.kotatsu.settings

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.system.Os
import android.system.OsConstants
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import coil3.ImageLoader
import com.davemorrissey.labs.subscaleview.decoder.DecoderStats
import com.davemorrissey.labs.subscaleview.decoder.JpegExtractCache
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import javax.inject.Inject

/**
 * Live runtime statistics (memory, image decoders, caches) for diagnosing performance on low-end
 * devices. Read-only apart from the reset / clear actions; refreshed while the screen is visible.
 */
@AndroidEntryPoint
class RuntimeStatsFragment : BasePreferenceFragment(R.string.runtime_stats) {

    @Inject
    lateinit var coil: ImageLoader

    private var lastReport: String = ""

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_runtime_stats)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
        "stat_copy" -> {
            val context = preference.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.runtime_stats), lastReport))
            Toast.makeText(context, R.string.stats_copied, Toast.LENGTH_SHORT).show()
            true
        }

        "stat_reset" -> {
            DecoderStats.reset()
            viewLifecycleOwner.lifecycleScope.launch { refresh() }
            true
        }

        "stat_clear_cache" -> {
            val appContext = preference.context.applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { JpegExtractCache.clear(appContext) }
                refresh()
            }
            true
        }

        else -> super.onPreferenceTreeClick(preference)
    }

    private suspend fun refresh() {
        val context = context?.applicationContext ?: return
        val values = withContext(Dispatchers.Default) { collect(context) }
        values.forEach { (key, text) -> findPreference<Preference>(key)?.summary = text }
        lastReport = values.joinToString("\n") { (key, text) -> "$key: $text" }
    }

    private fun collect(context: Context): List<Pair<String, String>> {
        fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stats = DecoderStats.snapshot()
        val extract = JpegExtractCache.usage(context)
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(0L)
        val coilMemory = coil.memoryCache
        val coilDisk = coil.diskCache

        fun tiles(count: Long, millis: Long) = getString(
            R.string.stats_tiles_fmt,
            count,
            if (count > 0) millis.toDouble() / count else 0.0,
        )

        return listOf(
            "stat_heap" to "${size(heapUsed)} / ${size(runtime.maxMemory())}",
            "stat_native" to "${size(Debug.getNativeHeapAllocatedSize())} / ${size(Debug.getNativeHeapSize())}",
            "stat_pss" to "${size(pss.totalPss * KIB)} (dalvik ${size(pss.dalvikPss * KIB)}, " +
                "native ${size(pss.nativePss * KIB)})",
            "stat_system" to "${size(mem.availMem)} free of ${size(mem.totalMem)}, class ${am.memoryClass} MB" +
                (if (mem.lowMemory) ", LOW" else "") + (if (am.isLowRamDevice) ", low-RAM device" else ""),
            "stat_jpeg_native" to tiles(stats.nativeTiles, stats.nativeMillis),
            "stat_skia" to tiles(stats.skiaTiles, stats.skiaMillis) + ", JPEG fallbacks ${stats.skiaFallbacks}",
            "stat_extract" to "${extract[0]} files, ${size(extract[1])}, extractions ${stats.extractions}",
            "stat_coil" to "memory ${size(coilMemory?.size ?: 0L)} / ${size(coilMemory?.maxSize ?: 0L)}, " +
                "disk ${size(coilDisk?.size ?: 0L)} / ${size(coilDisk?.maxSize ?: 0L)}",
            "stat_device" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.SUPPORTED_ABIS.joinToString()}, page size ${pageSize / KIB} KB, " +
                "${Runtime.getRuntime().availableProcessors()} cores",
            "stat_modes" to "Memory Saver ${onOff(settings.isMemorySaverEnabled)}, " +
                "32-bit colours ${onOff(settings.is32BitColorsEnabled)}, " +
                "animated images ${onOff(settings.isAnimatedImagesEnabled)}",
        )
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    private companion object {
        const val REFRESH_INTERVAL_MS = 1500L
        const val KIB = 1024L
    }
}
