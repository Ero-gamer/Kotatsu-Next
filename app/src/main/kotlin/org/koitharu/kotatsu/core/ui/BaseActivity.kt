package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.core.view.descendants
import androidx.viewbinding.ViewBinding
import com.google.android.material.navigation.NavigationBarView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppFont
import org.koitharu.kotatsu.core.prefs.FontTypefaceHolder
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.nav.AppRouter
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

	override fun attachBaseContext(newBase: Context) {
		entryPoint = EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(newBase.applicationContext)
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			AppCompatDelegate.setApplicationLocales(entryPoint.settings.appLocales)
		}
		super.attachBaseContext(newBase)
	}

	/**
	 * If true (default), apply the app's color-scheme theme overlay on top of the manifest theme.
	 * Override to false for activities whose manifest theme must be preserved as-is.
	 */
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

			val fontKey = settings.appFontKey

			// For bundled fonts: apply the static ThemeOverlay so the theme attribute
			// `android:fontFamily` is set for Material3 components and non-inflated widgets.
			// System fonts are excluded — their overlay was wrong (mapped to Roboto, not OEM font).
			// FontInflaterFactory2 handles all fonts uniformly at inflation time.
			if (fontKey != AppFont.APP_DEFAULT.key &&
				fontKey != AppFont.SYSTEM_FONT.key &&
				!fontKey.startsWith("system:")
			) {
				val fontOverlay = settings.appFont.themeOverlayRes
				if (fontOverlay != 0) {
					setTheme(fontOverlay)
				}
			}
		}

		putDataToExtras(intent)
		exceptionResolver = entryPoint.exceptionResolverFactory.create(this)
		if (applyColorSchemeTheme) {
			enableEdgeToEdge()
		}

		// super.onCreate() installs AppCompat's WrapperFactory2 on the LayoutInflater.
		// We MUST call super first so that AppCompat's factory is in place before we wrap it.
		super.onCreate(savedInstanceState)

		// Install FontInflaterFactory2 AFTER super.onCreate() so we correctly capture
		// AppCompat's WrapperFactory2 as our delegate.
		// Chain: Font -> AppCompat -> creates view -> Font applies typeface.
		// This covers: activity layout, all hosted Fragment layouts, RecyclerView adapter items.
		if (applyColorSchemeTheme) {
			FontInflaterFactory2.installFromSettings(layoutInflater, applicationContext, entryPoint.settings.appFontKey)
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

		// NavigationBarView (bottom nav / nav rail) renders its menu item labels internally
		// through the Material menu system — NOT through LayoutInflater — so our Factory2
		// does not reach those TextViews.  The menu items are built lazily after setContentView,
		// so we defer the typeface walk to the next layout pass via post().
		if (applyColorSchemeTheme) {
			val typeface = FontTypefaceHolder.resolve(applicationContext, entryPoint.settings.appFontKey)
			if (typeface != null) {
				binding.root.post { applyFontToNavBars(binding.root, typeface) }
			}
		}
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

	/**
	 * Apply [typeface] to all [TextView] descendants inside any [NavigationBarView] found in
	 * [root].  NavigationBarView renders its menu item labels through the Material menu system
	 * rather than through [android.view.LayoutInflater], so [FontInflaterFactory2] never sees
	 * those views.  A direct typeface walk is the only reliable fix.
	 *
	 * This is a one-time call from [setContentView] and is O(view-tree depth) — negligible cost.
	 */
	private fun applyFontToNavBars(root: View, typeface: android.graphics.Typeface) {
		val rootGroup = root as? ViewGroup ?: return
		rootGroup.descendants.filterIsInstance<NavigationBarView>().forEach { navBar ->
			navBar.descendants.filterIsInstance<TextView>().forEach { tv ->
				val style = tv.typeface?.style ?: android.graphics.Typeface.NORMAL
				tv.typeface = android.graphics.Typeface.create(typeface, style)
			}
		}
	}
}
