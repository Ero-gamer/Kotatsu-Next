package org.koitharu.kotatsu.settings.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.setPadding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import org.koitharu.kotatsu.R

class DonateDialogFragment : BottomSheetDialogFragment() {

	data class CryptoEntry(
		val name: String,
		val address: String,
		val colorHex: Int,
	)

	private val entries = listOf(
		CryptoEntry("Bitcoin", "19Zks5VmhPtPPiZNHQUv71vfLyEeCtec2T", 0xFFEAB300.toInt()),
		CryptoEntry("USDT (TRC20)", "TAxmtUbhiWEgY9bDQbgaaTPcmoS8EfJkKR", 0xFF168363.toInt()),
		CryptoEntry("Ethereum", "0x7f92c4a838286a48f007419c9707f9096dc6675d", 0xFF3C3C3D.toInt()),
		CryptoEntry("Solana", "5KCKZtKtYd9J5UB4VW3HJny4cBWKAJktmGUkfxsdsh9S", 0xFF9945FF.toInt()),
		CryptoEntry("TON", "UQAN5OUU7YjxFPEPP0-LC62lWL_CF_LqgVhz9qjbvzLhb74F", 0xFF0098EA.toInt()),
		CryptoEntry("Binance ID (UID)", "583622748", 0xFFF0B90B.toInt()),
	)

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val ctx = requireContext()
		val dp = ctx.resources.displayMetrics.density

		val scroll = android.widget.ScrollView(ctx)
		val linear = android.widget.LinearLayout(ctx).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			val pad = (16 * dp).toInt()
			setPadding(pad, pad, pad, pad)
		}

		// Title
		val title = TextView(ctx).apply {
			text = getString(R.string.donate_dialog_title)
			textSize = 20f
			setTypeface(null, android.graphics.Typeface.BOLD)
			val pb = (4 * dp).toInt()
			val mb = (16 * dp).toInt()
			setPadding(0, 0, 0, pb)
			layoutParams = ViewGroup.MarginLayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			).apply { bottomMargin = mb }
		}
		linear.addView(title)

		for (entry in entries) {
			val card = buildCryptoCard(ctx, entry, dp)
			val lp = android.widget.LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			).apply { bottomMargin = (12 * dp).toInt() }
			card.layoutParams = lp
			linear.addView(card)
		}

		scroll.addView(linear)
		return scroll
	}

	private fun buildCryptoCard(ctx: Context, entry: CryptoEntry, dp: Float): View {
		val card = androidx.cardview.widget.CardView(ctx).apply {
			radius = (12 * dp)
			cardElevation = (2 * dp)
			setCardBackgroundColor(entry.colorHex)
		}

		val inner = android.widget.LinearLayout(ctx).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			val pad = (14 * dp).toInt()
			setPadding(pad, pad, pad, pad)
		}

		val nameView = TextView(ctx).apply {
			text = entry.name
			textSize = 14f
			setTypeface(null, android.graphics.Typeface.BOLD)
			setTextColor(0xFFFFFFFF.toInt())
			val mb = (4 * dp).toInt()
			layoutParams = ViewGroup.MarginLayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			).apply { bottomMargin = mb }
		}

		val addrView = TextView(ctx).apply {
			text = entry.address
			textSize = 12f
			setTextColor(0xCCFFFFFF.toInt())
			isSingleLine = false
			// Do NOT set textIsSelectable — it intercepts touch events, requiring 3 taps
			// before the card's onClickListener fires. Address is copied via card click.
		}

		inner.addView(nameView)
		inner.addView(addrView)

		// Tap to copy
		card.isClickable = true
		card.isFocusable = true
		card.setOnClickListener { v ->
			val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
			clipboard.setPrimaryClip(ClipData.newPlainText(entry.name, entry.address))
			view?.let {
				Snackbar.make(it, R.string.donate_copy_address, Snackbar.LENGTH_SHORT).show()
			}
		}

		card.addView(inner)
		return card
	}

	companion object {
		const val TAG = "DonateDialog"

		fun newInstance() = DonateDialogFragment()
	}
}
