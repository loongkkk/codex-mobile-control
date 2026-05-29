package com.codex.mobilecontrol.network

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.model.FileUploadSource
import com.codex.mobilecontrol.model.ImageUploadSource
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.ThreadEventKind
import com.codex.mobilecontrol.model.ThreadMessageRole
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class GatewayApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GatewayApiClient
    private lateinit var config: GatewayConfig

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = GatewayApiClient(
            callFactory = OkHttpClient(),
            jsonParser = GatewayJsonParser()
        )
        config = GatewayConfig(
            url = server.url("/").toString().removeSuffix("/"),
            token = "secret-token"
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loginPostsTokenAndParsesResponse() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"authenticated":true}""")
                .addHeader("Content-Type", "application/json")
        )

        val response = api.login(config)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/auth/login", request.path)
        assertEquals("POST", request.method)
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("secret-token", payload.getString("token"))
        assertTrue(response.authenticated)
    }

    @Test
    fun getThreadsUsesBearerTokenAndParsesThreadStatus() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "threads":[
                        {
                          "threadId":"thread-1",
                          "title":"Main Thread",
                          "cwd":"/work/app",
                          "status":"running",
                          "updatedAt":"2026-04-22T00:00:00Z",
                          "progressSummary":"working",
                          "needsAttention":false,
                          "runningStartedAt":"2026-04-21T23:59:30Z",
                          "isPinned":true
                        }
                      ]
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val threads = api.getThreads(config)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/threads", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals(1, threads.size)
        assertEquals("thread-1", threads[0].threadId)
        assertEquals(MobileThreadStatus.RUNNING, threads[0].status)
        assertEquals("2026-04-21T23:59:30Z", threads[0].runningStartedAt)
        assertTrue(threads[0].isPinned)
    }

    @Test
    fun getLatestApkInfoUsesBearerTokenAndParsesVersionMetadata() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "available":true,
                      "fileName":"codex-mobile-control-debug-20260427-151500.apk",
                      "versionCode":123,
                      "versionName":"1.0.23",
                      "downloadUrl":"/downloads/latest.apk"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val latest = api.getLatestApkInfo(config)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/downloads/latest.json", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertTrue(latest.available)
        assertEquals(123, latest.versionCode)
        assertEquals("1.0.23", latest.versionName)
        assertEquals("codex-mobile-control-debug-20260427-151500.apk", latest.fileName)
        assertTrue(latest.isNewerThan(122))
        assertFalse(latest.isNewerThan(123))
    }

    @Test
    fun getDiagnosticsJsonUsesBearerTokenAndReturnsRawJson() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "diagnosticsVersion":1,
                      "runLogs":[
                        {
                          "fileName":"gateway-123.out.log",
                          "contentTail":"latest-line"
                        }
                      ]
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val diagnosticsJson = api.getDiagnosticsJson(config)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/diagnostics", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        val payload = JSONObject(diagnosticsJson)
        assertEquals(1, payload.getInt("diagnosticsVersion"))
        assertEquals("gateway-123.out.log", payload.getJSONArray("runLogs").getJSONObject(0).getString("fileName"))
    }

    @Test
    fun getMarkdownFilePreviewUsesBearerTokenAndEncodesPath() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "fileName":"notes.md",
                      "path":"docs/notes.md",
                      "content":"# 预览文档\n\n正文",
                      "sizeBytes":24
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val preview = api.getMarkdownFilePreview(config, "thread-1", "docs/notes.md")

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/threads/thread-1/markdown-preview?path=docs%2Fnotes.md", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals("notes.md", preview.fileName)
        assertEquals("docs/notes.md", preview.path)
        assertEquals("# 预览文档\n\n正文", preview.content)
        assertEquals(24L, preview.sizeBytes)
    }

    @Test
    fun getMarkdownFilePreviewSupportsJsonPaths() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "fileName":"sample.json",
                      "path":"artifacts/sample.json",
                      "content":"{\"ok\":true}",
                      "sizeBytes":11
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val preview = api.getMarkdownFilePreview(config, "thread-1", "sample.json")

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/threads/thread-1/markdown-preview?path=sample.json", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals("sample.json", preview.fileName)
        assertEquals("artifacts/sample.json", preview.path)
        assertEquals("{\"ok\":true}", preview.content)
        assertEquals(11L, preview.sizeBytes)
    }

    @Test
    fun sendRealtimeLatencyProbePostsProbePayload() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody(
                    """
                    {
                      "type":"latency_probe_result",
                      "probeId":"probe-1",
                      "sentAt":1777699000000,
                      "timestamp":"2026-05-02T05:29:00.000Z"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        api.sendRealtimeLatencyProbe(config, "probe-1", 1_777_699_000_000L)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/realtime/latency-probe", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("probe-1", payload.getString("probeId"))
        assertEquals(1_777_699_000_000L, payload.getLong("sentAt"))
    }

    @Test
    fun getThreadDetailParsesMessagesEventsAndTopLevelSendFields() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "thread":{
                        "threadId":"thread-9",
                        "title":"Detail Thread",
                        "cwd":"/work/detail",
                        "status":"waiting_input",
                        "updatedAt":"2026-04-22T00:01:00Z",
                        "progressSummary":"need user input",
                        "needsAttention":true
                      },
                      "recentMessages":[
                        {
                          "messageId":"msg-1",
                          "threadId":"thread-9",
                          "role":"assistant",
                          "text":"Please provide input",
                          "timestamp":"2026-04-22T00:00:30Z"
                        }
                      ],
                      "recentEvents":[
                        {
                          "eventId":"evt-1",
                          "threadId":"thread-9",
                          "kind":"status_changed",
                          "status":"waiting_input",
                          "text":"Thread waiting for user",
                          "timestamp":"2026-04-22T00:00:20Z"
                        }
                      ],
                      "fileChanges":{
                        "summary":"2 个文件已更改 +104 -1",
                        "changedFiles":2,
                        "added":104,
                        "removed":1,
                        "items":[
                          {
                            "path":"gateway/src/mobile-gateway-service.ts",
                            "added":26,
                            "removed":1
                          },
                          {
                            "path":"gateway/tests/mobile-gateway-service.test.ts",
                            "added":78,
                            "removed":0
                          }
                        ]
                      },
                      "composerState":{
                        "permissionLabel":"完全访问权限",
                        "modelLabel":"GPT-5.5 超高",
                        "effortLabel":"超高"
                      },
                      "sendAvailable":false,
                      "sendDisabledReason":"Waiting for approval"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val detail = api.getThreadDetail(config, "thread-9")

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/threads/thread-9", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals("thread-9", detail.thread.threadId)
        assertEquals(ThreadMessageRole.ASSISTANT, detail.recentMessages[0].role)
        assertEquals(ThreadEventKind.STATUS_CHANGED, detail.recentEvents[0].kind)
        assertEquals("2 个文件已更改 +104 -1", detail.fileChanges?.summary)
        assertEquals("gateway/src/mobile-gateway-service.ts", detail.fileChanges?.items?.get(0)?.path)
        assertEquals(26, detail.fileChanges?.items?.get(0)?.added)
        assertEquals(1, detail.fileChanges?.items?.get(0)?.removed)
        assertEquals("完全访问权限", detail.composerState?.permissionLabel)
        assertEquals("GPT-5.5 超高", detail.composerState?.modelLabel)
        assertEquals("超高", detail.composerState?.effortLabel)
        assertEquals(false, detail.sendAvailable)
        assertEquals("Waiting for approval", detail.sendDisabledReason)
    }

    @Test
    fun getThreadPreviewUsesPreviewEndpointAndParsesDetail() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "thread":{
                        "threadId":"thread-9",
                        "title":"Detail Thread",
                        "cwd":"/work/detail",
                        "status":"idle",
                        "updatedAt":"2026-04-22T00:01:00Z",
                        "progressSummary":"cached preview",
                        "needsAttention":false
                      },
                      "recentMessages":[
                        {
                          "messageId":"msg-preview",
                          "threadId":"thread-9",
                          "role":"user",
                          "kind":"text",
                          "text":"先展示预览消息",
                          "timestamp":"2026-04-22T00:00:30Z"
                        }
                      ],
                      "recentEvents":[],
                      "composerState":{
                        "permissionLabel":"完全访问权限",
                        "modelLabel":"GPT-5.5 超高",
                        "effortLabel":"超高"
                      },
                      "sendAvailable":true
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val detail = api.getThreadPreview(config, "thread-9")

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/threads/thread-9/preview", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals("thread-9", detail.thread.threadId)
        assertEquals("先展示预览消息", detail.recentMessages[0].text)
        assertEquals("完全访问权限", detail.composerState?.permissionLabel)
        assertTrue(detail.sendAvailable)
    }

    @Test
    fun getThreadMessagesUsesMessagesEndpointAndParsesPage() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "messages":[
                        {
                          "messageId":"msg-older",
                          "threadId":"thread-9",
                          "role":"assistant",
                          "kind":"text",
                          "text":"更早的一条消息",
                          "timestamp":"2026-04-22T00:00:10Z"
                        }
                      ],
                      "nextCursor":"msg-older"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val page = api.getThreadMessages(
            config = config,
            threadId = "thread-9",
            beforeMessageId = "msg-current",
            beforeTimestamp = "2026-04-22T00:00:30Z",
            limit = 20
        )

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(
            "/api/threads/thread-9/messages?before=msg-current&beforeTimestamp=2026-04-22T00%3A00%3A30Z&limit=20",
            request.path
        )
        assertEquals("GET", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals("更早的一条消息", page.messages.first().text)
        assertEquals("msg-older", page.nextCursor)
    }

    @Test
    fun getNewThreadMessagesUsesAfterCursorAndParsesPage() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "messages":[
                        {
                          "messageId":"msg-new",
                          "threadId":"thread-9",
                          "role":"assistant",
                          "kind":"text",
                          "text":"新增的一条消息",
                          "timestamp":"2026-04-22T00:00:40Z"
                        }
                      ],
                      "nextCursor":"msg-new"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val page = api.getNewThreadMessages(
            config = config,
            threadId = "thread-9",
            afterMessageId = "msg-current",
            afterTimestamp = "2026-04-22T00:00:30Z",
            limit = 20
        )

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(
            "/api/threads/thread-9/messages?after=msg-current&afterTimestamp=2026-04-22T00%3A00%3A30Z&limit=20",
            request.path
        )
        assertEquals("GET", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals("新增的一条消息", page.messages.first().text)
        assertEquals("msg-new", page.nextCursor)
    }

    @Test
    fun sendMessagePostsJsonPayloadAndParsesAck() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "accepted":true,
                      "threadId":"thread-2",
                      "clientMessageId":"client-1",
                      "sendPath":"app_server",
                      "confirmation":"observed"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val ack = api.sendMessage(
            config = config,
            threadId = "thread-2",
            text = "hello",
            clientMessageId = "client-1"
        )

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/threads/thread-2/messages", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("hello", payload.getString("text"))
        assertEquals("client-1", payload.getString("clientMessageId"))
        assertTrue(ack.accepted)
        assertEquals("thread-2", ack.threadId)
        assertEquals("client-1", ack.clientMessageId)
        assertEquals("app_server", ack.sendPath)
        assertEquals("observed", ack.confirmation)
        assertEquals(null, ack.warning)
    }

    @Test
    fun sendGuideMessagePostsGuidePayloadAndParsesAck() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "accepted":true,
                      "threadId":"thread-2",
                      "clientMessageId":"client-guide",
                      "sendPath":"desktop_bridge",
                      "confirmation":"keystrokes_sent"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val ack = api.sendGuideMessage(
            config = config,
            threadId = "thread-2",
            text = "guide",
            clientMessageId = "client-guide"
        )

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/threads/thread-2/messages", request.path)
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("guide", payload.getString("text"))
        assertEquals("client-guide", payload.getString("clientMessageId"))
        assertEquals(true, payload.getBoolean("guide"))
        assertTrue(ack.accepted)
        assertEquals("client-guide", ack.clientMessageId)
    }

    @Test
    fun sendQueuedMessagePostsQueuePayloadAndParsesAck() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "accepted":true,
                      "threadId":"thread-2",
                      "clientMessageId":"client-queue",
                      "sendPath":"desktop_bridge",
                      "confirmation":"keystrokes_sent"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val ack = api.sendQueuedMessage(
            config = config,
            threadId = "thread-2",
            text = "queue",
            clientMessageId = "client-queue"
        )

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/threads/thread-2/messages", request.path)
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("queue", payload.getString("text"))
        assertEquals("client-queue", payload.getString("clientMessageId"))
        assertEquals(true, payload.getBoolean("queue"))
        assertFalse(payload.has("guide"))
        assertTrue(ack.accepted)
        assertEquals("client-queue", ack.clientMessageId)
    }

    @Test
    fun getThreadDetailParsesImageMessages() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "thread":{
                        "threadId":"thread-9",
                        "title":"Detail Thread",
                        "cwd":"/work/detail",
                        "status":"idle",
                        "updatedAt":"2026-04-22T00:01:00Z",
                        "progressSummary":"need user input",
                        "needsAttention":false
                      },
                      "recentMessages":[
                        {
                          "messageId":"msg-1",
                          "threadId":"thread-9",
                          "role":"user",
                          "kind":"image",
                          "text":"请分析这张图",
                          "imageUrl":"/api/uploads/thread-9/demo.png",
                          "thumbnailUrl":"/api/uploads/thread-9/demo.png",
                          "fileName":"demo.png",
                          "timestamp":"2026-04-22T00:00:30Z"
                        }
                      ],
                      "recentEvents":[],
                      "sendAvailable":true
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val detail = api.getThreadDetail(config, "thread-9")

        assertEquals("image", detail.recentMessages.first().kind)
        assertEquals(
            "${config.url}/api/uploads/thread-9/demo.png",
            detail.recentMessages.first().imageUrl
        )
    }

    @Test
    fun sendImageMessagePostsMultipartPayloadAndParsesAck() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody(
                    """
                    {
                      "accepted":true,
                      "threadId":"thread-2",
                      "clientMessageId":"client-image-1",
                      "sendPath":"desktop_bridge",
                      "confirmation":"observed"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val ack = api.sendImageMessage(
            config = config,
            threadId = "thread-2",
            text = "看一下这张图",
            clientMessageId = "client-image-1",
            image = ImageUploadSource(
                fileName = "demo.png",
                mimeType = "image/png",
                previewUri = "content://demo/image",
                openStream = { Buffer().writeUtf8("png-data").inputStream() }
            )
        )

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/threads/thread-2/image-message", request.path)
        assertTrue(request.getHeader("Content-Type")!!.contains("multipart/form-data"))
        assertTrue(request.body.readUtf8().contains("filename=\"demo.png\""))
        assertEquals("client-image-1", ack.clientMessageId)
    }

    @Test
    fun sendImageMessagesPostsOneMultipartPayloadWithAllImagesAndParsesAck() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody(
                    """
                    {
                      "accepted":true,
                      "threadId":"thread-2",
                      "clientMessageId":"client-image-batch-1",
                      "sendPath":"desktop_bridge",
                      "confirmation":"observed"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val ack = api.sendImageMessages(
            config = config,
            threadId = "thread-2",
            text = "两张图",
            clientMessageId = "client-image-batch-1",
            images = listOf(
                ImageUploadSource(
                    fileName = "first.png",
                    mimeType = "image/png",
                    previewUri = "content://demo/first",
                    openStream = { Buffer().writeUtf8("first-png-data").inputStream() }
                ),
                ImageUploadSource(
                    fileName = "second.jpg",
                    mimeType = "image/jpeg",
                    previewUri = "content://demo/second",
                    openStream = { Buffer().writeUtf8("second-jpg-data").inputStream() }
                )
            )
        )

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val body = request.body.readUtf8()
        assertEquals("/api/threads/thread-2/image-messages", request.path)
        assertTrue(request.getHeader("Content-Type")!!.contains("multipart/form-data"))
        assertTrue(body.contains("name=\"images\"; filename=\"first.png\""))
        assertTrue(body.contains("name=\"images\"; filename=\"second.jpg\""))
        assertEquals("client-image-batch-1", ack.clientMessageId)
    }

    @Test
    fun sendFileMessagesPostsMultipartPayloadWithAllFilesAndParsesAck() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody(
                    """
                    {
                      "accepted":true,
                      "threadId":"thread-2",
                      "clientMessageId":"client-file-batch-1",
                      "sendPath":"desktop_bridge",
                      "confirmation":"observed"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val ack = api.sendFileMessages(
            config = config,
            threadId = "thread-2",
            text = "诊断日志",
            clientMessageId = "client-file-batch-1",
            files = listOf(
                FileUploadSource(
                    fileName = "diagnostics.zip",
                    mimeType = "application/zip",
                    previewUri = "content://demo/diagnostics",
                    openStream = { Buffer().writeUtf8("zip-data").inputStream() }
                ),
                FileUploadSource(
                    fileName = "gateway.log",
                    mimeType = "text/plain",
                    previewUri = "content://demo/gateway-log",
                    openStream = { Buffer().writeUtf8("log-data").inputStream() }
                )
            )
        )

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val body = request.body.readUtf8()
        assertEquals("/api/threads/thread-2/file-messages", request.path)
        assertTrue(request.getHeader("Content-Type")!!.contains("multipart/form-data"))
        assertTrue(body.contains("name=\"files\"; filename=\"diagnostics.zip\""))
        assertTrue(body.contains("name=\"files\"; filename=\"gateway.log\""))
        assertEquals("client-file-batch-1", ack.clientMessageId)
    }

    @Test
    fun sendAttachmentMessagesPostsMultipartPayloadWithImagesAndFilesAndParsesAck() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody(
                    """
                    {
                      "accepted":true,
                      "threadId":"thread-2",
                      "clientMessageId":"client-attachment-batch-1",
                      "sendPath":"desktop_bridge",
                      "confirmation":"observed"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val ack = api.sendAttachmentMessages(
            config = config,
            threadId = "thread-2",
            text = "图片和日志",
            clientMessageId = "client-attachment-batch-1",
            images = listOf(
                ImageUploadSource(
                    fileName = "screen.png",
                    mimeType = "image/png",
                    previewUri = "content://demo/screen",
                    openStream = { Buffer().writeUtf8("png-data").inputStream() }
                )
            ),
            files = listOf(
                FileUploadSource(
                    fileName = "diagnostics.zip",
                    mimeType = "application/zip",
                    previewUri = "content://demo/diagnostics",
                    openStream = { Buffer().writeUtf8("zip-data").inputStream() }
                )
            )
        )

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val body = request.body.readUtf8()
        assertEquals("/api/threads/thread-2/attachment-messages", request.path)
        assertTrue(request.getHeader("Content-Type")!!.contains("multipart/form-data"))
        assertTrue(body.contains("name=\"images\"; filename=\"screen.png\""))
        assertTrue(body.contains("name=\"files\"; filename=\"diagnostics.zip\""))
        assertEquals("client-attachment-batch-1", ack.clientMessageId)
    }

    @Test
    fun sendMessageHttpErrorUsesGatewayJsonMessageWhenPresent() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody(
                    """
                    {
                      "error":"desktop_bridge_unavailable",
                      "reason":"desktop_window_not_found",
                      "message":"未找到正在运行的 Codex Desktop 窗口"
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val error = assertSuspendFailsWith<IOException> {
            api.sendMessage(
                config = config,
                threadId = "thread-2",
                text = "hello",
                clientMessageId = "client-1"
            )
        }

        requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("未找到正在运行的 Codex Desktop 窗口", error.message)
    }

    @Test
    fun getThreadDetailFailsFastWhenThreadMissing() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "recentMessages":[],
                      "recentEvents":[],
                      "sendAvailable":true
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val error = assertSuspendFailsWith<IllegalArgumentException> {
            api.getThreadDetail(config, "thread-9")
        }

        requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertTrue(error.message?.contains("thread") == true)
    }

    @Test
    fun getThreadsThrowsOnBlankResponseBody() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("   \n")
                .addHeader("Content-Type", "application/json")
        )

        val error = assertSuspendFailsWith<IOException> {
            api.getThreads(config)
        }

        requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertTrue(error.message?.contains("GET") == true)
        assertTrue(error.message?.contains("/api/threads") == true)
        assertTrue(error.message?.contains("empty") == true)
    }

    @Test
    fun getThreadsHttpErrorContainsMethodUrlStatusAndBodySnippet() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("gateway exploded with detailed explanation")
                .addHeader("Content-Type", "text/plain")
        )

        val error = assertSuspendFailsWith<IOException> {
            api.getThreads(config)
        }

        requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertTrue(error.message?.contains("GET") == true)
        assertTrue(error.message?.contains("/api/threads") == true)
        assertTrue(error.message?.contains("500") == true)
        assertTrue(error.message?.contains("gateway exploded") == true)
    }

    @Test
    fun getThreadsPropagatesResponseBodyReadIOException() = runBlocking {
        val failingApi = GatewayApiClient(
            callFactory = AsyncResponseCallFactory { request ->
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(FailingReadResponseBody())
                    .build()
            },
            jsonParser = GatewayJsonParser()
        )

        val error = assertSuspendFailsWith<IOException> {
            withTimeout(2_000) {
                failingApi.getThreads(config)
            }
        }

        assertTrue(error.message?.contains("body read failed") == true)
    }

    @Test
    fun parseLoginResponseFailsWhenAuthenticatedMissing() {
        val parser = GatewayJsonParser()

        val error = assertFailsWith<IllegalArgumentException> {
            parser.parseLoginResponse("""{}""")
        }

        assertTrue(error.message?.contains("authenticated") == true)
    }

    @Test
    fun getThreadsFailsWhenThreadIdMissing() {
        val parser = GatewayJsonParser()

        val error = assertFailsWith<IllegalArgumentException> {
            parser.parseThreadsResponse(
                """
                {
                  "threads":[
                    {
                      "title":"Main Thread",
                      "cwd":"/work/app",
                      "status":"running",
                      "updatedAt":"2026-04-22T00:00:00Z",
                      "progressSummary":"working",
                      "needsAttention":false
                    }
                  ]
                }
                """.trimIndent()
            )
        }

        assertTrue(error.message?.contains("threadId") == true)
    }

    @Test
    fun parseThreadsResponseKeepsAutomationMetadata() {
        val parser = GatewayJsonParser()

        val threads = parser.parseThreadsResponse(
            """
            {
              "threads":[
                {
                  "threadId":"thread-scheduled",
                  "title":"Chrome",
                  "cwd":"D:/code/chrome_bot",
                  "status":"completed",
                  "updatedAt":"2026-05-12T01:32:24Z",
                  "progressSummary":"定时任务已开启 · 每15分钟",
                  "needsAttention":false,
                  "automationActive":true,
                  "automationSummary":"定时任务已开启 · 每15分钟"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(true, threads.single().automationActive)
        assertEquals("定时任务已开启 · 每15分钟", threads.single().automationSummary)
    }

    @Test
    fun getAutomationsUsesBearerTokenAndParsesAutomationList() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "automations":[
                        {
                          "id":"token-revenue",
                          "name":"Token revenue demos and research",
                          "kind":"heartbeat",
                          "status":"PAUSED",
                          "scheduleSummary":"每5分钟",
                          "targetThreadId":"thread-1",
                          "targetThreadTitle":"Chrome",
                          "cwd":"D:/code/chrome_bot"
                        }
                      ]
                    }
                    """.trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val automations = api.getAutomations(config)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/automations", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals(1, automations.size)
        assertEquals("token-revenue", automations.single().id)
        assertEquals("Token revenue demos and research", automations.single().name)
        assertEquals("heartbeat", automations.single().kind)
        assertEquals("PAUSED", automations.single().status)
        assertEquals("每5分钟", automations.single().scheduleSummary)
        assertEquals("thread-1", automations.single().targetThreadId)
        assertEquals("Chrome", automations.single().targetThreadTitle)
    }

    @Test
    fun parseThreadsResponseFailsWhenThreadsMissing() {
        val parser = GatewayJsonParser()

        val error = assertFailsWith<IllegalArgumentException> {
            parser.parseThreadsResponse("""{}""")
        }

        assertTrue(error.message?.contains("threads") == true)
    }

    @Test
    fun getThreadDetailFailsWhenMessageIdMissing() {
        val parser = GatewayJsonParser()

        val error = assertFailsWith<IllegalArgumentException> {
            parser.parseThreadDetail(
                """
                {
                  "thread":{
                    "threadId":"thread-9",
                    "title":"Detail Thread",
                    "cwd":"/work/detail",
                    "status":"waiting_input",
                    "updatedAt":"2026-04-22T00:01:00Z",
                    "progressSummary":"need user input",
                    "needsAttention":true
                  },
                  "recentMessages":[
                    {
                      "threadId":"thread-9",
                      "role":"assistant",
                      "text":"Please provide input",
                      "timestamp":"2026-04-22T00:00:30Z"
                    }
                  ],
                  "recentEvents":[],
                  "sendAvailable":true
                }
                """.trimIndent()
            )
        }

        assertTrue(error.message?.contains("messageId") == true)
    }

    @Test
    fun getThreadDetailFailsWhenRecentMessagesMissing() {
        val parser = GatewayJsonParser()

        val error = assertFailsWith<IllegalArgumentException> {
            parser.parseThreadDetail(
                """
                {
                  "thread":{
                    "threadId":"thread-9",
                    "title":"Detail Thread",
                    "cwd":"/work/detail",
                    "status":"waiting_input",
                    "updatedAt":"2026-04-22T00:01:00Z",
                    "progressSummary":"need user input",
                    "needsAttention":true
                  },
                  "recentEvents":[],
                  "sendAvailable":true
                }
                """.trimIndent()
            )
        }

        assertTrue(error.message?.contains("recentMessages") == true)
    }

    @Test
    fun getThreadDetailFailsWhenEventIdMissing() {
        val parser = GatewayJsonParser()

        val error = assertFailsWith<IllegalArgumentException> {
            parser.parseThreadDetail(
                """
                {
                  "thread":{
                    "threadId":"thread-9",
                    "title":"Detail Thread",
                    "cwd":"/work/detail",
                    "status":"waiting_input",
                    "updatedAt":"2026-04-22T00:01:00Z",
                    "progressSummary":"need user input",
                    "needsAttention":true
                  },
                  "recentMessages":[],
                  "recentEvents":[
                    {
                      "threadId":"thread-9",
                      "kind":"status_changed",
                      "status":"waiting_input",
                      "text":"Thread waiting for user",
                      "timestamp":"2026-04-22T00:00:20Z"
                    }
                  ],
                  "sendAvailable":true
                }
                """.trimIndent()
            )
        }

        assertTrue(error.message?.contains("eventId") == true)
    }

    @Test
    fun getThreadDetailFailsWhenRecentEventsMissing() {
        val parser = GatewayJsonParser()

        val error = assertFailsWith<IllegalArgumentException> {
            parser.parseThreadDetail(
                """
                {
                  "thread":{
                    "threadId":"thread-9",
                    "title":"Detail Thread",
                    "cwd":"/work/detail",
                    "status":"waiting_input",
                    "updatedAt":"2026-04-22T00:01:00Z",
                    "progressSummary":"need user input",
                    "needsAttention":true
                  },
                  "recentMessages":[],
                  "sendAvailable":true
                }
                """.trimIndent()
            )
        }

        assertTrue(error.message?.contains("recentEvents") == true)
    }

    @Test
    fun getThreadDetailFailsWhenTimestampMissing() {
        val parser = GatewayJsonParser()

        val error = assertFailsWith<IllegalArgumentException> {
            parser.parseThreadDetail(
                """
                {
                  "thread":{
                    "threadId":"thread-9",
                    "title":"Detail Thread",
                    "cwd":"/work/detail",
                    "status":"waiting_input",
                    "updatedAt":"2026-04-22T00:01:00Z",
                    "progressSummary":"need user input",
                    "needsAttention":true
                  },
                  "recentMessages":[
                    {
                      "messageId":"msg-1",
                      "threadId":"thread-9",
                      "role":"assistant",
                      "text":"Please provide input"
                    }
                  ],
                  "recentEvents":[],
                  "sendAvailable":true
                }
                """.trimIndent()
            )
        }

        assertTrue(error.message?.contains("timestamp") == true)
    }

    @Test
    fun sendMessageFailsWhenClientMessageIdMissing() {
        val parser = GatewayJsonParser()

        val error = assertFailsWith<IllegalArgumentException> {
            parser.parseSendMessageResponse(
                """
                {
                  "accepted":true,
                  "threadId":"thread-2"
                }
                """.trimIndent()
            )
        }

        assertTrue(error.message?.contains("clientMessageId") == true)
    }

    @Test
    fun cancellationCancelsUnderlyingOkHttpCall() = runTest {
        val wasCanceled = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    wasCanceled.set(true)
                }
            })
            .build()
        val cancellableApi = GatewayApiClient(
            callFactory = client,
            jsonParser = GatewayJsonParser()
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )

        val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            cancellableApi.getThreads(config)
        }

        requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        job.cancel()
        repeat(20) {
            if (wasCanceled.get()) {
                return@repeat
            }
            delay(50)
        }

        assertTrue(job.isCancelled)
        assertTrue(wasCanceled.get())
    }

    private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
        noinline block: suspend () -> Unit
    ): T {
        return try {
            block()
            fail("Expected ${T::class.java.simpleName} to be thrown")
            throw AssertionError("unreachable")
        } catch (error: Throwable) {
            if (error is T) {
                error
            } else {
                throw error
            }
        }
    }

    private inline fun <reified T : Throwable> assertFailsWith(
        noinline block: () -> Unit
    ): T {
        return try {
            block()
            fail("Expected ${T::class.java.simpleName} to be thrown")
            throw AssertionError("unreachable")
        } catch (error: Throwable) {
            if (error is T) {
                error
            } else {
                throw error
            }
        }
    }

    private class FailingReadResponseBody : ResponseBody() {
        override fun contentType() = null

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource {
            return object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    throw IOException("body read failed")
                }

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }.buffer()
        }
    }

    private class AsyncResponseCallFactory(
        private val responseProvider: (Request) -> Response
    ) : Call.Factory {
        override fun newCall(request: Request): Call {
            return FakeCall(request, responseProvider)
        }
    }

    private class FakeCall(
        private val request: Request,
        private val responseProvider: (Request) -> Response
    ) : Call {
        private var executed = false
        private var canceled = false

        override fun request(): Request = request

        override fun execute(): Response {
            executed = true
            return responseProvider(request)
        }

        override fun enqueue(responseCallback: okhttp3.Callback) {
            executed = true
            if (canceled) {
                responseCallback.onFailure(this, IOException("Canceled"))
                return
            }
            thread(
                start = true,
                isDaemon = true,
                name = "fake-call-callback"
            ) {
                responseCallback.onResponse(this, responseProvider(request))
            }
        }

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FakeCall(request, responseProvider)
    }
}
