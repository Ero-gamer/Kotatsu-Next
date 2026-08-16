package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.xmlpull.v1.XmlPullParser

/**
 * A [LayoutInflater] subclass that applies a custom [Typeface] to every [TextView]
 * (and subclasses — Button, EditText, CheckBox, etc.) after each layout is inflated.
 *
 * ## Why inflate() instead of onCreateView()
 *
 * [onCreateView] is only called as a fallback when no [LayoutInflater.Factory2] is set.
 * AppCompat installs its own `WrapperFactory2` which intercepts all view creation — so
 * [onCreateView] is never reached.  Overriding [inflate] is safe because it fires AFTER
 * AppCompat's factory has already created and returned the complete view tree.  We simply
 * walk that tree and apply the typeface.
 *
 * ## Coverage
 *
 * Returned by [BaseActivity.getSystemService] for [Context.LAYOUT_INFLATER_SERVICE], so
 * every [LayoutInflater.from] call using the activity context gets this inflater:
 * - Activity layouts (setContentView)
 * - Fragment layouts (Fragment.onCreateView inflater comes from the activity context)
 * - DialogFragment / BottomSheetDialogFragment (same)
 * - RecyclerView adapter items (LayoutInflater.from(parent.context))
 */
class TypefaceInflater(
    original: LayoutInflater,
    context: Context,
    private val typeface: Typeface,
) : LayoutInflater(original, context) {

    override fun cloneInContext(newContext: Context): LayoutInflater =
        TypefaceInflater(this, newContext, typeface)

    // onCreateView is NOT overridden — it's never reached when AppCompat's factory is set.

    /**
     * Core intercept point: called after the full view tree for a layout resource is built.
     * Walk the returned root and apply [typeface] to every [TextView].
     */
    override fun inflate(parser: XmlPullParser, root: ViewGroup?, attachToRoot: Boolean): View? {
        val result = super.inflate(parser, root, attachToRoot) ?: return null
        // `result` is the inflated view (or `root` if attachToRoot=true and root != null).
        // Either way, walk from result downward; if attachToRoot the newly added children
        // are the last children of root, but walking all of root is safe and still cheap.
        applyTypefaceToTree(result)
        return result
    }

    private fun applyTypefaceToTree(view: View) {
        if (view is TextView) {
            val style = view.typeface?.style ?: Typeface.NORMAL
            view.typeface = Typeface.create(typeface, style)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyTypefaceToTree(view.getChildAt(i))
            }
        }
    }
}
