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
 * ## Installation
 *
 * **Call [install] AFTER `super.onCreate()`** so AppCompat's Factory2 is already set and
 * can be captured as [delegate]. The resulting chain is:
 *
 *   FontInflaterFactory2 → AppCompat Factory2 → creates view → font applied
 *
 * ## Coverage
 *
 * One install on the Activity's inflater covers:
 *  - The activity's own layout (setContentView)
 *  - All Fragment layouts (Fragment clones the activity inflater, inheriting the factory chain)
 *  - RecyclerView adapter items (LayoutInflater.from(activity/fragment context))
 *
 * Note: NavigationBarView creates label TextViews programmatically, not via inflation.
 * Those are handled separately by BaseActivity.setContentView().
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
            // Preserve bold/italic style; only swap the font family.
            val existingStyle = view.typeface?.style ?: Typeface.NORMAL
            view.typeface = Typeface.create(typeface, existingStyle)
        }
    }

    companion object {

        /**
         * Installs [FontInflaterFactory2] on [inflater], wrapping whatever factory is
         * already installed (AppCompat's factory when called after super.onCreate()).
         *
         * Safe to call even when a factory is already set — uses [LayoutInflaterCompat.setFactory2]
         * which invokes `forceSetFactory2` via reflection (androidx.core 1.9+) to bypass
         * the "already set" guard. No-op when [typeface] is null.
         *
         * Guards against double-installation: if a [FontInflaterFactory2] is already the
         * top-level factory, this call is a no-op to prevent factory chain explosion.
         */
        fun install(inflater: LayoutInflater, typeface: Typeface?) {
            if (typeface == null) return
            // Guard: don't double-wrap if already installed.
            if (inflater.factory2 is FontInflaterFactory2) return
            val existing = inflater.factory2
            val factory = FontInflaterFactory2(existing, typeface)
            LayoutInflaterCompat.setFactory2(inflater, factory)
        }

        /**
         * Convenience overload: resolves the typeface via [FontTypefaceHolder] then calls [install].
         * No-op when the resolved typeface is null (APP_DEFAULT).
         */
        fun installFromSettings(inflater: LayoutInflater, context: Context, fontKey: String) {
            val typeface = FontTypefaceHolder.resolve(context, fontKey)
            install(inflater, typeface)
        }
    }
}
