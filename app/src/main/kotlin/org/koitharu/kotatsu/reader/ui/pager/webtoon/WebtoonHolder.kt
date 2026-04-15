package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.view.View
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
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

	private var scrollToRestore = 0

	// BUG 5 FIX: track whether we have a pending scroll that needs re-applying
	// after the image is re-measured (can happen after downsampling changes).
	private var pendingScrollApplied = false

	init {
		bindingInfo.progressBar.setVisibilityAfterHide(View.GONE)
	}

	override fun onReady() {
		binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
		with(binding.ssiv) {
			val targetScroll = when {
				scrollToRestore != 0 -> scrollToRestore
				itemView.top < 0 -> getScrollRange()
				else -> 0
			}
			scrollTo(targetScroll)
			// BUG 5 FIX: only clear scrollToRestore if the scroll actually
			// succeeded (i.e., the view had valid dimensions). If scrollTo
			// returned without doing anything (view not yet laid out), keep
			// scrollToRestore so onReady can retry.
			if (getScroll() == targetScroll || targetScroll == 0) {
				scrollToRestore = 0
				pendingScrollApplied = true
			}
		}
	}

	fun getScrollY() = binding.ssiv.getScroll()

	fun restoreScroll(scroll: Int) {
		// BUG 5 FIX: always update scrollToRestore first, then try to apply.
		// If the image isn't ready, the value persists and onReady() will use it.
		// If the image IS ready but dimensions are 0 (race during restore),
		// post the scroll to the next layout pass.
		scrollToRestore = scroll
		pendingScrollApplied = false
		if (binding.ssiv.isReady) {
			val maxScroll = binding.ssiv.getScrollRange()
			if (maxScroll > 0) {
				binding.ssiv.scrollTo(scroll)
				pendingScrollApplied = true
				scrollToRestore = 0
			} else {
				// Dimensions not ready yet even though isReady==true.
				// This can happen if onReady fired from onDownSamplingChanged
				// before layout. Post to next frame.
				binding.ssiv.post {
					if (scrollToRestore != 0 && !pendingScrollApplied) {
						binding.ssiv.scrollTo(scrollToRestore)
						scrollToRestore = 0
						pendingScrollApplied = true
					}
				}
			}
		}
	}
}
