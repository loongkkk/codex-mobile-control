package com.codex.mobilecontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityDownloadActionTest {

    @Test
    fun `main activity checks latest version before downloading apk`() {
        val source = readMainActivity()

        assertTrue(source.contains("binding.profileDownloadApkRow.setOnClickListener"))
        assertTrue(source.contains("handleVersionUpdateClick()"))
        assertTrue(source.contains("viewModel.checkLatestApk()"))
        assertTrue(source.contains("viewModel.checkLatestApk(force = true)"))
        assertTrue(source.contains("isNewerThan(BuildConfig.VERSION_CODE)"))
        assertTrue(source.contains("R.string.profile_update_already_latest"))
        assertTrue(source.contains("private fun downloadLatestApk()"))
        assertTrue(source.contains("DownloadManager.Request"))
        assertTrue(source.contains("/downloads/latest.apk"))
        assertTrue(source.contains("appendQueryParameter("))
        assertTrue(source.contains("\"v\""))
        assertTrue(source.contains("appendQueryParameter(\"t\""))
        assertTrue(source.contains("latestApkDownloadFileName("))
        assertFalse(source.contains("\"codex-mobile-control-latest.apk\""))
    }

    @Test
    fun `profile strings present version update wording`() {
        val strings = readStrings()

        assertTrue(strings.contains("<string name=\"profile_update_title\">版本更新</string>"))
        assertTrue(strings.contains("<string name=\"profile_update_already_latest\">已经是最新版</string>"))
        assertTrue(strings.contains("<string name=\"profile_clear_cache_title\">清除缓存</string>"))
        assertTrue(strings.contains("<string name=\"profile_clear_cache_success\">缓存已清除</string>"))
        assertTrue(strings.contains("""<string name="profile_about_version">当前版本 v%1${'$'}s</string>"""))
        assertFalse(strings.contains("下载最新版安装包"))
        assertFalse(strings.contains("管理消息通知与告警方式"))
    }

    @Test
    fun `profile clear cache row clears thread caches without logging out`() {
        val source = readMainActivity()

        assertTrue(source.contains("binding.profileClearCacheRow.setOnClickListener"))
        assertTrue(source.contains("viewModel.clearThreadCache()"))
        assertTrue(source.contains("R.string.profile_clear_cache_success"))
        assertFalse(source.contains("profileClearCacheRow.setOnClickListener {\n            performLogout()"))
    }

    @Test
    fun `profile connection mode uses unified confirmation dialog before persisting`() {
        val source = readMainActivity()
        val strings = readStrings()
        val setupBlock = source.substringAfter("private fun setupActions()")
            .substringBefore("private fun handleDetailHistoryTouch(")
        val dialogBlock = source.substringAfter("private fun showProfileConnectionModeDialog()")
            .substringBefore("private fun applyProfileConnectionMode(")
        val applyBlock = source.substringAfter("private fun applyProfileConnectionMode(")
            .substringBefore("private fun bindProfilePage(")

        assertTrue(strings.contains("<string name=\"profile_connection_mode_title\">实时通道</string>"))
        assertTrue(strings.contains("<string name=\"profile_connection_mode_socket\">WebSocket</string>"))
        assertTrue(strings.contains("<string name=\"profile_connection_mode_sse\">SSE</string>"))
        assertTrue(strings.contains("<string name=\"profile_connection_mode_dialog_confirm\">确认</string>"))
        assertTrue(strings.contains("<string name=\"profile_connection_mode_dialog_cancel\">取消</string>"))
        assertTrue(source.contains("private var profileConnectionMode = GatewayConnectionMode.SOCKET"))
        assertTrue(source.contains("GatewayPreferences.loadConnectionMode(this)"))
        assertTrue(setupBlock.contains("binding.profileConnectionModeRow.setOnClickListener"))
        assertTrue(setupBlock.contains("showProfileConnectionModeDialog()"))
        assertTrue(source.contains("private fun showProfileConnectionModeDialog()"))
        assertTrue(source.contains("private fun showUnifiedActionDialog("))
        assertTrue(source.contains("private fun unifiedDialogPanel("))
        assertTrue(source.contains("private fun unifiedDialogRow("))
        assertTrue(dialogBlock.contains("Dialog(this)"))
        assertTrue(dialogBlock.contains("unifiedDialogRow("))
        assertTrue(dialogBlock.contains("selected = mode == selectedMode"))
        assertTrue(dialogBlock.contains("current = mode == profileConnectionMode"))
        assertTrue(dialogBlock.contains("R.string.profile_connection_mode_dialog_confirm"))
        assertTrue(dialogBlock.contains("applyProfileConnectionMode(selectedMode)"))
        assertTrue(dialogBlock.contains("R.string.profile_connection_mode_dialog_cancel"))
        assertFalse(dialogBlock.contains("MaterialAlertDialogBuilder(this)"))
        assertFalse(dialogBlock.contains("setSingleChoiceItems"))
        assertTrue(source.contains("private fun bindProfileConnectionMode()"))
        assertTrue(source.contains("binding.profileConnectionModeSubtitle.text = getString("))
        assertTrue(applyBlock.contains("if (selectedMode == profileConnectionMode)"))
        assertTrue(applyBlock.contains("profileConnectionMode = selectedMode"))
        assertTrue(applyBlock.contains("GatewayPreferences.saveConnectionMode(this, profileConnectionMode)"))
        assertTrue(applyBlock.contains("viewModel.connect(config, state.selectedThreadId)"))
        assertFalse(applyBlock.contains("profileConnectionMode = when (profileConnectionMode)"))
        assertFalse(applyBlock.contains("viewModel.disconnect("))
    }

    @Test
    fun `profile socket status row binds connection state and can reconnect`() {
        val source = readMainActivity()
        val strings = readStrings()
        val setupBlock = source.substringAfter("private fun setupActions()")
            .substringBefore("private fun handleDetailHistoryTouch(")
        val bindBlock = source.substringAfter("private fun bindProfileSocketStatus(")
            .substringBefore("private fun showProfileConnectionModeDialog()")
        val reconnectBlock = source.substringAfter("private fun reconnectProfileSocket()")
            .substringBefore("private fun bindProfileSocketStatus(")

        assertTrue(strings.contains("<string name=\"profile_socket_status_title\">连接状态</string>"))
        assertTrue(strings.contains("<string name=\"profile_socket_reconnect\">重连</string>"))
        assertTrue(strings.contains("<string name=\"profile_socket_latency_test\">测速</string>"))
        assertTrue(strings.contains("<string name=\"profile_socket_latency_testing\">测速中...</string>"))
        assertTrue(setupBlock.contains("binding.profileSocketReconnectButton.setOnClickListener"))
        assertTrue(setupBlock.contains("reconnectProfileSocket()"))
        assertTrue(setupBlock.contains("binding.profileSocketLatencyButton.setOnClickListener"))
        assertTrue(setupBlock.contains("viewModel.testSocketLatency()"))
        assertTrue(source.contains("private fun reconnectProfileSocket()"))
        assertTrue(source.contains("private fun bindProfileSocketStatus(state: MainUiState)"))
        assertTrue(reconnectBlock.contains("viewModel.connect(config, state.selectedThreadId)"))
        assertTrue(bindBlock.contains("StreamConnectionState.OPEN"))
        assertTrue(bindBlock.contains("profileSocketStatusDot"))
        assertTrue(bindBlock.contains("profileSocketStatusSubtitle"))
        assertTrue(bindBlock.contains("socketLatencyAverageMillis"))
        assertTrue(bindBlock.contains("profileSocketLatencyButton"))
    }

    @Test
    fun `profile background connection row toggles foreground service`() {
        val source = readMainActivity()
        val strings = readStrings()
        val layout = readMainLayout()
        val prefs = File(
            "src/main/java/com/codex/mobilecontrol/GatewayPreferences.kt"
        ).readText()
        val setupBlock = source.substringAfter("private fun setupActions()")
            .substringBefore("private fun handleDetailHistoryTouch(")
        val applyBlock = source.substringAfter("private fun applyProfileBackgroundConnectionEnabled(")
            .substringBefore("private fun showProfileTypingSettingsDialog()")
        val logoutBlock = source.substringAfter("private fun performLogout()")
            .substringBefore("private fun appVersionName()")

        assertTrue(strings.contains("<string name=\"profile_background_connection_title\">后台保持连接</string>"))
        assertTrue(strings.contains("<string name=\"profile_background_connection_on\">已开启 · 通知栏常驻</string>"))
        assertTrue(strings.contains("<string name=\"profile_background_connection_off\">关闭后后台更省电</string>"))
        assertTrue(layout.contains("android:id=\"@+id/profileBackgroundConnectionRow\""))
        assertTrue(layout.contains("android:id=\"@+id/profileBackgroundConnectionSubtitle\""))
        assertTrue(layout.contains("android:id=\"@+id/profileBackgroundConnectionSwitch\""))
        assertTrue(layout.indexOf("profileConnectionModeRow") < layout.indexOf("profileBackgroundConnectionRow"))
        assertTrue(layout.indexOf("profileBackgroundConnectionRow") < layout.indexOf("profileSocketStatusRow"))
        assertTrue(prefs.contains("KEY_BACKGROUND_CONNECTION_ENABLED"))
        assertTrue(prefs.contains("loadBackgroundConnectionEnabled"))
        assertTrue(prefs.contains("saveBackgroundConnectionEnabled"))
        assertTrue(setupBlock.contains("binding.profileBackgroundConnectionRow.setOnClickListener"))
        assertTrue(setupBlock.contains("applyProfileBackgroundConnectionEnabled("))
        assertTrue(source.contains("private fun bindProfileBackgroundConnection()"))
        assertTrue(applyBlock.contains("GatewayPreferences.saveBackgroundConnectionEnabled(this, enabled)"))
        assertTrue(applyBlock.contains("RealtimeForegroundService.setEnabled(this, enabled)"))
        assertTrue(logoutBlock.contains("RealtimeForegroundService.setEnabled(this, false)"))
    }

    @Test
    fun `profile automation row opens mobile automation list dialog`() {
        val source = readMainActivity()
        val strings = readStrings()
        val layout = readMainLayout()
        val setupBlock = source.substringAfter("private fun setupActions()")
            .substringBefore("private fun handleDetailHistoryTouch(")

        assertTrue(strings.contains("<string name=\"profile_automations_title\">自动化</string>"))
        assertTrue(strings.contains("<string name=\"profile_automations_subtitle_empty\">暂无自动化</string>"))
        assertTrue(strings.contains("<string name=\"automation_list_dialog_title\">自动化</string>"))
        assertTrue(layout.contains("android:id=\"@+id/profileAutomationsRow\""))
        assertTrue(layout.indexOf("profileNotificationsRow") < layout.indexOf("profileAutomationsRow"))
        assertTrue(layout.indexOf("profileAutomationsRow") < layout.indexOf("profileConnectionModeRow"))
        assertTrue(setupBlock.contains("binding.profileAutomationsRow.setOnClickListener"))
        assertTrue(setupBlock.contains("showAutomationListDialog()"))
        assertTrue(source.contains("private fun showAutomationListDialog()"))
        assertTrue(source.contains("AutomationListDialogSupport.show("))
        assertTrue(source.contains("viewModel.refreshAutomations"))
        assertTrue(source.contains("binding.profileAutomationsSubtitle.text"))
    }

    @Test
    fun `profile connection mode row appears before socket status row`() {
        val layout = readMainLayout()

        assertTrue(layout.indexOf("profileConnectionModeRow") < layout.indexOf("profileSocketStatusRow"))
    }

    @Test
    fun `profile typing settings row uses unified dialog and persists animation preferences`() {
        val source = readMainActivity()
        val strings = readStrings()
        val layout = readMainLayout()
        val prefs = File(
            "src/main/java/com/codex/mobilecontrol/GatewayPreferences.kt"
        ).readText()
        val setupBlock = source.substringAfter("private fun setupActions()")
            .substringBefore("private fun handleDetailHistoryTouch(")
        val dialogBlock = source.substringAfter("private fun showProfileTypingSettingsDialog()")
            .substringBefore("private fun applyProfileTypingSettings(")
        val applyBlock = source.substringAfter("private fun applyProfileTypingSettings(")
            .substringBefore("private fun bindProfileConnectionMode(")
        val onTextChangedBlock = dialogBlock.substringAfter("override fun onTextChanged(")
            .substringBefore("override fun afterTextChanged(")
        val durationBlock = source.substringAfter("private fun detailTypingAnimationDurationMillis(")
            .substringBefore("private fun failedMessageIndicator(")

        assertTrue(strings.contains("<string name=\"profile_typing_settings_title\">打字动画</string>"))
        assertTrue(strings.contains("<string name=\"profile_typing_settings_enabled\">已开启</string>"))
        assertTrue(strings.contains("<string name=\"profile_typing_settings_disabled\">已关闭</string>"))
        assertTrue(strings.contains("<string name=\"profile_typing_speed_title\">打字速度</string>"))
        assertTrue(strings.contains("<string name=\"profile_typing_speed_normal\">标准</string>"))
        assertTrue(strings.contains("<string name=\"profile_typing_speed_fast\">快速</string>"))
        assertTrue(strings.contains("<string name=\"profile_typing_speed_custom\">自定义</string>"))
        assertTrue(strings.contains("<string name=\"profile_typing_speed_custom_hint\">输入字数</string>"))
        assertTrue(layout.contains("android:id=\"@+id/profileTypingSettingsRow\""))
        assertTrue(layout.indexOf("profileSocketStatusRow") < layout.indexOf("profileTypingSettingsRow"))
        assertTrue(setupBlock.contains("binding.profileTypingSettingsRow.setOnClickListener"))
        assertTrue(setupBlock.contains("showProfileTypingSettingsDialog()"))
        assertTrue(source.contains("private fun bindProfileTypingSettings()"))
        assertTrue(source.contains("GatewayPreferences.loadDetailTypingAnimationEnabled(this)"))
        assertTrue(source.contains("GatewayPreferences.loadDetailTypingCharsPerSecond(this)"))
        assertTrue(dialogBlock.contains("Dialog(this)"))
        assertTrue(dialogBlock.contains("unifiedDialogPanel("))
        assertTrue(dialogBlock.contains("unifiedDialogRow("))
        assertTrue(dialogBlock.contains("SeekBar(this)"))
        assertTrue(dialogBlock.contains("EditText(this)"))
        assertTrue(dialogBlock.contains("profileTypingSeekBar"))
        assertTrue(dialogBlock.contains("profileTypingCustomInput"))
        assertTrue(dialogBlock.contains("updateProfileTypingSeekBarRange("))
        assertFalse(dialogBlock.contains("max = 23"))
        assertFalse(dialogBlock.contains("InputFilter.LengthFilter(2)"))
        assertTrue(dialogBlock.contains("R.string.profile_typing_settings_dialog_confirm"))
        assertTrue(dialogBlock.contains("R.string.profile_typing_settings_dialog_cancel"))
        assertTrue(dialogBlock.contains("applyProfileTypingSettings("))
        assertFalse(onTextChangedBlock.contains("renderRows()"))
        assertTrue(applyBlock.contains("GatewayPreferences.saveDetailTypingAnimationEnabled(this,"))
        assertTrue(applyBlock.contains("GatewayPreferences.saveDetailTypingCharsPerSecond(this,"))
        assertFalse(prefs.contains(".coerceIn(1, 24)"))
        assertFalse(durationBlock.contains("coerceIn(1, 24)"))
        assertFalse(source.contains("ProfileTypingSpeedOption(charsPerSecond = 4"))
    }

    @Test
    fun `profile page can scroll fully above bottom nav`() {
        val source = readMainActivity()
        val layout = readMainLayout()
        val insetsBlock = source.substringAfter("private fun setupWindowInsets()")
            .substringBefore("private fun updateImeVisibility()")

        assertTrue(layout.contains("android:id=\"@+id/profilePage\""))
        assertTrue(layout.contains("android:clipToPadding=\"false\""))
        assertTrue(layout.contains("android:fillViewport=\"true\""))
        assertTrue(layout.contains("android:paddingBottom=\"28dp\""))
        assertTrue(insetsBlock.contains("binding.profilePageContent.updatePadding("))
        assertTrue(insetsBlock.contains("bottom = initialProfileBottomPadding + navigationBarInsets.bottom"))
    }

    @Test
    fun `profile history collapse setting uses dialog with sliders and inputs`() {
        val source = readMainActivity()
        val strings = readStrings()
        val layout = readMainLayout()
        val prefsSource = readGatewayPreferences()
        val timelineSource = readDetailTimelineSupport()
        val setupBlock = source.substringAfter("private fun setupActions()")
            .substringBefore("private fun handleDetailHistoryTouch(")
        val dialogBlock = source.substringAfter("private fun showHistoryCollapseSettingsDialog()")
            .substringBefore("private fun simpleSeekBarListener(")

        assertTrue(strings.contains("<string name=\"profile_history_collapse_title\">历史折叠</string>"))
        assertTrue(strings.contains("<string name=\"profile_history_collapse_dialog_title\">历史折叠</string>"))
        assertTrue(strings.contains("<string name=\"profile_history_collapse_min_duration\">最少处理时长</string>"))
        assertTrue(strings.contains("<string name=\"profile_history_collapse_min_messages\">最少过程消息数</string>"))
        assertTrue(strings.contains("<string name=\"profile_history_collapse_dialog_confirm\">确认</string>"))
        assertTrue(strings.contains("<string name=\"profile_history_collapse_dialog_cancel\">取消</string>"))
        assertTrue(layout.contains("android:id=\"@+id/profileHistoryCollapseRow\""))
        assertTrue(source.contains("binding.profileHistoryCollapseRow.setOnClickListener"))
        assertTrue(setupBlock.contains("showHistoryCollapseSettingsDialog()"))
        assertTrue(source.contains("private fun bindProfileHistoryCollapseSettings()"))
        assertTrue(source.contains("private fun showHistoryCollapseSettingsDialog()"))
        assertTrue(dialogBlock.contains("Dialog(this)"))
        assertTrue(dialogBlock.contains("requestWindowFeature(Window.FEATURE_NO_TITLE)"))
        assertTrue(dialogBlock.contains("unifiedDialogPanel("))
        assertTrue(dialogBlock.contains("unifiedDialogFooterButton("))
        assertTrue(dialogBlock.contains("setContentView(panel)"))
        assertTrue(dialogBlock.contains("styleUnifiedDialogWindow(dialog)"))
        assertFalse(dialogBlock.contains("MaterialAlertDialogBuilder("))
        assertFalse(dialogBlock.contains(".setTitle("))
        assertFalse(dialogBlock.contains(".setView("))
        assertFalse(dialogBlock.contains(".setPositiveButton("))
        assertTrue(dialogBlock.contains("SeekBar(this)"))
        assertTrue(dialogBlock.contains("EditText(this)"))
        assertTrue(source.contains("GatewayPreferences.loadDetailTimelineCollapseSettings(this)"))
        assertTrue(dialogBlock.contains("GatewayPreferences.saveDetailTimelineCollapseSettings("))
        assertTrue(prefsSource.contains("detail_timeline_collapse_settings_v1"))
        assertTrue(prefsSource.contains("fun saveDetailTimelineCollapseSettings("))
        assertTrue(prefsSource.contains("fun loadDetailTimelineCollapseSettings("))
        assertTrue(timelineSource.contains("DetailTimelineCollapseSettings"))
        assertFalse(timelineSource.contains("HISTORY_COLLAPSE_MIN_PROCESS_MESSAGES = 3"))
    }

    @Test
    fun `profile diagnostics export saves only to downloads`() {
        val source = readMainActivity()
        val strings = readStrings()
        val exportBlock = source.substringAfter("private fun exportDiagnosticsBundle()")
            .substringBefore("private fun saveDiagnosticBundleToDownloads(")

        assertTrue(source.contains("private fun saveDiagnosticBundleToDownloads("))
        assertTrue(source.contains("MediaStore.Downloads.EXTERNAL_CONTENT_URI"))
        assertTrue(source.contains("Environment.DIRECTORY_DOWNLOADS"))
        assertTrue(source.contains("DOWNLOADS_APP_SUBDIRECTORY = \"CodexMobile\""))
        assertTrue(source.contains("\"${'$'}DOWNLOADS_RELATIVE_DIR/${'$'}DOWNLOADS_APP_SUBDIRECTORY\""))
        assertTrue(source.contains("profile_export_diagnostics_saved_to_downloads"))
        assertFalse(exportBlock.contains("FileProvider.getUriForFile"))
        assertFalse(exportBlock.contains("Intent.ACTION_SEND"))
        assertFalse(exportBlock.contains("Intent.createChooser"))
        assertFalse(strings.contains("profile_export_diagnostics_share_title"))
        assertTrue(strings.contains("<string name=\"profile_export_diagnostics_title\">导出日志</string>"))
        assertTrue(strings.contains("<string name=\"profile_export_diagnostics_saved_to_downloads\">已保存到下载</string>"))
    }

    @Test
    fun `apk update downloads into dedicated codex mobile subdirectory`() {
        val source = readMainActivity()
        val downloadBlock = source.substringAfter("private fun downloadLatestApk()")
            .substringBefore("private fun latestApkDownloadFileName(")

        assertTrue(downloadBlock.contains("setDestinationInExternalPublicDir("))
        assertTrue(downloadBlock.contains("DOWNLOADS_RELATIVE_DIR"))
        assertTrue(downloadBlock.contains("\"${'$'}DOWNLOADS_APP_SUBDIRECTORY/${'$'}{latestApkDownloadFileName(latestApkInfo)}\""))
    }

    @Test
    fun `detail page keeps bottom when keyboard opens from bottom`() {
        val source = readMainActivity()
        val focusBlock = source.substringAfter("binding.messageInput.setOnFocusChangeListener")
            .substringBefore("binding.messageInput.addTextChangedListener")
        val clickBlock = source.substringAfter("binding.messageInput.setOnClickListener")
            .substringBefore("binding.messageInput.addTextChangedListener")

        assertTrue(source.contains("private fun requestDetailKeyboardBottomScrollIfNeeded()"))
        assertTrue(source.contains("private fun isDetailScrolledToBottom()"))
        assertTrue(source.contains("private fun scrollDetailToBottomAfterLayout()"))
        assertTrue(focusBlock.contains("requestDetailKeyboardBottomScrollIfNeeded()"))
        assertTrue(clickBlock.contains("requestDetailKeyboardBottomScrollIfNeeded()"))
    }

    @Test
    fun `detail page restores bottom after keyboard closes from sticky bottom`() {
        val source = readMainActivity()
        val updateImeBlock = source.substringAfter("private fun updateImeVisibility()")
            .substringBefore("private fun refreshImeVisibilitySnapshot()")
        val restoreBlock = source.substringAfter("private fun restoreDetailBottomAfterKeyboardClose()")
            .substringBefore("private fun refreshImeVisibilitySnapshot()")

        assertTrue(updateImeBlock.contains("val wasImeVisible = isImeVisible"))
        assertTrue(updateImeBlock.contains("val shouldRestoreDetailBottomAfterImeClose ="))
        assertTrue(updateImeBlock.contains("restoreDetailBottomAfterKeyboardClose()"))
        assertTrue(restoreBlock.contains("requestDetailScrollToBottom()"))
        assertTrue(restoreBlock.contains("scrollDetailToBottomAfterLayout()"))
        assertTrue(restoreBlock.contains("KEYBOARD_BOTTOM_SCROLL_DELAY_MS"))
    }

    @Test
    fun `detail page keeps bottom across state refreshes when already at bottom`() {
        val source = readMainActivity()
        val stateCollectBlock = source.substringAfter("viewModel.state.collect { state ->")
            .substringBefore("bindDetailPage(state)")

        assertTrue(source.contains("private fun requestDetailUpdateBottomScrollIfNeeded()"))
        assertTrue(source.contains("private fun isDetailScrolledToBottom()"))
        assertTrue(stateCollectBlock.contains("requestDetailUpdateBottomScrollIfNeeded()"))
    }

    private fun readMainActivity(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/MainActivity.kt"),
            File("src/main/java/com/codex/mobilecontrol/MainActivity.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("MainActivity.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }

    private fun readMainLayout(): String {
        val candidates = listOf(
            File("app/src/main/res/layout/activity_main.xml"),
            File("src/main/res/layout/activity_main.xml")
        )
        val layoutFile = candidates.firstOrNull(File::isFile)
            ?: error("activity_main.xml not found from ${System.getProperty("user.dir")}")

        return layoutFile.readText()
    }

    private fun readGatewayPreferences(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/GatewayPreferences.kt"),
            File("src/main/java/com/codex/mobilecontrol/GatewayPreferences.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("GatewayPreferences.kt not found from ${System.getProperty("user.dir")}")

        return sourceFile.readText()
    }

    private fun readDetailTimelineSupport(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/ui/DetailTimelineSupport.kt"),
            File("src/main/java/com/codex/mobilecontrol/ui/DetailTimelineSupport.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("DetailTimelineSupport.kt not found from ${System.getProperty("user.dir")}")

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
}
