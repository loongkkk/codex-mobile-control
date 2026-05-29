package com.codex.mobilecontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DetailLayoutGuardTest {

    @Test
    fun `detail screenshot layout keeps thread title and status in top nav`() {
        val document = parseLayout()
        val detailTopBar = findElement(document, "detailTopBar")
        val detailTopTitleBlock = findElement(document, "detailTopTitleBlock")
        val detailRefreshButton = findElement(document, "detailRefreshButton")
        val detailAlertButton = findElement(document, "detailAlertButton")
        val detailTopTitle = findElement(document, "detailTopTitle")
        val detailTopSubtitleRow = findElement(document, "detailTopSubtitleRow")
        val detailSocketStatusDot = findElement(document, "detailSocketStatusDot")
        val detailTopSubtitle = findElement(document, "detailTopSubtitle")

        assertEquals("androidx.constraintlayout.widget.ConstraintLayout", detailTopBar.tagName)
        assertEquals(
            "@id/detailBackButton",
            detailTopTitleBlock.getAttributeNS(APP_NS, "layout_constraintStart_toEndOf")
        )
        assertEquals(
            "@id/detailRefreshButton",
            detailTopTitleBlock.getAttributeNS(APP_NS, "layout_constraintEnd_toStartOf")
        )
        assertEquals("", detailTopTitleBlock.getAttributeNS(ANDROID_NS, "paddingStart"))
        assertEquals("", detailTopTitleBlock.getAttributeNS(ANDROID_NS, "paddingEnd"))
        assertEquals("TextView", detailRefreshButton.tagName)
        assertEquals("@string/detail_refresh_label", detailRefreshButton.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@string/detail_refresh", detailRefreshButton.getAttributeNS(ANDROID_NS, "contentDescription"))
        assertEquals("", detailRefreshButton.getAttributeNS(ANDROID_NS, "src"))
        assertEquals(
            "@id/detailAlertButton",
            detailRefreshButton.getAttributeNS(APP_NS, "layout_constraintEnd_toStartOf")
        )
        assertEquals("@drawable/ic_app_bell", detailAlertButton.getAttributeNS(ANDROID_NS, "src"))
        assertEquals(
            "parent",
            detailAlertButton.getAttributeNS(APP_NS, "layout_constraintEnd_toEndOf")
        )
        assertEquals("@string/detail_page_title", detailTopTitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@string/detail_page_subtitle", detailTopSubtitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("match_parent", detailTopTitle.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("center", detailTopTitle.getAttributeNS(ANDROID_NS, "gravity"))
        assertEquals("1", detailTopTitle.getAttributeNS(ANDROID_NS, "maxLines"))
        assertEquals("end", detailTopTitle.getAttributeNS(ANDROID_NS, "ellipsize"))
        assertEquals("20sp", detailTopTitle.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("uniform", detailTopTitle.getAttributeNS(APP_NS, "autoSizeTextType"))
        assertEquals("16sp", detailTopTitle.getAttributeNS(APP_NS, "autoSizeMinTextSize"))
        assertEquals("20sp", detailTopTitle.getAttributeNS(APP_NS, "autoSizeMaxTextSize"))
        assertEquals("androidx.constraintlayout.widget.ConstraintLayout", detailTopSubtitleRow.tagName)
        assertEquals("match_parent", detailTopSubtitleRow.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("8dp", detailSocketStatusDot.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("8dp", detailSocketStatusDot.getAttributeNS(ANDROID_NS, "layout_height"))
        assertEquals("@drawable/bg_detail_model_dot", detailSocketStatusDot.getAttributeNS(ANDROID_NS, "background"))
        assertEquals("@string/thread_status_hidden", detailSocketStatusDot.getAttributeNS(ANDROID_NS, "contentDescription"))
        assertEquals("packed", detailSocketStatusDot.getAttributeNS(APP_NS, "layout_constraintHorizontal_chainStyle"))
        assertEquals(
            "@id/detailTopSubtitle",
            detailSocketStatusDot.getAttributeNS(APP_NS, "layout_constraintEnd_toStartOf")
        )
        assertEquals(
            "@id/detailSocketStatusDot",
            detailTopSubtitle.getAttributeNS(APP_NS, "layout_constraintStart_toEndOf")
        )
        assertEquals("wrap_content", detailTopSubtitle.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("center", detailTopSubtitle.getAttributeNS(ANDROID_NS, "gravity"))
        assertEquals("1", detailTopSubtitle.getAttributeNS(ANDROID_NS, "maxLines"))
        assertEquals("end", detailTopSubtitle.getAttributeNS(ANDROID_NS, "ellipsize"))
    }

    @Test
    fun `detail screenshot layout keeps compact first viewport spacing`() {
        val document = parseLayout()
        val detailPage = findElement(document, "detailPage")
        val detailTopBar = findElement(document, "detailTopBar")
        val detailScroll = findElement(document, "detailScroll")
        val detailContentCard = findElement(document, "detailContentCard")
        val composerCard = findElement(document, "composerCard")
        val pendingImageCard = findElement(document, "pendingImageCard")
        val composerStatus = findElement(document, "composerStatus")

        assertEquals("0dp", detailPage.getAttributeNS(ANDROID_NS, "paddingStart"))
        assertEquals("0dp", detailPage.getAttributeNS(ANDROID_NS, "paddingTop"))
        assertEquals("0dp", detailPage.getAttributeNS(ANDROID_NS, "paddingEnd"))
        assertEquals("0dp", detailPage.getAttributeNS(ANDROID_NS, "paddingBottom"))
        assertEquals("10dp", detailTopBar.getAttributeNS(ANDROID_NS, "layout_marginStart"))
        assertEquals("10dp", detailTopBar.getAttributeNS(ANDROID_NS, "layout_marginEnd"))
        assertEquals("10dp", detailScroll.getAttributeNS(ANDROID_NS, "layout_marginStart"))
        assertEquals("10dp", detailScroll.getAttributeNS(ANDROID_NS, "layout_marginEnd"))
        assertEquals("12dp", detailScroll.getAttributeNS(ANDROID_NS, "layout_marginTop"))
        assertEquals("8dp", detailScroll.getAttributeNS(ANDROID_NS, "paddingBottom"))
        assertEquals("20dp", detailContentCard.getAttributeNS(APP_NS, "cardCornerRadius"))
        assertEquals("10dp", composerCard.getAttributeNS(ANDROID_NS, "layout_marginStart"))
        assertEquals("10dp", composerCard.getAttributeNS(ANDROID_NS, "layout_marginEnd"))
        assertEquals("10dp", composerCard.getAttributeNS(ANDROID_NS, "layout_marginBottom"))
        assertEquals("10dp", pendingImageCard.getAttributeNS(ANDROID_NS, "layout_marginStart"))
        assertEquals("10dp", pendingImageCard.getAttributeNS(ANDROID_NS, "layout_marginEnd"))
        assertEquals("10dp", composerStatus.getAttributeNS(ANDROID_NS, "layout_marginEnd"))
    }

    @Test
    fun `detail page has foreground alert toggle and jump to bottom action`() {
        val document = parseLayout()
        val detailBackButton = findElement(document, "detailBackButton")
        val detailAlertButton = findElement(document, "detailAlertButton")
        val detailJumpToBottomButton = findElement(document, "detailJumpToBottomButton")

        assertEquals("@string/detail_back", detailBackButton.getAttributeNS(ANDROID_NS, "contentDescription"))
        assertEquals("@drawable/ic_app_bell", detailAlertButton.getAttributeNS(ANDROID_NS, "src"))
        assertEquals("@string/detail_alert_toggle", detailAlertButton.getAttributeNS(ANDROID_NS, "contentDescription"))
        assertEquals("@drawable/ic_detail_chevron_down", detailJumpToBottomButton.getAttributeNS(ANDROID_NS, "src"))
        assertEquals("@string/detail_jump_to_bottom", detailJumpToBottomButton.getAttributeNS(ANDROID_NS, "contentDescription"))
        assertEquals("gone", detailJumpToBottomButton.getAttributeNS(ANDROID_NS, "visibility"))
        assertEquals(
            "@id/composerCard",
            detailJumpToBottomButton.getAttributeNS(APP_NS, "layout_constraintBottom_toTopOf")
        )
        assertEquals(
            "parent",
            detailJumpToBottomButton.getAttributeNS(APP_NS, "layout_constraintEnd_toEndOf")
        )
        assertEquals(
            "parent",
            detailJumpToBottomButton.getAttributeNS(APP_NS, "layout_constraintStart_toStartOf")
        )
        assertEquals("", detailJumpToBottomButton.getAttributeNS(ANDROID_NS, "layout_marginEnd"))
    }

    @Test
    fun `detail primary actions keep at least forty eight dp touch targets`() {
        val document = parseLayout()
        val detailBackButton = findElement(document, "detailBackButton")
        val detailAlertButton = findElement(document, "detailAlertButton")
        val detailJumpToBottomButton = findElement(document, "detailJumpToBottomButton")
        val sendButtonFrame = findElement(document, "sendButtonFrame")
        val pickImageButton = findElement(document, "pickImageButton")

        assertEquals("48dp", detailBackButton.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("48dp", detailBackButton.getAttributeNS(ANDROID_NS, "layout_height"))
        assertEquals("48dp", detailAlertButton.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("48dp", detailAlertButton.getAttributeNS(ANDROID_NS, "layout_height"))
        assertEquals("48dp", detailJumpToBottomButton.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("48dp", detailJumpToBottomButton.getAttributeNS(ANDROID_NS, "layout_height"))
        assertEquals("48dp", sendButtonFrame.getAttributeNS(ANDROID_NS, "layout_height"))
        assertEquals("48dp", pickImageButton.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("48dp", pickImageButton.getAttributeNS(ANDROID_NS, "layout_height"))
    }

    @Test
    fun `detail composer and bottom nav stay compact like the target screenshot`() {
        val document = parseLayout()
        val composerCard = findElement(document, "composerCard")
        val composerToolbar = findElement(document, "composerToolbar")
        val composerInputRow = findElement(document, "composerInputRow")
        val messageInput = findElement(document, "messageInput")
        val composerActionRow = findElement(document, "composerActionRow")
        val composerScopeText = findElement(document, "composerScopeText")
        val composerModeChip = findElement(document, "composerModeChip")
        val composerModelText = findElement(document, "composerModelText")
        val sendButtonFrame = findElement(document, "sendButtonFrame")
        val bottomNavCard = findElement(document, "bottomNavCard")
        val navDetailLabel = findElement(document, "navDetailLabel")

        assertEquals("20dp", composerCard.getAttributeNS(APP_NS, "cardCornerRadius"))
        assertEquals("8dp", composerToolbar.getAttributeNS(ANDROID_NS, "paddingTop"))
        assertEquals("8dp", composerToolbar.getAttributeNS(ANDROID_NS, "paddingBottom"))
        assertEquals("8dp", composerToolbar.getAttributeNS(ANDROID_NS, "paddingStart"))
        assertEquals("8dp", composerToolbar.getAttributeNS(ANDROID_NS, "paddingEnd"))
        assertEquals("horizontal", composerInputRow.getAttributeNS(ANDROID_NS, "orientation"))
        assertEquals("bottom", composerInputRow.getAttributeNS(ANDROID_NS, "gravity"))
        assertEquals(composerInputRow, messageInput.parentNode)
        assertEquals(composerInputRow, sendButtonFrame.parentNode)
        assertEquals("0dp", messageInput.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("1", messageInput.getAttributeNS(ANDROID_NS, "layout_weight"))
        assertEquals("wrap_content", messageInput.getAttributeNS(ANDROID_NS, "layout_height"))
        assertEquals("36dp", messageInput.getAttributeNS(ANDROID_NS, "minHeight"))
        assertEquals("4", messageInput.getAttributeNS(ANDROID_NS, "maxLines"))
        assertEquals("textMultiLine|textCapSentences", messageInput.getAttributeNS(ANDROID_NS, "inputType"))
        assertEquals("6dp", composerActionRow.getAttributeNS(ANDROID_NS, "layout_marginTop"))
        assertEquals("1", composerScopeText.getAttributeNS(ANDROID_NS, "maxLines"))
        assertEquals("end", composerScopeText.getAttributeNS(ANDROID_NS, "ellipsize"))
        assertEquals("11sp", composerScopeText.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("12sp", composerModeChip.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("12sp", composerModelText.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("58dp", sendButtonFrame.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("48dp", sendButtonFrame.getAttributeNS(ANDROID_NS, "layout_height"))
        assertEquals("bottom", sendButtonFrame.getAttributeNS(ANDROID_NS, "layout_gravity"))
        assertEquals("8dp", sendButtonFrame.getAttributeNS(ANDROID_NS, "layout_marginStart"))
        assertEquals("72dp", bottomNavCard.getAttributeNS(ANDROID_NS, "layout_height"))
        assertEquals("4dp", bottomNavCard.getAttributeNS(ANDROID_NS, "layout_marginBottom"))
        assertEquals("12sp", navDetailLabel.getAttributeNS(ANDROID_NS, "textSize"))
    }

    @Test
    fun `detail composer hides custom mode chip copy`() {
        val document = parseLayout()
        val composerModeChip = findElement(document, "composerModeChip")

        assertEquals("gone", composerModeChip.getAttributeNS(ANDROID_NS, "visibility"))
    }

    @Test
    fun `detail content card omits duplicated thread metadata header`() {
        val document = parseLayout()

        assertEquals(null, findElementOrNull(document, "detailThreadIconCard"))
        assertEquals(null, findElementOrNull(document, "detailThreadTextBlock"))
        assertEquals(null, findElementOrNull(document, "threadTitle"))
        assertEquals(null, findElementOrNull(document, "detailRepoChip"))
        assertEquals(null, findElementOrNull(document, "detailElapsedText"))
    }

    private fun parseLayout(): Document {
        val candidates = listOf(
            File("app/src/main/res/layout/activity_main.xml"),
            File("src/main/res/layout/activity_main.xml")
        )
        val layoutFile = candidates.firstOrNull(File::isFile)
            ?: error("activity_main.xml not found from ${System.getProperty("user.dir")}")

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

    private fun findElementOrNull(document: Document, id: String): Element? {
        val nodes = document.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val elementId = element.getAttributeNS(ANDROID_NS, "id")
            if (elementId == "@+id/$id" || elementId == "@id/$id") {
                return element
            }
        }
        return null
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val APP_NS = "http://schemas.android.com/apk/res-auto"
    }
}
