package org.koitharu.kotatsu.reader.ui.colorfilter

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R

/**
 * The (i) dialog of a color filter: explanation, best use, caveats and the in-category ratings.
 * Ratings are drawn as ten stars; see [FilterInfo] for why they are only comparable inside one
 * category (and are product judgment, not benchmarks) — the dialog says so itself.
 */
object FilterInfoDialog {
    fun show(
        context: Context,
        id: FilterId,
    ) {
        val info = FilterInfoRegistry.byId(id)
        MaterialAlertDialogBuilder(context)
            .setTitle(info.nameRes)
            .setMessage(buildMessage(context, info))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildMessage(
        context: Context,
        info: FilterInfo,
    ): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(context.getString(info.descriptionRes)).append("\n\n")
        section(sb, context.getString(R.string.filter_info_title_best_for), context.getString(info.bestForRes))
        section(sb, context.getString(R.string.filter_info_title_caveats), context.getString(info.caveatsRes))
        heading(sb, context.getString(R.string.filter_info_title_ratings))
        sb
            .append(
                context.getString(R.string.filter_info_overall, context.getString(info.category.titleRes), info.rating),
            ).append('\n')
        sb.append(stars(info.rating)).append("\n\n")
        for ((labelRes, value) in info.stats) {
            sb
                .append(context.getString(labelRes))
                .append(": ")
                .append(stars(value))
                .append(' ')
                .append(value.toString())
                .append("/10\n")
        }
        val start = sb.length
        sb.append('\n').append(context.getString(R.string.filter_info_disclaimer))
        sb.setSpan(RelativeSizeSpan(0.85f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun section(
        sb: SpannableStringBuilder,
        title: String,
        body: String,
    ) {
        heading(sb, title)
        sb.append(body).append("\n\n")
    }

    private fun heading(
        sb: SpannableStringBuilder,
        title: String,
    ) {
        val start = sb.length
        sb.append(title).append('\n')
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun stars(value: Int): String {
        val filled = value.coerceIn(0, STAR_COUNT)
        return "★".repeat(filled) + "☆".repeat(STAR_COUNT - filled)
    }

    private const val STAR_COUNT = 10
}
