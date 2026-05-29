package com.codex.mobilecontrol.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.codex.mobilecontrol.R
import com.codex.mobilecontrol.model.MobileAutomationItem

object AutomationListDialogSupport {
    fun show(
        context: Context,
        automations: List<MobileAutomationItem>,
        isLoading: Boolean,
        dp: (Int) -> Int,
        onOpenThread: (String) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = UnifiedDialogSupport.panel(
            context = context,
            title = context.getString(R.string.automation_list_dialog_title),
            dp = dp
        )
        when {
            isLoading && automations.isEmpty() -> {
                panel.addView(
                    infoRow(
                        context = context,
                        title = context.getString(R.string.automation_list_loading),
                        subtitle = context.getString(R.string.automation_list_loading_subtitle),
                        dp = dp
                    )
                )
            }
            automations.isEmpty() -> {
                panel.addView(
                    infoRow(
                        context = context,
                        title = context.getString(R.string.automation_list_empty),
                        subtitle = context.getString(R.string.automation_list_empty_subtitle),
                        dp = dp
                    )
                )
            }
            else -> {
                addAutomationSection(
                    context = context,
                    panel = panel,
                    title = context.getString(R.string.automation_list_active_section),
                    automations = automations.filter { it.status == "ACTIVE" },
                    dp = dp,
                    dialog = dialog,
                    onOpenThread = onOpenThread
                )
                addAutomationSection(
                    context = context,
                    panel = panel,
                    title = context.getString(R.string.automation_list_paused_section),
                    automations = automations.filter { it.status != "ACTIVE" },
                    dp = dp,
                    dialog = dialog,
                    onOpenThread = onOpenThread
                )
            }
        }

        panel.addView(
            LinearLayout(context).apply {
                gravity = Gravity.END
                addView(
                    UnifiedDialogSupport.footerButton(
                        context = context,
                        title = context.getString(R.string.automation_list_close),
                        primary = false,
                        dp = dp
                    ) {
                        dialog.dismiss()
                    }
                )
            }
        )

        dialog.setContentView(
            ScrollView(context).apply {
                isFillViewport = false
                addView(panel)
            }
        )
        dialog.show()
        UnifiedDialogSupport.styleWindow(context, dialog, dp)
    }

    private fun addAutomationSection(
        context: Context,
        panel: LinearLayout,
        title: String,
        automations: List<MobileAutomationItem>,
        dp: (Int) -> Int,
        dialog: Dialog,
        onOpenThread: (String) -> Unit
    ) {
        if (automations.isEmpty()) {
            return
        }
        panel.addView(sectionLabel(context, title, dp))
        automations.forEach { automation ->
            val targetThreadId = automation.targetThreadId
            panel.addView(
                UnifiedDialogSupport.row(
                    context = context,
                    title = automation.name.ifBlank { automation.id },
                    subtitle = automationSubtitle(context, automation),
                    iconResId = R.drawable.ic_detail_clock,
                    selected = automation.status == "ACTIVE",
                    current = false,
                    badgeText = automationStatusLabel(context, automation.status),
                    clickable = !targetThreadId.isNullOrBlank(),
                    dp = dp
                ) {
                    if (!targetThreadId.isNullOrBlank()) {
                        dialog.dismiss()
                        onOpenThread(targetThreadId)
                    }
                }
            )
        }
    }

    private fun infoRow(
        context: Context,
        title: String,
        subtitle: String,
        dp: (Int) -> Int
    ) = UnifiedDialogSupport.row(
            context = context,
            title = title,
            subtitle = subtitle,
            iconResId = R.drawable.ic_detail_clock,
            selected = false,
            current = false,
            clickable = false,
            dp = dp
        ) {
            Unit
        }

    private fun sectionLabel(
        context: Context,
        title: String,
        dp: (Int) -> Int
    ): TextView {
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params ->
                params.topMargin = dp(2)
                params.bottomMargin = dp(8)
            }
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.profile_muted_text))
        }
    }

    private fun automationSubtitle(context: Context, automation: MobileAutomationItem): String {
        return listOfNotNull(
            automationKindLabel(context, automation.kind),
            automation.scheduleSummary.takeIf { it.isNotBlank() },
            automation.targetThreadTitle?.takeIf { it.isNotBlank() } ?: automation.cwd?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }

    private fun automationKindLabel(context: Context, kind: String): String {
        return context.getString(
            when (kind) {
                "heartbeat" -> R.string.automation_kind_heartbeat
                "cron" -> R.string.automation_kind_cron
                else -> R.string.automation_kind_unknown
            }
        )
    }

    private fun automationStatusLabel(context: Context, status: String): String {
        return context.getString(
            when (status) {
                "ACTIVE" -> R.string.automation_status_active
                "PAUSED" -> R.string.automation_status_paused
                else -> R.string.automation_status_unknown
            }
        )
    }
}
