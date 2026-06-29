package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import androidx.core.view.ancestors
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.resolveDp
import kotlin.math.roundToInt

class WebtoonImageView @JvmOverloads constructor(
	context: Context,
	attr: AttributeSet? = null,
) : SubsamplingScaleImageView(context, attr) {

	private val ct = PointF()

	private var scrollPos = 0
	private var pendingScrollPos = -1
	private var debugPaint: Paint? = null

	init {
		applyLowRamTileSize()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (isDebugDrawingEnabled) {
			drawDebug(canvas)
		}
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
		val maxScroll = getScrollRange()
		if (maxScroll == 0) {
			scrollToInternal(0)
			return
		}
		scrollToInternal(y.coerceIn(0, maxScroll))
	}

	fun getScroll() = scrollPos

	fun getScrollRange(): Int {
		if (!isReady) {
			return 0
		}
		val totalHeight = (sHeight * width / sWidth.toFloat()).roundToInt()
		return (totalHeight - height).coerceAtLeast(0)
	}

	override fun recycle() {
		scrollPos = 0
		pendingScrollPos = -1
		super.recycle()
	}

	fun applyLowRamTileSize() {
		// On low-RAM devices (2GB), cap tile dimensions to 512px.
		// Default TILE_SIZE_AUTO picks tiles as large as the canvas allows (~1080×1080px
		// on a 1080p screen = ~4.4MB per tile bitmap). With FilteringRegionDecoder adding
		// src+out IntArray pools of the same size, peak per-tile cost is ~13MB.
		// 512×512px tiles = ~1MB bitmap + ~2MB arrays = ~3MB per tile, 4× lower.
		// Trade-off: more tiles to load, but each is cheaper and failures are smaller.
		if (context.isLowRamDevice()) {
			maxTileWidth  = 512
			maxTileHeight = 512
		}
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

	override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
		super.onLayout(changed, left, top, right, bottom)
		// Apply any scroll deferred by scrollToAfterLayout(). This handles the race where
		// adjustScale() → requestLayout() → onSizeChanged() overwrites the pendingCenter
		// that scrollToInternal() had just set via setScaleAndCenter().
		val pending = pendingScrollPos
		if (pending >= 0) {
			pendingScrollPos = -1
			scrollToInternal(pending.coerceIn(0, getScrollRange()))
		}
	}

	override fun onDownSamplingChanged() {
		super.onDownSamplingChanged()
		if (isReady) {
			adjustScale()
			onImageEventListener.onReady()
		}
	}

	override fun onReady() {
		super.onReady()
		adjustScale()
	}

	private fun scrollToInternal(pos: Int) {
		minScale = width / sWidth.toFloat()
		maxScale = minScale
		scrollPos = pos
		ct.set(sWidth / 2f, (height / 2f + pos.toFloat()) / minScale)
		setScaleAndCenter(minScale, ct)
	}

	private fun adjustScale() {
		minScale = width / sWidth.toFloat()
		maxScale = minScale
		minimumScaleType = SCALE_TYPE_CUSTOM
		requestLayout()
	}

	/**
	 * Defers a scroll to [pos] until after the next layout pass.
	 * Use instead of [scrollTo] when called from [onReady], because [adjustScale] triggers
	 * [requestLayout] which causes [onSizeChanged] to overwrite the pendingCenter set by
	 * [scrollToInternal], resulting in the wrong position being shown until the user scrolls.
	 */
	fun scrollToAfterLayout(pos: Int) {
		pendingScrollPos = pos
		if (!isLayoutRequested) {
			requestLayout()
		}
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
