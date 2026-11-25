package org.koitharu.kotatsu.image.ui

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.lifecycle
import coil3.request.target
import coil3.target.GenericViewTarget
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.image.CoilMemoryCacheKey
import org.koitharu.kotatsu.core.image.ImageFormatProbe
import org.koitharu.kotatsu.core.image.codecs.NativeAnimatedDrawable
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.util.PopupMenuMediator
import org.koitharu.kotatsu.core.util.ShareHelper
import org.koitharu.kotatsu.core.util.ext.allowAnimation
import org.koitharu.kotatsu.core.util.ext.consumeAll
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.enqueueWith
import org.koitharu.kotatsu.core.util.ext.getDisplayIcon
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.databinding.ActivityImageBinding
import org.koitharu.kotatsu.databinding.ItemErrorStateBinding
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.pager.AnimatedZoomHelper
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class ImageActivity :
    BaseActivity<ActivityImageBinding>(),
    ImageRequest.Listener,
    View.OnClickListener {

    @Inject
    lateinit var coil: ImageLoader

    private var errorBinding: ItemErrorStateBinding? = null
    private val viewModel: ImageViewModel by viewModels()
    private lateinit var menuMediator: PopupMenuMediator
    private val animZoom by lazy(LazyThreadSafetyMode.NONE) { AnimatedZoomHelper(viewBinding.animatedView) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityImageBinding.inflate(layoutInflater))
        viewBinding.buttonBack.setOnClickListener(this)
        viewBinding.buttonMenu.setOnClickListener(this)
        viewBinding.buttonPlay.setOnClickListener(this)

        val menuProvider = ImageMenuProvider(
            activity = this,
            snackbarHost = viewBinding.root,
            viewModel = viewModel,
        )
        menuMediator = PopupMenuMediator(menuProvider)
        viewModel.isLoading.observe(this, ::onLoadingStateChanged)
        viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.root, null))
        viewModel.onImageSaved.observeEvent(this, ::onImageSaved)
        loadImage()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.button_back -> dispatchNavigateUp()
            R.id.button_menu -> menuMediator.onLongClick(v)
            R.id.button_play -> {
                viewBinding.buttonPlay.isVisible = false
                loadImage(animated = true)
            }

            else -> loadImage()
        }
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        viewBinding.progressBar.hide()
        with(errorBinding ?: ItemErrorStateBinding.bind(viewBinding.stubError.inflate())) {
            errorBinding = this
            root.isVisible = true
            textViewError.text = result.throwable.getDisplayMessage(resources)
            textViewError.setCompoundDrawablesWithIntrinsicBounds(0, result.throwable.getDisplayIcon(), 0, 0)
            buttonRetry.isVisible = true
            buttonRetry.setOnClickListener(this@ImageActivity)
        }
    }

    override fun onStart(request: ImageRequest) {
        viewBinding.progressBar.show()
        (errorBinding?.root ?: viewBinding.stubError).isVisible = false
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        viewBinding.progressBar.hide()
        (errorBinding?.root ?: viewBinding.stubError).isVisible = false
        if (viewBinding.animatedView.isVisible) {
            animZoom.reset()
            (viewBinding.animatedView.drawable as? Animatable)?.start()
        } else {
            offerPlayIfAnimated(result)
        }
    }

    /** Swipe down on an image that is not zoomed in closes the viewer. */
    private val closeDetector by lazy(LazyThreadSafetyMode.NONE) {
        val minDistance = resources.displayMetrics.density * CLOSE_SWIPE_DISTANCE_DP
        val minVelocity = ViewConfiguration.get(this).scaledMinimumFlingVelocity * CLOSE_SWIPE_VELOCITY_FACTOR
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    if (e1 == null || isMultiTouch || !isImageAtFit()) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (dy >= minDistance && dy > abs(dx) * 2f && velocityY >= minVelocity && velocityY > abs(velocityX)) {
                        dispatchNavigateUp()
                        return true
                    }
                    return false
                }
            },
        )
    }
    private var isMultiTouch = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            isMultiTouch = false
        } else if (ev.pointerCount > 1) {
            isMultiTouch = true
        }
        closeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isImageAtFit(): Boolean = with(viewBinding.ssiv) {
        isVisible && isReady && scale <= minScale * AT_FIT_TOLERANCE
    }

    override fun onDestroy() {
        (viewBinding.animatedView.drawable as? NativeAnimatedDrawable)?.release()
        super.onDestroy()
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val typeMask = WindowInsetsCompat.Type.systemBars()
        val barsInsets = insets.getInsets(typeMask)
        val baseMargin = v.resources.getDimensionPixelOffset(R.dimen.screen_padding)
        viewBinding.buttonMenu.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginEnd = barsInsets.end(v) + baseMargin
            topMargin = barsInsets.top + baseMargin
        }
        viewBinding.buttonBack.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = barsInsets.start(v) + baseMargin
            topMargin = barsInsets.top + baseMargin
        }
        return insets.consumeAll(typeMask)
    }

    /** Shows the play button when the loaded file is an animation; it is decoded as one only on tap. */
    private fun offerPlayIfAnimated(result: SuccessResult) {
        lifecycleScope.launch {
            val animated = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val local = intent.data?.takeIf { it.scheme == "file" }?.path?.let(::File)
                    if (local != null) {
                        ImageFormatProbe.probe(local).animated
                    } else {
                        result.diskCacheKey?.let { key ->
                            coil.diskCache?.openSnapshot(key)?.use { ImageFormatProbe.probe(it.data.toFile()).animated }
                        } == true
                    }
                }.getOrDefault(false)
            }
            if (animated && !viewBinding.animatedView.isVisible) viewBinding.buttonPlay.isVisible = true
        }
    }

    private fun loadImage(animated: Boolean = false) {
        val preview = intent.getParcelableExtraCompat<CoilMemoryCacheKey>(AppRouter.KEY_PREVIEW)?.data
        val builder = ImageRequest.Builder(this)
            .data(intent.data)
            .lifecycle(this)
            .listener(this)
            .mangaSourceExtra(MangaSource(intent.getStringExtra(AppRouter.KEY_SOURCE)))
        if (animated) {
            // The cached preview is the static first frame; an animation needs its own decode.
            viewBinding.ssiv.isVisible = false
            viewBinding.animatedView.isVisible = true
            viewBinding.animatedView.setOnTouchListener(animZoom)
            builder.allowAnimation(true)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .target(viewBinding.animatedView)
        } else {
            viewBinding.ssiv.isVisible = true
            viewBinding.animatedView.isVisible = false
            builder.memoryCacheKey(preview)
                .memoryCachePolicy(CachePolicy.READ_ONLY)
                .target(SsivTarget(viewBinding.ssiv))
        }
        builder.enqueueWith(coil)
    }

    private fun onImageSaved(uri: Uri) {
        Snackbar.make(viewBinding.root, R.string.page_saved, Snackbar.LENGTH_LONG)
            .setAction(R.string.share) {
                ShareHelper(this).shareImage(uri)
            }.show()
    }

    private fun onLoadingStateChanged(isLoading: Boolean) {
        val button = viewBinding.buttonMenu
        button.isClickable = !isLoading
        if (isLoading) {
            button.setImageDrawable(
                CircularProgressDrawable(this).also {
                    it.setStyle(CircularProgressDrawable.LARGE)
                    it.setColorSchemeColors(getThemeColor(appcompatR.attr.colorControlNormal))
                    it.start()
                },
            )
        } else {
            button.setImageResource(appcompatR.drawable.abc_ic_menu_overflow_material)
        }
    }

    private companion object {

        const val CLOSE_SWIPE_DISTANCE_DP = 96f
        const val CLOSE_SWIPE_VELOCITY_FACTOR = 8
        const val AT_FIT_TOLERANCE = 1.05f
    }

    private class SsivTarget(
        override val view: SubsamplingScaleImageView,
    ) : GenericViewTarget<SubsamplingScaleImageView>() {

        override var drawable: Drawable? = null
            set(value) {
                field = value
                setImageDrawable(value)
            }

        override fun equals(other: Any?): Boolean = (this === other) || (other is SsivTarget && view == other.view)

        override fun hashCode() = view.hashCode()

        override fun toString() = "SsivTarget(view=$view)"

        private fun setImageDrawable(drawable: Drawable?) {
            if (drawable != null) {
                view.setImage(ImageSource.bitmap(drawable.toBitmap()))
            } else {
                view.recycle()
            }
        }
    }
}
