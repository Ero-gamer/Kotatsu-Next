package org.koitharu.kotatsu.reader.ui.config

import android.view.View
import androidx.appcompat.widget.PopupMenu
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.reader.ui.ReaderViewModel

/** Border crop of the current manga for the current reading style: global setting, on or off. */
object CropMenu {

    private const val GROUP = 0
    private const val ID_GLOBAL = 0
    private const val ID_ON = 1
    private const val ID_OFF = 2

    fun show(anchor: View, viewModel: ReaderViewModel) {
        val current = when (viewModel.getCropOverride()) {
            null -> ID_GLOBAL
            true -> ID_ON
            false -> ID_OFF
        }
        val menu = PopupMenu(anchor.context, anchor)
        menu.menu.add(GROUP, ID_GLOBAL, ID_GLOBAL, R.string.crop_use_global)
        menu.menu.add(GROUP, ID_ON, ID_ON, R.string.enabled)
        menu.menu.add(GROUP, ID_OFF, ID_OFF, R.string.disabled)
        menu.menu.setGroupCheckable(GROUP, true, true)
        menu.menu.findItem(current).isChecked = true
        menu.setOnMenuItemClickListener {
            viewModel.setCropOverride(
                when (it.itemId) {
                    ID_ON -> true
                    ID_OFF -> false
                    else -> null
                },
            )
            true
        }
        menu.show()
    }
}
