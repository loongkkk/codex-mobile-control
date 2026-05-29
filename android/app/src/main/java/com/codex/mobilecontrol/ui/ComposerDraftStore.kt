package com.codex.mobilecontrol.ui

internal data class ComposerDraftChange(
    val text: String,
    val selection: Int
)

internal class ComposerDraftStore {
    private val draftsByThreadId = mutableMapOf<String, String>()
    private var activeThreadId: String? = null
    private var restoringDraft = false

    fun onTextChanged(text: String) {
        if (restoringDraft) {
            return
        }
        activeThreadId?.let { threadId ->
            draftsByThreadId[threadId] = text
        }
    }

    fun switchThread(threadId: String?, currentText: String): ComposerDraftChange? {
        if (activeThreadId == threadId) {
            return null
        }

        activeThreadId = threadId
        val draft = threadId?.let { draftsByThreadId[it] }.orEmpty()
        if (currentText == draft) {
            return null
        }
        return ComposerDraftChange(text = draft, selection = draft.length)
    }

    fun <T> withRestoringDraft(block: () -> T): T {
        restoringDraft = true
        return try {
            block()
        } finally {
            restoringDraft = false
        }
    }
}
