package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.view.View
import android.view.ViewGroup
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.databinding.ItemPageWebtoonBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.domain.erase.PageMask
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.BasePageHolder
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.vm.PageState

class WebtoonHolder(
    owner: LifecycleOwner,
    binding: ItemPageWebtoonBinding,
    loader: PageLoader,
    readerSettingsProducer: ReaderSettings.Producer,
    networkState: NetworkState,
    exceptionResolver: ExceptionResolver,
) : BasePageHolder<ItemPageWebtoonBinding>(
    binding = binding,
    loader = loader,
    readerSettingsProducer = readerSettingsProducer,
    networkState = networkState,
    exceptionResolver = exceptionResolver,
    lifecycleOwner = owner,
) {

    override val ssiv = binding.ssiv

    private val pageRatios = loader.pageRatios
    private var isRatioRecorded = false

    // Pages enter and leave the foreground constantly while scrolling; re-decoding them each time
    // is pure stutter. Memory is bounded by tile windowing instead.
    override val usesBackgroundDownSampling: Boolean
        get() = false

    // Pages scroll partly out of view here: let an animation pause when it is mostly hidden.
    override fun visibleFraction(): Float {
        val parent = itemView.parent as? View ?: return 1f
        val height = itemView.height
        if (height <= 0 || parent.height <= 0) return 1f
        val visible = minOf(itemView.bottom, parent.height) - maxOf(itemView.top, 0)
        return (visible.toFloat() / minOf(height, parent.height)).coerceIn(0f, 1f)
    }

    private var scrollToRestore = 0
    private var lastSidePaddingMargin = -1

    /** What is being shown, as it has to be known once it has been replaced. See [topRowOnScreen]. */
    private var isShowingImage = false
    private var shownMask: PageMask? = null
    private var shownRegionTop = 0

    /** The row of the file to keep at the top of the screen while the image changes under it. */
    private var rowToKeepAtTop: Int? = null

    /** How far the list still has to move to undo this page's change of height. See [onResized]. */
    private var pendingShift = 0
    private var isShiftScheduled = false

    /** The list's height when this page was last laid out, as the list resizing resizes every page. */
    private var laidOutInHeight = 0

    init {
        bindingInfo.progressBar.setVisibilityAfterHide(View.GONE)
        binding.ssiv.prefetchFraction =
            if (settings.isMemorySaver) PREFETCH_CONSTRAINED else PREFETCH_DEFAULT
        itemView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            onResized(top, bottom, oldTop, oldBottom)
        }
    }

    /**
     * Keeps what is below a page where it was when the page changes height while reaching past the top of the screen.
     *
     * The list keeps that page's top edge in place, as it is the first one on screen. A page coming in from above starts as a
     * placeholder as tall as the screen, so one that turns out short - a banner, a credits page, what an erase left of one, or
     * nothing at all once hidden - would pull everything below it up by most of a screen: whatever was being read jumps away,
     * and scrolling up into the chapter before ends where it started. Below its top edge is what the reader is looking at, so
     * the bottom edge is what stays. A page whose erased image is being replaced under the top of the screen has its own
     * place to go back to: see [topRowOnScreen].
     */
    private fun onResized(top: Int, bottom: Int, oldTop: Int, oldBottom: Int) {
        val recyclerView = itemView.parent as? WebtoonRecyclerView ?: return
        val viewportHeight = recyclerView.height
        val isSameViewport = viewportHeight == laidOutInHeight
        laidOutInHeight = viewportHeight
        val shift = (bottom - top) - (oldBottom - oldTop)
        if (shift == 0 || !isSameViewport || top != oldTop || oldTop >= 0 || oldBottom <= 0 || rowToKeepAtTop != null) {
            return
        }
        pendingShift += shift
        if (isShiftScheduled) {
            return
        }
        isShiftScheduled = true
        // Not while the list is laying itself out; before anything of it is drawn, so the jump is never seen
        OneShotPreDrawListener.add(recyclerView) {
            val pending = pendingShift
            pendingShift = 0
            isShiftScheduled = false
            if (pending != 0 && itemView.parent === recyclerView) {
                recyclerView.scrollBy(0, pending)
                recyclerView.updateChildrenScroll()
            }
        }
    }

    override fun onBind(data: ReaderPage) {
        super.onBind(data)
        isRatioRecorded = false
        setCollapsed(false)
        showPlaceholder()
    }

    /** Until the page is ready it takes the height a page of this strip usually has, not a thin progress bar. */
    private fun showPlaceholder() {
        val parentWidth = (itemView.parent as? View)?.width?.takeIf { it > 0 }
            ?: itemView.resources.displayMetrics.widthPixels
        val width = parentWidth - 2 * lastSidePaddingMargin.coerceAtLeast(0)
        itemView.minimumHeight = if (binding.ssiv.isReady) 0 else pageRatios.placeholderHeight(width)
    }

    /**
     * A hidden page has nothing to show, and takes no room while it waits to leave the list. A view with no image
     * would otherwise stay as tall as whatever it last showed, or as the screen, as a blank in the middle of a strip.
     */
    override fun onStateChanged(state: PageState) {
        if (state is PageState.Loaded) {
            rowToKeepAtTop = topRowOnScreen()
        }
        super.onStateChanged(state)
        setCollapsed(state is PageState.Hidden)
        // An animated page never reports onReady: its size is known as soon as it is shown
        if (state is PageState.Hidden || (state is PageState.Shown && animatedView?.isVisible == true)) {
            itemView.minimumHeight = 0
        }
        isShowingImage = state is PageState.Shown
        if (state is PageState.Shown) {
            shownMask = state.mask
            shownRegionTop = viewModel.shownRegion?.top ?: 0
        }
    }

    override fun onRecycled() {
        itemView.minimumHeight = 0
        isRatioRecorded = false
        isShowingImage = false
        shownMask = null
        rowToKeepAtTop = null
        // A list that went away before drawing again never ran what was scheduled for it
        pendingShift = 0
        isShiftScheduled = false
        super.onRecycled()
    }

    /**
     * Which row of the file is at the top of the screen, for a page that reaches that far. Erasing makes a page shorter, and
     * with what was above the top of the screen gone the same scroll would be somewhere else: the whole strip moves under the
     * reader, for something they only asked to have taken out. Only a row of the file says the same thing before and after.
     * A page starting further down has nothing to keep: what is above it does not change.
     */
    private fun topRowOnScreen(): Int? {
        if (!isShowingImage || !binding.ssiv.isReady || itemView.top > 0 || itemView.bottom <= 0) {
            return null
        }
        val row = binding.ssiv.viewToSourceCoord(0f, -itemView.top.toFloat())?.y?.toInt() ?: return null
        val visible = row + shownRegionTop
        return shownMask?.toSource(visible) ?: visible
    }

    /** See [topRowOnScreen]. If that very row went, what comes after it is what ends up there. */
    private fun putRowAtTop(row: Int, recyclerView: WebtoonRecyclerView) {
        val ssiv = binding.ssiv
        if (itemView.parent !== recyclerView || !ssiv.isReady || ssiv.sWidth <= 0) {
            return
        }
        val visible = viewModel.shownMask?.toVisible(row) ?: row
        val rowInView = ((visible - (viewModel.shownRegion?.top ?: 0)).coerceAtLeast(0).toLong() * ssiv.width / ssiv.sWidth).toInt()
        val scroll = minOf(rowInView, ssiv.getScrollRange())
        ssiv.scrollTo(scroll)
        // What the page's own scroll cannot take up, the list does
        recyclerView.scrollBy(0, itemView.top - (scroll - rowInView))
    }

    private fun setCollapsed(isCollapsed: Boolean) {
        val params = itemView.layoutParams ?: return
        val height = if (isCollapsed) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        if (params.height != height) {
            params.height = height
            itemView.layoutParams = params
        }
    }

    override fun onConfigChanged(settings: ReaderSettings) {
        super.onConfigChanged(settings)
        binding.ssiv.prefetchFraction =
            if (settings.isMemorySaver) PREFETCH_CONSTRAINED else PREFETCH_DEFAULT
        val margin = (itemView.resources.displayMetrics.widthPixels * settings.webtoonSidePadding / 100f).toInt()
        if (lastSidePaddingMargin != margin) {
            lastSidePaddingMargin = margin
            binding.ssiv.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = margin
                rightMargin = margin
            }
            animatedView?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = margin
                rightMargin = margin
            }
        }
    }

    override fun onReady() {
        applyColorFilter()
        // Reached again on every settings change: one page counts once
        if (!isRatioRecorded) {
            isRatioRecorded = true
            pageRatios.record(binding.ssiv.sWidth, binding.ssiv.sHeight)
        }
        itemView.minimumHeight = 0
        val restore = scrollToRestore
        scrollToRestore = 0
        val recyclerView = itemView.parent as? WebtoonRecyclerView
        val rowToKeep = rowToKeepAtTop
        rowToKeepAtTop = null
        when {
            // Deferred until after the layout pass triggered by adjustScale() -> requestLayout()
            restore != 0 -> binding.ssiv.scrollToAfterLayout(restore)

            rowToKeep != null && recyclerView != null -> itemView.post {
                // After the layout it is about to have, as the view's height is part of how far it can scroll
                putRowAtTop(rowToKeep, recyclerView)
                recyclerView.onPageReady(itemView as WebtoonFrameLayout)
            }

            // Also reached on every settings change, so this must leave an already placed page alone
            recyclerView != null -> recyclerView.onPageReady(itemView as WebtoonFrameLayout)

            else -> binding.ssiv.scrollTo(binding.ssiv.getScroll())
        }
    }

    fun getScrollY() = binding.ssiv.getScroll()

    private companion object {
        // Tiles are decoded this many list heights beyond what is on screen.
        private const val PREFETCH_DEFAULT = 1f
        private const val PREFETCH_CONSTRAINED = 0.5f
    }

    fun restoreScroll(scroll: Int) {
        if (binding.ssiv.isReady) {
            binding.ssiv.scrollTo(scroll)
        } else {
            scrollToRestore = scroll
        }
    }
}
