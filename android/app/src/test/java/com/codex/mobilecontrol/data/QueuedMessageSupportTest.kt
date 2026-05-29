package com.codex.mobilecontrol.data

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.model.MobileThreadStatus
import com.codex.mobilecontrol.model.QueuedTextMessageStatus
import com.codex.mobilecontrol.model.QueuedTextMessage
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.model.ThreadListItem
import com.codex.mobilecontrol.ui.MainUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueuedMessageSupportTest {
    @Test
    fun `blank queued text is ignored`() {
        val plan = QueuedMessageSupport.planQueueText(
            state = state(detail = detail(status = MobileThreadStatus.RUNNING, sendAvailable = false)),
            text = "   ",
            queuedAtMillis = 12L,
            queueId = "queue-1"
        )

        assertEquals(QueuedTextPlan.Blank, plan)
    }

    @Test
    fun `running thread stores trimmed text for later dispatch`() {
        val plan = QueuedMessageSupport.planQueueText(
            state = state(detail = detail(status = MobileThreadStatus.RUNNING, sendAvailable = false)),
            text = "  继续优化  ",
            queuedAtMillis = 12L,
            queueId = "queue-1"
        )

        assertEquals(
            QueuedTextPlan.Queue(
                QueuedTextMessage(
                    threadId = "thread-1",
                    text = "继续优化",
                    queuedAtMillis = 12L,
                    blockedThreadUpdatedAt = "2026-05-08T00:00:00Z",
                    queueId = "queue-1",
                    status = QueuedTextMessageStatus.PENDING
                )
            ),
            plan
        )
    }

    @Test
    fun `completed sendable thread sends queued text immediately`() {
        val plan = QueuedMessageSupport.planQueueText(
            state = state(detail = detail(status = MobileThreadStatus.COMPLETED, sendAvailable = true)),
            text = "  继续优化  ",
            queuedAtMillis = 12L,
            queueId = "queue-1"
        )

        assertEquals(QueuedTextPlan.SendNow("继续优化"), plan)
    }

    @Test
    fun `queued text becomes dispatchable only for matching sendable detail`() {
        val queued = QueuedTextMessage(
            threadId = "thread-1",
            text = "继续优化",
            queuedAtMillis = 12L
        )

        assertNull(
            QueuedMessageSupport.dispatchableQueuedText(
                state(
                    detail = detail(status = MobileThreadStatus.RUNNING, sendAvailable = false),
                    queuedTextMessages = listOf(queued)
                )
            )
        )
        assertEquals(
            queued,
            QueuedMessageSupport.dispatchableQueuedText(
                state(
                    detail = detail(status = MobileThreadStatus.COMPLETED, sendAvailable = true),
                    queuedTextMessages = listOf(queued)
                )
            )
        )
    }

    @Test
    fun `queued text ignores sendable snapshots that are not newer than the blocked turn`() {
        val queued = QueuedTextMessage(
            threadId = "thread-1",
            text = "继续优化",
            queuedAtMillis = 12L,
            blockedThreadUpdatedAt = "2026-05-08T00:01:00Z"
        )

        assertNull(
            QueuedMessageSupport.dispatchableQueuedText(
                state(
                    detail = detail(
                        status = MobileThreadStatus.COMPLETED,
                        sendAvailable = true,
                        updatedAt = "2026-05-08T00:00:30Z"
                    ),
                    queuedTextMessages = listOf(queued)
                )
            )
        )
        assertNull(
            QueuedMessageSupport.dispatchableQueuedText(
                state(
                    detail = detail(
                        status = MobileThreadStatus.COMPLETED,
                        sendAvailable = true,
                        updatedAt = "2026-05-08T00:01:00Z"
                    ),
                    queuedTextMessages = listOf(queued)
                )
            )
        )
        assertEquals(
            queued,
            QueuedMessageSupport.dispatchableQueuedText(
                state(
                    detail = detail(
                        status = MobileThreadStatus.COMPLETED,
                        sendAvailable = true,
                        updatedAt = "2026-05-08T00:01:01Z"
                    ),
                    queuedTextMessages = listOf(queued)
                )
            )
        )
    }

    @Test
    fun `dispatchable queued text uses the first visible item and waits behind failures`() {
        val failed = queuedMessage(
            id = "queue-1",
            text = "第一条",
            status = QueuedTextMessageStatus.FAILED
        )
        val pending = queuedMessage(
            id = "queue-2",
            text = "第二条",
            status = QueuedTextMessageStatus.PENDING
        )

        assertNull(
            QueuedMessageSupport.dispatchableQueuedText(
                state(
                    detail = detail(status = MobileThreadStatus.COMPLETED, sendAvailable = true),
                    queuedTextMessages = listOf(failed, pending)
                )
            )
        )

        assertEquals(
            pending,
            QueuedMessageSupport.dispatchableQueuedText(
                state(
                    detail = detail(
                        status = MobileThreadStatus.COMPLETED,
                        sendAvailable = true,
                        updatedAt = "2026-05-08T00:00:01Z"
                    ),
                    queuedTextMessages = listOf(pending)
                )
            )
        )
    }

    private fun state(
        detail: ThreadDetail?,
        queuedTextMessages: List<QueuedTextMessage> = emptyList()
    ) = MainUiState(
        config = GatewayConfig(url = "http://127.0.0.1:43124", token = "token"),
        isConnected = true,
        selectedThreadId = "thread-1",
        detail = detail,
        queuedTextMessages = queuedTextMessages
    )

    private fun queuedMessage(
        id: String,
        text: String,
        status: QueuedTextMessageStatus
    ) = QueuedTextMessage(
        threadId = "thread-1",
        text = text,
        queuedAtMillis = 12L,
        blockedThreadUpdatedAt = "2026-05-08T00:00:00Z",
        queueId = id,
        status = status
    )

    private fun detail(
        status: MobileThreadStatus,
        sendAvailable: Boolean,
        updatedAt: String = "2026-05-08T00:00:00Z"
    ): ThreadDetail {
        val thread = ThreadListItem(
            threadId = "thread-1",
            title = "线程",
            cwd = "/tmp",
            status = status,
            updatedAt = updatedAt,
            progressSummary = "状态",
            needsAttention = false
        )
        return ThreadDetail(
            thread = thread,
            recentMessages = emptyList(),
            recentEvents = emptyList(),
            sendAvailable = sendAvailable
        )
    }
}
