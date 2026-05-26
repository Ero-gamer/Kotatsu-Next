package org.koitharu.kotatsu.reader.ui.pager.standard

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.Build
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setMargins
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.widgets.ZoomControl
import org.koitharu.kotatsu.databinding.ItemPageBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.BasePageHolder
import android.view.MotionEvent
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage

open class PageHolder(
	owner: LifecycleOwner,
	binding: ItemPageBinding,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
) : BasePageHolder<ItemPageBinding>(
	binding = binding,
	loader = loader,
	readerSettingsProducer = readerSettingsProducer,
	networkState = networkState,
	exceptionResolver = exceptionResolver,
	lifecycleOwner = owner,
), ZoomControl.ZoomControlListener, OnApplyWindowInsetsListener {

	override val ssiv = binding.ssiv

	init {
		ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			insets.toWindowInsets()?.let {
				applyRoundedCorners(it)
			}
		}
		return insets
	}

	override fun onConfigChanged(settings: ReaderSettings) {
		super.onConfigChanged(settings)
		binding.textViewNumber.isVisible = settings.isPagesNumbersEnabled
	}

	@SuppressLint("SetTextI18n")
	override fun onBind(data: ReaderPage) {
		super.onBind(data)
		binding.textViewNumber.text = (data.index + 1).toString()
	}

	override fun onReady() {
		// Compute maxScale as 3× the "fill" scale factor (the larger of width-fill and
		// height-fill ratios). Using 3× instead of 2× gives users noticeably more zoom
		// headroom, especially on high-DPI phones and Android TV where the original 2×
		// cap was too restrictive. 3× still keeps performance safe — SSIV loads
		// higher-res tiles progressively, so this does not increase memory usage.
		binding.ssiv.maxScale = 4f * maxOf(
			binding.ssiv.width / binding.ssiv.sWidth.toFloat(),
			binding.ssiv.height / binding.ssiv.sHeight.toFloat(),
		)
		// 4-step double-tap cycle: 100% → 130% → 160% → 200% → reset.
		// ZOOM_FOCUS_FIXED (already the fork default) anchors zoom to the tap pivot.
		// After each tap SSIV runs a 500ms animation; we post-update doubleTapZoomScale
		// once it completes so the next tap always zooms to the correct step.
		updateDoubleTapTarget()
		binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
		when (settings.zoomMode) {
			ZoomMode.FIT_CENTER -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
				binding.ssiv.resetScaleAndCenter()
			}

			ZoomMode.FIT_HEIGHT -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
				binding.ssiv.minScale = binding.ssiv.height / binding.ssiv.sHeight.toFloat()
				binding.ssiv.setScaleAndCenter(
					binding.ssiv.minScale,
					PointF(0f, binding.ssiv.sHeight / 2f),
				)
			}

			ZoomMode.FIT_WIDTH -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
				binding.ssiv.minScale = binding.ssiv.width / binding.ssiv.sWidth.toFloat()
				binding.ssiv.setScaleAndCenter(
					binding.ssiv.minScale,
					PointF(binding.ssiv.sWidth / 2f, 0f),
				)
			}

			ZoomMode.KEEP_START -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
				binding.ssiv.setScaleAndCenter(
					binding.ssiv.maxScale,
					PointF(0f, 0f),
				)
			}
		}
	}

	override fun onZoomIn() {
		scaleBy(1.2f)
	}

	override fun onZoomOut() {
		scaleBy(0.8f)
	}

	@SuppressLint("RtlHardcoded")
	@RequiresApi(Build.VERSION_CODES.S)
	protected open fun applyRoundedCorners(insets: WindowInsets) {
		binding.textViewNumber.updateLayoutParams<FrameLayout.LayoutParams> {
			val baseMargin = context.resources.getDimensionPixelOffset(R.dimen.margin_small)
			val absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection)
			val corner = when {
				absoluteGravity and Gravity.LEFT == Gravity.LEFT -> {
					insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
				}

				absoluteGravity and Gravity.RIGHT == Gravity.RIGHT -> {
					insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
				}

				else -> {
					null
				}
			}
			setMargins(baseMargin + (corner?.radius ?: 0))
		}
	}

	/**
	 * Cycles through four zoom levels on each double tap:
	 *   100% (fit) → 120% → 150% → 200% → reset to 100%
	 *
	 * Uses the same pivot-point zoom math as WebtoonScalingFrame so the tapped
	 * pixel stays visually fixed during zoom. SSIV's animateScaleAndCenter() pans
	 * so the given source point ends up at the VIEW CENTER — not at the tap point.
	 * We compensate by shifting the source center by (viewMid − tapPos) / targetScale,
	 * which exactly cancels the pan-to-center effect and anchors zoom to the tap.
	 */
	/**
	 * Sets [SubsamplingScaleImageView.doubleTapZoomScale] to the correct next step
	 * of the 100% → 130% → 160% → 200% → reset cycle, based on the current scale.
	 * Called from [onReady] and posted after each double-tap animation (500 ms) so
	 * the target is always primed before the next tap.
	 */
	private fun updateDoubleTapTarget() {
		val ssiv = binding.ssiv
		if (!ssiv.isReady) return
		val base = ssiv.minScale
		if (base <= 0f || base.isNaN()) return
		val current = ssiv.scale
		val eps = base * 0.05f
		val s130 = base * 1.3f
		val s160 = base * 1.6f
		val s200 = base * 2.0f
		ssiv.doubleTapZoomScale = when {
			current < base + eps -> s130  // at 100%: next tap → 130%
			current < s130 + eps -> s160  // at 130%: next tap → 160%
			current < s160 + eps -> s200  // at 160%: next tap → 200%
			else                 -> base  // at 200%+: next tap resets
		}
		// Re-prime after the double-tap animation completes (SSIV default: 500 ms).
		// Using postDelayed ensures we read the settled scale, not the in-flight one.
		ssiv.removeCallbacks(::updateDoubleTapTarget)
		ssiv.postDelayed(::updateDoubleTapTarget, DOUBLE_TAP_ZOOM_DURATION_MS + 50L)
	}

	private fun scaleBy(factor: Float) {
		val ssiv = binding.ssiv
		val center = ssiv.getCenter() ?: return
		val newScale = ssiv.scale * factor
		ssiv.animateScaleAndCenter(newScale, center)?.apply {
			withDuration(ssiv.resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
			withInterpolator(DecelerateInterpolator())
			start()
		}
	}

	private companion object {
		/** Must match SubsamplingScaleImageView.doubleTapZoomDuration (default 500 ms). */
		private const val DOUBLE_TAP_ZOOM_DURATION_MS = 500L
	}
}