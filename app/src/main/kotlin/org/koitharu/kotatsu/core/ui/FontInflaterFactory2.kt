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
 * A [LayoutInflater.Factory2] wrapper that applies a custom [Typeface] to every inflated
 * [TextView] (and subclasses — Button, EditText, CheckBox, RadioButton, etc.) at creation time.
 *
 * ## Correct installation order
 *
 * **MUST be installed AFTER `super.onCreate()`**, not before.
 *
 * `AppCompatActivity.super.onCreate()` calls `AppCompatDelegate.installViewFactory()` which
 * installs AppCompat's own `WrapperFactory2` on the `LayoutInflater`.  If we install our
 * factory *before* that, our captured `delegate` is `null`, and AppCompat wraps us on top —
 * when AppCompat calls our `onCreateView`, we forward to `null` → **NPE / crash**.
 *
 * Installing *after* `super.onCreate()`:
 * 1. `inflater.factory2` is AppCompat's `WrapperFactory2`.
 * 2. We create `FontInflaterFactory2(delegate = appCompatFactory)`.
 * 3. `LayoutInflaterCompat.setFactory2` uses reflection (`forceSetFactory2`) to bypass
 *    the "factory already set" guard and replaces the factory.
 * 4. Final chain: **Font → AppCompat → creates view → Font applies typeface**.
 *
 * This single installation covers:
 * - The activity's own layout (`setContentView`)
 * - All Fragment layouts (Fragment clones the activity's inflater, sharing the same factory)
 * - RecyclerView adapter items inflated via `LayoutInflater.from(activity/fragment context)`
 *
 * Dialogs and bottom sheets have separate windows with independent inflaters — those are
 * handled by overriding `onGetLayoutInflater()` in [BaseAdaptiveSheet] and
 * [AlertDialogFragment], using the same after-super install approach.
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
            // Preserve bold/italic style variant; only swap the font family.
            val existingStyle = view.typeface?.style ?: Typeface.NORMAL
            view.typeface = Typeface.create(typeface, existingStyle)
        }
    }

    companion object {

        /**
         * Installs this factory on [inflater].
         *
         * **Call AFTER `super.onCreate()`** so that AppCompat's factory is already set and can
         * be captured as the delegate.  No-op when [typeface] is `null`.
         *
         * Uses [LayoutInflaterCompat.setFactory2] which internally calls `forceSetFactory2`
         * via reflection (available in androidx.core 1.9+), bypassing the "already set" guard.
         */
        fun install(inflater: LayoutInflater, typeface: Typeface?) {
            if (typeface == null) return
            // Capture whatever factory is currently installed (AppCompat's WrapperFactory2
            // when called after super.onCreate, or null in a dialog/sheet context).
            val existing = inflater.factory2
            val factory = FontInflaterFactory2(existing, typeface)
            // LayoutInflaterCompat.setFactory2 uses forceSetFactory2 (reflection) in
            // androidx.core 1.9+, so it succeeds even when a factory is already installed.
            LayoutInflaterCompat.setFactory2(inflater, factory)
        }

        /**
         * Convenience overload that resolves the typeface from [FontTypefaceHolder] before
         * installing.  No-op when the resolved typeface is `null` (APP_DEFAULT).
         */
        fun installFromSettings(inflater: LayoutInflater, context: Context, fontKey: String) {
            val typeface = FontTypefaceHolder.resolve(context, fontKey)
            install(inflater, typeface)
        }
    }
}
