package org.koitharu.kotatsu.reader.ui.pager.vertical

import androidx.viewpager2.widget.ViewPager2
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.BasePagerReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

/**
 * Vertical pager that reads upwards: the first page is at the bottom and the next page is reached by
 * swiping down. Same idea as the right-to-left reader: the page list is reversed and every index that
 * crosses the fragment boundary is mirrored, so the rest of the reader keeps working with real indexes.
 */
@AndroidEntryPoint
class BottomToTopReaderFragment : BasePagerReaderFragment() {

    override fun onInitPager(pager: ViewPager2) {
        super.onInitPager(pager)
        pager.orientation = ViewPager2.ORIENTATION_VERTICAL
    }

    override fun onCreateAdvancedTransformer(): ViewPager2.PageTransformer = VerticalPageAnimTransformer()

    override fun switchPageBy(delta: Int) {
        super.switchPageBy(-delta)
    }

    override fun switchPageTo(position: Int, smooth: Boolean) {
        super.switchPageTo(reversed(position), smooth)
    }

    override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) {
        super.onPagesChanged(pages.reversed(), pendingState)
    }

    override fun notifyPageChanged(page: Int) {
        val pos = reversed(page)
        viewModel.onCurrentPageChanged(pos, pos)
    }

    private fun reversed(position: Int): Int = ((readerAdapter?.itemCount ?: 0) - position - 1).coerceAtLeast(0)
}
