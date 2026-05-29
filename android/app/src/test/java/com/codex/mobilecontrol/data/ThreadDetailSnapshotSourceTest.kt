package com.codex.mobilecontrol.data

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.model.ImageUploadSource
import com.codex.mobilecontrol.model.LoginResponse
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.SendMessageResponse
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.network.GatewayApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadDetailSnapshotSourceTest {

    @Test
    fun `concurrent snapshot fetches for the same thread share one network request`() = runTest {
        val releaseDetail = CompletableDeferred<Unit>()
        var detailCalls = 0
        val api = object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> = emptyList()

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
                detailCalls += 1
                releaseDetail.await()
                return sampleDetail(threadId)
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
        val config = GatewayConfig("http://snapshot-source-gateway", "snapshot-token")
        val threadId = "snapshot-source-thread"

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            ThreadDetailSnapshotSource.fetch(api, config, threadId)
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            ThreadDetailSnapshotSource.fetch(api, config, threadId)
        }
        advanceUntilIdle()

        assertEquals(1, detailCalls)

        releaseDetail.complete(Unit)

        assertEquals(threadId, first.await().thread.threadId)
        assertEquals(threadId, second.await().thread.threadId)
        assertEquals(1, detailCalls)
    }

    @Test
    fun `snapshot fetches from different api instances do not share in flight requests`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        var firstCalls = 0
        var secondCalls = 0
        val firstApi = blockingDetailApi(
            onCall = {
                firstCalls += 1
                releaseFirst.await()
            },
            detailTitle = "first api"
        )
        val secondApi = blockingDetailApi(
            onCall = {
                secondCalls += 1
            },
            detailTitle = "second api"
        )
        val config = GatewayConfig("http://snapshot-source-gateway", "snapshot-token")
        val threadId = "snapshot-source-thread"

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            ThreadDetailSnapshotSource.fetch(firstApi, config, threadId)
        }
        runCurrent()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            ThreadDetailSnapshotSource.fetch(secondApi, config, threadId)
        }
        runCurrent()

        try {
            assertEquals(1, firstCalls)
            assertEquals(1, secondCalls)
            assertTrue(second.isCompleted)
            assertEquals("second api", second.await().thread.title)
        } finally {
            releaseFirst.complete(Unit)
        }
        assertEquals("first api", first.await().thread.title)
    }

    private fun blockingDetailApi(
        onCall: suspend () -> Unit,
        detailTitle: String
    ): GatewayApi {
        return object : GatewayApi {
            override suspend fun login(config: GatewayConfig) = LoginResponse(authenticated = true)

            override suspend fun getThreads(config: GatewayConfig): List<ThreadListItem> = emptyList()

            override suspend fun getThreadDetail(config: GatewayConfig, threadId: String): ThreadDetail {
                onCall()
                val detail = sampleDetail(threadId)
                return detail.copy(thread = detail.thread.copy(title = detailTitle))
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
    }

    private fun sampleDetail(threadId: String): ThreadDetail {
        val thread = ThreadListItem(
            threadId = threadId,
            title = "Thread",
            cwd = "D:/repo",
            status = MobileThreadStatus.WAITING_INPUT,
            updatedAt = "2026-04-28T23:30:00Z",
            progressSummary = "等待输入",
            needsAttention = true
        )
        return ThreadDetail(
            thread = thread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = true
        )
    }
}
