package org.koitharu.kotatsu.local.ui.optimize

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemOptimizeBinding
import org.koitharu.kotatsu.local.data.optimize.OptimizeTaskEntity

class OptimizeAdapter(
    private val listener: Listener,
) : ListAdapter<OptimizeTaskEntity, OptimizeAdapter.ViewHolder>(DiffCallback) {

    interface Listener {
        fun onPauseClick(task: OptimizeTaskEntity)
        fun onResumeClick(task: OptimizeTaskEntity)
        fun onCancelClick(task: OptimizeTaskEntity)
        fun onRetryClick(task: OptimizeTaskEntity)
        fun onRemoveClick(task: OptimizeTaskEntity)
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemOptimizeBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        listener,
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(
        private val binding: ItemOptimizeBinding,
        private val listener: Listener,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var task: OptimizeTaskEntity? = null

        init {
            binding.buttonPrimary.setOnClickListener {
                val t = task ?: return@setOnClickListener
                when (t.state) {
                    OptimizeTaskEntity.STATE_RUNNING -> listener.onPauseClick(t)
                    OptimizeTaskEntity.STATE_PAUSED -> listener.onResumeClick(t)
                    OptimizeTaskEntity.STATE_FAILED, OptimizeTaskEntity.STATE_CANCELLED -> listener.onRetryClick(t)
                }
            }
            binding.buttonSecondary.setOnClickListener {
                val t = task ?: return@setOnClickListener
                if (t.isActive) listener.onCancelClick(t) else listener.onRemoveClick(t)
            }
        }

        fun bind(item: OptimizeTaskEntity) {
            task = item
            val context = itemView.context
            binding.textViewTitle.text = item.title
            binding.textViewOptions.setText(
                when {
                    item.split && item.convert -> R.string.optimize_opt_both
                    item.split -> R.string.optimize_opt_split
                    else -> R.string.optimize_opt_convert
                },
            )
            binding.textViewStatus.text = when (item.state) {
                OptimizeTaskEntity.STATE_QUEUED -> context.getString(R.string.optimize_state_queued)
                OptimizeTaskEntity.STATE_RUNNING -> context.getString(R.string.optimize_progress, item.done, item.total)
                OptimizeTaskEntity.STATE_PAUSED -> context.getString(R.string.optimize_paused)
                OptimizeTaskEntity.STATE_DONE -> context.getString(R.string.optimize_state_done)
                OptimizeTaskEntity.STATE_FAILED -> context.getString(R.string.optimize_state_failed)
                else -> context.getString(R.string.optimize_state_cancelled)
            }
            val showStats = !item.isActive && item.total > 0
            binding.textViewStats.isVisible = showStats
            if (showStats) {
                binding.textViewStats.text = context.getString(
                    R.string.optimize_item_stats,
                    item.changed,
                    item.failed,
                    Formatter.formatShortFileSize(context, item.savedBytes.coerceAtLeast(0L)),
                )
            }
            val showProgress = item.state == OptimizeTaskEntity.STATE_RUNNING || item.state == OptimizeTaskEntity.STATE_PAUSED
            binding.progressBar.isVisible = showProgress
            if (showProgress) {
                val percent = if (item.total > 0) (item.done.toLong() * 100L / item.total).toInt().coerceIn(0, 100) else 0
                binding.progressBar.setProgressCompat(percent, false)
            }
            // Primary: Pause (running) / Resume (paused) / Retry (failed, cancelled). Secondary: Cancel (active) / Remove.
            val primary = when (item.state) {
                OptimizeTaskEntity.STATE_RUNNING -> R.string.pause
                OptimizeTaskEntity.STATE_PAUSED -> R.string.resume
                OptimizeTaskEntity.STATE_FAILED, OptimizeTaskEntity.STATE_CANCELLED -> R.string.retry
                else -> 0
            }
            binding.buttonPrimary.isVisible = primary != 0
            if (primary != 0) binding.buttonPrimary.setText(primary)
            binding.buttonSecondary.setText(if (item.isActive) android.R.string.cancel else R.string.remove)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<OptimizeTaskEntity>() {

        override fun areItemsTheSame(oldItem: OptimizeTaskEntity, newItem: OptimizeTaskEntity) = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: OptimizeTaskEntity, newItem: OptimizeTaskEntity) =
            oldItem.state == newItem.state &&
                oldItem.title == newItem.title &&
                oldItem.total == newItem.total &&
                oldItem.done == newItem.done &&
                oldItem.changed == newItem.changed &&
                oldItem.failed == newItem.failed &&
                oldItem.savedBytes == newItem.savedBytes &&
                oldItem.split == newItem.split &&
                oldItem.convert == newItem.convert &&
                oldItem.workId == newItem.workId
    }
}
