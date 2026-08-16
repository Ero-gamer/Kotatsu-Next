package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView

/**
 * A [LayoutInflater] subclass that applies a custom [Typeface] to every [TextView]
 * (and subclasses — Button, EditText, CheckBox, etc.) at the moment it is created.
 *
 * ## Why this approach instead of Factory2
 *
 * Setting a [LayoutInflater.Factory2] via [androidx.core.view.LayoutInflaterCompat.setFactory2]
 * after AppCompat's own factory is installed requires reflection to bypass the "factory already
 * set" guard.  On some OEM ROMs (especially API 28 devices) this reflection silently fails or
 * throws, causing a crash in every subsequent `Activity.onCreate()` — a permanent boot-loop.
 *
 * Overriding [cloneInContext] instead is safe, reflection-free, and is the same technique used
 * by the Calligraphy and ViewPump font libraries.  The clone shares no mutable state with the
 * original inflater and carries its own [onCreateView] override cleanly.
 *
 * ## Coverage
 *
 * Because [Activity.getLayoutInflater] returns an instance that Android's [FragmentManager]
 * clones (via [cloneInContext]) for every hosted Fragment, this single override covers:
 *
 * - The activity's own layout (`setContentView`)
 * - Every [androidx.fragment.app.Fragment] hosted by the activity
 * - [androidx.fragment.app.DialogFragment] and bottom-sheet fragments (they use
 *   `Fragment.layoutInflater` which comes from the host activity's inflater clone)
 * - RecyclerView adapter items inflated with `LayoutInflater.from(activity/fragment context)`
 *
 * The only views NOT covered are those whose `TextView`s are created programmatically by
 * the widget itself (e.g. [com.google.android.material.navigation.NavigationBarView] menu
 * items). Those are handled separately in [BaseActivity.setContentView].
 */
class TypefaceInflater(
    original: LayoutInflater,
    context: Context,
    private val typeface: Typeface,
) : LayoutInflater(original, context) {

    override fun cloneInContext(newContext: Context): LayoutInflater =
        TypefaceInflater(this, newContext, typeface)

    override fun onCreateView(name: String, attrs: AttributeSet): View? =
        super.onCreateView(name, attrs)?.also { applyIfTextView(it) }

    override fun onCreateView(parent: View?, name: String, attrs: AttributeSet): View? =
        super.onCreateView(parent, name, attrs)?.also { applyIfTextView(it) }

    private fun applyIfTextView(view: View) {
        if (view is TextView) {
            // Preserve any bold/italic style already set; only swap the font family.
            val style = view.typeface?.style ?: Typeface.NORMAL
            view.typeface = Typeface.create(typeface, style)
        }
    }
}
