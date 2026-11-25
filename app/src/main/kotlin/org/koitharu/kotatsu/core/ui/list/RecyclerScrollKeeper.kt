package org.koitharu.kotatsu.core.ui.list

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver

class RecyclerScrollKeeper(
    private val rv: RecyclerView,
) : AdapterDataObserver() {

    private val scrollUpRunnable = Runnable {
        (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
    }

    fun attach() {
        rv.adapter?.registerAdapterDataObserver(this)
        rv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                rv.removeCallbacks(scrollUpRunnable)
            }
        })
    }

    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
        super.onItemRangeInserted(positionStart, itemCount)
        if (positionStart == 0 && isScrolledToTop()) {
            postScrollUp()
        }
    }

    override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
        super.onItemRangeMoved(fromPosition, toPosition, itemCount)
        if (toPosition == 0 && isScrolledToTop()) {
            postScrollUp()
        }
    }

    private fun postScrollUp() {
        rv.postDelayed(scrollUpRunnable, 500L)
    }

    private fun isScrolledToTop(): Boolean = (rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() == 0
}
