package org.koitharu.kotatsu.reader.ui.colorfilter

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.reader.domain.ColorFilterProfile

object ColorFilterProfilesDialog {

    class Actions(
        val onApply: (ColorFilterProfile) -> Unit,
        val onRename: (ColorFilterProfile, String) -> Unit,
        val onDelete: (ColorFilterProfile) -> Unit,
        val copyAction: Pair<String, (ColorFilterProfile) -> Unit>? = null,
        val saveNewAction: Pair<String, (name: String, onResult: (Boolean) -> Unit) -> Unit>? = null,
        val extraAction: Pair<String, () -> Unit>? = null,
        val confirmApply: Boolean = false,
    )

    fun show(context: Context, title: String, profiles: List<ColorFilterProfile>, actions: Actions) {
        if (profiles.isEmpty() && actions.saveNewAction == null) {
            Toast.makeText(context, R.string.no_saved_profiles, Toast.LENGTH_SHORT).show()
            return
        }
        val listView = ListView(context)
        listView.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, profiles.map { it.name })

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(listView)
            .setNegativeButton(android.R.string.cancel, null)
            .also { b ->
                actions.saveNewAction?.let { b.setNeutralButton(it.first, null) }
                actions.extraAction?.let   { b.setPositiveButton(it.first, null) }
            }
            .create()  // returns androidx.appcompat.app.AlertDialog

        listView.setOnItemClickListener { _, _, pos, _ ->
            val p = profiles[pos]
            if (actions.confirmApply) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.apply)
                    .setMessage(context.getString(R.string.apply_profile_confirm, p.name))
                    .setPositiveButton(R.string.apply) { _, _ -> actions.onApply(p); dialog.dismiss() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } else { actions.onApply(p); dialog.dismiss() }
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            showActionMenu(context, profiles[pos], actions, dialog); true
        }
        dialog.setOnShowListener {
            actions.saveNewAction?.let { (_, save) ->
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    promptName(context, null) { name ->
                        save(name) { ok ->
                            if (ok) dialog.dismiss()
                            else Toast.makeText(context, R.string.profiles_limit_reached, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            actions.extraAction?.let { (_, run) ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { run(); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun showActionMenu(context: Context, profile: ColorFilterProfile, actions: Actions, parent: AlertDialog) {
        val items = buildList {
            add(context.getString(R.string.rename))
            add(context.getString(R.string.delete))
            actions.copyAction?.let { add(it.first) }
        }
        MaterialAlertDialogBuilder(context).setTitle(profile.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> promptName(context, profile.name) { n -> actions.onRename(profile, n); parent.dismiss() }
                    1 -> { actions.onDelete(profile); parent.dismiss() }
                    else -> { actions.copyAction?.second?.invoke(profile); parent.dismiss() }
                }
            }.show()
    }

    private fun promptName(context: Context, prefill: String?, onName: (String) -> Unit) {
        val edit = EditText(context).apply { prefill?.let { setText(it) } }
        MaterialAlertDialogBuilder(context).setTitle(R.string.set_value).setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                edit.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onName)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
