package com.codex.mobilecontrol

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.codex.mobilecontrol.data.GatewaySessionStore
import com.codex.mobilecontrol.data.ThreadRepository
import com.codex.mobilecontrol.model.GatewayStreamEvent
import com.codex.mobilecontrol.model.GatewayConnectionMode
import com.codex.mobilecontrol.model.ImageUploadSource
import com.codex.mobilecontrol.model.LoginResponse
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.SendMessageResponse
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import com.codex.mobilecontrol.network.GatewayApi
import com.codex.mobilecontrol.network.GatewaySseClient
import com.codex.mobilecontrol.network.StreamConnectionState
import com.codex.mobilecontrol.ui.AppGraph
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    private val defaultFactory = AppGraph.repositoryFactory

    @After
    fun tearDown() {
        finishMainActivityIfPresent()
        AppGraph.repositoryFactory = defaultFactory
        clearSavedConfig()
    }

    @Test
    fun activityInflatesNativeShellIds() {
        clearSavedConfig()

        launchMainActivity()
        withActiveMainActivity { activity ->
            assertNotNull(activity.findViewById(R.id.loginCard))
            assertNotNull(activity.findViewById(R.id.threadDrawerRecycler))
            assertNotNull(activity.findViewById(R.id.messageInput))
        }
    }

    @Test
    fun disconnectedStateUsesDedicatedLoginHero() {
        clearSavedConfig()

        launchMainActivity()
        withActiveMainActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.topAppBar).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.connectionBanner).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginHeroTitle).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loginBrandIcon).visibility)
            assertEquals("登录", activity.findViewById<TextView>(R.id.connectButton).text.toString())
            assertEquals(
                "使用 Access Token 访问您的 Codex 实例",
                activity.findViewById<TextView>(R.id.helperText).text.toString()
            )
        }
    }

    @Test
    fun loginSuccessShowsTaskDashboard() {
        AppGraph.repositoryFactory = { _ ->
            ThreadRepository(
                api = FakeGatewayApi(),
                sseClient = FakeGatewaySseClient(),
                sessionStore = TestSessionStore(
                    savedConfig = null,
                    lastOpenedThreadId = "thread-2"
                ),
                dispatcher = Dispatchers.Unconfined
            )
        }

        launchMainActivity()
        loginThroughUi()
        waitForTaskDashboard(threadCount = 2)
        withActiveMainActivity { activity ->
            assertEquals(View.GONE, activity.findViewById<View>(R.id.loginCard).visibility)
            assertEquals("Second", threadTitleAtPosition(activity, 0))
        }
    }

    @Test
    fun notificationIntentOpensRequestedThreadDetail() {
        AppGraph.repositoryFactory = { _ ->
            ThreadRepository(
                api = FakeGatewayApi(),
                sseClient = FakeGatewaySseClient(),
                sessionStore = TestSessionStore(
                    savedConfig = null,
                    lastOpenedThreadId = "thread-1"
                ),
                dispatcher = Dispatchers.Unconfined
            )
        }

        launchMainActivity()
        loginThroughUi()
        waitForTaskDashboard(threadCount = 2)
        withActiveMainActivity { activity ->
            activity.startActivity(
                Intent(activity, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_TOKEN, "token")
                    putExtra(MainActivity.EXTRA_THREAD_ID, "thread-2")
                }
            )
        }
        waitForActivityCondition("thread-2 detail ready") { activity ->
            val detailPage = activity.findViewById<View>(R.id.detailPage)
            val detailTitle = detailPage.findViewById<TextView>(R.id.detailTopTitle)
            detailPage.visibility == View.VISIBLE && detailTitle.text.toString() == "Second"
        }
    }

    @Test
    fun tappingTaskCardOpensDetailScreen() {
        AppGraph.repositoryFactory = { _ ->
            ThreadRepository(
                api = FakeGatewayApi(),
                sseClient = FakeGatewaySseClient(),
                sessionStore = TestSessionStore(
                    savedConfig = null,
                    lastOpenedThreadId = "thread-2"
                ),
                dispatcher = Dispatchers.Unconfined
            )
        }

        launchMainActivity()
        loginThroughUi()
        waitForTaskDashboard(threadCount = 2)
        clickThreadAtPosition(0)
        waitForActivityCondition("first thread detail ready") { activity ->
            val detailPage = activity.findViewById<View>(R.id.detailPage)
            val detailTitle = detailPage.findViewById<TextView>(R.id.detailTopTitle)
            detailPage.visibility == View.VISIBLE && detailTitle.text.toString() == "First"
        }
    }

    @Test
    fun sendButtonPostsTextThroughRepositoryFlow() {
        val api = FakeGatewayApi()
        AppGraph.repositoryFactory = { _ ->
            ThreadRepository(
                api = api,
                sseClient = FakeGatewaySseClient(),
                sessionStore = TestSessionStore(
                    savedConfig = null,
                    lastOpenedThreadId = "thread-1"
                ),
                dispatcher = Dispatchers.Unconfined
            )
        }

        launchMainActivity()
        loginThroughUi()
        waitForTaskDashboard(threadCount = 2)
        clickThreadAtPosition(0)
        waitForActivityCondition("composer ready") { activity ->
            val detailPage = activity.findViewById<View>(R.id.detailPage)
            val detailTitle = detailPage.findViewById<TextView>(R.id.detailTopTitle)
            val messageInput = activity.findViewById<View>(R.id.messageInput)
            val sendButton = activity.findViewById<View>(R.id.sendButton)
            detailPage.visibility == View.VISIBLE &&
                detailTitle.text.toString() == "First" &&
                messageInput.width > 0 &&
                sendButton.isEnabled
        }
        withActiveMainActivity { activity ->
            activity.findViewById<EditText>(R.id.messageInput).setText("hello from phone")
            activity.findViewById<View>(R.id.sendButton).performClick()
        }
        waitForCondition("message send recorded") {
            api.lastSentText == "hello from phone"
        }
        assertEquals("hello from phone", api.lastSentText)
    }

    @Test
    fun sendButtonStaysDisabledWhenThreadCannotSend() {
        val api = object : GatewayApi {
            private val thread = ThreadListItem(
                "thread-1",
                "First",
                "D:/repo-a",
                MobileThreadStatus.WAITING_INPUT,
                "2026-04-23T00:00:00Z",
                "waiting input",
                true
            )

            override suspend fun login(config: GatewayConfig) = LoginResponse(true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ) = ThreadDetail(
                thread = thread,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = false,
                sendDisabledReason = "当前线程未处于可发送状态"
            )

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("send should stay disabled")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("send should stay disabled")
        }

        AppGraph.repositoryFactory = { _ ->
            ThreadRepository(
                api = api,
                sseClient = FakeGatewaySseClient(),
                sessionStore = TestSessionStore(
                    savedConfig = null,
                    lastOpenedThreadId = "thread-1"
                ),
                dispatcher = Dispatchers.Unconfined
            )
        }

        launchMainActivity()
        loginThroughUi()
        waitForTaskDashboard(threadCount = 1)
        clickThreadAtPosition(0)
        waitForActivityCondition("disabled composer state") { activity ->
            val detailPage = activity.findViewById<View>(R.id.detailPage)
            val detailTitle = detailPage.findViewById<TextView>(R.id.detailTopTitle)
            val sendButton = activity.findViewById<View>(R.id.sendButton)
            detailPage.visibility == View.VISIBLE &&
                detailTitle.text.toString() == "First" &&
                !sendButton.isEnabled
        }
        withActiveMainActivity { activity ->
            assertEquals(false, activity.findViewById<View>(R.id.sendButton).isEnabled)
            assertEquals(
                "当前线程未处于可发送状态",
                activity.findViewById<TextView>(R.id.composerStatus).text.toString()
            )
        }
    }

    @Test
    fun selectingImageShowsPendingPreviewCard() {
        val api = FakeGatewayApi()
        AppGraph.repositoryFactory = { _ ->
            ThreadRepository(
                api = api,
                sseClient = FakeGatewaySseClient(),
                sessionStore = TestSessionStore(savedConfig = null, lastOpenedThreadId = "thread-1"),
                dispatcher = Dispatchers.Unconfined
            )
        }

        launchMainActivity()
        loginThroughUi()
        waitForTaskDashboard(threadCount = 2)
        clickThreadAtPosition(0)

        withActiveMainActivity { activity ->
            activity.runOnUiThread {
                activity.onImagePickedForTest(
                    previewUri = "content://demo/image",
                    fileName = "demo.png",
                    mimeType = "image/png"
                )
            }
        }

        waitForActivityCondition("pending image preview visible") { activity ->
            activity.findViewById<View>(R.id.pendingImageCard).visibility == View.VISIBLE &&
                viewTreeContainsText(activity.findViewById(R.id.pendingImageList), "demo.png")
        }
    }

    @Test
    fun imageMessagesRenderThumbnailCard() {
        val api = FakeGatewayApi().apply {
            detailOverride = detailWithImageMessage()
        }
        AppGraph.repositoryFactory = { _ ->
            ThreadRepository(
                api = api,
                sseClient = FakeGatewaySseClient(),
                sessionStore = TestSessionStore(savedConfig = null, lastOpenedThreadId = "thread-1"),
                dispatcher = Dispatchers.Unconfined
            )
        }

        launchMainActivity()
        loginThroughUi()
        waitForTaskDashboard(threadCount = 2)
        clickThreadAtPosition(0)

        waitForActivityCondition("image message rendered") { activity ->
            viewTreeContainsImage(activity.findViewById(R.id.detailConversationList))
        }
    }

    private fun clearSavedConfig() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("codex_mobile_control", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun loginThroughUi(token: String = "token") {
        withActiveMainActivity { activity ->
            activity.findViewById<EditText>(R.id.tokenInput).setText(token)
            activity.findViewById<View>(R.id.connectButton).performClick()
        }
    }

    private fun clickThreadAtPosition(position: Int) {
        withActiveMainActivity { activity ->
            val recyclerView = activity.findViewById<RecyclerView>(R.id.threadDrawerRecycler)
            val threadRow = recyclerView.findViewHolderForAdapterPosition(position)
                ?.itemView
                ?.findViewById<View>(R.id.threadCompactRow)
                ?: recyclerView.getChildAt(position)?.findViewById(R.id.threadCompactRow)
            requireNotNull(threadRow) { "Thread row at position $position was not laid out" }
            threadRow.performClick()
        }
    }

    private fun threadTitleAtPosition(activity: MainActivity, position: Int): String? {
        val recyclerView = activity.findViewById<RecyclerView>(R.id.threadDrawerRecycler)
        val title = recyclerView.findViewHolderForAdapterPosition(position)
            ?.itemView
            ?.findViewById<TextView>(R.id.threadCompactTitle)
            ?: recyclerView.getChildAt(position)?.findViewById(R.id.threadCompactTitle)
        return title?.text?.toString()
    }

    private fun viewTreeContainsText(root: View, expected: String): Boolean {
        if (root is TextView && root.text.toString() == expected) {
            return true
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                if (viewTreeContainsText(root.getChildAt(index), expected)) {
                    return true
                }
            }
        }
        return false
    }

    private fun viewTreeContainsImage(root: View): Boolean {
        if (root is ImageView) {
            return true
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                if (viewTreeContainsImage(root.getChildAt(index))) {
                    return true
                }
            }
        }
        return false
    }

    private fun launchMainActivity(
        token: String? = null,
        threadId: String? = null
    ) {
        val command = if (token == null && threadId == null) {
            "monkey -p com.codex.mobilecontrol -c android.intent.category.LAUNCHER 1"
        } else {
            buildString {
                append("am start -W -n com.codex.mobilecontrol/.MainActivity")
                if (token != null) {
                    append(" --es ")
                    append(MainActivity.EXTRA_TOKEN)
                    append(" ")
                    append(shellEscape(token))
                }
                if (threadId != null) {
                    append(" --es ")
                    append(MainActivity.EXTRA_THREAD_ID)
                    append(" ")
                    append(shellEscape(threadId))
                }
            }
        }
        executeShellCommand(command)
        if (dismissMiuiStartPromptIfPresent()) {
            SystemClock.sleep(400)
            executeShellCommand(command)
        }
        waitForMainActivity()
    }

    private fun withActiveMainActivity(block: (MainActivity) -> Unit) {
        val activity = waitForMainActivity()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            block(activity)
        }
    }

    private fun waitForActivityCondition(
        description: String,
        timeoutMs: Long = 5_000,
        predicate: (MainActivity) -> Boolean
    ): MainActivity {
        var matchedActivity: MainActivity? = null
        waitForCondition(description, timeoutMs) {
            val activities = currentMainActivities()
            if (activities.isEmpty()) {
                return@waitForCondition false
            }
            val matched = booleanArrayOf(false)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                matchedActivity = activities.firstOrNull { activity -> predicate(activity) }
                matched[0] = matchedActivity != null
            }
            matched[0]
        }
        return matchedActivity ?: waitForMainActivity()
    }

    private fun waitForTaskDashboard(threadCount: Int): MainActivity {
        return waitForActivityCondition("task dashboard ready") { activity ->
            val recyclerView = activity.findViewById<RecyclerView>(R.id.threadDrawerRecycler)
            val adapterCount = recyclerView.adapter?.itemCount ?: 0
            activity.findViewById<View>(R.id.loginCard).visibility == View.GONE &&
                activity.findViewById<View>(R.id.taskPage).visibility == View.VISIBLE &&
                adapterCount >= threadCount
        }
    }

    private fun waitForCondition(
        description: String,
        timeoutMs: Long = 5_000,
        predicate: () -> Boolean
    ): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMs

        while (SystemClock.uptimeMillis() < deadline) {
            dismissMiuiStartPromptIfPresent(timeoutMs = 250)
            if (predicate()) {
                return true
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(120)
        }

        throw AssertionError("$description did not complete within ${timeoutMs}ms")
    }

    private fun finishMainActivityIfPresent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activities = currentMainActivities()
        if (activities.isNotEmpty()) {
            instrumentation.runOnMainSync {
                activities.forEach { activity ->
                    activity.finish()
                }
            }
            instrumentation.waitForIdleSync()
            waitForCondition("main activity cleanup") {
                currentMainActivities().isEmpty()
            }
        }
    }

    private fun waitForMainActivity(timeoutMs: Long = 5_000): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMs

        while (SystemClock.uptimeMillis() < deadline) {
            dismissMiuiStartPromptIfPresent(timeoutMs = 250)
            val activity = currentMainActivities().firstOrNull()
            if (activity != null) {
                return activity
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(120)
        }

        throw AssertionError("MainActivity did not become available within ${timeoutMs}ms")
    }

    private fun currentMainActivities(): List<MainActivity> {
        val activities = linkedSetOf<MainActivity>()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Stage.values()
                .filter { it != Stage.DESTROYED }
                .forEach { stage ->
                    activities += monitor.getActivitiesInStage(stage)
                        .filterIsInstance<MainActivity>()
                }
        }
        return activities.toList()
    }

    private fun executeShellCommand(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return readShellOutput(descriptor)
    }

    private fun readShellOutput(descriptor: ParcelFileDescriptor): String {
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                return reader.readText()
            }
        }
    }

    private fun dismissMiuiStartPromptIfPresent(timeoutMs: Long = 1_500): Boolean {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val promptVisible = device.wait(Until.hasObject(By.textContains("启动应用")), timeoutMs) ||
            device.currentPackageName == "com.miui.securitycenter"
        if (!promptVisible) {
            return false
        }

        val allowButton = device.wait(Until.findObject(By.text("允许")), 300)
            ?: device.wait(Until.findObject(By.textContains("允许")), 200)
        if (allowButton != null) {
            allowButton.click()
        } else {
            val allowX = (device.displayWidth * 0.71f).toInt()
            val allowY = (device.displayHeight * 0.91f).toInt()
            device.click(allowX, allowY)
        }
        device.waitForIdle()
        SystemClock.sleep(300)
        return true
    }

    private fun shellEscape(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}

