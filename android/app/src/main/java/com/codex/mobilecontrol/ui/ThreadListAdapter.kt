package com.codex.mobilecontrol.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.codex.mobilecontrol.R
import com.codex.mobilecontrol.databinding.ItemThreadCompactBinding
import com.codex.mobilecontrol.databinding.ItemThreadProjectBinding
import com.codex.mobilecontrol.databinding.ItemThreadSectionBinding
import com.codex.mobilecontrol.model.ThreadListItem

class ThreadListAdapter(
    private val onClick: (ThreadListItem) -> Unit
) : ListAdapter<ThreadListRow, RecyclerView.ViewHolder>(Diff) {
    private var lastSubmittedRows: List<ThreadListRow> = emptyList()
    private var lastSubmittedReadSettledIndicatorKeys: Map<String, String?> = emptyMap()
    private var readSettledIndicatorKeys: Map<String, String?> = emptyMap()

    fun submitThreads(
        threads: List<ThreadListItem>,
        readSettledIndicatorKeys: Map<String, String?> = emptyMap()
    ) {
        val rows = ThreadListDisplaySupport.buildRows(threads, readSettledIndicatorKeys)
        if (rows == lastSubmittedRows &&
            readSettledIndicatorKeys == lastSubmittedReadSettledIndicatorKeys
        ) {
            return
        }
        lastSubmittedRows = rows
        lastSubmittedReadSettledIndicatorKeys = readSettledIndicatorKeys.toMap()
        this.readSettledIndicatorKeys = lastSubmittedReadSettledIndicatorKeys
        submitList(rows)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ThreadListRow.Section -> VIEW_TYPE_SECTION
            is ThreadListRow.Project -> VIEW_TYPE_PROJECT
            is ThreadListRow.Thread -> VIEW_TYPE_THREAD
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SECTION -> SectionViewHolder(
                ItemThreadSectionBinding.inflate(inflater, parent, false)
            )
            VIEW_TYPE_PROJECT -> ProjectViewHolder(
                ItemThreadProjectBinding.inflate(inflater, parent, false)
            )
            else -> ThreadViewHolder(
                ItemThreadCompactBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ThreadListRow.Section -> (holder as SectionViewHolder).bind(item)
            is ThreadListRow.Project -> (holder as ProjectViewHolder).bind(item)
            is ThreadListRow.Thread -> (holder as ThreadViewHolder).bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ThreadViewHolder) {
            holder.recycle()
        }
        super.onViewRecycled(holder)
    }

    private class SectionViewHolder(
        private val binding: ItemThreadSectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ThreadListRow.Section) {
            binding.threadSectionTitle.text = item.title
        }
    }

    private class ProjectViewHolder(
        private val binding: ItemThreadProjectBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ThreadListRow.Project) {
            binding.threadProjectFolder.text = item.folderName
            binding.threadProjectParent.text = item.parentName
        }
    }

    inner class ThreadViewHolder(
        private val binding: ItemThreadCompactBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private var statusPulseAnimator: AnimatorSet? = null
        private var boundStatusState: ThreadStatusIndicatorSupport.IndicatorState? = null

        fun bind(item: ThreadListRow.Thread) {
            val thread = item.thread
            binding.threadCompactTitle.text = thread.title
            binding.threadCompactTime.text = MobileUiFormatter.formatRelativeTime(thread.updatedAt)
            bindPinnedIndicator()
            bindStatusIndicator(item)
            binding.threadCompactRow.updatePaddingRelative(
                start = binding.threadCompactRow.dp(THREAD_ROW_PADDING_DP)
            )
            binding.threadCompactRow.setOnClickListener {
                binding.threadCompactRow.animate().cancel()
                binding.threadCompactRow.animate()
                    .scaleX(0.985f)
                    .scaleY(0.985f)
                    .setDuration(90L)
                    .withEndAction {
                        binding.threadCompactRow.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150L)
                            .start()
                        onClick(thread)
                    }
                    .start()
            }
        }

        fun recycle() {
            boundStatusState = null
            stopStatusPulse()
            binding.threadCompactRow.animate().cancel()
        }

        private fun bindPinnedIndicator() {
            binding.threadPinIcon.visibility = View.GONE
        }

        private fun bindStatusIndicator(item: ThreadListRow.Thread) {
            val thread = item.thread
            val indicatorState = ThreadStatusIndicatorSupport.visibleStateFor(
                thread = thread,
                readSettledKey = item.readSettledIndicatorKey
            )
            if (indicatorState == boundStatusState) {
                return
            }
            stopStatusPulse()
            boundStatusState = indicatorState
            when (indicatorState) {
                ThreadStatusIndicatorSupport.IndicatorState.HIDDEN -> {
                    binding.threadStatusDot.visibility = View.INVISIBLE
                }
                ThreadStatusIndicatorSupport.IndicatorState.RUNNING -> {
                    binding.threadStatusDot.visibility = View.VISIBLE
                    tintStatusDot(R.color.thread_status_running)
                    startStatusPulse()
                }
                ThreadStatusIndicatorSupport.IndicatorState.WAITING_INPUT -> {
                    binding.threadStatusDot.visibility = View.VISIBLE
                    tintStatusDot(R.color.thread_status_waiting)
                    startStatusPulse()
                }
                ThreadStatusIndicatorSupport.IndicatorState.ERROR -> {
                    binding.threadStatusDot.visibility = View.VISIBLE
                    tintStatusDot(R.color.thread_status_error)
                    startStatusPulse()
                }
                ThreadStatusIndicatorSupport.IndicatorState.SETTLED -> {
                    binding.threadStatusDot.visibility = View.VISIBLE
                    tintStatusDot(R.color.thread_status_settled)
                }
            }
        }

        private fun tintStatusDot(colorRes: Int) {
            binding.threadStatusDot.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(binding.threadStatusDot.context, colorRes)
            )
        }

        private fun startStatusPulse() {
            val alpha = ObjectAnimator.ofFloat(binding.threadStatusDot, View.ALPHA, 0.42f, 1f)
            val scaleX = ObjectAnimator.ofFloat(binding.threadStatusDot, View.SCALE_X, 0.82f, 1.18f)
            val scaleY = ObjectAnimator.ofFloat(binding.threadStatusDot, View.SCALE_Y, 0.82f, 1.18f)
            listOf(alpha, scaleX, scaleY).forEach { animator ->
                animator.duration = STATUS_PULSE_DURATION_MS
                animator.repeatCount = ValueAnimator.INFINITE
                animator.repeatMode = ValueAnimator.REVERSE
            }
            statusPulseAnimator = AnimatorSet().apply {
                playTogether(alpha, scaleX, scaleY)
                start()
            }
        }

        private fun stopStatusPulse() {
            statusPulseAnimator?.cancel()
            statusPulseAnimator = null
            binding.threadStatusDot.animate().cancel()
            binding.threadStatusDot.alpha = 1f
            binding.threadStatusDot.scaleX = 1f
            binding.threadStatusDot.scaleY = 1f
        }
    }

    private object Diff : DiffUtil.ItemCallback<ThreadListRow>() {
        override fun areItemsTheSame(
            oldItem: ThreadListRow,
            newItem: ThreadListRow
        ): Boolean = rowKey(oldItem) == rowKey(newItem)

        override fun areContentsTheSame(
            oldItem: ThreadListRow,
            newItem: ThreadListRow
        ): Boolean = oldItem == newItem

        private fun rowKey(item: ThreadListRow): String {
            return when (item) {
                is ThreadListRow.Section -> "section:${item.title}"
                is ThreadListRow.Project -> "project:${item.cwd}"
                is ThreadListRow.Thread -> "thread:${item.thread.threadId}"
            }
        }
    }

    private companion object {
        private const val VIEW_TYPE_SECTION = 1
        private const val VIEW_TYPE_PROJECT = 2
        private const val VIEW_TYPE_THREAD = 3
        private const val THREAD_ROW_PADDING_DP = 9
        private const val STATUS_PULSE_DURATION_MS = 900L
    }

    private fun View.dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
