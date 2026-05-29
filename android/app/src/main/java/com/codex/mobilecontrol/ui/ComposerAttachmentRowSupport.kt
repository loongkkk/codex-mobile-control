package com.codex.mobilecontrol.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
import com.codex.mobilecontrol.R
import com.codex.mobilecontrol.model.PendingFileDraft
import com.codex.mobilecontrol.model.PendingImageDraft

internal object ComposerAttachmentRowSupport {
    fun imagePreviewRow(
        context: Context,
        draft: PendingImageDraft,
        dp: (Int) -> Int,
        roundedBackground: (colorRes: Int, strokeColorRes: Int) -> Drawable,
        onPreview: (PendingImageDraft) -> Unit,
        onRemove: (PendingImageDraft) -> Unit
    ): View {
        return attachmentRow(context, rowWidth = dp(88), marginEnd = dp(10)).apply {
            val previewFrame = previewFrame(context, size = dp(72))
            val preview = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                contentDescription = context.getString(R.string.pending_image_preview)
                scaleType = ImageView.ScaleType.CENTER_CROP
                isClickable = true
                isFocusable = true
                load(Uri.parse(draft.previewUri))
                setOnClickListener {
                    onPreview(draft)
                }
            }
            previewFrame.addView(preview)
            previewFrame.addView(
                removeButton(
                    context = context,
                    size = dp(24),
                    padding = dp(5),
                    background = roundedBackground(
                        R.color.surface_card,
                        R.color.dashboard_card_stroke
                    ),
                    contentDescription = context.getString(R.string.remove_pending_image),
                    onClick = { onRemove(draft) }
                )
            )
            addView(previewFrame)
            addView(fileNameLabel(context, draft.fileName, topMargin = dp(6)))
        }
    }

    fun filePreviewRow(
        context: Context,
        draft: PendingFileDraft,
        dp: (Int) -> Int,
        roundedBackground: (colorRes: Int, strokeColorRes: Int) -> Drawable,
        onRemove: (PendingFileDraft) -> Unit
    ): View {
        return attachmentRow(context, rowWidth = dp(124), marginEnd = dp(10)).apply {
            val previewFrame = previewFrame(context, size = dp(72))
            val icon = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                contentDescription = context.getString(R.string.pending_file_preview)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                setImageResource(android.R.drawable.ic_menu_upload)
                setColorFilter(ContextCompat.getColor(context, R.color.detail_link_text))
                background = roundedBackground(
                    R.color.detail_nested_card_surface,
                    R.color.dashboard_card_stroke
                )
            }
            previewFrame.addView(icon)
            previewFrame.addView(
                removeButton(
                    context = context,
                    size = dp(24),
                    padding = dp(5),
                    background = roundedBackground(
                        R.color.surface_card,
                        R.color.dashboard_card_stroke
                    ),
                    contentDescription = context.getString(R.string.remove_pending_file),
                    onClick = { onRemove(draft) }
                )
            )
            addView(previewFrame)
            addView(fileNameLabel(context, draft.fileName, topMargin = dp(6)))
        }
    }

    private fun attachmentRow(context: Context, rowWidth: Int, marginEnd: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(rowWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { params -> params.marginEnd = marginEnd }
        }
    }

    private fun previewFrame(context: Context, size: Int): FrameLayout {
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun removeButton(
        context: Context,
        size: Int,
        padding: Int,
        background: Drawable,
        contentDescription: String,
        onClick: () -> Unit
    ): ImageView {
        return ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
            this.background = background
            this.contentDescription = contentDescription
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(padding, padding, padding, padding)
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(ContextCompat.getColor(context, R.color.text_primary))
            setOnClickListener { onClick() }
        }
    }

    private fun fileNameLabel(context: Context, fileName: String, topMargin: Int): TextView {
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params -> params.topMargin = topMargin }
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            maxLines = 1
            text = fileName
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 12f
        }
    }
}
