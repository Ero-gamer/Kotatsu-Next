package org.koitharu.kotatsu.settings.utils

import android.content.Context
import android.graphics.Typeface
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppFont
import org.koitharu.kotatsu.core.prefs.SystemFontEntry
import org.koitharu.kotatsu.core.prefs.SystemFontScanner

/**
 * A custom Preference that renders a horizontal scrolling list of fonts,
 * each font name rendered in its own typeface so the user sees a live preview.
 *
 * Fonts are separated into two sections:
 *   • "Built-in fonts"   — bundled in res/font/ (AppFont entries with fontRes != null, or system/app-default)
 *   • "Device fonts"     — scanned from the Android system font directory at runtime
 *
 * The selected font name is rendered in bold at normal text size; unselected items are at 90% alpha.
 */
class FontChooserPreference @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : Preference(context, attrs, defStyleAttr) {

	private var currentKey: String = AppFont.APP_DEFAULT.key
	private var adapter: FontAdapter? = null

	var value: String
		get() = currentKey
		set(v) = setValueInternal(v, true)

	init {
		layoutResource = R.layout.preference_font_chooser
		isIconSpaceReserved = false
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)
		val rv = holder.itemView.findViewById<RecyclerView>(R.id.recycler_fonts) ?: return
		if (rv.layoutManager == null) {
			rv.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
			rv.setHasFixedSize(false)
		}
		val adp = adapter ?: buildAdapter().also { adapter = it }
		if (rv.adapter !== adp) rv.adapter = adp
		adp.setSelected(currentKey)
	}

	override fun onSetInitialValue(defaultValue: Any?) {
		value = getPersistedString(AppFont.APP_DEFAULT.key)
	}

	override fun onSaveInstanceState(): Parcelable? {
		// When persistent, the system already saves the value — no need for instance state.
		if (isPersistent) return super.onSaveInstanceState()
		val superState = super.onSaveInstanceState() ?: return null
		return SavedState(superState, currentKey)
	}

	override fun onRestoreInstanceState(state: Parcelable?) {
		if (state == null || state !is SavedState) { super.onRestoreInstanceState(state); return }
		super.onRestoreInstanceState(state.superState)
		currentKey = state.key
	}

	private fun setValueInternal(key: String, notify: Boolean) {
		if (key == currentKey) return
		currentKey = key
		persistString(key)
		if (notify) notifyChanged()
	}

	private fun buildAdapter(): FontAdapter {
		val items = mutableListOf<FontItem>()

		// ── Section 1: Built-in fonts ──
		items += FontItem.Header(context.getString(R.string.font_section_builtin))
		for (appFont in AppFont.entries) {
			val typeface = when {
				appFont.fontRes != null -> {
					// Bundled font — load from resources for the preview card.
					runCatching { ResourcesCompat.getFont(context, appFont.fontRes) }.getOrNull()
				}
				appFont == AppFont.SYSTEM_FONT -> {
					// SYSTEM_FONT: preview should show the actual device OEM font.
					// Typeface.DEFAULT IS the system font — the old code passed null here
					// which fell back to DEFAULT in the adapter anyway, but let's be explicit.
					Typeface.DEFAULT
				}
				else -> {
					// APP_DEFAULT: null → adapter uses Typeface.DEFAULT for rendering.
					null
				}
			}
			items += FontItem.Entry(
				key      = appFont.key,
				label    = context.getString(appFont.titleResId),
				typeface = typeface,
			)
		}

		// ── Section 2: Device fonts (async-safe: scanned once, cached) ──
		val systemFonts = runCatching { SystemFontScanner.getSystemFonts() }.getOrElse { emptyList() }
		if (systemFonts.isNotEmpty()) {
			items += FontItem.Header(context.getString(R.string.font_section_device))
			for (entry in systemFonts) {
				items += FontItem.Entry(
					key      = "system:${entry.name}",
					label    = entry.name,
					typeface = entry.typeface,
				)
			}
		}

		return FontAdapter(items) { key -> setValueInternal(key, true) }
	}

	// ── Sealed model ──
	sealed class FontItem {
		data class Header(val title: String) : FontItem()
		data class Entry(val key: String, val label: String, val typeface: Typeface?) : FontItem()
	}

	// ── Adapter ──
	inner class FontAdapter(
		private val items: List<FontItem>,
		private val onSelect: (String) -> Unit,
	) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

		private var selectedKey: String = currentKey

		fun setSelected(key: String) {
			if (key == selectedKey) return
			val old = items.indexOfFirst { it is FontItem.Entry && it.key == selectedKey }
			val new = items.indexOfFirst { it is FontItem.Entry && it.key == key }
			selectedKey = key
			if (old >= 0) notifyItemChanged(old)
			if (new >= 0) notifyItemChanged(new)
		}

		override fun getItemViewType(position: Int) =
			if (items[position] is FontItem.Header) 0 else 1

		override fun getItemCount() = items.size

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
			val inflater = LayoutInflater.from(parent.context)
			return if (viewType == 0) {
				HeaderVH(inflater.inflate(R.layout.item_font_section_header, parent, false))
			} else {
				EntryVH(inflater.inflate(R.layout.item_font_entry, parent, false))
			}
		}

		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
			when (val item = items[position]) {
				is FontItem.Header -> (holder as HeaderVH).bind(item)
				is FontItem.Entry  -> (holder as EntryVH).bind(item, item.key == selectedKey) { onSelect(item.key) }
			}
		}
	}

	// ── ViewHolders ──
	inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
		private val tv = view as TextView
		fun bind(item: FontItem.Header) { tv.text = item.title }
	}

	inner class EntryVH(view: View) : RecyclerView.ViewHolder(view) {
		private val tvName    = view.findViewById<TextView>(R.id.tv_font_name)
		private val tvPreview = view.findViewById<TextView>(R.id.tv_font_preview_sample)
		private val check     = view.findViewById<View>(R.id.view_selected_indicator)
		fun bind(item: FontItem.Entry, selected: Boolean, onClick: () -> Unit) {
			val tf = item.typeface ?: Typeface.DEFAULT
			// Preview card: show "Ag" in the font
			tvPreview.typeface = tf
			// Label: render the font name in its own typeface + bold when selected
			tvName.text     = item.label
			tvName.typeface = if (selected) Typeface.create(tf, Typeface.BOLD) else tf
			tvName.alpha    = if (selected) 1f else 0.65f
			check.visibility = if (selected) View.VISIBLE else View.GONE
			itemView.isSelected = selected
			itemView.setOnClickListener { onClick() }
		}
	}

	// ── Saved state ──
	private class SavedState : BaseSavedState {
		val key: String

		constructor(superState: Parcelable, key: String) : super(superState) {
			this.key = key
		}

		private constructor(source: Parcel) : super(source) {
			key = source.readString() ?: AppFont.APP_DEFAULT.key
		}

		override fun writeToParcel(dest: Parcel, flags: Int) {
			super.writeToParcel(dest, flags)
			dest.writeString(key)
		}

		object CREATOR : Parcelable.Creator<SavedState> {
			override fun createFromParcel(source: Parcel) = SavedState(source)
			override fun newArray(size: Int) = arrayOfNulls<SavedState>(size)
		}
	}
}
