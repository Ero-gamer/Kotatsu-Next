package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.viewbinding.ViewBinding
import com.google.android.material.navigation.NavigationBarView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppFont
import org.koitharu.kotatsu.core.prefs.FontTypefaceHolder
import org.koitharu.kotatsu.core.ui.util.ActionModeDelegate
import org.koitharu.kotatsu.core.util.ext.isWebViewUnavailable
import org.koitharu.kotatsu.main.ui.protect.ScreenshotPolicyHelper
import androidx.appcompat.R as appcompatR

abstract class BaseActivity<B : ViewBinding> :
	AppCompatActivity(),
	OnApplyWindowInsetsListener,
	ScreenshotPolicyHelper.ContentContainer {

	private var isAmoledTheme = false

	lateinit var viewBinding: B
		private set

	protected lateinit var exceptionResolver: ExceptionResolver
		private set

	@JvmField
	val actionModeDelegate = ActionModeDelegate()

	private lateinit var entryPoint: BaseActivityEntryPoint

	/**
	 * Resolved Typeface for the active runtime font (SYSTEM_FONT or a "system:Name" device font),
	 * or null for bundled/app-default fonts (handled by ThemeOverlay instead).
	 * Populated after super.onCreate(); safe to use from setContentView() onward.
	 */
	private var runtimeTypeface: Typeface? = null

	override fun attachBaseContext(newBase: Context) {
		entryPoint = EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(newBase.applicationContext)
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			AppCompatDelegate.setApplicationLocales(entryPoint.settings.appLocales)
		}
		super.attachBaseContext(newBase)
	}

	protected open val applyColorSchemeTheme: Boolean = true

	override fun onCreate(savedInstanceState: Bundle?) {
		val settings = entryPoint.settings
		isAmoledTheme = settings.isAmoledTheme
		if (applyColorSchemeTheme) {
			setTheme(settings.colorScheme.styleResId)
			if (isAmoledTheme) {
				setTheme(R.style.ThemeOverlay_Kotatsu_Amoled)
			}
			if (settings.isCoverTitleCardStyle) {
				setTheme(R.style.ThemeOverlay_Kotatsu_CoverTitleCards)
			} else {
				setTheme(R.style.ThemeOverlay_Kotatsu_ClassicCards)
			}
			// Bundled fonts: set theme overlay (declares android:fontFamily).
			// Runtime fonts (SYSTEM_FONT / "system:Name"): no static overlay — applied via
			// FontInflaterFactory2 installed AFTER super.onCreate() below.
			val fontKey = settings.appFontKey
			val fontOverlay = settings.appFont.themeOverlayRes
			if (fontOverlay != 0 &&
				fontKey != AppFont.SYSTEM_FONT.key &&
				!fontKey.startsWith("system:")
			) {
				setTheme(fontOverlay)
			}
		}
		putDataToExtras(intent)
		exceptionResolver = entryPoint.exceptionResolverFactory.create(this)
		if (applyColorSchemeTheme) {
			enableEdgeToEdge()
		}
		super.onCreate(savedInstanceState)

		// Install FontInflaterFactory2 AFTER super.onCreate() so AppCompat's Factory2 is
		// already set and can be captured as the delegate:
		//   FontInflaterFactory2 -> AppCompat Factory2 -> creates view -> font applied.
		//
		// Coverage: Activity layout, Fragment layouts (Fragment clones the activity inflater
		// inheriting the full factory chain), RecyclerView items via LayoutInflater.from(ctx).
		// NavigationBarView labels are handled in setContentView() via a post-layout walk.
		//
		// Both the resolve and the install are wrapped in runCatching so that any failure
		// (resource issue, reflection unavailable, etc.) silently falls back to the default
		// font rather than crashing the app.
		if (applyColorSchemeTheme) {
			val fontKey = settings.appFontKey
			if (fontKey == AppFont.SYSTEM_FONT.key || fontKey.startsWith("system:")) {
				runtimeTypeface = runCatching {
					val typeface = FontTypefaceHolder.resolve(this, fontKey)
					if (typeface != null) {
						FontInflaterFactory2.install(layoutInflater, typeface)
					}
					typeface
				}.getOrNull()
			}
		}
	}

	override fun onPostCreate(savedInstanceState: Bundle?) {
		super.onPostCreate(savedInstanceState)
		onBackPressedDispatcher.addCallback(actionModeDelegate)
	}

	override fun onNewIntent(intent: Intent) {
		putDataToExtras(intent)
		super.onNewIntent(intent)
	}

	@Deprecated("Use ViewBinding", level = DeprecationLevel.ERROR)
	override fun setContentView(layoutResID: Int) = throw UnsupportedOperationException()

	@Deprecated("Use ViewBinding", level = DeprecationLevel.ERROR)
	override fun setContentView(view: View?) = throw UnsupportedOperationException()

	protected fun setContentView(binding: B) {
		this.viewBinding = binding
		super.setContentView(binding.root)
		ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
		val toolbar = (binding.root.findViewById<View>(R.id.toolbar) as? Toolbar)
		toolbar?.let(this::setSupportActionBar)
		// NavigationBarView creates label TextViews programmatically (not via LayoutInflater),
		// so FontInflaterFactory2 never intercepts them. Walk the tree post-layout instead.
		val typeface = runtimeTypeface ?: return
		binding.root.post { applyTypefaceToNavBars(binding.root, typeface) }
	}

	protected fun setDisplayHomeAsUp(isEnabled: Boolean, showUpAsClose: Boolean) {
		supportActionBar?.run {
			setDisplayHomeAsUpEnabled(isEnabled)
			if (showUpAsClose) {
				setHomeAsUpIndicator(appcompatR.drawable.abc_ic_clear_material)
			}
		}
	}

	override fun onSupportNavigateUp(): Boolean {
		val fm = supportFragmentManager
		if (fm.isStateSaved) {
			return false
		}
		if (fm.backStackEntryCount > 0) {
			fm.popBackStack()
		} else {
			dispatchNavigateUp()
		}
		return true
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		if (BuildConfig.DEBUG) {
			if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
				ActivityCompat.recreate(this)
				return true
			} else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
				throw RuntimeException("Test crash")
			}
		}
		return super.onKeyDown(keyCode, event)
	}

	protected fun isDarkAmoledTheme(): Boolean {
		val uiMode = resources.configuration.uiMode
		val isNight = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
		return isNight && isAmoledTheme
	}

	@CallSuper
	override fun onSupportActionModeStarted(mode: ActionMode) {
		super.onSupportActionModeStarted(mode)
		actionModeDelegate.onSupportActionModeStarted(mode, window)
	}

	@CallSuper
	override fun onSupportActionModeFinished(mode: ActionMode) {
		super.onSupportActionModeFinished(mode)
		actionModeDelegate.onSupportActionModeFinished(mode, window)
	}

	protected open fun dispatchNavigateUp() {
		val upIntent = parentActivityIntent
		if (upIntent != null) {
			if (!navigateUpTo(upIntent)) {
				startActivity(upIntent)
			}
		} else {
			finishAfterTransition()
		}
	}

	override fun isNsfwContent(): Flow<Boolean> = flowOf(false)

	private fun putDataToExtras(intent: Intent?) {
		intent?.putExtra(AppRouter.KEY_DATA, intent.data)
	}

	protected fun setContentViewWebViewSafe(viewBindingProducer: () -> B): Boolean {
		return try {
			setContentView(viewBindingProducer())
			true
		} catch (e: Exception) {
			if (e.isWebViewUnavailable()) {
				Toast.makeText(this, R.string.web_view_unavailable, Toast.LENGTH_LONG).show()
				finishAfterTransition()
				false
			} else {
				throw e
			}
		}
	}

	protected fun hasViewBinding() = ::viewBinding.isInitialized

	// ── Nav bar typeface helpers ──────────────────────────────────────────────────────────────

	private fun applyTypefaceToNavBars(root: View, typeface: Typeface) {
		applyToNavBarsInGroup(root as? ViewGroup ?: return, typeface)
	}

	private fun applyToNavBarsInGroup(group: ViewGroup, typeface: Typeface) {
		for (i in 0 until group.childCount) {
			val child = group.getChildAt(i) ?: continue
			if (child is NavigationBarView) {
				applyTypefaceToGroup(child, typeface)
			} else if (child is ViewGroup) {
				applyToNavBarsInGroup(child, typeface)
			}
		}
	}

	private fun applyTypefaceToGroup(group: ViewGroup, typeface: Typeface) {
		for (i in 0 until group.childCount) {
			when (val child = group.getChildAt(i)) {
				is TextView -> {
					val style = child.typeface?.style ?: Typeface.NORMAL
					child.typeface = Typeface.create(typeface, style)
				}
				is ViewGroup -> applyTypefaceToGroup(child, typeface)
			}
		}
	}
}
