package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import androidx.core.view.ancestors
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.core.util.ext.resolveDp
import kotlin.math.roundToInt

class WebtoonImageView @JvmOverloads constructor(
	context: Context,
	attr: AttributeSet? = null,
) : SubsamplingScaleImageView(context, attr) {

	private val ct = PointF()

	private var scrollPos = 0
	private var debugPaint: Paint? = null

	// BUG 5 FIX: track whether we've been properly laid-out at least once
	// so we don't apply scroll/scale calculations with zero dimensions.
	private var isLayoutValid = false

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (isDebugDrawingEnabled) {
			drawDebug(canvas)
		}
	}

	// BUG 5 FIX: mark layout as valid once we have real dimensions
	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		isLayoutValid = w > 0 && h > 0
	}

	fun scrollBy(delta: Int) {
		val maxScroll = getScrollRange()
		if (maxScroll == 0) {
			return
		}
		val newScroll = scrollPos + delta
		scrollToInternal(newScroll.coerceIn(0, maxScroll))
	}

	fun scrollTo(y: Int) {
		// BUG 5 FIX: bail if we don't have valid image dimensions yet.
		// The caller (WebtoonHolder.onReady) will retry via scrollToRestore.
		if (sWidth == 0 || !isLayoutValid) {
			return
		}
		val maxScroll = getScrollRange()
		if (maxScroll == 0) {
			scrollToInternal(0)
			return
		}
		scrollToInternal(y.coerceIn(0, maxScroll))
	}

	fun getScroll() = scrollPos

	fun getScrollRange(): Int {
		if (!isReady || sWidth == 0 || width == 0) {
			return 0
		}
		val totalHeight = (sHeight * width / sWidth.toFloat()).roundToInt()
		return (totalHeight - height).coerceAtLeast(0)
	}

	override fun recycle() {
		scrollPos = 0
		isLayoutValid = false
		super.recycle()
	}

	override fun getSuggestedMinimumHeight(): Int {
		var desiredHeight = super.getSuggestedMinimumHeight()
		if (sHeight == 0) {
			val parentHeight = parentHeight()
			if (desiredHeight < parentHeight) {
				desiredHeight = parentHeight
			}
		}
		return desiredHeight
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
		val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
		val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
		val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
		val resizeWidth = widthSpecMode != MeasureSpec.EXACTLY
		val resizeHeight = heightSpecMode != MeasureSpec.EXACTLY
		var desiredWidth = parentWidth
		var desiredHeight = parentHeight
		if (sWidth > 0 && sHeight > 0) {
			if (resizeWidth && resizeHeight) {
				desiredWidth = sWidth
				desiredHeight = sHeight
			} else if (resizeHeight) {
				desiredHeight = (sHeight.toDouble() / sWidth.toDouble() * desiredWidth).toInt()
			} else if (resizeWidth) {
				desiredWidth = (sWidth.toDouble() / sHeight.toDouble() * desiredHeight).toInt()
			}
		}
		desiredWidth = desiredWidth.coerceAtLeast(suggestedMinimumWidth)
		desiredHeight = desiredHeight.coerceAtLeast(suggestedMinimumHeight).coerceAtMost(parentHeight())
		setMeasuredDimension(desiredWidth, desiredHeight)
	}

	// BUG 5 FIX: only fire onReady from onDownSamplingChanged if the view is
	// fully laid out. Previously this could fire with width==0, causing minScale=0
	// → infinity in scrollToInternal.
	override fun onDownSamplingChanged() {
		super.onDownSamplingChanged()
		if (isReady && isLayoutValid && width > 0 && sWidth > 0) {
			adjustScale()
			onImageEventListener.onReady()
		}
	}

	override fun onReady() {
		super.onReady()
		// onReady is only called from onDraw → checkReady, so layout is valid here.
		adjustScale()
	}

	private fun scrollToInternal(pos: Int) {
		// BUG 5 FIX: guard against zero/invalid dimensions that would produce
		// NaN or Infinity for minScale / center, causing extreme zoom.
		if (width <= 0 || sWidth <= 0 || sHeight <= 0) {
			// Save the position and wait for layout to be valid.
			scrollPos = pos
			return
		}
		val scale = width / sWidth.toFloat()
		// Sanity check: if scale is still nonsensical, bail.
		if (scale <= 0f || scale.isNaN() || scale.isInfinite()) {
			scrollPos = pos
			return
		}
		minScale = scale
		maxScale = scale
		scrollPos = pos
		// Calculate the center Y in source coordinates:
		// visible center in view = height/2; the scroll offset shifts the top of the image
		// by (scrollPos * scale) pixels, so the source Y at the view center is:
		//   (height/2 + scrollPos) / scale
		// This is correct regardless of scroll position.
		ct.set(sWidth / 2f, (height / 2f + pos.toFloat()) / scale)
		setScaleAndCenter(scale, ct)
	}

	private fun adjustScale() {
		// BUG 5 FIX: guard against invalid dimensions
		if (width <= 0 || sWidth <= 0) return
		val scale = width / sWidth.toFloat()
		if (scale <= 0f || scale.isNaN() || scale.isInfinite()) return
		minScale = scale
		maxScale = scale
		minimumScaleType = SCALE_TYPE_CUSTOM
		requestLayout()
	}

	private fun parentHeight(): Int {
		return ancestors.firstNotNullOfOrNull { it as? RecyclerView }?.height ?: 0
	}

	private fun drawDebug(canvas: Canvas) {
		val paint = debugPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = android.graphics.Color.RED
			strokeWidth = context.resources.resolveDp(2f)
			textAlign = Paint.Align.LEFT
			textSize = context.resources.resolveDp(14f)
			debugPaint = this
		}
		paint.style = Paint.Style.STROKE
		canvas.drawRect(1f, 1f, width.toFloat() - 1f, height.toFloat() - 1f, paint)
		paint.style = Paint.Style.FILL
		canvas.drawText("${getScroll()} / ${getScrollRange()}", 100f, 100f, paint)
	}
}
