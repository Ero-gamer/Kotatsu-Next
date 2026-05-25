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
		// Flag set in onDoubleTap (fires on 2nd tap DOWN) and cleared on ACTION_UP.
		// The onTouchListener returns true only while this flag is set, which blocks
		// SSIV from seeing the second tap without eating every other touch event.
		var consumingDoubleTap = false
		val doubleTapDetector = GestureDetector(
			binding.root.context,
			object : GestureDetector.SimpleOnGestureListener() {
				override fun onDoubleTap(e: MotionEvent): Boolean {
					consumingDoubleTap = true
					performSteppedZoom(e)
					return true
				}
			},
		)
		binding.ssiv.setOnTouchListener { _, event ->
			doubleTapDetector.onTouchEvent(event)
			if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
				val wasConsuming = consumingDoubleTap
				consumingDoubleTap = false
				wasConsuming
			} else {
				consumingDoubleTap
			}
		}
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
	private fun performSteppedZoom(e: MotionEvent) {
		val ssiv = binding.ssiv
		if (!ssiv.isReady) return
		// DoublePageHolder locks SSIV zoom (maxScale == minScale); handled by DoublePageScalingFrame.
		if (ssiv.maxScale <= ssiv.minScale + 0.01f) return
		val currentScale = ssiv.scale
		val base = ssiv.minScale
		if (base <= 0f || base.isNaN()) return
		val eps = base * 0.05f
		val step2 = base * 1.2f
		val step3 = base * 1.5f
		val step4 = base * 2.0f
		val targetScale = when {
			currentScale < base  + eps -> step2  // 100% → 120%
			currentScale < step2 + eps -> step3  // 120% → 150%
			currentScale < step3 + eps -> step4  // 150% → 200%
			else                       -> base   // 200%+ → reset to 100%
		}
		// Source point currently under the tap position.
		val sourceUnderTap = ssiv.viewToSourceCoord(e.x, e.y) ?: ssiv.getCenter() ?: return
		// Shift the center so the tapped pixel stays at the tap position rather than
		// being pulled to the view midpoint (matching WebtoonScalingFrame's pivot behaviour).
		val compensatedCenter = android.graphics.PointF(
			sourceUnderTap.x + (ssiv.width  / 2f - e.x) / targetScale,
			sourceUnderTap.y + (ssiv.height / 2f - e.y) / targetScale,
		)
		ssiv.animateScaleAndCenter(targetScale, compensatedCenter)
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
