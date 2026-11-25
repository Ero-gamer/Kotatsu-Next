package org.koitharu.kotatsu.settings.about

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivityLicensesBinding
import org.koitharu.kotatsu.main.ui.nav.FloatingNavTheme

/**
 * Open source licenses: every dependency of the app (generated at build time by the AboutLibraries
 * Gradle plugin from the resolved dependency graph) plus the native libraries and upstream projects
 * listed in config/aboutlibraries.
 */
@AndroidEntryPoint
class LicensesActivity : BaseActivity<ActivityLicensesBinding>() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityLicensesBinding.inflate(layoutInflater))
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
        viewBinding.composeView.setContent {
            FloatingNavTheme {
                val libraries by produceLibraries(R.raw.aboutlibraries)
                LibrariesContainer(
                    libraries = libraries,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val barsInsets = insets.systemBarsInsets
        viewBinding.composeView.updatePadding(
            left = barsInsets.left,
            right = barsInsets.right,
            bottom = barsInsets.bottom,
        )
        viewBinding.appbar.updatePadding(
            left = barsInsets.left,
            right = barsInsets.right,
            top = barsInsets.top,
        )
        return insets.consumeAllSystemBarsInsets()
    }
}
