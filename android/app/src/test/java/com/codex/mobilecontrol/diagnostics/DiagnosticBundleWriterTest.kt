package com.codex.mobilecontrol.diagnostics

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.PendingImageDraft
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.QueuedTextMessageStatus
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadEvent
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessage
import com.codex.mobilecontrol.model.ThreadMessageRole
import com.codex.mobilecontrol.network.GatewayRealtimeEvent
import com.codex.mobilecontrol.ui.MainUiState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class DiagnosticBundleWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `diagnostic bundle includes app detail snapshot and gateway diagnostics without token`() {
        val thread = ThreadListItem(
            threadId = "thread-1",
            title = "复现图片问题",
            cwd = "D:/projects/codex-mobile-control",
            status = MobileThreadStatus.WAITING_INPUT,
            updatedAt = "2026-04-29T12:00:00Z",
            progressSummary = "等待输入",
            needsAttention = true
        )
        val state = MainUiState(
            config = GatewayConfig(
                url = "http://127.0.0.1:43124",
                token = "secret-token"
            ),
            isConnected = true,
            threads = listOf(thread),
            selectedThreadId = "thread-1",
            detail = ThreadDetail(
                thread = thread,
                recentMessages = listOf(
                    ThreadMessage(
                        messageId = "msg-1",
                        threadId = "thread-1",
                        role = ThreadMessageRole.USER,
                        kind = "image",
                        text = "请看图片",
                        imageUrl = "http://127.0.0.1:43124/api/uploads/thread-1/demo.jpg",
                        thumbnailUrl = "http://127.0.0.1:43124/api/uploads/thread-1/demo-thumb.jpg",
                        fileName = "demo.jpg",
                        mimeType = "image/jpeg",
                        timestamp = "2026-04-29T12:00:01Z"
                    )
                ),
                recentEvents = listOf(
                    ThreadEvent(
                        eventId = "event-1",
                        threadId = "thread-1",
                        kind = ThreadEventKind.MESSAGE,
                        text = "执行命令: curl -H \"Authorization: Bearer codex-mobile-dev-token\" https://example.test/downloads/latest.json?token=query-secret",
                        timestamp = "2026-04-29T12:00:02Z"
                    )
                ),
                sendAvailable = true
            ),
            pendingImageDrafts = listOf(
                PendingImageDraft(
                    previewUri = "content://selected/image",
                    fileName = "selected.jpg",
                    mimeType = "image/jpeg"
                )
            ),
            queuedTextMessages = listOf(
                QueuedTextMessage(
                    threadId = "thread-1",
                    text = "排队消息 Authorization: Bearer queue-token",
                    queuedAtMillis = 1777467000000L,
                    blockedThreadUpdatedAt = "2026-04-29T12:00:00Z",
                    queueId = "queue-1",
                    status = QueuedTextMessageStatus.FAILED,
                    dispatchStartedAtMillis = 1777467100000L,
                    errorMessage = "send failed token=query-secret"
                )
            ),
            pendingRealtimeNotifications = listOf(
                GatewayRealtimeEvent.Notification(
                    eventId = "event-notification-1",
                    notificationId = "notification-1",
                    threadId = "thread-1",
                    trigger = "completed",
                    title = "线程完成",
                    body = "本轮已完成",
                    timestamp = "2026-04-29T12:10:00Z"
                )
            )
        )

        val zip = DiagnosticBundleWriter.create(
            outputDir = temporaryFolder.root,
            input = DiagnosticBundleInput(
                appVersionName = "3.0.12",
                appVersionCode = 183,
                currentScreen = "DETAIL",
                state = state,
                debugTraceLines = listOf(
                    "2026-04-29T12:00:03.000Z [detail-typing] startAnimation message=msg-1 len=4 start=0",
                    "2026-04-29T12:00:04.000Z [message-merge] matched previous=client-1 incoming=event-1 preferred=event-1",
                    "2026-04-29T12:00:05.000Z [network] Authorization: Bearer trace-bearer url=https://example.test/api/stream?token=trace-query body={\"token\":\"trace-json\"} file=C:\\Users\\devuser\\Desktop\\secret.txt"
                ),
                gatewayDiagnosticsJson = """
                    {
                      "diagnosticsVersion": 2,
                      "service": {
                        "statusDecisions": {
                          "recent": [
                            {
                              "threadId": "thread-1",
                              "context": "list",
                              "status": "error",
                              "source": "event",
                              "text": "线程异常结束，未收到最终回复",
                              "timestamp": "2026-04-29T12:10:00Z",
                              "observedAt": "2026-04-29T12:10:01Z"
                            }
                          ]
                        }
                      },
                      "runLogs": [{"fileName":"gateway.out.log"}]
                    }
                """.trimIndent(),
                alertMessageEnabledThreadIds = setOf("thread-1"),
                alertCompletionEnabledThreadIds = setOf("thread-1", "thread-2"),
                generatedAtMillis = 1777468000000L
            )
        )

        assertTrue(zip.name.startsWith("codex-mobile-diagnostics-"))
        assertTrue(zip.name.endsWith(".zip"))

        val appState = JSONObject(readZipEntry(zip, "app-state.json"))
        val rawZipText = ZipFile(zip).use { file ->
            file.entries().asSequence().joinToString("\n") { entry ->
                readZipEntry(zip, entry.name)
            }
        }
        assertFalse(rawZipText.contains("secret-token"))
        assertFalse(rawZipText.contains("codex-mobile-dev-token"))
        assertFalse(rawZipText.contains("query-secret"))
        assertFalse(rawZipText.contains("trace-bearer"))
        assertFalse(rawZipText.contains("trace-query"))
        assertFalse(rawZipText.contains("trace-json"))
        assertFalse(rawZipText.contains("C:\\Users\\devuser"))
        assertTrue(rawZipText.contains("Authorization: Bearer [REDACTED]"))
        assertTrue(rawZipText.contains("token=[REDACTED]"))
        assertEquals("3.0.12", appState.getJSONObject("app").getString("versionName"))
        assertEquals(183, appState.getJSONObject("app").getInt("versionCode"))
        assertEquals(3, appState.getInt("debugTraceLineCount"))
        assertEquals("DETAIL", appState.getString("currentScreen"))
        assertEquals("http://127.0.0.1:43124", appState.getJSONObject("gateway").getString("url"))
        val queue = appState.getJSONObject("queue")
        assertEquals(1, queue.getInt("total"))
        assertEquals(1, queue.getJSONObject("byStatus").getInt("FAILED"))
        val queuedItem = queue.getJSONArray("items").getJSONObject(0)
        assertEquals("queue-1", queuedItem.getString("queueId"))
        assertEquals("thread-1", queuedItem.getString("threadId"))
        assertEquals("FAILED", queuedItem.getString("status"))
        assertTrue(queuedItem.getString("textPreview").contains("[REDACTED]"))
        assertTrue(queuedItem.getString("errorMessage").contains("[REDACTED]"))
        val notifications = appState.getJSONObject("notifications")
        assertEquals(1, notifications.getInt("messageEnabledThreadCount"))
        assertEquals(2, notifications.getInt("completionEnabledThreadCount"))
        assertEquals(1, notifications.getInt("pendingRealtimeCount"))
        assertEquals("notification-1", notifications.getJSONArray("pendingRealtime").getJSONObject(0).getString("notificationId"))
        val diagnosticsSummary = appState.getJSONObject("gatewayDiagnosticsSummary")
        assertEquals(2, diagnosticsSummary.getInt("diagnosticsVersion"))
        assertEquals(1, diagnosticsSummary.getJSONObject("statusDecisions").getInt("recentCount"))
        assertEquals("event", diagnosticsSummary.getJSONObject("statusDecisions")
            .getJSONArray("recent")
            .getJSONObject(0)
            .getString("source"))
        val message = appState.getJSONObject("detail")
            .getJSONArray("recentMessages")
            .getJSONObject(0)
        assertEquals("请看图片", message.getString("text"))
        assertEquals("http://127.0.0.1:43124/api/uploads/thread-1/demo.jpg", message.getString("imageUrl"))
        assertEquals("gateway.out.log", JSONObject(readZipEntry(zip, "gateway-diagnostics.json"))
            .getJSONArray("runLogs")
            .getJSONObject(0)
            .getString("fileName"))
        assertTrue(readZipEntry(zip, "debug-trace.log").contains("[detail-typing] startAnimation"))
        assertTrue(readZipEntry(zip, "debug-trace.log").contains("file=[LOCAL_PATH]"))
    }

    @Test
    fun `diagnostic bundle records gateway diagnostics fetch failure for app only exports`() {
        val zip = DiagnosticBundleWriter.create(
            outputDir = temporaryFolder.root,
            input = DiagnosticBundleInput(
                appVersionName = "3.0.12",
                appVersionCode = 183,
                currentScreen = "PROFILE",
                state = MainUiState(),
                debugTraceLines = listOf("gatewayDiagnosticsFailed error=http_503 token=trace-secret"),
                gatewayDiagnosticsJson = """{"available":false,"error":"http_503 token=query-secret"}""",
                gatewayDiagnosticsError = "http_503 token=query-secret",
                generatedAtMillis = 1777468000000L
            )
        )

        val appState = JSONObject(readZipEntry(zip, "app-state.json"))
        assertEquals("PROFILE", appState.getString("currentScreen"))
        assertEquals("http_503 token=[REDACTED]", appState.getJSONObject("gateway").getString("diagnosticsError"))
        assertFalse(appState.getJSONObject("gatewayDiagnosticsSummary").getBoolean("available"))
        assertEquals("http_503 token=[REDACTED]", appState.getJSONObject("gatewayDiagnosticsSummary").getString("error"))
        assertFalse(readZipEntry(zip, "gateway-diagnostics.json").contains("query-secret"))
        assertFalse(readZipEntry(zip, "debug-trace.log").contains("trace-secret"))
    }

    private fun readZipEntry(zip: java.io.File, name: String): String {
        return ZipFile(zip).use { file ->
            val entry = file.getEntry(name) ?: error("Missing zip entry $name")
            file.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }
}
