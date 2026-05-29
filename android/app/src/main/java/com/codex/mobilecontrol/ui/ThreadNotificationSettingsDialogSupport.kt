package com.codex.mobilecontrol.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.Window
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.codex.mobilecontrol.R

data class ThreadNotificationSettingsSelection(
    val messageEnabled: Boolean,
    val completionEnabled: Boolean,
    val messageEnabledThreadIds: Set<String> = emptySet(),
    val completionEnabledThreadIds: Set<String> = emptySet()
)

data class ThreadNotificationSettingsThread(
    val threadId: String,
    val title: String,
    val subtitle: String,
    val messageEnabled: Boolean,
    val completionEnabled: Boolean
)

object ThreadNotificationSettingsDialogSupport {
    fun show(
        context: Context,
        initialSelection: ThreadNotificationSettingsSelection,
        messageThreadCount: Int,
        completionThreadCount: Int,
        threadItems: List<ThreadNotificationSettingsThread> = emptyList(),
        dp: (Int) -> Int,
        onOpenSystemSettings: () -> Unit,
        onThreadSelectionChanged: (ThreadNotificationSettingsSelection) -> Unit = {},
        onConfirm: (ThreadNotificationSettingsSelection) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val selectedMessageThreadIds = initialSelection.messageEnabledThreadIds
            .ifEmpty { threadItems.filter { it.messageEnabled }.map { it.threadId }.toSet() }
            .toMutableSet()
        val selectedCompletionThreadIds = initialSelection.completionEnabledThreadIds
            .ifEmpty { threadItems.filter { it.completionEnabled }.map { it.threadId }.toSet() }
            .toMutableSet()
        var selectedMessageEnabled = initialSelection.messageEnabled && selectedMessageThreadIds.isNotEmpty()
        var selectedCompletionEnabled =
            initialSelection.completionEnabled && selectedCompletionThreadIds.isNotEmpty()
        val panel = UnifiedDialogSupport.panel(
            context = context,
            title = context.getString(R.string.thread_notifications_settings_title),
            dp = dp
        )
        val optionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun currentSelection() = ThreadNotificationSettingsSelection(
            messageEnabled = selectedMessageEnabled && selectedMessageThreadIds.isNotEmpty(),
            completionEnabled = selectedCompletionEnabled && selectedCompletionThreadIds.isNotEmpty(),
            messageEnabledThreadIds = if (selectedMessageEnabled) {
                selectedMessageThreadIds.toSet()
            } else {
                emptySet()
            },
            completionEnabledThreadIds = if (selectedCompletionEnabled) {
                selectedCompletionThreadIds.toSet()
            } else {
                emptySet()
            }
        )

        fun renderRows() {
            optionsContainer.removeAllViews()
            optionsContainer.addView(
                notificationTypeRow(
                    context = context,
                    title = context.getString(R.string.detail_alert_new_message_title),
                    subtitle = if (selectedMessageEnabled) {
                        context.getString(
                            R.string.thread_notifications_message_on_subtitle,
                            selectedMessageThreadIds.size
                        )
                    } else {
                        context.getString(R.string.thread_notifications_message_off_subtitle)
                    },
                    iconResId = R.drawable.ic_app_chat,
                    selected = selectedMessageEnabled,
                    dp = dp,
                    onOpenList = {
                        showManagedThreadList(
                            context = context,
                            dialog = dialog,
                            title = context.getString(R.string.thread_notifications_manage_message_title),
                            subtitle = context.getString(R.string.thread_notifications_manage_message_subtitle),
                            threadItems = threadItems,
                            selectedThreadIds = selectedMessageThreadIds,
                            dp = dp,
                            onBack = {
                                dialog.setContentView(panel)
                                UnifiedDialogSupport.styleWindow(context, dialog, dp)
                                renderRows()
                            }
                        ) { threadId ->
                            selectedMessageThreadIds.remove(threadId)
                            selectedMessageEnabled = selectedMessageThreadIds.isNotEmpty()
                            onThreadSelectionChanged(currentSelection())
                        }
                    },
                    onToggle = {
                        if (selectedMessageEnabled || messageThreadCount > 0) {
                            selectedMessageEnabled =
                                !selectedMessageEnabled && selectedMessageThreadIds.isNotEmpty()
                            renderRows()
                        }
                    }
                )
            )
            optionsContainer.addView(
                notificationTypeRow(
                    context = context,
                    title = context.getString(R.string.detail_alert_completion_title),
                    subtitle = if (selectedCompletionEnabled) {
                        context.getString(
                            R.string.thread_notifications_completion_on_subtitle,
                            selectedCompletionThreadIds.size
                        )
                    } else {
                        context.getString(R.string.thread_notifications_completion_off_subtitle)
                    },
                    iconResId = R.drawable.ic_detail_clock,
                    selected = selectedCompletionEnabled,
                    dp = dp,
                    onOpenList = {
                        showManagedThreadList(
                            context = context,
                            dialog = dialog,
                            title = context.getString(R.string.thread_notifications_manage_completion_title),
                            subtitle = context.getString(R.string.thread_notifications_manage_completion_subtitle),
                            threadItems = threadItems,
                            selectedThreadIds = selectedCompletionThreadIds,
                            dp = dp,
                            onBack = {
                                dialog.setContentView(panel)
                                UnifiedDialogSupport.styleWindow(context, dialog, dp)
                                renderRows()
                            }
                        ) { threadId ->
                            selectedCompletionThreadIds.remove(threadId)
                            selectedCompletionEnabled = selectedCompletionThreadIds.isNotEmpty()
                            onThreadSelectionChanged(currentSelection())
                        }
                    },
                    onToggle = {
                        if (selectedCompletionEnabled || completionThreadCount > 0) {
                            selectedCompletionEnabled =
                                !selectedCompletionEnabled && selectedCompletionThreadIds.isNotEmpty()
                            renderRows()
                        }
                    }
                )
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
                        title = context.getString(R.string.thread_notifications_system_settings),
                        primary = false,
                        dp = dp
                    ) {
                        dialog.dismiss()
                        onOpenSystemSettings()
                    }
                )
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
                        title = context.getString(R.string.thread_notifications_settings_confirm),
                        primary = true,
                        dp = dp
                    ) {
                        dialog.dismiss()
                        onConfirm(currentSelection())
                    }
                )
            }
        )

        dialog.setContentView(panel)
        dialog.show()
        UnifiedDialogSupport.styleWindow(context, dialog, dp)
    }

    private fun notificationTypeRow(
        context: Context,
        title: String,
        subtitle: String,
        iconResId: Int,
        selected: Boolean,
        dp: (Int) -> Int,
        onOpenList: () -> Unit,
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
        onClick = onOpenList
    )

    private fun showManagedThreadList(
        context: Context,
        dialog: Dialog,
        title: String,
        subtitle: String,
        threadItems: List<ThreadNotificationSettingsThread>,
        selectedThreadIds: MutableSet<String>,
        dp: (Int) -> Int,
        onBack: () -> Unit,
        onDisableThread: (String) -> Unit
    ) {
        fun render() {
            val panel = UnifiedDialogSupport.panel(
                context = context,
                title = title,
                dp = dp
            )
            panel.addView(
                TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { params -> params.bottomMargin = dp(10) }
                    text = subtitle
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.profile_muted_text))
                }
            )

            val enabledThreads = threadItems.filter { it.threadId in selectedThreadIds }
            if (enabledThreads.isEmpty()) {
                panel.addView(
                    TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { params -> params.bottomMargin = dp(10) }
                        text = context.getString(R.string.thread_notifications_manage_empty)
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(context, R.color.profile_muted_text))
                    }
                )
            } else {
                panel.addView(
                    ScrollView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            if (enabledThreads.size > 4) dp(336) else LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { params -> params.bottomMargin = dp(4) }
                        isFillViewport = false
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                enabledThreads.forEach { thread ->
                                    addView(
                                        UnifiedDialogSupport.row(
                                            context = context,
                                            title = thread.title,
                                            subtitle = thread.subtitle,
                                            iconResId = R.drawable.ic_app_file,
                                            selected = true,
                                            current = false,
                                            trailingView = actionPill(
                                                context = context,
                                                title = context.getString(
                                                    R.string.thread_notifications_disable_thread
                                                ),
                                                dp = dp
                                            ) {
                                                onDisableThread(thread.threadId)
                                                render()
                                            },
                                            clickable = false,
                                            dp = dp
                                        ) {}
                                    )
                                }
                            }
                        )
                    }
                )
            }

            panel.addView(
                LinearLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    gravity = Gravity.END
                    addView(
                        UnifiedDialogSupport.footerButton(
                            context = context,
                            title = context.getString(R.string.thread_notifications_manage_back),
                            primary = false,
                            dp = dp
                        ) {
                            onBack()
                        }
                    )
                }
            )

            dialog.setContentView(panel)
            UnifiedDialogSupport.styleWindow(context, dialog, dp)
        }

        render()
    }

    private fun actionPill(
        context: Context,
        title: String,
        dp: (Int) -> Int,
        onClick: () -> Unit
    ) = UnifiedDialogSupport.footerButton(
        context = context,
        title = title,
        primary = false,
        dp = dp,
        onClick = onClick
    )
}
