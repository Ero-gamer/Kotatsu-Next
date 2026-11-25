package org.koitharu.kotatsu.reader.ui.pager.vertical

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

class VerticalPageAnimTransformer : ViewPager2.PageTransformer {

    override fun transformPage(page: View, position: Float) = with(page) {
        translationY = -position * height
        // Setters below do not compare with the current value: only touch them when they differ.
        val px = width / 2f
        if (pivotX != px) pivotX = px
        val py = height * 0.2f
        if (pivotY != py) pivotY = py
        if (abs(cameraDistance - CAMERA_DISTANCE) > 1f) cameraDistance = CAMERA_DISTANCE
        when {
            position < -1f || position > 1f -> {
                alpha = 0f
                rotationX = 0f
                translationZ = -1f
            }

            position > 0f -> {
                alpha = 1f
                rotationX = 0f
                translationZ = 0f
            }

            position <= 0f -> {
                alpha = 1f
                rotationX = -120 * position
                translationZ = 2f
            }
        }
    }

    private companion object {

        const val CAMERA_DISTANCE = 20000f
    }
}
