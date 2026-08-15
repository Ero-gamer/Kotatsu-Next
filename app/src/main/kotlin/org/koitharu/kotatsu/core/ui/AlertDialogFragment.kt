package org.koitharu.kotatsu.core.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.viewbinding.ViewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.EntryPointAccessors

abstract class AlertDialogFragment<B : ViewBinding> : DialogFragment() {

	var viewBinding: B? = null
		private set

	/**
	 * Apply the active font to every TextView inflated inside this dialog.
	 *
	 * Alert dialogs (and their Material counterparts) run in a separate [Dialog] window
	 * with their own [LayoutInflater] that is not shared with the host activity.  We
	 * override [onGetLayoutInflater] — called by the Fragment framework before
	 * [onCreateDialog] — so that our [FontInflaterFactory2] is already installed when
	 * the dialog's view hierarchy is built.
	 */
	override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
		val inflater = super.onGetLayoutInflater(savedInstanceState)
		val context = context ?: return inflater
		val entryPoint = EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(context.applicationContext)
		val fontKey = entryPoint.settings.appFontKey
		FontInflaterFactory2.installFromSettings(inflater, context.applicationContext, fontKey)
		return inflater
	}

	final override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val binding = onCreateViewBinding(layoutInflater, null)
		viewBinding = binding
		return MaterialAlertDialogBuilder(requireContext(), theme)
			.setView(binding.root)
			.run(::onBuildDialog)
			.create()
			.also(::onDialogCreated)
	}

	final override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	) = viewBinding?.root

	final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		onViewBindingCreated(requireViewBinding(), savedInstanceState)
	}

	@CallSuper
	override fun onDestroyView() {
		viewBinding = null
		super.onDestroyView()
	}

	open fun onBuildDialog(builder: MaterialAlertDialogBuilder): MaterialAlertDialogBuilder = builder

	open fun onDialogCreated(dialog: AlertDialog) = Unit

	fun requireViewBinding(): B = checkNotNull(viewBinding) {
		"Fragment $this did not return a ViewBinding from onCreateView() or this was called before onCreateView()."
	}

	protected abstract fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): B

	protected open fun onViewBindingCreated(binding: B, savedInstanceState: Bundle?) = Unit
}
