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
import android.view.GestureDetector
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

	/**
	 * Overrides SSIV's built-in double-tap zoom (which jumps directly to maxScale).
	 * Implements a 3-step cycle: 100% (fit) → 150% → 200% → back to 100%.
	 *
	 * We use setOnTouchListener and forward all events to our GestureDetector.
	 * Returning true only on the confirmed double-tap DOWN prevents SSIV's own
	 * GestureDetector from seeing that event, suppressing its native double-tap zoom.
	 * All other events return false so SSIV handles pan/pinch/single-tap normally.
	 */
	init {
		ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
		// Intercept SSIV double-tap to implement our 3-step zoom cycle.
		// The detector is created inside init{} (not as a property) so `this` is
		// guaranteed to be fully initialized before the lambda capturing it is created.
		// Replace SSIV's built-in double-tap zoom (single toggle to maxScale) with our
		// 4-step cycle. setOnDoubleTapListener is the standard SSIV API for this:
		// when set, SSIV calls the listener instead of its own onDoubleTap handler,
		// so there is zero leakage to SSIV's internal GestureDetector.
		binding.ssiv.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
			override fun onSingleTapConfirmed(e: MotionEvent) = false
			override fun onDoubleTap(e: MotionEvent): Boolean {
				performSteppedZoom(e)
				return true
			}
			override fun onDoubleTapEvent(e: MotionEvent) = false
		})
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
	 * 4-step zoom cycle: 100% → 130% → 160% → 200% → reset to 100%.
	 *
	 * Pivot math: animateScaleAndCenter(scale, center) pans so `center` (source-space)
	 * lands at the VIEW MIDPOINT. We compensate by shifting center by
	 * (viewMid - tapPos) / targetScale so the tapped pixel stays fixed instead.
	 */
	private fun performSteppedZoom(e: MotionEvent) {
		val ssiv = binding.ssiv
		if (!ssiv.isReady) return
		// DoublePageHolder locks SSIV zoom (maxScale == minScale); DoublePageScalingFrame handles it.
		if (ssiv.maxScale <= ssiv.minScale + 0.01f) return
		val base = ssiv.minScale
		if (base <= 0f || base.isNaN()) return
		val current = ssiv.scale
		val eps = base * 0.05f
		val s130 = base * 1.3f
		val s160 = base * 1.6f
		val s200 = base * 2.0f
		val target = when {
			current < base + eps -> s130  // 100% → 130%
			current < s130 + eps -> s160  // 130% → 160%
			current < s160 + eps -> s200  // 160% → 200%
			else                 -> base  // 200%+ → reset to 100%
		}
		val src = ssiv.viewToSourceCoord(e.x, e.y) ?: ssiv.getCenter() ?: return
		val center = android.graphics.PointF(
			src.x + (ssiv.width  / 2f - e.x) / target,
			src.y + (ssiv.height / 2f - e.y) / target,
		)
		ssiv.animateScaleAndCenter(target, center)
			?.withDuration(ssiv.resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
			?.withInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
			?.start()
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
}
