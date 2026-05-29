package com.codex.mobilecontrol.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.codex.mobilecontrol.R

data class UnifiedDialogAction(
    val title: String,
    val subtitle: String,
    @DrawableRes val iconResId: Int,
    val selected: Boolean = false,
    val onClick: (Dialog) -> Unit
)

object UnifiedDialogSupport {
    fun showActionDialog(
        context: Context,
        title: String,
        actions: List<UnifiedDialogAction>,
        dp: (Int) -> Int
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = panel(context, title, dp)
        actions.forEach { action ->
            panel.addView(
                row(
                    context = context,
                    title = action.title,
                    subtitle = action.subtitle,
                    iconResId = action.iconResId,
                    selected = action.selected,
                    dp = dp
                ) {
                    action.onClick(dialog)
                }
            )
        }
        dialog.setContentView(panel)
        dialog.show()
        styleWindow(context, dialog, dp)
    }

    fun panel(
        context: Context,
        title: String,
        dp: (Int) -> Int
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(ContextCompat.getColor(context, R.color.detail_nested_card_surface))
                setStroke(dp(1), ContextCompat.getColor(context, R.color.profile_panel_stroke_bright))
            }
            addView(
                TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { params -> params.bottomMargin = dp(12) }
                    text = title
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(context, R.color.detail_primary_text))
                }
            )
        }
    }

    fun row(
        context: Context,
        title: String,
        subtitle: String,
        @DrawableRes iconResId: Int,
        selected: Boolean,
        current: Boolean = selected,
        badgeText: String? = null,
        trailingView: View? = null,
        clickable: Boolean = true,
        dp: (Int) -> Int,
        onClick: () -> Unit
    ): View {
        return LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params -> params.bottomMargin = dp(10) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = clickable
            isFocusable = clickable
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.status_pill_background else R.color.profile_inner_surface
                    )
                )
                setStroke(
                    dp(1),
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.detail_link_text else R.color.profile_divider
                    )
                )
            }
            if (clickable) {
                setOnClickListener { onClick() }
            }

            addView(
                FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).also { params ->
                        params.marginEnd = dp(12)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(ContextCompat.getColor(context, R.color.profile_icon_tile_surface))
                        setStroke(dp(1), ContextCompat.getColor(context, R.color.profile_icon_tile_stroke))
                    }
                    addView(
                        ImageView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER)
                            setImageResource(iconResId)
                            imageTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(context, R.color.detail_icon_tint)
                            )
                        }
                    )
                }
            )

            addView(
                LinearLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(context).apply {
                            text = title
                            textSize = 15f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(ContextCompat.getColor(context, R.color.detail_primary_text))
                        }
                    )
                    addView(
                        TextView(context).apply {
                            text = subtitle
                            textSize = 12f
                            setTextColor(ContextCompat.getColor(context, R.color.profile_muted_text))
                        }
                    )
                }
            )

            if (trailingView != null) {
                val existingTrailingParams = trailingView.layoutParams
                addView(
                    trailingView.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            existingTrailingParams?.width ?: LinearLayout.LayoutParams.WRAP_CONTENT,
                            existingTrailingParams?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { params -> params.marginStart = dp(10) }
                    }
                )
                return@apply
            }

            val resolvedBadgeText = badgeText ?: if (current) {
                context.getString(R.string.dialog_option_selected)
            } else {
                null
            }
            if (resolvedBadgeText != null) {
                addView(
                    TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            dp(28)
                        ).also { params -> params.marginStart = dp(10) }
                        gravity = Gravity.CENTER
                        minWidth = dp(48)
                        setPadding(dp(10), 0, dp(10), 0)
                        text = resolvedBadgeText
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(ContextCompat.getColor(context, R.color.detail_success_text))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setColor(ContextCompat.getColor(context, R.color.detail_success_surface))
                        }
                    }
                )
            }
        }
    }

    fun pillSwitch(
        context: Context,
        checked: Boolean,
        dp: (Int) -> Int,
        onCheckedChange: (Boolean) -> Unit
    ): View {
        val switchWidth = dp(58)
        val switchHeight = dp(34)
        val switchPadding = dp(4)
        val thumbSize = dp(26)
        val thumbTravel = switchWidth - thumbSize - switchPadding * 2

        val thumb = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                thumbSize,
                thumbSize,
                Gravity.CENTER_VERTICAL or Gravity.START
            ).also { params ->
                params.marginStart = switchPadding
            }
            background = thumbDrawable(context, checked)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(2).toFloat()
            }
        }

        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(switchWidth, switchHeight)
            background = trackDrawable(context, checked, switchHeight, dp)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(
                if (checked) {
                    R.string.detail_alert_toggle_enabled
                } else {
                    R.string.detail_alert_toggle_disabled
                }
            )
            addView(thumb)
            post {
                thumb.translationX = if (checked) thumbTravel.toFloat() else 0f
            }
            setOnClickListener {
                onCheckedChange(!checked)
            }
        }
    }

    fun footerButton(
        context: Context,
        title: String,
        primary: Boolean,
        dp: (Int) -> Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(40)
            ).also { params -> params.marginStart = dp(10) }
            gravity = Gravity.CENTER
            minWidth = dp(74)
            setPadding(dp(14), 0, dp(14), 0)
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (primary) R.color.login_white else R.color.profile_muted_text
                )
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(
                    ContextCompat.getColor(
                        context,
                        if (primary) R.color.detail_send_blue else R.color.profile_inner_surface
                    )
                )
                if (!primary) {
                    setStroke(dp(1), ContextCompat.getColor(context, R.color.profile_divider))
                }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun styleWindow(
        context: Context,
        dialog: Dialog,
        dp: (Int) -> Int
    ) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.88f).toInt().coerceAtMost(dp(420)),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun trackDrawable(
        context: Context,
        isChecked: Boolean,
        switchHeight: Int,
        dp: (Int) -> Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = (switchHeight / 2).toFloat()
            setColor(
                ContextCompat.getColor(
                    context,
                    if (isChecked) R.color.status_pill_background else R.color.profile_inner_surface
                )
            )
            setStroke(
                dp(1),
                ContextCompat.getColor(
                    context,
                    if (isChecked) R.color.detail_send_blue else R.color.profile_divider
                )
            )
        }
    }

    private fun thumbDrawable(context: Context, isChecked: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                ContextCompat.getColor(
                    context,
                    if (isChecked) R.color.detail_send_blue else R.color.profile_muted_text
                )
            )
        }
    }
}
