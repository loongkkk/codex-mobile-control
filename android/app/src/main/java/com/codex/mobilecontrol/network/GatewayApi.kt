package com.codex.mobilecontrol.network

import com.codex.mobilecontrol.GatewayConfig
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

interface GatewayApi {
    suspend fun login(config: GatewayConfig): LoginResponse

    suspend fun getLatestApkInfo(config: GatewayConfig): LatestApkInfo {
        return LatestApkInfo(available = false)
    }

    suspend fun getDiagnosticsJson(config: GatewayConfig): String {
        return """{"diagnosticsVersion":1,"runLogs":[]}"""
    }

    suspend fun sendRealtimeLatencyProbe(
        config: GatewayConfig,
        probeId: String,
        sentAt: Long
    ) = Unit

    suspend fun getThreads(config: GatewayConfig): List<ThreadListItem>

    suspend fun getAutomations(config: GatewayConfig): List<MobileAutomationItem> = emptyList()

    suspend fun getThreadPreview(config: GatewayConfig, threadId: String): ThreadDetail {
        return getThreadDetail(config, threadId)
    }

    suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail

    suspend fun getThreadMessages(
        config: GatewayConfig,
        threadId: String,
        beforeMessageId: String?,
        beforeTimestamp: String?,
        limit: Int
    ): ThreadMessagesPage = ThreadMessagesPage(emptyList(), null)

    suspend fun getNewThreadMessages(
        config: GatewayConfig,
        threadId: String,
        afterMessageId: String?,
        afterTimestamp: String?,
        limit: Int
    ): ThreadMessagesPage = ThreadMessagesPage(emptyList(), afterMessageId)

    suspend fun getMarkdownFilePreview(
        config: GatewayConfig,
        threadId: String,
        path: String
    ): MarkdownFilePreview {
        throw UnsupportedOperationException("markdown previews are not supported by this GatewayApi")
    }

    suspend fun sendMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String
    ): SendMessageResponse

    suspend fun sendGuideMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String
    ): SendMessageResponse {
        return sendMessage(
            config = config,
            threadId = threadId,
            text = text,
            clientMessageId = clientMessageId
        )
    }

    suspend fun sendQueuedMessage(
        config: GatewayConfig,
        threadId: String,
        text: String,
        clientMessageId: String
    ): SendMessageResponse {
        return sendMessage(
            config = config,
            threadId = threadId,
            text = text,
            clientMessageId = clientMessageId
        )
    }

    suspend fun sendImageMessage(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        image: ImageUploadSource
    ): SendMessageResponse

    suspend fun sendImageMessages(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        images: List<ImageUploadSource>
    ): SendMessageResponse {
        var latest: SendMessageResponse? = null
        images.forEachIndexed { index, image ->
            latest = sendImageMessage(
                config = config,
                threadId = threadId,
                text = if (index == 0) text else null,
                clientMessageId = clientMessageId,
                image = image
            )
        }
        return latest ?: throw IllegalArgumentException("images is empty")
    }

    suspend fun sendFileMessages(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        files: List<FileUploadSource>
    ): SendMessageResponse {
        throw UnsupportedOperationException("file messages are not supported by this GatewayApi")
    }

    suspend fun sendAttachmentMessages(
        config: GatewayConfig,
        threadId: String,
        text: String?,
        clientMessageId: String,
        images: List<ImageUploadSource>,
        files: List<FileUploadSource>
    ): SendMessageResponse {
        throw UnsupportedOperationException("mixed attachment messages are not supported by this GatewayApi")
    }
}
