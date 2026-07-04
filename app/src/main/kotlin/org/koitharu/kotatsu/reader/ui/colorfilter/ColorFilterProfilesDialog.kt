package org.koitharu.kotatsu.reader.ui.colorfilter

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.reader.domain.ColorFilterProfile

/**
 * Generic "manage saved profiles" list dialog, reused for both a manga's own saved-profiles
 * list and the global list (Reader Settings). The caller supplies the data + the actions that
 * make sense for its scope — this file only knows how to render a list and confirm actions.
 */
object ColorFilterProfilesDialog {

    class Actions(
        /** Called when the user taps a row to apply it. */
        val onApply: (ColorFilterProfile) -> Unit,
        val onRename: (ColorFilterProfile, String) -> Unit,
        val onDelete: (ColorFilterProfile) -> Unit,
        /** Label + handler for the scope-specific "copy elsewhere" action. null hides it. */
        val copyAction: Pair<String, (ColorFilterProfile) -> Unit>? = null,
        /** Label + handler for "save the current filter as a new profile here". null hides it. */
        val saveNewAction: Pair<String, (name: String, onResult: (Boolean) -> Unit) -> Unit>? = null,
        /** Label + handler for an extra top-level action, e.g. "Import from global". null hides it. */
        val extraAction: Pair<String, () -> Unit>? = null,
        /** Whether applying should ask for confirmation first (global "apply to all" case). */
        val confirmApply: Boolean = false,
    )

    fun show(
        context: Context,
        title: String,
        profiles: List<ColorFilterProfile>,
        actions: Actions,
    ) {
        val names = profiles.map { it.name }.toMutableList()
        val listView = ListView(context)
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, names)
        listView.adapter = adapter

        // Empty-state: show a hint when there are no profiles yet.
        listView.emptyView = TextView(context).apply {
            text = context.getString(R.string.no_saved_profiles)
            setPadding(
                (24 * context.resources.displayMetrics.density).toInt(), 0,
                (24 * context.resources.displayMetrics.density).toInt(), 0,
            )
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(listView)
            .setNegativeButton(android.R.string.cancel, null)
            .also { builder ->
                actions.saveNewAction?.let { (label, _) -> builder.setNeutralButton(label, null) }
                actions.extraAction?.let { (label, _) -> builder.setPositiveButton(label, null) }
            }
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val profile = profiles[position]
            if (actions.confirmApply) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.apply)
                    .setMessage(context.getString(R.string.apply_profile_confirm, profile.name))
                    .setPositiveButton(R.string.apply) { _, _ -> actions.onApply(profile); dialog.dismiss() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                actions.onApply(profile)
                dialog.dismiss()
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            showActionMenu(context, profiles[position], actions, dialog)
            true
        }

        dialog.setOnShowListener {
            actions.saveNewAction?.let { (_, save) ->
                dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    promptName(context, null) { name ->
                        save(name) { ok ->
                            if (ok) dialog.dismiss() else Toast.makeText(context, R.string.profiles_limit_reached, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            actions.extraAction?.let { (_, run) ->
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    run()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showActionMenu(context: Context, profile: ColorFilterProfile, actions: Actions, parent: android.app.AlertDialog) {
        val items = buildList {
            add(context.getString(R.string.rename))
            add(context.getString(R.string.delete))
            actions.copyAction?.let { (label, _) -> add(label) }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(profile.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    context.getString(R.string.rename) -> promptName(context, profile.name) { newName ->
                        actions.onRename(profile, newName)
                        parent.dismiss()
                    }
                    context.getString(R.string.delete) -> {
                        actions.onDelete(profile)
                        parent.dismiss()
                    }
                    else -> {
                        actions.copyAction?.second?.invoke(profile)
                        parent.dismiss()
                    }
                }
            }
            .show()
    }

    private fun promptName(context: Context, prefill: String?, onName: (String) -> Unit) {
        val editText = EditText(context).apply { prefill?.let { setText(it) } }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.set_value)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
