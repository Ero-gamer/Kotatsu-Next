package org.koitharu.kotatsu.reader.ui.pager

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import kotlin.math.abs

/**
 * Pinch, pan and double-tap zoom for an animated page shown in a plain [ImageView].
 *
 * The image matrix is only ever a uniform scale plus a translation, so the state is three floats
 * and zooming never allocates. While zoomed the parent (pager) is blocked from stealing the drag,
 * except at an image edge in the drag direction so the next page can still be swiped to.
 */
class AnimatedZoomHelper(private val view: ImageView) : View.OnTouchListener {

    private val matrix = Matrix()
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitScale = 1f
    private var animator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(
        view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        },
    ).apply { isQuickScaleEnabled = false }

    private val gestureDetector = GestureDetector(
        view.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!isZoomed) return false
                val nx = clampX(scale, tx - distanceX)
                val ny = clampY(scale, ty - distanceY)
                val horizontal = abs(distanceX) >= abs(distanceY)
                val moved = if (horizontal) nx != tx else ny != ty
                // At the edge in the dominant drag direction: hand the gesture back to the pager.
                view.parent?.requestDisallowInterceptTouchEvent(moved)
                tx = nx
                ty = ny
                apply()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (drawableWidth() <= 0f) return false
                if (isZoomed) {
                    animateTo(fitScale, centeredX(fitScale), centeredY(fitScale))
                } else {
                    val target = fitScale * DOUBLE_TAP_ZOOM
                    val k = target / scale
                    animateTo(target, clampX(target, e.x - (e.x - tx) * k), clampY(target, e.y - (e.y - ty) * k))
                }
                return true
            }
        },
    )

    private val isZoomed: Boolean
        get() = scale > fitScale * ZOOMED_EPSILON

    init {
        view.scaleType = ImageView.ScaleType.MATRIX
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) reset()
        }
    }

    /** Fits the current drawable into the view and drops any zoom. Call after the image changes. */
    fun reset() {
        animator?.cancel()
        val dw = drawableWidth()
        val dh = drawableHeight()
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (dw <= 0f || dh <= 0f || vw <= 0f || vh <= 0f) return
        fitScale = minOf(vw / dw, vh / dh)
        scale = fitScale
        tx = centeredX(scale)
        ty = centeredY(scale)
        apply()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                if (isZoomed) v.parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (drawableWidth() <= 0f) return false
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val target = (scale * factor).coerceIn(fitScale, fitScale * MAX_ZOOM)
        val k = target / scale
        tx = clampX(target, fx - (fx - tx) * k)
        ty = clampY(target, fy - (fy - ty) * k)
        scale = target
        apply()
    }

    private fun animateTo(endScale: Float, endX: Float, endY: Float) {
        animator?.cancel()
        val s0 = scale
        val x0 = tx
        val y0 = ty
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                scale = s0 + (endScale - s0) * f
                tx = x0 + (endX - x0) * f
                ty = y0 + (endY - y0) * f
                apply()
            }
            start()
        }
    }

    private fun apply() {
        matrix.setScale(scale, scale)
        matrix.postTranslate(tx, ty)
        view.imageMatrix = matrix
    }

    private fun drawableWidth() = (view.drawable?.intrinsicWidth ?: 0).toFloat()

    private fun drawableHeight() = (view.drawable?.intrinsicHeight ?: 0).toFloat()

    private fun centeredX(s: Float) = (view.width - drawableWidth() * s) / 2f

    private fun centeredY(s: Float) = (view.height - drawableHeight() * s) / 2f

    /** Keeps the image covering the view along an axis, or centred when it is smaller than the view. */
    private fun clampX(s: Float, x: Float): Float {
        val extra = view.width - drawableWidth() * s
        return if (extra >= 0f) extra / 2f else x.coerceIn(extra, 0f)
    }

    private fun clampY(s: Float, y: Float): Float {
        val extra = view.height - drawableHeight() * s
        return if (extra >= 0f) extra / 2f else y.coerceIn(extra, 0f)
    }

    private companion object {
        const val MAX_ZOOM = 4f
        const val DOUBLE_TAP_ZOOM = 2.5f
        const val ZOOMED_EPSILON = 1.02f
        const val ANIMATION_MS = 220L
    }
}
