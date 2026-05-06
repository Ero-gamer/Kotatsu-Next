package org.koitharu.kotatsu.reader.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A wrapper that measures its height and provides it as width to its child,
 * designed to hold a Slider that is rotated 90 degrees.
 * Ported from Tsukimi (Tsukimi-devel).
 */
class VerticalSliderWrapper @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = measuredWidth
        val height = measuredHeight
        if (childCount > 0) {
            val child = getChildAt(0)
            val childWidthSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            val childHeightSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            child.measure(childWidthSpec, childHeightSpec)
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (childCount > 0) {
            val child = getChildAt(0)
            val width = right - left
            val height = bottom - top
            val cx = width / 2
            val cy = height / 2
            val cw = height
            val ch = width
            child.layout(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
        }
    }
}
