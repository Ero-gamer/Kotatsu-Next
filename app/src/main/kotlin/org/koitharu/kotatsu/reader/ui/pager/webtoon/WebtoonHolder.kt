package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.view.View
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.util.ext.isMemoryConstrained
import org.koitharu.kotatsu.databinding.ItemPageWebtoonBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.BasePageHolder

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

    // Pages enter and leave the foreground constantly while scrolling; re-decoding them each time
    // is pure stutter. Memory is bounded by tile windowing instead.
    override val usesBackgroundDownSampling: Boolean
        get() = false

    private var scrollToRestore = 0

    init {
        bindingInfo.progressBar.setVisibilityAfterHide(View.GONE)
        binding.ssiv.prefetchFraction =
            if (itemView.context.isMemoryConstrained()) PREFETCH_CONSTRAINED else PREFETCH_DEFAULT
    }

    override fun onReady() {
        applyColorFilter()
        with(binding.ssiv) {
            val targetScroll = when {
                scrollToRestore != 0 -> scrollToRestore
                itemView.top < 0 -> getScrollRange()
                else -> 0
            }
            scrollToRestore = 0
            // Defer until after the layout pass triggered by adjustScale() → requestLayout(),
            // otherwise onSizeChanged() resets pendingCenter and the first frame shows the
            // wrong position (requiring two scroll-ups to recover).
            scrollToAfterLayout(targetScroll)
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
