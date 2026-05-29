package com.codex.mobilecontrol

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class MainActivityInteractionMotionTest {

    @Test
    fun `main activity keeps a dedicated login motion sync`() {
        val source = readSource()

        assertTrue(source.contains("private fun syncConnectionMotion("))
        assertTrue(source.contains("StreamConnectionState.CONNECTING"))
        assertTrue(source.contains("binding.connectButton.animate()"))
    }

    @Test
    fun `login tap immediately shows transition overlay before async state catches up`() {
        val source = readSource()

        assertTrue(source.contains("private fun showLoginMotionOverlay()"))
        assertTrue(source.contains("showLoginMotionOverlay()"))
        assertTrue(source.contains("key = \"connect\""))
        assertTrue(source.contains("val gatewayUrl = binding.gatewayUrlInput.text"))
        assertTrue(source.contains("viewModel.connect(GatewayDefaults.configFor(gatewayUrl, token), null)"))
    }

    @Test
    fun `logout clears repository state without restarting activity`() {
        val source = readSource()
        val logoutBlock = source.substringAfter("private fun performLogout()")
            .substringBefore("private fun appVersionName()")

        assertTrue(logoutBlock.contains("viewModel.logout()"))
        assertTrue(logoutBlock.contains("showLoginState()"))
        assertFalse(logoutBlock.contains("startActivity("))
        assertFalse(logoutBlock.contains("Intent.FLAG_ACTIVITY_CLEAR_TASK"))
    }

    @Test
    fun `login state clears input focus for static landing layout`() {
        val source = readSource()
        val loginBlock = source.substringAfter("private fun showLoginState()")
            .substringBefore("private fun showContentState(")

        assertTrue(loginBlock.contains("binding.gatewayUrlInput.clearFocus()"))
        assertTrue(loginBlock.contains("binding.tokenInput.clearFocus()"))
        assertTrue(loginBlock.contains("binding.configCard.requestFocus()"))
        assertTrue(loginBlock.contains("binding.configCard.post"))
        assertTrue(loginBlock.contains("hideSoftInputFromWindow(binding.root.windowToken, 0)"))
    }

    @Test
    fun `main activity keeps a dedicated screen transition animator`() {
        val source = readSource()
        val renderBlock = source.substringAfter("private fun renderShellState(shellState: ConnectedShellState) {")
            .substringBefore("private fun renderDetailContent(")

        assertTrue(source.contains("private fun animateScreenTransition("))
        assertTrue(source.contains("translationX"))
        assertTrue(source.contains("alpha = 0f"))
        assertTrue(renderBlock.contains("binding.taskPage.alpha = 1f"))
        assertTrue(renderBlock.contains("binding.detailPage.alpha = 1f"))
        assertTrue(renderBlock.contains("binding.profilePage.alpha = 1f"))
    }

    @Test
    fun `main activity keeps a dedicated composer motion sync`() {
        val source = readSource()

        assertTrue(source.contains("private fun syncComposerMotion("))
        assertTrue(source.contains("binding.sendButton.animate()"))
        assertTrue(source.contains("binding.composerStatus.animate()"))
    }

    @Test
    fun `detail subtitle status dot reflects the same thread state as task list`() {
        val source = readSource()

        assertTrue(source.contains("private var detailSocketPulseAnimator: AnimatorSet? = null"))
        assertTrue(source.contains("private fun syncDetailStatusIndicator("))
        assertTrue(source.contains("ThreadStatusIndicatorSupport.visibleStateFor("))
        assertTrue(source.contains("binding.detailSocketStatusDot.backgroundTintList"))
        assertTrue(source.contains("ThreadStatusIndicatorSupport.IndicatorState.RUNNING"))
        assertTrue(source.contains("ThreadStatusIndicatorSupport.IndicatorState.WAITING_INPUT"))
        assertTrue(source.contains("ThreadStatusIndicatorSupport.IndicatorState.ERROR"))
        assertTrue(source.contains("ObjectAnimator.ofFloat(binding.detailSocketStatusDot, View.ALPHA"))
        assertTrue(source.contains("repeatMode = ValueAnimator.REVERSE"))
    }

    @Test
    fun `socket disconnect uses warning banners instead of detail status dot`() {
        val source = readSource()

        assertTrue(source.contains("private fun syncSocketWarningBanners("))
        assertTrue(source.contains("binding.taskSocketWarning.visibility"))
        assertTrue(source.contains("binding.detailSocketWarning.visibility"))
        assertTrue(source.contains("R.string.socket_warning_disconnected"))
    }

    @Test
    fun `send tap does not show global sending transition for async sends`() {
        val source = readSource()
        val sendClickBlock = source.substringAfter("binding.sendButton.setOnClickListener")
            .substringBefore("private fun attachDetailHistoryTouchLoader(")
        val motionOverlayBlock = source.substringAfter("private fun syncMotionOverlay(")
            .substringBefore("private fun showLoginMotionOverlay(")

        assertFalse(source.contains("private fun showSendTapFeedback("))
        assertFalse(sendClickBlock.contains("showSendTapFeedback("))
        assertFalse(motionOverlayBlock.contains("state.isSending"))
        assertFalse(motionOverlayBlock.contains("motion_overlay_send_text_title"))
        assertFalse(motionOverlayBlock.contains("motion_overlay_send_image_title"))
    }

    @Test
    fun `main activity keeps a dedicated motion overlay with minimum visible duration`() {
        val source = readSource()

        assertTrue(source.contains("private fun syncMotionOverlay("))
        assertTrue(source.contains("private fun showMotionOverlay("))
        assertTrue(source.contains("private fun hideMotionOverlay("))
        assertTrue(source.contains("MIN_MOTION_OVERLAY_VISIBLE_DURATION_MS"))
    }

    @Test
    fun `opening a thread detail does not show a global transition overlay`() {
        val source = readSource()
        val openDetailBlock = source.substringAfter("private fun openThreadDetail(threadId: String)")
            .substringBefore("private fun refreshCurrentDetail(")
        val motionOverlayBlock = source.substringAfter("private fun syncMotionOverlay(")
            .substringBefore("private fun showLoginMotionOverlay(")

        assertTrue(source.contains("private fun openThreadDetail(threadId: String)"))
        assertTrue(source.contains("pendingDetailThreadId = threadId"))
        assertTrue(openDetailBlock.contains("applyConnectedScreen(animated = false)"))
        assertFalse(motionOverlayBlock.contains("pendingDetailThreadId"))
        assertFalse(motionOverlayBlock.contains("motion_overlay_detail_title"))
        assertFalse(motionOverlayBlock.contains("motion_overlay_detail_subtitle"))
    }

    @Test
    fun `opening a different thread binds pending detail before showing detail page`() {
        val source = readSource()
        val openDetailBlock = source.substringAfter("private fun openThreadDetail(threadId: String)")
            .substringBefore("private fun refreshCurrentDetail(")

        assertTrue(openDetailBlock.contains("pendingDetailThreadId = threadId"))
        assertTrue(openDetailBlock.contains("bindDetailPage(state)"))
        assertTrue(
            openDetailBlock.indexOf("bindDetailPage(state)") <
                openDetailBlock.indexOf("applyConnectedScreen(animated = false)")
        )
    }

    @Test
    fun `profile page resets only when entering and shows without transition animation`() {
        val source = readSource()
        val showProfileBlock = source.substringAfter("private fun showProfileScreen()")
            .substringBefore("private fun openThreadDetail(")

        assertTrue(showProfileBlock.contains("val wasProfileScreen = currentScreen == ConnectedScreen.PROFILE"))
        assertTrue(showProfileBlock.contains("if (!wasProfileScreen)"))
        assertTrue(showProfileBlock.contains("resetProfileScrollToTop()"))
        assertTrue(
            showProfileBlock.indexOf("val wasProfileScreen = currentScreen == ConnectedScreen.PROFILE") <
                showProfileBlock.indexOf("currentScreen = ConnectedScreen.PROFILE")
        )
        assertTrue(
            showProfileBlock.indexOf("if (!wasProfileScreen)") <
                showProfileBlock.indexOf("resetProfileScrollToTop()")
        )
        assertTrue(showProfileBlock.contains("applyConnectedScreen(animated = false)"))
        assertFalse(showProfileBlock.contains("applyConnectedScreen(animated = true)"))
        assertTrue(source.contains("private fun resetProfileScrollToTop()"))
        assertTrue(source.contains("binding.profilePage.scrollTo(0, 0)"))
        assertTrue(source.contains("binding.profilePage.post"))
    }

    @Test
    fun `task page preserves scroll and enters without transition animation`() {
        val source = readSource()
        val showTaskBlock = source.substringAfter("private fun showTaskScreen()")
            .substringBefore("private fun showProfileScreen()")

        assertFalse(showTaskBlock.contains("resetTaskScrollToTop()"))
        assertFalse(source.contains("private fun resetTaskScrollToTop()"))
        assertTrue(showTaskBlock.contains("applyConnectedScreen(animated = false)"))
        assertFalse(showTaskBlock.contains("applyConnectedScreen(animated = true)"))
    }

    @Test
    fun `unchanged shell state updates dynamic content without resetting page chrome`() {
        val source = readSource()
        val applyBlock = source.substringAfter("private fun applyConnectedScreen(animated: Boolean = false) {")
            .substringBefore("private fun renderShellState(shellState: ConnectedShellState)")

        assertTrue(applyBlock.contains("if (previousShellState == shellState)"))
        assertTrue(applyBlock.contains("renderDynamicShellContent(shellState)"))
        assertTrue(applyBlock.contains("return"))
        assertTrue(
            applyBlock.indexOf("if (previousShellState == shellState)") <
                applyBlock.indexOf("renderShellState(shellState)")
        )
        assertTrue(source.contains("private fun renderDynamicShellContent(shellState: ConnectedShellState)"))
    }

    @Test
    fun `pending detail state is resolved before detail page binding`() {
        val source = readSource()
        val connectedBranch = source.substringAfter("if (state.isConnected) {")
            .substringBefore("} else {")

        assertTrue(
            connectedBranch.indexOf("syncDetailTransitionMotion(state)") <
                connectedBranch.indexOf("bindDetailPage(state)")
        )
    }

    @Test
    fun `pending detail binding does not render stale previous detail messages`() {
        val source = readSource()

        assertTrue(source.contains("val visualDetail = if (isPendingDetail) null else detail"))
        assertTrue(source.contains("DetailVisualSupport.build(this, visualDetail, detailThread)"))
    }

    @Test
    fun `detail conversation bubbles render message timestamps`() {
        val source = readSource()
        val conversationBlock = source.substringAfter("private fun conversationMessageRow(")
            .substringBefore("private fun conversationMessageMarkdown(")

        assertTrue(conversationBlock.contains("MobileUiFormatter.formatMessageTimestamp(message.timestamp)"))
        assertTrue(conversationBlock.contains("bubble.addView(timestampText)"))
    }

    @Test
    fun `markdown file links open an in app preview dialog`() {
        val source = readSource()

        assertTrue(source.contains("URLSpan"))
        assertTrue(source.contains("ClickableSpan"))
        assertTrue(source.contains("bindMarkdownPreviewLinks("))
        assertTrue(source.contains("installMarkdownPreviewLinkClickHandler(target)"))
        assertTrue(source.contains("showMarkdownFilePreviewDialog("))
        assertTrue(source.contains("viewModel.fetchMarkdownFilePreview("))
        assertTrue(source.contains("R.string.markdown_preview_loading"))
    }

    @Test
    fun `json file links open a monospace preview dialog`() {
        val source = readSource()

        assertTrue(source.contains("Typeface.MONOSPACE"))
        assertTrue(source.contains("MarkdownPreviewSupport.isJsonPreviewPath(preview.path)"))
        assertTrue(source.contains("MarkdownPreviewSupport.formatJsonPreviewContent(preview.content)"))
    }

    @Test
    fun `plain text file links open a selectable monospace preview dialog`() {
        val source = readSource()
        val previewBlock = source.substringAfter("private fun showMarkdownFilePreviewDialog(")
            .substringBefore("private fun formatMarkdownPreviewMeta(")

        assertTrue(previewBlock.contains("MarkdownPreviewSupport.isPlainTextPreviewPath(preview.path)"))
        assertTrue(previewBlock.contains("bodyText.typeface = Typeface.MONOSPACE"))
        assertTrue(previewBlock.contains("bodyText.setTextIsSelectable(true)"))
    }

    @Test
    fun `markdown preview dialog shows metadata and copy actions`() {
        val source = readSource()
        val previewBlock = source.substringAfter("private fun showMarkdownFilePreviewDialog(")
            .substringBefore("private fun roundedConversationBackground(")

        assertTrue(previewBlock.contains("R.string.markdown_preview_full_path"))
        assertTrue(previewBlock.contains("pathText.text ="))
        assertTrue(previewBlock.contains("pathText.setTextIsSelectable(true)"))
        assertTrue(previewBlock.contains("formatMarkdownPreviewMeta("))
        assertTrue(previewBlock.contains("preview.sizeBytes"))
        assertTrue(previewBlock.contains("R.string.markdown_preview_copy_path"))
        assertTrue(previewBlock.contains("R.string.markdown_preview_copy_content"))
        assertTrue(source.contains("ClipboardManager"))
        assertTrue(source.contains("ClipData.newPlainText"))
        assertTrue(source.contains("setPrimaryClip"))
    }

    @Test
    fun `markdown preview failures show gateway error message`() {
        val source = readSource()
        val failureBlock = source.substringAfter(".onFailure {")
            .substringBefore("}\n        }")

        assertTrue(failureBlock.contains("failure.message"))
        assertTrue(failureBlock.contains("markdown_preview_failed_subtitle"))
    }

    @Test
    fun `failed outgoing message bubble shows red retry affordance`() {
        val source = readSource()
        val conversationBlock = source.substringAfter("private fun conversationMessageRow(")
            .substringBefore("private fun conversationMessageMarkdown(")
        val indicatorBlock = source.substringAfter("private fun failedMessageIndicator(")
            .substringBefore("private fun conversationMessageMarkdown(")

        assertTrue(source.contains("ThreadMessageSendState.FAILED"))
        assertTrue(conversationBlock.contains("val isFailedOutgoingMessage ="))
        assertTrue(conversationBlock.contains("retryFailedMessage(message.messageId,"))
        assertTrue(conversationBlock.contains("contentResolver.openInputStream(Uri.parse(draft.previewUri))"))
        assertTrue(conversationBlock.contains("row.addView(failedMessageIndicator(message))"))
        assertTrue(conversationBlock.contains("params.marginStart = if (isFailedOutgoingMessage) 0 else dp(56)"))
        assertTrue(indicatorBlock.contains("LinearLayout.LayoutParams(dp(20), dp(20))"))
        assertTrue(indicatorBlock.contains("params.marginEnd = dp(6)"))
        assertTrue(indicatorBlock.contains("shape = GradientDrawable.OVAL"))
        assertTrue(indicatorBlock.contains("R.color.message_failed_indicator_bg"))
        assertTrue(indicatorBlock.contains("R.color.message_failed_indicator_text"))
        assertTrue(indicatorBlock.contains("text = \"!\""))
        assertTrue(indicatorBlock.contains("R.string.message_send_failed_retry"))
    }

    @Test
    fun `attachment bubbles expose sending progress and failed retry text`() {
        val source = readSource()
        val imageBlock = source.substringAfter("private fun conversationImageGroupView(")
            .substringBefore("private fun conversationFileView(")
        val fileBlock = source.substringAfter("private fun conversationFileView(")
            .substringBefore("private fun conversationMessageMarkdown(")

        assertTrue(source.contains("private fun attachmentSendStateText("))
        assertTrue(source.contains("R.string.attachment_upload_sending"))
        assertTrue(source.contains("R.string.attachment_upload_failed_retry"))
        assertTrue(imageBlock.contains("attachmentSendStateText(imageMessage)"))
        assertTrue(imageBlock.contains("retryFailedMessage(imageMessage.messageId,"))
        assertTrue(fileBlock.contains("attachmentSendStateText(message) ?:"))
        assertTrue(fileBlock.contains("retryFailedMessage(message.messageId,"))
        assertTrue(source.contains("openFileStream = { draft ->"))
    }

    @Test
    fun `assistant conversation messages render as separate left bubbles`() {
        val source = readSource()
        val bindConversationBlock = source.substringAfter("private fun bindDetailConversation(")
            .substringBefore("private fun conversationMessageRow(")
        val conversationBlock = source.substringAfter("private fun conversationMessageRow(")
            .substringBefore("private fun conversationMessageMarkdown(")

        assertTrue(bindConversationBlock.contains("DetailTimelineSupport.conversationItems("))
        assertTrue(bindConversationBlock.contains("visualDetail"))
        assertTrue(bindConversationBlock.contains("items.forEach { item ->"))
        assertTrue(bindConversationBlock.contains("binding.detailConversationList.addView(conversationItemRow(item, detail, typingSpec))"))
        assertTrue(conversationBlock.contains("val isAssistantMessage = message.role == ThreadMessageRole.ASSISTANT"))
        assertTrue(conversationBlock.contains("if (isUserMessage || isAssistantMessage || isSystemMessage)"))
        assertTrue(conversationBlock.contains("R.color.message_assistant_bg"))
        assertTrue(conversationBlock.contains("R.color.message_assistant_stroke"))
        assertTrue(conversationBlock.contains("params.marginEnd = dp(44)"))
    }

    @Test
    fun `detail conversation can expand collapsed execution history`() {
        val source = readSource()
        val strings = File("src/main/res/values/strings.xml").readText()
        val bindConversationBlock = source.substringAfter("private fun bindDetailConversation(")
            .substringBefore("private fun conversationMessageRow(")

        assertTrue(source.contains("private val expandedConversationHistoryKeysByThreadId = mutableMapOf<String, MutableSet<String>>()"))
        assertFalse(source.contains("private var expandedConversationHistoryThreadId: String? = null"))
        assertTrue(source.contains("is DetailConversationItem.HistoryGroup -> conversationHistoryGroupRow(item, detail?.thread?.threadId)"))
        assertTrue(source.contains("private fun conversationHistoryGroupRow("))
        assertTrue(source.contains("R.string.detail_history_processed_collapsed"))
        assertTrue(source.contains("R.string.detail_history_processed_expanded"))
        assertTrue(source.contains("item.processedDurationLabel"))
        assertTrue(source.contains("item.key"))
        assertFalse(source.contains("private fun scrollDetailHistoryControlToTop("))
        assertFalse(source.contains("scrollDetailHistoryControlToTop()"))
        assertFalse(source.contains("R.string.detail_history_collapsed"))
        assertFalse(source.contains("R.string.detail_history_expanded"))
        assertTrue(strings.contains("<string name=\"detail_history_processed_collapsed\">已处理 %1\$s &gt;</string>"))
        assertTrue(strings.contains("<string name=\"detail_history_processed_expanded\">已处理 %1\$s v</string>"))
        assertFalse(strings.contains("上 %1\$d 条消息"))
        assertFalse(strings.contains("收起 %1\$d 条历史消息"))
        assertTrue(bindConversationBlock.contains("DetailTimelineSupport.conversationItems("))
        assertTrue(bindConversationBlock.contains("visualDetail"))
        assertTrue(bindConversationBlock.contains("expandedConversationHistoryKeysFor(detail?.thread?.threadId)"))
        assertTrue(source.contains("expandedConversationHistoryKeys.add(item.key)"))
        assertTrue(source.contains("expandedConversationHistoryKeys.remove(item.key)"))
    }

    @Test
    fun `profile page can export diagnostics bundle without system share`() {
        val source = readSource()
        val setupBlock = source.substringAfter("private fun setupActions()")
            .substringBefore("private fun handleDetailHistoryTouch(")
        val exportBlock = source.substringAfter("private fun exportDiagnosticsBundle()")
            .substringBefore("private fun openSystemNotificationSettings()")
        val saveBlock = source.substringAfter("private fun saveDiagnosticBundleToDownloads(")
            .substringBefore("private fun diagnosticErrorSummary(")

        assertTrue(setupBlock.contains("binding.profileExportDiagnosticsRow.setOnClickListener"))
        assertTrue(setupBlock.contains("exportDiagnosticsBundle()"))
        assertTrue(source.contains("DebugTraceLogger.initialize(applicationContext)"))
        assertTrue(source.contains("private var diagnosticsExportJob: Job? = null"))
        assertTrue(exportBlock.contains("viewModel.fetchGatewayDiagnosticsJson"))
        assertTrue(exportBlock.contains("diagnosticsExportJob?.isActive == true"))
        assertTrue(exportBlock.contains("diagnosticsExportDuplicateIgnored"))
        assertTrue(exportBlock.contains("R.string.profile_export_diagnostics_already_running"))
        assertTrue(exportBlock.contains("finally"))
        assertTrue(exportBlock.contains("diagnosticsExportJob = null"))
        assertTrue(exportBlock.contains("DebugTraceLogger.log(\"diagnostics-export\""))
        assertTrue(exportBlock.contains("createAndSaveDiagnosticBundle("))
        assertTrue(exportBlock.contains("gatewayDiagnosticsFailureJson(result.error)"))
        assertTrue(exportBlock.contains("DiagnosticBundleWriter.create("))
        assertTrue(exportBlock.contains("saveDiagnosticBundleToDownloads(zipFile)"))
        assertTrue(exportBlock.contains("diagnosticErrorSummary(error)"))
        assertTrue(saveBlock.contains("DebugTraceLogger.log(\"diagnostics-export\""))
        assertTrue(saveBlock.contains("diagnosticErrorSummary(error)"))
        assertFalse(exportBlock.contains("FileProvider.getUriForFile"))
        assertFalse(exportBlock.contains("Intent.ACTION_SEND"))
        assertFalse(exportBlock.contains("Intent.createChooser"))
    }

    @Test
    fun `detail body and conversation messages render markdown content`() {
        val source = readSource()

        assertTrue(source.contains("private val markwon by lazy { Markwon.create(this) }"))
        assertTrue(source.contains("private fun renderMarkdown("))
        assertTrue(source.contains("markwon.setMarkdown(target, markdown)"))
        assertTrue(source.contains("renderMarkdown(binding.detailBodyText, visualContent.bodyMarkdown)"))
        assertTrue(source.contains("val markdown = if (imageMessages.isEmpty()) conversationMessageMarkdown(message) else \"\""))
        assertTrue(source.contains("renderConversationMarkdown(text, markdown, message.threadId)"))
    }

    @Test
    fun `detail conversation message text keeps history scroll gestures available`() {
        val source = readSource()
        val conversationBlock = source.substringAfter("private fun conversationMessageRow(")
            .substringBefore("private fun conversationMessageMarkdown(")
        val renderBlock = source.substringAfter("private fun renderConversationMarkdown(")
            .substringBefore("private fun roundedConversationBackground(")

        assertTrue(conversationBlock.contains("renderConversationMarkdown(text, markdown, message.threadId)"))
        assertTrue(renderBlock.contains("target.setTextIsSelectable(false)"))
        assertTrue(renderBlock.contains("installConversationMessageCopyAction(target)"))
        assertFalse(renderBlock.contains("target.setTextIsSelectable(true)"))
        assertFalse(renderBlock.contains("LinkMovementMethod.getInstance()"))
        assertTrue(source.contains("private fun installMarkdownPreviewLinkClickHandler("))
        assertTrue(source.contains("private fun clickableSpanAt("))
    }

    @Test
    fun `detail image messages can open a large preview`() {
        val source = readSource()
        val conversationBlock = source.substringAfter("private fun conversationMessageRow(")
            .substringBefore("private fun conversationMessageMarkdown(")

        assertTrue(source.contains("private fun showMessageImagePreviewDialog("))
        assertTrue(source.contains("private fun conversationImageGroupView("))
        assertTrue(source.contains("showMessageImagePreviewDialog(imageMessage)"))
        assertTrue(conversationBlock.contains("isClickable = true"))
        assertTrue(conversationBlock.contains("isFocusable = true"))
        assertTrue(conversationBlock.contains("if (markdown.isNotBlank())"))
        assertTrue(source.contains("message.kind == \"image\" && !message.imageUrl.isNullOrBlank()"))
    }

    @Test
    fun `file changes card hides when unavailable and toggles collapsed list`() {
        val source = readSource()

        assertTrue(source.contains("visualContent.showFileChanges"))
        assertTrue(source.contains("binding.detailFileChangesCard.visibility ="))
        assertTrue(source.contains("private fun syncFileChangesCard("))
        assertTrue(source.contains("binding.detailFileChangesCard.setOnClickListener"))
        assertTrue(source.contains("binding.detailFileChangesList.visibility = if (fileChangesExpanded) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("binding.detailFileChangesToggleIcon.rotation = if (fileChangesExpanded) 180f else 0f"))
    }

    @Test
    fun `file changes summary styles diff counts and expanded rows omit navigation chevrons`() {
        val source = readSource()
        val fileChangesBlock = source.substringAfter("private fun bindDetailFileChanges(")
            .substringBefore("private fun syncFileChangesCard(")

        assertTrue(source.contains("private fun styledFileSummary("))
        assertTrue(source.contains("ForegroundColorSpan"))
        assertFalse(fileChangesBlock.contains("ic_app_chevron_right"))
    }

    @Test
    fun `detail composer binds runtime state from thread detail`() {
        val source = readSource()

        assertTrue(source.contains("private fun bindComposerRuntimeState("))
        assertTrue(source.contains("detail?.composerState?.permissionLabel"))
        assertTrue(source.contains("detail?.composerState?.modelLabel"))
    }

    @Test
    fun `setup window insets applies status bar padding to detail page`() {
        val source = readSource()

        assertTrue(source.contains("val initialDetailTopPadding = binding.detailPage.paddingTop"))
        assertTrue(source.contains("binding.detailPage.updatePadding("))
        assertTrue(source.contains("top = initialDetailTopPadding + statusBarInsets.top"))
    }

    @Test
    fun `setup window insets keeps bottom navigation above system navigation bar`() {
        val source = readSource()

        assertTrue(source.contains("val initialBottomNavHeight = binding.bottomNavCard.layoutParams.height"))
        assertTrue(source.contains("val initialBottomNavItemsBottomPadding = binding.bottomNavItems.paddingBottom"))
        assertTrue(source.contains("binding.bottomNavCard.updateLayoutParams"))
        assertTrue(source.contains("height = initialBottomNavHeight + navigationBarInsets.bottom"))
        assertTrue(source.contains("binding.bottomNavItems.updatePadding("))
        assertTrue(source.contains("bottom = initialBottomNavItemsBottomPadding + navigationBarInsets.bottom"))
    }

    @Test
    fun `task refresh button triggers manual thread list refresh`() {
        val source = readSource()

        assertTrue(source.contains("binding.taskRefreshButton.setOnClickListener"))
        assertTrue(source.contains("taskRefreshSuccessToastPending = true"))
        assertTrue(source.contains("showTaskRefreshTapFeedback()"))
        assertTrue(source.contains("viewModel.refreshThreads()"))
        assertFalse(source.contains("showTaskRefreshStartedToast()"))
    }

    @Test
    fun `task mark all read button clears settled status dots`() {
        val source = readSource()

        assertTrue(source.contains("binding.taskMarkAllReadButton.setOnClickListener"))
        assertTrue(source.contains("private fun markAllThreadStatusIndicatorsRead()"))
        assertTrue(source.contains("ThreadStatusIndicatorSupport.settledReadKey(thread)"))
        assertTrue(source.contains("GatewayPreferences.saveReadThreadStatusIndicatorKeys(this, readThreadStatusIndicatorKeys)"))
        assertTrue(source.contains("threadAdapter.submitThreads(viewModel.state.value.threads, readThreadStatusIndicatorKeys)"))
        assertTrue(source.contains("Toast.makeText(this, R.string.task_mark_all_read_success, Toast.LENGTH_SHORT).show()"))
    }

    @Test
    fun `detail refresh button incrementally refreshes the selected thread detail`() {
        val source = readSource()
        val refreshBlock = source.substringAfter("private fun refreshCurrentDetail()")
            .substringBefore("private fun requestDetailScrollToBottom()")

        assertTrue(source.contains("binding.detailRefreshButton.setOnClickListener"))
        assertTrue(source.contains("showDetailRefreshTapFeedback()"))
        assertTrue(source.contains("detailRefreshSuccessToastPending = true"))
        assertTrue(source.contains("refreshCurrentDetail()"))
        assertTrue(source.contains("private fun refreshCurrentDetail()"))
        assertTrue(refreshBlock.contains("requestDetailScrollToBottom()"))
        assertTrue(refreshBlock.contains("viewModel.refreshThreadDetail(threadId) { result ->"))
        assertTrue(refreshBlock.contains("showDetailRefreshResultToast(result)"))
    }

    @Test
    fun `detail page exposes jump to bottom after scrolling upward`() {
        val source = readSource()
        val jumpBlock = source.substringAfter("private fun syncDetailJumpToBottomButton(")
            .substringBefore("private fun observeState(")

        assertTrue(source.contains("DETAIL_JUMP_TO_BOTTOM_THRESHOLD_DP"))
        assertTrue(source.contains("DETAIL_JUMP_TO_BOTTOM_THRESHOLD_DP = 64"))
        assertTrue(source.contains("binding.detailJumpToBottomButton.setOnClickListener"))
        assertTrue(source.contains("requestDetailScrollToBottom()"))
        assertTrue(source.contains("scrollDetailToBottomWithoutFocusChange()"))
        assertTrue(source.contains("binding.detailScroll.scrollTo(0, maxScrollY)"))
        assertFalse(source.contains("binding.detailScroll.fullScroll(View.FOCUS_DOWN)"))
        assertTrue(source.contains("syncDetailJumpToBottomButton(scrollY)"))
        assertTrue(source.contains("syncDetailJumpToBottomButton(binding.detailScroll.scrollY)"))
        assertTrue(jumpBlock.contains("val distanceFromBottom ="))
        assertTrue(jumpBlock.contains("distanceFromBottom > dp(DETAIL_JUMP_TO_BOTTOM_THRESHOLD_DP)"))
        assertTrue(jumpBlock.contains("button.visibility = View.VISIBLE"))
        assertTrue(jumpBlock.contains("button.visibility = View.GONE"))
        assertTrue(jumpBlock.contains("if (shouldShow) {"))
        assertTrue(jumpBlock.contains("if (button.visibility == View.VISIBLE)"))
        assertTrue(jumpBlock.contains("button.alpha = 1f"))
        assertTrue(jumpBlock.contains("button.scaleX = 1f"))
        assertTrue(jumpBlock.contains("button.scaleY = 1f"))
    }

    @Test
    fun `switching to detail page recomputes jump to bottom visibility`() {
        val source = readSource()
        val renderDetailBlock = source.substringAfter("private fun renderDetailContent(")
            .substringBefore("private fun animateScreenTransition(")
        val syncAfterLayoutBlock = source.substringAfter("private fun syncDetailJumpToBottomButtonAfterLayout(")
            .substringBefore("private fun observeState(")

        assertTrue(renderDetailBlock.contains("if (shellState.showDetailPage) {"))
        assertTrue(renderDetailBlock.contains("syncDetailJumpToBottomButtonAfterLayout()"))
        assertTrue(syncAfterLayoutBlock.contains("binding.detailScroll.post"))
        assertTrue(syncAfterLayoutBlock.contains("syncDetailJumpToBottomButton(binding.detailScroll.scrollY)"))
    }

    @Test
    fun `reentering already rendered loaded detail preserves scroll`() {
        val source = readSource()
        val openDetailBlock = source.substringAfter("private fun openThreadDetail(threadId: String) {")
            .substringBefore("private fun refreshCurrentDetail()")

        assertTrue(openDetailBlock.contains("val isSameLoadedThread ="))
        assertTrue(openDetailBlock.contains("val shouldRestoreInitialBottom ="))
        assertTrue(openDetailBlock.contains("lastRenderedDetailThreadId != threadId"))
        assertTrue(openDetailBlock.contains("if (!isSameLoadedThread) {"))
        assertTrue(openDetailBlock.contains("requestDetailScrollToBottom()"))
        assertTrue(openDetailBlock.contains("syncDetailJumpToBottomButtonAfterLayout()"))
        assertFalse(openDetailBlock.trimStart().startsWith("currentScreen = ConnectedScreen.DETAIL\n        requestDetailScrollToBottom()"))
    }

    @Test
    fun `opening cached detail for the first time in an activity restores bottom`() {
        val source = readSource()
        val openDetailBlock = source.substringAfter("private fun openThreadDetail(threadId: String) {")
            .substringBefore("private fun refreshCurrentDetail()")

        assertTrue(source.contains("private var lastRenderedDetailThreadId: String? = null"))
        assertTrue(openDetailBlock.contains("val shouldRestoreInitialBottom ="))
        assertTrue(openDetailBlock.contains("isSameLoadedThread && lastRenderedDetailThreadId != threadId"))
        assertTrue(openDetailBlock.contains("if (shouldRestoreInitialBottom) {"))
        assertTrue(openDetailBlock.contains("scrollDetailToBottomAfterLayout()"))
        assertTrue(openDetailBlock.contains("lastRenderedDetailThreadId = threadId"))
    }

    @Test
    fun `detail history loads when first page is shorter than the viewport`() {
        val source = readSource()
        val maybeLoadBlock = source.substringAfter("private fun maybeLoadOlderMessages(")
            .substringBefore("private fun requestOlderMessagesPage(")
        val underfilledBlock = source.substringAfter("private fun maybeLoadOlderMessagesWhenUnderfilled(")
            .substringBefore("private fun syncDetailJumpToBottomButton(")
        val touchBlock = source.substringAfter("private fun handleDetailHistoryTouch(")
            .substringBefore("private fun maybeLoadOlderMessages(")

        assertTrue(source.contains("private fun requestOlderMessagesPage()"))
        assertTrue(source.contains("private fun maybeLoadOlderMessagesWhenUnderfilled("))
        assertTrue(source.contains("private fun handleDetailHistoryTouch("))
        assertTrue(source.contains("override fun dispatchTouchEvent(event: MotionEvent): Boolean"))
        assertTrue(source.contains("private fun isTouchInsideDetailScroll(event: MotionEvent): Boolean"))
        assertTrue(source.contains("maybeLoadOlderMessagesWhenUnderfilled(state)"))
        assertTrue(maybeLoadBlock.contains("requestOlderMessagesPage()"))
        assertFalse(maybeLoadBlock.contains("oldScrollY <= scrollY"))
        assertTrue(underfilledBlock.contains("binding.detailScroll.post"))
        assertTrue(underfilledBlock.contains("binding.detailScrollContent.height <= binding.detailScroll.height + dp(8)"))
        assertTrue(underfilledBlock.contains("requestOlderMessagesPage()"))
        assertTrue(source.contains("DETAIL_PULL_TO_LOAD_THRESHOLD_DP"))
        assertFalse(source.contains("private fun attachDetailHistoryTouchLoader("))
        assertFalse(source.contains("attachDetailHistoryTouchLoader("))
        assertTrue(source.contains("isTouchInsideDetailScroll(event)"))
        assertTrue(touchBlock.contains("MotionEvent.ACTION_MOVE"))
        assertTrue(touchBlock.contains("binding.detailScroll.scrollY <= dp(8)"))
        assertTrue(touchBlock.contains("requestOlderMessagesPage()"))
    }

    @Test
    fun `older message loading restores scroll from captured anchor`() {
        val source = readSource()
        val requestBlock = source.substringAfter("private fun requestOlderMessagesPage()")
            .substringBefore("private fun maybeLoadOlderMessagesWhenUnderfilled(")
        val syncBlock = source.substringAfter("private fun syncOlderMessagesLoading(")
            .substringBefore("private fun showTaskRefreshTapFeedback(")

        assertTrue(source.contains("private data class OlderMessagesScrollAnchor("))
        assertTrue(source.contains("private var pendingOlderMessagesScrollAnchor: OlderMessagesScrollAnchor? = null"))
        assertTrue(requestBlock.contains("contentHeight = binding.detailScrollContent.height"))
        assertTrue(requestBlock.contains("scrollY = binding.detailScroll.scrollY"))
        assertTrue(syncBlock.contains("val delta = binding.detailScrollContent.height - anchor.contentHeight"))
        assertTrue(syncBlock.contains("binding.detailScroll.scrollTo(0, anchor.scrollY + delta)"))
        assertFalse(source.contains("pendingOlderMessagesScrollHeight"))
        assertFalse(syncBlock.contains("binding.detailScroll.scrollTo(0, delta)"))
    }

    @Test
    fun `detail history scroll diagnostics capture touch load and anchor evidence`() {
        val source = readSource()
        val touchBlock = source.substringAfter("private fun handleDetailHistoryTouch(")
            .substringBefore("private fun showComposerKeyboard(")
        val scrollBlock = source.substringAfter("binding.detailScroll.setOnScrollChangeListener")
            .substringBefore("binding.sendButton.setOnClickListener")
        val requestBlock = source.substringAfter("private fun requestOlderMessagesPage()")
            .substringBefore("private fun maybeLoadOlderMessagesWhenUnderfilled(")
        val syncBlock = source.substringAfter("private fun syncOlderMessagesLoading(")
            .substringBefore("private fun showTaskRefreshTapFeedback(")

        assertTrue(source.contains("private fun logDetailScrollState("))
        assertTrue(source.contains("DebugTraceLogger.log(\"detail-scroll\""))
        assertTrue(source.contains("private fun detailScrollDiagnosticState("))
        assertTrue(source.contains("private fun shouldLogDetailScroll("))
        assertTrue(scrollBlock.contains("\"detail.scroll\""))
        assertTrue(touchBlock.contains("\"detail.touch.down\""))
        assertTrue(touchBlock.contains("\"detail.touch.threshold\""))
        assertTrue(requestBlock.contains("\"older.request.start\""))
        assertTrue(requestBlock.contains("\"older.request.skip\""))
        assertTrue(source.contains("\"older.underfilled.check\""))
        assertTrue(syncBlock.contains("\"older.sync.done\""))
    }

    @Test
    fun `history expansion does not force the whole detail page to the top`() {
        val source = readSource()
        val historyBlock = source.substringAfter("private fun conversationHistoryGroupRow(")
            .substringBefore("private fun conversationMessageRow(")

        assertTrue(historyBlock.contains("bindDetailConversation(viewModel.state.value.detail)"))
        assertFalse(source.contains("private fun scrollDetailHistoryControlToTop("))
        assertFalse(source.contains("private fun scrollDetailConversationToTop("))
        assertFalse(source.contains("binding.detailScroll.scrollTo(0, 0)"))
        assertFalse(historyBlock.contains("scrollDetailHistoryControlToTop()"))
    }

    @Test
    fun `detail alerts refresh active opted in threads while worker owns system notifications`() {
        val source = readSource()
        val workerSource = readAlertsWorkerSource()
        val alertBlock = source.substringAfter("private fun maybeShowDetailNewMessageAlert(")
            .substringBefore("private fun latestDetailMessage(")
        val observeStateBlock = source.substringAfter("private fun observeState()")
            .substringBefore("private fun observeRealtimeNotifications()")
        val observeRealtimeNotificationsBlock = source.substringAfter("private fun observeRealtimeNotifications()")
            .substringBefore("private fun syncErrorToast(")
        val realtimeNotificationBlock = source.substringAfter("private fun showPendingRealtimeNotifications(")
            .substringBefore("private fun showServerRealtimeNotification(")
        val renderBlock = source.substringAfter("syncDetailAlertUi(composerThreadId, state.isConnected && !isPendingDetail)")
            .substringBefore("bindComposerRuntimeState(visualDetail)")
        val scheduleBlock = source.substringAfter("private fun scheduleDetailAlertRefresh()")
            .substringBefore("private fun cancelDetailAlertRefresh()")

        assertTrue(source.contains("private val alertEnabledThreadIds = mutableSetOf<String>()"))
        assertTrue(source.contains("private val alertMessageEnabledThreadIds = mutableSetOf<String>()"))
        assertTrue(source.contains("private val alertCompletionEnabledThreadIds = mutableSetOf<String>()"))
        assertTrue(source.contains("private val lastAlertMessageKeyByThreadId = mutableMapOf<String, String?>()"))
        assertTrue(source.contains("private val lastCompletionAlertKeyByThreadId = mutableMapOf<String, String?>()"))
        assertFalse(source.contains("lastAlertMessageIdByThreadId"))
        assertTrue(source.contains("private var detailAlertRefreshJob: Job? = null"))
        assertTrue(source.contains("private var pendingAlertPermissionThreadId: String? = null"))
        assertTrue(source.contains("GatewayPreferences.loadAlertEnabledThreadIds(this)"))
        assertTrue(source.contains("GatewayPreferences.loadAlertMessageEnabledThreadIds(this)"))
        assertTrue(source.contains("GatewayPreferences.loadAlertCompletionEnabledThreadIds(this)"))
        assertTrue(source.contains("GatewayPreferences.saveAlertEnabledThreadIds(this, alertEnabledThreadIds)"))
        assertTrue(source.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(source.contains("Manifest.permission.POST_NOTIFICATIONS"))
        assertTrue(source.contains("observeRealtimeNotifications()"))
        assertTrue(source.contains("private fun observeRealtimeNotifications()"))
        assertTrue(observeRealtimeNotificationsBlock.contains("viewModel.state"))
        assertTrue(observeRealtimeNotificationsBlock.contains("map { it.pendingRealtimeNotifications }"))
        assertTrue(observeRealtimeNotificationsBlock.contains("distinctUntilChanged()"))
        assertTrue(observeRealtimeNotificationsBlock.contains("collect { notifications ->"))
        assertFalse(observeStateBlock.contains("showPendingRealtimeNotifications(state)"))
        assertTrue(source.contains("DETAIL_ALERT_REFRESH_INTERVAL_MS = 10_000L"))
        assertTrue(source.contains("DetailNotificationIds.ALERT_CHANNEL_ID"))
        assertTrue(source.contains("DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID"))
        assertTrue(source.contains("binding.detailAlertButton.setOnClickListener"))
        assertTrue(source.contains("private fun showDetailAlertSettingsDialog()"))
        assertTrue(source.contains("private fun applyDetailAlertSettings("))
        assertTrue(source.contains("private fun enableDetailAlertForThread(threadId: String)"))
        assertTrue(source.contains("private fun scheduleDetailAlertRefresh()"))
        assertTrue(source.contains("private fun cancelDetailAlertRefresh()"))
        assertTrue(source.contains("val threadIds = alertEnabledThreadIds.toList()"))
        assertTrue(source.contains("delay(DETAIL_ALERT_REFRESH_INTERVAL_MS)"))
        assertTrue(source.contains("viewModel.refreshThreadForAlert(threadId)"))
        assertFalse(scheduleBlock.contains("maybeShowDetailNewMessageAlert(refreshedDetail)"))
        assertFalse(scheduleBlock.contains("maybeShowDetailCompletionAlert(refreshedDetail)"))
        assertFalse(renderBlock.contains("maybeShowDetailCompletionAlert(visualDetail)"))
        assertTrue(source.contains("private fun maybeShowDetailNewMessageAlert("))
        assertTrue(source.contains("private fun maybeShowDetailCompletionAlert("))
        assertTrue(source.contains("private fun detailAlertMessageKey("))
        assertTrue(source.contains("private fun detailCompletionAlertKey("))
        assertTrue(source.contains("message.timestamp"))
        assertFalse(source.contains("private fun showDetailSystemNotification("))
        assertFalse(source.contains("private fun showDetailCompletionNotification("))
        assertFalse(alertBlock.contains("NotificationManagerCompat.from(this)"))
        assertTrue(source.contains("private fun showServerRealtimeNotification(notification: GatewayRealtimeEvent.Notification): Boolean"))
        assertTrue(realtimeNotificationBlock.contains("if (showServerRealtimeNotification(notification))"))
        assertTrue(realtimeNotificationBlock.contains("viewModel.markRealtimeNotificationDisplayed(notification.notificationId)"))
        assertTrue(source.contains("DetailNotificationSupport.showThreadNotification("))
        assertTrue(source.contains("DetailNotificationSupport.refreshSummaryNotification("))
        assertFalse(source.contains("private fun vibrateForWaitingInputAlertIfNeeded("))
        assertTrue(source.contains("R.string.detail_completion_alert_channel_name"))
        assertTrue(source.contains("R.string.detail_completion_alert_channel_description"))
        assertTrue(workerSource.contains("R.string.detail_completion_alert_body"))
        assertTrue(workerSource.contains("GatewayPreferences.loadAlertMessageEnabledThreadIds(applicationContext)"))
        assertTrue(workerSource.contains("GatewayPreferences.loadAlertCompletionEnabledThreadIds(applicationContext)"))
        assertTrue(
            source.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
                .substringBefore("val requestedThreadId =")
                .contains("createDetailNotificationChannel()")
        )
        val onCreateBlock = source.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("override fun onNewIntent(")
        assertTrue(onCreateBlock.contains("if (alertEnabledThreadIds.isNotEmpty())"))
        assertTrue(onCreateBlock.contains("AlertPollingScheduler.schedule(this)"))
        assertFalse(alertBlock.contains("MaterialAlertDialogBuilder(this)"))
        assertFalse(alertBlock.contains("latestMessage?.messageId"))
    }

    @Test
    fun `detail alert bell opens unified notification settings with separate switches`() {
        val source = readSource()
        val dialogSource = readDetailAlertSettingsDialogSupportSource()
        val strings = readStrings()
        val prefsSource = readGatewayPreferencesSource()
        val setupBlock = source.substringAfter("private fun setupActions()")
            .substringBefore("private fun handleDetailHistoryTouch(")
        val applyBlock = source.substringAfter("private fun applyDetailAlertSettings(")
            .substringBefore("private fun enableDetailAlertForThread(")
        val realtimeNotificationBlock = source.substringAfter("private fun showServerRealtimeNotification(")
            .substringBefore("private fun resolveNotificationThreadTitle(")
        val workerSource = readAlertsWorkerSource()

        assertTrue(strings.contains("<string name=\"detail_alert_settings_title\">通知提醒</string>"))
        assertTrue(strings.contains("<string name=\"detail_alert_new_message_title\">新消息提醒</string>"))
        assertTrue(strings.contains("<string name=\"detail_alert_completion_title\">结束提醒</string>"))
        assertTrue(strings.contains("<string name=\"detail_alert_settings_confirm\">确认</string>"))
        assertTrue(strings.contains("<string name=\"detail_alert_settings_cancel\">取消</string>"))
        assertTrue(setupBlock.contains("showDetailAlertSettingsDialog()"))
        assertFalse(setupBlock.contains("toggleDetailAlertForCurrentThread()"))
        assertTrue(source.contains("DetailAlertSettingsDialogSupport.show("))
        assertTrue(dialogSource.contains("Dialog(context)"))
        assertTrue(dialogSource.contains("UnifiedDialogSupport.panel("))
        assertTrue(dialogSource.contains("R.string.detail_alert_settings_title"))
        assertTrue(dialogSource.contains("R.string.detail_alert_new_message_title"))
        assertTrue(dialogSource.contains("R.string.detail_alert_completion_title"))
        assertFalse(source.contains("import androidx.appcompat.widget.SwitchCompat"))
        assertFalse(dialogSource.contains("SwitchCompat(context).apply"))
        assertTrue(dialogSource.contains("UnifiedDialogSupport.pillSwitch("))
        assertTrue(dialogSource.contains("checked = selected"))
        assertTrue(dialogSource.contains("trailingView = UnifiedDialogSupport.pillSwitch("))
        assertFalse(dialogSource.contains("badgeText = context.getString("))
        assertTrue(dialogSource.contains("selectedMessageEnabled = !selectedMessageEnabled"))
        assertTrue(dialogSource.contains("selectedCompletionEnabled = !selectedCompletionEnabled"))
        assertTrue(dialogSource.contains("onConfirm("))
        assertTrue(applyBlock.contains("GatewayPreferences.saveAlertEnabledThreadIds(this, alertEnabledThreadIds)"))
        assertTrue(applyBlock.contains("GatewayPreferences.saveAlertMessageEnabledThreadIds(this, alertMessageEnabledThreadIds)"))
        assertTrue(applyBlock.contains("GatewayPreferences.saveAlertCompletionEnabledThreadIds(this, alertCompletionEnabledThreadIds)"))
        assertTrue(applyBlock.contains("alertMessageEnabledThreadIds.add(threadId)"))
        assertTrue(applyBlock.contains("alertCompletionEnabledThreadIds.add(threadId)"))
        assertTrue(realtimeNotificationBlock.contains("if (isCompletionNotification && notification.threadId !in alertCompletionEnabledThreadIds)"))
        assertTrue(realtimeNotificationBlock.contains("if (!isCompletionNotification && notification.threadId !in alertMessageEnabledThreadIds)"))
        assertTrue(workerSource.contains("if (threadId !in messageEnabledThreadIds)"))
        assertTrue(workerSource.contains("if (threadId !in completionEnabledThreadIds)"))
        assertTrue(prefsSource.contains("KEY_ALERT_MESSAGE_ENABLED_THREAD_IDS"))
        assertTrue(prefsSource.contains("KEY_ALERT_COMPLETION_ENABLED_THREAD_IDS"))
        assertTrue(prefsSource.contains("fun loadAlertMessageEnabledThreadIds("))
        assertTrue(prefsSource.contains("fun saveAlertMessageEnabledThreadIds("))
        assertTrue(prefsSource.contains("fun loadAlertCompletionEnabledThreadIds("))
        assertTrue(prefsSource.contains("fun saveAlertCompletionEnabledThreadIds("))
    }

    @Test
    fun `profile notification settings manage all thread notification types`() {
        val source = readSource()
        val dialogSource = readThreadNotificationSettingsDialogSupportSource()
        val strings = readStrings()
        val setupBlock = source.substringAfter("private fun setupActions()")
            .substringBefore("private fun handleDetailHistoryTouch(")
        val applyBlock = source.substringAfter("private fun applyGlobalThreadNotificationSettings(")
            .substringBefore("private fun rebuildAlertEnabledThreadIds()")

        assertTrue(strings.contains("<string name=\"profile_notifications_title\">线程通知</string>"))
        assertTrue(strings.contains("<string name=\"profile_notifications_subtitle\">统一管理所有线程提醒</string>"))
        assertTrue(strings.contains("<string name=\"thread_notifications_settings_title\">线程通知</string>"))
        assertTrue(strings.contains("<string name=\"thread_notifications_message_off_subtitle\">当前没有线程开启，进入详情页可单独开启</string>"))
        assertTrue(strings.contains("<string name=\"thread_notifications_completion_off_subtitle\">当前没有线程开启，进入详情页可单独开启</string>"))
        assertTrue(setupBlock.contains("binding.profileNotificationsRow.setOnClickListener"))
        assertTrue(setupBlock.contains("showGlobalThreadNotificationSettingsDialog()"))
        assertFalse(setupBlock.contains("openNotificationSettings()"))
        assertTrue(source.contains("private fun showGlobalThreadNotificationSettingsDialog()"))
        assertTrue(source.contains("ThreadNotificationSettingsDialogSupport.show("))
        assertTrue(dialogSource.contains("Dialog(context)"))
        assertTrue(dialogSource.contains("UnifiedDialogSupport.panel("))
        assertTrue(dialogSource.contains("R.string.thread_notifications_settings_title"))
        assertTrue(dialogSource.contains("R.string.detail_alert_new_message_title"))
        assertTrue(dialogSource.contains("R.string.detail_alert_completion_title"))
        assertTrue(dialogSource.contains("UnifiedDialogSupport.pillSwitch("))
        assertTrue(dialogSource.contains("messageThreadCount"))
        assertTrue(dialogSource.contains("completionThreadCount"))
        assertTrue(source.contains("private fun applyGlobalThreadNotificationSettings("))
        assertTrue(applyBlock.contains("if (!messageEnabled)"))
        assertTrue(applyBlock.contains("alertMessageEnabledThreadIds.clear()"))
        assertTrue(applyBlock.contains("lastAlertMessageKeyByThreadId.clear()"))
        assertTrue(applyBlock.contains("notifiedAlertMessageKeysByThreadId.clear()"))
        assertTrue(applyBlock.contains("if (!completionEnabled)"))
        assertTrue(applyBlock.contains("alertCompletionEnabledThreadIds.clear()"))
        assertTrue(applyBlock.contains("lastCompletionAlertKeyByThreadId.clear()"))
        assertTrue(applyBlock.contains("rebuildAlertEnabledThreadIds()"))
        assertTrue(applyBlock.contains("GatewayPreferences.saveAlertEnabledThreadIds(this, alertEnabledThreadIds)"))
        assertTrue(applyBlock.contains("GatewayPreferences.saveAlertMessageEnabledThreadIds(this, alertMessageEnabledThreadIds)"))
        assertTrue(applyBlock.contains("GatewayPreferences.saveAlertCompletionEnabledThreadIds(this, alertCompletionEnabledThreadIds)"))
        assertTrue(applyBlock.contains("saveDetailAlertBaselines()"))
        assertTrue(applyBlock.contains("viewModel.updateRealtimeNotificationPreferences(alertEnabledThreadIds)"))
        assertTrue(applyBlock.contains("AlertPollingScheduler.cancel(this)"))
        assertTrue(applyBlock.contains("scheduleDetailAlertRefresh()"))
        assertTrue(source.contains("private fun rebuildAlertEnabledThreadIds()"))
        assertTrue(source.contains("alertEnabledThreadIds.addAll(alertMessageEnabledThreadIds)"))
        assertTrue(source.contains("alertEnabledThreadIds.addAll(alertCompletionEnabledThreadIds)"))
    }

    @Test
    fun `profile notification settings can manage individual monitored threads`() {
        val source = readSource()
        val dialogSource = readThreadNotificationSettingsDialogSupportSource()
        val strings = readStrings()

        assertTrue(strings.contains("<string name=\"thread_notifications_manage_thread\">管理线程</string>"))
        assertTrue(strings.contains("<string name=\"thread_notifications_thread_disabled\">已关闭该线程提醒</string>"))
        assertTrue(dialogSource.contains("data class ThreadNotificationSettingsThread("))
        assertTrue(dialogSource.contains("messageEnabledThreadIds"))
        assertTrue(dialogSource.contains("completionEnabledThreadIds"))
        assertTrue(dialogSource.contains("showManagedThreadList("))
        assertTrue(dialogSource.contains("onThreadSelectionChanged("))
        assertTrue(source.contains("private fun notificationSettingsThreads()"))
        assertTrue(source.contains("private fun applyThreadNotificationSelection("))
        assertTrue(source.contains("threadItems = notificationSettingsThreads()"))
        assertTrue(source.contains("onThreadSelectionChanged = ::applyThreadNotificationSelection"))
    }

    @Test
    fun `profile notification managed thread rows only close from close button`() {
        val dialogSource = readThreadNotificationSettingsDialogSupportSource()
        val managedListBlock = dialogSource.substringAfter("private fun showManagedThreadList(")
            .substringBefore("private fun actionPill(")

        assertTrue(managedListBlock.contains("trailingView = actionPill("))
        assertTrue(managedListBlock.contains("clickable = false"))
        assertFalse(
            managedListBlock.contains(
                """
                                            dp = dp
                                        ) {
                                            onDisableThread(thread.threadId)
                                            render()
                                        }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `task list settled status dot is marked read when opening thread`() {
        val source = readSource()
        val prefsSource = readGatewayPreferencesSource()
        val clickBlock = source.substringAfter("threadAdapter = ThreadListAdapter { thread ->")
            .substringBefore("openThreadDetail(thread.threadId)")
        val submitBlock = source.substringAfter("private fun submitThreadsWithTaskDiagnostics(")
            .substringBefore("private fun taskListDiagnosticSignature(")

        assertTrue(source.contains("private val readThreadStatusIndicatorKeys = mutableMapOf<String, String?>()"))
        assertTrue(source.contains("readThreadStatusIndicatorKeys.putAll(GatewayPreferences.loadReadThreadStatusIndicatorKeys(this))"))
        assertTrue(source.contains("private fun markThreadStatusIndicatorRead(thread: ThreadListItem)"))
        assertTrue(clickBlock.contains("markThreadStatusIndicatorRead(thread)"))
        assertTrue(submitBlock.contains("threadAdapter.submitThreads(state.threads, readThreadStatusIndicatorKeys)"))
        assertTrue(prefsSource.contains("KEY_READ_THREAD_STATUS_INDICATOR_KEYS"))
        assertTrue(prefsSource.contains("fun loadReadThreadStatusIndicatorKeys(context: Context): Map<String, String?>"))
        assertTrue(prefsSource.contains("fun saveReadThreadStatusIndicatorKeys("))
        assertTrue(prefsSource.contains(".remove(KEY_READ_THREAD_STATUS_INDICATOR_KEYS)"))
    }

    @Test
    fun `realtime notifications are consumed incrementally instead of rescanning the whole pending list`() {
        val source = readSource()
        val realtimeNotificationBlock = source.substringAfter("private fun showPendingRealtimeNotifications(")
            .substringBefore("private fun showServerRealtimeNotification(")

        assertTrue(source.contains("private val handledRealtimeNotificationIds = mutableSetOf<String>()"))
        assertTrue(realtimeNotificationBlock.contains("val currentNotificationIds = notifications.map"))
        assertTrue(realtimeNotificationBlock.contains("handledRealtimeNotificationIds.retainAll(currentNotificationIds)"))
        assertTrue(realtimeNotificationBlock.contains("if (notification.notificationId in handledRealtimeNotificationIds)"))
        assertTrue(realtimeNotificationBlock.contains("if (showServerRealtimeNotification(notification))"))
        assertTrue(realtimeNotificationBlock.contains("handledRealtimeNotificationIds.add(notification.notificationId)"))
        assertTrue(realtimeNotificationBlock.indexOf("if (showServerRealtimeNotification(notification))") < realtimeNotificationBlock.indexOf("handledRealtimeNotificationIds.add(notification.notificationId)"))
        assertFalse(realtimeNotificationBlock.contains("notifications.filter { notification ->"))
        assertFalse(realtimeNotificationBlock.contains("notifications.forEach { notification ->"))
    }

    @Test
    fun `detail alerts keep polling enabled threads outside detail screen`() {
        val source = readSource()
        val scheduleBlock = source.substringAfter("private fun scheduleDetailAlertRefresh()")
            .substringBefore("private fun cancelDetailAlertRefresh()")
        val newMessageBlock = source.substringAfter("private fun maybeShowDetailNewMessageAlert(")
            .substringBefore("private fun maybeShowDetailCompletionAlert(")
        val completionBlock = source.substringAfter("private fun maybeShowDetailCompletionAlert(")
            .substringBefore("private fun latestDetailMessage(")
        val syncBlock = source.substringAfter("private fun syncDetailAlertUi(")
            .substringBefore("private fun scheduleDetailAlertRefresh()")

        assertTrue(source.contains("override fun onStop()"))
        assertTrue(source.contains("cancelDetailAlertRefresh()"))
        assertTrue(scheduleBlock.contains("alertEnabledThreadIds.toList()"))
        assertTrue(syncBlock.contains("alertEnabledThreadIds.isNotEmpty()"))
        assertFalse(scheduleBlock.contains("currentDetailThreadId()"))
        assertFalse(newMessageBlock.contains("currentScreen != ConnectedScreen.DETAIL"))
        assertFalse(completionBlock.contains("currentScreen != ConnectedScreen.DETAIL"))
    }

    @Test
    fun `only completion detail alerts trigger vibration`() {
        val source = readSource()
        val workerSource = readAlertsWorkerSource()

        assertFalse(source.contains("private fun vibrateForWaitingInputAlertIfNeeded("))
        assertFalse(source.contains("triggerDetailAlertVibration()"))
        assertTrue(workerSource.contains("NotificationCompat.DEFAULT_ALL"))
    }

    @Test
    fun `detail notifications are grouped per thread with a summary notification`() {
        val source = readSource()
        val workerSource = readAlertsWorkerSource()
        val supportSource = readDetailNotificationSupportSource()

        assertTrue(source.contains("DetailNotificationSupport.showThreadNotification("))
        assertTrue(workerSource.contains("DetailNotificationSupport.showThreadNotification("))
        assertTrue(source.contains("DetailNotificationSupport.refreshSummaryNotification("))
        assertTrue(supportSource.contains("DetailNotificationIds.GROUP_KEY"))
        assertTrue(supportSource.contains(".setGroup(DetailNotificationIds.GROUP_KEY)"))
        assertTrue(supportSource.contains(".setGroupSummary(true)"))
        assertTrue(supportSource.contains("DetailNotificationIds.notificationTagForThread(threadId)"))
        assertTrue(supportSource.contains("DetailNotificationIds.threadContentIntentRequestCode(threadId)"))
        assertTrue(supportSource.contains("DetailNotificationIds.SUMMARY_NOTIFICATION_ID"))
        assertTrue(supportSource.contains("DetailNotificationIds.SUMMARY_CONTENT_INTENT_REQUEST_CODE"))
        assertFalse(supportSource.contains("VISIBLE_NOTIFICATION_ID"))
    }

    @Test
    fun `detail notification pending intents do not carry gateway token`() {
        val source = readSource()
        val supportSource = readDetailNotificationSupportSource()
        val launchIntentBlock = supportSource.substringAfter("private fun launchIntent(")
            .substringBefore("private fun String.quoteForTrace()")
        val intentConfigBlock = source.substringAfter("private fun intentConfig()")
            .substringBefore("private fun displayErrorMessage(")

        assertFalse(
            "Notification PendingIntent must not expose gateway token extras",
            launchIntentBlock.contains("MainActivity.EXTRA_TOKEN")
        )
        assertFalse(
            "Notification PendingIntent must not write gateway token extras",
            launchIntentBlock.contains("putExtra(MainActivity.EXTRA_TOKEN")
        )
        assertTrue(
            "MainActivity should still recover token from saved local config",
            intentConfigBlock.contains("GatewayPreferences.loadConfig(this)?.url")
        )
    }

    @Test
    fun `detail notifications emit debug trace logs around system posting`() {
        val workerSource = readAlertsWorkerSource()
        val supportSource = readDetailNotificationSupportSource()

        assertTrue(workerSource.contains("DebugTraceLogger.log("))
        assertTrue(workerSource.contains("\"notification.failed\""))
        assertTrue(supportSource.contains("\"notification.prepare\""))
        assertTrue(supportSource.contains("\"notification.posted\""))
        assertTrue(supportSource.contains("\"notification.failed\""))
    }

    @Test
    fun `detail typing uses profile settings and keeps bottom sticky during animation`() {
        val source = readSource()
        val resolveBlock = source.substringAfter("private fun resolveDetailTypingSpec(")
            .substringBefore("private fun expandedConversationHistoryKeysFor(")
        val typingBlock = source.substringAfter("private fun startDetailTypingAnimation(")
            .substringBefore("private fun failedMessageIndicator(")

        assertTrue(source.contains("private var keepDetailBottomForTyping = false"))
        assertTrue(source.contains("private fun shouldKeepDetailBottomForTyping(): Boolean"))
        assertTrue(source.contains("GatewayPreferences.loadDetailTypingAnimationEnabled(this)"))
        assertTrue(source.contains("GatewayPreferences.loadDetailTypingCharsPerSecond(this)"))
        assertTrue(resolveBlock.contains("if (!GatewayPreferences.loadDetailTypingAnimationEnabled(this))"))
        assertTrue(typingBlock.contains("typingCharsPerSecond = GatewayPreferences.loadDetailTypingCharsPerSecond(this)"))
        assertTrue(typingBlock.contains("detailTypingAnimationDurationMillis("))
        assertTrue(typingBlock.contains("keepDetailBottomForTyping = shouldKeepDetailBottomForTyping()"))
        assertTrue(typingBlock.contains("if (keepDetailBottomForTyping && currentScreen == ConnectedScreen.DETAIL)"))
        assertTrue(typingBlock.contains("target.post {"))
        assertTrue(typingBlock.contains("scrollDetailToBottomWithoutFocusChange()"))
    }

    @Test
    fun `detail typing animates rendered markdown text instead of markdown source`() {
        val source = readSource()
        val resolveBlock = source.substringAfter("private fun resolveDetailTypingSpec(")
            .substringBefore("private fun expandedConversationHistoryKeysFor(")
        val typingBlock = source.substringAfter("private fun startDetailTypingAnimation(")
            .substringBefore("private fun failedMessageIndicator(")

        assertTrue(source.contains("private fun conversationMessageTypingText("))
        assertTrue(source.contains("markwon.toMarkdown(markdown).toString()"))
        assertTrue(resolveBlock.contains("val markdown = conversationMessageMarkdown(latestMessage)"))
        assertTrue(resolveBlock.contains("val typingText = conversationMessageTypingText(markdown)"))
        assertTrue(typingBlock.contains("renderConversationMarkdown(target, markdown, threadId)"))
    }

    @Test
    fun `launcher intent preserves the current connected screen when app is already alive`() {
        val source = readSource()
        val newIntentBlock = source.substringAfter("override fun onNewIntent(intent: Intent)")
            .substringBefore("override fun dispatchTouchEvent")

        assertTrue(newIntentBlock.contains("val explicitConfig = intentConfig()"))
        assertTrue(newIntentBlock.contains("requestedThreadId == null && explicitConfig == null"))
        assertTrue(newIntentBlock.contains("binding.connectedShell.visibility == View.VISIBLE"))
        assertTrue(newIntentBlock.contains("return"))
        assertTrue(newIntentBlock.contains("val config = explicitConfig ?: GatewayPreferences.loadConfig(this) ?: return"))
    }

    @Test
    fun `background system alert polling follows manually enabled detail alerts`() {
        val source = readSource()
        val schedulerSource = readAlertPollingSchedulerSource()
        val workerSource = readAlertsWorkerSource()
        val scheduleBlock = schedulerSource.substringAfter("fun schedule(context: Context)")
            .substringBefore("fun scheduleNext(context: Context)")
        val scheduleNextBlock = schedulerSource.substringAfter("fun scheduleNext(context: Context)")
            .substringBefore("fun cancel(context: Context)")

        assertFalse(source.contains("requestNotificationPermissionIfNeeded()"))
        assertTrue(source.contains("AlertPollingScheduler.schedule(this)"))
        assertTrue(source.contains("AlertPollingScheduler.cancel(this)"))
        assertTrue(scheduleBlock.contains("GatewayPreferences.loadConnectionMode(context) == GatewayConnectionMode.SOCKET"))
        assertTrue(scheduleBlock.contains("cancel(context)"))
        assertTrue(schedulerSource.contains("OneTimeWorkRequestBuilder<AlertsWorker>()"))
        assertTrue(schedulerSource.contains("ExistingWorkPolicy.REPLACE"))
        assertTrue(schedulerSource.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))
        assertTrue(schedulerSource.contains("NetworkType.CONNECTED"))
        assertTrue(schedulerSource.contains("setInitialDelay(ALERT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)"))
        assertTrue(schedulerSource.contains("fun scheduleNext(context: Context)"))
        assertFalse(scheduleBlock.contains("enqueueUniquePeriodicWork"))
        assertTrue(scheduleBlock.contains("enqueueNext(context, ExistingWorkPolicy.REPLACE)"))
        assertTrue(scheduleNextBlock.contains("cancel(context)"))
        assertTrue(scheduleNextBlock.contains("enqueueNext(context, ExistingWorkPolicy.APPEND_OR_REPLACE)"))
        assertTrue(schedulerSource.contains("enqueueUniqueWork"))
        assertTrue(workerSource.contains("GatewayPreferences.loadAlertEnabledThreadIds(applicationContext)"))
        assertTrue(workerSource.contains("ThreadDetailSnapshotSource.fetch(api, config, threadId)"))
        assertTrue(workerSource.contains("DetailAlertSupport.messageAlertKey(latestMessage)"))
        assertTrue(workerSource.contains("DetailAlertSupport.completionAlertKey(detail)"))
        assertTrue(workerSource.contains("GatewayPreferences.saveAlertMessageKeys(applicationContext"))
        assertTrue(workerSource.contains("GatewayPreferences.saveAlertCompletionKeys(applicationContext"))
        assertTrue(workerSource.contains("GatewayPreferences.saveCachedThreadDetail(applicationContext, detail)"))
        assertTrue(workerSource.contains("AlertPollingScheduler.scheduleNext(applicationContext)"))
        assertFalse(workerSource.contains("/api/alerts"))
    }

    @Test
    fun `task refresh button has visible transition motion while refreshing`() {
        val source = readSource()

        assertTrue(source.contains("private var lastIsRefreshingThreads = false"))
        assertTrue(source.contains("private var taskRefreshSuccessToastPending = false"))
        assertTrue(source.contains("private fun syncTaskRefreshMotion("))
        assertTrue(source.contains("state.isRefreshingThreads"))
        assertTrue(source.contains("binding.taskRefreshButton"))
        assertTrue(source.contains("binding.taskRefreshButton.isEnabled = false"))
        assertTrue(source.contains("binding.taskRefreshButton.alpha = 0.55f"))
        assertTrue(source.contains("private fun showTaskRefreshTapFeedback()"))
        assertFalse(source.contains("taskRefreshAnimator"))
    }

    @Test
    fun `task refresh reports only success with bottom toast`() {
        val source = readSource()
        val strings = readStrings()

        assertTrue(source.contains("Toast.makeText(this, R.string.task_refresh_success, Toast.LENGTH_SHORT).show()"))
        assertTrue(strings.contains("<string name=\"task_refresh_success\">刷新成功</string>"))
        assertFalse(source.contains("R.string.task_refresh_started"))
        assertFalse(strings.contains("task_refresh_started"))
        assertFalse(strings.contains("开始刷新"))
    }

    @Test
    fun `detail refresh has text feedback and result toasts`() {
        val source = readSource()
        val strings = readStrings()

        assertTrue(source.contains("private var detailRefreshSuccessToastPending = false"))
        assertTrue(source.contains("private fun showDetailRefreshTapFeedback()"))
        assertTrue(source.contains("binding.detailRefreshButton.isEnabled = false"))
        assertTrue(source.contains("binding.detailRefreshButton.alpha = 0.55f"))
        assertTrue(source.contains("showDetailRefreshResultToast(result)"))
        assertTrue(source.contains("R.string.detail_refresh_synced"))
        assertTrue(source.contains("R.string.detail_refresh_unchanged"))
        assertTrue(source.contains("R.string.detail_refresh_failed"))
        assertTrue(strings.contains("<string name=\"detail_refresh_label\">刷新</string>"))
        assertTrue(strings.contains("<string name=\"detail_refresh_synced\">详情已同步</string>"))
        assertTrue(strings.contains("<string name=\"detail_refresh_unchanged\">已是最新</string>"))
        assertTrue(strings.contains("<string name=\"detail_refresh_failed\">刷新失败，请稍后重试</string>"))
    }

    @Test
    fun `message input stays focusable when selected thread cannot send yet`() {
        val source = readSource()

        assertTrue(source.contains("binding.sendButton.isEnabled ="))
        assertTrue(source.contains("binding.sendButton.isEnabled = state.isConnected && !isPendingDetail"))
        assertTrue(source.contains("binding.messageInput.isEnabled = state.isConnected && !isPendingDetail"))
        assertTrue(source.contains("binding.messageInput.alpha = 1f"))
    }

    @Test
    fun `running text send asks whether to queue or guide the message`() {
        val source = readSource()
        val sendClickBlock = source.substringAfter("binding.sendButton.setOnClickListener")
            .substringBefore("private fun handleDetailHistoryTouch(")
        val shouldConfirmBlock = source.substringAfter("private fun shouldConfirmRunningGuideMessage(")
            .substringBefore("private fun showRunningMessageSendTypeDialog(")
        val guideDialogBlock = source.substringAfter("private fun showRunningMessageSendTypeDialog(")
            .substringBefore("private fun handleDetailHistoryTouch(")
        val strings = readStrings()

        assertTrue(source.contains("private fun shouldConfirmRunningGuideMessage("))
        assertTrue(source.contains("private fun showRunningMessageSendTypeDialog("))
        assertTrue(sendClickBlock.contains("shouldConfirmRunningGuideMessage("))
        assertTrue(sendClickBlock.contains("showRunningMessageSendTypeDialog(text)"))
        assertTrue(shouldConfirmBlock.contains("ComposerSendModeSupport.shouldConfirmRunningTextSend("))
        assertTrue(shouldConfirmBlock.contains("hasPendingAttachments = pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()"))
        assertTrue(source.contains("import com.codex.mobilecontrol.ui.ComposerSendModeSupport"))
        assertFalse(source.contains("private fun isRunningSendBlockedDetail(detail: ThreadDetail): Boolean"))
        assertTrue(guideDialogBlock.contains("R.string.running_guide_message_title"))
        assertTrue(guideDialogBlock.contains("R.string.running_queue_message_confirm"))
        assertTrue(guideDialogBlock.contains("R.string.running_guide_message_confirm"))
        assertTrue(guideDialogBlock.contains("setNeutralButton(R.string.profile_connection_mode_dialog_cancel"))
        assertTrue(guideDialogBlock.contains("setNegativeButton(R.string.running_queue_message_confirm)"))
        assertTrue(guideDialogBlock.contains("viewModel.queueMessageAfterCurrentTurn(text)"))
        assertTrue(guideDialogBlock.contains("viewModel.sendMessage(text, guide = true)"))
        assertTrue(guideDialogBlock.contains("binding.messageInput.text?.clear()"))
        assertTrue(source.contains("showQueuedMessageToast()"))
        assertTrue(source.contains("private fun showQueuedMessagesDialog()"))
        assertTrue(source.contains("private fun cancelQueuedMessageToComposer(queueId: String): Boolean"))
        assertTrue(source.contains("queuedTextMessages"))
        assertFalse(source.contains("Snackbar.make("))
        assertTrue(strings.contains("<string name=\"running_guide_message_title\">选择发送方式</string>"))
        assertTrue(strings.contains("<string name=\"running_queue_message_confirm\">加入队列</string>"))
        assertTrue(strings.contains("<string name=\"running_guide_message_confirm\">发送引导</string>"))
        assertTrue(strings.contains("<string name=\"running_queue_status_pending_many\">排队 %1${'$'}d</string>"))
        assertTrue(strings.contains("<string name=\"running_queue_snackbar\">加入队列成功</string>"))
        assertFalse(strings.contains("running_queue_snackbar_undo"))
        assertTrue(strings.contains("<string name=\"running_queue_message_cancelled\">已取消排队发送</string>"))
        assertFalse(strings.contains("Ctrl+Enter"))
        assertFalse(strings.contains("普通回车"))
    }

    @Test
    fun `queued confirmation uses the same short toast style as refresh success`() {
        val source = readSource()
        val strings = readStrings()
        val toastBlock = source.substringAfter("private fun showQueuedMessageToast()")
            .substringBefore("private fun showQueuedMessagesDialog(")

        assertTrue(toastBlock.contains("Toast.makeText(this, R.string.running_queue_snackbar, Toast.LENGTH_SHORT).show()"))
        assertTrue(source.contains("Toast.makeText(this, R.string.task_refresh_success, Toast.LENGTH_SHORT).show()"))
        assertTrue(source.contains("Toast.makeText(this, R.string.detail_refresh_synced, Toast.LENGTH_SHORT).show()"))
        assertFalse(source.contains("private fun styleQueuedMessageSnackbar("))
        assertFalse(source.contains("setAnchorView(binding.composerCard)"))
        assertFalse(source.contains("running_queue_snackbar_undo"))
        assertTrue(strings.contains("<string name=\"running_queue_snackbar\">加入队列成功</string>"))
        assertFalse(strings.contains("已加入队列：%1${'$'}s"))
    }

    @Test
    fun `queued message cancellation from queue dialog restores text`() {
        val source = readSource()
        val dialogBlock = source.substringAfter("private fun showQueuedMessagesDialog(")
            .substringBefore("private fun queueStatusLabel(")
        val helperBlock = source.substringAfter("private fun cancelQueuedMessageToComposer(")
            .substringBefore("private fun queueStatusLabel(")

        assertTrue(source.contains("private fun cancelQueuedMessageToComposer(queueId: String): Boolean"))
        assertTrue(dialogBlock.contains("cancelQueuedMessageToComposer(message.queueId)"))
        assertTrue(helperBlock.contains("viewModel.cancelQueuedMessage(queueId)"))
        assertTrue(helperBlock.contains("binding.messageInput.setText(cancelled.text)"))
        assertTrue(helperBlock.contains("binding.messageInput.setSelection(binding.messageInput.text?.length ?: 0)"))
        assertTrue(helperBlock.contains("showComposerKeyboard()"))
        assertTrue(helperBlock.contains("R.string.running_queue_message_cancelled"))
    }

    @Test
    fun `queued messages dialog shows queued time next to message preview`() {
        val source = readSource()
        val strings = readStrings()
        val dialogBlock = source.substringAfter("private fun showQueuedMessagesDialog(")
            .substringBefore("private fun cancelQueuedMessageToComposer(")

        assertTrue(dialogBlock.contains("MobileUiFormatter.formatQueuedMessageTime(message.queuedAtMillis)"))
        assertTrue(dialogBlock.contains("R.string.running_queue_dialog_item_subtitle"))
        assertTrue(strings.contains("<string name=\"running_queue_dialog_item_subtitle\">%1${'$'}s · %2${'$'}s</string>"))
    }

    @Test
    fun `queued composer status uses compact icon chip styling`() {
        val source = readSource()
        val layout = readActivityLayout()
        val strings = readStrings()
        val statusBlock = layout.substringAfter("android:id=\"@+id/composerStatus\"")
            .substringBefore("<com.google.android.material.card.MaterialCardView")

        assertTrue(statusBlock.contains("android:layout_width=\"wrap_content\""))
        assertTrue(statusBlock.contains("android:background=\"@drawable/bg_status_pill\""))
        assertTrue(statusBlock.contains("android:drawableStart=\"@drawable/ic_detail_clock\""))
        assertTrue(statusBlock.contains("android:drawablePadding=\"5dp\""))
        assertTrue(statusBlock.contains("android:paddingStart=\"10dp\""))
        assertTrue(statusBlock.contains("app:layout_constraintEnd_toEndOf=\"parent\""))
        assertFalse(statusBlock.contains("app:layout_constraintStart_toStartOf=\"parent\""))
        assertTrue(source.contains("R.string.running_queue_status_pending_one"))
        assertTrue(strings.contains("<string name=\"running_queue_status_pending_one\">排队 1</string>"))
        assertTrue(strings.contains("<string name=\"running_queue_status_dispatching\">发送中</string>"))
        assertTrue(strings.contains("<string name=\"running_queue_status_failed\">排队失败</string>"))
        assertFalse(strings.contains("下一条"))
    }

    @Test
    fun `image picker stays selectable when selected thread cannot send yet`() {
        val source = readSource()

        assertTrue(source.contains("binding.pickImageButton.isEnabled ="))
        assertTrue(source.contains("binding.pickImageButton.isEnabled = state.isConnected && !isPendingDetail"))
        assertFalse(source.contains("detail?.sendAvailable == true && state.isConnected && !state.isSending && !isPendingDetail"))
    }

    @Test
    fun `message input opens keyboard only from explicit taps`() {
        val source = readSource()
        val focusBlock = source.substringAfter("binding.messageInput.setOnFocusChangeListener")
            .substringBefore("binding.messageInput.setOnClickListener")
        val clickBlock = source.substringAfter("binding.messageInput.setOnClickListener")
            .substringBefore("binding.messageInput.addTextChangedListener")

        assertTrue(source.contains("binding.messageInput.setOnFocusChangeListener"))
        assertTrue(source.contains("binding.messageInput.setOnClickListener"))
        assertTrue(source.contains("private fun showComposerKeyboard()"))
        assertTrue(source.contains("InputMethodManager.SHOW_IMPLICIT"))
        assertFalse(focusBlock.contains("showComposerKeyboard()"))
        assertTrue(clickBlock.contains("showComposerKeyboard()"))
    }

    @Test
    fun `passive detail entry hides composer keyboard`() {
        val source = readSource()
        val onStartBlock = source.substringAfter("override fun onStart()")
            .substringBefore("private fun hideComposerKeyboardForPassiveDetailEntry()")
        val passiveEntryBlock =
            source.substringAfter("private fun hideComposerKeyboardForPassiveDetailEntry()")
                .substringBefore("private fun refreshVisibleDetailOnForeground()")
        val newIntentBlock = source.substringAfter("override fun onNewIntent(intent: Intent)")
            .substringBefore("override fun onStop()")

        assertTrue(source.contains("private fun hideComposerKeyboardForPassiveDetailEntry()"))
        assertTrue(onStartBlock.contains("hideComposerKeyboardForPassiveDetailEntry()"))
        assertTrue(
            onStartBlock.indexOf("hideComposerKeyboardForPassiveDetailEntry()") <
                onStartBlock.indexOf("refreshVisibleDetailOnForeground()")
        )
        assertTrue(passiveEntryBlock.contains("currentScreen != ConnectedScreen.DETAIL"))
        assertTrue(passiveEntryBlock.contains("hideComposerKeyboard()"))
        assertTrue(newIntentBlock.contains("hideComposerKeyboardForPassiveDetailEntry()"))
    }

    @Test
    fun `back press hides composer keyboard before leaving detail`() {
        val source = readSource()
        val backBlock = source.substringAfter("override fun handleOnBackPressed()")
            .substringBefore("isEnabled = false")

        assertTrue(source.contains("private fun hideComposerKeyboard()"))
        assertTrue(backBlock.contains("if (isComposerKeyboardOpen())"))
        assertTrue(backBlock.contains("hideComposerKeyboard()"))
        assertTrue(backBlock.indexOf("hideComposerKeyboard()") < backBlock.indexOf("showTaskScreen()"))
    }

    @Test
    fun `back press refreshes ime visibility before deciding to consume detail back`() {
        val source = readSource()
        val backBlock = source.substringAfter("override fun handleOnBackPressed()")
            .substringBefore("isEnabled = false")

        assertTrue(source.contains("private fun refreshImeVisibilitySnapshot()"))
        assertTrue(backBlock.indexOf("refreshImeVisibilitySnapshot()") < backBlock.indexOf("if (isComposerKeyboardOpen())"))
    }

    @Test
    fun `leaving detail explicitly hides composer keyboard and moves focus away`() {
        val source = readSource()
        val renderBlock = source.substringAfter("private fun renderShellState(")
            .substringBefore("private fun renderDetailContent(")
        val hideBlock = source.substringAfter("private fun hideComposerKeyboard()")
            .substringBefore("private fun syncComposerDraftForThread(")

        assertTrue(renderBlock.contains("hideComposerKeyboard()"))
        assertFalse(renderBlock.contains("binding.messageInput.clearFocus()"))
        assertTrue(hideBlock.contains("currentFocus?.clearFocus()"))
        assertTrue(hideBlock.contains("binding.messageInput.clearFocus()"))
        assertTrue(hideBlock.contains("binding.rootDrawer.requestFocus()"))
        assertTrue(hideBlock.contains("hideSoftInputFromWindow"))
        assertTrue(hideBlock.contains("binding.root.windowToken"))
    }

    @Test
    fun `hiding composer keyboard clears stale ime state before the next back press`() {
        val source = readSource()
        val hideBlock = source.substringAfter("private fun hideComposerKeyboard()")
            .substringBefore("private fun syncComposerDraftForThread(")

        assertTrue(hideBlock.contains("isImeVisibleFromInsets = false"))
        assertTrue(hideBlock.contains("isImeVisibleFromLayout = false"))
        assertTrue(hideBlock.contains("isImeVisible = false"))
        assertTrue(hideBlock.contains("syncBottomNavForIme()"))
    }

    @Test
    fun `keyboard visibility close restores sticky bottom without clearing composer focus`() {
        val source = readSource()
        val updateImeBlock = source.substringAfter("private fun updateImeVisibility()")
            .substringBefore("private fun refreshImeVisibilitySnapshot()")

        assertTrue(updateImeBlock.contains("} else {"))
        assertTrue(updateImeBlock.contains("restoreDetailBottomAfterKeyboardClose()"))
        assertFalse(updateImeBlock.contains("binding.messageInput.clearFocus()"))
        assertFalse(updateImeBlock.contains("binding.rootDrawer.requestFocus()"))
    }

    @Test
    fun `stale composer focus alone does not consume the next detail back press`() {
        val source = readSource()
        val keyboardOpenBlock = source.substringAfter("private fun isComposerKeyboardOpen()")
            .substringBefore("private fun hideComposerKeyboard()")

        assertTrue(keyboardOpenBlock.contains("isImeVisible"))
        assertFalse(keyboardOpenBlock.contains("binding.messageInput.hasFocus()"))
    }

    @Test
    fun `message input draft is scoped to selected thread`() {
        val source = readSource()

        assertTrue(source.contains("private val composerDraftStore = ComposerDraftStore()"))
        assertTrue(source.contains("binding.messageInput.addTextChangedListener"))
        assertTrue(source.contains("composerDraftStore.onTextChanged(text?.toString().orEmpty())"))
        assertTrue(source.contains("private fun syncComposerDraftForThread("))
        assertTrue(source.contains("composerDraftStore.switchThread("))
        assertTrue(source.contains("composerDraftStore.withRestoringDraft"))
        assertFalse(source.contains("private val composerDraftsByThreadId = mutableMapOf<String, String>()"))
        assertFalse(source.contains("private var activeComposerThreadId: String? = null"))
        assertTrue(source.contains("syncComposerDraftForThread(composerThreadId)"))
    }

    @Test
    fun `user conversation bubble keeps right placement but left aligns inner content`() {
        val source = readSource()
        val conversationBlock = source.substringAfter("private fun conversationMessageRow(")
            .substringBefore("private fun conversationMessageMarkdown(")

        assertTrue(conversationBlock.contains("isUserMessage -> Gravity.END"))
        assertTrue(conversationBlock.contains("gravity = if (isSystemMessage) Gravity.CENTER else Gravity.START"))
        assertFalse(conversationBlock.contains("gravity = if (isUserMessage) Gravity.END else Gravity.START"))
    }

    @Test
    fun `conversation text and timestamp measure by content to avoid narrow short bubbles`() {
        val source = readSource()
        val conversationBlock = source.substringAfter("private fun conversationMessageRow(")
            .substringBefore("private fun conversationMessageMarkdown(")

        assertTrue(conversationBlock.contains("val conversationTextWidth = LinearLayout.LayoutParams.WRAP_CONTENT"))
        assertTrue(conversationBlock.contains("layoutParams = LinearLayout.LayoutParams(\n                conversationTextWidth,"))
        assertTrue(conversationBlock.contains("val conversationTimestampWidth = LinearLayout.LayoutParams.WRAP_CONTENT"))
        assertTrue(conversationBlock.contains("layoutParams = LinearLayout.LayoutParams(\n                conversationTimestampWidth,"))
    }

    @Test
    fun `short conversation text keeps enough width for cjk replies`() {
        val source = readSource()
        val conversationBlock = source.substringAfter("private fun conversationMessageRow(")
            .substringBefore("private fun conversationMessageMarkdown(")

        assertTrue(source.contains("private const val CONVERSATION_MIN_TEXT_WIDTH_DP = 128"))
        assertTrue(source.contains("private const val CONVERSATION_MIN_TEXT_EMS = 10"))
        assertTrue(conversationBlock.contains("if (isAssistantMessage)"))
        assertTrue(conversationBlock.contains("minWidth = dp(CONVERSATION_MIN_TEXT_WIDTH_DP)"))
        assertTrue(conversationBlock.contains("minEms = CONVERSATION_MIN_TEXT_EMS"))
        assertTrue(conversationBlock.contains("if (isUserMessage)"))
        assertTrue(conversationBlock.contains("params.gravity = Gravity.END"))
    }

    @Test
    fun `short conversation text measures rendered markdown before choosing bubble width`() {
        val source = readSource()
        val conversationBlock = source.substringAfter("private fun conversationMessageRow(")
            .substringBefore("private fun startDetailTypingAnimation(")

        assertTrue(source.contains("import android.text.Layout"))
        assertTrue(source.contains("private fun applyConversationMeasuredTextWidth("))
        assertTrue(source.contains("Layout.getDesiredWidth(source, lineStart, lineEnd, target.paint)"))
        assertTrue(conversationBlock.contains("applyConversationMeasuredTextWidth("))
        assertTrue(conversationBlock.contains("measurementText = typingSpec?.typingText"))
        assertTrue(conversationBlock.contains("keepMinimumWidth = isAssistantMessage"))
    }

    @Test
    fun `state errors are also surfaced with a toast`() {
        val source = readSource()

        assertTrue(source.contains("private var lastShownErrorMessage: String? = null"))
        assertTrue(source.contains("private fun syncErrorToast("))
        assertTrue(source.contains("Toast.makeText(this, displayErrorMessage(errorMessage), Toast.LENGTH_SHORT).show()"))
    }

    @Test
    fun `detail top bar binds selected thread name and current status`() {
        val source = readSource()

        assertTrue(source.contains("binding.detailTopTitle.text = detailThread?.title"))
        assertTrue(source.contains("val detailSubtitle = when {"))
        assertTrue(source.contains("isDetailSyncPending -> getString(R.string.detail_sync_pending_state)"))
        assertTrue(source.contains("binding.detailTopSubtitle.text = detailSubtitle"))
        assertTrue(source.contains("DetailRunningStatusSupport.subtitle("))
        assertFalse(source.contains("binding.detailBellBadge"))
        assertFalse(source.contains("binding.detailSettingsButton"))
    }

    @Test
    fun `detail subtitle can show the full truncated status in a dialog`() {
        val source = readSource()
        val detailBlock = source.substringAfter("private fun bindDetailPage(")
            .substringBefore("private fun toggleDetailAlertForCurrentThread()")
        val dialogBlock = source.substringAfter("private fun showDetailStatusDialog(")
            .substringBefore("private fun currentDetailThreadId()")

        assertTrue(detailBlock.contains("syncDetailSubtitlePopup("))
        assertTrue(source.contains("private fun syncDetailSubtitlePopup("))
        assertTrue(source.contains("subtitleView.post"))
        assertTrue(source.contains("layout.getEllipsisCount(0) > 0"))
        assertTrue(source.contains("MaterialAlertDialogBuilder(this)"))
        assertTrue(source.contains("R.string.detail_status_dialog_title"))
        assertTrue(source.contains(".setMessage(subtitle)"))
        assertTrue(dialogBlock.contains("styleDetailStatusDialog(dialog)"))
        assertTrue(source.contains("private fun styleDetailStatusDialog("))
        assertTrue(source.contains("R.color.detail_nested_card_surface"))
        assertTrue(source.contains("R.color.detail_primary_text"))
        assertTrue(source.contains("R.color.detail_body_text"))
        assertTrue(source.contains("R.color.detail_link_text"))
    }

    @Test
    fun `profile current version row is display only`() {
        val source = readSource()

        assertFalse(source.contains("binding.profileAboutRow.setOnClickListener"))
        assertFalse(source.contains("showAboutDialog()"))
        assertTrue(source.contains("binding.profileAboutTitle.text = getString("))
        assertTrue(source.contains("R.string.profile_about_version"))
    }

    @Test
    fun `composer image picker supports multiple pending images and preview dialog`() {
        val source = readSource()

        assertTrue(source.contains("ActivityResultContracts.GetMultipleContents()"))
        assertTrue(source.contains("viewModel.addPendingImages("))
        assertTrue(source.contains("state.pendingImageDrafts"))
        assertTrue(source.contains("private fun bindPendingImages("))
        assertTrue(source.contains("ComposerAttachmentRowSupport.imagePreviewRow("))
        assertTrue(source.contains("private fun showPendingImagePreviewDialog("))
        assertTrue(source.contains("onPreview = ::showPendingImagePreviewDialog"))
        assertTrue(source.contains("viewModel.sendAttachmentMessage("))
        assertTrue(source.contains("openImageStream = { draft ->"))
    }

    @Test
    fun `composer attachment picker supports image and file drafts`() {
        val source = readSource()
        val pickerBlock = source.substringAfter("private fun showAttachmentPicker()")
            .substringBefore("private fun bindPendingImages(")

        assertTrue(source.contains("private val pickImageLauncher = registerForActivityResult"))
        assertTrue(source.contains("private val pickFileLauncher = registerForActivityResult"))
        assertTrue(source.contains("private fun showAttachmentPicker()"))
        assertTrue(source.contains("private fun showUnifiedActionDialog("))
        assertTrue(source.contains("private fun unifiedDialogRow("))
        assertTrue(source.contains("R.string.attachment_picker_title"))
        assertTrue(pickerBlock.contains("showUnifiedActionDialog("))
        assertFalse(pickerBlock.contains(".setItems("))
        assertTrue(source.contains("pickImageLauncher.launch(\"image/*\")"))
        assertTrue(source.contains("pickFileLauncher.launch(\"*/*\")"))
        assertTrue(source.contains("viewModel.addPendingFiles("))
        assertTrue(source.contains("state.pendingFileDrafts"))
        assertTrue(source.contains("private fun bindPendingAttachments("))
        assertTrue(source.contains("ComposerAttachmentRowSupport.filePreviewRow("))
        assertTrue(source.contains("viewModel.sendAttachmentMessage("))
        assertTrue(source.contains("openFileStream = { draft ->"))
    }

    @Test
    fun `pending image previews only show with detail composer and can remove one draft`() {
        val source = readSource()

        assertTrue(source.contains("ComposerAttachmentSupport.isCardVisible("))
        assertTrue(source.contains("showComposer = shellState.showComposer"))
        assertTrue(source.contains("ComposerAttachmentRowSupport.imagePreviewRow("))
        assertFalse(source.contains("private fun pendingImagePreviewRow(draft: PendingImageDraft): View"))
        assertFalse(source.contains("private fun pendingFilePreviewRow(draft: PendingFileDraft): View"))
        assertTrue(source.contains("onRemove = viewModel::removePendingImage"))
    }

    @Test
    fun `profile header has no duplicate notification or settings actions`() {
        val source = readSource()

        assertFalse(source.contains("binding.profileBellButton"))
        assertFalse(source.contains("binding.profileBellBadge"))
        assertFalse(source.contains("binding.profileSettingsButton"))
    }

    @Test
    fun `entering detail requests one-time detail scroll to the bottom`() {
        val source = readSource()

        assertTrue(source.contains("private var shouldScrollDetailToBottom = false"))
        assertTrue(source.contains("private fun requestDetailScrollToBottom()"))
        assertTrue(source.contains("private fun maybeScrollDetailTimelineToBottom("))
        assertTrue(source.contains("requestDetailScrollToBottom()"))
        assertTrue(source.contains("scrollDetailToBottomWithoutFocusChange()"))
        assertFalse(source.contains("binding.detailScroll.fullScroll(View.FOCUS_DOWN)"))
    }

    @Test
    fun `stale completion notifications are cleared when a monitored thread runs again`() {
        val source = readSource()
        val workerSource = readAlertsWorkerSource()
        val supportSource = readDetailNotificationSupportSource()
        val observeBlock = source.substringAfter("private fun observeState()")
            .substringBefore("private fun observeRealtimeNotifications()")
        val syncBlock = source.substringAfter("private fun syncStaleCompletionNotifications(")
            .substringBefore("private fun showPendingRealtimeNotifications(")
        val workerCompletionBlock = workerSource.substringAfter("private fun updateCompletionAlertBaseline(")
            .substringBefore("private fun showMessageNotification(")

        assertTrue(supportSource.contains("fun cancelThreadNotificationIfChannel("))
        assertTrue(observeBlock.contains("syncStaleCompletionNotifications(state)"))
        assertTrue(syncBlock.contains("MobileThreadStatus.RUNNING"))
        assertTrue(syncBlock.contains("DetailAlertSupport.shouldClearCompletionNotification("))
        assertTrue(syncBlock.contains("DetailNotificationSupport.cancelThreadNotificationIfChannel("))
        assertTrue(syncBlock.contains("DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID"))
        assertTrue(syncBlock.contains("saveDetailAlertBaselines()"))
        assertTrue(workerCompletionBlock.contains("DetailAlertSupport.shouldClearCompletionNotification("))
        assertTrue(workerCompletionBlock.contains("DetailNotificationSupport.cancelThreadNotificationIfChannel("))
        assertTrue(workerCompletionBlock.contains("DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID"))
    }

    @Test
    fun `foreground detail refresh does not emit system notifications`() {
        val source = readSource()
        val refreshBlock = source.substringAfter("private fun scheduleDetailAlertRefresh()")
            .substringBefore("private fun cancelDetailAlertRefresh()")

        assertFalse(refreshBlock.contains("maybeShowDetailNewMessageAlert("))
        assertFalse(refreshBlock.contains("maybeShowDetailCompletionAlert("))
        assertTrue(source.contains("override fun onStop()"))
        assertTrue(source.contains("cancelDetailAlertRefresh()"))
    }

    @Test
    fun `detail running duration subtitle refreshes every second while visible`() {
        val source = readSource()
        val runtimeRefreshBlock = source.substringAfter("private fun syncDetailRuntimeSubtitleRefresh(")
            .substringBefore("private fun refreshDetailRuntimeSubtitle()")

        assertTrue(source.contains("DETAIL_RUNTIME_SUBTITLE_REFRESH_INTERVAL_MS = 1_000L"))
        assertTrue(runtimeRefreshBlock.contains("delay(DETAIL_RUNTIME_SUBTITLE_REFRESH_INTERVAL_MS)"))
        assertTrue(runtimeRefreshBlock.contains("refreshDetailRuntimeSubtitle()"))
    }

    @Test
    fun `foregrounding app restarts alert refresh and refreshes visible detail`() {
        val source = readSource()
        val onStartBlock = source.substringAfter("override fun onStart()")
            .substringBefore("override fun onNewIntent(")
        val foregroundRefreshBlock = source.substringAfter("private fun refreshVisibleDetailOnForeground()")
            .substringBefore("override fun onNewIntent(")

        assertTrue(onStartBlock.contains("scheduleDetailAlertRefresh()"))
        assertTrue(onStartBlock.contains("refreshVisibleDetailOnForeground()"))
        assertTrue(foregroundRefreshBlock.contains("currentScreen != ConnectedScreen.DETAIL"))
        assertTrue(foregroundRefreshBlock.contains("!state.isConnected"))
        assertTrue(foregroundRefreshBlock.contains("viewModel.refreshThreadDetail(threadId)"))
    }

    private fun readSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/MainActivity.kt"),
            File("src/main/java/com/codex/mobilecontrol/MainActivity.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("MainActivity.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText().replace("\r\n", "\n")
    }

    private fun readAlertPollingSchedulerSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/AlertPollingScheduler.kt"),
            File("src/main/java/com/codex/mobilecontrol/AlertPollingScheduler.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("AlertPollingScheduler.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }

    private fun readAlertsWorkerSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/AlertsWorker.kt"),
            File("src/main/java/com/codex/mobilecontrol/AlertsWorker.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("AlertsWorker.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }

    private fun readDetailNotificationSupportSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/DetailNotificationSupport.kt"),
            File("src/main/java/com/codex/mobilecontrol/DetailNotificationSupport.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("DetailNotificationSupport.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }

    private fun readDetailAlertSettingsDialogSupportSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/ui/DetailAlertSettingsDialogSupport.kt"),
            File("src/main/java/com/codex/mobilecontrol/ui/DetailAlertSettingsDialogSupport.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("DetailAlertSettingsDialogSupport.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }

    private fun readThreadNotificationSettingsDialogSupportSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/ui/ThreadNotificationSettingsDialogSupport.kt"),
            File("src/main/java/com/codex/mobilecontrol/ui/ThreadNotificationSettingsDialogSupport.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("ThreadNotificationSettingsDialogSupport.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }

    private fun readGatewayPreferencesSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/GatewayPreferences.kt"),
            File("src/main/java/com/codex/mobilecontrol/GatewayPreferences.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("GatewayPreferences.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }

    private fun readStrings(): String {
        val candidates = listOf(
            File("app/src/main/res/values/strings.xml"),
            File("src/main/res/values/strings.xml")
        )
        val stringsFile = candidates.firstOrNull(File::isFile)
            ?: error("strings.xml not found from ${System.getProperty("user.dir")}")

        return stringsFile.readText()
    }

    private fun readActivityLayout(): String {
        val candidates = listOf(
            File("app/src/main/res/layout/activity_main.xml"),
            File("src/main/res/layout/activity_main.xml")
        )
        val layoutFile = candidates.firstOrNull(File::isFile)
            ?: error("activity_main.xml not found from ${System.getProperty("user.dir")}")

        return layoutFile.readText().replace("\r\n", "\n")
    }
}
