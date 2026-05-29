package com.codex.mobilecontrol.ui

import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.codex.mobilecontrol.databinding.ItemMessageBinding
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole

class ThreadMessageAdapter :
    ListAdapter<ThreadMessage, ThreadMessageAdapter.MessageViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ThreadMessage) {
            binding.messageRole.text = when (item.role) {
                ThreadMessageRole.USER -> "你"
                ThreadMessageRole.ASSISTANT -> "Codex"
                ThreadMessageRole.SYSTEM -> "系统"
            }
            if (item.kind == "image" && !item.imageUrl.isNullOrBlank()) {
                val imageUrl = item.thumbnailUrl ?: item.imageUrl
                binding.messageImage.visibility = View.VISIBLE
                binding.messageFileName.visibility =
                    if (item.fileName.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.messageText.visibility =
                    if (item.text.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.messageFileName.text = item.fileName
                binding.messageText.text = item.text
                binding.messageImage.load(
                    data = imageUrl,
                    imageLoader = GatewayImageLoader.get(binding.root.context)
                )
            } else if (item.kind == "file") {
                binding.messageImage.visibility = View.GONE
                binding.messageFileName.visibility =
                    if (item.fileName.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.messageText.visibility =
                    if (item.text.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.messageFileName.text = item.fileName
                binding.messageText.text = item.text
            } else {
                binding.messageImage.visibility = View.GONE
                binding.messageFileName.visibility = View.GONE
                binding.messageText.visibility = View.VISIBLE
                binding.messageText.text = item.text ?: item.fileName ?: "[图片]"
            }
            binding.messageTimestamp.text = MobileUiFormatter.formatMessageTimestamp(item.timestamp)
        }
    }

    private object Diff : DiffUtil.ItemCallback<ThreadMessage>() {
        override fun areItemsTheSame(oldItem: ThreadMessage, newItem: ThreadMessage): Boolean {
            return oldItem.messageId == newItem.messageId
        }

        override fun areContentsTheSame(oldItem: ThreadMessage, newItem: ThreadMessage): Boolean {
            return oldItem == newItem
        }
    }
}
