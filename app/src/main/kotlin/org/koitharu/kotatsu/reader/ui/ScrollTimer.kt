package org.koitharu.kotatsu.reader.ui

import android.content.res.Resources
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.util.ext.resolveDp
import kotlin.coroutines.resume
import kotlin.math.roundToLong

private const val MAX_DELAY = 32L
private const val MAX_SWITCH_DELAY = 10_000L
private const val INTERACTION_SKIP_MS = 2_000L
private const val SPEED_FACTOR_DELTA = 0.02f

// Pause/resume ramp, per millisecond (was SPEED_FACTOR_DELTA per MAX_DELAY tick).
private const val SPEED_RAMP_PER_MS = SPEED_FACTOR_DELTA / MAX_DELAY

// A stalled frame must not turn into one big jump.
private const val MAX_FRAME_DT_MS = 50L
private const val NOT_RESUMED_POLL_MS = 250L

class ScrollTimer @AssistedInject constructor(
    @Assisted resources: Resources,
    @Assisted private val listener: ReaderControlDelegate.OnInteractionListener,
    @Assisted lifecycleOwner: LifecycleOwner,
    settings: AppSettings,
) {

    private val coroutineScope = lifecycleOwner.lifecycleScope
    private var job: Job? = null
    private var delayMs: Long = 10L
    var pageSwitchDelay: Long = 100L
        private set
    private var resumeAt = 0L
    private var isTouchDown = MutableStateFlow(false)
    private val isRunning = MutableStateFlow(false)
    private val scrollDelta = resources.resolveDp(1)

    val isActive: StateFlow<Boolean>
        get() = isRunning

    init {
        settings.observeAsFlow(AppSettings.KEY_READER_AUTOSCROLL_SPEED) {
            readerAutoscrollSpeed
        }.flowOn(Dispatchers.Default)
            .onEach {
                onSpeedChanged(it)
            }.launchIn(coroutineScope)
    }

    fun setActive(value: Boolean) {
        if (isRunning.value != value) {
            isRunning.value = value
            restartJob()
        }
    }

    fun onUserInteraction() {
        resumeAt = SystemClock.elapsedRealtime() + INTERACTION_SKIP_MS
    }

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouchDown.value = true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                isTouchDown.value = false
            }
        }
    }

    private fun onSpeedChanged(speed: Float) {
        if (speed <= 0f) {
            delayMs = 0L
            pageSwitchDelay = 0L
        } else {
            val speedFactor = 1f - speed
            delayMs = (MAX_DELAY * speedFactor).roundToLong()
            pageSwitchDelay = (MAX_SWITCH_DELAY * speedFactor).roundToLong()
        }
        if ((job == null) != (delayMs == 0L)) {
            restartJob()
        }
    }

    private fun restartJob() {
        job?.cancel()
        resumeAt = 0L
        if (!isRunning.value || delayMs == 0L) {
            job = null
            return
        }
        job = coroutineScope.launch {
            var speedFactor = 1f
            var pending = 0f
            var blockedMs = 0L
            var blocked = false
            var last = awaitFrameMs()
            while (isActive) {
                val now = awaitFrameMs()
                val dt = (now - last).coerceIn(0L, MAX_FRAME_DT_MS)
                last = now
                val ramp = SPEED_RAMP_PER_MS * dt
                speedFactor = if (isPaused()) {
                    (speedFactor - ramp).coerceAtLeast(0f)
                } else {
                    (speedFactor + ramp).coerceAtMost(1f)
                }
                if (speedFactor == 0f) {
                    delayUntilResumed()
                    last = awaitFrameMs()
                    continue
                }
                if (!listener.isReaderResumed()) {
                    delay(NOT_RESUMED_POLL_MS)
                    last = awaitFrameMs()
                    continue
                }
                // Whole pixels per frame, the remainder carries over: speed no longer depends on timer jitter.
                pending += scrollDelta.toFloat() / delayMs * speedFactor * dt
                val whole = pending.toInt()
                if (whole > 0) {
                    pending -= whole
                    blocked = !listener.scrollBy(whole, false)
                }
                if (blocked) {
                    blockedMs += dt
                    if (blockedMs >= pageSwitchDelay) {
                        listener.switchPageBy(1)
                        blockedMs -= pageSwitchDelay
                        blocked = false
                    }
                } else {
                    blockedMs = 0L
                }
            }
        }
    }

    /** Suspends until the next display frame and returns its time in milliseconds. */
    private suspend fun awaitFrameMs(): Long = suspendCancellableCoroutine { cont ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { nanos -> cont.resume(nanos / 1_000_000L) }
        choreographer.postFrameCallback(callback)
        cont.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }

    private fun isPaused(): Boolean = isTouchDown.value || resumeAt > SystemClock.elapsedRealtime()

    private suspend fun delayUntilResumed() {
        while (isPaused()) {
            val delayTime = resumeAt - SystemClock.elapsedRealtime()
            if (delayTime > 0) {
                delay(delayTime)
            } else {
                yield()
            }
            isTouchDown.first { !it }
        }
    }

    @AssistedFactory
    interface Factory {

        fun create(
            resources: Resources,
            lifecycleOwner: LifecycleOwner,
            listener: ReaderControlDelegate.OnInteractionListener,
        ): ScrollTimer
    }
}
