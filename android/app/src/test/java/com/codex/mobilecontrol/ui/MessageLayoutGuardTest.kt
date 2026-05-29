package com.codex.mobilecontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MessageLayoutGuardTest {

    @Test
    fun `message content stretches across the full card width`() {
        val document = parseLayout()
        val messageContent = findElement(document, "messageContent")

        assertEquals("match_parent", messageContent.getAttributeNS(ANDROID_NS, "layout_width"))
    }

    @Test
    fun `message text stays bounded inside detail cards`() {
        val document = parseLayout()
        val messageText = findElement(document, "messageText")

        assertEquals("6", messageText.getAttributeNS(ANDROID_NS, "maxLines"))
        assertEquals("end", messageText.getAttributeNS(ANDROID_NS, "ellipsize"))
    }

    @Test
    fun `message text supports long press selection`() {
        val document = parseLayout()
        val messageText = findElement(document, "messageText")

        assertEquals("true", messageText.getAttributeNS(ANDROID_NS, "textIsSelectable"))
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
