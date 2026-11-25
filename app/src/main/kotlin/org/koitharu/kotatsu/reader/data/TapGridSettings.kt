package org.koitharu.kotatsu.reader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.mmkv.MmkvPreferences
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.observeChanges
import org.koitharu.kotatsu.core.util.ext.putAll
import org.koitharu.kotatsu.core.util.ext.putEnumValue
import org.koitharu.kotatsu.reader.domain.TapGridArea
import org.koitharu.kotatsu.reader.ui.tapgrid.TapAction
import javax.inject.Inject

@Reusable
class TapGridSettings
@Inject
constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = MmkvPreferences.open(context, "tap_grid", PREFS_NAME)

    init {
        if (!prefs.getBoolean(KEY_INIT, false)) {
            initPrefs(withDefaultValues = true)
        }
    }

    fun getTapAction(
        area: TapGridArea,
        isLongTap: Boolean,
    ): TapAction? {
        val key = getPrefKey(area, isLongTap)
        return prefs.getEnumValue(key, TapAction::class.java)
    }

    fun setTapAction(
        area: TapGridArea,
        isLongTap: Boolean,
        action: TapAction?,
    ) {
        val key = getPrefKey(area, isLongTap)
        prefs.edit { putEnumValue(key, action) }
    }

    fun reset() {
        initPrefs(withDefaultValues = true)
    }

    /** Replaces every tap action with [preset]; long-tap on the centre keeps opening the menu. */
    fun applyPreset(preset: Preset) {
        prefs.edit {
            clear()
            for (area in TapGridArea.entries) {
                putEnumValue(getPrefKey(area, false), preset.actionFor(area))
            }
            putEnumValue(getPrefKey(TapGridArea.CENTER, true), TapAction.SHOW_MENU)
            putBoolean(KEY_INIT, true)
        }
    }

    enum class Preset(@StringRes val nameResId: Int) {

        /** Left and top turn back, right and bottom go forward. */
        L_SHAPED(R.string.tap_preset_l_shaped),

        /** Left column goes back, everything else forward. */
        KINDLE(R.string.tap_preset_kindle),

        /** Left column back, right column forward, middle column toggles the UI. */
        LEFT_RIGHT(R.string.tap_preset_left_right),

        /** Top row back, bottom row forward, middle row toggles the UI. */
        TOP_BOTTOM(R.string.tap_preset_top_bottom),
        ;

        fun actionFor(area: TapGridArea): TapAction {
            val col = when (area) {
                TapGridArea.TOP_LEFT, TapGridArea.CENTER_LEFT, TapGridArea.BOTTOM_LEFT -> 0
                TapGridArea.TOP_CENTER, TapGridArea.CENTER, TapGridArea.BOTTOM_CENTER -> 1
                else -> 2
            }
            val row = when (area) {
                TapGridArea.TOP_LEFT, TapGridArea.TOP_CENTER, TapGridArea.TOP_RIGHT -> 0
                TapGridArea.CENTER_LEFT, TapGridArea.CENTER, TapGridArea.CENTER_RIGHT -> 1
                else -> 2
            }
            return when (this) {
                L_SHAPED -> when {
                    area == TapGridArea.CENTER -> TapAction.TOGGLE_UI
                    col == 0 || (row == 0 && col == 1) -> TapAction.PAGE_PREV
                    else -> TapAction.PAGE_NEXT
                }
                KINDLE -> when {
                    area == TapGridArea.CENTER -> TapAction.TOGGLE_UI
                    col == 0 -> TapAction.PAGE_PREV
                    else -> TapAction.PAGE_NEXT
                }
                LEFT_RIGHT -> when (col) {
                    0 -> TapAction.PAGE_PREV
                    1 -> TapAction.TOGGLE_UI
                    else -> TapAction.PAGE_NEXT
                }
                TOP_BOTTOM -> when (row) {
                    0 -> TapAction.PAGE_PREV
                    1 -> TapAction.TOGGLE_UI
                    else -> TapAction.PAGE_NEXT
                }
            }
        }
    }

    fun disableAll() {
        initPrefs(withDefaultValues = false)
    }

    fun observeChanges() = prefs.observeChanges().flowOn(Dispatchers.IO)

    fun getAllValues(): Map<String, *> = prefs.all

    fun upsertAll(m: Map<String, *>) = prefs.edit {
        clear()
        putAll(m)
    }

    private fun initPrefs(withDefaultValues: Boolean) {
        prefs.edit {
            clear()
            if (withDefaultValues) {
                initDefaultActions(this)
            }
            putBoolean(KEY_INIT, true)
        }
    }

    private fun getPrefKey(
        area: TapGridArea,
        isLongTap: Boolean,
    ): String = if (isLongTap) {
        area.name + SUFFIX_LONG
    } else {
        area.name
    }

    private fun initDefaultActions(editor: SharedPreferences.Editor) {
        editor.putEnumValue(getPrefKey(TapGridArea.TOP_LEFT, false), TapAction.PAGE_PREV)
        editor.putEnumValue(getPrefKey(TapGridArea.TOP_CENTER, false), TapAction.PAGE_PREV)
        editor.putEnumValue(getPrefKey(TapGridArea.CENTER_LEFT, false), TapAction.PAGE_PREV)
        editor.putEnumValue(getPrefKey(TapGridArea.BOTTOM_LEFT, false), TapAction.PAGE_PREV)

        editor.putEnumValue(getPrefKey(TapGridArea.CENTER, false), TapAction.TOGGLE_UI)
        editor.putEnumValue(getPrefKey(TapGridArea.CENTER, true), TapAction.SHOW_MENU)

        editor.putEnumValue(getPrefKey(TapGridArea.TOP_RIGHT, false), TapAction.PAGE_NEXT)
        editor.putEnumValue(getPrefKey(TapGridArea.CENTER_RIGHT, false), TapAction.PAGE_NEXT)
        editor.putEnumValue(getPrefKey(TapGridArea.BOTTOM_CENTER, false), TapAction.PAGE_NEXT)
        editor.putEnumValue(getPrefKey(TapGridArea.BOTTOM_RIGHT, false), TapAction.PAGE_NEXT)
    }

    private companion object {
        private const val PREFS_NAME = "tap_grid"
        private const val KEY_INIT = "_init"
        private const val SUFFIX_LONG = "_long"
    }
}