private class TestSessionStore(
    private val savedConfig: GatewayConfig?,
    private val lastOpenedThreadId: String?
) : GatewaySessionStore {
    override fun loadConfig(): GatewayConfig? = savedConfig

    override fun saveConfig(config: GatewayConfig) = Unit

    override fun loadLastOpenedThreadId(): String? = lastOpenedThreadId

    override fun saveLastOpenedThreadId(threadId: String?) = Unit

    override fun clearSession() = Unit

    override fun loadConnectionMode(): GatewayConnectionMode = GatewayConnectionMode.SSE

    override fun saveConnectionMode(mode: GatewayConnectionMode) = Unit

    override fun loadCachedThreads(): List<ThreadListItem> = emptyList()

    override fun saveCachedThreads(threads: List<ThreadListItem>) = Unit

    override fun loadCachedThreadDetail(threadId: String): ThreadDetail? = null

    override fun saveCachedThreadDetail(detail: ThreadDetail) = Unit

    override fun loadQueuedTextMessages(): List<QueuedTextMessage> = emptyList()

    override fun saveQueuedTextMessages(messages: List<QueuedTextMessage>) = Unit

    override fun setThreadCachePersistenceEnabled(enabled: Boolean) = Unit

    override fun loadLastRealtimeEventId(): String? = null

    override fun saveLastRealtimeEventId(eventId: String?) = Unit

    override fun loadKnownNotificationIds(): Set<String> = emptySet()

    override fun saveKnownNotificationIds(ids: Set<String>) = Unit

    override fun loadAlertEnabledThreadIds(): Set<String> = emptySet()

    override fun clearThreadCache() = Unit
}

