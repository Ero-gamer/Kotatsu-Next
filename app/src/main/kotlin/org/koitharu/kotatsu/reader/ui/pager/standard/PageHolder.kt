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
import org.koitharu.kotatsu.core.model.ZoomStart
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.widgets.ZoomControl
import org.koitharu.kotatsu.core.util.ext.getAnimationDuration
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

    /** Where [ZoomStart.AUTO] starts: the right edge for right-to-left readers. */
    protected open val startsAtRight: Boolean
        get() = false

    private var landscapeZoom: Runnable? = null

    init {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
    }

    private companion object {
        // Same zoom model as webtoon mode (WebtoonScalingFrame), as multiples of the fit scale:
        // double-tap toggles fit <-> 2.8x (3.5 * 0.8) about the tapped point, pinch ceiling 6x.
        const val DOUBLE_TAP_ZOOM = 2.8f
        const val MAX_ZOOM_SCALE = 6f
        const val KEEP_START_MAX_SCALE = 6f
        const val LANDSCAPE_ZOOM_DELAY_MS = 500L
        const val MIN_PAN_PX = 4f
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
        cancelLandscapeZoom()
        binding.ssiv.doubleTapZoomStages = null
        super.onRecycled()
    }

    /**
     * Applies the webtoon zoom model: pinch ceiling [MAX_ZOOM_SCALE] and a single double-tap stage
     * ([DOUBLE_TAP_ZOOM]) relative to the final fit scale, so call it after the zoom mode is applied.
     * Shared by the standard and the reversed (manga) pager.
     */
    protected fun setupDoubleTapZoom(minCeiling: Float = 0f) {
        val ssiv = binding.ssiv
        if (!ssiv.isReady) return
        val fit = ssiv.minScale
        if (fit <= 0f) return
        ssiv.maxScale = maxOf(MAX_ZOOM_SCALE * fit, minCeiling)
        ssiv.doubleTapZoomStages = floatArrayOf(DOUBLE_TAP_ZOOM * fit)
        ssiv.doubleTapZoomStyle = SubsamplingScaleImageView.ZOOM_FOCUS_FIXED
        ssiv.doubleTapZoomDuration = settings.doubleTapZoomDuration.takeIf { it > 0 }
            ?: ssiv.resources.getInteger(R.integer.config_defaultAnimTime)
        ssiv.isDoubleTapZoomEnabled = settings.isDoubleTapZoom
    }

    /** Horizontal start position in image pixels; [rtl] is what [ZoomStart.AUTO] resolves to. */
    protected fun startX(imageWidth: Int, rtl: Boolean): Float = when (settings.zoomStart) {
        ZoomStart.AUTO -> if (rtl) imageWidth.toFloat() else 0f
        ZoomStart.LEFT -> 0f
        ZoomStart.RIGHT -> imageWidth.toFloat()
        ZoomStart.CENTER -> imageWidth / 2f
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
                    PointF(startX(binding.ssiv.sWidth, rtl = false), binding.ssiv.sHeight / 2f),
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
                    PointF(startX(binding.ssiv.sWidth, rtl = false), 0f),
                )
            }

            ZoomMode.STRETCH -> {
                binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP
                binding.ssiv.resetScaleAndCenter()
            }

            ZoomMode.ORIGINAL -> {
                binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
                binding.ssiv.minScale = 1f
                binding.ssiv.setScaleAndCenter(
                    1f,
                    PointF(binding.ssiv.sWidth / 2f, binding.ssiv.sHeight / 2f),
                )
            }
        }
        setupDoubleTapZoom(if (settings.zoomMode == ZoomMode.KEEP_START) keepStartScale else 0f)
        scheduleLandscapeZoom()
    }

    /**
     * Tap-to-pan: moves a zoomed page one view width in the visual direction of [delta].
     * With [rows] (horizontal pagers) a page whose row is finished moves on to the next row instead: one view
     * height down when [forward] (up otherwise), restarting at the row's start edge, so a page taller than the
     * screen is read row by row. Returns false (so the caller turns the page) when disabled or nothing is left.
     */
    fun panByPage(delta: Int, forward: Boolean = delta > 0, rows: Boolean = false): Boolean {
        val ssiv = binding.ssiv
        if (!settings.isTapToPan || !ssiv.isReady || ssiv.scale <= 0f) return false
        val center = ssiv.getCenter() ?: return false
        // [delta] is already the ViewPager's visual direction (the reversed fragment negates it).
        val direction = delta
        val viewWidth = ssiv.width / ssiv.scale
        val half = viewWidth / 2f
        val remaining = if (direction > 0) ssiv.sWidth - (center.x + half) else center.x - half
        if (remaining * ssiv.scale >= MIN_PAN_PX) {
            val step = minOf(viewWidth, remaining)
            animatePanTo(if (direction > 0) center.x + step else center.x - step, center.y)
            return true
        }
        if (!rows) return false
        val viewHeight = ssiv.height / ssiv.scale
        val remainingY = if (forward) ssiv.sHeight - (center.y + viewHeight / 2f) else center.y - viewHeight / 2f
        if (remainingY * ssiv.scale < MIN_PAN_PX) return false
        val newY = if (forward) center.y + minOf(viewHeight, remainingY) else center.y - minOf(viewHeight, remainingY)
        // The new row starts at the edge opposite to the reading direction; a page no wider than the view keeps its x.
        val newX = when {
            ssiv.sWidth <= viewWidth -> center.x
            direction > 0 -> half
            else -> ssiv.sWidth - half
        }
        animatePanTo(newX, newY)
        return true
    }

    private fun animatePanTo(x: Float, y: Float) {
        val ssiv = binding.ssiv
        ssiv.animateScaleAndCenter(ssiv.scale, PointF(x, y))?.apply {
            withDuration(ssiv.context.getAnimationDuration(R.integer.config_shortAnimTime))
            withInterpolator(DecelerateInterpolator())
            start()
        }
    }

    protected fun cancelLandscapeZoom() {
        landscapeZoom?.let { binding.ssiv.removeCallbacks(it) }
        landscapeZoom = null
    }

    /**
     * A wide page shown fitted in a portrait view is small: after a short pause animate to fit its height.
     * Skipped once the user has zoomed; the animation itself is interrupted by any touch.
     */
    protected fun scheduleLandscapeZoom() {
        cancelLandscapeZoom()
        val ssiv = binding.ssiv
        if (!settings.isLandscapeAutoZoom || settings.zoomMode != ZoomMode.FIT_CENTER) return
        if (ssiv.sWidth <= ssiv.sHeight || ssiv.width >= ssiv.height) return
        val task = Runnable {
            landscapeZoom = null
            val target = ssiv.height / ssiv.sHeight.toFloat()
            if (!ssiv.isReady || ssiv.scale > ssiv.minScale * 1.01f || target <= ssiv.minScale * 1.01f) return@Runnable
            ssiv.animateScaleAndCenter(target, PointF(startX(ssiv.sWidth, startsAtRight), ssiv.sHeight / 2f))?.apply {
                withDuration(ssiv.context.getAnimationDuration(R.integer.config_defaultAnimTime))
                withInterpolator(DecelerateInterpolator())
                start()
            }
        }
        landscapeZoom = task
        ssiv.postDelayed(task, LANDSCAPE_ZOOM_DELAY_MS)
    }

    override fun onZoomIn() {
        scaleBy(1.1f)
    }

    override fun onZoomOut() {
        scaleBy(0.9f)
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
