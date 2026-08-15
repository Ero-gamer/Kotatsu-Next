package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.LayoutInflaterCompat
import org.koitharu.kotatsu.core.prefs.FontTypefaceHolder

/**
 * A [LayoutInflater.Factory2] wrapper that intercepts every inflated [TextView] (and
 * subclasses — Button, EditText, etc.) and applies a custom [Typeface] at creation time.
 *
 * **Why at inflation time, not after layout?**
 * - The old approach called `decorView.post { walk tree }` in `BaseActivity.onCreate`.
 *   This ran *after* the initial layout pass, missed views inflated later (fragment
 *   transactions, dialogs, bottom sheets), and never reached separate Dialog windows.
 * - By hooking the inflater factory we apply the typeface to every single view the
 *   moment it is created, regardless of which Window, Fragment, or Dialog it belongs to.
 *
 * **Chaining with AppCompat:**
 * `AppCompatActivity` installs its own `Factory2` (`AppCompatViewInflater`) via
 * `AppCompatDelegate.installViewFactory()`, which is called from `super.onCreate()`.
 * We install *our* factory **before** `super.onCreate()` using
 * [LayoutInflaterCompat.setFactory2].  AppCompat detects that a factory is already
 * present and wraps it internally (since AppCompat 1.1+), so both factories run.
 * Our factory receives the already-AppCompat-constructed view and just tweaks its
 * typeface — we never need to construct the view ourselves.
 *
 * Usage — call [install] once at the top of `onCreate` / `onCreateDialog`:
 * ```kotlin
 * FontInflaterFactory2.install(layoutInflater, typeface)
 * ```
 */
class FontInflaterFactory2(
    private val delegate: LayoutInflater.Factory2?,
    private val typeface: Typeface,
) : LayoutInflater.Factory2 {

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet,
    ): View? {
        val view = delegate?.onCreateView(parent, name, context, attrs)
        applyTypefaceIfNeeded(view)
        return view
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        val view = delegate?.onCreateView(name, context, attrs)
        applyTypefaceIfNeeded(view)
        return view
    }

    private fun applyTypefaceIfNeeded(view: View?) {
        if (view is TextView) {
            val existingStyle = view.typeface?.style ?: Typeface.NORMAL
            // Preserve bold/italic style, just swap the font family.
            view.typeface = Typeface.create(typeface, existingStyle)
        }
    }

    companion object {
        /**
         * Installs this factory on [inflater] if [typeface] is non-null.
         * Safe to call multiple times — no-op when typeface is null.
         *
         * Must be called **before** `super.onCreate()` in an Activity so AppCompat
         * can chain properly, OR after `super.onCreateDialog()` in a DialogFragment.
         */
        fun install(inflater: LayoutInflater, typeface: Typeface?) {
            if (typeface == null) return
            // Grab whatever factory AppCompat (or anyone else) has already installed.
            val existing = inflater.factory2
            val factory = FontInflaterFactory2(existing, typeface)
            // setFactory2 throws if a factory is already set — use the compat wrapper
            // which handles the "already set" case gracefully (wraps via reflection on older APIs).
            LayoutInflaterCompat.setFactory2(inflater, factory)
        }

        /**
         * Variant that reads the typeface from [FontTypefaceHolder].
         * Resolves the current user preference and installs if non-null.
         */
        fun installFromSettings(inflater: LayoutInflater, context: Context, fontKey: String) {
            val typeface = FontTypefaceHolder.resolve(context, fontKey)
            install(inflater, typeface)
        }
    }
}
