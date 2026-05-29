package com.codex.mobilecontrol.model

enum class MobileThreadStatus(val wireValue: String) {
    RUNNING("running"),
    WAITING_INPUT("waiting_input"),
    ERROR("error"),
    COMPLETED("completed"),
    IDLE("idle"),
    OFFLINE("offline");

    companion object {
        fun fromWireValue(value: String?): MobileThreadStatus {
            return entries.firstOrNull { it.wireValue == value } ?: IDLE
        }
    }
}

enum class ThreadEventKind(val wireValue: String) {
    STATUS_CHANGED("status_changed"),
    TURN_STARTED("turn_started"),
    TURN_COMPLETED("turn_completed"),
    MESSAGE("message"),
    PLAN("plan"),
    COMMAND("command"),
    LOG_SIGNAL("log_signal"),
    ERROR("error");

    companion object {
        fun fromWireValue(value: String?): ThreadEventKind {
            return entries.firstOrNull { it.wireValue == value } ?: ERROR
        }
    }
}

enum class ThreadMessageRole(val wireValue: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun fromWireValue(value: String?): ThreadMessageRole {
            return entries.firstOrNull { it.wireValue == value } ?: SYSTEM
        }
    }
}

enum class ThreadMessageSendState(val wireValue: String) {
    SENDING("sending"),
    FAILED("failed");

    companion object {
        fun fromWireValue(value: String?): ThreadMessageSendState? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

enum class DetailRefreshStatus {
    UPDATED,
    UNCHANGED,
    FAILED
}

data class DetailRefreshResult(
    val status: DetailRefreshStatus,
    val changed: Boolean,
    val messageCount: Int,
    val lastMessageId: String?,
    val errorMessage: String? = null
)

enum class AlertTrigger(val wireValue: String) {
    WAITING_INPUT("waiting_input"),
    ERROR("error"),
    COMPLETED("completed"),
    MESSAGE("message");

    companion object {
        fun fromWireValue(value: String?): AlertTrigger {
            return entries.firstOrNull { it.wireValue == value } ?: ERROR
        }
    }
}

enum class GatewayConnectionMode(val wireValue: String) {
    SOCKET("socket"),
    SSE("sse");

    companion object {
        fun fromWireValue(value: String?): GatewayConnectionMode {
            if (value == "http") {
                return SSE
            }
            return entries.firstOrNull { it.wireValue == value } ?: SOCKET
        }
    }
}

data class ThreadListItem(
    val threadId: String,
    val title: String,
    val cwd: String,
    val status: MobileThreadStatus,
    val updatedAt: String,
    val progressSummary: String,
    val needsAttention: Boolean,
    val isPinned: Boolean = false,
    val automationActive: Boolean = false,
    val automationSummary: String? = null,
    val runningStartedAt: String? = null
)

data class MobileAutomationItem(
    val id: String,
    val name: String,
    val kind: String,
    val status: String,
    val scheduleSummary: String,
    val targetThreadId: String? = null,
    val targetThreadTitle: String? = null,
    val cwd: String? = null
)

data class ThreadEvent(
    val eventId: String,
    val threadId: String,
    val kind: ThreadEventKind,
    val status: MobileThreadStatus? = null,
    val text: String,
    val timestamp: String
)

data class ThreadMessage(
    val messageId: String,
    val threadId: String,
    val role: ThreadMessageRole,
    val kind: String,
    val text: String? = null,
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val fileUrl: String? = null,
    val fileName: String? = null,
    val timestamp: String,
    val sendState: ThreadMessageSendState? = null,
    val mimeType: String? = null
)

data class ThreadFileChangeItem(
    val path: String,
    val added: Int,
    val removed: Int
)

data class ThreadFileChanges(
    val summary: String,
    val changedFiles: Int,
    val added: Int,
    val removed: Int,
    val items: List<ThreadFileChangeItem>
)

data class ThreadComposerState(
    val permissionLabel: String,
    val modelLabel: String,
    val effortLabel: String? = null
)

data class PendingImageDraft(
    val previewUri: String,
    val fileName: String,
    val mimeType: String
)

data class PendingFileDraft(
    val previewUri: String,
    val fileName: String,
    val mimeType: String
)

enum class QueuedTextMessageStatus {
    PENDING,
    DISPATCHING,
    SENT,
    FAILED,
    CANCELLED
}

data class QueuedTextMessage(
    val threadId: String,
    val text: String,
    val queuedAtMillis: Long,
    val blockedThreadUpdatedAt: String? = null,
    val queueId: String = "queued-$queuedAtMillis",
    val status: QueuedTextMessageStatus = QueuedTextMessageStatus.PENDING,
    val dispatchStartedAtMillis: Long? = null,
    val errorMessage: String? = null
)

data class ThreadDetail(
    val thread: ThreadListItem,
    val recentMessages: List<ThreadMessage>,
    val recentEvents: List<ThreadEvent>,
    val sendAvailable: Boolean,
    val sendDisabledReason: String? = null,
    val fileChanges: ThreadFileChanges? = null,
    val composerState: ThreadComposerState? = null,
    val queuedTextMessages: List<QueuedTextMessage> = emptyList()
)

data class ThreadMessagesPage(
    val messages: List<ThreadMessage>,
    val nextCursor: String?
)

data class MarkdownFilePreview(
    val fileName: String,
    val path: String,
    val content: String,
    val sizeBytes: Long? = null
)

data class LatestApkInfo(
    val available: Boolean,
    val fileName: String? = null,
    val versionCode: Int? = null,
    val versionName: String? = null,
    val downloadUrl: String? = null
) {
    fun isNewerThan(currentVersionCode: Int): Boolean {
        return available && versionCode != null && versionCode > currentVersionCode
    }
}

data class Alert(
    val alertId: String,
    val threadId: String,
    val trigger: AlertTrigger,
    val title: String,
    val body: String,
    val timestamp: String
)

data class LoginResponse(
    val authenticated: Boolean
)

data class SendMessageResponse(
    val accepted: Boolean,
    val threadId: String,
    val clientMessageId: String,
    val sendPath: String,
    val confirmation: String,
    val warning: String? = null
)

data class GatewayStreamEvent(
    val type: String,
    val threadId: String?,
    val timestamp: String?,
    val probeId: String? = null,
    val sentAt: Long? = null
)
