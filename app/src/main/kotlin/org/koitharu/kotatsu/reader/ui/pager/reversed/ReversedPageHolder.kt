package org.koitharu.kotatsu.reader.ui.pager.reversed

import android.graphics.PointF
import android.view.Gravity
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.databinding.ItemPageBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.standard.PageHolder

class ReversedPageHolder(
    owner: LifecycleOwner,
    binding: ItemPageBinding,
    loader: PageLoader,
    readerSettingsProducer: ReaderSettings.Producer,
    networkState: NetworkState,
    exceptionResolver: ExceptionResolver,
) : PageHolder(
    owner = owner,
    binding = binding,
    loader = loader,
    readerSettingsProducer = readerSettingsProducer,
    networkState = networkState,
    exceptionResolver = exceptionResolver,
) {

    override val startsAtRight: Boolean
        get() = true

    init {
        (binding.textViewNumber.layoutParams as FrameLayout.LayoutParams)
            .gravity = Gravity.START or Gravity.BOTTOM
    }

    override fun onReady() {
        with(binding.ssiv) {
            // KEEP_START historically opens at 2× fill here; independent of the double-tap stages.
            val fillScale = maxOf(width / sWidth.toFloat(), height / sHeight.toFloat())
            val keepStartScale = 2f * fillScale
            applyColorFilter()
            when (settings.zoomMode) {
                ZoomMode.FIT_CENTER -> {
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                    resetScaleAndCenter()
                }

                ZoomMode.FIT_HEIGHT -> {
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
                    minScale = height / sHeight.toFloat()
                    setScaleAndCenter(
                        minScale,
                        PointF(startX(sWidth, rtl = true), sHeight / 2f),
                    )
                }

                ZoomMode.FIT_WIDTH -> {
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
                    minScale = width / sWidth.toFloat()
                    setScaleAndCenter(
                        minScale,
                        PointF(sWidth / 2f, 0f),
                    )
                }

                ZoomMode.KEEP_START -> {
                    maxScale = maxOf(maxScale, keepStartScale)
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                    setScaleAndCenter(
                        keepStartScale,
                        PointF(startX(sWidth, rtl = true), 0f),
                    )
                }

                ZoomMode.STRETCH -> {
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP
                    resetScaleAndCenter()
                }

                ZoomMode.ORIGINAL -> {
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
                    minScale = 1f
                    setScaleAndCenter(
                        1f,
                        PointF(sWidth / 2f, sHeight / 2f),
                    )
                }
            }
            setupDoubleTapZoom(if (settings.zoomMode == ZoomMode.KEEP_START) keepStartScale else 0f)
            scheduleLandscapeZoom()
        }
    }
}
