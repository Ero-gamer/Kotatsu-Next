package org.koitharu.kotatsu.core.ui.list.lifecycle

import android.view.View
import androidx.annotation.CallSuper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.RecyclerView

abstract class LifecycleAwareViewHolder(
	itemView: View,
	private val parentLifecycleOwner: LifecycleOwner,
) : RecyclerView.ViewHolder(itemView), LifecycleOwner {

	@Suppress("LeakingThis")
	final override val lifecycle = LifecycleRegistry(this)
	private var isCurrent = false

	// Guards BasePageHolder.onCreate() which registers observe() callbacks.
	// addObserver() replays lifecycle events up to the parent's current state on
	// every reattachToParent() call. Without this flag, onCreate() would fire again
	// on every re-bind, registering duplicate observers that accumulate across scrolls.
	private var isInitialized = false

	// The single observer instance registered on the parent lifecycle. Stored so
	// detachFromParent() can remove it precisely on recycle, preventing the
	// N-observers-per-page leak that kept WebtoonHolder subtrees alive.
	private var parentObserver: ParentLifecycleObserver? = null

	init {
		itemView.post { attachToParent() }
	}

	fun setIsCurrent(value: Boolean) {
		isCurrent = value
		dispatchResumed()
	}

	@CallSuper
	open fun onCreate() {
		// Guard: only run once per holder instance lifetime.
		// LifecycleRegistry rejects ON_CREATE when already at CREATED — calling it
		// again after reattachToParent() would throw IllegalStateException.
		if (isInitialized) return
		isInitialized = true
		lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
	}

	@CallSuper
	open fun onStart() {
		// Guard against replayed events from addObserver() arriving when already STARTED.
		if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
		lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
	}

	@CallSuper
	open fun onResume() {
		if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
		lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
	}

	@CallSuper
	open fun onPause() {
		if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
		lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
	}

	@CallSuper
	open fun onStop() {
		if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
		lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
	}

	@CallSuper
	open fun onDestroy() {
		if (lifecycle.currentState == Lifecycle.State.DESTROYED) return
		lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
	}

	/**
	 * Removes this holder's observer from the parent lifecycle.
	 *
	 * Does NOT change the holder's own lifecycle state. LifecycleRegistry cannot
	 * go backwards (e.g. RESUMED → CREATED), and dropping to STOPPED/CREATED here
	 * caused reattachToParent() → addObserver() to replay ON_CREATE on an already-
	 * CREATED registry, throwing IllegalStateException and breaking re-bind entirely.
	 *
	 * The holder's coroutines continue running while pooled — this is correct because
	 * observe() (non-repeatOnLifecycle) runs until ON_DESTROY regardless of STARTED/
	 * RESUMED state, and the holder will be re-bound shortly.
	 *
	 * Call from onViewRecycled().
	 */
	fun detachFromParent() {
		parentObserver?.let {
			parentLifecycleOwner.lifecycle.removeObserver(it)
			parentObserver = null
		}
	}

	/**
	 * Re-registers this holder with the parent lifecycle.
	 * addObserver() replays missed events up to the parent's current state, advancing
	 * the holder's lifecycle forward if needed. All event handlers are guarded against
	 * going backwards or replaying an already-handled state.
	 * Call from onBindViewHolder(), before bind().
	 */
	fun reattachToParent() {
		if (parentObserver != null) return
		attachToParent()
	}

	private fun attachToParent() {
		if (parentObserver != null) return
		val observer = ParentLifecycleObserver()
		parentObserver = observer
		parentLifecycleOwner.lifecycle.addObserver(observer)
	}

	private fun dispatchResumed() {
		val isParentResumed = parentLifecycleOwner.lifecycle.currentState
			.isAtLeast(Lifecycle.State.RESUMED)
		if (isCurrent && isParentResumed) {
			if (!isResumed()) onResume()
		} else {
			if (isResumed()) onPause()
		}
	}

	protected fun isResumed(): Boolean =
		lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

	private inner class ParentLifecycleObserver : DefaultLifecycleObserver {

		override fun onCreate(owner: LifecycleOwner) =
			this@LifecycleAwareViewHolder.onCreate()

		override fun onStart(owner: LifecycleOwner) =
			this@LifecycleAwareViewHolder.onStart()

		override fun onResume(owner: LifecycleOwner) =
			this@LifecycleAwareViewHolder.dispatchResumed()

		override fun onPause(owner: LifecycleOwner) =
			this@LifecycleAwareViewHolder.dispatchResumed()

		override fun onStop(owner: LifecycleOwner) =
			this@LifecycleAwareViewHolder.onStop()

		override fun onDestroy(owner: LifecycleOwner) {
			// Parent fragment/activity is gone — destroy this holder's lifecycle so
			// lifecycleScope cancels and all coroutines terminate cleanly.
			parentObserver = null // registry already removing it
			this@LifecycleAwareViewHolder.onDestroy()
		}
	}
}
