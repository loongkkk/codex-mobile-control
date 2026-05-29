package com.codex.mobilecontrol

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import coil.load
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codex.mobilecontrol.databinding.ActivityMainBinding
import com.codex.mobilecontrol.diagnostics.DiagnosticBundleInput
import com.codex.mobilecontrol.diagnostics.DiagnosticBundleWriter
import com.codex.mobilecontrol.diagnostics.DebugTraceLogger
import com.codex.mobilecontrol.diagnostics.GatewayDiagnosticsFetchResult
import com.codex.mobilecontrol.diagnostics.fetchGatewayDiagnosticsForBundle
import com.codex.mobilecontrol.model.DetailRefreshResult
import com.codex.mobilecontrol.model.DetailRefreshStatus
import com.codex.mobilecontrol.model.GatewayConnectionMode
import com.codex.mobilecontrol.model.LatestApkInfo
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.PendingFileDraft
import com.codex.mobilecontrol.model.PendingImageDraft
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.QueuedTextMessageStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import com.codex.mobilecontrol.model.ThreadMessageSendState
import com.codex.mobilecontrol.network.GatewayRealtimeEvent
import com.codex.mobilecontrol.network.StreamConnectionState
import com.codex.mobilecontrol.ui.AppGraph
import com.codex.mobilecontrol.ui.AutomationListDialogSupport
import com.codex.mobilecontrol.ui.ComposerAttachmentRowSupport
import com.codex.mobilecontrol.ui.ComposerAttachmentSupport
import com.codex.mobilecontrol.ui.ConnectedScreen
import com.codex.mobilecontrol.ui.ConnectedShellState
import com.codex.mobilecontrol.ui.ComposerDraftStore
import com.codex.mobilecontrol.ui.ComposerStatusContent
import com.codex.mobilecontrol.ui.ComposerStatusSupport
import com.codex.mobilecontrol.ui.ComposerSendModeSupport
import com.codex.mobilecontrol.ui.ConversationBubbleWidthSupport
import com.codex.mobilecontrol.ui.DetailAlertSupport
import com.codex.mobilecontrol.ui.DetailAlertSettingsDialogSupport
import com.codex.mobilecontrol.ui.DetailAlertSettingsSelection
import com.codex.mobilecontrol.ui.DetailChecklistItem
import com.codex.mobilecontrol.ui.DetailConversationItem
import com.codex.mobilecontrol.ui.DetailErrorContent
import com.codex.mobilecontrol.ui.DetailFileChangeItem
import com.codex.mobilecontrol.ui.DetailRunningStatusSupport
import com.codex.mobilecontrol.ui.DetailTimelineCollapseSettings
import com.codex.mobilecontrol.ui.DetailTimelineSupport
import com.codex.mobilecontrol.ui.DetailTypingAnimatorSupport
import com.codex.mobilecontrol.ui.DetailVisualContent
import com.codex.mobilecontrol.ui.DetailVisualSupport
import com.codex.mobilecontrol.ui.MainUiState
import com.codex.mobilecontrol.ui.MainViewModel
import com.codex.mobilecontrol.ui.MarkdownPreviewSupport
import com.codex.mobilecontrol.ui.MobileUiFormatter
import com.codex.mobilecontrol.ui.ThreadListAdapter
import com.codex.mobilecontrol.ui.ThreadNotificationSettingsDialogSupport
import com.codex.mobilecontrol.ui.ThreadNotificationSettingsSelection
import com.codex.mobilecontrol.ui.ThreadNotificationSettingsThread
import com.codex.mobilecontrol.ui.ThreadStatusIndicatorSupport
import com.codex.mobilecontrol.ui.UnifiedDialogAction
import com.codex.mobilecontrol.ui.UnifiedDialogSupport
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { AppGraph.repositoryFactory(applicationContext) }
    private val viewModel by lazy {
        ViewModelProvider(this, MainViewModel.Factory(repository))[MainViewModel::class.java]
    }
    private val markwon by lazy { Markwon.create(this) }
    private val motionInterpolator = FastOutSlowInInterpolator()
    private lateinit var threadAdapter: ThreadListAdapter
    private var currentScreen: ConnectedScreen = ConnectedScreen.TASKS
    private var lastRenderedShellState: ConnectedShellState? = null
    private val readThreadStatusIndicatorKeys = mutableMapOf<String, String?>()
    private var lastIsConnected = false
    private var lastIsConnecting = false
    private var lastIsSending = false
    private var lastIsRefreshingThreads = false
    private var detailSocketPulseAnimator: AnimatorSet? = null
    private var lastDetailStatusIndicatorState: ThreadStatusIndicatorSupport.IndicatorState? = null
    private var detailTypingAnimator: ValueAnimator? = null
    private var detailTypingMessageId: String? = null
    private var detailTypingMessageKey: String? = null
    private var taskRefreshSuccessToastPending = false
    private var detailRefreshSuccessToastPending = false
    private var lastIsLoadingOlderMessages = false
    private var isImeVisible = false
    private var isImeVisibleFromInsets = false
    private var isImeVisibleFromLayout = false
    private var composerKeyboardRequestedAtMs = 0L
    private var lastComposerStatusText: String = ""
    private var pendingDetailThreadId: String? = null
    private var pendingDetailTitle: String? = null
    private var lastRenderedDetailThreadId: String? = null
    private var shouldScrollDetailToBottom = false
    private var keepDetailBottomForKeyboard = false
    private var keepDetailBottomForTyping = false
    private var motionOverlayShownAtMs = 0L
    private var activeMotionOverlayKey: String? = null
    private var pendingMotionOverlayHide: Runnable? = null
    private var lastShownErrorMessage: String? = null
    private var expandedFileChangesThreadId: String? = null
    private val expandedConversationHistoryKeysByThreadId = mutableMapOf<String, MutableSet<String>>()
    private val lastDetailTypingMessageKeyByThreadId = mutableMapOf<String, String?>()
    private val lastDetailTypingTextByThreadId = mutableMapOf<String, String?>()
    private val detailTypingVisibleCharsByMessageKey = mutableMapOf<String, Int>()
    private var pendingOlderMessagesScrollAnchor: OlderMessagesScrollAnchor? = null
    private var lastDetailTouchY: Float? = null
    private var detailHistoryPullDistancePx: Float = 0f
    private val composerDraftStore = ComposerDraftStore()
    private val alertEnabledThreadIds = mutableSetOf<String>()
    private val alertMessageEnabledThreadIds = mutableSetOf<String>()
    private val alertCompletionEnabledThreadIds = mutableSetOf<String>()
    private val handledRealtimeNotificationIds = mutableSetOf<String>()
    private val lastAlertMessageKeyByThreadId = mutableMapOf<String, String?>()
    private val notifiedAlertMessageKeysByThreadId = mutableMapOf<String, MutableSet<String>>()
    private val lastCompletionAlertKeyByThreadId = mutableMapOf<String, String?>()
    private var detailAlertRefreshJob: Job? = null
    private var detailRuntimeSubtitleRefreshJob: Job? = null
    private var pendingAlertPermissionThreadId: String? = null
    private var pendingAlertSelection: DetailAlertSelection? = null
    private var lastTaskListDiagnosticSignature: TaskListDiagnosticSignature? = null
    private var lastTaskListLayoutSignature: String? = null
    private var lastTaskListScrollLogAtMs = 0L
    private var lastDetailScrollLogAtMs = 0L
    private var lastBottomNavHiddenForIme: Boolean? = null
    private var diagnosticsExportJob: Job? = null

    private data class MotionOverlayModel(
        val key: String,
        val title: String,
        val subtitle: String
    )

    private data class OlderMessagesScrollAnchor(
        val contentHeight: Int,
        val scrollY: Int
    )

    private data class DetailAlertSelection(
        val messageEnabled: Boolean,
        val completionEnabled: Boolean
    )

    private data class TaskListDiagnosticSignature(
        val screen: ConnectedScreen,
        val isConnected: Boolean,
        val isRefreshingThreads: Boolean,
        val selectedThreadId: String?,
        val threadCount: Int,
        val firstThreadIds: String,
        val firstThreadStatuses: String,
        val firstThreadUpdatedAts: String
    )

    private data class DetailTypingSpec(
        val messageId: String,
        val messageKey: String,
        val markdown: String,
        val typingText: String,
        val startIndex: Int
    )

    private data class ProfileTypingSpeedOption(
        val charsPerSecond: Int,
        val titleResId: Int
    )

    private var profileConnectionMode = GatewayConnectionMode.SOCKET
    private var profileBackgroundConnectionEnabled = false
    private var detailTimelineCollapseSettings = DetailTimelineCollapseSettings()

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) {
            return@registerForActivityResult
        }

        viewModel.addPendingImages(
            uris.map { uri ->
                val fileName = contentResolver.queryDisplayName(uri) ?: "selected-image"
                val mimeType = contentResolver.getType(uri) ?: "image/*"
                PendingImageDraft(
                    previewUri = uri.toString(),
                    fileName = fileName,
                    mimeType = mimeType
                )
            }
        )
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) {
            return@registerForActivityResult
        }

        viewModel.addPendingFiles(
            uris.map { uri ->
                val fileName = contentResolver.queryDisplayName(uri) ?: "selected-file"
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                PendingFileDraft(
                    previewUri = uri.toString(),
                    fileName = fileName,
                    mimeType = mimeType
                )
            }
        )
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val threadId = pendingAlertPermissionThreadId
        val selection = pendingAlertSelection
        pendingAlertPermissionThreadId = null
        pendingAlertSelection = null
        if (granted && threadId != null) {
            val nextSelection = selection ?: DetailAlertSelection(
                messageEnabled = true,
                completionEnabled = true
            )
            applyDetailAlertSettings(
                threadId = threadId,
                messageEnabled = nextSelection.messageEnabled,
                completionEnabled = nextSelection.completionEnabled
            )
        } else {
            Toast.makeText(this, R.string.detail_alert_permission_required, Toast.LENGTH_SHORT).show()
            syncDetailAlertUi(currentDetailThreadId(), enabled = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugTraceLogger.initialize(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupBackHandling()
        setupLists()
        setupActions()
        observeState()
        createDetailNotificationChannel()
        profileConnectionMode = GatewayPreferences.loadConnectionMode(this)
        profileBackgroundConnectionEnabled = GatewayPreferences.loadBackgroundConnectionEnabled(this)
        detailTimelineCollapseSettings = GatewayPreferences.loadDetailTimelineCollapseSettings(this)
        readThreadStatusIndicatorKeys.putAll(GatewayPreferences.loadReadThreadStatusIndicatorKeys(this))
        alertEnabledThreadIds.addAll(GatewayPreferences.loadAlertEnabledThreadIds(this))
        alertMessageEnabledThreadIds.addAll(
            GatewayPreferences.loadAlertMessageEnabledThreadIds(this)
                .filter { it in alertEnabledThreadIds }
        )
        alertCompletionEnabledThreadIds.addAll(
            GatewayPreferences.loadAlertCompletionEnabledThreadIds(this)
                .filter { it in alertEnabledThreadIds }
        )
        lastAlertMessageKeyByThreadId.putAll(GatewayPreferences.loadAlertMessageKeys(this))
        notifiedAlertMessageKeysByThreadId.putAll(
            GatewayPreferences.loadAlertNotifiedMessageKeys(this)
                .mapValues { (_, keys) -> keys.toMutableSet() }
        )
        lastCompletionAlertKeyByThreadId.putAll(GatewayPreferences.loadAlertCompletionKeys(this))
        lastAlertMessageKeyByThreadId.keys.retainAll(alertMessageEnabledThreadIds)
        notifiedAlertMessageKeysByThreadId.keys.retainAll(alertMessageEnabledThreadIds)
        lastCompletionAlertKeyByThreadId.keys.retainAll(alertCompletionEnabledThreadIds)
        if (alertEnabledThreadIds.isNotEmpty()) {
            AlertPollingScheduler.schedule(this)
            scheduleDetailAlertRefresh()
        } else {
            AlertPollingScheduler.cancel(this)
        }
        viewModel.updateRealtimeNotificationPreferences(alertEnabledThreadIds)
        observeRealtimeNotifications()

        val requestedThreadId = intent.getStringExtra(EXTRA_THREAD_ID)
        val config = intentConfig() ?: GatewayPreferences.loadConfig(this)
        if (config != null) {
            currentScreen = if (requestedThreadId != null) {
                ConnectedScreen.DETAIL
            } else {
                ConnectedScreen.TASKS
            }
            if (requestedThreadId != null) {
                requestDetailScrollToBottom()
            }
            bindLoginInputs(config)
            viewModel.connect(config, requestedThreadId)
            if (profileBackgroundConnectionEnabled) {
                RealtimeForegroundService.setEnabled(this, true)
            }
        } else {
            bindLoginInputs(null)
            showLoginState()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) {
            return
        }
        if (alertEnabledThreadIds.isNotEmpty()) {
            scheduleDetailAlertRefresh()
        }
        DetailNotificationSupport.refreshSummaryNotification(
            context = this
        )
        hideComposerKeyboardForPassiveDetailEntry()
        refreshVisibleDetailOnForeground()
    }

    private fun hideComposerKeyboardForPassiveDetailEntry() {
        if (currentScreen != ConnectedScreen.DETAIL) {
            return
        }
        hideComposerKeyboard()
    }

    private fun refreshVisibleDetailOnForeground() {
        val state = viewModel.state.value
        if (currentScreen != ConnectedScreen.DETAIL || !state.isConnected) {
            return
        }
        val threadId = state.selectedThreadId ?: state.detail?.thread?.threadId ?: return
        viewModel.refreshThreadDetail(threadId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedThreadId = intent.getStringExtra(EXTRA_THREAD_ID)
        val explicitConfig = intentConfig()
        val isImplicitLauncherResume = requestedThreadId == null && explicitConfig == null &&
            binding.connectedShell.visibility == View.VISIBLE
        if (isImplicitLauncherResume) {
            return
        }
        val config = explicitConfig ?: GatewayPreferences.loadConfig(this) ?: return
        currentScreen = if (requestedThreadId != null) {
            ConnectedScreen.DETAIL
        } else {
            ConnectedScreen.TASKS
        }
        if (requestedThreadId != null) {
            requestDetailScrollToBottom()
            hideComposerKeyboardForPassiveDetailEntry()
        }
        bindLoginInputs(config)
        viewModel.connect(config, requestedThreadId)
    }

    override fun onStop() {
        super.onStop()
        cancelDetailAlertRefresh()
        cancelDetailRuntimeSubtitleRefresh()
        stopDetailSocketPulse()
        lastDetailStatusIndicatorState = null
    }

    override fun onDestroy() {
        diagnosticsExportJob?.cancel()
        diagnosticsExportJob = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::binding.isInitialized &&
            currentScreen == ConnectedScreen.DETAIL &&
            isTouchInsideDetailScroll(event)
        ) {
            handleDetailHistoryTouch(event)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isTouchInsideDetailScroll(event: MotionEvent): Boolean {
        if (binding.detailScroll.visibility != View.VISIBLE) {
            return false
        }
        val location = IntArray(2)
        binding.detailScroll.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        return event.rawX >= left &&
            event.rawX <= left + binding.detailScroll.width &&
            event.rawY >= top &&
            event.rawY <= top + binding.detailScroll.height
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    refreshImeVisibilitySnapshot()
                    if (isComposerKeyboardOpen()) {
                        hideComposerKeyboard()
                        return
                    }
                    if (binding.connectedShell.visibility == View.VISIBLE &&
                        currentScreen != ConnectedScreen.TASKS
                    ) {
                        showTaskScreen()
                        return
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        )
    }

    private fun setupWindowInsets() {
        val initialTaskTopPadding = binding.taskPageContent.paddingTop
        val initialDetailTopPadding = binding.detailPage.paddingTop
        val initialDetailBottomPadding = binding.detailPage.paddingBottom
        val initialProfileTopPadding = binding.profilePageContent.paddingTop
        val initialProfileBottomPadding = binding.profilePageContent.paddingBottom
        val initialBottomNavHeight = binding.bottomNavCard.layoutParams.height
        val initialBottomNavItemsBottomPadding = binding.bottomNavItems.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val navigationBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            isImeVisibleFromInsets =
                windowInsets.isVisible(WindowInsetsCompat.Type.ime()) ||
                    imeInsets.bottom > navigationBarInsets.bottom
            val imeBottomInset = if (isImeVisibleFromInsets) {
                maxOf(0, imeInsets.bottom - navigationBarInsets.bottom)
            } else {
                0
            }
            updateImeVisibility()
            binding.taskPageContent.updatePadding(top = initialTaskTopPadding + statusBarInsets.top)
            binding.detailPage.updatePadding(
                top = initialDetailTopPadding + statusBarInsets.top,
                bottom = initialDetailBottomPadding + imeBottomInset
            )
            binding.profilePageContent.updatePadding(
                top = initialProfileTopPadding + statusBarInsets.top,
                bottom = initialProfileBottomPadding + navigationBarInsets.bottom
            )
            binding.bottomNavCard.updateLayoutParams {
                height = initialBottomNavHeight + navigationBarInsets.bottom
            }
            binding.bottomNavItems.updatePadding(
                bottom = initialBottomNavItemsBottomPadding + navigationBarInsets.bottom
            )
            if (keepDetailBottomForKeyboard && isImeVisible && currentScreen == ConnectedScreen.DETAIL) {
                scrollDetailToBottomAfterLayout()
            }
            windowInsets
        }
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleFrame = Rect()
            binding.root.getWindowVisibleDisplayFrame(visibleFrame)
            val obscuredHeight = binding.root.rootView.height - visibleFrame.bottom
            val fallbackVisible = obscuredHeight > dp(KEYBOARD_VISIBILITY_THRESHOLD_DP)
            if (isImeVisibleFromLayout != fallbackVisible) {
                isImeVisibleFromLayout = fallbackVisible
                updateImeVisibility()
            }
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateImeVisibility() {
        val wasImeVisible = isImeVisible
        val wasKeepingDetailBottom = keepDetailBottomForKeyboard
        isImeVisible = isImeVisibleFromInsets || isImeVisibleFromLayout
        val shouldRestoreDetailBottomAfterImeClose =
            wasImeVisible &&
                !isImeVisible &&
                wasKeepingDetailBottom &&
                currentScreen == ConnectedScreen.DETAIL
        if (isImeVisible) {
            composerKeyboardRequestedAtMs = 0L
        } else {
            keepDetailBottomForKeyboard = false
            if (shouldRestoreDetailBottomAfterImeClose) {
                restoreDetailBottomAfterKeyboardClose()
            }
        }
        syncBottomNavForIme()
    }

    private fun restoreDetailBottomAfterKeyboardClose() {
        requestDetailScrollToBottom()
        scrollDetailToBottomAfterLayout()
        binding.detailScroll.postDelayed({
            if (currentScreen == ConnectedScreen.DETAIL) {
                scrollDetailToBottomAfterLayout()
            }
        }, KEYBOARD_BOTTOM_SCROLL_DELAY_MS)
        binding.detailScroll.postDelayed({
            if (currentScreen == ConnectedScreen.DETAIL) {
                scrollDetailToBottomAfterLayout()
            }
        }, KEYBOARD_BOTTOM_SCROLL_FINAL_DELAY_MS)
    }

    private fun refreshImeVisibilitySnapshot() {
        ViewCompat.getRootWindowInsets(binding.root)?.let { windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val navigationBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            isImeVisibleFromInsets =
                windowInsets.isVisible(WindowInsetsCompat.Type.ime()) ||
                    imeInsets.bottom > navigationBarInsets.bottom
        }

        val visibleFrame = Rect()
        binding.root.getWindowVisibleDisplayFrame(visibleFrame)
        val obscuredHeight = binding.root.rootView.height - visibleFrame.bottom
        isImeVisibleFromLayout = obscuredHeight > dp(KEYBOARD_VISIBILITY_THRESHOLD_DP)
        updateImeVisibility()
    }

    private fun syncBottomNavForIme() {
        val shouldHideBottomNav =
            currentScreen == ConnectedScreen.DETAIL && isImeVisible
        if (lastBottomNavHiddenForIme != shouldHideBottomNav) {
            lastBottomNavHiddenForIme = shouldHideBottomNav
            logTaskListState(
                "bottomNav.ime",
                "hidden=$shouldHideBottomNav screen=$currentScreen ime=$isImeVisible " +
                    "taskScrollY=${binding.taskPage.scrollY}"
            )
        }
        binding.bottomNavCard.visibility = if (shouldHideBottomNav) View.GONE else View.VISIBLE
    }

    private fun markThreadStatusIndicatorRead(thread: ThreadListItem) {
        val readKey = ThreadStatusIndicatorSupport.settledReadKey(thread) ?: return
        if (readThreadStatusIndicatorKeys[thread.threadId] == readKey) {
            return
        }
        readThreadStatusIndicatorKeys[thread.threadId] = readKey
        GatewayPreferences.saveReadThreadStatusIndicatorKeys(this, readThreadStatusIndicatorKeys)
        threadAdapter.submitThreads(viewModel.state.value.threads, readThreadStatusIndicatorKeys)
    }

    private fun markAllThreadStatusIndicatorsRead() {
        val threads = viewModel.state.value.threads
        val currentThreadIds = threads.map { it.threadId }.toSet()
        readThreadStatusIndicatorKeys.keys.retainAll(currentThreadIds)
        threads.forEach { thread ->
            val readKey = ThreadStatusIndicatorSupport.settledReadKey(thread) ?: return@forEach
            readThreadStatusIndicatorKeys[thread.threadId] = readKey
        }
        GatewayPreferences.saveReadThreadStatusIndicatorKeys(this, readThreadStatusIndicatorKeys)
        threadAdapter.submitThreads(viewModel.state.value.threads, readThreadStatusIndicatorKeys)
        Toast.makeText(this, R.string.task_mark_all_read_success, Toast.LENGTH_SHORT).show()
    }

    private fun setupLists() {
        threadAdapter = ThreadListAdapter { thread ->
            logTaskListState(
                "thread.tap",
                "thread=${thread.threadId.logId()} title=${thread.title.logCompact()} " +
                    "status=${thread.status} taskScrollY=${binding.taskPage.scrollY}"
            )
            markThreadStatusIndicatorRead(thread)
            openThreadDetail(thread.threadId)
        }

        binding.threadDrawerRecycler.layoutManager = LinearLayoutManager(this)
        binding.threadDrawerRecycler.itemAnimator = null
        binding.threadDrawerRecycler.adapter = threadAdapter
        binding.taskPage.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (shouldLogTaskListScroll(scrollY - oldScrollY)) {
                logTaskListState(
                    "task.scroll",
                    "oldY=$oldScrollY newY=$scrollY delta=${scrollY - oldScrollY} " +
                        "contentHeight=${binding.taskPageContent.height}"
                )
            }
        }
        binding.threadDrawerRecycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (shouldLogTaskListScroll(dy)) {
                        logTaskListState(
                            "recycler.scroll",
                            "dx=$dx dy=$dy " +
                                "first=${taskListFirstVisiblePosition()} " +
                                "last=${taskListLastVisiblePosition()} " +
                                "taskScrollY=${binding.taskPage.scrollY}"
                        )
                    }
                }
            }
        )
        binding.threadDrawerRecycler.addOnLayoutChangeListener { _, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom ->
            val signature =
                "$left,$top,$right,$bottom/$oldLeft,$oldTop,$oldRight,$oldBottom/" +
                    "${binding.taskPage.scrollY}/${threadAdapter.itemCount}"
            if (signature != lastTaskListLayoutSignature) {
                lastTaskListLayoutSignature = signature
                logTaskListState(
                    "recycler.layout",
                    "new=[$left,$top][$right,$bottom] old=[$oldLeft,$oldTop][$oldRight,$oldBottom] " +
                        "adapterItems=${threadAdapter.itemCount} taskScrollY=${binding.taskPage.scrollY} " +
                        "taskBounds=${binding.taskPage.diagnosticBounds()} " +
                        "recyclerBounds=${binding.threadDrawerRecycler.diagnosticBounds()}"
                )
            }
        }
    }

    private fun setupActions() {
        binding.connectButton.setOnClickListener {
            val gatewayUrl = binding.gatewayUrlInput.text?.toString()?.trim().orEmpty()
            val token = binding.tokenInput.text?.toString()?.trim().orEmpty()
            if (gatewayUrl.isBlank()) {
                binding.helperText.text = getString(R.string.error_missing_gateway_url)
                binding.helperText.setTextColor(
                    ContextCompat.getColor(this, R.color.login_error_text)
                )
                return@setOnClickListener
            }
            if (token.isBlank()) {
                binding.helperText.text = getString(R.string.error_missing_token)
                binding.helperText.setTextColor(
                    ContextCompat.getColor(this, R.color.login_error_text)
                )
                return@setOnClickListener
            }

            currentScreen = ConnectedScreen.TASKS
            showLoginMotionOverlay()
            viewModel.connect(GatewayDefaults.configFor(gatewayUrl, token), null)
        }

        binding.detailBackButton.setOnClickListener {
            showTaskScreen()
        }
        binding.detailRefreshButton.setOnClickListener {
            DebugTraceLogger.log(
                "detail-refresh",
                "manual.tap thread=${viewModel.state.value.selectedThreadId ?: "none"}"
            )
            showDetailRefreshTapFeedback()
            detailRefreshSuccessToastPending = true
            refreshCurrentDetail()
        }
        binding.detailAlertButton.setOnClickListener {
            showDetailAlertSettingsDialog()
        }
        binding.detailJumpToBottomButton.setOnClickListener {
            requestDetailScrollToBottom()
            scrollDetailToBottomAfterLayout()
        }

        binding.navTasks.setOnClickListener {
            logTaskListState(
                "nav.tasks.tap",
                "from=$currentScreen taskScrollY=${binding.taskPage.scrollY}"
            )
            showTaskScreen()
        }

        binding.navDetail.setOnClickListener {
            val state = viewModel.state.value
            val targetThreadId = state.selectedThreadId ?: state.threads.firstOrNull()?.threadId
            logTaskListState(
                "nav.detail.tap",
                "from=$currentScreen target=${targetThreadId.logId()} taskScrollY=${binding.taskPage.scrollY}"
            )
            if (targetThreadId != null) {
                openThreadDetail(targetThreadId)
            }
        }

        binding.navProfile.setOnClickListener {
            logTaskListState(
                "nav.profile.tap",
                "from=$currentScreen taskScrollY=${binding.taskPage.scrollY}"
            )
            showProfileScreen()
        }

        binding.taskRefreshButton.setOnClickListener {
            logTaskListState(
                "task.refresh.tap",
                "screen=$currentScreen count=${viewModel.state.value.threads.size} " +
                    "taskScrollY=${binding.taskPage.scrollY}"
            )
            showTaskRefreshTapFeedback()
            taskRefreshSuccessToastPending = true
            viewModel.refreshThreads()
        }
        binding.taskMarkAllReadButton.setOnClickListener {
            logTaskListState(
                "task.markAllRead.tap",
                "screen=$currentScreen count=${viewModel.state.value.threads.size} " +
                    "taskScrollY=${binding.taskPage.scrollY}"
            )
            markAllThreadStatusIndicatorsRead()
        }

        binding.profileNotificationsRow.setOnClickListener {
            showGlobalThreadNotificationSettingsDialog()
        }

        binding.profileAutomationsRow.setOnClickListener {
            showAutomationListDialog()
        }

        binding.profileConnectionModeRow.setOnClickListener {
            showProfileConnectionModeDialog()
        }

        binding.profileBackgroundConnectionRow.setOnClickListener {
            applyProfileBackgroundConnectionEnabled(!profileBackgroundConnectionEnabled)
        }

        binding.profileTypingSettingsRow.setOnClickListener {
            showProfileTypingSettingsDialog()
        }

        binding.profileHistoryCollapseRow.setOnClickListener {
            showHistoryCollapseSettingsDialog()
        }

        binding.profileSocketReconnectButton.setOnClickListener {
            reconnectProfileSocket()
        }

        binding.profileSocketLatencyButton.setOnClickListener {
            viewModel.testSocketLatency()
        }

        binding.profileDownloadApkRow.setOnClickListener {
            handleVersionUpdateClick()
        }

        binding.profileClearCacheRow.setOnClickListener {
            viewModel.clearThreadCache()
            Toast.makeText(this, R.string.profile_clear_cache_success, Toast.LENGTH_SHORT).show()
        }

        binding.profileExportDiagnosticsRow.setOnClickListener {
            exportDiagnosticsBundle()
        }

        binding.profileLogoutRow.setOnClickListener {
            performLogout()
        }

        binding.pickImageButton.setOnClickListener {
            showAttachmentPicker()
        }

        binding.messageInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                requestDetailKeyboardBottomScrollIfNeeded()
            }
            syncBottomNavForIme()
        }
        binding.messageInput.setOnClickListener {
            requestDetailKeyboardBottomScrollIfNeeded()
            binding.messageInput.requestFocus()
            showComposerKeyboard()
        }
        binding.messageInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    composerDraftStore.onTextChanged(text?.toString().orEmpty())
                }

                override fun afterTextChanged(editable: Editable?) = Unit
            }
        )

        binding.detailScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val scrollDelta = scrollY - oldScrollY
            if (shouldLogDetailScroll(scrollDelta)) {
                logDetailScrollState(
                    "detail.scroll",
                    "old=$oldScrollY new=$scrollY delta=$scrollDelta"
                )
            }
            syncDetailJumpToBottomButton(scrollY)
            if (!isDetailScrolledToBottom()) {
                keepDetailBottomForKeyboard = false
            }
            maybeLoadOlderMessages(scrollY = scrollY, oldScrollY = oldScrollY)
        }

        binding.sendButton.setOnClickListener {
            val text = binding.messageInput.text?.toString()?.trim().orEmpty()
            val pendingImages = viewModel.state.value.pendingImageDrafts
            val pendingFiles = viewModel.state.value.pendingFileDrafts
            if (pendingImages.isEmpty() && pendingFiles.isEmpty() && text.isBlank()) {
                return@setOnClickListener
            }
            if (shouldConfirmRunningGuideMessage(text, pendingImages, pendingFiles)) {
                showRunningMessageSendTypeDialog(text)
                return@setOnClickListener
            }
            if (pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()) {
                viewModel.sendAttachmentMessage(
                    text = text,
                    openImageStream = { draft ->
                        contentResolver.openInputStream(Uri.parse(draft.previewUri))
                            ?: throw IOException("image_open_failed")
                    },
                    openFileStream = { draft ->
                        contentResolver.openInputStream(Uri.parse(draft.previewUri))
                            ?: throw IOException("file_open_failed")
                    }
                ) {
                    binding.messageInput.text?.clear()
                }
            } else if (text.isNotBlank()) {
                viewModel.sendMessage(text) {
                    binding.messageInput.text?.clear()
                }
            }
        }

        binding.composerStatus.setOnClickListener {
            showQueuedMessagesDialog()
        }
    }

    private fun shouldConfirmRunningGuideMessage(
        text: String,
        pendingImages: List<PendingImageDraft>,
        pendingFiles: List<PendingFileDraft>
    ): Boolean {
        return ComposerSendModeSupport.shouldConfirmRunningTextSend(
            text = text,
            hasPendingAttachments = pendingImages.isNotEmpty() || pendingFiles.isNotEmpty(),
            detail = viewModel.state.value.detail
        )
    }

    private fun showRunningMessageSendTypeDialog(text: String) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.running_guide_message_title)
            .setMessage(R.string.running_guide_message_message)
            .setNeutralButton(R.string.profile_connection_mode_dialog_cancel, null)
            .setNegativeButton(R.string.running_queue_message_confirm) { _, _ ->
                viewModel.queueMessageAfterCurrentTurn(text) { _ ->
                    binding.messageInput.text?.clear()
                    showQueuedMessageToast()
                }
            }
            .setPositiveButton(R.string.running_guide_message_confirm) { _, _ ->
                viewModel.sendMessage(text, guide = true) {
                    binding.messageInput.text?.clear()
                }
            }
            .show()
        styleDetailStatusDialog(dialog)
    }

    private fun showQueuedMessageToast() {
        Toast.makeText(this, R.string.running_queue_snackbar, Toast.LENGTH_SHORT).show()
    }

    private fun showQueuedMessagesDialog() {
        val state = viewModel.state.value
        val messages = queuedMessagesForThread(state.queuedTextMessages, state.selectedThreadId)
        if (messages.isEmpty()) {
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = unifiedDialogPanel(getString(R.string.running_queue_dialog_title, messages.size))
        messages.forEachIndexed { index, message ->
            panel.addView(
                unifiedDialogRow(
                    title = getString(
                        R.string.running_queue_dialog_item_title,
                        index + 1,
                        queueStatusLabel(message.status)
                    ),
                    subtitle = getString(
                        R.string.running_queue_dialog_item_subtitle,
                        MobileUiFormatter.formatQueuedMessageTime(message.queuedAtMillis),
                        ComposerStatusSupport.queuedTextPreview(message.text)
                    ),
                    iconResId = R.drawable.ic_detail_clock,
                    selected = index == 0,
                    current = false,
                    badgeText = queueStatusLabel(message.status),
                    trailingView = unifiedDialogFooterButton(
                        title = getString(R.string.running_queue_dialog_cancel),
                        primary = false
                    ) {
                        cancelQueuedMessageToComposer(message.queueId)
                        dialog.dismiss()
                    }
                ) {
                    if (message.status == QueuedTextMessageStatus.FAILED) {
                        viewModel.retryQueuedMessage(message.queueId)
                        dialog.dismiss()
                    }
                }
            )
        }
        panel.addView(
            unifiedDialogFooterButton(
                title = getString(R.string.markdown_preview_close),
                primary = true
            ) {
                dialog.dismiss()
            }
        )
        dialog.setContentView(panel)
        dialog.show()
        styleUnifiedDialogWindow(dialog)
    }

    private fun cancelQueuedMessageToComposer(queueId: String): Boolean {
        val cancelled = viewModel.cancelQueuedMessage(queueId) ?: return false
        binding.messageInput.setText(cancelled.text)
        binding.messageInput.setSelection(binding.messageInput.text?.length ?: 0)
        showComposerKeyboard()
        Toast.makeText(
            this,
            R.string.running_queue_message_cancelled,
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    private fun queueStatusLabel(status: QueuedTextMessageStatus): String {
        return when (status) {
            QueuedTextMessageStatus.PENDING -> getString(R.string.running_queue_status_badge_pending)
            QueuedTextMessageStatus.DISPATCHING -> getString(R.string.running_queue_status_badge_dispatching)
            QueuedTextMessageStatus.FAILED -> getString(R.string.running_queue_status_badge_failed)
            QueuedTextMessageStatus.SENT -> getString(R.string.running_queue_status_badge_sent)
            QueuedTextMessageStatus.CANCELLED -> getString(R.string.running_queue_status_badge_cancelled)
        }
    }

    private fun handleDetailHistoryTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDetailTouchY = event.rawY
                detailHistoryPullDistancePx = 0f
                logDetailScrollState(
                    "detail.touch.down",
                    "rawX=${event.rawX.toInt()} rawY=${event.rawY.toInt()}"
                )
            }
            MotionEvent.ACTION_MOVE -> {
                val touchStartY = lastDetailTouchY ?: event.rawY
                val pullDelta = event.rawY - touchStartY
                if (pullDelta > 0f && binding.detailScroll.scrollY <= dp(8)) {
                    detailHistoryPullDistancePx += pullDelta
                } else if (pullDelta < 0f) {
                    detailHistoryPullDistancePx = 0f
                }
                lastDetailTouchY = event.rawY
                if (detailHistoryPullDistancePx > dp(DETAIL_PULL_TO_LOAD_THRESHOLD_DP)) {
                    logDetailScrollState(
                        "detail.touch.threshold",
                        "pull=${detailHistoryPullDistancePx.toInt()} delta=${pullDelta.toInt()}"
                    )
                    requestOlderMessagesPage()
                    detailHistoryPullDistancePx = 0f
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (detailHistoryPullDistancePx > 0f) {
                    logDetailScrollState(
                        "detail.touch.end",
                        "action=${event.actionMasked} pull=${detailHistoryPullDistancePx.toInt()}"
                    )
                }
                lastDetailTouchY = null
                detailHistoryPullDistancePx = 0f
            }
        }
    }

    private fun showComposerKeyboard() {
        if (!binding.messageInput.isEnabled) {
            return
        }

        composerKeyboardRequestedAtMs = SystemClock.uptimeMillis()
        binding.messageInput.post {
            binding.messageInput.requestFocus()
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(binding.messageInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun isComposerKeyboardOpen(): Boolean {
        return currentScreen == ConnectedScreen.DETAIL &&
            (isImeVisible || isComposerKeyboardRequestPending())
    }

    private fun hideComposerKeyboard() {
        keepDetailBottomForKeyboard = false
        composerKeyboardRequestedAtMs = 0L
        isImeVisibleFromInsets = false
        isImeVisibleFromLayout = false
        isImeVisible = false
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        val focusToken = currentFocus?.windowToken
        currentFocus?.clearFocus()
        binding.messageInput.clearFocus()
        binding.rootDrawer.isFocusableInTouchMode = true
        binding.rootDrawer.requestFocus()
        listOfNotNull(
            focusToken,
            binding.messageInput.windowToken,
            binding.root.windowToken,
            window.decorView.windowToken
        ).distinct().forEach { token ->
            inputMethodManager?.hideSoftInputFromWindow(token, 0)
        }
        WindowInsetsControllerCompat(window, binding.root)
            .hide(WindowInsetsCompat.Type.ime())
        syncBottomNavForIme()
    }

    private fun isComposerKeyboardRequestPending(): Boolean {
        val requestedAtMs = composerKeyboardRequestedAtMs
        if (requestedAtMs == 0L || !binding.messageInput.hasFocus()) {
            return false
        }

        val isPending =
            SystemClock.uptimeMillis() - requestedAtMs <= COMPOSER_KEYBOARD_OPEN_GRACE_MS
        if (!isPending) {
            composerKeyboardRequestedAtMs = 0L
        }
        return isPending
    }

    private fun syncComposerDraftForThread(threadId: String?) {
        val draftChange = composerDraftStore.switchThread(
            threadId = threadId,
            currentText = binding.messageInput.text?.toString().orEmpty()
        ) ?: return
        composerDraftStore.withRestoringDraft {
            binding.messageInput.setText(draftChange.text)
            binding.messageInput.setSelection(draftChange.selection)
        }
    }

    private fun maybeLoadOlderMessages(scrollY: Int, oldScrollY: Int) {
        if (currentScreen != ConnectedScreen.DETAIL ||
            scrollY > dp(8)
        ) {
            return
        }

        if (oldScrollY >= scrollY) {
            requestOlderMessagesPage()
        }
    }

    private fun requestOlderMessagesPage() {
        val state = viewModel.state.value
        val skipReason = when {
            pendingOlderMessagesScrollAnchor != null -> "pendingAnchor"
            !state.canLoadOlderMessages -> "cannotLoad"
            state.isLoadingOlderMessages -> "loading"
            state.detail?.recentMessages.isNullOrEmpty() -> "emptyMessages"
            else -> null
        }
        if (skipReason != null) {
            logDetailScrollState("older.request.skip", "reason=$skipReason")
            return
        }

        pendingOlderMessagesScrollAnchor = OlderMessagesScrollAnchor(
            contentHeight = binding.detailScrollContent.height,
            scrollY = binding.detailScroll.scrollY
        )
        logDetailScrollState(
            "older.request.start",
            "anchorContent=${binding.detailScrollContent.height} anchorScroll=${binding.detailScroll.scrollY}"
        )
        viewModel.loadOlderMessages()
    }

    private fun maybeLoadOlderMessagesWhenUnderfilled(state: MainUiState) {
        if (currentScreen != ConnectedScreen.DETAIL ||
            !state.canLoadOlderMessages ||
            state.isLoadingOlderMessages ||
            state.detail?.recentMessages.isNullOrEmpty()
        ) {
            return
        }

        binding.detailScroll.post {
            val isUnderfilled = binding.detailScrollContent.height <= binding.detailScroll.height + dp(8)
            val isNearTop = binding.detailScroll.scrollY <= dp(8)
            if (isUnderfilled && isNearTop) {
                logDetailScrollState(
                    "older.underfilled.check",
                    "content=${binding.detailScrollContent.height} viewport=${binding.detailScroll.height}"
                )
                requestOlderMessagesPage()
            }
        }
    }

    private fun syncDetailJumpToBottomButton(scrollY: Int) {
        val maxScrollY = maxOf(
            0,
            binding.detailScrollContent.height - binding.detailScroll.height
        )
        val distanceFromBottom = maxOf(0, maxScrollY - scrollY)
        val shouldShow =
            currentScreen == ConnectedScreen.DETAIL &&
                distanceFromBottom > dp(DETAIL_JUMP_TO_BOTTOM_THRESHOLD_DP)
        val button = binding.detailJumpToBottomButton

        if (shouldShow) {
            if (button.visibility == View.VISIBLE) {
                button.alpha = 1f
                button.scaleX = 1f
                button.scaleY = 1f
                return
            }
            button.animate().cancel()
            button.alpha = 0f
            button.scaleX = 0.88f
            button.scaleY = 0.88f
            button.visibility = View.VISIBLE
            button.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .start()
            return
        }

        if (button.visibility == View.VISIBLE) {
            button.animate().cancel()
            button.animate()
                .alpha(0f)
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .withEndAction {
                    button.visibility = View.GONE
                    button.alpha = 1f
                    button.scaleX = 1f
                    button.scaleY = 1f
                }
                .start()
        }
    }

    private fun isDetailScrolledToBottom(): Boolean {
        if (currentScreen != ConnectedScreen.DETAIL ||
            binding.detailScroll.visibility != View.VISIBLE
        ) {
            return false
        }
        val maxScrollY = maxOf(
            0,
            binding.detailScrollContent.height - binding.detailScroll.height
        )
        val distanceFromBottom = maxOf(0, maxScrollY - binding.detailScroll.scrollY)
        return distanceFromBottom <= dp(DETAIL_BOTTOM_STICKY_THRESHOLD_DP)
    }

    private fun scrollDetailToBottomAfterLayout() {
        binding.detailScroll.post {
            scrollDetailToBottomWithoutFocusChange()
        }
    }

    private fun scrollDetailToBottomWithoutFocusChange() {
        val maxScrollY = maxOf(
            0,
            binding.detailScrollContent.height - binding.detailScroll.height
        )
        binding.detailScroll.scrollTo(0, maxScrollY)
        syncDetailJumpToBottomButton(binding.detailScroll.scrollY)
    }

    private fun syncDetailJumpToBottomButtonAfterLayout() {
        binding.detailScroll.post {
            syncDetailJumpToBottomButton(binding.detailScroll.scrollY)
        }
    }

    private fun requestDetailKeyboardBottomScrollIfNeeded() {
        if (!isDetailScrolledToBottom()) {
            return
        }
        keepDetailBottomForKeyboard = true
        requestDetailScrollToBottom()
        scrollDetailToBottomAfterLayout()
        binding.detailScroll.postDelayed({
            if (keepDetailBottomForKeyboard && currentScreen == ConnectedScreen.DETAIL) {
                scrollDetailToBottomAfterLayout()
            }
        }, KEYBOARD_BOTTOM_SCROLL_DELAY_MS)
        binding.detailScroll.postDelayed({
            if (keepDetailBottomForKeyboard && currentScreen == ConnectedScreen.DETAIL) {
                scrollDetailToBottomAfterLayout()
            }
        }, KEYBOARD_BOTTOM_SCROLL_FINAL_DELAY_MS)
    }

    private fun requestDetailUpdateBottomScrollIfNeeded() {
        if (isDetailScrolledToBottom()) {
            requestDetailScrollToBottom()
        }
    }

    private fun shouldKeepDetailBottomForTyping(): Boolean {
        return keepDetailBottomForKeyboard || shouldScrollDetailToBottom || isDetailScrolledToBottom()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    submitThreadsWithTaskDiagnostics(state)
                    syncStaleCompletionNotifications(state)

                    if (state.isConnected) {
                        showContentState(animateEntrance = !lastIsConnected)
                        syncDetailTransitionMotion(state)
                        bindTaskPage(state)
                        requestDetailUpdateBottomScrollIfNeeded()
                        bindDetailPage(state)
                        bindProfilePage(state)
                        maybeScrollDetailTimelineToBottom(state)
                        applyConnectedScreen()
                    } else {
                        showLoginState()
                        bindLoginState(state)
                    }

                    syncConnectionMotion(state)
                    syncTaskRefreshMotion(state)
                    syncOlderMessagesLoading(state)
                    syncComposerMotion(state)
                    syncMotionOverlay(state)
                    syncErrorToast(state)
                    lastIsConnected = state.isConnected
                }
            }
        }
    }

    private fun observeRealtimeNotifications() {
        lifecycleScope.launch {
            viewModel.state
                .map { it.pendingRealtimeNotifications }
                .distinctUntilChanged()
                .collect { notifications ->
                    showPendingRealtimeNotifications(notifications)
                }
        }
    }

    private fun syncErrorToast(state: MainUiState) {
        val errorMessage = state.errorMessage
        if (errorMessage == null) {
            lastShownErrorMessage = null
            return
        }
        if (!state.isConnected || errorMessage == lastShownErrorMessage) {
            return
        }

        Toast.makeText(this, displayErrorMessage(errorMessage), Toast.LENGTH_SHORT).show()
        lastShownErrorMessage = errorMessage
    }

    private fun submitThreadsWithTaskDiagnostics(state: MainUiState) {
        val signature = taskListDiagnosticSignature(state)
        val changed = signature != lastTaskListDiagnosticSignature
        if (changed) {
            logTaskListState(
                "threads.submit",
                "count=${state.threads.size} selected=${state.selectedThreadId.logId()} " +
                    "screen=$currentScreen connected=${state.isConnected} " +
                    "refreshing=${state.isRefreshingThreads} sample=${taskThreadSample(state)}"
            )
        }
        lastTaskListDiagnosticSignature = signature
        threadAdapter.submitThreads(state.threads, readThreadStatusIndicatorKeys)
        if (changed) {
            logTaskListSnapshotAfterLayout("threads.afterLayout", state)
        }
    }

    private fun taskListDiagnosticSignature(state: MainUiState): TaskListDiagnosticSignature {
        return TaskListDiagnosticSignature(
            screen = currentScreen,
            isConnected = state.isConnected,
            isRefreshingThreads = state.isRefreshingThreads,
            selectedThreadId = state.selectedThreadId,
            threadCount = state.threads.size,
            firstThreadIds = state.threads.take(TASK_LIST_DIAGNOSTIC_SAMPLE_SIZE)
                .joinToString(separator = "|") { it.threadId },
            firstThreadStatuses = state.threads.take(TASK_LIST_DIAGNOSTIC_SAMPLE_SIZE)
                .joinToString(separator = "|") { it.status.name },
            firstThreadUpdatedAts = state.threads.take(TASK_LIST_DIAGNOSTIC_SAMPLE_SIZE)
                .joinToString(separator = "|") { it.updatedAt }
        )
    }

    private fun logTaskListSnapshotAfterLayout(reason: String, state: MainUiState) {
        binding.threadDrawerRecycler.post {
            logTaskListState(
                reason,
                "screen=$currentScreen count=${state.threads.size} selected=${state.selectedThreadId.logId()} " +
                    "adapterItems=${threadAdapter.itemCount} first=${taskListFirstVisiblePosition()} " +
                    "last=${taskListLastVisiblePosition()} taskScrollY=${binding.taskPage.scrollY} " +
                    "taskHeight=${binding.taskPage.height} contentHeight=${binding.taskPageContent.height} " +
                    "recyclerHeight=${binding.threadDrawerRecycler.height} " +
                    "taskBounds=${binding.taskPage.diagnosticBounds()} " +
                    "recyclerBounds=${binding.threadDrawerRecycler.diagnosticBounds()} " +
                    "bottomNav=${binding.bottomNavCard.diagnosticBounds()} " +
                    "bottomNavVisible=${binding.bottomNavCard.visibilityName()} sample=${taskThreadSample(state)}"
            )
        }
    }

    private fun logTaskListState(event: String, details: String) {
        DebugTraceLogger.log("task-list",
            "$event ${details.logCompact(TASK_LIST_DIAGNOSTIC_MESSAGE_MAX_CHARS)}"
        )
    }

    private fun logDetailScrollState(event: String, details: String) {
        DebugTraceLogger.log("detail-scroll",
            "$event $details ${detailScrollDiagnosticState()}"
                .logCompact(DETAIL_SCROLL_DIAGNOSTIC_MESSAGE_MAX_CHARS)
        )
    }

    private fun detailScrollDiagnosticState(): String {
        val state = viewModel.state.value
        val maxScrollY = maxOf(
            0,
            binding.detailScrollContent.height - binding.detailScroll.height
        )
        val anchor = pendingOlderMessagesScrollAnchor
        val anchorText = if (anchor == null) {
            "none"
        } else {
            "${anchor.contentHeight}:${anchor.scrollY}"
        }
        return "thread=${(state.detail?.thread?.threadId ?: state.selectedThreadId).logId()} " +
            "screen=$currentScreen scroll=${binding.detailScroll.scrollY}/$maxScrollY " +
            "viewport=${binding.detailScroll.height} content=${binding.detailScrollContent.height} " +
            "messages=${state.detail?.recentMessages?.size ?: 0} canLoad=${state.canLoadOlderMessages} " +
            "loading=${state.isLoadingOlderMessages} anchor=$anchorText " +
            "jump=${binding.detailJumpToBottomButton.visibilityName()} " +
            "bottom=${isDetailScrolledToBottom()} keepKeyboard=$keepDetailBottomForKeyboard " +
            "keepTyping=$keepDetailBottomForTyping ime=$isImeVisible pull=${detailHistoryPullDistancePx.toInt()}"
    }

    private fun shouldLogDetailScroll(delta: Int): Boolean {
        if (abs(delta) < DETAIL_SCROLL_DIAGNOSTIC_MIN_DELTA_PX) {
            return false
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastDetailScrollLogAtMs < DETAIL_SCROLL_DIAGNOSTIC_THROTTLE_MS) {
            return false
        }
        lastDetailScrollLogAtMs = now
        return true
    }

    private fun shouldLogTaskListScroll(delta: Int): Boolean {
        if (abs(delta) < TASK_LIST_DIAGNOSTIC_MIN_SCROLL_DELTA_PX) {
            return false
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastTaskListScrollLogAtMs < TASK_LIST_DIAGNOSTIC_SCROLL_THROTTLE_MS) {
            return false
        }
        lastTaskListScrollLogAtMs = now
        return true
    }

    private fun taskThreadSample(state: MainUiState): String {
        return state.threads.take(TASK_LIST_DIAGNOSTIC_SAMPLE_SIZE)
            .joinToString(separator = " || ") { thread ->
                "${thread.threadId.logId()}:${thread.title.logCompact(28)}:" +
                    "${thread.status}:${thread.updatedAt.logCompact(24)}"
            }
    }

    private fun taskListFirstVisiblePosition(): Int {
        return (binding.threadDrawerRecycler.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()
            ?: RecyclerView.NO_POSITION
    }

    private fun taskListLastVisiblePosition(): Int {
        return (binding.threadDrawerRecycler.layoutManager as? LinearLayoutManager)
            ?.findLastVisibleItemPosition()
            ?: RecyclerView.NO_POSITION
    }

    private fun bindLoginState(state: MainUiState) {
        val loginMessage = state.errorMessage?.let(::displayErrorMessage)
            ?: getString(R.string.connect_hint)
        val loginMessageColor = if (state.errorMessage == null) {
            R.color.login_muted_text
        } else {
            R.color.login_error_text
        }
        binding.helperText.text = loginMessage
        binding.helperText.setTextColor(
            ContextCompat.getColor(this, loginMessageColor)
        )
    }

    private fun bindTaskPage(state: MainUiState) {
        binding.taskMarkAllReadButton.isEnabled = state.isConnected && state.threads.isNotEmpty()
        binding.taskMarkAllReadButton.alpha = if (binding.taskMarkAllReadButton.isEnabled) 1f else 0.55f
        binding.taskRefreshButton.isEnabled = state.isConnected && !state.isRefreshingThreads
        binding.taskRefreshButton.alpha = when {
            state.isRefreshingThreads -> 0.55f
            state.isConnected -> 1f
            else -> 0.55f
        }
        syncSocketWarningBanners(state)
    }

    private fun syncDetailStatusIndicator(thread: ThreadListItem?) {
        val indicatorState = thread?.let {
            ThreadStatusIndicatorSupport.visibleStateFor(
                thread = it,
                readSettledKey = readThreadStatusIndicatorKeys[it.threadId]
            )
        } ?: ThreadStatusIndicatorSupport.IndicatorState.HIDDEN
        binding.detailTopSubtitle.setTextColor(ContextCompat.getColor(this, R.color.detail_nav_subtitle))

        if (lastDetailStatusIndicatorState == indicatorState) {
            return
        }
        lastDetailStatusIndicatorState = indicatorState
        stopDetailSocketPulse()

        if (indicatorState == ThreadStatusIndicatorSupport.IndicatorState.HIDDEN) {
            binding.detailSocketStatusDot.visibility = View.INVISIBLE
            binding.detailSocketStatusDot.contentDescription = getString(R.string.thread_status_hidden)
            return
        }

        binding.detailSocketStatusDot.visibility = View.VISIBLE
        binding.detailSocketStatusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, detailStatusIndicatorColor(indicatorState))
        )
        binding.detailSocketStatusDot.contentDescription = getString(
            detailStatusIndicatorDescription(indicatorState)
        )

        when (indicatorState) {
            ThreadStatusIndicatorSupport.IndicatorState.RUNNING,
            ThreadStatusIndicatorSupport.IndicatorState.WAITING_INPUT,
            ThreadStatusIndicatorSupport.IndicatorState.ERROR -> startDetailSocketPulse()
            ThreadStatusIndicatorSupport.IndicatorState.SETTLED,
            ThreadStatusIndicatorSupport.IndicatorState.HIDDEN -> Unit
        }
    }

    private fun detailStatusIndicatorColor(
        state: ThreadStatusIndicatorSupport.IndicatorState
    ): Int = when (state) {
        ThreadStatusIndicatorSupport.IndicatorState.RUNNING -> R.color.thread_status_running
        ThreadStatusIndicatorSupport.IndicatorState.WAITING_INPUT -> R.color.thread_status_waiting
        ThreadStatusIndicatorSupport.IndicatorState.ERROR -> R.color.thread_status_error
        ThreadStatusIndicatorSupport.IndicatorState.SETTLED -> R.color.thread_status_settled
        ThreadStatusIndicatorSupport.IndicatorState.HIDDEN -> R.color.detail_model_dot
    }

    private fun detailStatusIndicatorDescription(
        state: ThreadStatusIndicatorSupport.IndicatorState
    ): Int = when (state) {
        ThreadStatusIndicatorSupport.IndicatorState.RUNNING -> R.string.thread_status_running
        ThreadStatusIndicatorSupport.IndicatorState.WAITING_INPUT -> R.string.thread_status_waiting
        ThreadStatusIndicatorSupport.IndicatorState.ERROR -> R.string.thread_status_error
        ThreadStatusIndicatorSupport.IndicatorState.SETTLED -> R.string.thread_status_settled
        ThreadStatusIndicatorSupport.IndicatorState.HIDDEN -> R.string.thread_status_hidden
    }

    private fun syncSocketWarningBanners(state: MainUiState) {
        val warningTextRes = when (state.connectionState) {
            StreamConnectionState.OPEN -> if (state.syncWarningMessage != null) {
                R.string.socket_warning_sync_blocked
            } else {
                null
            }
            StreamConnectionState.CONNECTING,
            StreamConnectionState.RECONNECTING -> R.string.socket_warning_reconnecting
            StreamConnectionState.CLOSED,
            StreamConnectionState.FAILED -> R.string.socket_warning_disconnected
        }
        if (warningTextRes == null) {
            binding.taskSocketWarning.visibility = View.GONE
            binding.detailSocketWarning.visibility = View.GONE
        } else {
            binding.taskSocketWarning.setText(warningTextRes)
            binding.taskSocketWarning.visibility = View.VISIBLE
            binding.detailSocketWarning.setText(warningTextRes)
            binding.detailSocketWarning.visibility = View.VISIBLE
        }
    }

    private fun startDetailSocketPulse() {
        stopDetailSocketPulse()
        val alpha = ObjectAnimator.ofFloat(binding.detailSocketStatusDot, View.ALPHA, 0.42f, 1f)
        val scaleX = ObjectAnimator.ofFloat(binding.detailSocketStatusDot, View.SCALE_X, 0.82f, 1.18f)
        val scaleY = ObjectAnimator.ofFloat(binding.detailSocketStatusDot, View.SCALE_Y, 0.82f, 1.18f)
        listOf(alpha, scaleX, scaleY).forEach { animator ->
            animator.duration = DETAIL_SOCKET_PULSE_DURATION_MS
            animator.repeatCount = ValueAnimator.INFINITE
            animator.repeatMode = ValueAnimator.REVERSE
            animator.interpolator = motionInterpolator
        }
        detailSocketPulseAnimator = AnimatorSet().apply {
            playTogether(alpha, scaleX, scaleY)
            start()
        }
    }

    private fun stopDetailSocketPulse() {
        detailSocketPulseAnimator?.cancel()
        detailSocketPulseAnimator = null
        binding.detailSocketStatusDot.animate().cancel()
        binding.detailSocketStatusDot.alpha = 1f
        binding.detailSocketStatusDot.scaleX = 1f
        binding.detailSocketStatusDot.scaleY = 1f
    }

    private fun bindDetailPage(state: MainUiState) {
        val detail = state.detail
        val isPendingDetail =
            pendingDetailThreadId != null && detail?.thread?.threadId != pendingDetailThreadId
        val detailThread = if (isPendingDetail) {
            state.threads.firstOrNull { it.threadId == pendingDetailThreadId } ?: detail?.thread
        } else {
            detail?.thread
        }
        binding.detailTopTitle.text = detailThread?.title
            ?: pendingDetailTitle
            ?: getString(R.string.thread_empty_state)
        val detailThreadId = detailThread?.threadId
        val isDetailSyncPending = !isPendingDetail &&
            detailThreadId != null &&
            detailThreadId in state.pendingDetailSyncThreadIds
        val detailSubtitle = when {
            isPendingDetail -> getString(R.string.detail_loading_state)
            isDetailSyncPending -> getString(R.string.detail_sync_pending_state)
            else -> DetailRunningStatusSupport.subtitle(detailThread, detail)
        }
        binding.detailTopSubtitle.text = detailSubtitle
        syncSocketWarningBanners(state)
        syncDetailStatusIndicator(detailThread)
        syncDetailSubtitlePopup(detailSubtitle)
        syncDetailRuntimeSubtitleRefresh(
            detailThread = detailThread,
            detail = if (!isPendingDetail && !isDetailSyncPending) detail else null
        )

        val visualDetail = if (isPendingDetail) null else detail
        val visualContent = DetailVisualSupport.build(this, visualDetail, detailThread)
        val composerThreadId = detailThread?.threadId
        val queuedTextMessages = queuedMessagesForThread(state.queuedTextMessages, composerThreadId)
        syncComposerDraftForThread(composerThreadId)
        syncDetailAlertUi(composerThreadId, state.isConnected && !isPendingDetail)
        maybeShowDetailNewMessageAlert(visualDetail)
        bindComposerRuntimeState(visualDetail)
        renderMarkdown(binding.detailBodyText, visualContent.bodyMarkdown)
        bindDetailConversation(visualDetail)
        bindDetailErrorCard(visualContent.errorContent)
        binding.detailTodoTitle.text = visualContent.todoTitle
        binding.detailTodoProgressChip.text = visualContent.todoProgress.ifBlank {
            DetailVisualSupport.fallbackPercent(visualContent.todoItems)
        }
        binding.detailTodoTodoLabel.text = visualContent.todoMarkerText
        binding.detailTodoTodoLabel.visibility =
            if (visualContent.showTodoMarker) View.VISIBLE else View.GONE
        bindDetailChecklist(visualContent.todoItems)
        bindDetailFileChanges(visualContent.fileItems)
        syncFileChangesCard(visualContent, detailThread?.threadId)
        if (currentScreen == ConnectedScreen.DETAIL && !isPendingDetail) {
            detailThread?.threadId?.let { lastRenderedDetailThreadId = it }
        }
        binding.detailFileChangesCard.setOnClickListener {
            val activeThreadId = detailThread?.threadId ?: return@setOnClickListener
            if (!visualContent.showFileChanges) {
                return@setOnClickListener
            }
            expandedFileChangesThreadId = if (expandedFileChangesThreadId == activeThreadId) {
                null
            } else {
                activeThreadId
            }
            syncFileChangesCard(visualContent, activeThreadId)
        }

        val pendingImages = state.pendingImageDrafts
        val pendingFiles = state.pendingFileDrafts
        bindPendingAttachments(pendingImages, pendingFiles)
        binding.pickImageButton.isEnabled = state.isConnected && !isPendingDetail

        binding.sendButton.isEnabled = state.isConnected && !isPendingDetail
        binding.messageInput.isEnabled = state.isConnected && !isPendingDetail
        binding.detailRefreshButton.isEnabled = state.isConnected && !isPendingDetail
        binding.sendButton.alpha = if (binding.sendButton.isEnabled) 1f else 0.42f
        binding.pickImageButton.alpha = if (binding.pickImageButton.isEnabled) 1f else 0.42f
        binding.detailRefreshButton.alpha = if (binding.detailRefreshButton.isEnabled) 1f else 0.42f
        binding.messageInput.alpha = 1f
        val composerStatus = ComposerStatusSupport.presentation(
            queuedTextMessages = queuedTextMessages,
            isPendingDetail = isPendingDetail,
            sendDisabledReason = detail?.sendDisabledReason,
            errorMessage = state.errorMessage
        )
        binding.composerStatus.text = composerStatus.content.toDisplayText()
        binding.composerStatus.setTextColor(
            ContextCompat.getColor(this, composerStatus.colorResId)
        )
        binding.composerStatus.isClickable = composerStatus.isActionable
        binding.composerStatus.isFocusable = composerStatus.isActionable
        maybeLoadOlderMessagesWhenUnderfilled(state)
        binding.detailScroll.post {
            syncDetailJumpToBottomButton(binding.detailScroll.scrollY)
        }
    }

    private fun ComposerStatusContent.toDisplayText(): String {
        return when (this) {
            is ComposerStatusContent.Queued -> when (status) {
                QueuedTextMessageStatus.DISPATCHING -> getString(R.string.running_queue_status_dispatching)
                QueuedTextMessageStatus.FAILED -> getString(R.string.running_queue_status_failed)
                else -> if (count > 1) {
                    getString(R.string.running_queue_status_pending_many, count)
                } else {
                    getString(R.string.running_queue_status_pending_one)
                }
            }
            ComposerStatusContent.Loading -> getString(R.string.detail_loading_state)
            is ComposerStatusContent.Disabled -> reason
            is ComposerStatusContent.Error -> displayErrorMessage(message)
            ComposerStatusContent.Ready -> getString(R.string.composer_ready)
        }
    }

    private fun queuedMessagesForThread(
        queuedMessages: List<QueuedTextMessage>,
        threadId: String?
    ): List<QueuedTextMessage> {
        if (threadId == null) {
            return emptyList()
        }
        return queuedMessages.filter { message ->
            message.threadId == threadId &&
                (
                    message.status == QueuedTextMessageStatus.PENDING ||
                        message.status == QueuedTextMessageStatus.DISPATCHING ||
                        message.status == QueuedTextMessageStatus.FAILED
                    )
        }
    }

    private fun showAttachmentPicker() {
        showUnifiedActionDialog(
            title = getString(R.string.attachment_picker_title),
            actions = listOf(
                UnifiedDialogAction(
                    title = getString(R.string.attachment_pick_image),
                    subtitle = getString(R.string.attachment_pick_image_subtitle),
                    iconResId = R.drawable.ic_app_plus
                ) { dialog ->
                    dialog.dismiss()
                    pickImageLauncher.launch("image/*")
                },
                UnifiedDialogAction(
                    title = getString(R.string.attachment_pick_file),
                    subtitle = getString(R.string.attachment_pick_file_subtitle),
                    iconResId = R.drawable.ic_app_file
                ) { dialog ->
                    dialog.dismiss()
                    pickFileLauncher.launch("*/*")
                }
            )
        )
    }

    private fun showUnifiedActionDialog(
        title: String,
        actions: List<UnifiedDialogAction>
    ) {
        UnifiedDialogSupport.showActionDialog(this, title, actions, ::dp)
    }

    private fun unifiedDialogPanel(title: String): LinearLayout {
        return UnifiedDialogSupport.panel(this, title, ::dp)
    }

    private fun unifiedDialogRow(
        title: String,
        subtitle: String,
        iconResId: Int,
        selected: Boolean,
        current: Boolean = selected,
        badgeText: String? = null,
        trailingView: View? = null,
        onClick: () -> Unit
    ): View {
        return UnifiedDialogSupport.row(
            context = this,
            title = title,
            subtitle = subtitle,
            iconResId = iconResId,
            selected = selected,
            current = current,
            badgeText = badgeText,
            trailingView = trailingView,
            dp = ::dp,
            onClick = onClick
        )
    }

    private fun unifiedDialogPillSwitch(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ): View {
        return UnifiedDialogSupport.pillSwitch(this, checked, ::dp, onCheckedChange)
    }

    private fun unifiedDialogFooterButton(
        title: String,
        primary: Boolean,
        onClick: () -> Unit
    ): TextView {
        return UnifiedDialogSupport.footerButton(this, title, primary, ::dp, onClick)
    }

    private fun styleUnifiedDialogWindow(dialog: Dialog) {
        UnifiedDialogSupport.styleWindow(this, dialog, ::dp)
    }

    private fun bindPendingImages(pendingImages: List<PendingImageDraft>) {
        bindPendingAttachments(pendingImages, emptyList())
    }

    private fun bindPendingAttachments(
        pendingImages: List<PendingImageDraft>,
        pendingFiles: List<PendingFileDraft>
    ) {
        val attachments = ComposerAttachmentSupport.presentation(pendingImages, pendingFiles)
        binding.pendingImageCard.visibility =
            if (attachments.hasAttachments) View.VISIBLE else View.GONE
        binding.pendingImageList.removeAllViews()
        pendingImages.forEach { draft ->
            binding.pendingImageList.addView(
                ComposerAttachmentRowSupport.imagePreviewRow(
                    context = this,
                    draft = draft,
                    dp = ::dp,
                    roundedBackground = ::roundedConversationBackground,
                    onPreview = ::showPendingImagePreviewDialog,
                    onRemove = viewModel::removePendingImage
                )
            )
        }
        pendingFiles.forEach { draft ->
            binding.pendingImageList.addView(
                ComposerAttachmentRowSupport.filePreviewRow(
                    context = this,
                    draft = draft,
                    dp = ::dp,
                    roundedBackground = ::roundedConversationBackground,
                    onRemove = viewModel::removePendingFile
                )
            )
        }
    }

    private fun showPendingImagePreviewDialog(draft: PendingImageDraft) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val image = ImageView(this).apply {
            adjustViewBounds = true
            contentDescription = draft.fileName
            scaleType = ImageView.ScaleType.FIT_CENTER
            load(Uri.parse(draft.previewUri))
            setOnClickListener {
                dialog.dismiss()
            }
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(14), dp(28), dp(14), dp(28))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.detail_nested_card_surface))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), ContextCompat.getColor(context, R.color.detail_card_stroke))
            }
            addView(
                image,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
        }
        dialog.setContentView(container)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.76f).toInt()
        )
    }

    private fun showMessageImagePreviewDialog(message: ThreadMessage) {
        val imageUrl = listOf(message.imageUrl, message.thumbnailUrl)
            .firstOrNull { !it.isNullOrBlank() }
            ?: return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val image = ImageView(this).apply {
            adjustViewBounds = true
            contentDescription = message.fileName ?: "图片"
            scaleType = ImageView.ScaleType.FIT_CENTER
            load(
                data = imageUrl,
                imageLoader = com.codex.mobilecontrol.ui.GatewayImageLoader.get(this@MainActivity)
            )
            setOnClickListener {
                dialog.dismiss()
            }
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(14), dp(28), dp(14), dp(28))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.detail_nested_card_surface))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), ContextCompat.getColor(context, R.color.detail_card_stroke))
            }
            addView(
                image,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
        }
        dialog.setContentView(container)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.76f).toInt()
        )
    }

    private fun syncDetailSubtitlePopup(subtitle: String) {
        val subtitleView = binding.detailTopSubtitle
        subtitleView.setOnClickListener(null)
        subtitleView.isClickable = false
        subtitleView.isFocusable = false
        subtitleView.contentDescription = subtitle
        subtitleView.post {
            if (subtitleView.text.toString() != subtitle) {
                return@post
            }
            val layout = subtitleView.layout ?: return@post
            val isTruncated = subtitleView.lineCount > 0 && layout.getEllipsisCount(0) > 0
            if (!isTruncated || subtitle.isBlank()) {
                return@post
            }
            subtitleView.isClickable = true
            subtitleView.isFocusable = true
            subtitleView.setOnClickListener {
                showDetailStatusDialog(subtitle)
            }
        }
    }

    private fun syncDetailRuntimeSubtitleRefresh(
        detailThread: ThreadListItem?,
        detail: ThreadDetail?
    ) {
        val shouldRefresh = currentScreen == ConnectedScreen.DETAIL &&
            DetailRunningStatusSupport.shouldRefreshSubtitle(detailThread, detail)
        if (!shouldRefresh) {
            cancelDetailRuntimeSubtitleRefresh()
            return
        }
        if (detailRuntimeSubtitleRefreshJob?.isActive == true) {
            return
        }

        detailRuntimeSubtitleRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(DETAIL_RUNTIME_SUBTITLE_REFRESH_INTERVAL_MS)
                refreshDetailRuntimeSubtitle()
            }
        }
    }

    private fun refreshDetailRuntimeSubtitle() {
        val state = viewModel.state.value
        val detail = state.detail
        val isPendingDetail =
            pendingDetailThreadId != null && detail?.thread?.threadId != pendingDetailThreadId
        if (currentScreen != ConnectedScreen.DETAIL || !state.isConnected || isPendingDetail) {
            cancelDetailRuntimeSubtitleRefresh()
            return
        }

        val detailThread = detail?.thread ?: state.threads.firstOrNull {
            it.threadId == state.selectedThreadId
        }
        if (!DetailRunningStatusSupport.shouldRefreshSubtitle(detailThread, detail)) {
            cancelDetailRuntimeSubtitleRefresh()
            return
        }

        val subtitle = DetailRunningStatusSupport.subtitle(detailThread, detail)
        binding.detailTopSubtitle.text = subtitle
        syncDetailSubtitlePopup(subtitle)
    }

    private fun cancelDetailRuntimeSubtitleRefresh() {
        detailRuntimeSubtitleRefreshJob?.cancel()
        detailRuntimeSubtitleRefreshJob = null
    }

    private fun showDetailStatusDialog(subtitle: String) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.detail_status_dialog_title)
            .setMessage(subtitle)
            .setPositiveButton(R.string.profile_dialog_confirm, null)
            .show()
        styleDetailStatusDialog(dialog)
    }

    private fun styleDetailStatusDialog(dialog: AlertDialog) {
        val surfaceColor = ContextCompat.getColor(this, R.color.detail_nested_card_surface)
        val strokeColor = ContextCompat.getColor(this, R.color.detail_card_stroke)
        val titleColor = ContextCompat.getColor(this, R.color.detail_primary_text)
        val messageColor = ContextCompat.getColor(this, R.color.detail_body_text)
        val buttonColor = ContextCompat.getColor(this, R.color.detail_link_text)

        dialog.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(surfaceColor)
                setStroke(dp(1), strokeColor)
            }
        )
        dialog.findViewById<TextView>(com.google.android.material.R.id.alertTitle)
            ?.setTextColor(titleColor)
        dialog.findViewById<TextView>(android.R.id.message)
            ?.setTextColor(messageColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(buttonColor)
    }

    private fun currentDetailThreadId(): String? {
        if (currentScreen != ConnectedScreen.DETAIL) {
            return null
        }

        val state = viewModel.state.value
        return pendingDetailThreadId
            ?: state.detail?.thread?.threadId
            ?: state.selectedThreadId
    }

    private fun showDetailAlertSettingsDialog() {
        val threadId = currentDetailThreadId() ?: return
        DetailAlertSettingsDialogSupport.show(
            context = this,
            initialSelection = DetailAlertSettingsSelection(
                messageEnabled = threadId in alertMessageEnabledThreadIds,
                completionEnabled = threadId in alertCompletionEnabledThreadIds
            ),
            dp = ::dp
        ) { selection ->
            applyDetailAlertSettings(
                threadId = threadId,
                messageEnabled = selection.messageEnabled,
                completionEnabled = selection.completionEnabled
            )
        }
    }

    private fun showGlobalThreadNotificationSettingsDialog() {
        ThreadNotificationSettingsDialogSupport.show(
            context = this,
            initialSelection = ThreadNotificationSettingsSelection(
                messageEnabled = alertMessageEnabledThreadIds.isNotEmpty(),
                completionEnabled = alertCompletionEnabledThreadIds.isNotEmpty(),
                messageEnabledThreadIds = alertMessageEnabledThreadIds.toSet(),
                completionEnabledThreadIds = alertCompletionEnabledThreadIds.toSet()
            ),
            messageThreadCount = alertMessageEnabledThreadIds.size,
            completionThreadCount = alertCompletionEnabledThreadIds.size,
            threadItems = notificationSettingsThreads(),
            dp = ::dp,
            onOpenSystemSettings = ::openSystemNotificationSettings,
            onThreadSelectionChanged = ::applyThreadNotificationSelection
        ) { selection ->
            applyGlobalThreadNotificationSettings(
                messageEnabled = selection.messageEnabled,
                completionEnabled = selection.completionEnabled
            )
        }
    }

    private fun showAutomationListDialog() {
        val state = viewModel.state.value
        if (state.isLoadingAutomations) {
            AutomationListDialogSupport.show(
                context = this,
                automations = state.automations,
                isLoading = true,
                dp = ::dp,
                onOpenThread = ::openThreadDetail
            )
            return
        }

        viewModel.refreshAutomations { automations ->
            AutomationListDialogSupport.show(
                context = this,
                automations = automations,
                isLoading = false,
                dp = ::dp,
                onOpenThread = ::openThreadDetail
            )
        }
    }

    private fun notificationSettingsThreads(): List<ThreadNotificationSettingsThread> {
        val enabledThreadIds = (alertMessageEnabledThreadIds + alertCompletionEnabledThreadIds)
            .filter { it.isNotBlank() }
            .toSet()
        if (enabledThreadIds.isEmpty()) {
            return emptyList()
        }

        val state = viewModel.state.value
        val knownThreads = state.threads.associateBy { it.threadId }
        val detailThread = state.detail?.thread
        return enabledThreadIds
            .sortedBy { threadId ->
                knownThreads[threadId]?.title
                    ?: detailThread?.takeIf { it.threadId == threadId }?.title
                    ?: threadId
            }
            .map { threadId ->
                val thread = knownThreads[threadId]
                val title = thread?.title
                    ?: detailThread?.takeIf { it.threadId == threadId }?.title
                    ?: getString(R.string.thread_notifications_unknown_thread, threadId.logId())
                ThreadNotificationSettingsThread(
                    threadId = threadId,
                    title = title,
                    subtitle = thread?.cwd?.takeIf { it.isNotBlank() } ?: threadId.logId(),
                    messageEnabled = threadId in alertMessageEnabledThreadIds,
                    completionEnabled = threadId in alertCompletionEnabledThreadIds
                )
            }
    }

    private fun applyThreadNotificationSelection(selection: ThreadNotificationSettingsSelection) {
        val nextMessageThreadIds = selection.messageEnabledThreadIds
        val nextCompletionThreadIds = selection.completionEnabledThreadIds
        val removedMessageThreadIds = alertMessageEnabledThreadIds - nextMessageThreadIds
        val removedCompletionThreadIds = alertCompletionEnabledThreadIds - nextCompletionThreadIds

        if (removedMessageThreadIds.isNotEmpty()) {
            cancelThreadNotificationsForChannel(
                threadIds = removedMessageThreadIds,
                channelId = DetailNotificationIds.ALERT_CHANNEL_ID
            )
            lastAlertMessageKeyByThreadId.keys.removeAll(removedMessageThreadIds)
            notifiedAlertMessageKeysByThreadId.keys.removeAll(removedMessageThreadIds)
        }
        if (removedCompletionThreadIds.isNotEmpty()) {
            cancelThreadNotificationsForChannel(
                threadIds = removedCompletionThreadIds,
                channelId = DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID
            )
            lastCompletionAlertKeyByThreadId.keys.removeAll(removedCompletionThreadIds)
        }

        alertMessageEnabledThreadIds.clear()
        alertMessageEnabledThreadIds.addAll(nextMessageThreadIds)
        alertCompletionEnabledThreadIds.clear()
        alertCompletionEnabledThreadIds.addAll(nextCompletionThreadIds)
        rebuildAlertEnabledThreadIds()
        GatewayPreferences.saveAlertEnabledThreadIds(this, alertEnabledThreadIds)
        GatewayPreferences.saveAlertMessageEnabledThreadIds(this, alertMessageEnabledThreadIds)
        GatewayPreferences.saveAlertCompletionEnabledThreadIds(this, alertCompletionEnabledThreadIds)
        saveDetailAlertBaselines()
        viewModel.updateRealtimeNotificationPreferences(alertEnabledThreadIds)
        if (alertEnabledThreadIds.isEmpty()) {
            AlertPollingScheduler.cancel(this)
            cancelDetailAlertRefresh()
        } else {
            createDetailNotificationChannel()
            AlertPollingScheduler.schedule(this)
            scheduleDetailAlertRefresh()
        }
        syncDetailAlertUi(currentDetailThreadId(), enabled = viewModel.state.value.isConnected)
        DetailNotificationSupport.refreshSummaryNotification(context = this)
        Toast.makeText(this, R.string.thread_notifications_thread_disabled, Toast.LENGTH_SHORT).show()
    }

    private fun applyGlobalThreadNotificationSettings(
        messageEnabled: Boolean,
        completionEnabled: Boolean
    ) {
        if (!messageEnabled) {
            cancelThreadNotificationsForChannel(
                threadIds = alertMessageEnabledThreadIds.toList(),
                channelId = DetailNotificationIds.ALERT_CHANNEL_ID
            )
            alertMessageEnabledThreadIds.clear()
            lastAlertMessageKeyByThreadId.clear()
            notifiedAlertMessageKeysByThreadId.clear()
        }
        if (!completionEnabled) {
            cancelThreadNotificationsForChannel(
                threadIds = alertCompletionEnabledThreadIds.toList(),
                channelId = DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID
            )
            alertCompletionEnabledThreadIds.clear()
            lastCompletionAlertKeyByThreadId.clear()
        }

        rebuildAlertEnabledThreadIds()
        GatewayPreferences.saveAlertEnabledThreadIds(this, alertEnabledThreadIds)
        GatewayPreferences.saveAlertMessageEnabledThreadIds(this, alertMessageEnabledThreadIds)
        GatewayPreferences.saveAlertCompletionEnabledThreadIds(this, alertCompletionEnabledThreadIds)
        saveDetailAlertBaselines()
        viewModel.updateRealtimeNotificationPreferences(alertEnabledThreadIds)
        if (alertEnabledThreadIds.isEmpty()) {
            AlertPollingScheduler.cancel(this)
            cancelDetailAlertRefresh()
            Toast.makeText(this, R.string.thread_notifications_all_disabled, Toast.LENGTH_SHORT).show()
        } else {
            createDetailNotificationChannel()
            AlertPollingScheduler.schedule(this)
            scheduleDetailAlertRefresh()
            Toast.makeText(this, R.string.thread_notifications_settings_saved, Toast.LENGTH_SHORT).show()
        }
        syncDetailAlertUi(currentDetailThreadId(), enabled = viewModel.state.value.isConnected)
        DetailNotificationSupport.refreshSummaryNotification(
            context = this
        )
    }

    private fun rebuildAlertEnabledThreadIds() {
        alertEnabledThreadIds.clear()
        alertEnabledThreadIds.addAll(alertMessageEnabledThreadIds)
        alertEnabledThreadIds.addAll(alertCompletionEnabledThreadIds)
    }

    private fun cancelThreadNotificationsForChannel(
        threadIds: Collection<String>,
        channelId: String
    ) {
        threadIds.forEach { threadId ->
            DetailNotificationSupport.cancelThreadNotificationIfChannel(
                context = this,
                threadId = threadId,
                channelId = channelId
            )
        }
    }

    private fun applyDetailAlertSettings(
        threadId: String,
        messageEnabled: Boolean,
        completionEnabled: Boolean
    ) {
        if ((messageEnabled || completionEnabled) && !hasDetailNotificationPermission()) {
            pendingAlertPermissionThreadId = threadId
            pendingAlertSelection = DetailAlertSelection(messageEnabled, completionEnabled)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        val currentDetail = viewModel.state.value.detail
            ?.takeIf { it.thread.threadId == threadId }

        if (messageEnabled || completionEnabled) {
            alertEnabledThreadIds.add(threadId)
        } else {
            alertEnabledThreadIds.remove(threadId)
        }

        if (messageEnabled) {
            alertMessageEnabledThreadIds.add(threadId)
            val currentMessage = latestDetailMessage(currentDetail)
            val currentMessageKey =
                if (currentDetail != null && currentMessage != null) {
                    detailAlertMessageKey(currentMessage)
                } else {
                    null
                }
            lastAlertMessageKeyByThreadId[threadId] = currentMessageKey
            notifiedAlertMessageKeysByThreadId[threadId] =
                DetailAlertSupport.rememberMessageAlertKey(
                    notifiedAlertMessageKeysByThreadId[threadId].orEmpty(),
                    currentMessageKey
                )
        } else {
            alertMessageEnabledThreadIds.remove(threadId)
            lastAlertMessageKeyByThreadId.remove(threadId)
            notifiedAlertMessageKeysByThreadId.remove(threadId)
        }

        if (completionEnabled) {
            alertCompletionEnabledThreadIds.add(threadId)
            lastCompletionAlertKeyByThreadId[threadId] = currentDetail?.let(::detailCompletionAlertKey)
        } else {
            alertCompletionEnabledThreadIds.remove(threadId)
            lastCompletionAlertKeyByThreadId.remove(threadId)
        }

        GatewayPreferences.saveAlertEnabledThreadIds(this, alertEnabledThreadIds)
        GatewayPreferences.saveAlertMessageEnabledThreadIds(this, alertMessageEnabledThreadIds)
        GatewayPreferences.saveAlertCompletionEnabledThreadIds(this, alertCompletionEnabledThreadIds)
        saveDetailAlertBaselines()
        viewModel.updateRealtimeNotificationPreferences(alertEnabledThreadIds)
        if (alertEnabledThreadIds.isEmpty()) {
            AlertPollingScheduler.cancel(this)
            cancelDetailAlertRefresh()
            Toast.makeText(this, R.string.detail_alert_disabled, Toast.LENGTH_SHORT).show()
        } else {
            createDetailNotificationChannel()
            AlertPollingScheduler.schedule(this)
            scheduleDetailAlertRefresh()
            Toast.makeText(this, R.string.detail_alert_settings_saved, Toast.LENGTH_SHORT).show()
        }
        syncDetailAlertUi(threadId, enabled = true)
    }

    private fun enableDetailAlertForThread(threadId: String) {
        applyDetailAlertSettings(
            threadId = threadId,
            messageEnabled = true,
            completionEnabled = true
        )
    }

    private fun saveDetailAlertBaselines() {
        val enabledMessageThreadIds = alertMessageEnabledThreadIds.toSet()
        val enabledCompletionThreadIds = alertCompletionEnabledThreadIds.toSet()
        GatewayPreferences.saveAlertMessageKeys(
            this,
            lastAlertMessageKeyByThreadId.filterKeys { it in enabledMessageThreadIds }
        )
        GatewayPreferences.saveAlertNotifiedMessageKeys(
            this,
            notifiedAlertMessageKeysByThreadId.filterKeys { it in enabledMessageThreadIds }
        )
        GatewayPreferences.saveAlertCompletionKeys(
            this,
            lastCompletionAlertKeyByThreadId.filterKeys { it in enabledCompletionThreadIds }
        )
    }

    private fun syncDetailAlertUi(threadId: String?, enabled: Boolean) {
        val isActive = threadId != null && threadId in alertEnabledThreadIds
        binding.detailAlertButton.isEnabled = enabled && threadId != null
        binding.detailAlertButton.isSelected = isActive
        binding.detailAlertButton.alpha = when {
            !binding.detailAlertButton.isEnabled -> 0.42f
            isActive -> 1f
            else -> 0.68f
        }
        binding.detailAlertButton.setColorFilter(
            ContextCompat.getColor(
                this,
                if (isActive) R.color.dashboard_nav_active else R.color.detail_nav_icon
            )
        )

        if (alertEnabledThreadIds.isNotEmpty()) {
            scheduleDetailAlertRefresh()
        } else {
            cancelDetailAlertRefresh()
        }
    }

    private fun scheduleDetailAlertRefresh() {
        if (detailAlertRefreshJob?.isActive == true) {
            return
        }

        detailAlertRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(DETAIL_ALERT_REFRESH_INTERVAL_MS)
                val threadIds = alertEnabledThreadIds.toList()
                if (threadIds.isEmpty()) {
                    cancelDetailAlertRefresh()
                    return@launch
                }
                threadIds.forEach { threadId ->
                    viewModel.refreshThreadForAlert(threadId) { refreshedDetail ->
                        if (refreshedDetail?.thread?.threadId != threadId) {
                            return@refreshThreadForAlert
                        }
                        // 前台详情页只刷新数据；系统通知统一由 AlertsWorker 负责，避免两条轮询路径竞争。
                    }
                }
            }
        }
    }

    private fun cancelDetailAlertRefresh() {
        detailAlertRefreshJob?.cancel()
        detailAlertRefreshJob = null
    }

    private fun maybeShowDetailNewMessageAlert(detail: ThreadDetail?) {
        val threadId = detail?.thread?.threadId ?: return
        if (threadId !in alertMessageEnabledThreadIds) {
            return
        }
        refreshPersistedDetailAlertMessages()
        val latestMessage = latestDetailMessage(detail)
        if (DetailAlertSupport.shouldSuppressNewMessageAlertForCompletion(detail, latestMessage)) {
            return
        }

        val latestMessageKey = latestMessage?.let { detailAlertMessageKey(it) }
        val rememberedKeys = notifiedAlertMessageKeysByThreadId[threadId].orEmpty()
        val hasBaseline = lastAlertMessageKeyByThreadId.containsKey(threadId)
        if (!hasBaseline) {
            lastAlertMessageKeyByThreadId[threadId] = latestMessageKey
            notifiedAlertMessageKeysByThreadId[threadId] =
                DetailAlertSupport.rememberMessageAlertKey(rememberedKeys, latestMessageKey)
            saveDetailAlertBaselines()
            return
        }
        if (!DetailAlertSupport.shouldNotifyMessageAlert(rememberedKeys, latestMessageKey)) {
            lastAlertMessageKeyByThreadId[threadId] = latestMessageKey
            notifiedAlertMessageKeysByThreadId[threadId] =
                DetailAlertSupport.rememberMessageAlertKey(rememberedKeys, latestMessageKey)
            saveDetailAlertBaselines()
            return
        }

        lastAlertMessageKeyByThreadId[threadId] = latestMessageKey
        notifiedAlertMessageKeysByThreadId[threadId] =
            DetailAlertSupport.rememberMessageAlertKey(rememberedKeys, latestMessageKey)
        saveDetailAlertBaselines()
        if (latestMessage == null || latestMessage.role == ThreadMessageRole.USER) {
            return
        }
    }

    private fun refreshPersistedDetailAlertMessages() {
        val persistedMessageKeys = GatewayPreferences.loadAlertMessageKeys(this)
        lastAlertMessageKeyByThreadId.putAll(persistedMessageKeys)
        lastAlertMessageKeyByThreadId.keys.retainAll(alertMessageEnabledThreadIds)

        notifiedAlertMessageKeysByThreadId.clear()
        notifiedAlertMessageKeysByThreadId.putAll(
            GatewayPreferences.loadAlertNotifiedMessageKeys(this)
                .mapValues { (_, keys) -> keys.toMutableSet() }
        )
        notifiedAlertMessageKeysByThreadId.keys.retainAll(alertMessageEnabledThreadIds)
    }

    private fun maybeShowDetailCompletionAlert(detail: ThreadDetail?) {
        val threadId = detail?.thread?.threadId ?: return
        if (threadId !in alertCompletionEnabledThreadIds) {
            return
        }

        val completionKey = detailCompletionAlertKey(detail)
        val hasBaseline = lastCompletionAlertKeyByThreadId.containsKey(threadId)
        if (!hasBaseline) {
            lastCompletionAlertKeyByThreadId[threadId] = completionKey
            saveDetailAlertBaselines()
            return
        }
        if (completionKey == null) {
            if (
                DetailAlertSupport.shouldClearCompletionNotification(
                    previousCompletionKey = lastCompletionAlertKeyByThreadId[threadId],
                    currentCompletionKey = completionKey
                )
            ) {
                DetailNotificationSupport.cancelThreadNotificationIfChannel(
                    context = this,
                    threadId = threadId,
                    channelId = DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID
                )
            }
            lastCompletionAlertKeyByThreadId[threadId] = null
            saveDetailAlertBaselines()
            return
        }
        if (lastCompletionAlertKeyByThreadId[threadId] == completionKey) {
            return
        }

        lastCompletionAlertKeyByThreadId[threadId] = completionKey
        saveDetailAlertBaselines()
    }

    private fun latestDetailMessage(detail: ThreadDetail?): ThreadMessage? {
        return DetailAlertSupport.latestAlertMessage(detail)
    }

    private fun detailAlertMessageKey(message: ThreadMessage): String {
        return DetailAlertSupport.messageAlertKey(message)
    }

    private fun detailCompletionAlertKey(detail: ThreadDetail): String? {
        return DetailAlertSupport.completionAlertKey(detail)
    }

    private fun hasDetailNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createDetailNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            DetailNotificationIds.ALERT_CHANNEL_ID,
            getString(R.string.detail_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.detail_alert_channel_description)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
        val completionChannel = NotificationChannel(
            DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID,
            getString(R.string.detail_completion_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.detail_completion_alert_channel_description)
            enableVibration(true)
        }
        manager.createNotificationChannel(completionChannel)
    }

    private fun showProfileConnectionModeDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val modes = listOf(GatewayConnectionMode.SOCKET, GatewayConnectionMode.SSE)
        var selectedMode = profileConnectionMode
        val panel = unifiedDialogPanel(getString(R.string.profile_connection_mode_title))
        val optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun renderModeRows() {
            optionsContainer.removeAllViews()
            modes.forEach { mode ->
                optionsContainer.addView(
                    unifiedDialogRow(
                        title = getString(
                            when (mode) {
                                GatewayConnectionMode.SOCKET -> R.string.profile_connection_mode_socket
                                GatewayConnectionMode.SSE -> R.string.profile_connection_mode_sse
                            }
                        ),
                        subtitle = getString(
                            when (mode) {
                                GatewayConnectionMode.SOCKET -> {
                                    R.string.profile_connection_mode_socket_subtitle
                                }
                                GatewayConnectionMode.SSE -> {
                                    R.string.profile_connection_mode_sse_subtitle
                                }
                            }
                        ),
                        iconResId = when (mode) {
                            GatewayConnectionMode.SOCKET -> R.drawable.ic_app_refresh
                            GatewayConnectionMode.SSE -> R.drawable.ic_app_folder
                        },
                        selected = mode == selectedMode,
                        current = mode == profileConnectionMode
                    ) {
                        selectedMode = mode
                        renderModeRows()
                    }
                )
            }
        }

        renderModeRows()
        panel.addView(optionsContainer)
        panel.addView(
            LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { params -> params.topMargin = dp(2) }
                gravity = Gravity.END
                addView(
                    unifiedDialogFooterButton(
                        title = getString(R.string.profile_connection_mode_dialog_cancel),
                        primary = false
                    ) {
                        dialog.dismiss()
                    }
                )
                addView(
                    unifiedDialogFooterButton(
                        title = getString(R.string.profile_connection_mode_dialog_confirm),
                        primary = true
                    ) {
                        dialog.dismiss()
                        applyProfileConnectionMode(selectedMode)
                    }
                )
            }
        )

        dialog.setContentView(panel)
        dialog.show()
        styleUnifiedDialogWindow(dialog)
    }

    private fun applyProfileConnectionMode(selectedMode: GatewayConnectionMode) {
        if (selectedMode == profileConnectionMode) {
            return
        }

        profileConnectionMode = selectedMode
        GatewayPreferences.saveConnectionMode(this, profileConnectionMode)
        if (profileConnectionMode == GatewayConnectionMode.SOCKET) {
            AlertPollingScheduler.cancel(this)
        } else if (alertEnabledThreadIds.isNotEmpty()) {
            AlertPollingScheduler.schedule(this)
        }
        bindProfileConnectionMode()
        val state = viewModel.state.value
        val config = state.config ?: GatewayPreferences.loadConfig(this)
        if (config != null) {
            viewModel.connect(config, state.selectedThreadId)
        }
    }

    private fun applyProfileBackgroundConnectionEnabled(enabled: Boolean) {
        if (enabled && (viewModel.state.value.config ?: GatewayPreferences.loadConfig(this)) == null) {
            profileBackgroundConnectionEnabled = false
            GatewayPreferences.saveBackgroundConnectionEnabled(this, false)
            bindProfileBackgroundConnection()
            Toast.makeText(this, R.string.error_missing_token, Toast.LENGTH_SHORT).show()
            return
        }

        profileBackgroundConnectionEnabled = enabled
        GatewayPreferences.saveBackgroundConnectionEnabled(this, enabled)
        RealtimeForegroundService.setEnabled(this, enabled)
        bindProfileBackgroundConnection()
    }

    private fun bindProfileBackgroundConnection() {
        binding.profileBackgroundConnectionSwitch.isChecked = profileBackgroundConnectionEnabled
        binding.profileBackgroundConnectionSubtitle.text = getString(
            if (profileBackgroundConnectionEnabled) {
                R.string.profile_background_connection_on
            } else {
                R.string.profile_background_connection_off
            }
        )
    }

    private fun showProfileTypingSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var selectedEnabled = GatewayPreferences.loadDetailTypingAnimationEnabled(this)
        var selectedCharsPerSecond = GatewayPreferences.loadDetailTypingCharsPerSecond(this)
        var syncingProfileTypingControls = false
        val speedOptions = detailTypingSpeedOptions()
        val panel = unifiedDialogPanel(getString(R.string.profile_typing_settings_title))
        val optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val profileTypingSeekBar = SeekBar(this)
        val profileTypingCustomInput = EditText(this)
        val profileTypingCurrentValue = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.detail_primary_text))
        }
        val profileTypingControlsContainer = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params -> params.topMargin = dp(4) }
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(2))
            addView(profileTypingCurrentValue)
            addView(
                profileTypingSeekBar.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
            )
            addView(
                profileTypingCustomInput.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { params -> params.topMargin = dp(8) }
                    hint = getString(R.string.profile_typing_speed_custom_hint)
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(
                            ContextCompat.getColor(
                                context,
                                R.color.profile_inner_surface
                            )
                        )
                        setStroke(
                            dp(1),
                            ContextCompat.getColor(context, R.color.profile_divider)
                        )
                    }
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setTextColor(
                        ContextCompat.getColor(context, R.color.detail_primary_text)
                    )
                    setHintTextColor(
                        ContextCompat.getColor(context, R.color.profile_muted_text)
                    )
                }
            )
        }

        fun addDialogSectionTitle(title: String) {
            optionsContainer.addView(
                TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { params ->
                        params.topMargin = dp(4)
                        params.bottomMargin = dp(8)
                    }
                    text = title
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(context, R.color.profile_muted_text))
                }
            )
        }

        fun normalizeTypingSpeed(value: Int): Int {
            return value.coerceAtLeast(1)
        }

        fun syncSpeedControls(updateCustomInputText: Boolean = true) {
            val safeValue = normalizeTypingSpeed(selectedCharsPerSecond)
            syncingProfileTypingControls = true
            profileTypingCurrentValue.text = getString(
                R.string.profile_typing_speed_current,
                safeValue
            )
            updateProfileTypingSeekBarRange(profileTypingSeekBar, safeValue)
            if (profileTypingSeekBar.progress != safeValue - 1) {
                profileTypingSeekBar.progress = safeValue - 1
            }
            if (updateCustomInputText) {
                val currentText = profileTypingCustomInput.text?.toString().orEmpty()
                val nextText = safeValue.toString()
                if (currentText != nextText) {
                    profileTypingCustomInput.setText(nextText)
                    profileTypingCustomInput.setSelection(nextText.length)
                }
            }
            syncingProfileTypingControls = false
        }

        fun renderRows() {
            optionsContainer.removeAllViews()
            addDialogSectionTitle(getString(R.string.profile_typing_settings_title))
            optionsContainer.addView(
                unifiedDialogRow(
                    title = getString(R.string.profile_typing_settings_enabled),
                    subtitle = getString(R.string.profile_typing_settings_enabled_subtitle),
                    iconResId = R.drawable.ic_app_refresh,
                    selected = selectedEnabled,
                    current = GatewayPreferences.loadDetailTypingAnimationEnabled(this)
                ) {
                    selectedEnabled = true
                    renderRows()
                }
            )
            optionsContainer.addView(
                unifiedDialogRow(
                    title = getString(R.string.profile_typing_settings_disabled),
                    subtitle = getString(R.string.profile_typing_settings_disabled_subtitle),
                    iconResId = R.drawable.ic_app_trash,
                    selected = !selectedEnabled,
                    current = !GatewayPreferences.loadDetailTypingAnimationEnabled(this)
                ) {
                    selectedEnabled = false
                    renderRows()
                }
            )
            addDialogSectionTitle(getString(R.string.profile_typing_speed_title))
            speedOptions.forEach { option ->
                optionsContainer.addView(
                    unifiedDialogRow(
                        title = getString(option.titleResId),
                        subtitle = profileTypingSpeedRateLabel(option.charsPerSecond),
                        iconResId = R.drawable.ic_app_send,
                        selected = option.charsPerSecond == normalizeTypingSpeed(selectedCharsPerSecond),
                        current = option.charsPerSecond ==
                            GatewayPreferences.loadDetailTypingCharsPerSecond(this)
                    ) {
                        selectedCharsPerSecond = option.charsPerSecond
                        syncSpeedControls()
                        renderRows()
                    }
                )
            }
            optionsContainer.addView(
                unifiedDialogRow(
                    title = getString(R.string.profile_typing_speed_custom),
                    subtitle = profileTypingSpeedRateLabel(normalizeTypingSpeed(selectedCharsPerSecond)),
                    iconResId = R.drawable.ic_app_chat,
                    selected = speedOptions.none {
                        it.charsPerSecond == normalizeTypingSpeed(selectedCharsPerSecond)
                    },
                    current = speedOptions.none {
                        it.charsPerSecond ==
                            GatewayPreferences.loadDetailTypingCharsPerSecond(this)
                    }
                ) {
                    syncSpeedControls()
                    profileTypingCustomInput.requestFocus()
                    profileTypingCustomInput.post {
                        profileTypingCustomInput.setSelection(
                            profileTypingCustomInput.text?.length ?: 0
                        )
                        val imm = getSystemService(InputMethodManager::class.java)
                        imm?.showSoftInput(
                            profileTypingCustomInput,
                            InputMethodManager.SHOW_IMPLICIT
                        )
                    }
                }
            )
            optionsContainer.addView(
                profileTypingControlsContainer.also { controls ->
                    (controls.parent as? ViewGroup)?.removeView(controls)
                }
            )
        }

        profileTypingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) {
                    return
                }
                selectedCharsPerSecond = progress + 1
                syncSpeedControls()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                renderRows()
            }
        })
        profileTypingCustomInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    if (syncingProfileTypingControls) {
                        return
                    }
                    val typedValue = text?.toString()?.toIntOrNull() ?: return
                    val normalized = normalizeTypingSpeed(typedValue)
                    if (selectedCharsPerSecond == normalized) {
                        return
                    }
                    selectedCharsPerSecond = normalized
                    syncSpeedControls(updateCustomInputText = false)
                }

                override fun afterTextChanged(editable: Editable?) = Unit
            }
        )
        syncSpeedControls()
        renderRows()
        panel.addView(optionsContainer)
        panel.addView(
            LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { params -> params.topMargin = dp(2) }
                gravity = Gravity.END
                addView(
                    unifiedDialogFooterButton(
                        title = getString(R.string.profile_typing_settings_dialog_cancel),
                        primary = false
                    ) {
                        dialog.dismiss()
                    }
                )
                addView(
                    unifiedDialogFooterButton(
                        title = getString(R.string.profile_typing_settings_dialog_confirm),
                        primary = true
                    ) {
                        dialog.dismiss()
                        applyProfileTypingSettings(selectedEnabled, selectedCharsPerSecond)
                    }
                )
            }
        )

        dialog.setContentView(panel)
        dialog.show()
        styleUnifiedDialogWindow(dialog)
    }

    private fun applyProfileTypingSettings(enabled: Boolean, charsPerSecond: Int) {
        GatewayPreferences.saveDetailTypingAnimationEnabled(this, enabled)
        GatewayPreferences.saveDetailTypingCharsPerSecond(this, charsPerSecond)
        bindProfileTypingSettings()
    }

    private fun bindProfileTypingSettings() {
        val enabled = GatewayPreferences.loadDetailTypingAnimationEnabled(this)
        val charsPerSecond = GatewayPreferences.loadDetailTypingCharsPerSecond(this)
        binding.profileTypingSettingsSubtitle.text = if (enabled) {
            getString(R.string.profile_typing_settings_summary_enabled, charsPerSecond)
        } else {
            getString(R.string.profile_typing_settings_summary_disabled)
        }
    }

    private fun bindProfileHistoryCollapseSettings() {
        binding.profileHistoryCollapseSubtitle.text = getString(
            R.string.profile_history_collapse_summary,
            MobileUiFormatter.formatWholeMinuteDurationMillis(
                detailTimelineCollapseSettings.minProcessDurationMillis
            ),
            detailTimelineCollapseSettings.minProcessMessageCount
        )
    }

    private fun updateProfileTypingSeekBarRange(seekBar: SeekBar, charsPerSecond: Int) {
        val safeValue = charsPerSecond.coerceAtLeast(1)
        val nextMax = maxOf(PROFILE_TYPING_SLIDER_MIN_MAX_VALUE, safeValue) - 1
        if (seekBar.max != nextMax) {
            seekBar.max = nextMax
        }
    }

    private fun detailTypingSpeedOptions(): List<ProfileTypingSpeedOption> {
        return listOf(
            ProfileTypingSpeedOption(charsPerSecond = 8, titleResId = R.string.profile_typing_speed_normal),
            ProfileTypingSpeedOption(charsPerSecond = 12, titleResId = R.string.profile_typing_speed_fast)
        )
    }

    private fun profileTypingSpeedTitleRes(charsPerSecond: Int): Int {
        return detailTypingSpeedOptions()
            .firstOrNull { it.charsPerSecond == charsPerSecond }
            ?.titleResId
            ?: R.string.profile_typing_speed_normal
    }

    private fun profileTypingSpeedRateLabel(charsPerSecond: Int): String {
        return getString(R.string.profile_typing_speed_rate, charsPerSecond)
    }

    private fun showHistoryCollapseSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val current = detailTimelineCollapseSettings
        val durationRangeMinutes = 30
        val messageRange = 12
        val panel = unifiedDialogPanel(getString(R.string.profile_history_collapse_dialog_title))
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val durationTitle = TextView(this).apply {
            text = getString(R.string.profile_history_collapse_min_duration)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.detail_primary_text))
        }
        val durationValue = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.profile_muted_text))
            textSize = 12f
        }
        val durationSeekBar = SeekBar(this).apply {
            max = durationRangeMinutes
            progress = (current.minProcessDurationMillis / 60_000L).toInt()
                .coerceIn(0, durationRangeMinutes)
        }
        val durationInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((current.minProcessDurationMillis / 60_000L).toString())
            setSelection(text.length)
            setSingleLine(true)
        }
        val messageTitle = TextView(this).apply {
            text = getString(R.string.profile_history_collapse_min_messages)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.detail_primary_text))
        }
        val messageValue = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.profile_muted_text))
            textSize = 12f
        }
        val messageSeekBar = SeekBar(this).apply {
            max = messageRange
            progress = current.minProcessMessageCount.coerceIn(0, messageRange)
        }
        val messageInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.minProcessMessageCount.toString())
            setSelection(text.length)
            setSingleLine(true)
        }

        fun styleNumberInput(input: EditText) {
            input.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params -> params.topMargin = dp(6) }
            input.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(ContextCompat.getColor(this@MainActivity, R.color.profile_inner_surface))
                setStroke(dp(1), ContextCompat.getColor(this@MainActivity, R.color.profile_divider))
            }
            input.setPadding(dp(14), dp(10), dp(14), dp(10))
            input.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.detail_primary_text))
            input.setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.profile_muted_text))
        }

        fun historyControlSection(
            title: TextView,
            value: TextView,
            seekBar: SeekBar,
            input: EditText
        ): LinearLayout {
            return LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { params -> params.bottomMargin = dp(10) }
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(ContextCompat.getColor(context, R.color.profile_inner_surface))
                    setStroke(dp(1), ContextCompat.getColor(context, R.color.profile_divider))
                }
                addView(title)
                addView(
                    value.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { params -> params.topMargin = dp(4) }
                    }
                )
                addView(
                    seekBar.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { params -> params.topMargin = dp(4) }
                    }
                )
                styleNumberInput(input)
                addView(input)
            }
        }

        fun bindDuration(minutes: Int) {
            val clamped = minutes.coerceIn(0, durationRangeMinutes)
            if (durationSeekBar.progress != clamped) {
                durationSeekBar.progress = clamped
            }
            val textValue = clamped.toString()
            if (durationInput.text?.toString() != textValue) {
                durationInput.setText(textValue)
                durationInput.setSelection(durationInput.text.length)
            }
            durationValue.text = getString(
                R.string.profile_history_collapse_duration_value,
                MobileUiFormatter.formatWholeMinuteDurationMillis(clamped * 60_000L)
            )
        }

        fun bindMessages(count: Int) {
            val clamped = count.coerceIn(0, messageRange)
            if (messageSeekBar.progress != clamped) {
                messageSeekBar.progress = clamped
            }
            val textValue = clamped.toString()
            if (messageInput.text?.toString() != textValue) {
                messageInput.setText(textValue)
                messageInput.setSelection(messageInput.text.length)
            }
            messageValue.text = getString(R.string.profile_history_collapse_messages_value, clamped)
        }

        bindDuration((current.minProcessDurationMillis / 60_000L).toInt())
        bindMessages(current.minProcessMessageCount)

        durationSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { progress ->
            bindDuration(progress)
        })
        messageSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { progress ->
            bindMessages(progress)
        })
        durationInput.addTextChangedListener(simpleAfterTextChanged { value ->
            bindDuration(value.toIntOrNull() ?: 0)
        })
        messageInput.addTextChangedListener(simpleAfterTextChanged { value ->
            bindMessages(value.toIntOrNull() ?: 0)
        })

        dialogContent.addView(
            historyControlSection(durationTitle, durationValue, durationSeekBar, durationInput)
        )
        dialogContent.addView(
            historyControlSection(messageTitle, messageValue, messageSeekBar, messageInput)
        )
        panel.addView(dialogContent)
        panel.addView(
            LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { params -> params.topMargin = dp(2) }
                gravity = Gravity.END
                addView(
                    unifiedDialogFooterButton(
                        title = getString(R.string.profile_history_collapse_dialog_cancel),
                        primary = false
                    ) {
                        dialog.dismiss()
                    }
                )
                addView(
                    unifiedDialogFooterButton(
                        title = getString(R.string.profile_history_collapse_dialog_confirm),
                        primary = true
                    ) {
                        dialog.dismiss()
                        detailTimelineCollapseSettings = DetailTimelineCollapseSettings(
                            minProcessMessageCount = messageSeekBar.progress,
                            minProcessDurationMillis = durationSeekBar.progress * 60_000L
                        )
                        GatewayPreferences.saveDetailTimelineCollapseSettings(
                            this@MainActivity,
                            detailTimelineCollapseSettings
                        )
                        bindProfileHistoryCollapseSettings()
                        bindDetailConversation(viewModel.state.value.detail)
                    }
                )
            }
        )

        dialog.setContentView(panel)
        dialog.show()
        styleUnifiedDialogWindow(dialog)
    }

    private fun simpleSeekBarListener(onProgressChanged: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onProgressChanged(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
    }

    private fun simpleAfterTextChanged(onChanged: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                onChanged(s?.toString().orEmpty())
            }
        }
    }

    private fun bindProfileConnectionMode() {
        binding.profileConnectionModeSubtitle.text = getString(
            when (profileConnectionMode) {
                GatewayConnectionMode.SOCKET -> R.string.profile_connection_mode_socket
                GatewayConnectionMode.SSE -> R.string.profile_connection_mode_sse
            }
        )
    }

    private fun reconnectProfileSocket() {
        val state = viewModel.state.value
        val config = state.config ?: GatewayPreferences.loadConfig(this) ?: return
        viewModel.connect(config, state.selectedThreadId)
    }

    private fun bindProfileSocketStatus(state: MainUiState) {
        val statusTextRes = when (state.connectionState) {
            StreamConnectionState.OPEN -> R.string.profile_socket_status_connected
            StreamConnectionState.CONNECTING -> R.string.profile_socket_status_connecting
            StreamConnectionState.RECONNECTING -> R.string.profile_socket_status_reconnecting
            StreamConnectionState.CLOSED -> R.string.profile_socket_status_disconnected
            StreamConnectionState.FAILED -> R.string.profile_socket_status_failed
        }
        val statusColorRes = when (state.connectionState) {
            StreamConnectionState.OPEN -> R.color.detail_socket_connected
            StreamConnectionState.CONNECTING,
            StreamConnectionState.RECONNECTING -> R.color.thread_status_running
            StreamConnectionState.CLOSED,
            StreamConnectionState.FAILED -> R.color.detail_socket_disconnected
        }

        val statusText = getString(statusTextRes)
        val socketLatencyAverageMillis = state.socketLatencyAverageMillis
        binding.profileSocketStatusSubtitle.text = when {
            state.isTestingSocketLatency -> getString(R.string.profile_socket_latency_testing)
            socketLatencyAverageMillis != null &&
                state.connectionState == StreamConnectionState.OPEN -> getString(
                    R.string.profile_socket_latency_result,
                    socketLatencyAverageMillis,
                    state.socketLatencySampleCount
                )
            state.socketLatencyError != null -> getString(R.string.profile_socket_latency_failed)
            else -> statusText
        }
        binding.profileSocketStatusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, statusColorRes)
        )
        binding.profileSocketStatusDot.contentDescription = statusText
        binding.profileSocketReconnectButton.isEnabled =
            state.connectionState != StreamConnectionState.CONNECTING &&
                state.connectionState != StreamConnectionState.RECONNECTING
        binding.profileSocketReconnectButton.alpha =
            if (binding.profileSocketReconnectButton.isEnabled) 1f else 0.45f
        binding.profileSocketLatencyButton.isEnabled =
            state.connectionState == StreamConnectionState.OPEN && !state.isTestingSocketLatency
        binding.profileSocketLatencyButton.alpha =
            if (binding.profileSocketLatencyButton.isEnabled) 1f else 0.45f
    }

    private fun syncStaleCompletionNotifications(state: MainUiState) {
        if (alertCompletionEnabledThreadIds.isEmpty()) {
            return
        }

        var baselineChanged = false
        alertCompletionEnabledThreadIds.toList().forEach { threadId ->
            val detail = state.detail?.takeIf { it.thread.threadId == threadId }
            val status = detail?.thread?.status
                ?: state.threads.firstOrNull { it.threadId == threadId }?.status
            val previousCompletionKey = lastCompletionAlertKeyByThreadId[threadId]
            val currentCompletionKey = when {
                detail != null -> detailCompletionAlertKey(detail)
                status == MobileThreadStatus.RUNNING -> null
                else -> previousCompletionKey
            }
            val shouldClear = DetailAlertSupport.shouldClearCompletionNotification(
                previousCompletionKey = previousCompletionKey,
                currentCompletionKey = currentCompletionKey
            )
            if (shouldClear || status == MobileThreadStatus.RUNNING) {
                DetailNotificationSupport.cancelThreadNotificationIfChannel(
                    context = this,
                    threadId = threadId,
                    channelId = DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID
                )
            }
            if (shouldClear) {
                lastCompletionAlertKeyByThreadId[threadId] = null
                baselineChanged = true
            }
        }
        if (baselineChanged) {
            saveDetailAlertBaselines()
        }
    }

    private fun showPendingRealtimeNotifications(notifications: List<GatewayRealtimeEvent.Notification>) {
        val currentNotificationIds = notifications.map { it.notificationId }.toSet()
        handledRealtimeNotificationIds.retainAll(currentNotificationIds)
        for (notification in notifications) {
            if (notification.notificationId in handledRealtimeNotificationIds) {
                continue
            }
            if (showServerRealtimeNotification(notification)) {
                handledRealtimeNotificationIds.add(notification.notificationId)
                viewModel.markRealtimeNotificationDisplayed(notification.notificationId)
            }
        }
    }

    private fun showServerRealtimeNotification(notification: GatewayRealtimeEvent.Notification): Boolean {
        if (notification.threadId !in alertEnabledThreadIds) {
            return true
        }
        if (!hasDetailNotificationPermission()) {
            return false
        }

        val isCompletionNotification = notification.trigger == "completed" || notification.trigger == "error"
        if (isCompletionNotification && notification.threadId !in alertCompletionEnabledThreadIds) {
            return true
        }
        if (!isCompletionNotification && notification.threadId !in alertMessageEnabledThreadIds) {
            return true
        }
        val channelId = if (isCompletionNotification || notification.trigger == "error") {
            DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID
        } else {
            DetailNotificationIds.ALERT_CHANNEL_ID
        }
        val threadTitle = resolveNotificationThreadTitle(notification.threadId, notification.title)
        val notificationBody = if (notification.trigger == "completed") {
            DetailAlertSupport.completionAlertBodyFromText(
                text = notification.body,
                fallback = getString(R.string.detail_completion_alert_body)
            )
        } else {
            notification.body.ifBlank {
                getString(R.string.detail_new_message_fallback)
            }
        }
        return DetailNotificationSupport.showThreadNotification(
            context = this,
            threadId = notification.threadId,
            channelId = channelId,
            title = threadTitle,
            body = notificationBody,
            category = NotificationCompat.CATEGORY_STATUS,
            defaults = if (channelId == DetailNotificationIds.COMPLETION_ALERT_CHANNEL_ID) {
                NotificationCompat.DEFAULT_ALL
            } else {
                0
            }
        )
    }

    private fun resolveNotificationThreadTitle(threadId: String, fallbackTitle: String): String {
        val state = viewModel.state.value
        val threadTitle = when {
            state.detail?.thread?.threadId == threadId -> state.detail.thread.title
            else -> state.threads.firstOrNull { it.threadId == threadId }?.title.orEmpty()
        }
        return DetailAlertSupport.notificationThreadTitle(
            threadTitle = threadTitle,
            fallback = fallbackTitle.ifBlank { getString(R.string.detail_new_message_title) }
        )
    }

    private fun bindProfilePage(state: MainUiState) {
        bindProfileConnectionMode()
        bindProfileBackgroundConnection()
        bindProfileSocketStatus(state)
        bindProfileTypingSettings()
        bindProfileHistoryCollapseSettings()
        bindProfileAutomations(state)
        binding.profileAboutTitle.text = getString(
            R.string.profile_about_version,
            appVersionName()
        )
        binding.aboutVersion.visibility = View.GONE

        val latestApkInfo = state.latestApkInfo
        val hasUpdate = latestApkInfo?.isNewerThan(BuildConfig.VERSION_CODE) == true
        binding.profileUpdateBadge.visibility = if (hasUpdate) View.VISIBLE else View.GONE
        binding.profileDownloadApkSubtitle.text = when {
            state.isCheckingLatestApk -> getString(R.string.profile_update_checking)
            hasUpdate && !latestApkInfo?.versionName.isNullOrBlank() -> {
                getString(R.string.profile_update_new_version, latestApkInfo?.versionName.orEmpty())
            }
            hasUpdate && latestApkInfo?.versionCode != null -> {
                getString(R.string.profile_update_new_version_code, latestApkInfo.versionCode)
            }
            latestApkInfo?.available == true -> getString(R.string.profile_update_latest)
            latestApkInfo?.available == false -> getString(R.string.download_apk_unavailable)
            else -> getString(R.string.profile_update_check_prompt)
        }
    }

    private fun bindProfileAutomations(state: MainUiState) {
        val activeCount = state.automations.count { it.status == "ACTIVE" }
        binding.profileAutomationsSubtitle.text = when {
            state.isLoadingAutomations -> getString(R.string.profile_automations_subtitle_loading)
            state.automations.isEmpty() -> getString(R.string.profile_automations_subtitle_empty)
            activeCount > 0 -> getString(
                R.string.profile_automations_subtitle_active,
                state.automations.size,
                activeCount
            )
            else -> getString(
                R.string.profile_automations_subtitle_paused,
                state.automations.size
            )
        }
    }

    private fun bindDetailConversation(detail: ThreadDetail?) {
        val visualDetail = detail
        val items = DetailTimelineSupport.conversationItems(
            visualDetail,
            expandedHistoryKeys = expandedConversationHistoryKeysFor(detail?.thread?.threadId),
            collapseSettings = detailTimelineCollapseSettings
        )
        val typingSpec = resolveDetailTypingSpec(detail, items)
        DebugTraceLogger.log(
            "detail-ui",
            "bindDetailConversation thread=${detail?.thread?.threadId ?: "null"} items=${items.size} " +
                "typingSpec=${typingSpec?.messageId ?: "none"} activeTyping=${detailTypingMessageId ?: "none"}"
        )
        if (typingSpec?.messageKey != detailTypingMessageKey) {
            detailTypingAnimator?.cancel()
            detailTypingAnimator = null
            detailTypingMessageId = typingSpec?.messageId
            detailTypingMessageKey = typingSpec?.messageKey
        }
        binding.detailConversationList.removeAllViews()
        binding.detailConversationList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.detailBodyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { item ->
            binding.detailConversationList.addView(conversationItemRow(item, detail, typingSpec))
        }
    }

    private fun bindDetailErrorCard(errorContent: DetailErrorContent?) {
        if (errorContent == null || !errorContent.showCard) {
            binding.detailErrorCard.visibility = View.GONE
            return
        }
        binding.detailErrorCard.visibility = View.VISIBLE
        binding.detailErrorTitle.text = errorContent.title.ifBlank {
            getString(R.string.detail_error_card_title)
        }
        binding.detailErrorSummary.text = errorContent.summary
        val evidence = errorContent.evidence.orEmpty().trim()
        binding.detailErrorEvidence.visibility = if (evidence.isBlank()) View.GONE else View.VISIBLE
        binding.detailErrorEvidence.text = evidence
    }

    private fun resolveDetailTypingSpec(
        detail: ThreadDetail?,
        items: List<DetailConversationItem>
    ): DetailTypingSpec? {
        val threadId = detail?.thread?.threadId ?: return null
        if (!GatewayPreferences.loadDetailTypingAnimationEnabled(this)) {
            return null
        }
        val latestMessage = DetailTypingAnimatorSupport.latestAnimatableMessage(items) ?: return null
        val markdown = conversationMessageMarkdown(latestMessage)
        val typingText = conversationMessageTypingText(markdown)
        val messageKey = DetailTypingAnimatorSupport.stableMessageKey(latestMessage, typingText)
        val startIndex = DetailTypingAnimatorSupport.animationStartIndex(
            previousLatestMessageKey = lastDetailTypingMessageKeyByThreadId[threadId],
            previousLatestText = lastDetailTypingTextByThreadId[threadId],
            currentMessageKey = messageKey,
            currentLatestText = typingText,
            previousVisibleChars = detailTypingVisibleCharsByMessageKey[messageKey] ?: 0,
            isAnimationActive = detailTypingMessageKey == messageKey && detailTypingAnimator != null
        )
        lastDetailTypingMessageKeyByThreadId[threadId] = messageKey
        lastDetailTypingTextByThreadId[threadId] = typingText
        DebugTraceLogger.log(
            "detail-typing",
            "resolve thread=$threadId message=${latestMessage.messageId} len=${typingText.length} " +
                "prevMessage=${lastDetailTypingMessageKeyByThreadId[threadId]} " +
                "visible=${detailTypingVisibleCharsByMessageKey[messageKey] ?: 0} " +
                "startIndex=${startIndex?.toString() ?: "none"} text=${typingText.take(80).replace('\n', ' ')}"
        )
        return startIndex?.let {
            DetailTypingSpec(
                messageId = latestMessage.messageId,
                messageKey = messageKey,
                markdown = markdown,
                typingText = typingText,
                startIndex = it
            )
        }
    }

    private fun expandedConversationHistoryKeysFor(threadId: String?): Set<String> {
        return if (threadId == null) {
            emptySet()
        } else {
            expandedConversationHistoryKeysByThreadId[threadId].orEmpty()
        }
    }

    private fun conversationItemRow(
        item: DetailConversationItem,
        detail: ThreadDetail?,
        typingSpec: DetailTypingSpec?
    ): View {
        return when (item) {
            is DetailConversationItem.HistoryGroup -> conversationHistoryGroupRow(item, detail?.thread?.threadId)
            is DetailConversationItem.ImageGroup -> {
                conversationMessageRow(
                    message = item.messages.first(),
                    imageMessages = item.messages
                )
            }
            is DetailConversationItem.MessageRow -> conversationMessageRow(
                message = item.message,
                imageMessages = emptyList(),
                typingSpec = typingSpec?.takeIf { it.messageId == item.message.messageId }
            )
        }
    }

    private fun conversationHistoryGroupRow(
        item: DetailConversationItem.HistoryGroup,
        threadId: String?
    ): View {
        val toggleHistory = View.OnClickListener {
            if (threadId == null) {
                return@OnClickListener
            }
            val expandedConversationHistoryKeys =
                expandedConversationHistoryKeysByThreadId.getOrPut(threadId) { mutableSetOf() }
            if (item.expanded) {
                expandedConversationHistoryKeys.remove(item.key)
                if (expandedConversationHistoryKeys.isEmpty()) {
                    expandedConversationHistoryKeysByThreadId.remove(threadId)
                }
            } else {
                expandedConversationHistoryKeys.add(item.key)
            }
            bindDetailConversation(viewModel.state.value.detail)
        }
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params -> params.topMargin = dp(8) }
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener(toggleHistory)
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            )
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
            text = getString(
                if (item.expanded) {
                    R.string.detail_history_processed_expanded
                } else {
                    R.string.detail_history_processed_collapsed
                },
                item.processedDurationLabel
            )
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.detail_secondary_text))
            isClickable = true
            isFocusable = true
            setOnClickListener(toggleHistory)
        }
        row.addView(label)
        row.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
                setBackgroundColor(ContextCompat.getColor(context, R.color.detail_row_divider))
            }
        )
        return row
    }

    private fun conversationMessageRow(message: ThreadMessage): View {
        return conversationMessageRow(message, emptyList(), null)
    }

    private fun conversationMessageRow(
        message: ThreadMessage,
        imageMessages: List<ThreadMessage>,
        typingSpec: DetailTypingSpec? = null
    ): View {
        val isUserMessage = message.role == ThreadMessageRole.USER
        val isAssistantMessage = message.role == ThreadMessageRole.ASSISTANT
        val isSystemMessage = message.role == ThreadMessageRole.SYSTEM
        val isFailedOutgoingMessage = isUserMessage &&
            message.sendState == ThreadMessageSendState.FAILED
        val rowGravity = when {
            isUserMessage -> Gravity.END
            isSystemMessage -> Gravity.CENTER_HORIZONTAL
            else -> Gravity.START
        }
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params -> params.topMargin = dp(10) }
            gravity = rowGravity
            orientation = LinearLayout.HORIZONTAL
        }

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (isUserMessage || isAssistantMessage || isSystemMessage) {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = roundedConversationBackground(
                    colorRes = when {
                        isUserMessage -> R.color.message_user_bg
                        isAssistantMessage -> R.color.message_assistant_bg
                        else -> R.color.message_system_bg
                    },
                    strokeColorRes = when {
                        isUserMessage -> R.color.message_user_stroke
                        isAssistantMessage -> R.color.message_assistant_stroke
                        else -> R.color.detail_card_stroke
                    }
                )
            } else {
                setPadding(0, dp(2), 0, dp(4))
            }
        }
        val bubbleParams = LinearLayout.LayoutParams(
            if (isUserMessage || isAssistantMessage || isSystemMessage) {
                LinearLayout.LayoutParams.WRAP_CONTENT
            } else {
                LinearLayout.LayoutParams.MATCH_PARENT
            },
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { params ->
            if (isUserMessage) {
                params.marginStart = if (isFailedOutgoingMessage) 0 else dp(56)
            } else if (isAssistantMessage) {
                params.marginEnd = dp(44)
            } else if (!isSystemMessage) {
                params.marginEnd = dp(18)
            }
        }
        bubble.layoutParams = bubbleParams

        if (isFailedOutgoingMessage) {
            row.addView(failedMessageIndicator(message))
        }

        val conversationTextWidth = LinearLayout.LayoutParams.WRAP_CONTENT
        val text = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                conversationTextWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params ->
                if (isUserMessage) {
                    params.gravity = Gravity.END
                }
            }
            textSize = if (isUserMessage) 14f else 15f
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isSystemMessage) R.color.detail_secondary_text else R.color.detail_body_text
                )
            )
            setLineSpacing(dp(if (isUserMessage) 2 else 5).toFloat(), 1f)
            gravity = if (isSystemMessage) Gravity.CENTER else Gravity.START
            if (isAssistantMessage) {
                minEms = CONVERSATION_MIN_TEXT_EMS
                minWidth = dp(CONVERSATION_MIN_TEXT_WIDTH_DP)
            }
            if (isUserMessage || isAssistantMessage) {
                maxWidth = (
                    resources.displayMetrics.widthPixels *
                        if (isUserMessage) 0.72f else 0.82f
                    ).toInt()
            }
        }
        val markdown = if (imageMessages.isEmpty()) conversationMessageMarkdown(message) else ""
        if (markdown.isNotBlank()) {
            if (typingSpec != null && message.kind == "text") {
                val startIndex = typingSpec.startIndex.coerceIn(0, typingSpec.typingText.length)
                detailTypingVisibleCharsByMessageKey[typingSpec.messageKey] = startIndex
                text.text = typingSpec.typingText.take(startIndex)
                startDetailTypingAnimation(
                    target = text,
                    threadId = message.threadId,
                    messageId = message.messageId,
                    messageKey = typingSpec.messageKey,
                    markdown = typingSpec.markdown,
                    displayedText = typingSpec.typingText,
                    startIndex = startIndex
                )
            } else {
                renderConversationMarkdown(text, markdown, message.threadId)
            }
            if (isUserMessage || isAssistantMessage) {
                applyConversationMeasuredTextWidth(
                    target = text,
                    measurementText = typingSpec?.typingText,
                    keepMinimumWidth = isAssistantMessage
                )
            }
            bubble.addView(text)
        }

        val imagesToRender = imageMessages.ifEmpty {
            if (message.kind == "image" && !message.imageUrl.isNullOrBlank()) listOf(message) else emptyList()
        }
        if (imagesToRender.isNotEmpty()) {
            bubble.addView(
                conversationImageGroupView(
                    messages = imagesToRender,
                    topMargin = if (markdown.isNotBlank()) dp(8) else 0,
                    isUserMessage = isUserMessage
                )
            )
        }

        if (message.kind == "file") {
            bubble.addView(
                conversationFileView(
                    message = message,
                    topMargin = if (markdown.isNotBlank()) dp(8) else 0,
                    isUserMessage = isUserMessage
                )
            )
        }

        val conversationTimestampWidth = LinearLayout.LayoutParams.WRAP_CONTENT
        val timestampText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                conversationTimestampWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params ->
                params.topMargin = dp(6)
                if (isUserMessage) {
                    params.gravity = Gravity.END
                }
            }
            this.text = MobileUiFormatter.formatMessageTimestamp(message.timestamp)
            textSize = 11f
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isUserMessage) R.color.message_user_role else R.color.detail_secondary_text
                )
            )
            gravity = when {
                isSystemMessage -> Gravity.CENTER
                else -> Gravity.START
            }
        }
        bubble.addView(timestampText)

        row.addView(bubble)
        return row
    }

    private fun applyConversationMeasuredTextWidth(
        target: TextView,
        measurementText: CharSequence? = null,
        keepMinimumWidth: Boolean
    ) {
        val maxWidth = target.maxWidth
        if (maxWidth <= 0 || maxWidth == Int.MAX_VALUE) {
            return
        }
        val source = measurementText ?: target.text ?: return
        val desiredWidth = desiredConversationTextWidth(source, target)
        if (desiredWidth <= 0) {
            return
        }

        val targetWidth = ConversationBubbleWidthSupport.resolvedTextWidth(
            desiredWidth = desiredWidth,
            minimumWidth = target.minWidth,
            maximumWidth = maxWidth,
            bufferWidth = dp(CONVERSATION_TEXT_WIDTH_BUFFER_DP),
            keepMinimumWidth = keepMinimumWidth
        ) ?: return
        val params = target.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.width != targetWidth) {
            params.width = targetWidth
            target.layoutParams = params
        }
    }

    private fun desiredConversationTextWidth(source: CharSequence, target: TextView): Int {
        var maxLineWidth = 0f
        var lineStart = 0
        while (lineStart <= source.length) {
            var lineEnd = lineStart
            while (lineEnd < source.length && source[lineEnd] != '\n') {
                lineEnd += 1
            }
            if (lineEnd > lineStart) {
                maxLineWidth = maxOf(
                    maxLineWidth,
                    Layout.getDesiredWidth(source, lineStart, lineEnd, target.paint)
                )
            }
            if (lineEnd >= source.length) {
                break
            }
            lineStart = lineEnd + 1
        }
        return ceil(maxLineWidth).toInt()
    }

    private fun startDetailTypingAnimation(
        target: TextView,
        threadId: String,
        messageId: String,
        messageKey: String,
        markdown: String,
        displayedText: String,
        startIndex: Int
    ) {
        val safeStartIndex = startIndex.coerceIn(0, displayedText.length)
        DebugTraceLogger.log(
            "detail-typing",
            "startAnimation message=$messageId len=${displayedText.length} start=$safeStartIndex " +
                "active=${detailTypingMessageId ?: "none"}"
        )
        if (safeStartIndex >= displayedText.length) {
            renderConversationMarkdown(target, markdown, threadId)
            detailTypingVisibleCharsByMessageKey[messageKey] = displayedText.length
            detailTypingAnimator = null
            detailTypingMessageId = null
            detailTypingMessageKey = null
            keepDetailBottomForTyping = false
            DebugTraceLogger.log("detail-typing", "skipAnimation message=$messageId reason=already-complete")
            return
        }

        detailTypingAnimator?.cancel()
        detailTypingMessageId = messageId
        detailTypingMessageKey = messageKey
        keepDetailBottomForTyping = shouldKeepDetailBottomForTyping()
        target.setTextIsSelectable(false)
        target.text = displayedText.take(safeStartIndex)

        val remainingChars = displayedText.length - safeStartIndex
        val typingCharsPerSecond = GatewayPreferences.loadDetailTypingCharsPerSecond(this)
        val durationMs = detailTypingAnimationDurationMillis(
            remainingChars = remainingChars,
            typingCharsPerSecond = typingCharsPerSecond
        )
        detailTypingAnimator = ValueAnimator.ofInt(safeStartIndex, displayedText.length).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val visibleChars = animator.animatedValue as Int
                detailTypingVisibleCharsByMessageKey[messageKey] = visibleChars
                target.text = displayedText.take(visibleChars)
                if (keepDetailBottomForTyping && currentScreen == ConnectedScreen.DETAIL) {
                    target.post {
                        if (keepDetailBottomForTyping && currentScreen == ConnectedScreen.DETAIL) {
                            scrollDetailToBottomWithoutFocusChange()
                        }
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                    keepDetailBottomForTyping = false
                    DebugTraceLogger.log("detail-typing", "cancelAnimation message=$messageId")
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        renderConversationMarkdown(target, markdown, threadId)
                        detailTypingVisibleCharsByMessageKey[messageKey] = displayedText.length
                        DebugTraceLogger.log("detail-typing", "finishAnimation message=$messageId")
                    }
                    if (detailTypingMessageId == messageId) {
                        detailTypingMessageId = null
                    }
                    if (detailTypingMessageKey == messageKey) {
                        detailTypingMessageKey = null
                    }
                    if (detailTypingAnimator === animation) {
                        detailTypingAnimator = null
                    }
                    keepDetailBottomForTyping = false
                }
            })
            target.post {
                if (target.isAttachedToWindow) {
                    start()
                }
            }
        }
    }

    private fun detailTypingAnimationDurationMillis(
        remainingChars: Int,
        typingCharsPerSecond: Int
    ): Long {
        val safeCharsPerSecond = typingCharsPerSecond.coerceAtLeast(1)
        val millisPerChar = 1000.0 / safeCharsPerSecond.toDouble()
        return (remainingChars * millisPerChar).toLong().coerceIn(320L, 8_000L)
    }

    private fun failedMessageIndicator(message: ThreadMessage): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { params ->
                params.gravity = Gravity.CENTER_VERTICAL
                params.marginEnd = dp(6)
            }
            text = "!"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(ContextCompat.getColor(context, R.color.message_failed_indicator_text))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@MainActivity, R.color.message_failed_indicator_bg))
            }
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.message_send_failed_retry)
            setOnClickListener {
                viewModel.retryFailedMessage(message.messageId,
                    openStream = { draft ->
                        contentResolver.openInputStream(Uri.parse(draft.previewUri))
                            ?: throw IOException("image_open_failed")
                    },
                    openFileStream = { draft ->
                        contentResolver.openInputStream(Uri.parse(draft.previewUri))
                            ?: throw IOException("file_open_failed")
                    }
                )
            }
        }
    }

    private fun conversationImageGroupView(
        messages: List<ThreadMessage>,
        topMargin: Int,
        isUserMessage: Boolean
    ): View {
        val maxGroupWidth = (
            resources.displayMetrics.widthPixels *
                if (isUserMessage) 0.72f else 0.82f
            ).toInt()
        val gap = dp(6)
        val columnCount = if (messages.size == 1) 1 else 2
        val imageWidth = if (columnCount == 1) {
            minOf(maxGroupWidth, dp(220))
        } else {
            (maxGroupWidth - gap) / 2
        }
        val imageHeight = if (columnCount == 1) dp(180) else dp(150)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params ->
                params.topMargin = topMargin
            }
            messages.chunked(columnCount).forEachIndexed { rowIndex, rowMessages ->
                val imageRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { params ->
                        if (rowIndex > 0) {
                            params.topMargin = gap
                        }
                    }
                }
                rowMessages.forEachIndexed { columnIndex, imageMessage ->
                    val imageCell = FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            imageWidth,
                            imageHeight
                        ).also { params ->
                            if (columnIndex > 0) {
                                params.marginStart = gap
                            }
                        }
                    }
                    val image = ImageView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        isClickable = true
                        isFocusable = true
                        load(
                            data = imageMessage.thumbnailUrl ?: imageMessage.imageUrl,
                            imageLoader = com.codex.mobilecontrol.ui.GatewayImageLoader.get(context)
                        )
                        setOnClickListener {
                            if (imageMessage.sendState == ThreadMessageSendState.FAILED) {
                                viewModel.retryFailedMessage(imageMessage.messageId,
                                    openStream = { draft ->
                                        contentResolver.openInputStream(Uri.parse(draft.previewUri))
                                            ?: throw IOException("image_open_failed")
                                    },
                                    openFileStream = { draft ->
                                        contentResolver.openInputStream(Uri.parse(draft.previewUri))
                                            ?: throw IOException("file_open_failed")
                                    }
                                )
                            } else {
                                showMessageImagePreviewDialog(imageMessage)
                            }
                        }
                    }
                    imageCell.addView(image)
                    attachmentSendStateText(imageMessage)?.let { stateText ->
                        imageCell.addView(
                            TextView(context).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                                ).also { params -> params.setMargins(dp(6), dp(6), dp(6), dp(8)) }
                                text = stateText
                                textSize = 11f
                                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                                setPadding(dp(8), dp(4), dp(8), dp(4))
                                background = roundedConversationBackground(
                                    colorRes = if (imageMessage.sendState == ThreadMessageSendState.FAILED) {
                                        R.color.message_failed_indicator_bg
                                    } else {
                                        R.color.dashboard_chip_bg
                                    },
                                    strokeColorRes = if (imageMessage.sendState == ThreadMessageSendState.FAILED) {
                                        R.color.message_failed_indicator_bg
                                    } else {
                                        R.color.dashboard_card_stroke
                                    }
                                )
                            }
                        )
                    }
                    imageRow.addView(imageCell)
                }
                addView(imageRow)
            }
        }
    }

    private fun conversationFileView(
        message: ThreadMessage,
        topMargin: Int,
        isUserMessage: Boolean
    ): View {
        val maxGroupWidth = (
            resources.displayMetrics.widthPixels *
                if (isUserMessage) 0.72f else 0.82f
            ).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                maxGroupWidth.coerceAtMost(dp(260)),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params ->
                params.topMargin = topMargin
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedConversationBackground(
                colorRes = R.color.detail_nested_card_surface,
                strokeColorRes = R.color.dashboard_card_stroke
            )
            if (message.sendState == ThreadMessageSendState.FAILED) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    viewModel.retryFailedMessage(message.messageId,
                        openStream = { draft ->
                            contentResolver.openInputStream(Uri.parse(draft.previewUri))
                                ?: throw IOException("image_open_failed")
                        },
                        openFileStream = { draft ->
                            contentResolver.openInputStream(Uri.parse(draft.previewUri))
                                ?: throw IOException("file_open_failed")
                        }
                    )
                }
            }

            addView(
                ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).also { params ->
                        params.marginEnd = dp(8)
                    }
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    setImageResource(android.R.drawable.ic_menu_save)
                    setColorFilter(ContextCompat.getColor(context, R.color.detail_link_text))
                }
            )

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                    addView(
                        TextView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            ellipsize = TextUtils.TruncateAt.END
                            maxLines = 1
                            text = message.fileName ?: getString(R.string.attachment_pick_file)
                            textSize = 14f
                            setTextColor(ContextCompat.getColor(context, R.color.detail_primary_text))
                        }
                    )
                    addView(
                        TextView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { params -> params.topMargin = dp(2) }
                            ellipsize = TextUtils.TruncateAt.END
                            maxLines = 1
                            text = attachmentSendStateText(message) ?: message.mimeType ?: "application/octet-stream"
                            textSize = 11f
                            setTextColor(
                                ContextCompat.getColor(
                                    context,
                                    if (message.sendState == ThreadMessageSendState.FAILED) {
                                        R.color.login_error_text
                                    } else {
                                        R.color.detail_secondary_text
                                    }
                                )
                            )
                        }
                    )
                }
            )
        }
    }

    private fun attachmentSendStateText(message: ThreadMessage): String? {
        return when (message.sendState) {
            ThreadMessageSendState.SENDING -> getString(R.string.attachment_upload_sending)
            ThreadMessageSendState.FAILED -> getString(R.string.attachment_upload_failed_retry)
            null -> null
        }
    }

    private fun conversationMessageMarkdown(message: ThreadMessage): String {
        if (message.kind == "image") {
            return message.text
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: ""
        }

        if (message.kind == "file") {
            return message.text
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: ""
        }

        return message.text
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: message.fileName
            ?: ""
    }

    private fun conversationMessageTypingText(markdown: String): String {
        return markwon.toMarkdown(markdown).toString()
    }

    private fun renderConversationMarkdown(target: TextView, markdown: String, threadId: String) {
        renderMarkdown(target, markdown, threadId)
        target.setTextIsSelectable(false)
        installConversationMessageCopyAction(target)
    }

    private fun renderMarkdown(target: TextView, markdown: String, threadId: String? = null): Boolean {
        target.setTextIsSelectable(false)
        target.movementMethod = null
        target.linksClickable = false
        target.setOnTouchListener(null)
        markwon.setMarkdown(target, markdown)
        return bindMarkdownPreviewLinks(target, threadId)
    }

    private fun bindMarkdownPreviewLinks(target: TextView, threadId: String?): Boolean {
        if (threadId.isNullOrBlank()) {
            return false
        }
        val spanned = target.text as? Spanned ?: return false
        val urlSpans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
        if (urlSpans.isEmpty()) {
            return false
        }

        val spannable = SpannableString(spanned)
        var hasPreviewLinks = false
        urlSpans.forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start) {
                return@forEach
            }
            val label = spanned.subSequence(start, end).toString()
            val previewPath = MarkdownPreviewSupport.previewPathFor(span.url, label) ?: return@forEach
            spannable.removeSpan(span)
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        showMarkdownFilePreviewDialog(threadId, previewPath)
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = ContextCompat.getColor(this@MainActivity, R.color.detail_link_text)
                        ds.isUnderlineText = true
                    }
                },
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            hasPreviewLinks = true
        }

        if (hasPreviewLinks) {
            target.text = spannable
            target.setTextIsSelectable(false)
            target.linksClickable = true
            target.movementMethod = null
            installMarkdownPreviewLinkClickHandler(target)
            target.highlightColor = Color.TRANSPARENT
        }
        return hasPreviewLinks
    }

    private fun showMarkdownFilePreviewDialog(threadId: String, path: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = unifiedDialogPanel(getString(R.string.markdown_preview_title))
        var latestPreviewPath = path
        var latestPreviewContent: String? = null
        val fileNameText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params -> params.bottomMargin = dp(4) }
            text = path.substringAfterLast('/').substringAfterLast('\\')
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.detail_secondary_text))
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 2
        }
        val pathText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params -> params.bottomMargin = dp(6) }
            text = getString(R.string.markdown_preview_full_path, path)
            textSize = 11f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(ContextCompat.getColor(context, R.color.profile_muted_text))
        }
        pathText.setTextIsSelectable(true)
        val metaText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params -> params.bottomMargin = dp(8) }
            text = formatMarkdownPreviewMeta(path, null)
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.profile_muted_text))
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 2
        }
        val bodyText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = getString(R.string.markdown_preview_loading)
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(ContextCompat.getColor(context, R.color.detail_body_text))
        }
        panel.addView(fileNameText)
        panel.addView(pathText)
        panel.addView(metaText)
        panel.addView(
            ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.52f).toInt().coerceAtLeast(dp(220))
                ).also { params -> params.bottomMargin = dp(12) }
                isFillViewport = false
                addView(bodyText)
            }
        )
        panel.addView(
            LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.END
                addView(
                    unifiedDialogFooterButton(
                        title = getString(R.string.markdown_preview_copy_path),
                        primary = false
                    ) {
                        copyMarkdownPreviewText(
                            label = getString(R.string.markdown_preview_copy_path),
                            value = latestPreviewPath
                        )
                    }
                )
                addView(
                    unifiedDialogFooterButton(
                        title = getString(R.string.markdown_preview_copy_content),
                        primary = false
                    ) {
                        val content = latestPreviewContent
                        if (content == null) {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.markdown_preview_copy_empty),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            copyMarkdownPreviewText(
                                label = getString(R.string.markdown_preview_copy_content),
                                value = content
                            )
                        }
                    }
                )
                addView(
                    unifiedDialogFooterButton(
                        title = getString(R.string.markdown_preview_close),
                        primary = true
                    ) {
                        dialog.dismiss()
                    }
                )
            }
        )
        dialog.setContentView(panel)
        dialog.show()
        styleUnifiedDialogWindow(dialog)

        viewModel.fetchMarkdownFilePreview(threadId, path) { result ->
            if (!dialog.isShowing) {
                return@fetchMarkdownFilePreview
            }
            result
                .onSuccess { preview ->
                    latestPreviewPath = preview.path
                    latestPreviewContent = preview.content
                    fileNameText.text = preview.fileName
                    pathText.text = getString(R.string.markdown_preview_full_path, preview.path)
                    metaText.text = formatMarkdownPreviewMeta(preview.path, preview.sizeBytes)
                    if (MarkdownPreviewSupport.isJsonPreviewPath(preview.path)) {
                        bodyText.typeface = Typeface.MONOSPACE
                        bodyText.movementMethod = null
                        bodyText.text = MarkdownPreviewSupport.formatJsonPreviewContent(preview.content)
                        bodyText.setTextIsSelectable(true)
                    } else if (MarkdownPreviewSupport.isPlainTextPreviewPath(preview.path)) {
                        bodyText.typeface = Typeface.MONOSPACE
                        bodyText.movementMethod = null
                        bodyText.text = preview.content
                        bodyText.setTextIsSelectable(true)
                    } else {
                        bodyText.typeface = Typeface.DEFAULT
                        val hasNestedPreviewLinks = renderMarkdown(bodyText, preview.content, threadId)
                        if (!hasNestedPreviewLinks) {
                            bodyText.setTextIsSelectable(true)
                        }
                    }
                }
                .onFailure { failure ->
                    fileNameText.text = getString(R.string.markdown_preview_failed)
                    metaText.text = formatMarkdownPreviewMeta(path, null)
                    bodyText.setTextIsSelectable(false)
                    bodyText.movementMethod = null
                    bodyText.text = failure.message
                        ?.trim()
                        ?.takeIf { message -> message.isNotEmpty() }
                        ?: getString(R.string.markdown_preview_failed_subtitle)
                }
        }
    }

    private fun formatMarkdownPreviewMeta(path: String, sizeBytes: Long?): String {
        val sizeText = sizeBytes?.let(::formatMarkdownPreviewSize)
            ?: getString(R.string.markdown_preview_size_unknown)
        return getString(R.string.markdown_preview_meta, path, sizeText)
    }

    private fun formatMarkdownPreviewSize(sizeBytes: Long): String {
        val safeBytes = sizeBytes.coerceAtLeast(0L)
        return when {
            safeBytes < 1024L -> "$safeBytes B"
            safeBytes < 1024L * 1024L -> {
                String.format(Locale.US, "%.1f KB", safeBytes / 1024.0)
            }
            else -> String.format(Locale.US, "%.1f MB", safeBytes / (1024.0 * 1024.0))
        }
    }

    private fun copyMarkdownPreviewText(label: String, value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, getString(R.string.markdown_preview_copied), Toast.LENGTH_SHORT).show()
    }

    private fun installConversationMessageCopyAction(target: TextView) {
        target.setOnLongClickListener {
            val copyText = target.text
                ?.toString()
                ?.trim()
                .orEmpty()
            if (copyText.isBlank()) {
                false
            } else {
                copyMarkdownPreviewText(
                    label = getString(R.string.conversation_message_copy_label),
                    value = copyText
                )
                true
            }
        }
    }

    private fun installMarkdownPreviewLinkClickHandler(target: TextView) {
        val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var downSpan: ClickableSpan? = null

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downSpan = clickableSpanAt(target, event)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                        downSpan = null
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val span = downSpan
                    val isTap =
                        abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop
                    downSpan = null
                    if (span != null && isTap && clickableSpanAt(target, event) === span) {
                        span.onClick(target)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    downSpan = null
                    false
                }
                else -> false
            }
        }
    }

    private fun clickableSpanAt(target: TextView, event: MotionEvent): ClickableSpan? {
        val spanned = target.text as? Spanned ?: return null
        val layout = target.layout ?: return null
        val x = event.x - target.totalPaddingLeft + target.scrollX
        val y = event.y - target.totalPaddingTop + target.scrollY
        if (x < 0f || y < 0f) {
            return null
        }

        val line = layout.getLineForVertical(y.toInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) {
            return null
        }

        val offset = layout.getOffsetForHorizontal(line, x)
        return spanned.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }

    private fun roundedConversationBackground(
        colorRes: Int,
        strokeColorRes: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(ContextCompat.getColor(this@MainActivity, colorRes))
            setStroke(dp(1), ContextCompat.getColor(this@MainActivity, strokeColorRes))
        }
    }

    private fun bindDetailChecklist(items: List<DetailChecklistItem>) {
        binding.detailTodoList.removeAllViews()
        items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
            }

            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                setImageResource(R.drawable.ic_detail_check_circle)
            }
            row.addView(icon)

            val text = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).also { params -> params.marginStart = dp(10) }
                setTextColor(ContextCompat.getColor(context, R.color.detail_body_text))
                textSize = 15f
                this.text = item.text
            }
            row.addView(text)
            binding.detailTodoList.addView(row)

            if (index < items.lastIndex) {
                binding.detailTodoList.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(1)
                        ).also { lp ->
                            lp.topMargin = dp(2)
                            lp.bottomMargin = dp(2)
                        }
                        setBackgroundColor(ContextCompat.getColor(context, R.color.detail_row_divider))
                    }
                )
            }
        }
    }

    private fun bindDetailFileChanges(items: List<DetailFileChangeItem>) {
        binding.detailFileChangesList.removeAllViews()
        items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dp(54)
            }

            val pathText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setTextColor(ContextCompat.getColor(context, R.color.detail_primary_text))
                textSize = 14f
                text = item.path
            }
            row.addView(pathText)

            if (!item.added.isNullOrBlank()) {
                row.addView(metricText(item.added, R.color.detail_positive_text))
            }
            if (!item.removed.isNullOrBlank()) {
                row.addView(metricText(item.removed, R.color.detail_negative_text))
            }

            binding.detailFileChangesList.addView(row)

            if (index < items.lastIndex) {
                binding.detailFileChangesList.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(1)
                        )
                        setBackgroundColor(ContextCompat.getColor(context, R.color.detail_row_divider))
                    }
                )
            }
        }
    }

    private fun syncFileChangesCard(visualContent: DetailVisualContent, threadId: String?) {
        if (!visualContent.showFileChanges) {
            binding.detailFileChangesCard.visibility = View.GONE
            binding.detailFileChangesList.visibility = View.GONE
            binding.detailFilesTodoLabel.visibility = View.GONE
            if (threadId == null || expandedFileChangesThreadId == threadId) {
                expandedFileChangesThreadId = null
            }
            return
        }

        val fileChangesExpanded = expandedFileChangesThreadId == threadId
        binding.detailFileChangesCard.visibility = View.VISIBLE
        binding.detailFileChangesTitle.text = styledFileSummary(visualContent.fileSummary)
        binding.detailFilesTodoLabel.text = visualContent.filesMarkerText
        binding.detailFileChangesList.visibility = if (fileChangesExpanded) View.VISIBLE else View.GONE
        binding.detailFilesTodoLabel.visibility =
            if (fileChangesExpanded && visualContent.showFilesMarker) View.VISIBLE else View.GONE
        binding.detailFileChangesToggleIcon.rotation = if (fileChangesExpanded) 180f else 0f
    }

    private fun bindComposerRuntimeState(detail: ThreadDetail?) {
        binding.composerScopeText.text = detail?.composerState?.permissionLabel
            ?: getString(R.string.detail_scope_unknown)
        binding.composerModelText.text = detail?.composerState?.modelLabel
            ?: getString(R.string.detail_model_unknown)
    }

    private fun styledFileSummary(summary: String): CharSequence {
        val styled = SpannableString(summary)
        applyFileSummaryColor(
            styled = styled,
            pattern = FILE_ADD_PATTERN,
            color = ContextCompat.getColor(this, R.color.detail_positive_text)
        )
        applyFileSummaryColor(
            styled = styled,
            pattern = FILE_REMOVE_PATTERN,
            color = ContextCompat.getColor(this, R.color.detail_negative_text)
        )
        return styled
    }

    private fun applyFileSummaryColor(styled: SpannableString, pattern: Regex, color: Int) {
        pattern.findAll(styled).forEach { match ->
            styled.setSpan(
                ForegroundColorSpan(color),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun metricText(value: String, colorRes: Int): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { params -> params.marginStart = dp(10) }
            setTextColor(ContextCompat.getColor(context, colorRes))
            textSize = 14f
            text = value
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    fun onImagePickedForTest(previewUri: String, fileName: String, mimeType: String) {
        viewModel.setPendingImage(PendingImageDraft(previewUri, fileName, mimeType))
    }

    private fun showLoginState() {
        binding.configCard.animate().cancel()
        binding.connectedShell.animate().cancel()
        binding.connectProgress.animate().cancel()
        binding.sendProgress.animate().cancel()
        binding.detailTransitionProgress.animate().cancel()
        binding.motionOverlay.animate().cancel()
        binding.motionOverlayCard.animate().cancel()
        cancelPendingMotionOverlayHide()
        cancelDetailAlertRefresh()
        binding.detailJumpToBottomButton.visibility = View.GONE
        binding.configCard.visibility = View.VISIBLE
        binding.configCard.alpha = 1f
        binding.configCard.translationY = 0f
        binding.connectedShell.visibility = View.GONE
        binding.connectedShell.alpha = 1f
        binding.connectedShell.translationY = 0f
        binding.connectionFieldsGroup.visibility = View.VISIBLE
        binding.connectButton.visibility = View.VISIBLE
        binding.topAppBar.visibility = View.GONE
        binding.connectionBanner.visibility = View.GONE
        binding.rootDrawer.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        window.statusBarColor = ContextCompat.getColor(this, R.color.login_screen_top)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.login_screen_top)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false
        lastRenderedShellState = null
        pendingDetailThreadId = null
        pendingDetailTitle = null
        binding.connectProgress.visibility = View.GONE
        binding.sendProgress.visibility = View.GONE
        binding.detailTransitionProgress.visibility = View.GONE
        binding.configCard.isFocusableInTouchMode = true
        binding.configCard.requestFocus()
        binding.gatewayUrlInput.clearFocus()
        binding.tokenInput.clearFocus()
        binding.configCard.post {
            binding.configCard.requestFocus()
            binding.gatewayUrlInput.clearFocus()
            binding.tokenInput.clearFocus()
        }
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        if (viewModel.state.value.connectionState != StreamConnectionState.CONNECTING) {
            binding.motionOverlay.visibility = View.GONE
            binding.motionOverlay.alpha = 1f
            binding.motionOverlayCard.alpha = 1f
            binding.motionOverlayCard.translationY = 0f
            binding.motionOverlayCard.scaleX = 1f
            binding.motionOverlayCard.scaleY = 1f
            activeMotionOverlayKey = null
            motionOverlayShownAtMs = 0L
        }
    }

    private fun showContentState(animateEntrance: Boolean = false) {
        binding.configCard.animate().cancel()
        binding.connectedShell.animate().cancel()
        binding.connectedShell.visibility = View.VISIBLE
        binding.topAppBar.visibility = View.GONE
        binding.connectionBanner.visibility = View.GONE
        binding.rootDrawer.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        window.statusBarColor = ContextCompat.getColor(this, R.color.screen_background)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.screen_background)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false

        if (!animateEntrance) {
            binding.configCard.visibility = View.GONE
            binding.connectedShell.alpha = 1f
            binding.connectedShell.translationY = 0f
            binding.connectedShell.scaleX = 1f
            binding.connectedShell.scaleY = 1f
            return
        }

        val shift = motionOffsetPx()
        binding.configCard.visibility = View.VISIBLE
        binding.configCard.alpha = 1f
        binding.configCard.translationY = 0f
        binding.configCard.scaleX = 1f
        binding.configCard.scaleY = 1f
        binding.connectedShell.alpha = 0f
        binding.connectedShell.translationY = shift
        binding.connectedShell.scaleX = 0.985f
        binding.connectedShell.scaleY = 0.985f

        binding.connectedShell.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(CONTENT_ENTER_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .start()

        binding.configCard.animate()
            .alpha(0f)
            .translationY(-shift / 2f)
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(CONTENT_ENTER_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                binding.configCard.visibility = View.GONE
                binding.configCard.alpha = 1f
                binding.configCard.translationY = 0f
                binding.configCard.scaleX = 1f
                binding.configCard.scaleY = 1f
            }
            .start()
    }

    private fun showTaskScreen() {
        val wasTaskScreen = currentScreen == ConnectedScreen.TASKS
        logTaskListState(
            "screen.tasks.show",
            "from=$currentScreen wasTask=$wasTaskScreen taskScrollY=${binding.taskPage.scrollY}"
        )
        currentScreen = ConnectedScreen.TASKS
        cancelDetailAlertRefresh()
        cancelDetailRuntimeSubtitleRefresh()
        syncDetailJumpToBottomButton(0)
        applyConnectedScreen(animated = false)
        if (!wasTaskScreen) {
            restoreTaskPageTopAfterLayout("screen.tasks")
        }
        logTaskListSnapshotAfterLayout("screen.tasks.afterLayout", viewModel.state.value)
    }

    private fun restoreTaskPageTopAfterLayout(reason: String) {
        binding.taskPage.requestFocus()
        binding.taskPage.scrollTo(0, 0)
        binding.taskPage.post {
            binding.taskPage.requestFocus()
            binding.taskPage.scrollTo(0, 0)
            logTaskListState(
                "task.restoreTop",
                "reason=$reason step=post taskScrollY=${binding.taskPage.scrollY}"
            )
        }
        binding.taskPage.postDelayed({
            binding.taskPage.requestFocus()
            binding.taskPage.scrollTo(0, 0)
            logTaskListState(
                "task.restoreTop",
                "reason=$reason step=postDelayed taskScrollY=${binding.taskPage.scrollY}"
            )
        }, TASK_LIST_TOP_RESTORE_DELAY_MS)
    }

    private fun showProfileScreen() {
        val wasProfileScreen = currentScreen == ConnectedScreen.PROFILE
        logTaskListState(
            "screen.profile.show",
            "from=$currentScreen wasProfile=$wasProfileScreen taskScrollY=${binding.taskPage.scrollY}"
        )
        currentScreen = ConnectedScreen.PROFILE
        cancelDetailAlertRefresh()
        cancelDetailRuntimeSubtitleRefresh()
        syncDetailJumpToBottomButton(0)
        if (!wasProfileScreen) {
            resetProfileScrollToTop()
        }
        applyConnectedScreen(animated = false)
        viewModel.checkLatestApk()
        viewModel.refreshAutomations()
    }

    private fun resetProfileScrollToTop() {
        binding.profilePage.scrollTo(0, 0)
        binding.profilePage.post {
            binding.profilePage.scrollTo(0, 0)
        }
    }

    private fun openThreadDetail(threadId: String) {
        logTaskListState(
            "screen.detail.open",
            "from=$currentScreen target=${threadId.logId()} taskScrollY=${binding.taskPage.scrollY}"
        )
        currentScreen = ConnectedScreen.DETAIL
        val state = viewModel.state.value
        val isSameLoadedThread =
            state.selectedThreadId == threadId && state.detail?.thread?.threadId == threadId
        val requiresDetailSync = threadId in state.pendingDetailSyncThreadIds
        val shouldRestoreInitialBottom =
            isSameLoadedThread && lastRenderedDetailThreadId != threadId
        if (!isSameLoadedThread || requiresDetailSync) {
            if (!isSameLoadedThread) {
                requestDetailScrollToBottom()
                pendingDetailThreadId = threadId
                pendingDetailTitle = state.threads.firstOrNull { it.threadId == threadId }?.title
            } else {
                pendingDetailThreadId = null
                pendingDetailTitle = null
            }
            viewModel.selectThread(threadId)
            bindDetailPage(state)
        } else {
            pendingDetailThreadId = null
            pendingDetailTitle = null
        }
        applyConnectedScreen(animated = false)
        if (isSameLoadedThread) {
            if (shouldRestoreInitialBottom) {
                scrollDetailToBottomAfterLayout()
            } else {
                syncDetailJumpToBottomButtonAfterLayout()
            }
            lastRenderedDetailThreadId = threadId
        }
        syncDetailAlertUi(threadId, enabled = viewModel.state.value.isConnected)
    }

    private fun refreshCurrentDetail() {
        val state = viewModel.state.value
        val threadId = state.selectedThreadId ?: state.threads.firstOrNull()?.threadId ?: return
        pendingDetailThreadId = threadId
        pendingDetailTitle = state.threads.firstOrNull { it.threadId == threadId }?.title
            ?: state.detail?.thread?.title
        requestDetailScrollToBottom()
        viewModel.refreshThreadDetail(threadId) { result ->
            binding.detailRefreshButton.post {
                if (detailRefreshSuccessToastPending &&
                    viewModel.state.value.selectedThreadId == threadId
                ) {
                    showDetailRefreshResultToast(result)
                }
                detailRefreshSuccessToastPending = false
                restoreDetailRefreshButton()
            }
        }
    }

    private fun requestDetailScrollToBottom() {
        shouldScrollDetailToBottom = true
    }

    private fun maybeScrollDetailTimelineToBottom(state: MainUiState) {
        if (!shouldScrollDetailToBottom || currentScreen != ConnectedScreen.DETAIL) {
            return
        }

        val targetThreadId = pendingDetailThreadId ?: state.selectedThreadId ?: return
        val detailThreadId = state.detail?.thread?.threadId ?: return
        if (detailThreadId != targetThreadId) {
            return
        }

        shouldScrollDetailToBottom = false
        binding.detailScroll.post {
            scrollDetailToBottomWithoutFocusChange()
        }
    }

    private fun applyConnectedScreen(animated: Boolean = false) {
        val shellState = ConnectedShellState.forScreen(currentScreen)
        val previousShellState = lastRenderedShellState

        if (previousShellState == shellState) {
            renderDynamicShellContent(shellState)
            syncMotionOverlay(viewModel.state.value)
            return
        }

        logTaskListState(
            "shell.apply",
            "previous=$previousShellState next=$shellState animated=$animated " +
                "taskScrollY=${binding.taskPage.scrollY}"
        )
        if (animated && previousShellState != null && previousShellState != shellState) {
            animateScreenTransition(previousShellState, shellState)
        } else {
            renderShellState(shellState)
        }

        lastRenderedShellState = shellState
        syncMotionOverlay(viewModel.state.value)
    }

    private fun renderShellState(shellState: ConnectedShellState) {
        binding.taskPage.alpha = 1f
        binding.taskPage.translationX = 0f
        binding.taskPage.scaleX = 1f
        binding.taskPage.scaleY = 1f
        binding.detailPage.alpha = 1f
        binding.detailPage.translationX = 0f
        binding.detailPage.scaleX = 1f
        binding.detailPage.scaleY = 1f
        binding.profilePage.alpha = 1f
        binding.profilePage.translationX = 0f
        binding.profilePage.scaleX = 1f
        binding.profilePage.scaleY = 1f
        binding.taskPage.visibility = if (shellState.showTaskPage) View.VISIBLE else View.GONE
        binding.detailPage.visibility = if (shellState.showDetailContainer) View.VISIBLE else View.GONE
        renderDetailContent(shellState)
        if (shellState.clearComposerFocus) {
            hideComposerKeyboard()
        }
        updateBottomNav(shellState.activeNav)
        syncBottomNavForIme()
    }

    private fun renderDetailContent(shellState: ConnectedShellState) {
        binding.detailTopBar.visibility = if (shellState.showDetailPage) View.VISIBLE else View.GONE
        binding.detailScroll.visibility = if (shellState.showDetailPage) View.VISIBLE else View.GONE
        binding.detailJumpToBottomButton.visibility =
            if (shellState.showDetailPage) binding.detailJumpToBottomButton.visibility else View.GONE
        if (shellState.showDetailPage) {
            syncDetailJumpToBottomButtonAfterLayout()
        }
        binding.profilePage.visibility = if (shellState.showProfilePage) View.VISIBLE else View.GONE
        binding.composerCard.visibility = if (shellState.showComposer) View.VISIBLE else View.GONE
        renderDynamicShellContent(shellState)
    }

    private fun renderDynamicShellContent(shellState: ConnectedShellState) {
        val state = viewModel.state.value
        binding.pendingImageCard.visibility =
            if (
                ComposerAttachmentSupport.isCardVisible(
                    showComposer = shellState.showComposer,
                    pendingImages = state.pendingImageDrafts,
                    pendingFiles = state.pendingFileDrafts
                )
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        binding.composerStatus.visibility =
            if (
                ComposerStatusSupport.isStatusVisible(
                    showComposer = shellState.showComposer,
                    queuedTextMessages = queuedMessagesForThread(
                        state.queuedTextMessages,
                        state.selectedThreadId
                    ),
                    selectedThreadId = state.selectedThreadId
                )
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun animateScreenTransition(
        previousShellState: ConnectedShellState,
        shellState: ConnectedShellState
    ) {
        val outgoingView = shellRootView(previousShellState)
        val incomingView = shellRootView(shellState)
        val shift = motionOffsetPx()
        val direction = if (shellState.showTaskPage) -1f else 1f

        if (incomingView === outgoingView) {
            renderShellState(shellState)
            incomingView.animate().cancel()
            incomingView.alpha = 0f
            incomingView.translationX = direction * (shift / 3f)
            incomingView.scaleX = 0.985f
            incomingView.scaleY = 0.985f
            incomingView.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(SCREEN_TRANSITION_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .start()
            return
        }

        binding.taskPage.animate().cancel()
        binding.detailPage.animate().cancel()
        binding.profilePage.animate().cancel()
        if (previousShellState.showTaskPage) {
            binding.taskPage.visibility = View.VISIBLE
        }
        if (previousShellState.showDetailContainer) {
            binding.detailPage.visibility = View.VISIBLE
            renderDetailContent(previousShellState)
        }
        if (previousShellState.showProfilePage) {
            binding.profilePage.visibility = View.VISIBLE
        }
        if (shellState.showDetailContainer) {
            binding.detailPage.visibility = View.VISIBLE
            renderDetailContent(shellState)
        }
        if (shellState.showProfilePage) {
            binding.profilePage.visibility = View.VISIBLE
        }
        if (shellState.showTaskPage) {
            binding.taskPage.visibility = View.VISIBLE
        }
        updateBottomNav(shellState.activeNav)

        incomingView.alpha = 0f
        incomingView.translationX = direction * shift
        incomingView.scaleX = 0.985f
        incomingView.scaleY = 0.985f
        outgoingView.alpha = 1f
        outgoingView.translationX = 0f
        outgoingView.scaleX = 1f
        outgoingView.scaleY = 1f

        incomingView.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SCREEN_TRANSITION_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .start()

        outgoingView.animate()
            .alpha(0.18f)
            .translationX(-direction * (shift / 2f))
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(SCREEN_TRANSITION_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                outgoingView.alpha = 1f
                outgoingView.translationX = 0f
                outgoingView.scaleX = 1f
                outgoingView.scaleY = 1f
                renderShellState(shellState)
            }
            .start()
    }

    private fun shellRootView(shellState: ConnectedShellState): View {
        return when {
            shellState.showTaskPage -> binding.taskPage
            shellState.showProfilePage -> binding.profilePage
            else -> binding.detailPage
        }
    }

    private fun syncConnectionMotion(state: MainUiState) {
        val isConnecting = !state.isConnected &&
            state.connectionState == StreamConnectionState.CONNECTING

        if (isConnecting) {
            binding.helperText.text = getString(R.string.connect_in_progress)
        }

        binding.connectButton.animate().cancel()
        binding.helperText.animate().cancel()
        binding.connectionFieldsGroup.animate().cancel()
        if (isConnecting != lastIsConnecting) {
            updateLoadingIndicator(binding.connectProgress, isConnecting)
        }
        binding.gatewayUrlInput.isEnabled = !isConnecting
        binding.tokenInput.isEnabled = !isConnecting
        if (isConnecting) {
            binding.connectButton.animate()
                .scaleX(0.97f)
                .scaleY(0.97f)
                .alpha(0.82f)
                .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .start()
            binding.connectionFieldsGroup.animate()
                .alpha(0.78f)
                .translationY(-motionOffsetPx(4))
                .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .start()
            binding.helperText.alpha = 0.58f
            binding.helperText.animate()
                .alpha(1f)
                .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .start()
        } else {
            binding.connectButton.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .start()
            binding.connectionFieldsGroup.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .start()
            binding.helperText.animate()
                .alpha(1f)
                .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .start()
        }

        lastIsConnecting = isConnecting
    }

    private fun syncTaskRefreshMotion(state: MainUiState) {
        if (state.isRefreshingThreads == lastIsRefreshingThreads) {
            return
        }

        binding.taskRefreshButton.animate().cancel()
        if (state.isRefreshingThreads) {
            binding.taskRefreshButton.scaleX = 0.96f
            binding.taskRefreshButton.scaleY = 0.96f
            binding.taskRefreshButton.alpha = 0.55f
        } else {
            if (lastIsRefreshingThreads && taskRefreshSuccessToastPending) {
                showTaskRefreshSuccessToast()
            }
            taskRefreshSuccessToastPending = false
            binding.taskRefreshButton.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(if (state.isConnected) 1f else 0.55f)
                .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .start()
        }

        lastIsRefreshingThreads = state.isRefreshingThreads
    }

    private fun syncOlderMessagesLoading(state: MainUiState) {
        binding.detailOlderMessagesProgress.visibility =
            if (state.isLoadingOlderMessages) View.VISIBLE else View.GONE

        if (state.isLoadingOlderMessages != lastIsLoadingOlderMessages) {
            logDetailScrollState(
                "older.loading",
                "loading=${state.isLoadingOlderMessages}"
            )
        }

        if (lastIsLoadingOlderMessages && !state.isLoadingOlderMessages) {
            val anchor = pendingOlderMessagesScrollAnchor
            pendingOlderMessagesScrollAnchor = null
            if (anchor != null && currentScreen == ConnectedScreen.DETAIL) {
                binding.detailScroll.post {
                    val delta = binding.detailScrollContent.height - anchor.contentHeight
                    if (delta > 0) {
                        binding.detailScroll.scrollTo(0, anchor.scrollY + delta)
                    }
                    logDetailScrollState(
                        "older.sync.done",
                        "anchorContent=${anchor.contentHeight} anchorScroll=${anchor.scrollY} " +
                            "delta=$delta finalScroll=${binding.detailScroll.scrollY}"
                    )
                    syncDetailJumpToBottomButton(binding.detailScroll.scrollY)
                }
            }
        }

        lastIsLoadingOlderMessages = state.isLoadingOlderMessages
    }

    private fun showTaskRefreshTapFeedback() {
        binding.taskRefreshButton.animate().cancel()
        binding.taskRefreshButton.isEnabled = false
        binding.taskRefreshButton.alpha = 0.55f
        binding.taskRefreshButton.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .alpha(0.55f)
            .setDuration(BUTTON_FEEDBACK_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                if (!viewModel.state.value.isRefreshingThreads) {
                    binding.taskRefreshButton.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(if (viewModel.state.value.isConnected) 1f else 0.55f)
                        .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                        .setInterpolator(motionInterpolator)
                        .start()
                }
            }
            .start()
    }

    private fun showTaskRefreshSuccessToast() {
        Toast.makeText(this, R.string.task_refresh_success, Toast.LENGTH_SHORT).show()
    }

    private fun showDetailRefreshTapFeedback() {
        binding.detailRefreshButton.animate().cancel()
        binding.detailRefreshButton.isEnabled = false
        binding.detailRefreshButton.alpha = 0.55f
        binding.detailRefreshButton.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .alpha(0.55f)
            .setDuration(BUTTON_FEEDBACK_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .start()
    }

    private fun restoreDetailRefreshButton() {
        val state = viewModel.state.value
        val isPendingDetail =
            pendingDetailThreadId != null && state.detail?.thread?.threadId != pendingDetailThreadId
        binding.detailRefreshButton.isEnabled = state.isConnected && !isPendingDetail
        binding.detailRefreshButton.animate().cancel()
        binding.detailRefreshButton.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(if (binding.detailRefreshButton.isEnabled) 1f else 0.42f)
            .setDuration(BUTTON_FEEDBACK_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .start()
    }

    private fun showDetailRefreshResultToast(result: DetailRefreshResult) {
        when (result.status) {
            DetailRefreshStatus.UPDATED -> {
                Toast.makeText(this, R.string.detail_refresh_synced, Toast.LENGTH_SHORT).show()
            }
            DetailRefreshStatus.UNCHANGED -> {
                Toast.makeText(this, R.string.detail_refresh_unchanged, Toast.LENGTH_SHORT).show()
            }
            DetailRefreshStatus.FAILED -> {
                Toast.makeText(this, R.string.detail_refresh_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncComposerMotion(state: MainUiState) {
        val statusText = binding.composerStatus.text?.toString().orEmpty()
        if (statusText != lastComposerStatusText) {
            binding.composerStatus.animate().cancel()
            binding.composerStatus.alpha = 0.45f
            binding.composerStatus.animate()
                .alpha(1f)
                .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .start()
            lastComposerStatusText = statusText
        }

        if (state.isSending == lastIsSending) {
            return
        }

        binding.sendButton.animate().cancel()
        updateLoadingIndicator(binding.sendProgress, false)
        binding.sendButton.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(if (binding.sendButton.isEnabled) 1f else 0.42f)
            .translationY(0f)
            .setDuration(BUTTON_FEEDBACK_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .start()
        binding.composerCard.animate()
            .translationY(0f)
            .setDuration(BUTTON_FEEDBACK_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .start()

        lastIsSending = state.isSending
    }

    private fun syncDetailTransitionMotion(state: MainUiState) {
        if (
            pendingDetailThreadId != null &&
            (state.detail?.thread?.threadId == pendingDetailThreadId || state.errorMessage != null)
        ) {
            pendingDetailThreadId = null
            pendingDetailTitle = null
        }

        updateLoadingIndicator(binding.detailTransitionProgress, false)
        binding.detailScroll.animate().cancel()
        binding.composerCard.animate().cancel()
        binding.detailScroll.alpha = 1f
        binding.detailScroll.translationY = 0f
        binding.composerCard.alpha = 1f
        binding.composerCard.translationY = 0f
    }

    private fun syncMotionOverlay(state: MainUiState) {
        val overlay = when {
            !state.isConnected && state.connectionState == StreamConnectionState.CONNECTING -> {
                MotionOverlayModel(
                    key = "connect",
                    title = getString(R.string.motion_overlay_connect_title),
                    subtitle = getString(R.string.motion_overlay_connect_subtitle)
                )
            }
            else -> null
        }

        if (overlay == null) {
            hideMotionOverlay()
        } else {
            showMotionOverlay(overlay)
        }
    }

    private fun showLoginMotionOverlay() {
        showMotionOverlay(
            MotionOverlayModel(
                key = "connect",
                title = getString(R.string.motion_overlay_connect_title),
                subtitle = getString(R.string.motion_overlay_connect_subtitle)
            )
        )
    }

    private fun showMotionOverlay(overlay: MotionOverlayModel) {
        cancelPendingMotionOverlayHide()
        binding.motionOverlayTitle.text = overlay.title
        binding.motionOverlaySubtitle.text = overlay.subtitle

        if (
            binding.motionOverlay.visibility == View.VISIBLE &&
            activeMotionOverlayKey == overlay.key
        ) {
            return
        }

        activeMotionOverlayKey = overlay.key
        motionOverlayShownAtMs = SystemClock.elapsedRealtime()
        binding.motionOverlay.animate().cancel()
        binding.motionOverlayCard.animate().cancel()
        binding.motionOverlay.visibility = View.VISIBLE
        binding.motionOverlay.alpha = 0f
        binding.motionOverlayCard.alpha = 0f
        binding.motionOverlayCard.translationY = motionOffsetPx(16)
        binding.motionOverlayCard.scaleX = 0.94f
        binding.motionOverlayCard.scaleY = 0.94f

        binding.motionOverlay.animate()
            .alpha(1f)
            .setDuration(BUTTON_FEEDBACK_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .start()

        binding.motionOverlayCard.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(CONTENT_ENTER_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .start()
    }

    private fun hideMotionOverlay(immediate: Boolean = false) {
        cancelPendingMotionOverlayHide()
        if (binding.motionOverlay.visibility != View.VISIBLE) {
            activeMotionOverlayKey = null
            motionOverlayShownAtMs = 0L
            return
        }

        val elapsedMs = SystemClock.elapsedRealtime() - motionOverlayShownAtMs
        if (!immediate && elapsedMs < MIN_MOTION_OVERLAY_VISIBLE_DURATION_MS) {
            val remainingMs = MIN_MOTION_OVERLAY_VISIBLE_DURATION_MS - elapsedMs
            pendingMotionOverlayHide = Runnable {
                pendingMotionOverlayHide = null
                hideMotionOverlay(immediate = true)
            }.also { runnable ->
                binding.root.postDelayed(runnable, remainingMs)
            }
            return
        }

        activeMotionOverlayKey = null
        motionOverlayShownAtMs = 0L
        binding.motionOverlay.animate().cancel()
        binding.motionOverlayCard.animate().cancel()

        binding.motionOverlay.animate()
            .alpha(0f)
            .setDuration(BUTTON_FEEDBACK_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .start()

        binding.motionOverlayCard.animate()
            .alpha(0f)
            .translationY(motionOffsetPx(12))
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(BUTTON_FEEDBACK_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                if (activeMotionOverlayKey == null) {
                    binding.motionOverlay.visibility = View.GONE
                    binding.motionOverlay.alpha = 1f
                    binding.motionOverlayCard.alpha = 1f
                    binding.motionOverlayCard.translationY = 0f
                    binding.motionOverlayCard.scaleX = 1f
                    binding.motionOverlayCard.scaleY = 1f
                }
            }
            .start()
    }

    private fun cancelPendingMotionOverlayHide() {
        pendingMotionOverlayHide?.let(binding.root::removeCallbacks)
        pendingMotionOverlayHide = null
    }

    private fun updateLoadingIndicator(view: View, visible: Boolean) {
        view.animate().cancel()
        if (visible) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.scaleX = 0.86f
                view.scaleY = 0.86f
                view.visibility = View.VISIBLE
            }
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(BUTTON_FEEDBACK_DURATION_MS)
                .setInterpolator(motionInterpolator)
                .start()
            return
        }

        if (view.visibility != View.VISIBLE) {
            return
        }

        view.animate()
            .alpha(0f)
            .scaleX(0.86f)
            .scaleY(0.86f)
            .setDuration(BUTTON_FEEDBACK_DURATION_MS)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
            }
            .start()
    }

    private fun motionOffsetPx(dp: Int = 44): Float {
        return dp * resources.displayMetrics.density
    }

    private fun updateBottomNav(activeNav: ConnectedScreen) {
        val activeColor = ContextCompat.getColor(this, R.color.dashboard_nav_active)
        val inactiveColor = ContextCompat.getColor(this, R.color.dashboard_nav_inactive)

        updateNavItem(
            isActive = false,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            icon = binding.navOverviewIcon,
            label = binding.navOverviewLabel
        )
        updateNavItem(
            isActive = activeNav == ConnectedScreen.TASKS,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            icon = binding.navTasksIcon,
            label = binding.navTasksLabel
        )
        updateNavItem(
            isActive = activeNav == ConnectedScreen.DETAIL,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            icon = binding.navDetailIcon,
            label = binding.navDetailLabel
        )
        updateNavItem(
            isActive = activeNav == ConnectedScreen.PROFILE,
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            icon = binding.navProfileIcon,
            label = binding.navProfileLabel
        )
    }

    private fun updateNavItem(
        isActive: Boolean,
        activeColor: Int,
        inactiveColor: Int,
        icon: ImageView,
        label: TextView
    ) {
        val color = if (isActive) activeColor else inactiveColor
        icon.setColorFilter(color)
        label.setTextColor(color)
    }

    private fun exportDiagnosticsBundle() {
        if (diagnosticsExportJob?.isActive == true) {
            DebugTraceLogger.log("diagnostics-export", "diagnosticsExportDuplicateIgnored")
            Toast.makeText(
                this,
                R.string.profile_export_diagnostics_already_running,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        diagnosticsExportJob = lifecycleScope.launch {
            try {
                DebugTraceLogger.log("diagnostics-export",
                    "start screen=${currentScreen.name} version=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}"
                )
                Toast.makeText(
                    this@MainActivity,
                    R.string.profile_export_diagnostics_preparing,
                    Toast.LENGTH_SHORT
                ).show()
                when (val result = fetchGatewayDiagnosticsForBundle {
                    viewModel.fetchGatewayDiagnosticsJson()
                }) {
                    is GatewayDiagnosticsFetchResult.Success -> {
                        DebugTraceLogger.log("diagnostics-export",
                            "gatewayDiagnosticsFetched bytes=${result.json.toByteArray().size}"
                        )
                        createAndSaveDiagnosticBundle(gatewayDiagnosticsJson = result.json)
                    }
                    is GatewayDiagnosticsFetchResult.Failure -> {
                        val errorSummary = diagnosticErrorSummary(result.error)
                        DebugTraceLogger.log("diagnostics-export",
                            "gatewayDiagnosticsFailed error=$errorSummary"
                        )
                        createAndSaveDiagnosticBundle(
                            gatewayDiagnosticsJson = gatewayDiagnosticsFailureJson(result.error),
                            gatewayDiagnosticsError = errorSummary
                        )
                    }
                }
            } catch (error: CancellationException) {
                DebugTraceLogger.log("diagnostics-export",
                    "cancelled error=${diagnosticErrorSummary(error)}"
                )
                throw error
            } finally {
                diagnosticsExportJob = null
            }
        }
    }

    private suspend fun createAndSaveDiagnosticBundle(
        gatewayDiagnosticsJson: String,
        gatewayDiagnosticsError: String? = null
    ) {
        try {
            DebugTraceLogger.log("diagnostics-export", "creatingBundle")
            val zipFile = withContext(Dispatchers.IO) {
                DiagnosticBundleWriter.create(
                    outputDir = File(cacheDir, "diagnostics"),
                    input = DiagnosticBundleInput(
                        appVersionName = BuildConfig.VERSION_NAME,
                        appVersionCode = BuildConfig.VERSION_CODE,
                        currentScreen = currentScreen.name,
                        state = viewModel.state.value,
                        debugTraceLines = DebugTraceLogger.snapshot(),
                        gatewayDiagnosticsJson = gatewayDiagnosticsJson,
                        gatewayDiagnosticsError = gatewayDiagnosticsError,
                        alertMessageEnabledThreadIds = alertMessageEnabledThreadIds.toSet(),
                        alertCompletionEnabledThreadIds = alertCompletionEnabledThreadIds.toSet(),
                        generatedAtMillis = System.currentTimeMillis()
                    )
                )
            }
            DebugTraceLogger.log("diagnostics-export",
                "bundleCreated file=${zipFile.name} bytes=${zipFile.length()}"
            )
            val savedToDownloads = withContext(Dispatchers.IO) {
                saveDiagnosticBundleToDownloads(zipFile)
            }
            if (savedToDownloads) {
                DebugTraceLogger.log("diagnostics-export",
                    "savedToDownloads file=${zipFile.name}"
                )
                Toast.makeText(
                    this@MainActivity,
                    R.string.profile_export_diagnostics_saved_to_downloads,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                DebugTraceLogger.log("diagnostics-export",
                    "saveToDownloadsFailed file=${zipFile.name}"
                )
                Toast.makeText(
                    this@MainActivity,
                    R.string.profile_export_diagnostics_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DebugTraceLogger.log("diagnostics-export",
                "bundleException error=${diagnosticErrorSummary(error)}"
            )
            Toast.makeText(
                this@MainActivity,
                R.string.profile_export_diagnostics_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun gatewayDiagnosticsFailureJson(error: Throwable): String {
        return JSONObject()
            .put("available", false)
            .put("error", diagnosticErrorSummary(error))
            .toString()
    }

    private fun saveDiagnosticBundleToDownloads(zipFile: File): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, zipFile.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, DIAGNOSTIC_ZIP_MIME_TYPE)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "$DOWNLOADS_RELATIVE_DIR/$DOWNLOADS_APP_SUBDIRECTORY"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return@runCatching false.also {
                    DebugTraceLogger.log("diagnostics-export",
                        "saveToDownloadsInsertFailed file=${zipFile.name}"
                    )
                }
                runCatching {
                    val outputStream = contentResolver.openOutputStream(uri)
                        ?: throw IOException("diagnostic_download_output_failed")
                    outputStream.use { output ->
                        zipFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    true
                }.getOrElse { error ->
                    contentResolver.delete(uri, null, null)
                    throw error
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    DOWNLOADS_RELATIVE_DIR
                )
                val appDownloadsDir = File(downloadsDir, DOWNLOADS_APP_SUBDIRECTORY)
                appDownloadsDir.mkdirs()
                zipFile.copyTo(File(appDownloadsDir, zipFile.name), overwrite = true)
                true
            }
        }.getOrElse { error ->
            DebugTraceLogger.log("diagnostics-export",
                "saveToDownloadsException file=${zipFile.name} error=${diagnosticErrorSummary(error)}"
            )
            false
        }
    }

    private fun diagnosticErrorSummary(error: Throwable): String {
        val type = error::class.java.simpleName.ifBlank { "Throwable" }
        val message = error.message?.takeIf { it.isNotBlank() } ?: "no message"
        return "$type: $message"
    }

    private fun openSystemNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        }
        startActivity(intent)
    }

    private fun handleVersionUpdateClick() {
        val state = viewModel.state.value
        val latestApkInfo = state.latestApkInfo
        when {
            state.isCheckingLatestApk -> {
                Toast.makeText(this, R.string.profile_update_checking, Toast.LENGTH_SHORT).show()
            }
            latestApkInfo?.isNewerThan(BuildConfig.VERSION_CODE) == true -> {
                downloadLatestApk()
            }
            latestApkInfo != null -> {
                checkLatestApkManually()
            }
            else -> {
                checkLatestApkManually()
            }
        }
    }

    private fun checkLatestApkManually() {
        Toast.makeText(this, R.string.profile_update_checking, Toast.LENGTH_SHORT).show()
        viewModel.checkLatestApk(force = true) { latestApkInfo ->
            val message = if (latestApkInfo?.isNewerThan(BuildConfig.VERSION_CODE) == true) {
                when {
                    !latestApkInfo.versionName.isNullOrBlank() -> {
                        getString(R.string.profile_update_new_version, latestApkInfo.versionName)
                    }
                    latestApkInfo.versionCode != null -> {
                        getString(R.string.profile_update_new_version_code, latestApkInfo.versionCode)
                    }
                    else -> getString(R.string.profile_update_title)
                }
            } else {
                getString(R.string.profile_update_already_latest)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadLatestApk() {
        val state = viewModel.state.value
        val config = state.config ?: GatewayPreferences.loadConfig(this)
        val latestApkInfo = state.latestApkInfo
        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as? DownloadManager
        if (config == null || downloadManager == null) {
            Toast.makeText(this, R.string.download_apk_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val downloadUri = Uri.parse("${config.url.trimEnd('/')}/downloads/latest.apk")
            .buildUpon()
            .appendQueryParameter(
                "v",
                latestApkInfo?.versionCode?.toString() ?: BuildConfig.VERSION_CODE.toString()
            )
            .appendQueryParameter("t", System.currentTimeMillis().toString())
            .build()

        val request = DownloadManager.Request(
            downloadUri
        ).apply {
            setTitle(getString(R.string.profile_update_title))
            setDescription(getString(R.string.download_apk_started))
            setMimeType(APK_MIME_TYPE)
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            addRequestHeader("Authorization", "Bearer ${config.token}")
            setDestinationInExternalPublicDir(
                DOWNLOADS_RELATIVE_DIR,
                "$DOWNLOADS_APP_SUBDIRECTORY/${latestApkDownloadFileName(latestApkInfo)}"
            )
        }

        runCatching {
            downloadManager.enqueue(request)
        }.onSuccess {
            Toast.makeText(this, R.string.download_apk_started, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.download_apk_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun latestApkDownloadFileName(latestApkInfo: LatestApkInfo?): String {
        val versionTag = latestApkInfo?.versionName?.takeIf { it.isNotBlank() }
            ?: latestApkInfo?.versionCode?.let { "code-$it" }
            ?: "latest"
        val safeVersionTag = versionTag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "codex-mobile-control-$safeVersionTag.apk"
    }

    private fun performLogout() {
        RealtimeForegroundService.setEnabled(this, false)
        AlertPollingScheduler.cancel(this)
        viewModel.logout()
        currentScreen = ConnectedScreen.TASKS
        bindLoginInputs(null)
        showLoginState()
    }

    private fun appVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "0.1.0" }
    }

    private fun bindLoginInputs(config: GatewayConfig?) {
        binding.gatewayUrlInput.setText(config?.url ?: GatewayDefaults.BUILT_IN_DISPLAY_URL)
        binding.tokenInput.setText(config?.token.orEmpty())
    }

    private fun intentConfig(): GatewayConfig? {
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token.isNullOrBlank()) return null
        val gatewayUrl = intent.getStringExtra(EXTRA_GATEWAY_URL)
            ?.takeIf { it.isNotBlank() }
            ?: GatewayPreferences.loadConfig(this)?.url
            ?: GatewayDefaults.BUILT_IN_URL
        return GatewayDefaults.configFor(gatewayUrl, token)
    }

    private fun displayErrorMessage(errorMessage: String): String {
        return when (errorMessage) {
            "token_invalid" -> getString(R.string.error_token_invalid)
            "gateway_unreachable" -> getString(R.string.error_gateway_unreachable)
            "send_disabled" -> getString(R.string.error_send_disabled)
            "desktop_send_confirmation_timeout" -> {
                getString(R.string.error_desktop_send_confirmation_timeout)
            }
            "desktop_window_not_found" -> getString(R.string.error_desktop_window_not_found)
            "desktop_not_running" -> getString(R.string.error_desktop_not_running)
            "desktop_focus_failed" -> getString(R.string.error_desktop_focus_failed)
            "clipboard_unavailable" -> getString(R.string.error_clipboard_unavailable)
            "composer_input_failed" -> getString(R.string.error_composer_input_failed)
            "file_missing" -> getString(R.string.error_file_missing)
            else -> errorMessage
        }
    }

    private fun View.diagnosticBounds(): String {
        val rect = Rect()
        val visible = getGlobalVisibleRect(rect)
        return "${visibilityName()}:visible=$visible[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
    }

    private fun View.visibilityName(): String {
        return when (visibility) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "invisible"
            View.GONE -> "gone"
            else -> "visibility-$visibility"
        }
    }

    private fun String?.logId(): String {
        val value = this?.takeIf { it.isNotBlank() } ?: return "null"
        return if (value.length <= 12) value else "${value.take(6)}...${value.takeLast(6)}"
    }

    private fun String.logCompact(maxChars: Int = 80): String {
        val compact = replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= maxChars) compact else compact.take(maxChars) + "..."
    }

    companion object {
        private val FILE_ADD_PATTERN = Regex("""\+\d+""")
        private val FILE_REMOVE_PATTERN = Regex("""-\d+""")
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val DIAGNOSTIC_ZIP_MIME_TYPE = "application/zip"
        private val DOWNLOADS_RELATIVE_DIR = Environment.DIRECTORY_DOWNLOADS
        private const val DOWNLOADS_APP_SUBDIRECTORY = "CodexMobile"
        private const val BUTTON_FEEDBACK_DURATION_MS = 240L
        private const val DETAIL_SOCKET_PULSE_DURATION_MS = 900L
        private const val SCREEN_TRANSITION_DURATION_MS = 320L
        private const val CONTENT_ENTER_DURATION_MS = 320L
        private const val MIN_MOTION_OVERLAY_VISIBLE_DURATION_MS = 420L
        private const val DETAIL_JUMP_TO_BOTTOM_THRESHOLD_DP = 64
        private const val DETAIL_BOTTOM_STICKY_THRESHOLD_DP = 12
        private const val KEYBOARD_BOTTOM_SCROLL_DELAY_MS = 120L
        private const val KEYBOARD_BOTTOM_SCROLL_FINAL_DELAY_MS = 320L
        private const val COMPOSER_KEYBOARD_OPEN_GRACE_MS = 800L
        private const val CONVERSATION_MIN_TEXT_EMS = 10
        private const val CONVERSATION_MIN_TEXT_WIDTH_DP = 128
        private const val CONVERSATION_TEXT_WIDTH_BUFFER_DP = 6
        private const val DETAIL_PULL_TO_LOAD_THRESHOLD_DP = 18
        private const val DETAIL_SCROLL_DIAGNOSTIC_MESSAGE_MAX_CHARS = 900
        private const val DETAIL_SCROLL_DIAGNOSTIC_MIN_DELTA_PX = 2
        private const val DETAIL_SCROLL_DIAGNOSTIC_THROTTLE_MS = 180L
        private const val KEYBOARD_VISIBILITY_THRESHOLD_DP = 120
        private const val DETAIL_ALERT_REFRESH_INTERVAL_MS = 10_000L
        private const val DETAIL_RUNTIME_SUBTITLE_REFRESH_INTERVAL_MS = 1_000L
        private const val PROFILE_TYPING_SLIDER_MIN_MAX_VALUE = 100
        private const val TASK_LIST_DIAGNOSTIC_SAMPLE_SIZE = 6
        private const val TASK_LIST_DIAGNOSTIC_MESSAGE_MAX_CHARS = 900
        private const val TASK_LIST_DIAGNOSTIC_MIN_SCROLL_DELTA_PX = 2
        private const val TASK_LIST_DIAGNOSTIC_SCROLL_THROTTLE_MS = 180L
        private const val TASK_LIST_TOP_RESTORE_DELAY_MS = 80L
        const val EXTRA_GATEWAY_URL = "gateway_url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_THREAD_ID = "thread_id"
    }
}

private fun android.content.ContentResolver.queryDisplayName(uri: Uri): String? {
    val cursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            return it.getString(0)
        }
    }
    return uri.lastPathSegment
}
