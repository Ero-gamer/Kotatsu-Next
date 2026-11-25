package org.koitharu.kotatsu.local.ui.optimize

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.EventFlow
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.local.data.optimize.OptimizeTaskEntity
import org.koitharu.kotatsu.local.ui.OptimizeScheduler
import javax.inject.Inject

@HiltViewModel
class OptimizeViewModel @Inject constructor(
    private val scheduler: OptimizeScheduler,
) : BaseViewModel() {

    /** `null` until the first database read, so the screen does not flash the empty state. */
    val tasks: StateFlow<List<OptimizeTaskEntity>?> = scheduler.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val messageEvent = MutableEventFlow<Int>()
    val onMessage: EventFlow<Int>
        get() = messageEvent

    fun pause(task: OptimizeTaskEntity) = scheduler.pause(task)

    fun resume(task: OptimizeTaskEntity) = scheduler.resume(task)

    fun cancel(task: OptimizeTaskEntity) {
        launchJob { scheduler.cancel(task) }
    }

    fun retry(task: OptimizeTaskEntity) {
        launchJob {
            if (!scheduler.retry(task)) {
                messageEvent.call(R.string.optimize_retry_unavailable)
            }
        }
    }

    fun remove(task: OptimizeTaskEntity) {
        launchJob { scheduler.remove(task) }
    }

    fun clearHistory() {
        val finished = tasks.value.orEmpty().filterNot { it.isActive }
        if (finished.isEmpty()) return
        launchJob { scheduler.clearFinished(finished) }
    }
}
