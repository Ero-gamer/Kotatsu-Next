package org.koitharu.kotatsu.local.ui.optimize

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.core.graphics.Insets
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.ActivityOptimizeBinding
import org.koitharu.kotatsu.local.data.optimize.OptimizeTaskEntity

@AndroidEntryPoint
class OptimizeActivity :
    BaseActivity<ActivityOptimizeBinding>(),
    OptimizeAdapter.Listener {

    private val viewModel by viewModels<OptimizeViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityOptimizeBinding.inflate(layoutInflater))
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
        val adapter = OptimizeAdapter(this)
        viewBinding.recyclerView.adapter = adapter
        addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.opt_optimize, menu)
                }

                override fun onPrepareMenu(menu: Menu) {
                    menu.findItem(R.id.action_clear_history)?.isVisible =
                        viewModel.tasks.value.orEmpty().any { !it.isActive }
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
                    R.id.action_clear_history -> {
                        viewModel.clearHistory()
                        true
                    }

                    else -> false
                }
            },
        )
        viewModel.tasks.observe(this) { list ->
            adapter.submitList(list.orEmpty())
            viewBinding.textViewEmpty.isVisible = list != null && list.isEmpty()
            invalidateMenu()
        }
        viewModel.onMessage.observeEvent(this) {
            Snackbar.make(viewBinding.recyclerView, it, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        viewBinding.recyclerView.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
        viewBinding.appbar.updatePadding(left = bars.left, right = bars.right, top = bars.top)
        return WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
            .build()
    }

    override fun onPauseClick(task: OptimizeTaskEntity) = viewModel.pause(task)

    override fun onResumeClick(task: OptimizeTaskEntity) = viewModel.resume(task)

    override fun onCancelClick(task: OptimizeTaskEntity) = viewModel.cancel(task)

    override fun onRetryClick(task: OptimizeTaskEntity) = viewModel.retry(task)

    override fun onRemoveClick(task: OptimizeTaskEntity) = viewModel.remove(task)
}
