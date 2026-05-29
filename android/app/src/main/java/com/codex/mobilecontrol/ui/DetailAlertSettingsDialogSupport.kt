package com.codex.mobilecontrol.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.Window
import android.widget.LinearLayout
import com.codex.mobilecontrol.R

data class DetailAlertSettingsSelection(
    val messageEnabled: Boolean,
    val completionEnabled: Boolean
)

object DetailAlertSettingsDialogSupport {
    fun show(
        context: Context,
        initialSelection: DetailAlertSettingsSelection,
        dp: (Int) -> Int,
        onConfirm: (DetailAlertSettingsSelection) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var selectedMessageEnabled = initialSelection.messageEnabled
        var selectedCompletionEnabled = initialSelection.completionEnabled
        val panel = UnifiedDialogSupport.panel(
            context = context,
            title = context.getString(R.string.detail_alert_settings_title),
            dp = dp
        )
        val optionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun renderRows() {
            optionsContainer.removeAllViews()
            optionsContainer.addView(
                alertOptionRow(
                    context = context,
                    title = context.getString(R.string.detail_alert_new_message_title),
                    subtitle = context.getString(
                        if (selectedMessageEnabled) {
                            R.string.detail_alert_new_message_enabled_subtitle
                        } else {
                            R.string.detail_alert_new_message_disabled_subtitle
                        }
                    ),
                    iconResId = R.drawable.ic_app_chat,
                    selected = selectedMessageEnabled,
                    dp = dp
                ) {
                    selectedMessageEnabled = !selectedMessageEnabled
                    renderRows()
                }
            )
            optionsContainer.addView(
                alertOptionRow(
                    context = context,
                    title = context.getString(R.string.detail_alert_completion_title),
                    subtitle = context.getString(
                        if (selectedCompletionEnabled) {
                            R.string.detail_alert_completion_enabled_subtitle
                        } else {
                            R.string.detail_alert_completion_disabled_subtitle
                        }
                    ),
                    iconResId = R.drawable.ic_detail_clock,
                    selected = selectedCompletionEnabled,
                    dp = dp
                ) {
                    selectedCompletionEnabled = !selectedCompletionEnabled
                    renderRows()
                }
            )
        }

        renderRows()
        panel.addView(optionsContainer)
        panel.addView(
            LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { params -> params.topMargin = dp(2) }
                gravity = Gravity.END
                addView(
                    UnifiedDialogSupport.footerButton(
                        context = context,
                        title = context.getString(R.string.detail_alert_settings_cancel),
                        primary = false,
                        dp = dp
                    ) {
                        dialog.dismiss()
                    }
                )
                addView(
                    UnifiedDialogSupport.footerButton(
                        context = context,
                        title = context.getString(R.string.detail_alert_settings_confirm),
                        primary = true,
                        dp = dp
                    ) {
                        dialog.dismiss()
                        onConfirm(
                            DetailAlertSettingsSelection(
                                messageEnabled = selectedMessageEnabled,
                                completionEnabled = selectedCompletionEnabled
                            )
                        )
                    }
                )
            }
        )

        dialog.setContentView(panel)
        dialog.show()
        UnifiedDialogSupport.styleWindow(context, dialog, dp)
    }

    private fun alertOptionRow(
        context: Context,
        title: String,
        subtitle: String,
        iconResId: Int,
        selected: Boolean,
        dp: (Int) -> Int,
        onToggle: () -> Unit
    ) = UnifiedDialogSupport.row(
        context = context,
        title = title,
        subtitle = subtitle,
        iconResId = iconResId,
        selected = selected,
        current = false,
        trailingView = UnifiedDialogSupport.pillSwitch(
            context = context,
            checked = selected,
            dp = dp
        ) {
            onToggle()
        },
        dp = dp,
        onClick = onToggle
    )
}
