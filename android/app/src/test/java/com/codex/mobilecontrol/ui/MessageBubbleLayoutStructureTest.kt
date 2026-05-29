package com.codex.mobilecontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MessageBubbleLayoutStructureTest {

    @Test
    fun `message bubble hides role label and lets content start flush`() {
        val document = parseLayout()
        val messageRole = findElement(document, "messageRole")
        val messageText = findElement(document, "messageText")

        assertEquals("gone", messageRole.getAttributeNS(ANDROID_NS, "visibility"))
        assertEquals("0dp", messageText.getAttributeNS(ANDROID_NS, "layout_marginTop"))
    }

    private fun parseLayout(): Document {
        val candidates = listOf(
            File("app/src/main/res/layout/item_message.xml"),
            File("src/main/res/layout/item_message.xml")
        )
        val layoutFile = candidates.firstOrNull(File::isFile)
            ?: error("item_message.xml not found from ${System.getProperty("user.dir")}")

        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(layoutFile)
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

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
