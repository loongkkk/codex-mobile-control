package com.codex.mobilecontrol.data

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.model.FileUploadSource
import com.codex.mobilecontrol.model.GatewayConnectionMode
import com.codex.mobilecontrol.model.GatewayStreamEvent
import com.codex.mobilecontrol.model.ImageUploadSource
import com.codex.mobilecontrol.model.LatestApkInfo
import com.codex.mobilecontrol.model.PendingFileDraft
import com.codex.mobilecontrol.model.PendingImageDraft
import com.codex.mobilecontrol.model.LoginResponse
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.QueuedTextMessageStatus
import com.codex.mobilecontrol.model.SendMessageResponse
import com.codex.mobilecontrol.model.DetailRefreshStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import com.codex.mobilecontrol.model.ThreadMessageSendState
import com.codex.mobilecontrol.model.ThreadMessagesPage
import com.codex.mobilecontrol.network.GatewayApi
import com.codex.mobilecontrol.network.GatewayRealtimeClient
import com.codex.mobilecontrol.network.GatewayRealtimeConnection
import com.codex.mobilecontrol.network.GatewayRealtimeEvent
import com.codex.mobilecontrol.network.GatewaySseClient
import com.codex.mobilecontrol.network.SocketLatencyTestResult
import com.codex.mobilecontrol.network.StreamConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadRepositoryTest {
    private val config = GatewayConfig("http://gateway", "token")
    private val thread1 = ThreadListItem(
        threadId = "thread-1",
        title = "First",
        cwd = "D:/repo-a",
        status = MobileThreadStatus.IDLE,
        updatedAt = "2026-04-23T00:00:00Z",
        progressSummary = "idle",
        needsAttention = false
    )
    private val thread2 = ThreadListItem(
        threadId = "thread-2",
        title = "Second",
        cwd = "D:/repo-b",
        status = MobileThreadStatus.WAITING_INPUT,
        updatedAt = "2026-04-23T00:01:00Z",
        progressSummary = "waiting input",
        needsAttention = true
    )

    @Test
    fun `connect selects saved thread when request is absent`() = runTest {
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1, thread2)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                lastOpenedThreadId = "thread-2"
            ),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = null)
        val state = repository.state.first { it.isConnected }

        assertEquals("thread-2", state.selectedThreadId)
        assertEquals("thread-2", state.detail?.thread?.threadId)
    }

    @Test
    fun `initial cached detail removes optimistic echo when confirmed message is present`() = runTest {
        val confirmedMessage = ThreadMessage(
            messageId = "event:23416:2026-05-01T15:16:38.183Z:text",
            threadId = "thread-1",
            role = ThreadMessageRole.USER,
            kind = "text",
            text = "可以按你说的来，再加上多次取平均值",
            timestamp = "2026-05-01T15:16:38.183Z"
        )
        val staleOptimisticMessage = confirmedMessage.copy(
            messageId = "client-stale-echo",
            timestamp = "2026-05-01T15:16:37.500Z",
            sendState = null
        )
        val cachedDetail = ThreadDetail(
            thread = thread1,
            recentMessages = listOf(staleOptimisticMessage, confirmedMessage),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                lastOpenedThreadId = "thread-1",
                cachedThreads = listOf(thread1),
                cachedDetails = mutableMapOf("thread-1" to cachedDetail)
            ),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val messages = repository.state.value.detail!!.recentMessages

        assertEquals(listOf(confirmedMessage.messageId), messages.map { it.messageId })
    }

    @Test
    fun `socket status event updates selected thread without http refresh`() = runTest {
        val runningThread = thread1.copy(
            status = MobileThreadStatus.RUNNING,
            progressSummary = "正在处理新的请求"
        )
        val api = FakeGatewayApi(listOf(runningThread))
        val realtimeClient = FakeRealtimeClient()
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            connectionMode = GatewayConnectionMode.SOCKET
        )
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.ThreadStatusChanged(
                eventId = "event-completed",
                threadId = "thread-1",
                status = MobileThreadStatus.COMPLETED,
                progressSummary = "本轮已完成",
                needsAttention = false,
                timestamp = "2026-04-30T01:00:00.000Z"
            )
        )
        advanceUntilIdle()

        assertEquals(MobileThreadStatus.COMPLETED, repository.state.value.detail!!.thread.status)
        assertEquals("本轮已完成", repository.state.value.detail!!.thread.progressSummary)
        assertEquals("event-completed", store.lastRealtimeEventId)
        assertEquals(1, api.getThreadDetailCallCount)
    }

    @Test
    fun `logout clears session state and closes realtime connection`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            lastOpenedThreadId = "thread-1",
            cachedThreads = listOf(thread1),
            cachedDetails = mutableMapOf("thread-1" to ThreadDetail(
                thread = thread1,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = true,
                sendDisabledReason = null
            )),
            connectionMode = GatewayConnectionMode.SOCKET
        )
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()
        repository.logout()

        val state = repository.state.value
        assertNull(state.config)
        assertFalse(state.isConnected)
        assertEquals(StreamConnectionState.CLOSED, state.connectionState)
        assertTrue(state.threads.isEmpty())
        assertNull(state.selectedThreadId)
        assertNull(state.detail)
        assertTrue(realtimeClient.lastConnection!!.closed)
        assertNull(store.loadConfig())
        assertTrue(store.cachedThreads.isEmpty())
        assertTrue(store.cachedDetails.isEmpty())
    }

    @Test
    fun `reconnect with same config reuses open socket connection`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            connectionMode = GatewayConnectionMode.SOCKET
        )
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()
        val firstConnection = realtimeClient.lastConnection!!

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        assertEquals(1, realtimeClient.connectCount)
        assertFalse(firstConnection.closed)
        assertSame(firstConnection, realtimeClient.lastConnection)
        assertEquals(StreamConnectionState.OPEN, repository.state.value.connectionState)
    }

    @Test
    fun `gateway diagnostics fetch times out quickly to keep export bundle moving`() = runTest {
        val api = FakeGatewayApi(listOf(thread1)).apply {
            diagnosticsDelayMillis = 10_000L
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        var capturedError: Throwable? = null
        val job = launch {
            capturedError = try {
                repository.fetchGatewayDiagnosticsJson()
                null
            } catch (error: Throwable) {
                error
            }
        }

        advanceTimeBy(7_999L)
        runCurrent()
        assertFalse(job.isCompleted)

        advanceTimeBy(1L)
        runCurrent()

        assertTrue(job.isCompleted)
        assertTrue(capturedError is TimeoutCancellationException)
        assertEquals(1, api.diagnosticsCallCount)
    }

    @Test
    fun `socket message event merges messages by id`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            connectionMode = GatewayConnectionMode.SOCKET
        )
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        val message = ThreadMessage(
            messageId = "assistant-2",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "新的回复",
            timestamp = "2026-04-30T01:00:02.000Z"
        )
        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.MessagesAppended(
                eventId = "event-message-1",
                threadId = "thread-1",
                messages = listOf(message),
                timestamp = "2026-04-30T01:00:02.000Z"
            )
        )
        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.MessagesAppended(
                eventId = "event-message-1",
                threadId = "thread-1",
                messages = listOf(message),
                timestamp = "2026-04-30T01:00:02.000Z"
            )
        )
        advanceUntilIdle()

        assertEquals(
            1,
            repository.state.value.detail!!.recentMessages.count { it.messageId == "assistant-2" }
        )
        assertEquals("event-message-1", store.lastRealtimeEventId)
    }

    @Test
    fun `socket message event caches inactive thread without http refresh`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val cachedThread2Detail = ThreadDetail(
            thread = thread2,
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "thread-2-old",
                    threadId = "thread-2",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "旧缓存",
                    timestamp = "2026-04-23T00:00:00Z"
                )
            ),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            connectionMode = GatewayConnectionMode.SOCKET,
            cachedThreads = listOf(thread1, thread2),
            cachedDetails = mutableMapOf("thread-2" to cachedThread2Detail)
        )
        val api = FakeGatewayApi(listOf(thread1, thread2))
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()
        val detailCallsAfterConnect = api.getThreadDetailCallCount

        val message = ThreadMessage(
            messageId = "thread-2-new",
            threadId = "thread-2",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "后台线程 socket 新消息",
            timestamp = "2026-04-30T01:00:02.000Z"
        )
        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.MessagesAppended(
                eventId = "event-thread-2-message",
                threadId = "thread-2",
                messages = listOf(message),
                timestamp = "2026-04-30T01:00:02.000Z"
            )
        )
        advanceUntilIdle()

        assertEquals("thread-1", repository.state.value.detail!!.thread.threadId)
        assertEquals(detailCallsAfterConnect, api.getThreadDetailCallCount)
        assertEquals(
            listOf("thread-2-old", "thread-2-new"),
            store.cachedDetails["thread-2"]!!.recentMessages.map { it.messageId }
        )
        assertFalse(repository.state.value.pendingDetailSyncThreadIds.contains("thread-2"))
    }

    @Test
    fun `selecting socket updated cached thread does not refresh detail`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val cachedThread2Detail = ThreadDetail(
            thread = thread2,
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "thread-2-old",
                    threadId = "thread-2",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "旧缓存",
                    timestamp = "2026-04-23T00:00:00Z"
                )
            ),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            connectionMode = GatewayConnectionMode.SOCKET,
            cachedThreads = listOf(thread1, thread2),
            cachedDetails = mutableMapOf("thread-2" to cachedThread2Detail)
        )
        val api = FakeGatewayApi(listOf(thread1, thread2))
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()
        val detailCallsAfterConnect = api.getThreadDetailCallCount
        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.MessagesAppended(
                eventId = "event-thread-2-message",
                threadId = "thread-2",
                messages = listOf(
                    ThreadMessage(
                        messageId = "thread-2-new",
                        threadId = "thread-2",
                        role = ThreadMessageRole.ASSISTANT,
                        kind = "text",
                        text = "socket 已经推送的新消息",
                        timestamp = "2026-04-30T01:00:02.000Z"
                    )
                ),
                timestamp = "2026-04-30T01:00:02.000Z"
            )
        )
        advanceUntilIdle()

        repository.selectThread("thread-2")
        advanceUntilIdle()

        assertEquals(detailCallsAfterConnect, api.getThreadDetailCallCount)
        assertEquals(
            listOf("thread-2-old", "thread-2-new"),
            repository.state.value.detail!!.recentMessages.map { it.messageId }
        )
    }

    @Test
    fun `socket notification marks inactive thread for detail sync without creating message`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val cachedThread2Detail = ThreadDetail(
            thread = thread2,
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "thread-2-old",
                    threadId = "thread-2",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "旧缓存",
                    timestamp = "2026-04-23T00:00:00Z"
                )
            ),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            connectionMode = GatewayConnectionMode.SOCKET,
            alertEnabledThreadIds = setOf("thread-2"),
            cachedThreads = listOf(thread1, thread2),
            cachedDetails = mutableMapOf("thread-2" to cachedThread2Detail)
        )
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1, thread2)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()
        val notification = GatewayRealtimeEvent.Notification(
            eventId = "notification:thread-2:completed:turn-9",
            notificationId = "thread-2:completed:turn-9",
            threadId = "thread-2",
            trigger = "completed",
            title = "Second",
            body = "本轮已完成",
            timestamp = "2026-04-30T01:00:01.000Z"
        )
        realtimeClient.lastConnection!!.emit(notification)
        advanceUntilIdle()

        assertTrue(repository.state.value.pendingDetailSyncThreadIds.contains("thread-2"))
        assertEquals(
            listOf("thread-2-old"),
            store.cachedDetails["thread-2"]!!.recentMessages.map { it.messageId }
        )
    }

    @Test
    fun `selecting notification marked thread refreshes detail and clears sync marker`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val cachedThread2Detail = ThreadDetail(
            thread = thread2,
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "thread-2-old",
                    threadId = "thread-2",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "旧缓存",
                    timestamp = "2026-04-23T00:00:00Z"
                )
            ),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val freshThread2Detail = cachedThread2Detail.copy(
            recentMessages = cachedThread2Detail.recentMessages + ThreadMessage(
                messageId = "thread-2-fresh",
                threadId = "thread-2",
                role = ThreadMessageRole.ASSISTANT,
                kind = "text",
                text = "兜底刷新拿到的新消息",
                timestamp = "2026-04-30T01:00:02.000Z"
            )
        )
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            connectionMode = GatewayConnectionMode.SOCKET,
            alertEnabledThreadIds = setOf("thread-2"),
            cachedThreads = listOf(thread1, thread2),
            cachedDetails = mutableMapOf("thread-2" to cachedThread2Detail)
        )
        val api = FakeGatewayApi(listOf(thread1, thread2))
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()
        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.Notification(
                eventId = "notification:thread-2:completed:turn-9",
                notificationId = "thread-2:completed:turn-9",
                threadId = "thread-2",
                trigger = "completed",
                title = "Second",
                body = "本轮已完成",
                timestamp = "2026-04-30T01:00:01.000Z"
            )
        )
        advanceUntilIdle()
        api.threadDetail = freshThread2Detail

        repository.selectThread("thread-2")
        advanceUntilIdle()

        assertEquals(
            listOf("thread-2-old", "thread-2-fresh"),
            repository.state.value.detail!!.recentMessages.map { it.messageId }
        )
        assertFalse(repository.state.value.pendingDetailSyncThreadIds.contains("thread-2"))
    }

    @Test
    fun `socket notification event is queued until it is displayed`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            connectionMode = GatewayConnectionMode.SOCKET,
            alertEnabledThreadIds = setOf("thread-1")
        )
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        val notification = GatewayRealtimeEvent.Notification(
            eventId = "notification:thread-1:completed:turn-9",
            notificationId = "thread-1:completed:turn-9",
            threadId = "thread-1",
            trigger = "completed",
            title = "本轮已完成",
            body = "本轮已完成",
            timestamp = "2026-04-30T01:00:01.000Z"
        )
        realtimeClient.lastConnection!!.emit(notification)
        realtimeClient.lastConnection!!.emit(notification)
        advanceUntilIdle()

        assertEquals(listOf(notification), repository.state.value.pendingRealtimeNotifications)
        assertFalse(store.knownNotificationIds.contains("thread-1:completed:turn-9"))

        repository.markRealtimeNotificationDisplayed("thread-1:completed:turn-9")
        assertTrue(repository.state.value.pendingRealtimeNotifications.isEmpty())
        assertTrue(store.knownNotificationIds.contains("thread-1:completed:turn-9"))
        assertTrue(
            realtimeClient.lastConnection!!.knownNotificationIds.contains(
                "thread-1:completed:turn-9"
            )
        )
    }

    @Test
    fun `socket notification cursor waits until notification is displayed`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            connectionMode = GatewayConnectionMode.SOCKET,
            alertEnabledThreadIds = setOf("thread-1"),
            lastRealtimeEventId = "event-before"
        )
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        val notification = GatewayRealtimeEvent.Notification(
            eventId = "notification:thread-1:completed:turn-9",
            notificationId = "thread-1:completed:turn-9",
            threadId = "thread-1",
            trigger = "completed",
            title = "本轮已完成",
            body = "本轮已完成",
            timestamp = "2026-04-30T01:00:01.000Z"
        )
        realtimeClient.lastConnection!!.emit(notification)
        advanceUntilIdle()
        assertEquals("event-before", store.lastRealtimeEventId)

        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.ThreadStatusChanged(
                eventId = "event-after-notification",
                threadId = "thread-1",
                status = MobileThreadStatus.COMPLETED,
                progressSummary = "本轮已完成",
                needsAttention = false,
                timestamp = "2026-04-30T01:00:02.000Z"
            )
        )
        advanceUntilIdle()
        assertEquals("event-before", store.lastRealtimeEventId)

        repository.markRealtimeNotificationDisplayed("thread-1:completed:turn-9")

        assertEquals("event-after-notification", store.lastRealtimeEventId)
        assertTrue(store.knownNotificationIds.contains("thread-1:completed:turn-9"))
    }

    @Test
    fun `socket latency test stores average from multiple samples`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            connectionMode = GatewayConnectionMode.SOCKET
        )
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { 1_777_635_927_000L }
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        realtimeClient.lastConnection!!.latencyResult = SocketLatencyTestResult(
            averageMillis = 24L,
            samplesMillis = listOf(20L, 25L, 27L)
        )

        repository.testSocketLatency()
        advanceUntilIdle()

        val state = repository.state.value
        assertFalse(state.isTestingSocketLatency)
        assertEquals(24L, state.socketLatencyAverageMillis)
        assertEquals(3, state.socketLatencySampleCount)
        assertNull(state.socketLatencyError)
    }

    @Test
    fun `sse latency test posts probes and averages sse echoes`() = runTest {
        val sseClient = ControllableGatewaySseClient()
        val api = FakeGatewayApi(listOf(thread1))
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            connectionMode = GatewayConnectionMode.fromWireValue("sse")
        )
        var now = 1_777_699_000_000L
        val repository = ThreadRepository(
            api = api,
            sseClient = sseClient,
            sessionStore = store,
            realtimeClient = FakeRealtimeClient(),
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { now }
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        val latencyJob = launch { repository.testSocketLatency() }
        runCurrent()

        assertEquals(3, api.latencyProbeRequests.size)
        api.latencyProbeRequests.forEachIndexed { index, request ->
            now = request.sentAt + listOf(18L, 22L, 26L)[index]
            sseClient.emit(
                GatewayStreamEvent(
                    type = "latency_probe_result",
                    threadId = null,
                    timestamp = "2026-05-02T05:29:00.000Z",
                    probeId = request.probeId,
                    sentAt = request.sentAt
                )
            )
            runCurrent()
        }
        latencyJob.join()

        val state = repository.state.value
        assertFalse(state.isTestingSocketLatency)
        assertEquals(22L, state.socketLatencyAverageMillis)
        assertEquals(3, state.socketLatencySampleCount)
        assertNull(state.socketLatencyError)
    }

    @Test
    fun `reconnecting socket triggers http compensation refresh`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val api = FakeGatewayApi(listOf(thread1))
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                connectionMode = GatewayConnectionMode.SOCKET
            ),
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        realtimeClient.lastConnection!!.emitState(StreamConnectionState.RECONNECTING)
        advanceTimeBy(1_500L)
        advanceUntilIdle()

        assertTrue(api.getThreadDetailCallCount >= 2)
    }

    @Test
    fun `failed socket falls back to sse stream updates`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val sseClient = ControllableGatewaySseClient()
        val api = FakeGatewayApi(listOf(thread1))
        val repository = ThreadRepository(
            api = api,
            sseClient = sseClient,
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                connectionMode = GatewayConnectionMode.SOCKET
            ),
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        realtimeClient.lastConnection!!.emitState(StreamConnectionState.FAILED)
        advanceUntilIdle()

        assertEquals(1, sseClient.connectCount)

        sseClient.emit(
            GatewayStreamEvent(
                type = "thread.updated",
                threadId = "thread-1",
                timestamp = "2026-04-30T01:00:00Z"
            )
        )
        advanceUntilIdle()

        assertTrue(api.getThreadDetailCallCount >= 2)
    }

    @Test
    fun `reconnecting sse restarts stream after delay`() = runTest {
        val sseClient = ControllableGatewaySseClient()
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1)),
            sseClient = sseClient,
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                connectionMode = GatewayConnectionMode.SSE
            ),
            realtimeClient = FakeRealtimeClient(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        assertEquals(1, sseClient.connectCount)

        sseClient.emitState(StreamConnectionState.RECONNECTING)
        advanceTimeBy(1_500L)
        advanceUntilIdle()

        assertEquals(2, sseClient.connectCount)
        assertEquals(StreamConnectionState.OPEN, repository.state.value.connectionState)
    }

    @Test
    fun `connect shows refreshed notification cache before network detail returns`() = runTest {
        val staleDetail = ThreadDetail(
            thread = thread1,
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "old-message",
                    threadId = "thread-1",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "旧缓存消息",
                    timestamp = "2026-04-23T00:00:00Z"
                )
            ),
            recentEvents = emptyList(),
            sendAvailable = true
        )
        val notificationDetail = staleDetail.copy(
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "new-message",
                    threadId = "thread-1",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "通知刚看到的新消息",
                    timestamp = "2026-04-23T00:01:00Z"
                )
            )
        )
        val releaseLogin = CompletableDeferred<Unit>()
        val releaseThreads = CompletableDeferred<Unit>()
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            lastOpenedThreadId = "thread-1",
            cachedThreads = listOf(thread1),
            cachedDetails = mutableMapOf("thread-1" to staleDetail)
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig): LoginResponse {
                releaseLogin.await()
                return LoginResponse(authenticated = true)
            }

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                releaseThreads.await()
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
                return notificationDetail
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        store.cachedDetails["thread-1"] = notificationDetail

        val connectJob = launch {
            repository.connect(config = config, requestedThreadId = "thread-1")
        }
        runCurrent()

        val state = repository.state.value
        assertEquals("thread-1", state.selectedThreadId)
        assertEquals("通知刚看到的新消息", state.detail?.recentMessages?.single()?.text)

        releaseLogin.complete(Unit)
        releaseThreads.complete(Unit)
        advanceUntilIdle()
        connectJob.cancel()
    }

    @Test
    fun `clear thread cache removes cached threads and current detail without clearing config`() = runTest {
        val cachedDetail = ThreadDetail(
            thread = thread1,
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "cached-message",
                    threadId = "thread-1",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "cached",
                    timestamp = "2026-04-23T00:00:00Z"
                )
            ),
            recentEvents = emptyList(),
            sendAvailable = true
        )
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            lastOpenedThreadId = "thread-1",
            cachedThreads = listOf(thread1),
            cachedDetails = mutableMapOf("thread-1" to cachedDetail)
        )
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.clearThreadCache()

        val state = repository.state.value
        assertEquals(config, state.config)
        assertTrue(state.threads.isEmpty())
        assertNull(state.selectedThreadId)
        assertNull(state.detail)
        assertFalse(state.canLoadOlderMessages)
        assertTrue(store.cachedThreads.isEmpty())
        assertTrue(store.cachedDetails.isEmpty())
        assertNull(store.loadLastOpenedThreadId())
        assertEquals(config, store.loadConfig())
    }

    @Test
    fun `clear thread cache prevents in flight refresh from restoring cached data`() = runTest {
        val releaseRefresh = CompletableDeferred<Unit>()
        val store = InMemoryGatewaySessionStore(
            savedConfig = config,
            lastOpenedThreadId = "thread-1",
            cachedThreads = listOf(thread1),
            cachedDetails = mutableMapOf(
                "thread-1" to ThreadDetail(
                    thread = thread1,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            )
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                releaseRefresh.await()
                return listOf(thread2)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail = throw IllegalStateException("unused")

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = store,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val refreshJob = launch { repository.refreshThreads() }
        runCurrent()
        repository.clearThreadCache()
        releaseRefresh.complete(Unit)
        advanceUntilIdle()

        assertTrue(repository.state.value.threads.isEmpty())
        assertNull(repository.state.value.detail)
        assertTrue(store.cachedThreads.isEmpty())
        assertTrue(store.cachedDetails.isEmpty())
        assertNull(store.loadLastOpenedThreadId())
        refreshJob.cancel()
    }

    @Test
    fun `manual refresh reloads the thread list and preserves selected detail`() = runTest {
        var refreshCount = 0
        val refreshedThread = thread1.copy(
            updatedAt = "2026-04-23T00:02:00Z",
            progressSummary = "fresh from manual refresh"
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                refreshCount += 1
                return if (refreshCount == 1) {
                    listOf(thread1)
                } else {
                    listOf(refreshedThread, thread2)
                }
            }

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = listOf(
                        ThreadMessage(
                            messageId = "message-1",
                            threadId = threadId,
                            role = ThreadMessageRole.USER,
                            kind = "text",
                            text = "hello",
                            timestamp = "2026-04-23T00:00:00Z"
                        )
                    ),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.refreshThreads()

        val state = repository.state.value
        assertEquals(listOf(refreshedThread, thread2), state.threads)
        assertEquals("thread-1", state.selectedThreadId)
        assertEquals("fresh from manual refresh", state.detail?.thread?.progressSummary)
        assertEquals("hello", state.detail?.recentMessages?.single()?.text)
    }

    @Test
    fun `detail refresh reports unchanged when latest message is already visible`() = runTest {
        val message = ThreadMessage(
            messageId = "message-1",
            threadId = "thread-1",
            role = ThreadMessageRole.USER,
            kind = "text",
            text = "hello",
            timestamp = "2026-04-23T00:00:00Z"
        )
        val detail = ThreadDetail(
            thread = thread1,
            recentMessages = listOf(message),
            recentEvents = emptyList(),
            sendAvailable = true
        )
        val api = FakeGatewayApi(listOf(thread1)).apply {
            threadDetail = detail
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        val result = repository.refreshThreadDetail("thread-1")

        assertEquals(DetailRefreshStatus.UNCHANGED, result.status)
        assertFalse(result.changed)
        assertEquals("message-1", result.lastMessageId)
        assertEquals(1, result.messageCount)
    }

    @Test
    fun `detail refresh reports updated when a newer message arrives`() = runTest {
        val firstMessage = ThreadMessage(
            messageId = "message-1",
            threadId = "thread-1",
            role = ThreadMessageRole.USER,
            kind = "text",
            text = "hello",
            timestamp = "2026-04-23T00:00:00Z"
        )
        val secondMessage = firstMessage.copy(
            messageId = "message-2",
            role = ThreadMessageRole.ASSISTANT,
            text = "done",
            timestamp = "2026-04-23T00:01:00Z"
        )
        val api = FakeGatewayApi(listOf(thread1)).apply {
            threadDetail = ThreadDetail(
                thread = thread1,
                recentMessages = listOf(firstMessage),
                recentEvents = emptyList(),
                sendAvailable = true
            )
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        api.threadDetail = ThreadDetail(
            thread = thread1.copy(updatedAt = "2026-04-23T00:01:00Z"),
            recentMessages = listOf(firstMessage, secondMessage),
            recentEvents = emptyList(),
            sendAvailable = true
        )
        val result = repository.refreshThreadDetail("thread-1")

        assertEquals(DetailRefreshStatus.UPDATED, result.status)
        assertTrue(result.changed)
        assertEquals("message-2", result.lastMessageId)
        assertEquals(2, result.messageCount)
    }

    @Test
    fun `detail refresh reports failed when gateway detail request fails`() = runTest {
        val api = FakeGatewayApi(listOf(thread1))
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        api.threadDetailError = IOException("connection closed")
        val result = repository.refreshThreadDetail("thread-1")

        assertEquals(DetailRefreshStatus.FAILED, result.status)
        assertFalse(result.changed)
        assertEquals("gateway_unreachable", result.errorMessage)
        assertEquals("gateway_unreachable", repository.state.value.errorMessage)
    }

    @Test
    fun `detail refresh joins an in flight request for the same thread`() = runTest {
        val releaseRefresh = CompletableDeferred<Unit>()
        var detailCalls = 0
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
                detailCalls += 1
                if (detailCalls > 1) {
                    releaseRefresh.await()
                }
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = listOf(
                        ThreadMessage(
                            messageId = "message-1",
                            threadId = threadId,
                            role = ThreadMessageRole.USER,
                            kind = "text",
                            text = "hello",
                            timestamp = "2026-04-23T00:00:00Z"
                        )
                    ),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        val firstRefresh = async { repository.refreshThreadDetail("thread-1") }
        runCurrent()
        val secondRefresh = async { repository.refreshThreadDetail("thread-1") }
        runCurrent()

        assertFalse(firstRefresh.isCompleted)
        assertFalse(secondRefresh.isCompleted)
        assertEquals(2, detailCalls)

        releaseRefresh.complete(Unit)
        advanceUntilIdle()

        assertEquals(DetailRefreshStatus.UNCHANGED, firstRefresh.await().status)
        assertEquals(DetailRefreshStatus.UNCHANGED, secondRefresh.await().status)
        assertEquals(2, detailCalls)
    }

    @Test
    fun `socket open keeps persistent sync warning when http detail refresh fails`() = runTest {
        val api = FakeGatewayApi(listOf(thread1))
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                connectionMode = GatewayConnectionMode.SOCKET
            ),
            realtimeClient = FakeRealtimeClient(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        api.threadDetailError = IOException("timeout")
        repository.refreshThreadDetail("thread-1")

        assertEquals(StreamConnectionState.OPEN, repository.state.value.connectionState)
        assertEquals("gateway_unreachable", repository.state.value.syncWarningMessage)

        api.threadDetailError = null
        repository.refreshThreadDetail("thread-1")

        assertNull(repository.state.value.syncWarningMessage)
    }

    @Test
    fun `manual refresh exposes refreshing state while threads are loading`() = runTest {
        val releaseRefresh = CompletableDeferred<Unit>()
        var refreshCount = 0
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                refreshCount += 1
                if (refreshCount > 1) {
                    releaseRefresh.await()
                }
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        val refreshJob = launch { repository.refreshThreads() }
        runCurrent()

        assertTrue(repository.state.value.isRefreshingThreads)

        releaseRefresh.complete(Unit)
        advanceUntilIdle()

        assertFalse(repository.state.value.isRefreshingThreads)
        refreshJob.cancel()
    }

    @Test
    fun `checkLatestApk stores async update metadata without touching thread state`() = runTest {
        val api = FakeGatewayApi(listOf(thread1)).apply {
            latestApkInfo = LatestApkInfo(
                available = true,
                fileName = "codex-mobile-control-debug-20260427-151500.apk",
                versionCode = 123,
                versionName = "1.0.23"
            )
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.checkLatestApk()

        val state = repository.state.value
        assertFalse(state.isCheckingLatestApk)
        assertEquals("thread-1", state.selectedThreadId)
        assertEquals(123, state.latestApkInfo?.versionCode)
        assertTrue(state.latestApkInfo?.isNewerThan(122) == true)
    }

    @Test
    fun `checkLatestApk skips automatic refresh for an hour but force refreshes`() = runTest {
        var nowMillis = 1_000L
        val api = FakeGatewayApi(listOf(thread1)).apply {
            latestApkInfo = LatestApkInfo(
                available = true,
                fileName = "codex-mobile-control-debug-20260427-151500.apk",
                versionCode = 123,
                versionName = "1.0.23"
            )
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { nowMillis }
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.checkLatestApk()
        nowMillis += 30 * 60 * 1_000L
        repository.checkLatestApk()

        assertEquals(1, api.latestApkInfoCalls)

        repository.checkLatestApk(force = true)

        assertEquals(2, api.latestApkInfoCalls)
    }

    @Test
    fun `connect marks gateway reachable before thread bootstrap finishes`() = runTest {
        val releaseThreads = CompletableDeferred<Unit>()
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                releaseThreads.await()
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val connectJob = launch { repository.connect(config = config, requestedThreadId = null) }
        runCurrent()

        assertTrue(repository.state.value.isConnected)
        assertEquals(StreamConnectionState.CONNECTING, repository.state.value.connectionState)
        assertTrue(repository.state.value.threads.isEmpty())
        assertNull(repository.state.value.detail)

        releaseThreads.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(thread1), repository.state.value.threads)
        connectJob.cancel()
    }

    @Test
    fun `repository displays cached threads before connect refresh finishes`() = runTest {
        val releaseThreads = CompletableDeferred<Unit>()
        val refreshedThread = thread1.copy(
            updatedAt = "2026-04-23T00:03:00Z",
            progressSummary = "fresh from gateway"
        )
        val sessionStore = InMemoryGatewaySessionStore(
            savedConfig = config,
            lastOpenedThreadId = "thread-1",
            cachedThreads = listOf(thread1)
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                releaseThreads.await()
                return listOf(refreshedThread, thread2)
            }

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
                return ThreadDetail(
                    thread = refreshedThread,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = sessionStore,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        assertTrue(repository.state.value.isConnected)
        assertEquals(listOf(thread1), repository.state.value.threads)
        assertEquals("thread-1", repository.state.value.selectedThreadId)

        val connectJob = launch { repository.connect(config = config, requestedThreadId = null) }
        runCurrent()

        assertTrue(repository.state.value.isConnected)
        assertEquals(listOf(thread1), repository.state.value.threads)

        releaseThreads.complete(Unit)
        advanceUntilIdle()

        val refreshedThreads = listOf(refreshedThread, thread2)
        assertEquals(refreshedThreads, repository.state.value.threads)
        assertEquals(refreshedThreads, sessionStore.cachedThreads)
        connectJob.cancel()
    }

    @Test
    fun `startup reconnect does not restore last opened thread over a newer user selection`() = runTest {
        val releaseStartupDetail = CompletableDeferred<Unit>()
        val cachedThread1Detail = ThreadDetail(
            thread = thread1,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true
        )
        val cachedThread2Detail = ThreadDetail(
            thread = thread2,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true
        )
        val sessionStore = InMemoryGatewaySessionStore(
            savedConfig = config,
            lastOpenedThreadId = "thread-1",
            cachedThreads = listOf(thread1, thread2),
            cachedDetails = mutableMapOf(
                "thread-1" to cachedThread1Detail,
                "thread-2" to cachedThread2Detail
            )
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                return listOf(thread1, thread2)
            }

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
                if (threadId == "thread-1") {
                    releaseStartupDetail.await()
                }
                val thread = if (threadId == "thread-2") thread2 else thread1
                return ThreadDetail(
                    thread = thread,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = sessionStore,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        assertEquals("thread-1", repository.state.value.selectedThreadId)

        val connectJob = launch { repository.connect(config = config, requestedThreadId = null) }
        runCurrent()

        repository.selectThread("thread-2")
        advanceUntilIdle()

        assertEquals("thread-2", repository.state.value.selectedThreadId)

        releaseStartupDetail.complete(Unit)
        advanceUntilIdle()

        assertEquals("thread-2", repository.state.value.selectedThreadId)
        assertEquals("thread-2", repository.state.value.detail?.thread?.threadId)
        connectJob.cancel()
    }

    @Test
    fun `alert detail fetch does not replace the selected conversation or rebuild display cache`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sessionStore = InMemoryGatewaySessionStore(savedConfig = config)
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1, thread2)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = sessionStore,
            dispatcher = dispatcher
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        val alertDetail = repository.fetchThreadDetailForAlert("thread-2")
        advanceUntilIdle()

        assertEquals("thread-2", alertDetail?.thread?.threadId)
        assertEquals("thread-1", repository.state.value.selectedThreadId)
        assertEquals("thread-1", repository.state.value.detail?.thread?.threadId)
        assertNull(sessionStore.cachedDetails["thread-2"])
    }

    @Test
    fun `connect replaces selected list item with fresher detail thread`() = runTest {
        val staleThread = thread1.copy(
            status = MobileThreadStatus.RUNNING,
            updatedAt = "2026-04-23T10:22:40Z",
            progressSummary = "正在处理新的请求"
        )
        val freshDetailThread = staleThread.copy(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2026-04-23T10:23:01Z",
            progressSummary = "本轮已完成"
        )

        val repository = ThreadRepository(
            api = object : GatewayApi {
                override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

                override suspend fun getThreads(config: GatewayConfig) = listOf(staleThread)

                override suspend fun getThreadDetail(
                    config: GatewayConfig,
                    threadId: String
                ) = ThreadDetail(
                    thread = freshDetailThread,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
                    sendAvailable = true,
                    sendDisabledReason = null
                )

                override suspend fun sendMessage(
                    config: GatewayConfig,
                    threadId: String,
                    text: String,
                    clientMessageId: String
                ): SendMessageResponse = throw IllegalStateException("unused")

                override suspend fun sendImageMessage(
                    config: GatewayConfig,
                    threadId: String,
                    text: String?,
                    clientMessageId: String,
                    image: ImageUploadSource
                ): SendMessageResponse = throw IllegalStateException("unused")
            },
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                lastOpenedThreadId = staleThread.threadId
            ),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = null)
        val state = repository.state.first { it.isConnected }

        assertEquals(MobileThreadStatus.COMPLETED, state.detail?.thread?.status)
        assertEquals(MobileThreadStatus.COMPLETED, state.threads.first().status)
        assertEquals("本轮已完成", state.threads.first().progressSummary)
    }

    @Test
    fun `connect keeps fresher list snapshot when detail thread is stale`() = runTest {
        val freshListThread = thread1.copy(
            status = MobileThreadStatus.WAITING_INPUT,
            updatedAt = "2026-04-24T04:20:00Z",
            progressSummary = "线程正在等待新的输入",
            needsAttention = true
        )
        val staleDetailThread = freshListThread.copy(
            status = MobileThreadStatus.RUNNING,
            updatedAt = "2026-04-24T04:17:00Z",
            progressSummary = "正在处理新的请求",
            needsAttention = false
        )

        val repository = ThreadRepository(
            api = object : GatewayApi {
                override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

                override suspend fun getThreads(config: GatewayConfig) = listOf(freshListThread)

                override suspend fun getThreadDetail(
                    config: GatewayConfig,
                    threadId: String
                ) = ThreadDetail(
                    thread = staleDetailThread,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
                    sendAvailable = true,
                    sendDisabledReason = null
                )

                override suspend fun sendMessage(
                    config: GatewayConfig,
                    threadId: String,
                    text: String,
                    clientMessageId: String
                ): SendMessageResponse = throw IllegalStateException("unused")

                override suspend fun sendImageMessage(
                    config: GatewayConfig,
                    threadId: String,
                    text: String?,
                    clientMessageId: String,
                    image: ImageUploadSource
                ): SendMessageResponse = throw IllegalStateException("unused")
            },
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                lastOpenedThreadId = freshListThread.threadId
            ),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = null)
        val state = repository.state.first { it.isConnected }

        assertEquals(MobileThreadStatus.WAITING_INPUT, state.detail?.thread?.status)
        assertEquals("线程正在等待新的输入", state.detail?.thread?.progressSummary)
        assertEquals(true, state.detail?.thread?.needsAttention)
        assertEquals(MobileThreadStatus.WAITING_INPUT, state.threads.first().status)
    }

    @Test
    fun `sendMessage keeps draft disabled while sending and refreshes detail`() = runTest {
        val api = FakeGatewayApi(listOf(thread1))
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.sendMessage("hello")
        val state = repository.state.value

        assertFalse(state.isSending)
        assertEquals("hello", api.lastSentText)
        assertEquals("thread-1", state.selectedThreadId)
        assertTrue(state.detail != null)
    }

    @Test
    fun `sendMessage returns optimistic success without waiting for detail refresh`() = runTest {
        val refreshGate = CompletableDeferred<ThreadDetail>()
        var detailCalls = 0
        var sendCalls = 0
        val sendableDetail = ThreadDetail(
            thread = thread1,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                detailCalls += 1
                return if (detailCalls == 1) {
                    sendableDetail
                } else {
                    refreshGate.await()
                }
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse {
                sendCalls += 1
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
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        val sendJob = launch { repository.sendMessage("hello") }
        runCurrent()

        try {
            val state = repository.state.value
            assertFalse(state.isSending)
            assertEquals(1, sendCalls)
            assertEquals(MobileThreadStatus.RUNNING, state.detail?.thread?.status)
            assertEquals(false, state.detail?.sendAvailable)
            assertEquals("hello", state.detail?.recentMessages?.lastOrNull()?.text)
        } finally {
            sendJob.cancel()
        }
    }

    @Test
    fun `queueMessageAfterCurrentTurn caches text and sends after current run finishes`() = runTest {
        val runningThread = thread1.copy(
            status = MobileThreadStatus.RUNNING,
            updatedAt = "2026-05-08T00:01:00Z",
            progressSummary = "消息已发送，线程正在处理中"
        )
        val completedThread = thread1.copy(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2026-05-08T00:02:00Z",
            progressSummary = "本轮已完成"
        )
        val api = FakeGatewayApi(listOf(runningThread)).apply {
            threadDetail = ThreadDetail(
                thread = runningThread,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = false,
                sendDisabledReason = "线程仍在运行，暂不支持并发发送"
            )
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        val queued = repository.queueMessageAfterCurrentTurn("继续下一步优化")

        assertNull(api.lastSentText)
        assertEquals("继续下一步优化", queued?.text)
        assertEquals("继续下一步优化", repository.state.value.queuedTextMessages.single().text)
        assertEquals(QueuedTextMessageStatus.PENDING, repository.state.value.queuedTextMessages.single().status)

        api.threadDetail = ThreadDetail(
            thread = completedThread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        repository.refreshThreadDetail("thread-1")
        advanceUntilIdle()

        assertEquals("继续下一步优化", api.lastSentText)
        assertTrue(repository.state.value.queuedTextMessages.isEmpty())
    }

    @Test
    fun `queueMessageAfterCurrentTurn keeps multiple texts and dispatches them in order`() = runTest {
        val runningThread = thread1.copy(
            status = MobileThreadStatus.RUNNING,
            updatedAt = "2026-05-08T00:01:00Z",
            progressSummary = "消息已发送，线程正在处理中"
        )
        val firstCompletedThread = thread1.copy(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2099-05-08T00:02:00Z",
            progressSummary = "本轮已完成"
        )
        val secondCompletedThread = thread1.copy(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2099-05-08T00:03:00Z",
            progressSummary = "下一轮已完成"
        )
        val api = FakeGatewayApi(listOf(runningThread)).apply {
            threadDetail = ThreadDetail(
                thread = runningThread,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = false,
                sendDisabledReason = "线程仍在运行，暂不支持并发发送"
            )
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.queueMessageAfterCurrentTurn("第一条排队")
        repository.queueMessageAfterCurrentTurn("第二条排队")

        assertEquals(listOf("第一条排队", "第二条排队"), repository.state.value.queuedTextMessages.map { it.text })

        api.threadDetail = ThreadDetail(
            thread = firstCompletedThread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        repository.refreshThreadDetail("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("第一条排队"), api.sentTexts)
        assertEquals(listOf("第二条排队"), repository.state.value.queuedTextMessages.map { it.text })

        api.threadDetail = ThreadDetail(
            thread = secondCompletedThread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        repository.refreshThreadDetail("thread-1")
        advanceUntilIdle()

        assertEquals(listOf("第一条排队", "第二条排队"), api.sentTexts)
        assertTrue(repository.state.value.queuedTextMessages.isEmpty())
    }

    @Test
    fun `queued text survives repository recreation and dispatches after current run finishes`() = runTest {
        val runningThread = thread1.copy(
            status = MobileThreadStatus.RUNNING,
            updatedAt = "2026-05-08T00:01:00Z",
            progressSummary = "消息已发送，线程正在处理中"
        )
        val completedThread = thread1.copy(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2026-05-08T00:02:00Z",
            progressSummary = "本轮已完成"
        )
        val api = FakeGatewayApi(listOf(runningThread)).apply {
            threadDetail = ThreadDetail(
                thread = runningThread,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = false,
                sendDisabledReason = "线程仍在运行，暂不支持并发发送"
            )
        }
        val sessionStore = InMemoryGatewaySessionStore(savedConfig = config)
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = sessionStore,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.queueMessageAfterCurrentTurn("重启后继续发送")

        val recreatedRepository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = sessionStore,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        assertEquals(
            listOf("重启后继续发送"),
            recreatedRepository.state.value.queuedTextMessages.map { it.text }
        )

        api.threadDetail = ThreadDetail(
            thread = completedThread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        recreatedRepository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        assertEquals("重启后继续发送", api.lastSentText)
        assertTrue(recreatedRepository.state.value.queuedTextMessages.isEmpty())
    }

    @Test
    fun `queued texts keep polling after queued run outlives short post send refreshes`() = runTest {
        val runningThread = thread1.copy(
            status = MobileThreadStatus.RUNNING,
            updatedAt = "2026-05-08T00:01:00Z",
            progressSummary = "消息已发送，线程正在处理中"
        )
        val firstCompletedThread = thread1.copy(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2026-05-08T00:02:00Z",
            progressSummary = "本轮已完成"
        )
        val queuedRunStillRunningThread = thread1.copy(
            status = MobileThreadStatus.RUNNING,
            updatedAt = "2026-05-08T00:02:10Z",
            progressSummary = "开始处理新的输入"
        )
        val queuedRunCompletedThread = thread1.copy(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2026-05-08T00:03:00Z",
            progressSummary = "本轮已完成"
        )
        val api = FakeGatewayApi(listOf(runningThread)).apply {
            threadDetail = ThreadDetail(
                thread = runningThread,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = false,
                sendDisabledReason = "线程仍在运行，暂不支持并发发送"
            )
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { Instant.parse("2026-05-08T00:02:05Z").toEpochMilli() }
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.queueMessageAfterCurrentTurn("第一条排队")
        repository.queueMessageAfterCurrentTurn("第二条排队")

        api.threadDetail = ThreadDetail(
            thread = firstCompletedThread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        repository.refreshThreadDetail("thread-1")
        runCurrent()

        assertEquals(listOf("第一条排队"), api.sentTexts)
        assertEquals(listOf("第二条排队"), repository.state.value.queuedTextMessages.map { it.text })

        api.threadDetail = ThreadDetail(
            thread = queuedRunStillRunningThread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = false,
            sendDisabledReason = "线程仍在运行，暂不支持并发发送"
        )
        advanceTimeBy(20_000L)
        runCurrent()

        assertEquals(listOf("第一条排队"), api.sentTexts)

        api.threadDetail = ThreadDetail(
            thread = queuedRunCompletedThread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        advanceTimeBy(6_000L)
        runCurrent()

        assertEquals(listOf("第一条排队", "第二条排队"), api.sentTexts)
        assertTrue(repository.state.value.queuedTextMessages.isEmpty())
    }

    @Test
    fun `queued text stays pending when a stale completed snapshot arrives`() = runTest {
        val queueConfig = GatewayConfig("http://queue-test-gateway", "queue-token")
        val runningThread = thread1.copy(
            status = MobileThreadStatus.RUNNING,
            updatedAt = "2026-05-08T00:01:00Z",
            progressSummary = "消息已发送，线程正在处理中"
        )
        val staleCompletedThread = thread1.copy(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2026-05-08T00:00:30Z",
            progressSummary = "上一轮已完成"
        )
        val api = FakeGatewayApi(listOf(runningThread)).apply {
            threadDetail = ThreadDetail(
                thread = runningThread,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = false,
                sendDisabledReason = "线程仍在运行，暂不支持并发发送"
            )
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = queueConfig),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = queueConfig, requestedThreadId = "thread-1")
        repository.queueMessageAfterCurrentTurn("继续下一步优化")

        api.threadDetail = ThreadDetail(
            thread = staleCompletedThread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        repository.refreshThreadDetail("thread-1")
        advanceUntilIdle()

        assertNull(api.lastSentText)
        assertEquals("继续下一步优化", repository.state.value.queuedTextMessages.single().text)
        assertEquals(QueuedTextMessageStatus.PENDING, repository.state.value.queuedTextMessages.single().status)
    }

    @Test
    fun `failed queued text stays in queue with failure status and blocks later texts`() = runTest {
        val queueConfig = GatewayConfig("http://failed-queue-test-gateway", "queue-token")
        val runningThread = thread1.copy(
            status = MobileThreadStatus.RUNNING,
            updatedAt = "2026-05-08T00:01:00Z",
            progressSummary = "消息已发送，线程正在处理中"
        )
        val completedThread = thread1.copy(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2026-05-08T00:02:00Z",
            progressSummary = "本轮已完成"
        )
        val api = FakeGatewayApi(listOf(runningThread)).apply {
            threadDetail = ThreadDetail(
                thread = runningThread,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = false,
                sendDisabledReason = "线程仍在运行，暂不支持并发发送"
            )
            sendError = IOException("queue_network_failed")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = queueConfig),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = queueConfig, requestedThreadId = "thread-1")
        repository.queueMessageAfterCurrentTurn("会失败的第一条")
        repository.queueMessageAfterCurrentTurn("不能越过失败项的第二条")

        api.threadDetail = ThreadDetail(
            thread = completedThread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        repository.refreshThreadDetail("thread-1")
        runCurrent()

        assertEquals(emptyList<String>(), api.sentTexts)
        assertEquals(
            listOf(
                QueuedTextMessageStatus.FAILED,
                QueuedTextMessageStatus.PENDING
            ),
            repository.state.value.queuedTextMessages.map { it.status }
        )
        assertEquals("queue_network_failed", repository.state.value.queuedTextMessages.first().errorMessage)
    }

    @Test
    fun `cancelQueuedMessage prevents cached text from sending after current run finishes`() = runTest {
        val runningThread = thread1.copy(
            status = MobileThreadStatus.RUNNING,
            updatedAt = "2026-05-08T00:01:00Z",
            progressSummary = "消息已发送，线程正在处理中"
        )
        val completedThread = thread1.copy(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2026-05-08T00:02:00Z",
            progressSummary = "本轮已完成"
        )
        val api = FakeGatewayApi(listOf(runningThread)).apply {
            threadDetail = ThreadDetail(
                thread = runningThread,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = false,
                sendDisabledReason = "线程仍在运行，暂不支持并发发送"
            )
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        val queued = repository.queueMessageAfterCurrentTurn("不要再发这条")
        repository.cancelQueuedMessage(queued?.queueId)

        api.threadDetail = ThreadDetail(
            thread = completedThread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        repository.refreshThreadDetail("thread-1")
        advanceUntilIdle()

        assertNull(api.lastSentText)
        assertTrue(repository.state.value.queuedTextMessages.isEmpty())
    }

    @Test
    fun `sendMessage shows pending user bubble before gateway send returns`() = runTest {
        val releaseSend = CompletableDeferred<Unit>()
        var sendStarted = false
        val sendableDetail = ThreadDetail(
            thread = thread1,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String) = sendableDetail

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse {
                sendStarted = true
                releaseSend.await()
                return SendMessageResponse(
                    accepted = true,
                    threadId = threadId,
                    clientMessageId = clientMessageId,
                    sendPath = "desktop_bridge",
                    confirmation = "keystrokes_sent"
                )
            }

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        var accepted = false
        val sendJob = launch {
            accepted = repository.sendMessage("typed now")
        }
        runCurrent()

        val pendingMessage = repository.state.value.detail?.recentMessages?.single()
        assertTrue(sendStarted)
        assertFalse(repository.state.value.isSending)
        assertEquals("typed now", pendingMessage?.text)
        assertEquals(ThreadMessageSendState.SENDING, pendingMessage?.sendState)

        releaseSend.complete(Unit)
        advanceUntilIdle()
        assertTrue(accepted)
        sendJob.cancel()
    }

    @Test
    fun `sendMessage marks optimistic text failed when gateway send never returns`() = runTest {
        val releaseSend = CompletableDeferred<Unit>()
        var sendStarted = false
        val sendableDetail = ThreadDetail(
            thread = thread1,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String) = sendableDetail

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse {
                sendStarted = true
                releaseSend.await()
                return SendMessageResponse(
                    accepted = true,
                    threadId = threadId,
                    clientMessageId = clientMessageId,
                    sendPath = "desktop_bridge",
                    confirmation = "keystrokes_sent"
                )
            }

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.sendMessage("hanging send")
        runCurrent()

        val pendingMessage = repository.state.value.detail?.recentMessages?.single()
        assertTrue(sendStarted)
        assertEquals(ThreadMessageSendState.SENDING, pendingMessage?.sendState)

        advanceTimeBy(60_001L)
        runCurrent()

        val failedMessage = repository.state.value.detail?.recentMessages?.single()
        assertEquals("hanging send", failedMessage?.text)
        assertEquals(ThreadMessageSendState.FAILED, failedMessage?.sendState)

        releaseSend.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `failed optimistic text message can be retried from its bubble`() = runTest {
        var failNextSend = true
        val releaseRetry = CompletableDeferred<Unit>()
        val sentTexts = mutableListOf<String>()
        val sendableDetail = ThreadDetail(
            thread = thread1,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String) = sendableDetail

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse {
                sentTexts += text
                if (failNextSend) {
                    failNextSend = false
                    throw IOException("desktop bridge unavailable")
                }
                releaseRetry.await()
                return SendMessageResponse(
                    accepted = true,
                    threadId = threadId,
                    clientMessageId = clientMessageId,
                    sendPath = "desktop_bridge",
                    confirmation = "keystrokes_sent"
                )
            }

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.sendMessage("please retry")
        advanceUntilIdle()

        val failedMessage = repository.state.value.detail?.recentMessages?.single()
        assertEquals(ThreadMessageSendState.FAILED, failedMessage?.sendState)

        val retried = repository.retryFailedMessage(requireNotNull(failedMessage).messageId)
        runCurrent()

        val retryingMessage = repository.state.value.detail?.recentMessages?.single()
        assertTrue(retried)
        assertFalse(repository.state.value.isSending)
        assertEquals(listOf("please retry", "please retry"), sentTexts)
        assertEquals(ThreadMessageSendState.SENDING, retryingMessage?.sendState)

        releaseRetry.complete(Unit)
        advanceUntilIdle()
        val sentMessage = repository.state.value.detail?.recentMessages?.single()
        assertNull(sentMessage?.sendState)
        assertNull(repository.state.value.errorMessage)
    }

    @Test
    fun `connect surfaces token invalid without claiming success`() = runTest {
        val repository = ThreadRepository(
            api = object : GatewayApi {
                override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = false)

                override suspend fun getThreads(config: GatewayConfig) = emptyList<ThreadListItem>()

                override suspend fun getThreadDetail(
                    config: GatewayConfig,
                    threadId: String
                ): ThreadDetail = throw IllegalStateException("unused")

                override suspend fun sendMessage(
                    config: GatewayConfig,
                    threadId: String,
                    text: String,
                    clientMessageId: String
                ): SendMessageResponse = throw IllegalStateException("unused")

                override suspend fun sendImageMessage(
                    config: GatewayConfig,
                    threadId: String,
                    text: String?,
                    clientMessageId: String,
                    image: ImageUploadSource
                ): SendMessageResponse = throw IllegalStateException("unused")
            },
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        runCatching { repository.connect(config, null) }
        val state = repository.state.value

        assertFalse(state.isConnected)
        assertEquals("token_invalid", state.errorMessage)
    }

    @Test
    fun `connect maps io failures to gateway unreachable`() = runTest {
        val repository = ThreadRepository(
            api = object : GatewayApi {
                override suspend fun login(config: GatewayConfig): LoginResponse {
                    throw IOException("connection refused")
                }

                override suspend fun getThreads(config: GatewayConfig) = emptyList<ThreadListItem>()

                override suspend fun getThreadDetail(
                    config: GatewayConfig,
                    threadId: String
                ): ThreadDetail = throw IllegalStateException("unused")

                override suspend fun sendMessage(
                    config: GatewayConfig,
                    threadId: String,
                    text: String,
                    clientMessageId: String
                ): SendMessageResponse = throw IllegalStateException("unused")

                override suspend fun sendImageMessage(
                    config: GatewayConfig,
                    threadId: String,
                    text: String?,
                    clientMessageId: String,
                    image: ImageUploadSource
                ): SendMessageResponse = throw IllegalStateException("unused")
            },
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        runCatching { repository.connect(config, null) }
        val state = repository.state.value

        assertFalse(state.isConnected)
        assertEquals(StreamConnectionState.FAILED, state.connectionState)
        assertEquals("gateway_unreachable", state.errorMessage)
    }

    @Test
    fun `connect keeps current detail when reconnect fails after a successful session`() = runTest {
        var failReconnect = false
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig): LoginResponse {
                if (failReconnect) {
                    throw IOException("timeout")
                }
                return LoginResponse(authenticated = true)
            }

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                if (failReconnect) {
                    throw IOException("timeout")
                }
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                if (failReconnect) {
                    throw IOException("timeout")
                }
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
                    sendAvailable = true,
                    sendDisabledReason = null
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config, "thread-1")
        failReconnect = true
        repository.connect(config, "thread-1")
        val state = repository.state.value

        assertTrue(state.isConnected)
        assertEquals(StreamConnectionState.FAILED, state.connectionState)
        assertEquals("thread-1", state.selectedThreadId)
        assertEquals("thread-1", state.detail?.thread?.threadId)
        assertEquals("gateway_unreachable", state.errorMessage)
    }

    @Test
    fun `sendMessage attempts gateway when detail has generic disabled state`() = runTest {
        val api = object : GatewayApi {
            var sendCalls = 0

            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread2)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ) = ThreadDetail(
                thread = thread2,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = false,
                sendDisabledReason = null
            )

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse {
                sendCalls += 1
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
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-2")
        repository.sendMessage("hello")
        val state = repository.state.value

        assertEquals(1, api.sendCalls)
        assertFalse(state.isSending)
        assertNull(state.errorMessage)
    }

    @Test
    fun `sendMessage refreshes selected detail but still posts when latest detail is disabled`() = runTest {
        var detailCalls = 0
        var sendCalls = 0
        val staleSendableDetail = ThreadDetail(
            thread = thread1,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val latestRunningThread = thread1.copy(
            status = MobileThreadStatus.RUNNING,
            updatedAt = "2026-04-25T20:25:11Z",
            progressSummary = "正在处理新的请求"
        )
        val latestDisabledDetail = ThreadDetail(
            thread = latestRunningThread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = false,
            sendDisabledReason = "线程仍在运行，暂不支持并发发送"
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                return listOf(if (detailCalls >= 1) latestRunningThread else thread1)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                detailCalls += 1
                return if (detailCalls == 1) staleSendableDetail else latestDisabledDetail
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse {
                sendCalls += 1
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
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        val sent = repository.sendMessage("hello")
        val state = repository.state.value

        assertTrue(sent)
        assertEquals(1, sendCalls)
        assertFalse(state.isSending)
        assertEquals(MobileThreadStatus.RUNNING, state.detail?.thread?.status)
        assertEquals(false, state.detail?.sendAvailable)
        assertEquals("hello", state.detail?.recentMessages?.lastOrNull()?.text)
        assertNull(state.errorMessage)
    }

    @Test
    fun `sendMessage treats desktop confirmation timeout as accepted pending confirmation`() = runTest {
        val api = FakeGatewayApi(listOf(thread1)).apply {
            sendResponse = SendMessageResponse(
                accepted = true,
                threadId = "thread-1",
                clientMessageId = "client-1",
                sendPath = "desktop_bridge",
                confirmation = "keystrokes_sent",
                warning = "desktop_send_confirmation_timeout"
            )
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        val confirmed = repository.sendMessage("hello after timeout")
        val state = repository.state.value

        assertTrue(confirmed)
        assertFalse(state.isSending)
        assertNull(state.errorMessage)
        assertEquals(MobileThreadStatus.RUNNING, state.detail?.thread?.status)
        assertEquals(false, state.detail?.sendAvailable)
        assertEquals("hello after timeout", state.detail?.recentMessages?.lastOrNull()?.text)
    }

    @Test
    fun `socket desktop send failure marks optimistic text message failed for retry`() = runTest {
        val api = FakeGatewayApi(listOf(thread1))
        val realtimeClient = FakeRealtimeClient()
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                connectionMode = GatewayConnectionMode.SOCKET
            ),
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()
        repository.sendMessage("会失败的消息")
        advanceUntilIdle()
        val optimisticMessage = requireNotNull(
            repository.state.value.detail?.recentMessages?.lastOrNull()
        )

        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.ThreadStatusChanged(
                eventId = "${optimisticMessage.messageId}:desktop-error",
                threadId = "thread-1",
                status = MobileThreadStatus.ERROR,
                progressSummary = "桌面发送失败: 未找到 Codex Desktop 安装路径",
                needsAttention = true,
                timestamp = "2026-05-02T03:18:00.000Z"
            )
        )
        advanceUntilIdle()

        val failedMessage = repository.state.value.detail?.recentMessages?.lastOrNull()
        assertEquals(ThreadMessageSendState.FAILED, failedMessage?.sendState)
        assertEquals("会失败的消息", failedMessage?.text)
        assertEquals(MobileThreadStatus.ERROR, repository.state.value.detail?.thread?.status)
    }

    @Test
    fun `socket desktop send failure marks optimistic image message failed for retry`() = runTest {
        val api = FakeGatewayApi(listOf(thread1))
        val realtimeClient = FakeRealtimeClient()
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                connectionMode = GatewayConnectionMode.SOCKET
            ),
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()
        repository.setPendingImage(
            PendingImageDraft(
                previewUri = "content://demo/image",
                fileName = "demo.png",
                mimeType = "image/png"
            )
        )
        repository.sendImageMessage(
            text = "这个菜单栏太高了",
            openStream = { _ -> "png-data".byteInputStream() }
        )
        advanceUntilIdle()
        val optimisticMessage = requireNotNull(
            repository.state.value.detail?.recentMessages?.lastOrNull()
        )

        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.ThreadStatusChanged(
                eventId = "${optimisticMessage.messageId}:desktop-error",
                threadId = "thread-1",
                status = MobileThreadStatus.ERROR,
                progressSummary = "桌面发送失败: 未找到 Codex Desktop 安装路径",
                needsAttention = true,
                timestamp = "2026-05-02T03:18:00.000Z"
            )
        )
        advanceUntilIdle()

        val failedMessage = repository.state.value.detail?.recentMessages?.lastOrNull()
        assertEquals("image", failedMessage?.kind)
        assertEquals("这个菜单栏太高了", failedMessage?.text)
        assertEquals(ThreadMessageSendState.FAILED, failedMessage?.sendState)
    }

    @Test
    fun `later desktop send success event keeps retried client message sent`() = runTest {
        val api = FakeGatewayApi(listOf(thread1)).apply {
            threadDetail = ThreadDetail(
                thread = thread1,
                recentMessages = listOf(
                    ThreadMessage(
                        messageId = "client-retry",
                        threadId = "thread-1",
                        role = ThreadMessageRole.USER,
                        kind = "text",
                        text = "重试后成功",
                        timestamp = "2026-05-02T03:18:00.000Z"
                    )
                ),
                recentEvents = listOf(
                    ThreadEvent(
                        eventId = "client-retry:desktop-error",
                        threadId = "thread-1",
                        kind = ThreadEventKind.ERROR,
                        status = MobileThreadStatus.ERROR,
                        text = "桌面发送失败: 未找到 Codex Desktop 安装路径",
                        timestamp = "2026-05-02T03:18:01.000Z"
                    ),
                    ThreadEvent(
                        eventId = "client-retry:desktop",
                        threadId = "thread-1",
                        kind = ThreadEventKind.STATUS_CHANGED,
                        status = MobileThreadStatus.RUNNING,
                        text = "已从手机端发送新消息",
                        timestamp = "2026-05-02T03:18:10.000Z"
                    )
                ),
                sendAvailable = true,
                sendDisabledReason = null
            )
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        val message = repository.state.value.detail?.recentMessages?.single()
        assertEquals("重试后成功", message?.text)
        assertNull(message?.sendState)
    }

    @Test
    fun `sendMessage keeps optimistic detail when refresh after send times out`() = runTest {
        var sendCompleted = false
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                if (sendCompleted) {
                    throw IOException("timeout")
                }
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                if (sendCompleted) {
                    throw IOException("timeout")
                }
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = listOf(
                        ThreadMessage(
                            messageId = "message-1",
                            threadId = threadId,
                            role = ThreadMessageRole.USER,
                            kind = "text",
                            text = "old",
                            timestamp = "2026-04-23T00:00:00Z"
                        )
                    ),
                    recentEvents = emptyList(),
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
                sendCompleted = true
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
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.sendMessage("hello after timeout")
        val state = repository.state.value

        assertFalse(state.isSending)
        assertEquals(null, state.errorMessage)
        assertEquals(MobileThreadStatus.RUNNING, state.detail?.thread?.status)
        assertEquals(false, state.detail?.sendAvailable)
        assertEquals("hello after timeout", state.detail?.recentMessages?.last()?.text)
    }

    @Test
    fun `sendMessage refreshes detail snapshot again after accepted send to show final reply`() = runTest {
        var sendCompleted = false
        var detailCallsAfterSend = 0
        val finalThread = thread1.copy(
            status = MobileThreadStatus.COMPLETED,
            updatedAt = "2099-04-25T18:52:04Z",
            progressSummary = "本轮已完成"
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                if (sendCompleted) {
                    detailCallsAfterSend += 1
                }
                val isFinal = detailCallsAfterSend >= 2
                return ThreadDetail(
                    thread = if (isFinal) finalThread else thread1,
                    recentMessages = listOf(
                        ThreadMessage(
                            messageId = if (isFinal) "assistant-final" else "old",
                            threadId = threadId,
                            role = ThreadMessageRole.ASSISTANT,
                            kind = "text",
                            text = if (isFinal) "66689766" else "旧回复",
                            timestamp = if (isFinal) {
                                "2099-04-25T18:52:04Z"
                            } else {
                                "2026-04-23T00:00:00Z"
                            }
                        )
                    ),
                    recentEvents = emptyList(),
                    sendAvailable = isFinal,
                    sendDisabledReason = null
                )
            }

            override suspend fun getNewThreadMessages(
                config: GatewayConfig,
                threadId: String,
                afterMessageId: String?,
                afterTimestamp: String?,
                limit: Int
            ): ThreadMessagesPage {
                throw AssertionError("refresh after send should use detail snapshot")
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse {
                sendCompleted = true
                return SendMessageResponse(
                    accepted = true,
                    threadId = threadId,
                    clientMessageId = clientMessageId,
                    sendPath = "app_server",
                    confirmation = "turn_started"
                )
            }

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.sendMessage("测试，回复66689766")
        runCurrent()

        assertEquals("测试，回复66689766", repository.state.value.detail?.recentMessages?.last()?.text)

        advanceTimeBy(1_600)
        runCurrent()
        assertEquals("测试，回复66689766", repository.state.value.detail?.recentMessages?.last()?.text)

        advanceTimeBy(4_000)
        advanceUntilIdle()

        val state = repository.state.value
        assertEquals(MobileThreadStatus.COMPLETED, state.detail?.thread?.status)
        assertEquals("66689766", state.detail?.recentMessages?.last()?.text)
        assertEquals(true, state.detail?.sendAvailable)
    }

    @Test
    fun `post send detail snapshot refresh replaces matching optimistic text bubble`() = runTest {
        var detailCallsAfterSend = 0
        var sendCompleted = false
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                if (sendCompleted) {
                    detailCallsAfterSend += 1
                }
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = if (sendCompleted) {
                        listOf(
                            ThreadMessage(
                                messageId = "item-130",
                                threadId = threadId,
                                role = ThreadMessageRole.USER,
                                kind = "text",
                                text = "测试，请回复66999",
                                timestamp = "2026-04-23T00:00:01Z"
                            )
                        )
                    } else {
                        emptyList()
                    },
                    recentEvents = emptyList(),
                    sendAvailable = true,
                    sendDisabledReason = null
                )
            }

            override suspend fun getNewThreadMessages(
                config: GatewayConfig,
                threadId: String,
                afterMessageId: String?,
                afterTimestamp: String?,
                limit: Int
            ): ThreadMessagesPage {
                throw AssertionError("post send refresh should use detail snapshot")
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse {
                sendCompleted = true
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
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { 1_776_902_400_000L }
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.sendMessage("测试，请回复66999")

        assertEquals(1, repository.state.value.detail?.recentMessages?.size)

        advanceTimeBy(1_600)
        runCurrent()

        val messages = repository.state.value.detail?.recentMessages.orEmpty()
        assertTrue(detailCallsAfterSend >= 1)
        assertEquals(1, messages.size)
        assertEquals("item-130", messages.single().messageId)
        assertEquals("测试，请回复66999", messages.single().text)
        assertNull(messages.single().sendState)
    }

    @Test
    fun `send success does not re-add optimistic text after realtime confirmation replaces it`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val sendGate = CompletableDeferred<SendMessageResponse>()
        var capturedClientMessageId: String? = null
        val confirmedMessage = ThreadMessage(
            messageId = "item-confirmed",
            threadId = "thread-1",
            role = ThreadMessageRole.USER,
            kind = "text",
            text = "typed now",
            timestamp = "2026-05-01T11:45:27.390Z"
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
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
                capturedClientMessageId = clientMessageId
                return sendGate.await().copy(
                    threadId = threadId,
                    clientMessageId = clientMessageId
                )
            }

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                connectionMode = GatewayConnectionMode.SOCKET
            ),
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { 1_777_635_927_000L }
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        repository.sendMessage("typed now")
        runCurrent()

        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.MessagesAppended(
                eventId = "event-message-confirmed",
                threadId = "thread-1",
                messages = listOf(confirmedMessage),
                timestamp = confirmedMessage.timestamp
            )
        )
        runCurrent()

        assertEquals(
            listOf("item-confirmed"),
            repository.state.value.detail!!.recentMessages.map { it.messageId }
        )

        sendGate.complete(
            SendMessageResponse(
                accepted = true,
                threadId = "thread-1",
                clientMessageId = capturedClientMessageId ?: "client-missing",
                sendPath = "desktop_bridge",
                confirmation = "observed"
            )
        )
        runCurrent()

        val matchingMessages = repository.state.value.detail!!.recentMessages.filter {
            it.role == ThreadMessageRole.USER && it.text == "typed now"
        }
        assertEquals(1, matchingMessages.size)
        assertEquals("item-confirmed", matchingMessages.single().messageId)
    }

    @Test
    fun `late realtime confirmation still replaces optimistic text bubble after two minutes`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val sendGate = CompletableDeferred<SendMessageResponse>()
        var capturedClientMessageId: String? = null
        val confirmedMessage = ThreadMessage(
            messageId = "event:999:2026-05-02T10:59:04.000Z:text",
            threadId = "thread-1",
            role = ThreadMessageRole.USER,
            kind = "text",
            text = "测试，回复我一百个字的一段话",
            timestamp = "2026-05-02T10:59:04.000Z"
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
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
                capturedClientMessageId = clientMessageId
                return sendGate.await().copy(
                    threadId = threadId,
                    clientMessageId = clientMessageId
                )
            }

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                connectionMode = GatewayConnectionMode.SOCKET
            ),
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { Instant.parse("2026-05-02T10:57:00.000Z").toEpochMilli() }
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        repository.sendMessage("测试，回复我一百个字的一段话")
        runCurrent()

        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.MessagesAppended(
                eventId = "event-message-confirmed-late",
                threadId = "thread-1",
                messages = listOf(confirmedMessage),
                timestamp = confirmedMessage.timestamp
            )
        )
        runCurrent()

        sendGate.complete(
            SendMessageResponse(
                accepted = true,
                threadId = "thread-1",
                clientMessageId = capturedClientMessageId ?: "client-missing",
                sendPath = "desktop_bridge",
                confirmation = "observed"
            )
        )
        runCurrent()

        val matchingMessages = repository.state.value.detail!!.recentMessages.filter {
            it.role == ThreadMessageRole.USER && it.text == "测试，回复我一百个字的一段话"
        }
        assertEquals(1, matchingMessages.size)
        assertEquals("event:999:2026-05-02T10:59:04.000Z:text", matchingMessages.single().messageId)
    }

    @Test
    fun `realtime append does not duplicate confirmed user message already present in detail snapshot`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val realtimeConfirmedMessage = ThreadMessage(
            messageId = "event:435:2026-05-02T13:30:48.752Z:text",
            threadId = "thread-1",
            role = ThreadMessageRole.USER,
            kind = "text",
            text = "测试，回复一个100字左右的话",
            timestamp = "2026-05-02T13:30:48.752Z"
        )
        val snapshotConfirmedMessage = realtimeConfirmedMessage.copy(
            messageId = "item-435"
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = listOf(snapshotConfirmedMessage),
                    recentEvents = emptyList(),
                    sendAvailable = true,
                    sendDisabledReason = null
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                connectionMode = GatewayConnectionMode.SOCKET
            ),
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.MessagesAppended(
                eventId = "event-message-confirmed",
                threadId = "thread-1",
                messages = listOf(realtimeConfirmedMessage),
                timestamp = realtimeConfirmedMessage.timestamp
            )
        )
        advanceUntilIdle()

        val matchingMessages = repository.state.value.detail!!.recentMessages.filter {
            it.role == ThreadMessageRole.USER &&
                it.text == "测试，回复一个100字左右的话"
        }
        assertEquals(1, matchingMessages.size)
        assertEquals("event:435:2026-05-02T13:30:48.752Z:text", matchingMessages.single().messageId)
    }

    @Test
    fun `realtime append does not duplicate confirmed assistant preview already present in detail snapshot`() = runTest {
        val realtimeClient = FakeRealtimeClient()
        val snapshotAssistantMessage = ThreadMessage(
            messageId = "preview:459:2026-05-02T14:14:33.926Z",
            threadId = "thread-1",
            role = ThreadMessageRole.ASSISTANT,
            kind = "text",
            text = "程序员去相亲，女生问：“你有房吗？”他说：“有，云上三台。”女生又问：“有车吗？”他说：“有，数据库里好几辆。”女生皱眉：“那你会做饭吗？”程序员点头：“会，昨天刚把电饭煲重启过，问题暂时解决了。”",
            timestamp = "2026-05-02T14:14:33.926Z"
        )
        val realtimeAssistantMessage = snapshotAssistantMessage.copy(
            messageId = "preview:1:2026-05-02T14:14:33.926Z"
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = listOf(snapshotAssistantMessage),
                    recentEvents = emptyList(),
                    sendAvailable = true,
                    sendDisabledReason = null
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                connectionMode = GatewayConnectionMode.SOCKET
            ),
            realtimeClient = realtimeClient,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        realtimeClient.lastConnection!!.emit(
            GatewayRealtimeEvent.MessagesAppended(
                eventId = "event-message-assistant-preview",
                threadId = "thread-1",
                messages = listOf(realtimeAssistantMessage),
                timestamp = realtimeAssistantMessage.timestamp
            )
        )
        advanceUntilIdle()

        val matchingMessages = repository.state.value.detail!!.recentMessages.filter {
            it.role == ThreadMessageRole.ASSISTANT &&
                it.timestamp == "2026-05-02T14:14:33.926Z" &&
                it.text == snapshotAssistantMessage.text
        }
        assertEquals(1, matchingMessages.size)
        assertEquals("preview:1:2026-05-02T14:14:33.926Z", matchingMessages.single().messageId)
    }

    @Test
    fun `stream refresh timeout keeps current detail without crashing repository`() = runTest {
        var failStreamRefresh = false
        val sseClient = ControllableGatewaySseClient()
        val stableDetail = ThreadDetail(
            thread = thread1.copy(
                status = MobileThreadStatus.WAITING_INPUT,
                updatedAt = "2026-04-24T03:59:00Z",
                progressSummary = "等待用户输入"
            ),
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "message-1",
                    threadId = "thread-1",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "请继续补充需求",
                    timestamp = "2026-04-24T03:59:00Z"
                )
            ),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                if (failStreamRefresh) {
                    throw IOException("app_server_request_timeout:thread/list")
                }
                return listOf(thread1)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail = stableDetail

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = sseClient,
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        val beforeRefresh = repository.state.value

        failStreamRefresh = true
        sseClient.emit(
            GatewayStreamEvent(
                type = "thread.updated",
                threadId = "thread-1",
                timestamp = "2026-04-24T04:00:00Z"
            )
        )
        advanceUntilIdle()

        val state = repository.state.value
        assertTrue(state.isConnected)
        assertEquals(beforeRefresh.selectedThreadId, state.selectedThreadId)
        assertEquals(beforeRefresh.detail, state.detail)
        assertEquals(beforeRefresh.threads, state.threads)
    }

    @Test
    fun `stale stream refresh does not overwrite thread selected while refresh is pending`() = runTest {
        val releaseStreamRefresh = CompletableDeferred<Unit>()
        var streamRefreshStarted = false
        val sseClient = ControllableGatewaySseClient()
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
                if (streamRefreshStarted) {
                    releaseStreamRefresh.await()
                }
                return listOf(thread1, thread2)
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                val thread = if (threadId == "thread-2") thread2 else thread1
                return ThreadDetail(
                    thread = thread,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
                    sendAvailable = true,
                    sendDisabledReason = null
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = sseClient,
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        streamRefreshStarted = true
        sseClient.emit(
            GatewayStreamEvent(
                type = "thread.updated",
                threadId = "thread-1",
                timestamp = "2026-04-24T04:00:00Z"
            )
        )
        runCurrent()

        repository.selectThread("thread-2")
        assertEquals("thread-2", repository.state.value.selectedThreadId)

        releaseStreamRefresh.complete(Unit)
        advanceUntilIdle()

        val state = repository.state.value
        assertEquals("thread-2", state.selectedThreadId)
        assertEquals("thread-2", state.detail?.thread?.threadId)
    }

    @Test
    fun `selectThread displays preview before full detail finishes`() = runTest {
        val releaseFullDetail = CompletableDeferred<Unit>()
        var previewCalls = 0
        var thread2DetailStarted = false
        val previewDetail = ThreadDetail(
            thread = thread2,
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "preview-message",
                    threadId = "thread-2",
                    role = ThreadMessageRole.USER,
                    kind = "text",
                    text = "先展示预览消息",
                    timestamp = "2026-04-23T00:01:00Z"
                )
            ),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val fullDetail = ThreadDetail(
            thread = thread2.copy(progressSummary = "完整详情已补齐"),
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "full-message",
                    threadId = "thread-2",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "完整详情消息",
                    timestamp = "2026-04-23T00:02:00Z"
                )
            ),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1, thread2)

            override suspend fun getThreadPreview(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                previewCalls += 1
                return previewDetail
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                if (threadId == "thread-2") {
                    thread2DetailStarted = true
                    releaseFullDetail.await()
                    return fullDetail
                }
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
                    sendAvailable = true,
                    sendDisabledReason = null
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        val selectJob = launch { repository.selectThread("thread-2") }
        runCurrent()

        assertEquals(1, previewCalls)
        assertTrue(thread2DetailStarted)
        assertEquals("thread-2", repository.state.value.selectedThreadId)
        assertEquals("先展示预览消息", repository.state.value.detail?.recentMessages?.firstOrNull()?.text)

        releaseFullDetail.complete(Unit)
        advanceUntilIdle()

        assertEquals("完整详情消息", repository.state.value.detail?.recentMessages?.lastOrNull()?.text)
        selectJob.cancel()
    }

    @Test
    fun `selectThread displays cached shell before preview finishes`() = runTest {
        val releasePreview = CompletableDeferred<Unit>()
        val releaseFullDetail = CompletableDeferred<Unit>()
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1, thread2)

            override suspend fun getThreadPreview(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                releasePreview.await()
                return ThreadDetail(
                    thread = thread2.copy(progressSummary = "预览已到达"),
                    recentMessages = listOf(
                        ThreadMessage(
                            messageId = "preview-message",
                            threadId = "thread-2",
                            role = ThreadMessageRole.USER,
                            kind = "text",
                            text = "预览消息",
                            timestamp = "2026-04-23T00:01:00Z"
                        )
                    ),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                if (threadId == "thread-2") {
                    releaseFullDetail.await()
                }
                return ThreadDetail(
                    thread = if (threadId == "thread-2") {
                        thread2.copy(progressSummary = "完整详情已到达")
                    } else {
                        thread1
                    },
                    recentMessages = emptyList(),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        val selectJob = launch { repository.selectThread("thread-2") }
        runCurrent()

        assertEquals("thread-2", repository.state.value.selectedThreadId)
        assertEquals("thread-2", repository.state.value.detail?.thread?.threadId)
        assertEquals("waiting input", repository.state.value.detail?.thread?.progressSummary)
        assertEquals(emptyList<ThreadMessage>(), repository.state.value.detail?.recentMessages)

        releasePreview.complete(Unit)
        advanceUntilIdle()

        assertEquals("预览消息", repository.state.value.detail?.recentMessages?.firstOrNull()?.text)

        releaseFullDetail.complete(Unit)
        advanceUntilIdle()

        assertEquals("完整详情已到达", repository.state.value.detail?.thread?.progressSummary)
        selectJob.cancel()
    }

    @Test
    fun `selectThread displays cached detail immediately then refreshes detail snapshot`() = runTest {
        val releaseFullDetail = CompletableDeferred<Unit>()
        var fullDetailCalls = 0
        var previewCalls = 0
        var incrementalCalls = 0
        val cachedDetail = ThreadDetail(
            thread = thread2.copy(progressSummary = "缓存详情"),
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "cached-message",
                    threadId = "thread-2",
                    role = ThreadMessageRole.ASSISTANT,
                    kind = "text",
                    text = "缓存消息",
                    timestamp = "2026-04-23T00:01:00Z"
                )
            ),
            recentEvents = emptyList(),
            sendAvailable = true
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1, thread2)

            override suspend fun getThreadPreview(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                previewCalls += 1
                throw AssertionError("cached detail refresh should not use preview")
            }

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                fullDetailCalls += 1
                releaseFullDetail.await()
                return ThreadDetail(
                    thread = thread2.copy(progressSummary = "完整详情已刷新"),
                    recentMessages = listOf(
                        ThreadMessage(
                            messageId = "new-message",
                            threadId = threadId,
                            role = ThreadMessageRole.ASSISTANT,
                            kind = "text",
                            text = "新增消息",
                            timestamp = "2026-04-23T00:02:00Z"
                        )
                    ),
                    recentEvents = listOf(
                        ThreadEvent(
                            eventId = "event-completed",
                            threadId = threadId,
                            kind = ThreadEventKind.TURN_COMPLETED,
                            status = MobileThreadStatus.COMPLETED,
                            text = "本轮已完成",
                            timestamp = "2026-04-23T00:02:30Z"
                        )
                    ),
                    sendAvailable = true
                )
            }

            override suspend fun getNewThreadMessages(
                config: GatewayConfig,
                threadId: String,
                afterMessageId: String?,
                afterTimestamp: String?,
                limit: Int
            ): ThreadMessagesPage {
                incrementalCalls += 1
                throw AssertionError("cached detail refresh should use detail snapshot")
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val sessionStore = InMemoryGatewaySessionStore(
            savedConfig = config,
            lastOpenedThreadId = "thread-2",
            cachedThreads = listOf(thread1, thread2),
            cachedDetails = mutableMapOf("thread-2" to cachedDetail)
        )
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = sessionStore,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val selectJob = launch { repository.selectThread("thread-2") }
        runCurrent()

        assertEquals("缓存详情", repository.state.value.detail?.thread?.progressSummary)
        assertEquals(listOf("缓存消息"), repository.state.value.detail?.recentMessages?.map { it.text })
        assertEquals(1, fullDetailCalls)
        assertEquals(0, previewCalls)
        assertEquals(0, incrementalCalls)

        releaseFullDetail.complete(Unit)
        advanceUntilIdle()

        assertEquals("完整详情已刷新", repository.state.value.detail?.thread?.progressSummary)
        assertEquals(
            listOf("缓存消息", "新增消息"),
            repository.state.value.detail?.recentMessages?.map { it.text }
        )
        assertEquals("event-completed", repository.state.value.detail?.recentEvents?.single()?.eventId)
        assertEquals(repository.state.value.detail, sessionStore.cachedDetails["thread-2"])
        selectJob.cancel()
    }

    @Test
    fun `loadOlderMessages prepends a history page before current detail messages`() = runTest {
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = listOf(
                        historyMessage(3),
                        historyMessage(4)
                    ),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun getThreadMessages(
                config: GatewayConfig,
                threadId: String,
                beforeMessageId: String?,
                beforeTimestamp: String?,
                limit: Int
            ): ThreadMessagesPage {
                assertEquals("message-3", beforeMessageId)
                assertEquals("2026-04-23T00:03:00Z", beforeTimestamp)
                assertEquals(20, limit)
                return ThreadMessagesPage(
                    messages = listOf(
                        historyMessage(1),
                        historyMessage(2)
                    ),
                    nextCursor = null
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        assertEquals(listOf("消息 3", "消息 4"), repository.state.value.detail?.recentMessages?.map { it.text })
        assertTrue(repository.state.value.canLoadOlderMessages)

        repository.loadOlderMessages()
        advanceUntilIdle()

        assertEquals(
            listOf("消息 1", "消息 2", "消息 3", "消息 4"),
            repository.state.value.detail?.recentMessages?.map { it.text }
        )
        assertFalse(repository.state.value.isLoadingOlderMessages)
        assertFalse(repository.state.value.canLoadOlderMessages)
    }

    @Test
    fun `refreshThreadDetail keeps history exhausted after older pagination reaches the start`() = runTest {
        val localConfig = GatewayConfig("http://gateway", "history-exhausted-token")
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = listOf(historyMessage(3), historyMessage(4)),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun getThreadMessages(
                config: GatewayConfig,
                threadId: String,
                beforeMessageId: String?,
                beforeTimestamp: String?,
                limit: Int
            ): ThreadMessagesPage {
                return ThreadMessagesPage(
                    messages = listOf(historyMessage(1), historyMessage(2)),
                    nextCursor = null
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = localConfig),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = localConfig, requestedThreadId = "thread-1")
        advanceUntilIdle()
        repository.loadOlderMessages()
        advanceUntilIdle()

        assertFalse(repository.state.value.canLoadOlderMessages)

        val refreshJob = launch { repository.refreshThreadDetail("thread-1") }
        advanceUntilIdle()

        assertTrue(refreshJob.isCompleted)
        assertEquals(
            listOf("消息 1", "消息 2", "消息 3", "消息 4"),
            repository.state.value.detail?.recentMessages?.map { it.text }
        )
        assertFalse(repository.state.value.canLoadOlderMessages)
    }

    @Test
    fun `refreshThreadDetail preserves loaded messages after an optimistic local attachment`() = runTest {
        val localConfig = GatewayConfig("http://gateway", "optimistic-gap-token")
        val optimisticAttachment = ThreadMessage(
            messageId = "client-local-file",
            threadId = "thread-1",
            role = ThreadMessageRole.USER,
            kind = "file",
            text = "本地附件",
            fileName = "diagnostics.zip",
            timestamp = "2026-04-23T00:02:00Z"
        )
        val cachedDetail = ThreadDetail(
            thread = thread1,
            recentMessages = listOf(
                historyMessage(1),
                optimisticAttachment,
                historyMessage(3),
                historyMessage(4)
            ),
            recentEvents = emptyList(),
            sendAvailable = true
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = listOf(historyMessage(5)),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = localConfig,
                lastOpenedThreadId = "thread-1",
                cachedThreads = listOf(thread1),
                cachedDetails = mutableMapOf("thread-1" to cachedDetail)
            ),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.refreshThreadDetail("thread-1")
        advanceUntilIdle()

        assertEquals(
            listOf("消息 1", "本地附件", "消息 3", "消息 4", "消息 5"),
            repository.state.value.detail?.recentMessages?.map { it.text }
        )
    }

    @Test
    fun `refreshThreadDetail backfills a cached gap after an optimistic local attachment`() = runTest {
        val localConfig = GatewayConfig("http://gateway", "optimistic-backfill-token")
        val optimisticAttachment = ThreadMessage(
            messageId = "client-local-file",
            threadId = "thread-1",
            role = ThreadMessageRole.USER,
            kind = "file",
            text = "本地附件",
            fileName = "diagnostics.zip",
            timestamp = "2026-04-23T00:02:00Z"
        )
        val cachedDetail = ThreadDetail(
            thread = thread1,
            recentMessages = listOf(
                historyMessage(1),
                optimisticAttachment,
                historyMessage(5)
            ),
            recentEvents = emptyList(),
            sendAvailable = true
        )
        var backfillCalls = 0
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = listOf(historyMessage(5)),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun getNewThreadMessages(
                config: GatewayConfig,
                threadId: String,
                afterMessageId: String?,
                afterTimestamp: String?,
                limit: Int
            ): ThreadMessagesPage {
                backfillCalls += 1
                assertEquals("client-local-file", afterMessageId)
                assertEquals("2026-04-23T00:02:00Z", afterTimestamp)
                assertEquals(20, limit)
                return ThreadMessagesPage(
                    messages = listOf(historyMessage(3), historyMessage(4), historyMessage(5)),
                    nextCursor = "message-5"
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = localConfig,
                lastOpenedThreadId = "thread-1",
                cachedThreads = listOf(thread1),
                cachedDetails = mutableMapOf("thread-1" to cachedDetail)
            ),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.refreshThreadDetail("thread-1")
        advanceUntilIdle()

        assertEquals(1, backfillCalls)
        assertEquals(
            listOf("消息 1", "本地附件", "消息 3", "消息 4", "消息 5"),
            repository.state.value.detail?.recentMessages?.map { it.text }
        )
    }

    @Test
    fun `refreshThreadDetail does not finish an older messages request that is still in flight`() = runTest {
        val localConfig = GatewayConfig("http://gateway", "older-refresh-race-token")
        val releaseOlderMessages = CompletableDeferred<Unit>()
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ): ThreadDetail {
                return ThreadDetail(
                    thread = thread1,
                    recentMessages = listOf(historyMessage(3), historyMessage(4)),
                    recentEvents = emptyList(),
                    sendAvailable = true
                )
            }

            override suspend fun getThreadMessages(
                config: GatewayConfig,
                threadId: String,
                beforeMessageId: String?,
                beforeTimestamp: String?,
                limit: Int
            ): ThreadMessagesPage {
                releaseOlderMessages.await()
                return ThreadMessagesPage(
                    messages = listOf(historyMessage(1), historyMessage(2)),
                    nextCursor = null
                )
            }

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = localConfig),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = localConfig, requestedThreadId = "thread-1")
        advanceUntilIdle()
        val olderJob = launch { repository.loadOlderMessages() }
        runCurrent()

        assertTrue(repository.state.value.isLoadingOlderMessages)

        val refreshJob = launch { repository.refreshThreadDetail("thread-1") }
        runCurrent()

        assertTrue(refreshJob.isCompleted)
        assertTrue(repository.state.value.isLoadingOlderMessages)

        releaseOlderMessages.complete(Unit)
        advanceUntilIdle()

        assertFalse(repository.state.value.isLoadingOlderMessages)
        assertFalse(repository.state.value.canLoadOlderMessages)
        assertEquals(
            listOf("消息 1", "消息 2", "消息 3", "消息 4"),
            repository.state.value.detail?.recentMessages?.map { it.text }
        )
        olderJob.cancel()
    }

    @Test
    fun `sendImageMessage refreshes detail and clears pending attachment`() = runTest {
        val api = FakeGatewayApi(listOf(thread1))
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.setPendingImage(
            PendingImageDraft(
                previewUri = "content://demo/image",
                fileName = "demo.png",
                mimeType = "image/png"
            )
        )
        repository.sendImageMessage(
            text = "请分析这张图",
            openStream = { _ -> "png-data".byteInputStream() }
        )

        val state = repository.state.value
        assertFalse(state.isSending)
        assertEquals(null, state.pendingImageDraft)
        assertEquals("请分析这张图", api.lastSentImageText)
        assertEquals("demo.png", api.lastSentImageName)
    }

    @Test
    fun `pending image and file drafts can coexist`() = runTest {
        val repository = ThreadRepository(
            api = FakeGatewayApi(listOf(thread1)),
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val imageDraft = PendingImageDraft(
            previewUri = "content://demo/image",
            fileName = "demo.png",
            mimeType = "image/png"
        )
        val fileDraft = PendingFileDraft(
            previewUri = "content://demo/file",
            fileName = "diagnostics.zip",
            mimeType = "application/zip"
        )

        repository.setPendingFiles(listOf(fileDraft))
        repository.addPendingImages(listOf(imageDraft))

        assertEquals(listOf(imageDraft), repository.state.value.pendingImageDrafts)
        assertEquals(listOf(fileDraft), repository.state.value.pendingFileDrafts)
    }

    @Test
    fun `sendAttachmentMessage sends image and file drafts together`() = runTest {
        val api = FakeGatewayApi(listOf(thread1))
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val imageDraft = PendingImageDraft(
            previewUri = "content://demo/image",
            fileName = "demo.png",
            mimeType = "image/png"
        )
        val fileDraft = PendingFileDraft(
            previewUri = "content://demo/file",
            fileName = "diagnostics.zip",
            mimeType = "application/zip"
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.addPendingImages(listOf(imageDraft))
        repository.addPendingFiles(listOf(fileDraft))
        val accepted = repository.sendAttachmentMessage(
            text = "一起分析",
            openImageStream = { _ -> "png-data".byteInputStream() },
            openFileStream = { _ -> "zip-data".byteInputStream() }
        )
        advanceUntilIdle()

        assertTrue(accepted)
        assertEquals(1, api.sentAttachmentBatchCalls)
        assertEquals(listOf("demo.png"), api.sentAttachmentImageNames)
        assertEquals(listOf("diagnostics.zip"), api.sentAttachmentFileNames)
        assertEquals("一起分析", api.lastSentAttachmentText)
        assertEquals(emptyList<PendingImageDraft>(), repository.state.value.pendingImageDrafts)
        assertEquals(emptyList<PendingFileDraft>(), repository.state.value.pendingFileDrafts)
        assertEquals(
            listOf("image", "file"),
            repository.state.value.detail?.recentMessages?.takeLast(2)?.map { it.kind }
        )
    }

    @Test
    fun `sendImageMessage shows pending image bubble before gateway send returns`() = runTest {
        val releaseSend = CompletableDeferred<Unit>()
        var sendStarted = false
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ) = ThreadDetail(
                thread = thread1,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = true,
                sendDisabledReason = null
            )

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessages(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                images: List<ImageUploadSource>
            ): SendMessageResponse {
                sendStarted = true
                releaseSend.await()
                return SendMessageResponse(
                    accepted = true,
                    threadId = threadId,
                    clientMessageId = clientMessageId,
                    sendPath = "desktop_bridge",
                    confirmation = "keystrokes_sent"
                )
            }
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val draft = PendingImageDraft(
            previewUri = "content://demo/image",
            fileName = "demo.png",
            mimeType = "image/png"
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.setPendingImage(draft)
        var accepted = false
        val sendJob = launch {
            accepted = repository.sendImageMessage(
                text = "看图",
                openStream = { _ -> "png-data".byteInputStream() }
            )
        }
        runCurrent()

        val pendingMessage = repository.state.value.detail?.recentMessages?.single()
        assertTrue(sendStarted)
        assertFalse(repository.state.value.isSending)
        assertEquals(emptyList<PendingImageDraft>(), repository.state.value.pendingImageDrafts)
        assertEquals("image", pendingMessage?.kind)
        assertEquals("看图", pendingMessage?.text)
        assertEquals("content://demo/image", pendingMessage?.imageUrl)
        assertEquals("demo.png", pendingMessage?.fileName)
        assertEquals("image/png", pendingMessage?.mimeType)
        assertEquals(ThreadMessageSendState.SENDING, pendingMessage?.sendState)

        releaseSend.complete(Unit)
        advanceUntilIdle()
        assertTrue(accepted)
        sendJob.cancel()
    }

    @Test
    fun `sendImageMessage sends all pending images in one batch and clears attachment list`() = runTest {
        val api = FakeGatewayApi(listOf(thread1))
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val first = PendingImageDraft(
            previewUri = "content://demo/first",
            fileName = "first.png",
            mimeType = "image/png"
        )
        val second = PendingImageDraft(
            previewUri = "content://demo/second",
            fileName = "second.jpg",
            mimeType = "image/jpeg"
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.setPendingImages(listOf(first, second))
        repository.sendImageMessage(
            text = "两张图",
            openStream = { draft -> draft.fileName.byteInputStream() }
        )

        val state = repository.state.value
        assertFalse(state.isSending)
        assertEquals(emptyList<PendingImageDraft>(), state.pendingImageDrafts)
        assertEquals(1, api.sentImageBatchCalls)
        assertEquals(listOf("first.png", "second.jpg"), api.sentImageNames)
        assertEquals(listOf("两张图"), api.sentImageTexts)
    }

    @Test
    fun `sendFileMessage sends all pending files in one batch and clears attachment list`() = runTest {
        val api = FakeGatewayApi(listOf(thread1))
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val first = PendingFileDraft(
            previewUri = "content://demo/diagnostics",
            fileName = "diagnostics.zip",
            mimeType = "application/zip"
        )
        val second = PendingFileDraft(
            previewUri = "content://demo/gateway-log",
            fileName = "gateway.log",
            mimeType = "text/plain"
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.setPendingFiles(listOf(first, second))
        repository.sendFileMessage(
            text = "诊断日志",
            openStream = { draft -> draft.fileName.byteInputStream() }
        )
        advanceUntilIdle()

        val state = repository.state.value
        val fileMessages = state.detail?.recentMessages.orEmpty().filter { it.kind == "file" }
        assertFalse(state.isSending)
        assertEquals(emptyList<PendingFileDraft>(), state.pendingFileDrafts)
        assertEquals(1, api.sentFileBatchCalls)
        assertEquals(listOf("diagnostics.zip", "gateway.log"), api.sentFileNames)
        assertEquals(listOf("诊断日志"), api.sentFileTexts)
        assertEquals(listOf("diagnostics.zip", "gateway.log"), fileMessages.map { it.fileName })
        assertEquals(listOf("application/zip", "text/plain"), fileMessages.map { it.mimeType })
    }

    @Test
    fun `refresh merges optimistic file attachment with later confirmed text timestamp`() = runTest {
        val cachedDetail = ThreadDetail(
            thread = thread1,
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "client-file-1",
                    threadId = "thread-1",
                    role = ThreadMessageRole.USER,
                    kind = "file",
                    text = "route test",
                    fileUrl = "content://demo/route-test",
                    fileName = "route-test.txt",
                    mimeType = "text/plain",
                    timestamp = "2026-04-29T14:19:00Z"
                )
            ),
            recentEvents = emptyList(),
            sendAvailable = true,
            sendDisabledReason = null
        )
        val confirmedDetail = cachedDetail.copy(
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "event:473:2026-04-29T14:27:03.785Z:text",
                    threadId = "thread-1",
                    role = ThreadMessageRole.USER,
                    kind = "text",
                    text = "route test",
                    timestamp = "2026-04-29T14:27:03.785Z"
                )
            )
        )
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ) = confirmedDetail

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(
                savedConfig = config,
                lastOpenedThreadId = "thread-1",
                cachedThreads = listOf(thread1),
                cachedDetails = mutableMapOf("thread-1" to cachedDetail)
            ),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        advanceUntilIdle()

        val messages = repository.state.value.detail?.recentMessages.orEmpty()
        assertEquals(1, messages.size)
        val mergedMessage = messages.single()
        assertEquals("event:473:2026-04-29T14:27:03.785Z:text", mergedMessage.messageId)
        assertEquals("file", mergedMessage.kind)
        assertEquals("route test", mergedMessage.text)
        assertEquals("route-test.txt", mergedMessage.fileName)
        assertEquals("content://demo/route-test", mergedMessage.fileUrl)
        assertEquals("text/plain", mergedMessage.mimeType)
        assertEquals("2026-04-29T14:27:03.785Z", mergedMessage.timestamp)
    }

    @Test
    fun `failed optimistic image message can be retried from its bubble`() = runTest {
        var failNextSend = true
        val releaseRetry = CompletableDeferred<Unit>()
        val sentImageNames = mutableListOf<String>()
        val sentImageBytes = mutableListOf<String>()
        val retryDrafts = mutableListOf<PendingImageDraft>()
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ) = ThreadDetail(
                thread = thread1,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = true,
                sendDisabledReason = null
            )

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessages(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                images: List<ImageUploadSource>
            ): SendMessageResponse {
                images.forEach { image ->
                    sentImageNames += image.fileName
                    sentImageBytes += image.openStream().use { it.readBytes().decodeToString() }
                }
                if (failNextSend) {
                    failNextSend = false
                    throw IOException("image desktop bridge unavailable")
                }
                releaseRetry.await()
                return SendMessageResponse(
                    accepted = true,
                    threadId = threadId,
                    clientMessageId = clientMessageId,
                    sendPath = "desktop_bridge",
                    confirmation = "keystrokes_sent"
                )
            }
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.setPendingImage(
            PendingImageDraft(
                previewUri = "content://demo/image",
                fileName = "demo.png",
                mimeType = "image/png"
            )
        )
        repository.sendImageMessage(
            text = "这张图失败后重发",
            openStream = { _ -> "first-image-bytes".byteInputStream() }
        )
        advanceUntilIdle()

        val failedMessage = repository.state.value.detail?.recentMessages?.single()
        assertEquals(ThreadMessageSendState.FAILED, failedMessage?.sendState)

        val retried = repository.retryFailedMessage(requireNotNull(failedMessage).messageId) { draft ->
            retryDrafts += draft
            "retry-image-bytes".byteInputStream()
        }
        runCurrent()

        val retryingMessage = repository.state.value.detail?.recentMessages?.single()
        assertTrue(retried)
        assertFalse(repository.state.value.isSending)
        assertEquals(listOf("demo.png", "demo.png"), sentImageNames)
        assertEquals(listOf("first-image-bytes", "retry-image-bytes"), sentImageBytes)
        assertEquals(listOf("content://demo/image"), retryDrafts.map { it.previewUri })
        assertEquals(ThreadMessageSendState.SENDING, retryingMessage?.sendState)

        releaseRetry.complete(Unit)
        advanceUntilIdle()
        val sentMessage = repository.state.value.detail?.recentMessages?.single()
        assertNull(sentMessage?.sendState)
        assertNull(repository.state.value.errorMessage)
    }

    @Test
    fun `failed optimistic file message can be retried from its bubble`() = runTest {
        var failNextSend = true
        val releaseRetry = CompletableDeferred<Unit>()
        val sentFileNames = mutableListOf<String>()
        val sentFileBytes = mutableListOf<String>()
        val retryDrafts = mutableListOf<PendingFileDraft>()
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ) = ThreadDetail(
                thread = thread1,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = true,
                sendDisabledReason = null
            )

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendFileMessages(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                files: List<FileUploadSource>
            ): SendMessageResponse {
                files.forEach { file ->
                    sentFileNames += file.fileName
                    sentFileBytes += file.openStream().use { it.readBytes().decodeToString() }
                }
                if (failNextSend) {
                    failNextSend = false
                    throw IOException("file desktop bridge unavailable")
                }
                releaseRetry.await()
                return SendMessageResponse(
                    accepted = true,
                    threadId = threadId,
                    clientMessageId = clientMessageId,
                    sendPath = "desktop_bridge",
                    confirmation = "keystrokes_sent"
                )
            }
        }
        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.setPendingFiles(
            listOf(
                PendingFileDraft(
                    previewUri = "content://demo/diagnostics",
                    fileName = "diagnostics.zip",
                    mimeType = "application/zip"
                )
            )
        )
        repository.sendFileMessage(
            text = "日志文件失败后重发",
            openStream = { _ -> "first-file-bytes".byteInputStream() }
        )
        advanceUntilIdle()

        val failedMessage = repository.state.value.detail?.recentMessages?.single()
        assertEquals(ThreadMessageSendState.FAILED, failedMessage?.sendState)

        val retried = repository.retryFailedMessage(
            messageId = requireNotNull(failedMessage).messageId,
            openFileStream = { draft ->
                retryDrafts += draft
                "retry-file-bytes".byteInputStream()
            }
        )
        runCurrent()

        val retryingMessage = repository.state.value.detail?.recentMessages?.single()
        assertTrue(retried)
        assertFalse(repository.state.value.isSending)
        assertEquals(listOf("diagnostics.zip", "diagnostics.zip"), sentFileNames)
        assertEquals(listOf("first-file-bytes", "retry-file-bytes"), sentFileBytes)
        assertEquals(listOf("content://demo/diagnostics"), retryDrafts.map { it.previewUri })
        assertEquals(ThreadMessageSendState.SENDING, retryingMessage?.sendState)

        releaseRetry.complete(Unit)
        advanceUntilIdle()
        val sentMessage = repository.state.value.detail?.recentMessages?.single()
        assertNull(sentMessage?.sendState)
        assertNull(repository.state.value.errorMessage)
    }

    @Test
    fun `sendImageMessage keeps thread in running state when refreshed detail is still stale`() = runTest {
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig) = listOf(thread1)

            override suspend fun getThreadDetail(
                config: GatewayConfig,
                threadId: String
            ) = ThreadDetail(
                thread = thread1,
                recentMessages = emptyList(),
                recentEvents = emptyList(),
                sendAvailable = true,
                sendDisabledReason = null
            )

            override suspend fun sendMessage(
                config: GatewayConfig,
                threadId: String,
                text: String,
                clientMessageId: String
            ): SendMessageResponse = throw IllegalStateException("unused")

            override suspend fun sendImageMessage(
                config: GatewayConfig,
                threadId: String,
                text: String?,
                clientMessageId: String,
                image: ImageUploadSource
            ) = SendMessageResponse(
                accepted = true,
                threadId = threadId,
                clientMessageId = clientMessageId,
                sendPath = "desktop_bridge",
                confirmation = "observed"
            )
        }

        val repository = ThreadRepository(
            api = api,
            sseClient = FakeGatewaySseClient(),
            sessionStore = InMemoryGatewaySessionStore(savedConfig = config),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.connect(config = config, requestedThreadId = "thread-1")
        repository.setPendingImage(
            PendingImageDraft(
                previewUri = "content://demo/image",
                fileName = "demo.png",
                mimeType = "image/png"
            )
        )
        repository.sendImageMessage(
            text = "",
            openStream = { _ -> "png-data".byteInputStream() }
        )

        val state = repository.state.value
        assertEquals(MobileThreadStatus.RUNNING, state.detail?.thread?.status)
        assertEquals(false, state.detail?.sendAvailable)
        assertTrue(state.detail?.sendDisabledReason?.contains("图片已发送") == true)
        assertEquals(MobileThreadStatus.RUNNING, state.threads.first().status)
    }

    private fun historyMessage(number: Int): ThreadMessage {
        return ThreadMessage(
            messageId = "message-$number",
            threadId = "thread-1",
            role = ThreadMessageRole.USER,
            kind = "text",
            text = "消息 $number",
            timestamp = "2026-04-23T00:0$number:00Z"
        )
    }
}

private class InMemoryGatewaySessionStore(
    private var savedConfig: GatewayConfig? = null,
    private var lastOpenedThreadId: String? = null,
    var cachedThreads: List<ThreadListItem> = emptyList(),
    var cachedDetails: MutableMap<String, ThreadDetail> = mutableMapOf(),
    private var threadCachePersistenceEnabled: Boolean = true,
    private var connectionMode: GatewayConnectionMode = GatewayConnectionMode.SSE,
    var lastRealtimeEventId: String? = null,
    var knownNotificationIds: Set<String> = emptySet(),
    private var alertEnabledThreadIds: Set<String> = emptySet(),
    private var queuedTextMessages: List<QueuedTextMessage> = emptyList()
) : GatewaySessionStore {
    override fun loadConfig(): GatewayConfig? = savedConfig

    override fun saveConfig(config: GatewayConfig) {
        savedConfig = config
    }

    override fun clearSession() {
        savedConfig = null
        lastOpenedThreadId = null
        cachedThreads = emptyList()
        cachedDetails.clear()
        threadCachePersistenceEnabled = false
        lastRealtimeEventId = null
        knownNotificationIds = emptySet()
        alertEnabledThreadIds = emptySet()
        queuedTextMessages = emptyList()
    }

    override fun loadConnectionMode(): GatewayConnectionMode = connectionMode

    override fun saveConnectionMode(mode: GatewayConnectionMode) {
        connectionMode = mode
    }

    override fun loadLastOpenedThreadId(): String? = lastOpenedThreadId

    override fun saveLastOpenedThreadId(threadId: String?) {
        lastOpenedThreadId = threadId
    }

    override fun loadCachedThreads(): List<ThreadListItem> = cachedThreads

    override fun saveCachedThreads(threads: List<ThreadListItem>) {
        if (!threadCachePersistenceEnabled) {
            return
        }
        cachedThreads = threads
    }

    override fun loadCachedThreadDetail(threadId: String): ThreadDetail? = cachedDetails[threadId]

    override fun saveCachedThreadDetail(detail: ThreadDetail) {
        if (!threadCachePersistenceEnabled) {
            return
        }
        cachedDetails[detail.thread.threadId] = detail
    }

    override fun loadQueuedTextMessages(): List<QueuedTextMessage> = queuedTextMessages

    override fun saveQueuedTextMessages(messages: List<QueuedTextMessage>) {
        queuedTextMessages = messages
    }

    override fun setThreadCachePersistenceEnabled(enabled: Boolean) {
        threadCachePersistenceEnabled = enabled
    }

    override fun loadLastRealtimeEventId(): String? = lastRealtimeEventId

    override fun saveLastRealtimeEventId(eventId: String?) {
        lastRealtimeEventId = eventId
    }

    override fun loadKnownNotificationIds(): Set<String> = knownNotificationIds

    override fun saveKnownNotificationIds(ids: Set<String>) {
        knownNotificationIds = ids
    }

    override fun loadAlertEnabledThreadIds(): Set<String> = alertEnabledThreadIds

    override fun clearThreadCache() {
        lastOpenedThreadId = null
        cachedThreads = emptyList()
        cachedDetails.clear()
        threadCachePersistenceEnabled = false
    }
}

private class FakeGatewayApi(
    private val threads: List<ThreadListItem>
) : GatewayApi {
    data class LatencyProbeRequest(val probeId: String, val sentAt: Long)

    var lastSentText: String? = null
    val sentTexts = mutableListOf<String>()
    var sendError: Throwable? = null
    var lastSentImageText: String? = null
    var lastSentImageName: String? = null
    var lastSentFileText: String? = null
    var lastSentFileName: String? = null
    var lastSentAttachmentText: String? = null
    val sentImageTexts = mutableListOf<String?>()
    val sentImageNames = mutableListOf<String>()
    val sentFileTexts = mutableListOf<String?>()
    val sentFileNames = mutableListOf<String>()
    val sentAttachmentImageNames = mutableListOf<String>()
    val sentAttachmentFileNames = mutableListOf<String>()
    var sentImageBatchCalls = 0
    var sentFileBatchCalls = 0
    var sentAttachmentBatchCalls = 0
    var diagnosticsJson = """{"diagnosticsVersion":2,"runLogs":[]}"""
    var diagnosticsDelayMillis = 0L
    var diagnosticsCallCount = 0
    var latestApkInfo = LatestApkInfo(available = false)
    var latestApkInfoCalls = 0
    var getThreadDetailCallCount = 0
    var threadDetailError: Throwable? = null
    val latencyProbeRequests = mutableListOf<LatencyProbeRequest>()
    var threadDetail: ThreadDetail? = null
    var sendResponse = SendMessageResponse(
        accepted = true,
        threadId = "thread-1",
        clientMessageId = "client-1",
        sendPath = "desktop_bridge",
        confirmation = "observed",
        warning = null
    )

    override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

    override suspend fun getLatestApkInfo(config: GatewayConfig): LatestApkInfo {
        latestApkInfoCalls += 1
        return latestApkInfo
    }

    override suspend fun getDiagnosticsJson(config: GatewayConfig): String {
        diagnosticsCallCount += 1
        if (diagnosticsDelayMillis > 0L) {
            delay(diagnosticsDelayMillis)
        }
        return diagnosticsJson
    }

    override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> = threads

    override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
        getThreadDetailCallCount += 1
        threadDetailError?.let { throw it }
        threadDetail?.let { return it }
        val thread = threads.first { it.threadId == threadId }
        return ThreadDetail(
            thread = thread,
            recentMessages = listOf(
                ThreadMessage(
                    messageId = "message-1",
                    threadId = threadId,
                    role = ThreadMessageRole.USER,
                    kind = "text",
                    text = "hello",
                    timestamp = "2026-04-23T00:00:00Z"
                )
            ),
            recentEvents = listOf(
                ThreadEvent(
                    eventId = "event-1",
                    threadId = threadId,
                    kind = ThreadEventKind.MESSAGE,
                    status = null,
                    text = "updated",
                    timestamp = "2026-04-23T00:00:00Z"
                )
            ),
            sendAvailable = true,
            sendDisabledReason = null
        )
    }

    override suspend fun sendRealtimeLatencyProbe(
        config: GatewayConfig,
        probeId: String,
        sentAt: Long
    ) {
        latencyProbeRequests += LatencyProbeRequest(probeId, sentAt)
    }

    override suspend fun sendMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String
    ): SendMessageResponse {
        sendError?.let { throw it }
        lastSentText = text
        sentTexts += text
        return sendResponse.copy(
            threadId = threadId,
            clientMessageId = clientMessageId
        )
    }

    override suspend fun sendImageMessage(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        image: ImageUploadSource
    ): SendMessageResponse {
        lastSentImageText = text
        lastSentImageName = image.fileName
        sentImageTexts += text
        sentImageNames += image.fileName
        return sendResponse.copy(
            threadId = threadId,
            clientMessageId = clientMessageId
        )
    }

    override suspend fun sendImageMessages(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        images: List<ImageUploadSource>
    ): SendMessageResponse {
        sentImageBatchCalls += 1
        lastSentImageText = text
        lastSentImageName = images.firstOrNull()?.fileName
        sentImageTexts += text
        sentImageNames += images.map { it.fileName }
        return sendResponse.copy(
            threadId = threadId,
            clientMessageId = clientMessageId
        )
    }

    override suspend fun sendFileMessages(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        files: List<FileUploadSource>
    ): SendMessageResponse {
        sentFileBatchCalls += 1
        lastSentFileText = text
        lastSentFileName = files.firstOrNull()?.fileName
        sentFileTexts += text
        sentFileNames += files.map { it.fileName }
        return sendResponse.copy(
            threadId = threadId,
            clientMessageId = clientMessageId
        )
    }

    override suspend fun sendAttachmentMessages(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        images: List<ImageUploadSource>,
        files: List<FileUploadSource>
    ): SendMessageResponse {
        sentAttachmentBatchCalls += 1
        lastSentAttachmentText = text
        sentAttachmentImageNames += images.map { it.fileName }
        sentAttachmentFileNames += files.map { it.fileName }
        return sendResponse.copy(
            threadId = threadId,
            clientMessageId = clientMessageId
        )
    }
}

private class FakeGatewaySseClient : GatewaySseClient {
    override fun connect(
        config: GatewayConfig,
        onEvent: (GatewayStreamEvent) -> Unit,
        onStateChanged: (StreamConnectionState) -> Unit
    ): AutoCloseable = AutoCloseable { }
}

private class FakeRealtimeClient : GatewayRealtimeClient {
    var lastConnection: FakeRealtimeConnection? = null
    var connectCount = 0

    override fun connect(
        config: GatewayConfig,
        lastEventId: String?,
        enabledThreadIds: Set<String>,
        knownNotificationIds: Set<String>,
        onEvent: (GatewayRealtimeEvent) -> Unit,
        onStateChanged: (StreamConnectionState) -> Unit
    ): GatewayRealtimeConnection {
        connectCount += 1
        val connection = FakeRealtimeConnection(onEvent, onStateChanged)
        lastConnection = connection
        onStateChanged(StreamConnectionState.OPEN)
        connection.updateNotificationPreferences(enabledThreadIds, knownNotificationIds)
        return connection
    }
}

private class FakeRealtimeConnection(
    private val onEvent: (GatewayRealtimeEvent) -> Unit,
    private val onStateChanged: (StreamConnectionState) -> Unit
) : GatewayRealtimeConnection {
    var closed = false
    var enabledThreadIds: Set<String> = emptySet()
    var knownNotificationIds: Set<String> = emptySet()
    var latencyResult: SocketLatencyTestResult? = null

    fun emit(event: GatewayRealtimeEvent) {
        onEvent(event)
    }

    fun emitState(state: StreamConnectionState) {
        onStateChanged(state)
    }

    override fun updateNotificationPreferences(
        enabledThreadIds: Set<String>,
        knownNotificationIds: Set<String>
    ) {
        this.enabledThreadIds = enabledThreadIds
        this.knownNotificationIds = knownNotificationIds
    }

    override suspend fun measureLatency(
        sampleCount: Int,
        timeoutMillis: Long
    ): SocketLatencyTestResult {
        return checkNotNull(latencyResult) { "latencyResult not configured" }
    }

    override fun close() {
        closed = true
    }
}

private class ControllableGatewaySseClient : GatewaySseClient {
    private var onEvent: ((GatewayStreamEvent) -> Unit)? = null
    private var onStateChanged: ((StreamConnectionState) -> Unit)? = null
    var connectCount = 0

    override fun connect(
        config: GatewayConfig,
        onEvent: (GatewayStreamEvent) -> Unit,
        onStateChanged: (StreamConnectionState) -> Unit
    ): AutoCloseable {
        connectCount += 1
        this.onEvent = onEvent
        this.onStateChanged = onStateChanged
        onStateChanged(StreamConnectionState.OPEN)
        return AutoCloseable {
            this.onEvent = null
            this.onStateChanged = null
        }
    }

    fun emit(event: GatewayStreamEvent) {
        requireNotNull(onEvent) { "SSE not connected" }.invoke(event)
    }

    fun emitState(state: StreamConnectionState) {
        requireNotNull(onStateChanged) { "SSE not connected" }.invoke(state)
    }
}
