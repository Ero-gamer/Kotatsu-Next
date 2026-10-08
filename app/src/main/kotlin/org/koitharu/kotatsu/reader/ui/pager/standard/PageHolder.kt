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
),
    ZoomControl.ZoomControlListener,
    OnApplyWindowInsetsListener {

    override val ssiv = binding.ssiv

    init {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
    }

    private companion object {
        // Double-tap stages, as multiples of the page's fit scale (100% = how the page is first shown).
        const val ZOOM_STAGE_1 = 2f
        const val ZOOM_STAGE_2 = 2.5f
        const val PINCH_FILL_FACTOR = 4f
        const val MAX_ZOOM_SCALE = 7f
        const val KEEP_START_MAX_SCALE = 6f
    }

    override fun onApplyWindowInsets(
        v: View,
        insets: WindowInsetsCompat,
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

    override fun onBind(data: ReaderPage) {
        super.onBind(data)
        binding.textViewNumber.text = (data.index + 1).toString()
    }

    override fun onRecycled() {
        binding.ssiv.doubleTapZoomStages = null
        super.onRecycled()
    }

    /**
     * Installs the pinch ceiling and the deterministic double-tap stages (fit → 2× → 2.5× → fit),
     * both relative to the final fit scale, so call it after the zoom mode has been applied.
     * Shared by the standard and the reversed (manga) pager.
     */
    protected fun setupDoubleTapZoom(minCeiling: Float = 0f) {
        val ssiv = binding.ssiv
        if (!ssiv.isReady) return
        val fit = ssiv.minScale
        if (fit <= 0f) return
        val fill = maxOf(ssiv.width / ssiv.sWidth.toFloat(), ssiv.height / ssiv.sHeight.toFloat())
        val pinchCeiling = minOf(PINCH_FILL_FACTOR * fill, MAX_ZOOM_SCALE)
        ssiv.maxScale = maxOf(ZOOM_STAGE_2 * fit, pinchCeiling, minCeiling)
        ssiv.doubleTapZoomStages = floatArrayOf(ZOOM_STAGE_1 * fit, ZOOM_STAGE_2 * fit)
    }

    override fun onReady() {
        val ssiv = binding.ssiv
        // KEEP_START historically opens at 4× fill (capped); independent of the double-tap stages.
        val keepStartScale =
            minOf(
                4f * maxOf(ssiv.width / ssiv.sWidth.toFloat(), ssiv.height / ssiv.sHeight.toFloat()),
                KEEP_START_MAX_SCALE,
            )
        applyColorFilter()
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
                binding.ssiv.maxScale = maxOf(binding.ssiv.maxScale, keepStartScale)
                binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                binding.ssiv.setScaleAndCenter(
                    keepStartScale,
                    PointF(0f, 0f),
                )
            }
        }
        setupDoubleTapZoom(if (settings.zoomMode == ZoomMode.KEEP_START) keepStartScale else 0f)
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
     * Zooms about the view centre by [factor] (used by the zoom-in / zoom-out controls).
     * Double-tap zoom is handled separately, see [setupDoubleTapZoom].
     */

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
