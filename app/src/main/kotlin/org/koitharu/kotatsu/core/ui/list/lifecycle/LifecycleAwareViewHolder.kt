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

	// Stored so we can remove this specific observer instance on recycle rather than
	// waiting for parent destruction. Without this, every bind registers a fresh observer
	// that is never removed until the fragment is destroyed — one leaked observer per
	// scroll event across a 100-page chapter = ~95 dead observer references held by the
	// fragment's lifecycle, each keeping its WebtoonHolder subtree alive.
	private var parentObserver: ParentLifecycleObserver? = null

	init {
		// itemView.post defers until after the first layout pass so the parent lifecycle
		// is in at least CREATED state before we register.
		itemView.post { attachToParent() }
	}

	fun setIsCurrent(value: Boolean) {
		isCurrent = value
		dispatchResumed()
	}

	@CallSuper
	open fun onCreate() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

	@CallSuper
	open fun onStart() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)

	@CallSuper
	open fun onResume() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

	@CallSuper
	open fun onPause() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)

	@CallSuper
	open fun onStop() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

	@CallSuper
	open fun onDestroy() = lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

	/**
	 * Removes the parent lifecycle observer so it stops accumulating.
	 * Does NOT destroy this holder's own lifecycle — the LifecycleRegistry cannot be
	 * reused after ON_DESTROY, so we keep it alive for the next re-bind.
	 * Must be called when the holder is recycled. Safe to call multiple times.
	 */
	fun detachFromParent() {
		parentObserver?.let {
			parentLifecycleOwner.lifecycle.removeObserver(it)
			parentObserver = null
		}
		// Pause/stop so that any repeatOnLifecycle(STARTED/RESUMED) blocks inside
		// active coroutines suspend while the holder is in the recycle pool.
		if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
			onPause()
		}
		if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
			onStop()
		}
	}

	/**
	 * Re-registers this holder with the parent lifecycle observer.
	 * Must be called at the start of re-bind (before any state observation restarts),
	 * after [detachFromParent] was called during recycling.
	 */
	fun reattachToParent() {
		if (parentObserver != null) return // already attached
		attachToParent()
	}

	private fun attachToParent() {
		if (parentObserver != null) return
		val observer = ParentLifecycleObserver()
		parentObserver = observer
		parentLifecycleOwner.lifecycle.addObserver(observer)
	}

	private fun dispatchResumed() {
		val isParentResumed = parentLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
		if (isCurrent && isParentResumed) {
			if (!isResumed()) {
				onResume()
			}
		} else {
			if (isResumed()) {
				onPause()
			}
		}
	}

	protected fun isResumed(): Boolean =
		lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

	private inner class ParentLifecycleObserver : DefaultLifecycleObserver {

		override fun onCreate(owner: LifecycleOwner) = this@LifecycleAwareViewHolder.onCreate()

		override fun onStart(owner: LifecycleOwner) = this@LifecycleAwareViewHolder.onStart()

		override fun onResume(owner: LifecycleOwner) = this@LifecycleAwareViewHolder.dispatchResumed()

		override fun onPause(owner: LifecycleOwner) = this@LifecycleAwareViewHolder.dispatchResumed()

		override fun onStop(owner: LifecycleOwner) = this@LifecycleAwareViewHolder.onStop()

		override fun onDestroy(owner: LifecycleOwner) {
			// Parent is truly gone — destroy this holder's lifecycle too so
			// lifecycleScope cancels and all coroutine observers terminate.
			parentObserver = null // already being removed by the registry
			this@LifecycleAwareViewHolder.onDestroy()
		}
	}
}
