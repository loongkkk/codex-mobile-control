package com.codex.mobilecontrol.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.codex.mobilecontrol.R
import coil.load
import com.codex.mobilecontrol.databinding.ItemDetailSectionBinding
import com.codex.mobilecontrol.databinding.ItemEventBinding
import com.codex.mobilecontrol.databinding.ItemMessageBinding
import com.codex.mobilecontrol.model.ThreadMessageRole

class DetailTimelineAdapter :
    ListAdapter<DetailTimelineItem, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is DetailTimelineItem.Section -> VIEW_TYPE_SECTION
            is DetailTimelineItem.EventRow -> VIEW_TYPE_EVENT
            is DetailTimelineItem.MessageRow -> VIEW_TYPE_MESSAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SECTION -> SectionViewHolder(
                ItemDetailSectionBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_EVENT -> EventViewHolder(
                ItemEventBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_MESSAGE -> MessageViewHolder(
                ItemMessageBinding.inflate(inflater, parent, false)
            )

            else -> error("Unsupported viewType=$viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SectionViewHolder -> holder.bind(getItem(position) as DetailTimelineItem.Section)
            is EventViewHolder -> holder.bind(getItem(position) as DetailTimelineItem.EventRow)
            is MessageViewHolder -> holder.bind(getItem(position) as DetailTimelineItem.MessageRow)
        }
    }

    private class SectionViewHolder(
        private val binding: ItemDetailSectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DetailTimelineItem.Section) {
            binding.sectionTitle.text = item.title
        }
    }

    private class EventViewHolder(
        private val binding: ItemEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DetailTimelineItem.EventRow) {
            binding.eventText.text = item.event.text
            binding.eventTimestamp.text =
                MobileUiFormatter.formatMessageTimestamp(item.event.timestamp)
        }
    }

    private class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DetailTimelineItem.MessageRow) {
            val message = item.message
            val roleLabel = when (message.role) {
                ThreadMessageRole.USER -> "你"
                ThreadMessageRole.ASSISTANT -> "Codex"
                ThreadMessageRole.SYSTEM -> "系统"
            }
            binding.messageRole.text = roleLabel

            val context = binding.root.context
            val isUserMessage = message.role == ThreadMessageRole.USER
            val isSystemMessage = message.role == ThreadMessageRole.SYSTEM
            val horizontalGravity = if (isUserMessage) Gravity.END else Gravity.START

            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    when {
                        isUserMessage -> R.color.message_user_bg
                        isSystemMessage -> R.color.message_system_bg
                        else -> R.color.dashboard_icon_tile
                    }
                )
            )
            binding.root.strokeColor = ContextCompat.getColor(
                context,
                if (isUserMessage) R.color.message_user_stroke else R.color.dashboard_card_stroke
            )
            binding.messageRole.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isUserMessage) R.color.message_user_role else R.color.accent
                )
            )
            binding.messageRole.gravity = horizontalGravity
            binding.messageText.gravity = horizontalGravity
            binding.messageTimestamp.gravity = horizontalGravity
            binding.messageFileName.gravity = horizontalGravity
            updateBubbleMargins(context = context, isUserMessage = isUserMessage)

            if (message.kind == "image" && !message.imageUrl.isNullOrBlank()) {
                val imageUrl = message.thumbnailUrl ?: message.imageUrl
                binding.messageImage.visibility = View.VISIBLE
                binding.messageFileName.visibility =
                    if (message.fileName.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.messageText.visibility =
                    if (message.text.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.messageFileName.text = message.fileName
                binding.messageText.text = message.text
                binding.messageImage.load(
                    data = imageUrl,
                    imageLoader = GatewayImageLoader.get(binding.root.context)
                )
            } else {
                binding.messageImage.visibility = View.GONE
                binding.messageFileName.visibility = View.GONE
                binding.messageText.visibility = View.VISIBLE
                binding.messageText.text = message.text ?: message.fileName ?: "[图片]"
            }
            binding.messageTimestamp.text =
                MobileUiFormatter.formatMessageTimestamp(message.timestamp)
        }

        private fun updateBubbleMargins(context: Context, isUserMessage: Boolean) {
            val layoutParams = binding.root.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val bubbleOffset = context.dp(52)
            layoutParams.marginStart = if (isUserMessage) bubbleOffset else 0
            layoutParams.marginEnd = if (isUserMessage) 0 else bubbleOffset
            binding.root.layoutParams = layoutParams
        }
    }

    private object Diff : DiffUtil.ItemCallback<DetailTimelineItem>() {
        override fun areItemsTheSame(
            oldItem: DetailTimelineItem,
            newItem: DetailTimelineItem
        ): Boolean {
            return when {
                oldItem is DetailTimelineItem.Section && newItem is DetailTimelineItem.Section ->
                    oldItem.key == newItem.key

                oldItem is DetailTimelineItem.EventRow && newItem is DetailTimelineItem.EventRow ->
                    oldItem.event.eventId == newItem.event.eventId

                oldItem is DetailTimelineItem.MessageRow &&
                    newItem is DetailTimelineItem.MessageRow ->
                    oldItem.message.messageId == newItem.message.messageId

                else -> false
            }
        }

        override fun areContentsTheSame(
            oldItem: DetailTimelineItem,
            newItem: DetailTimelineItem
        ): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val VIEW_TYPE_SECTION = 0
        private const val VIEW_TYPE_EVENT = 1
        private const val VIEW_TYPE_MESSAGE = 2
    }
}

private fun Context.dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
}
