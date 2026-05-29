package com.codex.mobilecontrol.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.codex.mobilecontrol.databinding.ItemEventBinding
import com.codex.mobilecontrol.model.ThreadEvent

class ThreadEventAdapter :
    ListAdapter<ThreadEvent, ThreadEventAdapter.EventViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class EventViewHolder(
        private val binding: ItemEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ThreadEvent) {
            binding.eventText.text = item.text
            binding.eventTimestamp.text = MobileUiFormatter.formatMessageTimestamp(item.timestamp)
        }
    }

    private object Diff : DiffUtil.ItemCallback<ThreadEvent>() {
        override fun areItemsTheSame(oldItem: ThreadEvent, newItem: ThreadEvent): Boolean {
            return oldItem.eventId == newItem.eventId
        }

        override fun areContentsTheSame(oldItem: ThreadEvent, newItem: ThreadEvent): Boolean {
            return oldItem == newItem
        }
    }
}
