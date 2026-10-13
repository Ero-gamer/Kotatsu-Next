package org.koitharu.kotatsu.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.documentfile.provider.DocumentFile
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.resolveFile
import org.koitharu.kotatsu.core.util.ext.setDefaultValueCompat
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.local.data.CbzMaintenance
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.utils.DozeHelper
import javax.inject.Inject

@AndroidEntryPoint
class DownloadsSettingsFragment :
    BasePreferenceFragment(R.string.downloads),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val dozeHelper = DozeHelper(this)

    @Inject
    lateinit var storageManager: LocalStorageManager

    @Inject
    lateinit var downloadsScheduler: DownloadWorker.Scheduler

    private val pickFileTreeLauncher = OpenDocumentTreeHelper(this) {
        if (it != null) onDirectoryPicked(it)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_downloads)
        findPreference<ListPreference>(AppSettings.KEY_DOWNLOADS_FORMAT)?.run {
            entryValues = DownloadFormat.entries.names()
            setDefaultValueCompat(DownloadFormat.AUTOMATIC.name)
        }
        findPreference<ListPreference>(AppSettings.KEY_DOWNLOADS_METERED_NETWORK)?.run {
            entryValues = TriStateOption.entries.names()
            setDefaultValueCompat(TriStateOption.ASK.name)
        }
        dozeHelper.updatePreference()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        findPreference<Preference>(AppSettings.KEY_LOCAL_STORAGE)?.bindStorageName()
        findPreference<Preference>(AppSettings.KEY_LOCAL_MANGA_DIRS)?.bindDirectoriesCount()
        findPreference<Preference>(AppSettings.KEY_PAGES_SAVE_DIR)?.bindPagesDirectory()
        settings.subscribe(this)
    }

    override fun onDestroyView() {
        settings.unsubscribe(this)
        super.onDestroyView()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        when (key) {
            AppSettings.KEY_LOCAL_STORAGE -> {
                findPreference<Preference>(key)?.bindStorageName()
            }

            AppSettings.KEY_LOCAL_MANGA_DIRS -> {
                findPreference<Preference>(key)?.bindDirectoriesCount()
            }

            AppSettings.KEY_DOWNLOADS_METERED_NETWORK -> {
                updateDownloadsConstraints()
            }

            AppSettings.KEY_PAGES_SAVE_DIR -> {
                findPreference<Preference>(AppSettings.KEY_PAGES_SAVE_DIR)?.bindPagesDirectory()
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
        AppSettings.KEY_LOCAL_STORAGE -> {
            router.showDirectorySelectDialog()
            true
        }

        AppSettings.KEY_LOCAL_MANGA_DIRS -> {
            router.openDirectoriesSettings()
            true
        }

        KEY_CBZ_REPACK -> {
            confirmMaintenance(preference, repack = true)
            true
        }

        KEY_CBZ_FIX_EXT -> {
            confirmMaintenance(preference, repack = false)
            true
        }

        AppSettings.KEY_IGNORE_DOZE -> {
            dozeHelper.startIgnoreDoseActivity()
        }

        AppSettings.KEY_PAGES_SAVE_DIR -> {
            if (!pickFileTreeLauncher.tryLaunch(settings.getPagesSaveDir(preference.context)?.uri)) {
                Snackbar.make(
                    requireView(),
                    R.string.operation_not_supported,
                    Snackbar.LENGTH_SHORT,
                ).show()
            }
            true
        }

        else -> super.onPreferenceTreeClick(preference)
    }

    private var maintenanceJob: Job? = null

    private fun confirmMaintenance(preference: Preference, repack: Boolean) {
        if (maintenanceJob?.isActive == true) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(preference.title)
            .setMessage(R.string.archive_maintenance_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> runMaintenance(preference, repack) }
            .show()
    }

    private fun runMaintenance(preference: Preference, repack: Boolean) {
        val originalSummary = preference.summary
        maintenanceJob = viewLifecycleScope.launch {
            preference.isEnabled = false
            var changed = 0
            var failed = 0
            var total = 0
            try {
                val files = withContext(Dispatchers.IO) {
                    val dirs = storageManager.getReadableDirs()
                    CbzMaintenance.cleanupTemp(dirs)
                    CbzMaintenance.listArchives(dirs)
                }
                total = files.size
                for ((i, file) in files.withIndex()) {
                    preference.summary = getString(R.string.archive_maintenance_progress, i + 1, total)
                    withContext(Dispatchers.IO) {
                        ensureActive()
                        try {
                            if (if (repack) CbzMaintenance.repackStored(file) else CbzMaintenance.fixExtensions(file)) {
                                changed++
                            }
                        } catch (e: java.io.IOException) {
                            e.printStackTraceDebug()
                            failed++
                        }
                    }
                }
                view?.let {
                    Snackbar.make(it, getString(R.string.archive_maintenance_done, changed, failed, total), Snackbar.LENGTH_LONG).show()
                }
            } finally {
                preference.summary = originalSummary
                preference.isEnabled = true
            }
        }
    }

    private fun onDirectoryPicked(uri: Uri) {
        storageManager.takePermissions(uri)
        val doc = DocumentFile.fromTreeUri(requireContext(), uri)?.takeIf {
            it.canWrite()
        }
        settings.setPagesSaveDir(doc?.uri)
    }

    private fun Preference.bindStorageName() {
        viewLifecycleScope.launch {
            val storage = storageManager.getDefaultWriteableDir()
            summary = if (storage != null) {
                storageManager.getDirectoryDisplayName(storage, isFullPath = true)
            } else {
                getString(R.string.not_available)
            }
        }
    }

    private fun Preference.bindDirectoriesCount() {
        viewLifecycleScope.launch {
            val dirs = storageManager.getReadableDirs().size
            summary = resources.getQuantityStringSafe(R.plurals.items, dirs, dirs)
        }
    }

    private fun Preference.bindPagesDirectory() {
        viewLifecycleScope.launch {
            val df = withContext(Dispatchers.IO) {
                settings.getPagesSaveDir(this@bindPagesDirectory.context)
            }
            summary = df?.getDisplayPath(this@bindPagesDirectory.context)
                ?: this@bindPagesDirectory.context.getString(androidx.preference.R.string.not_set)
        }
    }

    private fun updateDownloadsConstraints() {
        val preference = findPreference<Preference>(AppSettings.KEY_DOWNLOADS_METERED_NETWORK)
        viewLifecycleScope.launch {
            try {
                preference?.isEnabled = false
                withContext(Dispatchers.Default) {
                    val option = when (settings.allowDownloadOnMeteredNetwork) {
                        TriStateOption.ENABLED -> true
                        TriStateOption.ASK -> return@withContext
                        TriStateOption.DISABLED -> false
                    }
                    downloadsScheduler.updateConstraints(option)
                }
            } catch (e: Exception) {
                e.printStackTraceDebug()
            } finally {
                preference?.isEnabled = true
            }
        }
    }

    private fun DocumentFile.getDisplayPath(context: Context): String = uri.resolveFile(context)?.path ?: uri.toString()

    private companion object {

        const val KEY_CBZ_REPACK = "cbz_repack_stored"
        const val KEY_CBZ_FIX_EXT = "cbz_fix_extensions"
    }
}
