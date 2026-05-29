package com.codex.mobilecontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerDraftStoreTest {
    @Test
    fun `stores drafts separately for each thread`() {
        val store = ComposerDraftStore()

        assertNull(store.switchThread(threadId = "thread-1", currentText = ""))
        store.onTextChanged("第一条草稿")

        assertEquals(
            ComposerDraftChange(text = "", selection = 0),
            store.switchThread(threadId = "thread-2", currentText = "第一条草稿")
        )
        store.onTextChanged("第二条草稿")

        assertEquals(
            ComposerDraftChange(text = "第一条草稿", selection = 5),
            store.switchThread(threadId = "thread-1", currentText = "第二条草稿")
        )
    }

    @Test
    fun `ignores programmatic text changes while restoring draft`() {
        val store = ComposerDraftStore()

        store.switchThread(threadId = "thread-1", currentText = "")
        store.onTextChanged("用户输入")
        store.withRestoringDraft {
            store.onTextChanged("程序恢复文本")
        }

        assertEquals(
            ComposerDraftChange(text = "", selection = 0),
            store.switchThread(threadId = "thread-2", currentText = "用户输入")
        )
        assertEquals(
            ComposerDraftChange(text = "用户输入", selection = 4),
            store.switchThread(threadId = "thread-1", currentText = "其他线程文本")
        )
    }

    @Test
    fun `does not request input changes when active thread or text already matches`() {
        val store = ComposerDraftStore()

        assertNull(store.switchThread(threadId = "thread-1", currentText = ""))
        store.onTextChanged("草稿")

        assertNull(store.switchThread(threadId = "thread-1", currentText = "草稿"))
        assertNull(store.switchThread(threadId = "thread-2", currentText = ""))
    }
}
