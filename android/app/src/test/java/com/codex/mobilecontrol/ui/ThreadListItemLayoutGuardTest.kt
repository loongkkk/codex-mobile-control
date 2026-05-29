package com.codex.mobilecontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ThreadListItemLayoutGuardTest {

    @Test
    fun `thread list title row gives priority to project title and hides repo chip`() {
        val document = parseLayout()
        val threadTitle = findElement(document, "threadTitle")
        val threadChip = findElement(document, "threadChip")
        val threadSummary = findElement(document, "threadSummary")

        assertEquals("0dp", threadTitle.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("1", threadTitle.getAttributeNS(ANDROID_NS, "layout_weight"))
        assertEquals("gone", threadChip.getAttributeNS(ANDROID_NS, "visibility"))
        assertEquals("match_parent", threadSummary.getAttributeNS(ANDROID_NS, "layout_width"))
    }

    @Test
    fun `compact thread row reserves a leading status slot without shifting the title`() {
        val document = parseCompactLayout()
        val row = findElement(document, "threadCompactRow")
        val statusSlot = findElement(document, "threadStatusDotSlot")
        val statusDot = findElement(document, "threadStatusDot")
        val pinIcon = findElement(document, "threadPinIcon")
        val threadTitle = findElement(document, "threadCompactTitle")
        val threadTime = findElement(document, "threadCompactTime")

        assertEquals("9dp", row.getAttributeNS(ANDROID_NS, "paddingStart"))
        assertEquals("19dp", statusSlot.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("invisible", statusDot.getAttributeNS(ANDROID_NS, "visibility"))
        assertEquals("9dp", statusDot.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("gone", pinIcon.getAttributeNS(ANDROID_NS, "visibility"))
        assertEquals("@drawable/ic_app_pin", pinIcon.getAttributeNS(APP_NS, "srcCompat"))
        assertNodeAppearsBefore(document, statusSlot, threadTitle)
        assertNodeAppearsBefore(document, threadTitle, pinIcon)
        assertNodeAppearsBefore(document, pinIcon, threadTime)
    }

    @Test
    fun `thread adapter keeps pinned icon hidden while status indicator remains independent`() {
        val source = readThreadListAdapter()

        assertTrue(source.contains("binding.threadPinIcon.visibility = View.GONE"))
        assertTrue(source.contains("bindStatusIndicator(item)"))
        assertTrue(source.contains("bindPinnedIndicator()"))
        assertFalse(
            source.contains("if (thread.isPinned) View.VISIBLE")
        )
    }

    @Test
    fun `thread adapter skips unchanged rows and keeps running pulse stable`() {
        val source = readThreadListAdapter()

        assertTrue(source.contains("private var lastSubmittedRows: List<ThreadListRow> = emptyList()"))
        assertTrue(source.contains("val rows = ThreadListDisplaySupport.buildRows(threads, readSettledIndicatorKeys)"))
        assertTrue(source.contains("rows == lastSubmittedRows"))
        assertTrue(source.contains("readSettledIndicatorKeys == lastSubmittedReadSettledIndicatorKeys"))
        assertTrue(source.contains("private var boundStatusState: ThreadStatusIndicatorSupport.IndicatorState? = null"))
        assertTrue(source.contains("if (indicatorState == boundStatusState)"))
        assertTrue(source.contains("boundStatusState = indicatorState"))
    }

    @Test
    fun `thread adapter can hide read settled status dots without hiding future settled dots`() {
        val source = readThreadListAdapter()

        assertTrue(source.contains("readSettledIndicatorKeys: Map<String, String?>"))
        assertTrue(source.contains("private var lastSubmittedReadSettledIndicatorKeys: Map<String, String?> = emptyMap()"))
        assertTrue(source.contains("readSettledKey = item.readSettledIndicatorKey"))
        assertTrue(source.contains("ThreadStatusIndicatorSupport.visibleStateFor("))
    }

    private fun parseLayout(): Document {
        val candidates = listOf(
            File("app/src/main/res/layout/item_thread.xml"),
            File("src/main/res/layout/item_thread.xml")
        )
        val layoutFile = candidates.firstOrNull(File::isFile)
            ?: error("item_thread.xml not found from ${System.getProperty("user.dir")}")

        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(layoutFile)
    }

    private fun parseCompactLayout(): Document {
        val candidates = listOf(
            File("app/src/main/res/layout/item_thread_compact.xml"),
            File("src/main/res/layout/item_thread_compact.xml")
        )
        val layoutFile = candidates.firstOrNull(File::isFile)
            ?: error("item_thread_compact.xml not found from ${System.getProperty("user.dir")}")

        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(layoutFile)
    }

    private fun readThreadListAdapter(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/ui/ThreadListAdapter.kt"),
            File("src/main/java/com/codex/mobilecontrol/ui/ThreadListAdapter.kt")
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
            ?: error("ThreadListAdapter.kt not found from ${System.getProperty("user.dir")}")
        return sourceFile.readText()
    }

    private fun findElement(document: Document, id: String): Element {
        val nodes = document.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val elementId = element.getAttributeNS(ANDROID_NS, "id")
            if (elementId == "@+id/$id" || elementId == "@id/$id") {
                return element
            }
        }
        error("View with id '$id' was not found")
    }

    private fun assertNodeAppearsBefore(document: Document, first: Element, second: Element) {
        val nodes = document.getElementsByTagName("*")
        var firstIndex = -1
        var secondIndex = -1
        for (index in 0 until nodes.length) {
            when (nodes.item(index)) {
                first -> firstIndex = index
                second -> secondIndex = index
            }
        }
        check(firstIndex >= 0 && secondIndex >= 0)
        assertTrue(firstIndex < secondIndex)
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val APP_NS = "http://schemas.android.com/apk/res-auto"
    }
}
