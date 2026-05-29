package com.codex.mobilecontrol.network

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.diagnostics.DebugTraceLogger
import com.codex.mobilecontrol.model.FileUploadSource
import com.codex.mobilecontrol.model.ImageUploadSource
import com.codex.mobilecontrol.model.LatestApkInfo
import com.codex.mobilecontrol.model.LoginResponse
import com.codex.mobilecontrol.model.MarkdownFilePreview
import com.codex.mobilecontrol.model.MobileAutomationItem
import com.codex.mobilecontrol.model.SendMessageResponse
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.model.ThreadMessagesPage
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GatewayApiClient(
    private val callFactory: Call.Factory = defaultHttpClient(),
    private val jsonParser: GatewayJsonParser = GatewayJsonParser()
) : GatewayApi {

    override suspend fun login(config: GatewayConfig): LoginResponse {
        val requestBody = JSONObject()
            .put("token", config.token)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(buildUrl(config, "api", "auth", "login"))
            .post(requestBody)
            .build()

        val responseBody = execute(request)
        return jsonParser.parseLoginResponse(responseBody)
    }

    override suspend fun getLatestApkInfo(config: GatewayConfig): LatestApkInfo {
        val request = Request.Builder()
            .url(buildUrl(config, "downloads", "latest.json"))
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

        val responseBody = execute(request)
        return jsonParser.parseLatestApkInfo(responseBody)
    }

    override suspend fun getDiagnosticsJson(config: GatewayConfig): String {
        val request = Request.Builder()
            .url(buildUrl(config, "api", "diagnostics"))
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

        return execute(request)
    }

    override suspend fun sendRealtimeLatencyProbe(
        config: GatewayConfig,
        probeId: String,
        sentAt: Long
    ) {
        val requestBody = JSONObject()
            .put("probeId", probeId)
            .put("sentAt", sentAt)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(buildUrl(config, "api", "realtime", "latency-probe"))
            .header("Authorization", "Bearer ${config.token}")
            .post(requestBody)
            .build()

        execute(request)
    }

    override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> {
        val request = Request.Builder()
            .url(buildUrl(config, "api", "threads"))
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

        val responseBody = execute(request)
        return jsonParser.parseThreadsResponse(responseBody)
    }

    override suspend fun getAutomations(config: GatewayConfig): List<MobileAutomationItem> {
        val request = Request.Builder()
            .url(buildUrl(config, "api", "automations"))
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

        val responseBody = execute(request)
        return jsonParser.parseAutomationsResponse(responseBody)
    }

    override suspend fun getThreadPreview(config: GatewayConfig, threadId: String): ThreadDetail {
        val request = Request.Builder()
            .url(buildUrl(config, "api", "threads", threadId, "preview"))
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

        val responseBody = execute(request)
        return resolveMessageUrls(config.url, jsonParser.parseThreadDetail(responseBody))
    }

    override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
        val request = Request.Builder()
            .url(buildUrl(config, "api", "threads", threadId))
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

        val responseBody = execute(request)
        return resolveMessageUrls(config.url, jsonParser.parseThreadDetail(responseBody))
    }

    override suspend fun getThreadMessages(
        config: GatewayConfig,
        threadId: String,
        beforeMessageId: String?,
        beforeTimestamp: String?,
        limit: Int
    ): ThreadMessagesPage {
        val url = buildUrl(config, "api", "threads", threadId, "messages")
            .toHttpUrl()
            .newBuilder()
            .apply {
                if (!beforeMessageId.isNullOrBlank()) {
                    addQueryParameter("before", beforeMessageId)
                }
                if (!beforeTimestamp.isNullOrBlank()) {
                    addQueryParameter("beforeTimestamp", beforeTimestamp)
                }
                addQueryParameter("limit", limit.toString())
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

        val responseBody = execute(request)
        val page = jsonParser.parseThreadMessagesResponse(responseBody)
        return page.copy(
            messages = page.messages.map { message ->
                message.copy(
                    imageUrl = resolveUrl(config.url, message.imageUrl),
                    thumbnailUrl = resolveUrl(config.url, message.thumbnailUrl)
                )
            }
        )
    }

    override suspend fun getNewThreadMessages(
        config: GatewayConfig,
        threadId: String,
        afterMessageId: String?,
        afterTimestamp: String?,
        limit: Int
    ): ThreadMessagesPage {
        val url = buildUrl(config, "api", "threads", threadId, "messages")
            .toHttpUrl()
            .newBuilder()
            .apply {
                if (!afterMessageId.isNullOrBlank()) {
                    addQueryParameter("after", afterMessageId)
                }
                if (!afterTimestamp.isNullOrBlank()) {
                    addQueryParameter("afterTimestamp", afterTimestamp)
                }
                addQueryParameter("limit", limit.toString())
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

        val responseBody = execute(request)
        val page = jsonParser.parseThreadMessagesResponse(responseBody)
        return page.copy(
            messages = page.messages.map { message ->
                message.copy(
                    imageUrl = resolveUrl(config.url, message.imageUrl),
                    thumbnailUrl = resolveUrl(config.url, message.thumbnailUrl)
                )
            }
        )
    }

    override suspend fun getMarkdownFilePreview(
        config: GatewayConfig,
        threadId: String,
        path: String
    ): MarkdownFilePreview {
        val url = buildUrl(config, "api", "threads", threadId, "markdown-preview")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("path", path)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

        val responseBody = execute(request)
        return jsonParser.parseMarkdownFilePreview(responseBody)
    }

    override suspend fun sendMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String
    ): SendMessageResponse {
        return sendTextMessage(
            config = config,
            threadId = threadId,
            text = text,
            clientMessageId = clientMessageId,
            guide = false,
            queue = false
        )
    }

    override suspend fun sendGuideMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String
    ): SendMessageResponse {
        return sendTextMessage(
            config = config,
            threadId = threadId,
            text = text,
            clientMessageId = clientMessageId,
            guide = true,
            queue = false
        )
    }

    override suspend fun sendQueuedMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String
    ): SendMessageResponse {
        return sendTextMessage(
            config = config,
            threadId = threadId,
            text = text,
            clientMessageId = clientMessageId,
            guide = false,
            queue = true
        )
    }

    private suspend fun sendTextMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String,
        guide: Boolean,
        queue: Boolean
    ): SendMessageResponse {
        val requestBody = JSONObject()
            .put("text", text)
            .put("clientMessageId", clientMessageId)
            .apply {
                if (guide) {
                    put("guide", true)
                }
                if (queue) {
                    put("queue", true)
                }
            }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(buildUrl(config, "api", "threads", threadId, "messages"))
            .header("Authorization", "Bearer ${config.token}")
            .post(requestBody)
            .build()

        val responseBody = execute(request)
        return jsonParser.parseSendMessageResponse(responseBody)
    }

    override suspend fun sendImageMessage(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        image: ImageUploadSource
    ): SendMessageResponse {
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("clientMessageId", clientMessageId)
            .apply {
                if (!text.isNullOrBlank()) {
                    addFormDataPart("text", text)
                }
                addFormDataPart(
                    "image",
                    image.fileName,
                    object : RequestBody() {
                        override fun contentType() = image.mimeType.toMediaTypeOrNull()

                        override fun writeTo(sink: BufferedSink) {
                            image.openStream().use { input ->
                                sink.writeAll(input.source())
                            }
                        }
                    }
                )
            }
            .build()

        val request = Request.Builder()
            .url(buildUrl(config, "api", "threads", threadId, "image-message"))
            .header("Authorization", "Bearer ${config.token}")
            .post(multipartBody)
            .build()

        val responseBody = execute(request)
        return jsonParser.parseSendMessageResponse(responseBody)
    }

    override suspend fun sendImageMessages(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        images: List<ImageUploadSource>
    ): SendMessageResponse {
        require(images.isNotEmpty()) { "images is empty" }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("clientMessageId", clientMessageId)
            .apply {
                if (!text.isNullOrBlank()) {
                    addFormDataPart("text", text)
                }
                images.forEach { image ->
                    addFormDataPart(
                        "images",
                        image.fileName,
                        object : RequestBody() {
                            override fun contentType() = image.mimeType.toMediaTypeOrNull()

                            override fun writeTo(sink: BufferedSink) {
                                image.openStream().use { input ->
                                    sink.writeAll(input.source())
                                }
                            }
                        }
                    )
                }
            }
            .build()

        val request = Request.Builder()
            .url(buildUrl(config, "api", "threads", threadId, "image-messages"))
            .header("Authorization", "Bearer ${config.token}")
            .post(multipartBody)
            .build()

        val responseBody = execute(request)
        return jsonParser.parseSendMessageResponse(responseBody)
    }

    override suspend fun sendFileMessages(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        files: List<FileUploadSource>
    ): SendMessageResponse {
        require(files.isNotEmpty()) { "files is empty" }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("clientMessageId", clientMessageId)
            .apply {
                if (!text.isNullOrBlank()) {
                    addFormDataPart("text", text)
                }
                files.forEach { file ->
                    addFormDataPart(
                        "files",
                        file.fileName,
                        object : RequestBody() {
                            override fun contentType() = file.mimeType.toMediaTypeOrNull()

                            override fun writeTo(sink: BufferedSink) {
                                file.openStream().use { input ->
                                    sink.writeAll(input.source())
                                }
                            }
                        }
                    )
                }
            }
            .build()

        val request = Request.Builder()
            .url(buildUrl(config, "api", "threads", threadId, "file-messages"))
            .header("Authorization", "Bearer ${config.token}")
            .post(multipartBody)
            .build()

        val responseBody = execute(request)
        return jsonParser.parseSendMessageResponse(responseBody)
    }

    override suspend fun sendAttachmentMessages(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        images: List<ImageUploadSource>,
        files: List<FileUploadSource>
    ): SendMessageResponse {
        require(images.isNotEmpty() || files.isNotEmpty()) { "attachments are empty" }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("clientMessageId", clientMessageId)
            .apply {
                if (!text.isNullOrBlank()) {
                    addFormDataPart("text", text)
                }
                images.forEach { image ->
                    addFormDataPart(
                        "images",
                        image.fileName,
                        object : RequestBody() {
                            override fun contentType() = image.mimeType.toMediaTypeOrNull()

                            override fun writeTo(sink: BufferedSink) {
                                image.openStream().use { input ->
                                    sink.writeAll(input.source())
                                }
                            }
                        }
                    )
                }
                files.forEach { file ->
                    addFormDataPart(
                        "files",
                        file.fileName,
                        object : RequestBody() {
                            override fun contentType() = file.mimeType.toMediaTypeOrNull()

                            override fun writeTo(sink: BufferedSink) {
                                file.openStream().use { input ->
                                    sink.writeAll(input.source())
                                }
                            }
                        }
                    )
                }
            }
            .build()

        val request = Request.Builder()
            .url(buildUrl(config, "api", "threads", threadId, "attachment-messages"))
            .header("Authorization", "Bearer ${config.token}")
            .post(multipartBody)
            .build()

        val responseBody = execute(request)
        return jsonParser.parseSendMessageResponse(responseBody)
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = callFactory.newCall(request)
        val completed = AtomicBoolean(false)
        val startedAtNanos = System.nanoTime()

        fun elapsedMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

        fun logFailure(error: IOException) {
            DebugTraceLogger.log(
                "gateway-http",
                "failure method=${request.method} url=${request.url} elapsedMs=${elapsedMillis()} " +
                    "error=${error.toHttpErrorSummary()}"
            )
        }

        fun resumeSuccess(value: String) {
            if (completed.compareAndSet(false, true)) {
                continuation.resume(value)
            }
        }

        fun resumeFailure(error: IOException) {
            if (completed.compareAndSet(false, true)) {
                logFailure(error)
                continuation.resumeWithException(error)
            }
        }

        continuation.invokeOnCancellation {
            completed.compareAndSet(false, true)
            call.cancel()
        }

        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    resumeFailure(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val responseBody = try {
                            response.body?.string()
                        } catch (e: IOException) {
                            resumeFailure(e)
                            return
                        }

                        if (!response.isSuccessful) {
                            val gatewayErrorMessage = parseGatewayErrorMessage(responseBody)
                            resumeFailure(
                                IOException(
                                    gatewayErrorMessage
                                        ?: buildHttpErrorMessage(request, response.code, responseBody)
                                )
                            )
                            return
                        }

                        if (responseBody.isNullOrBlank()) {
                            resumeFailure(
                                IOException(
                                    "Gateway request failed: ${request.method} ${request.url} returned empty response body"
                                )
                            )
                            return
                        }

                        resumeSuccess(responseBody)
                    }
                }
            }
        )
    }

    private fun buildHttpErrorMessage(request: Request, statusCode: Int, responseBody: String?): String {
        val bodySummary = responseBody
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.replace(Regex("\\s+"), " ")
            ?.let { body -> truncate(body, ERROR_BODY_SUMMARY_LIMIT) }

        return if (bodySummary == null) {
            "Gateway request failed: ${request.method} ${request.url} returned HTTP $statusCode"
        } else {
            "Gateway request failed: ${request.method} ${request.url} returned HTTP $statusCode; body=\"$bodySummary\""
        }
    }

    private fun parseGatewayErrorMessage(responseBody: String?): String? {
        val body = responseBody
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return runCatching {
            JSONObject(body)
                .optString("message")
                .trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun truncate(value: String, maxLength: Int): String {
        if (value.length <= maxLength) {
            return value
        }
        return value.take(maxLength) + "...(truncated)"
    }

    private fun IOException.toHttpErrorSummary(): String {
        val type = this::class.java.simpleName.ifBlank { "IOException" }
        val message = message
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() }
            ?: "no message"
        return "$type: ${truncate(message, ERROR_BODY_SUMMARY_LIMIT)}"
    }

    private fun buildUrl(config: GatewayConfig, vararg segments: String): String {
        val builder = config.url.trimEnd('/').toHttpUrl().newBuilder()
        segments.forEach { segment -> builder.addPathSegment(segment) }
        return builder.build().toString()
    }

    private fun resolveMessageUrls(baseUrl: String, detail: ThreadDetail): ThreadDetail {
        return detail.copy(
            recentMessages = detail.recentMessages.map { message ->
                message.copy(
                    imageUrl = resolveUrl(baseUrl, message.imageUrl),
                    thumbnailUrl = resolveUrl(baseUrl, message.thumbnailUrl)
                )
            }
        )
    }

    private fun resolveUrl(baseUrl: String, value: String?): String? {
        val candidate = value?.takeIf { it.isNotBlank() } ?: return null
        return when {
            candidate.startsWith("http://") || candidate.startsWith("https://") -> candidate
            candidate.startsWith("/") -> baseUrl.trimEnd('/') + candidate
            else -> baseUrl.trimEnd('/') + "/" + candidate.trimStart('/')
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val ERROR_BODY_SUMMARY_LIMIT = 240
        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
        }
    }
}