private class FakeGatewayApi : GatewayApi {
    var lastSentText: String? = null
    var detailOverride: ThreadDetail? = null

    private val threads = listOf(
        ThreadListItem(
            "thread-1",
            "First",
            "D:/repo-a",
            MobileThreadStatus.IDLE,
            "2026-04-23T00:00:00Z",
            "idle",
            false
        ),
        ThreadListItem(
            "thread-2",
            "Second",
            "D:/repo-b",
            MobileThreadStatus.WAITING_INPUT,
            "2026-04-23T00:01:00Z",
            "waiting input",
            true
        )
    )

    override suspend fun login(config: GatewayConfig): LoginResponse = LoginResponse(true)

    override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> = threads

    override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
        detailOverride?.let { return it }
        val thread = threads.first { it.threadId == threadId }
        return ThreadDetail(
            thread = thread,
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "m1",
                    threadId = threadId,
                    role = ThreadMessageRole.USER,
                    kind = "text",
                    text = "continue",
                    timestamp = "2026-04-23T00:00:00Z"
                )
            ),
            recentEvents = listOf(
                ThreadEvent(
                    "e1",
                    threadId,
                    ThreadEventKind.TURN_STARTED,
                    null,
                    "Worker started",
                    "2026-04-23T00:00:01Z"
                )
            ),
            sendAvailable = true,
            sendDisabledReason = null
        )
    }

    override suspend fun sendMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String
    ): SendMessageResponse {
        lastSentText = text
        return SendMessageResponse(
            accepted = true,
            threadId = threadId,
            clientMessageId = clientMessageId,
            sendPath = "desktop_bridge",
            confirmation = "observed"
        )
    }

    override suspend fun sendImageMessage(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        image: ImageUploadSource
    ): SendMessageResponse {
        lastSentText = text
        return SendMessageResponse(
            accepted = true,
            threadId = threadId,
            clientMessageId = clientMessageId,
            sendPath = "desktop_bridge",
            confirmation = "observed"
        )
    }
}

private fun detailWithImageMessage(): ThreadDetail {
    return ThreadDetail(
        thread = ThreadListItem(
            "thread-1",
            "First",
            "D:/repo-a",
            MobileThreadStatus.IDLE,
            "2026-04-23T00:00:00Z",
            "idle",
            false
        ),
        recentMessages = listOf(
            ThreadMessage(
                messageId = "img-1",
                threadId = "thread-1",
                role = ThreadMessageRole.USER,
                kind = "image",
                text = "请分析这张图",
                imageUrl = "https://gateway.example.test/api/uploads/thread-1/demo.png",
                thumbnailUrl = "https://gateway.example.test/api/uploads/thread-1/demo.png",
                fileName = "demo.png",
                timestamp = "2026-04-23T00:00:00Z"
            )
        ),
        recentEvents = emptyList(),
        sendAvailable = true,
        sendDisabledReason = null
    )
}

private class FakeGatewaySseClient : GatewaySseClient {
    override fun connect(
        config: GatewayConfig,
        onEvent: (GatewayStreamEvent) -> Unit,
        onStateChanged: (StreamConnectionState) -> Unit
    ): AutoCloseable {
        onStateChanged(StreamConnectionState.OPEN)
        return AutoCloseable { }
    }
}
