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

	// Whether onCreate() has already fired. BasePageHolder.onCreate() registers
	// observe() callbacks — they must only be registered once per holder instance,
	// not re-registered on every re-bind (reattachToParent replays the onCreate event).
	private var isInitialized = false

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
	open fun onCreate() {
		if (isInitialized) return
		isInitialized = true
		lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
	}

	@CallSuper
	open fun onStart() {
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
	 * Removes the parent lifecycle observer so it stops accumulating.
	 * Does NOT touch the holder's own lifecycle state — LifecycleRegistry cannot go
	 * backwards, and the holder's coroutines should keep running while pooled because
	 * the holder will be re-bound shortly.
	 * Must be called on recycle. Safe to call multiple times.
	 */
	fun detachFromParent() {
		parentObserver?.let {
			parentLifecycleOwner.lifecycle.removeObserver(it)
			parentObserver = null
		}
	}

	/**
	 * Re-registers this holder with the parent lifecycle.
	 * Must be called at the start of re-bind, after [detachFromParent].
	 * Safe to call if already attached.
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
		val isParentResumed = parentLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
		if (isCurrent && isParentResumed) {
			if (!isResumed()) onResume()
		} else {
			if (isResumed()) onPause()
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
			parentObserver = null
			this@LifecycleAwareViewHolder.onDestroy()
		}
	}
}
