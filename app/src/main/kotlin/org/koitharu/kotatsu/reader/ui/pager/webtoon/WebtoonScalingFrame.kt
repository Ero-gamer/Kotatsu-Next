package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewConfigurationCompat
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.widgets.ZoomControl
import org.koitharu.kotatsu.core.util.ext.getAnimationDuration
import kotlin.math.roundToInt

private const val MAX_SCALE = 3.5f       // double-tap zoom ceiling
private const val MAX_PINCH_SCALE = 6.0f // manual pinch/keyboard zoom ceiling (matches manga mode)
private const val MIN_SCALE = 0.5f

private const val FLING_RANGE = 20_000

class WebtoonScalingFrame @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyles: Int = 0,
) : FrameLayout(context, attrs, defStyles),
	ScaleGestureDetector.OnScaleGestureListener,
	ZoomControl.ZoomControlListener {

	private val scaleDetector = ScaleGestureDetector(context, this)
	private val gestureDetector = GestureDetector(context, GestureListener())
	private val overScroller = OverScroller(context, AccelerateDecelerateInterpolator())

	private val transformMatrix = Matrix()
	private val matrixValues = FloatArray(9)
	private val scale
		get() = matrixValues[Matrix.MSCALE_X]
	private val transX
		get() = halfWidth * (scale - 1f) + matrixValues[Matrix.MTRANS_X]
	private val transY
		get() = halfHeight * (scale - 1f) + matrixValues[Matrix.MTRANS_Y]
	private var halfWidth = 0f
	private var halfHeight = 0f
	private val translateBounds = RectF()
	private val targetHitRect = Rect()
	private var animator: ValueAnimator? = null

	// BUG 4 FIX: removed 'pendingScroll' field — we no longer manipulate
	// RecyclerView layoutParams.height (which caused the ATV14 ghost rendering).
	// Instead we use pure render transforms (scaleX/scaleY/translationX/Y).

	var isZoomEnable = false
		set(value) {
			field = value
			if (scale != 1f) {
				scaleChild(1f, halfWidth, halfHeight)
			}
		}

	var zoom: Float
		get() = scale
		set(value) {
			if (value != scale) {
				scaleChild(value, halfWidth, halfHeight)
				onPostScale(invalidateLayout = true)
			}
		}

	init {
		syncMatrixValues()
		// BUG 4 FIX: ensure children are properly clipped so that transform
		// changes don't cause hardware-layer ghost artifacts on ATV.
		clipChildren = true
		clipToPadding = true
	}

	override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
		if (!isZoomEnable || ev == null) {
			return super.dispatchTouchEvent(ev)
		}

		if (ev.action == MotionEvent.ACTION_DOWN && overScroller.computeScrollOffset()) {
			overScroller.forceFinished(true)
		}

		val consumed = gestureDetector.onTouchEvent(ev)
		scaleDetector.onTouchEvent(ev)

		// Offset event to inside the child view
		if (scale < 1 && !targetHitRect.contains(ev.x.toInt(), ev.y.toInt())) {
			ev.offsetLocation(halfWidth - ev.x + targetHitRect.width() / 3, 0f)
		}

		return consumed || scaleDetector.isInProgress || super.dispatchTouchEvent(ev)
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean {
		if (isZoomEnable && event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
			if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
				val withCtrl = event.metaState and KeyEvent.META_CTRL_MASK != 0
				if (withCtrl) {
					val axisValue =
						event.getAxisValue(MotionEvent.AXIS_VSCROLL) * ViewConfigurationCompat.getScaledVerticalScrollFactor(
							ViewConfiguration.get(context), context,
						)
					val newScale = (scale + axisValue).coerceIn(MIN_SCALE, MAX_PINCH_SCALE)
					scaleChild(newScale, event.x, event.y)
					return true
				}
			}
		}
		return super.onGenericMotionEvent(event)
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		if (!isZoomEnable) {
			return super.onKeyDown(keyCode, event)
		}
		return when (keyCode) {
			KeyEvent.KEYCODE_ZOOM_IN,
			KeyEvent.KEYCODE_NUMPAD_ADD,
			KeyEvent.KEYCODE_PLUS -> {
				onZoomIn()
				true
			}

			KeyEvent.KEYCODE_ZOOM_OUT,
			KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
			KeyEvent.KEYCODE_MINUS -> {
				onZoomOut()
				true
			}

			KeyEvent.KEYCODE_ESCAPE -> {
				smoothScaleTo(1f)
				true
			}

			else -> super.onKeyDown(keyCode, event)
		}
	}

	override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
		return if (isZoomEnable) {
			keyCode == KeyEvent.KEYCODE_NUMPAD_ADD
				|| keyCode == KeyEvent.KEYCODE_PLUS
				|| keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT
				|| keyCode == KeyEvent.KEYCODE_MINUS
				|| keyCode == KeyEvent.KEYCODE_ZOOM_IN
				|| keyCode == KeyEvent.KEYCODE_ZOOM_OUT
				|| keyCode == KeyEvent.KEYCODE_ESCAPE
				|| super.onKeyUp(keyCode, event)
		} else {
			super.onKeyUp(keyCode, event)
		}
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		halfWidth = w / 2f
		halfHeight = h / 2f
	}

	override fun onZoomIn() {
		smoothScaleTo(scale * 1.1f)
	}

	override fun onZoomOut() {
		smoothScaleTo(scale * 0.9f)
	}

	// BUG 4 FIX: completely rewritten invalidateTarget().
	// OLD: modified RecyclerView.layoutParams.height + requestLayout() + relayoutChildren()
	//   → causes RecyclerView to relayout mid-scroll, corrupting HW display lists on ATV14,
	//     producing "stuck" image strips at top/bottom.
	// NEW: use pure render transforms (scaleX, scaleY, translationX, translationY) on the
	//   RecyclerView child. These do NOT trigger layout, they only affect the render pass.
	//   The RecyclerView's layout remains unchanged; only its visual appearance is scaled.
	private fun invalidateTarget() {
		val targetChild = findTargetChild()
		adjustBounds()

		val currentScale = scale
		val currentTransX = transX
		val currentTransY = transY

		// BUG 4 FIX: when scale is exactly 1, reset everything to identity to
		// avoid any floating-point drift that could cause pixel-off rendering.
		if (currentScale == 1f || currentScale.isNaN()) {
			targetChild.scaleX = 1f
			targetChild.scaleY = 1f
			targetChild.translationX = 0f
			targetChild.translationY = 0f
			targetHitRect.setEmpty()
			// BUG 4 FIX: force a hardware layer flush when returning to identity scale.
			// This clears any stale GPU-cached content from previous scale states.
			targetChild.setLayerType(View.LAYER_TYPE_NONE, null)
			return
		}

		// Apply render-only transform. pivotX/pivotY are set to the view center
		// (halfWidth/halfHeight). With pivot at center, the correct translationX/Y is
		// exactly currentTransX/currentTransY — no division by scale.
		//
		// Proof: for pivot P, View maps x → P + scale*(x-P) + translation.
		// We want this to equal transformMatrix(x) = MTRANS_X + scale*x.
		// Solving: translation = transX  (see WebtoonScalingFrame.transX getter).
		// Dividing by scale (the old code) incorrectly halved the pan range at 2×
		// zoom, which caused the zoom-in-but-can't-pan bug in webtoon mode.
		targetChild.pivotX = halfWidth
		targetChild.pivotY = halfHeight
		targetChild.scaleX = currentScale
		targetChild.scaleY = currentScale
		targetChild.translationX = currentTransX
		targetChild.translationY = currentTransY

		// BUG 4 FIX: update the hit-rect for below-1 scale (for touch event offsetting).
		if (currentScale < 1f) {
			targetChild.getHitRect(targetHitRect)
		} else {
			targetHitRect.setEmpty()
		}

		// BUG 4 FIX: Force the RecyclerView to use a software layer during active
		// scale gestures. This prevents the GPU from caching intermediate states
		// that cause the ghost-image artifact on ATV14.
		// The layer is removed in onScaleEnd / smoothScaleTo's doOnEnd.
		if (scaleDetector.isInProgress) {
			if (targetChild.layerType != View.LAYER_TYPE_SOFTWARE) {
				targetChild.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
			}
		}
	}

	private fun syncMatrixValues() {
		transformMatrix.getValues(matrixValues)
	}

	private fun adjustBounds() {
		syncMatrixValues()
		val dx = when {
			transX < translateBounds.left -> translateBounds.left - transX
			transX > translateBounds.right -> translateBounds.right - transX
			else -> 0f
		}

		val dy = when {
			transY < translateBounds.top -> translateBounds.top - transY
			transY > translateBounds.bottom -> translateBounds.bottom - transY
			else -> 0f
		}

		// BUG 4 FIX: removed the pendingScroll / nestedScrollBy call.
		// Translating the RecyclerView during scroll via nestedScrollBy while
		// simultaneously changing its transform caused double-scroll artifacts.
		// The dy offset is now absorbed entirely by the translationY render property.
		if (dx != 0f || dy != 0f) {
			transformMatrix.postTranslate(dx, dy)
			syncMatrixValues()
		}
	}

	private fun scaleChild(
		newScale: Float,
		focusX: Float,
		focusY: Float,
	): Boolean {
		if (scale.isNaN() || scale == 0f) {
			return false
		}
		val factor = newScale / scale
		if (newScale > 1) {
			translateBounds.set(
				halfWidth * (1 - newScale),
				halfHeight * (1 - newScale),
				halfWidth * (newScale - 1),
				halfHeight * (newScale - 1),
			)
		} else {
			translateBounds.set(
				0f,
				halfHeight - halfHeight / newScale,
				0f,
				halfHeight - halfHeight / newScale,
			)
		}
		transformMatrix.postScale(factor, factor, focusX, focusY)
		invalidateTarget()
		return true
	}

	override fun onScale(detector: ScaleGestureDetector): Boolean {
		val newScale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_PINCH_SCALE)
		return scaleChild(newScale, detector.focusX, detector.focusY)
	}

	override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
		animator?.cancel()
		animator = null
		return true
	}

	override fun onScaleEnd(p0: ScaleGestureDetector) {
		onPostScale(invalidateLayout = false)
	}

	private fun onPostScale(invalidateLayout: Boolean) {
		val target = findTargetChild()
		// BUG 4 FIX: clear software layer after scale gesture ends so that
		// normal scrolling uses hardware acceleration again.
		target.post {
			target.setLayerType(View.LAYER_TYPE_NONE, null)
			target.updateChildrenScroll()
			if (invalidateLayout) {
				target.requestLayout()
			}
		}
	}

	private fun smoothScaleTo(target: Float) {
		val newScale = target.coerceIn(MIN_SCALE, MAX_PINCH_SCALE)
		animator?.cancel()
		animator = ValueAnimator.ofFloat(scale, newScale).apply {
			setDuration(context.getAnimationDuration(android.R.integer.config_shortAnimTime))
			interpolator = DecelerateInterpolator()
			addUpdateListener { scaleChild(it.animatedValue as Float, halfWidth, halfHeight) }
			doOnEnd {
				onPostScale(invalidateLayout = false)
				// BUG 4 FIX: ensure layer type is cleared after animation.
				findTargetChild().setLayerType(View.LAYER_TYPE_NONE, null)
			}
			start()
		}
	}

	private fun findTargetChild() = getChildAt(0) as WebtoonRecyclerView

	private inner class GestureListener : GestureDetector.SimpleOnGestureListener(), Runnable {
		private val prevPos = Point()

		override fun onScroll(
			e1: MotionEvent?,
			e2: MotionEvent,
			distanceX: Float,
			distanceY: Float,
		): Boolean {
			if (scale <= 1f || scale.isNaN()) return false

			// Sync matrix values so transX/transY are current.
			syncMatrixValues()

			// Determine whether we are at the vertical pan limits AND the user is trying
			// to scroll PAST them in that direction.
			//
			// transY layout: translateBounds.top = minimum transY (zoomed-up limit),
			//                translateBounds.bottom = maximum transY (zoomed-down limit)
			// distanceY > 0 → user dragging UP   → content moves up   → transY decreases
			// distanceY < 0 → user dragging DOWN  → content moves down → transY increases
			val atTopLimit    = distanceY > 0f && transY <= translateBounds.top    + 1f
			val atBottomLimit = distanceY < 0f && transY >= translateBounds.bottom - 1f

			if (atTopLimit || atBottomLimit) {
				// We have reached the vertical pan boundary. Pass the scroll gesture
				// directly to the RecyclerView so the user can navigate to the previous
				// or next webtoon strip page.
				// Note: nestedScrollBy is safe to call from a touch handler — it simply
				// queues a scroll on the RecyclerView, which processes it on the next frame.
				// This does NOT cause the layoutParams-mutation bug that triggered the ATV14
				// ghost-image artifact (that bug was from calling requestLayout() mid-frame).
				findTargetChild().nestedScrollBy(0, distanceY.toInt())
				return true  // Consume the gesture to prevent double-handling.
			}

			// Within the pan limits: move the zoomed content.
			transformMatrix.postTranslate(-distanceX, -distanceY)
			invalidateTarget()
			return true
		}

		override fun onDoubleTap(e: MotionEvent): Boolean {
			val newScale = if (scale != 1f) 1f else MAX_SCALE * 0.8f
			ValueAnimator.ofFloat(scale, newScale).run {
				interpolator = AccelerateDecelerateInterpolator()
				duration = context.getAnimationDuration(R.integer.config_defaultAnimTime)
				addUpdateListener {
					scaleChild(it.animatedValue as Float, e.x, e.y)
				}
				doOnEnd {
					// BUG 4 FIX: clear software layer after double-tap zoom.
					findTargetChild().setLayerType(View.LAYER_TYPE_NONE, null)
				}
				start()
			}
			return true
		}

		override fun onFling(
			e1: MotionEvent?,
			e2: MotionEvent,
			velocityX: Float,
			velocityY: Float,
		): Boolean {
			if (scale <= 1 || scale.isNaN()) return false

			prevPos.set(transX.toInt(), transY.toInt())
			overScroller.fling(
				prevPos.x,
				prevPos.y,
				velocityX.toInt(),
				velocityY.toInt(),
				translateBounds.left.toInt(),
				translateBounds.right.toInt(),
				translateBounds.top.toInt() - FLING_RANGE,
				translateBounds.bottom.toInt() + FLING_RANGE,
			)
			postOnAnimation(this)
			return true
		}

		override fun run() {
			if (overScroller.computeScrollOffset()) {
				transformMatrix.postTranslate(
					overScroller.currX.toFloat() - prevPos.x,
					overScroller.currY.toFloat() - prevPos.y,
				)
				prevPos.set(overScroller.currX, overScroller.currY)
				invalidateTarget()
				postOnAnimation(this)
			}
		}
	}
}
