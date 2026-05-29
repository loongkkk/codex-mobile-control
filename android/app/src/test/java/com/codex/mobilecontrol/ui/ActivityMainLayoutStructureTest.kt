package com.codex.mobilecontrol.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ActivityMainLayoutStructureTest {

    @Test
    fun `nested detail children constrain within detail page`() {
        val document = parseLayout()
        val detailPage = findElement(document, "detailPage")
        val profilePage = findElement(document, "profilePage")
        val composerCard = findElement(document, "composerCard")

        assertEquals(detailPage, profilePage.parentNode)
        assertEquals(
            "parent",
            profilePage.getAttributeNS(APP_NS, "layout_constraintBottom_toBottomOf")
        )
        assertEquals(detailPage, composerCard.parentNode)
        assertEquals(
            "parent",
            composerCard.getAttributeNS(APP_NS, "layout_constraintBottom_toBottomOf")
        )
    }

    @Test
    fun `detail scroll container only constrains against siblings inside detail page`() {
        val document = parseLayout()
        val detailScroll = findElement(document, "detailScroll")
        val bottomTargetReference = detailScroll.getAttributeNS(
            APP_NS,
            "layout_constraintBottom_toTopOf"
        )
        val bottomTarget = findElement(
            document,
            bottomTargetReference.substringAfter("/").takeIf { it.isNotBlank() }
                ?: error("detailScroll bottom constraint is missing")
        )
        val topTargetReference = detailScroll.getAttributeNS(
            APP_NS,
            "layout_constraintTop_toBottomOf"
        )
        val topTarget = findElement(
            document,
            topTargetReference.substringAfter("/").takeIf { it.isNotBlank() }
                ?: error("detailScroll top constraint is missing")
        )

        assertEquals(detailScroll.parentNode, bottomTarget.parentNode)
        assertEquals(detailScroll.parentNode, topTarget.parentNode)
        assertEquals("detailSocketWarning", topTarget.getAttributeNS(ANDROID_NS, "id").substringAfter("/"))
    }

    @Test
    fun `detail page uses a single nested scroll container with a primary content card`() {
        val document = parseLayout()

        assertNotNull(findElementOrNull(document, "detailScroll"))
        assertNotNull(findElementOrNull(document, "detailContentCard"))
        assertNotNull(findElementOrNull(document, "detailConversationList"))
        assertNull(findElementOrNull(document, "detailTimelineRecycler"))
        assertNull(findElementOrNull(document, "eventsRecycler"))
        assertNull(findElementOrNull(document, "messagesRecycler"))
    }

    @Test
    fun `detail page keeps target screenshot sections inside the main content card`() {
        val document = parseLayout()

        assertNotNull(findElementOrNull(document, "detailBodyText"))
        assertNotNull(findElementOrNull(document, "detailTodoCard"))
        assertNotNull(findElementOrNull(document, "detailFileChangesCard"))
        assertNull(findElementOrNull(document, "detailRepoChip"))
    }

    @Test
    fun `detail next step suggestions card stays hidden`() {
        val document = parseLayout()
        val todoCard = findElement(document, "detailTodoCard")

        assertEquals("gone", todoCard.getAttributeNS(ANDROID_NS, "visibility"))
    }

    @Test
    fun `detail file changes card starts collapsed with summary and chevron header`() {
        val document = parseLayout()
        val header = findElement(document, "detailFileChangesHeader")
        val title = findElement(document, "detailFileChangesTitle")
        val icon = findElement(document, "detailFileChangesToggleIcon")
        val list = findElement(document, "detailFileChangesList")

        assertEquals(header, title.parentNode)
        assertEquals(header, icon.parentNode)
        assertEquals("@drawable/ic_detail_chevron_down", icon.getAttributeNS(ANDROID_NS, "src"))
        assertEquals("gone", list.getAttributeNS(ANDROID_NS, "visibility"))
        assertNull(findElementOrNull(document, "detailFileChangesSummary"))
    }

    @Test
    fun `composer uses text send button in the input row`() {
        val document = parseLayout()
        val composerInputRow = findElement(document, "composerInputRow")
        val messageInput = findElement(document, "messageInput")
        val sendButtonFrame = findElement(document, "sendButtonFrame")
        val sendButton = findElement(document, "sendButton")

        assertEquals(composerInputRow, messageInput.parentNode)
        assertEquals(composerInputRow, sendButtonFrame.parentNode)
        assertEquals("TextView", sendButton.tagName)
        assertEquals("@string/send_message", sendButton.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("", sendButton.getAttributeNS(ANDROID_NS, "src"))
    }

    @Test
    fun `composer permission and model labels are display only without dropdown icons`() {
        val document = parseLayout()
        val scopeGroup = findElement(document, "composerScopeGroup")
        val modelGroup = findElement(document, "composerModelGroup")

        assertNull(findDirectChildByDrawable(scopeGroup, "@drawable/ic_detail_chevron_down"))
        assertNull(findDirectChildByDrawable(modelGroup, "@drawable/ic_detail_chevron_down"))
    }

    @Test
    fun `task page removes duplicate title copy and keeps a content container`() {
        val document = parseLayout()

        assertNull(findElementOrNull(document, "taskPageTitle"))
        assertNull(findElementOrNull(document, "taskPageSubtitle"))
        assertNotNull(findElementOrNull(document, "taskPageContent"))
    }

    @Test
    fun `task page uses one desktop style grouped thread list without separate chat preview`() {
        val document = parseLayout()

        assertNotNull(findElementOrNull(document, "threadDrawerRecycler"))
        assertNull(findElementOrNull(document, "chatPreviewCard"))
        assertNull(findElementByTextReferenceOrNull(document, "@string/chat_section_title"))
        assertNull(findElementByTextReferenceOrNull(document, "@string/task_page_hint"))
    }

    @Test
    fun `task and detail pages expose socket warning banners outside status dots`() {
        val document = parseLayout()
        val taskSocketWarning = findElement(document, "taskSocketWarning")
        val detailSocketWarning = findElement(document, "detailSocketWarning")
        val detailScroll = findElement(document, "detailScroll")

        assertEquals("gone", taskSocketWarning.getAttributeNS(ANDROID_NS, "visibility"))
        assertEquals("@string/socket_warning_disconnected", taskSocketWarning.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("gone", detailSocketWarning.getAttributeNS(ANDROID_NS, "visibility"))
        assertEquals("@string/socket_warning_disconnected", detailSocketWarning.getAttributeNS(ANDROID_NS, "text"))
        assertEquals(
            "@id/detailSocketWarning",
            detailScroll.getAttributeNS(APP_NS, "layout_constraintTop_toBottomOf")
        )
    }

    @Test
    fun `task page prevents recycler focus from auto scrolling the outer page`() {
        val document = parseLayout()
        val taskPage = findElement(document, "taskPage")
        val taskPageContent = findElement(document, "taskPageContent")
        val threadDrawerRecycler = findElement(document, "threadDrawerRecycler")

        assertEquals("true", taskPage.getAttributeNS(ANDROID_NS, "focusable"))
        assertEquals("true", taskPage.getAttributeNS(ANDROID_NS, "focusableInTouchMode"))
        assertEquals(
            "blocksDescendants",
            taskPageContent.getAttributeNS(ANDROID_NS, "descendantFocusability")
        )
        assertEquals("false", threadDrawerRecycler.getAttributeNS(ANDROID_NS, "focusable"))
        assertEquals(
            "false",
            threadDrawerRecycler.getAttributeNS(ANDROID_NS, "focusableInTouchMode")
        )
    }

    @Test
    fun `task page header exposes refresh and mark all read actions`() {
        val document = parseLayout()
        val refreshButton = findElement(document, "taskRefreshButton")
        val markAllReadButton = findElement(document, "taskMarkAllReadButton")

        assertEquals("TextView", refreshButton.tagName)
        assertEquals("@string/task_refresh_label", refreshButton.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@string/task_refresh", refreshButton.getAttributeNS(ANDROID_NS, "contentDescription"))
        assertEquals("", refreshButton.getAttributeNS(ANDROID_NS, "src"))
        assertEquals("TextView", markAllReadButton.tagName)
        assertEquals("@string/task_mark_all_read_label", markAllReadButton.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@string/task_mark_all_read", markAllReadButton.getAttributeNS(ANDROID_NS, "contentDescription"))
        assertEquals(
            refreshButton.getAttributeNS(ANDROID_NS, "textColor"),
            markAllReadButton.getAttributeNS(ANDROID_NS, "textColor")
        )
        assertNull(findElementOrNull(document, "taskBellBadge"))
    }

    @Test
    fun `profile page includes a download latest apk row`() {
        val document = parseLayout()

        assertNotNull(findElementOrNull(document, "profileDownloadApkRow"))
    }

    @Test
    fun `login page asks for gateway url and masks access token`() {
        val document = parseLayout()
        val gatewayUrlInput = findElement(document, "gatewayUrlInput")
        val tokenInput = findElement(document, "tokenInput")
        val gatewayUrlLabel = findElementByTextReferenceOrNull(document, "@string/gateway_url_label")

        assertNotNull(gatewayUrlLabel)
        assertEquals("textUri", gatewayUrlInput.getAttributeNS(ANDROID_NS, "inputType"))
        assertEquals("@string/gateway_url_hint", gatewayUrlInput.getAttributeNS(ANDROID_NS, "hint"))
        assertEquals(
            "textPassword|textNoSuggestions",
            tokenInput.getAttributeNS(ANDROID_NS, "inputType")
        )
    }

    @Test
    fun `login page matches compact glass reference layout`() {
        val document = parseLayout()
        val loginContentRoot = findElement(document, "loginContentRoot")
        val loginBrandHeader = findElement(document, "loginBrandHeader")
        val loginBrandIcon = findElement(document, "loginBrandIcon")
        val loginBrandTextBlock = findElement(document, "loginBrandTextBlock")
        val loginHeroTitle = findElement(document, "loginHeroTitle")
        val loginBrandSubtitle = findElement(document, "loginBrandSubtitle")
        val loginCard = findElement(document, "loginCard")
        val gatewayUrlLabel = findElement(document, "gatewayUrlLabel")
        val gatewayUrlField = findElement(document, "gatewayUrlField")
        val tokenField = findElement(document, "tokenField")
        val tokenLabel = findElement(document, "tokenLabel")
        val gatewayUrlInput = findElement(document, "gatewayUrlInput")
        val tokenInput = findElement(document, "tokenInput")
        val connectButton = findElement(document, "connectButton")
        val connectButtonFrame = findElement(document, "connectButtonFrame")
        val loginSecurityNote = findElement(document, "loginSecurityNote")
        val helperText = findElement(document, "helperText")

        assertEquals("30dp", loginContentRoot.getAttributeNS(ANDROID_NS, "paddingStart"))
        assertEquals("88dp", loginContentRoot.getAttributeNS(ANDROID_NS, "paddingTop"))
        assertEquals("20dp", loginBrandHeader.getAttributeNS(ANDROID_NS, "layout_marginTop"))
        assertEquals("56dp", loginBrandIcon.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("21dp", loginBrandTextBlock.getAttributeNS(ANDROID_NS, "layout_marginStart"))
        assertEquals("@string/login_brand_title", loginHeroTitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("21sp", loginHeroTitle.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("@string/login_brand_subtitle", loginBrandSubtitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("12sp", loginBrandSubtitle.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("56dp", loginCard.getAttributeNS(ANDROID_NS, "layout_marginTop"))
        assertEquals("28dp", loginCard.getAttributeNS(APP_NS, "cardCornerRadius"))
        assertEquals("13sp", gatewayUrlLabel.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("50dp", gatewayUrlField.getAttributeNS(ANDROID_NS, "layout_height"))
        assertEquals("12dp", gatewayUrlField.getAttributeNS(ANDROID_NS, "paddingStart"))
        assertEquals("14sp", gatewayUrlInput.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("13sp", tokenLabel.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("50dp", tokenField.getAttributeNS(ANDROID_NS, "layout_height"))
        assertEquals("12dp", tokenField.getAttributeNS(ANDROID_NS, "paddingStart"))
        assertEquals("14sp", tokenInput.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("52dp", connectButtonFrame.getAttributeNS(ANDROID_NS, "layout_height"))
        assertEquals("15sp", connectButton.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("horizontal", loginSecurityNote.getAttributeNS(ANDROID_NS, "orientation"))
        assertEquals("@string/login_security_note", helperText.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("12sp", helperText.getAttributeNS(ANDROID_NS, "textSize"))
        assertNull(findElementOrNull(document, "loginHeroIllustration"))
        assertNull(findElementByTextReferenceOrNull(document, "@string/login_card_title"))
    }

    @Test
    fun `profile page includes an export diagnostics row before updates`() {
        val document = parseLayout()
        val clearCacheRow = findElement(document, "profileClearCacheRow")
        val exportDiagnosticsRow = findElement(document, "profileExportDiagnosticsRow")
        val exportDiagnosticsTitle = findElement(document, "profileExportDiagnosticsTitle")
        val exportDiagnosticsSubtitle = findElement(document, "profileExportDiagnosticsSubtitle")
        val updateRow = findElement(document, "profileDownloadApkRow")
        val parent = updateRow.parentNode as Element

        assertEquals("@string/profile_export_diagnostics_title", exportDiagnosticsTitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@string/profile_export_diagnostics_subtitle", exportDiagnosticsSubtitle.getAttributeNS(ANDROID_NS, "text"))
        assertTrue(childIndex(parent, clearCacheRow) < childIndex(parent, exportDiagnosticsRow))
        assertTrue(childIndex(parent, exportDiagnosticsRow) < childIndex(parent, updateRow))
    }

    @Test
    fun `profile page includes a realtime channel setting row`() {
        val document = parseLayout()
        val notificationsRow = findElement(document, "profileNotificationsRow")
        val connectionModeRow = findElement(document, "profileConnectionModeRow")
        val connectionModeIcon = findElement(document, "profileConnectionModeIcon")
        val connectionModeTitle = findElement(document, "profileConnectionModeTitle")
        val connectionModeSubtitle = findElement(document, "profileConnectionModeSubtitle")
        val clearCacheRow = findElement(document, "profileClearCacheRow")
        val parent = connectionModeRow.parentNode as Element

        assertEquals("@string/profile_connection_mode_title", connectionModeTitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@string/profile_connection_mode_socket", connectionModeSubtitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@drawable/ic_app_settings", connectionModeIcon.getAttributeNS(ANDROID_NS, "src"))
        assertTrue(childIndex(parent, notificationsRow) < childIndex(parent, connectionModeRow))
        assertTrue(childIndex(parent, connectionModeRow) < childIndex(parent, clearCacheRow))
    }

    @Test
    fun `profile page includes realtime status and reconnect row after channel setting`() {
        val document = parseLayout()
        val socketStatusRow = findElement(document, "profileSocketStatusRow")
        val socketStatusDot = findElement(document, "profileSocketStatusDot")
        val socketStatusTitle = findElement(document, "profileSocketStatusTitle")
        val socketStatusSubtitle = findElement(document, "profileSocketStatusSubtitle")
        val reconnectButton = findElement(document, "profileSocketReconnectButton")
        val latencyButton = findElement(document, "profileSocketLatencyButton")
        val connectionModeRow = findElement(document, "profileConnectionModeRow")
        val parent = socketStatusRow.parentNode as Element

        assertEquals("@string/profile_socket_status_title", socketStatusTitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@string/profile_socket_status_unknown", socketStatusSubtitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@string/profile_socket_reconnect", reconnectButton.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@string/profile_socket_latency_test", latencyButton.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@drawable/bg_thread_status_dot", socketStatusDot.getAttributeNS(ANDROID_NS, "background"))
        assertTrue(childIndex(parent, connectionModeRow) < childIndex(parent, socketStatusRow))
    }

    @Test
    fun `profile page is fixed and exposes a clear cache row`() {
        val document = parseLayout()
        val profilePage = findElement(document, "profilePage")
        val profilePageContent = findElement(document, "profilePageContent")
        val clearCacheRow = findElement(document, "profileClearCacheRow")
        val clearCacheTitle = findElement(document, "profileClearCacheTitle")
        val clearCacheSubtitle = findElement(document, "profileClearCacheSubtitle")

        assertEquals("androidx.core.widget.NestedScrollView", profilePage.tagName)
        assertEquals("LinearLayout", profilePageContent.tagName)
        assertEquals("false", profilePage.getAttributeNS(ANDROID_NS, "clipToPadding"))
        assertEquals("true", profilePage.getAttributeNS(ANDROID_NS, "fillViewport"))
        assertEquals("@string/profile_clear_cache_title", clearCacheTitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("@string/profile_clear_cache_subtitle", clearCacheSubtitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("match_parent", clearCacheRow.getAttributeNS(ANDROID_NS, "layout_width"))
    }

    @Test
    fun `profile page uses compact update and current version rows`() {
        val document = parseLayout()
        val notificationSubtitle = findElement(document, "profileNotificationsSubtitle")
        val updateTitle = findElement(document, "profileDownloadApkTitle")
        val updateBadge = findElement(document, "profileUpdateBadge")
        val aboutRow = findElement(document, "profileAboutRow")
        val aboutVersion = findElement(document, "aboutVersion")

        assertEquals("visible", notificationSubtitle.getAttributeNS(ANDROID_NS, "visibility"))
        assertEquals("@string/profile_update_title", updateTitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("8dp", updateBadge.getAttributeNS(ANDROID_NS, "layout_width"))
        assertEquals("@drawable/bg_profile_update_badge", updateBadge.getAttributeNS(ANDROID_NS, "background"))
        assertEquals("gone", updateBadge.getAttributeNS(ANDROID_NS, "visibility"))
        assertEquals("false", aboutRow.getAttributeNS(ANDROID_NS, "clickable"))
        assertNull(findDirectChildByDrawable(aboutRow, "@drawable/ic_app_chevron_right"))
        assertEquals("gone", aboutVersion.getAttributeNS(ANDROID_NS, "visibility"))
    }

    @Test
    fun `profile page keeps update row adjacent to current version`() {
        val document = parseLayout()
        val clearCacheRow = findElement(document, "profileClearCacheRow")
        val updateRow = findElement(document, "profileDownloadApkRow")
        val currentVersionCard = findElement(document, "profileCurrentVersionCard")
        val parent = updateRow.parentNode

        assertEquals(parent, clearCacheRow.parentNode)
        assertEquals(parent, currentVersionCard.parentNode)
        assertTrue(childIndex(parent as Element, clearCacheRow) < childIndex(parent, updateRow))
        assertTrue(childIndex(parent, updateRow) < childIndex(parent, currentVersionCard))
    }

    @Test
    fun `profile page omits duplicate header actions and large page title`() {
        val document = parseLayout()

        assertNull(findElementOrNull(document, "profileBellButton"))
        assertNull(findElementOrNull(document, "profileBellBadge"))
        assertNull(findElementOrNull(document, "profileSettingsButton"))
        assertNull(findElementOrNull(document, "profilePageTitle"))
    }

    @Test
    fun `bottom nav temporarily hides overview tab and lays out three visible tabs`() {
        val document = parseLayout()
        val bottomNavItems = findElement(document, "bottomNavItems")
        val overview = findElement(document, "navOverview")

        assertEquals("3", bottomNavItems.getAttributeNS(ANDROID_NS, "weightSum"))
        assertEquals("gone", overview.getAttributeNS(ANDROID_NS, "visibility"))
    }

    @Test
    fun `bottom nav does not render selected top indicator bars`() {
        val document = parseLayout()

        assertNull(findElementOrNull(document, "navOverviewIndicator"))
        assertNull(findElementOrNull(document, "navTasksIndicator"))
        assertNull(findElementOrNull(document, "navDetailIndicator"))
        assertNull(findElementOrNull(document, "navProfileIndicator"))
    }

    @Test
    fun `profile page typography is compact`() {
        val document = parseLayout()
        val brandCard = findElement(document, "profileBrandCard")
        val brandTitle = findDescendantByTextReferenceOrNull(brandCard, "@string/app_name")
        val brandSubtitle = findDescendantByTextReferenceOrNull(brandCard, "@string/profile_brand_subtitle")

        assertNotNull(brandTitle)
        assertNotNull(brandSubtitle)
        assertEquals("18sp", brandTitle!!.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("12sp", brandSubtitle!!.getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("13sp", findElement(document, "profileNotificationsTitle").getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("11sp", findElement(document, "profileNotificationsSubtitle").getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("13sp", findElement(document, "profileDownloadApkTitle").getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("11sp", findElement(document, "profileDownloadApkSubtitle").getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("13sp", findElement(document, "profileClearCacheTitle").getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("11sp", findElement(document, "profileClearCacheSubtitle").getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("13sp", findElement(document, "profileAboutTitle").getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("11sp", findElement(document, "aboutVersion").getAttributeNS(ANDROID_NS, "textSize"))
        assertEquals("13sp", findElement(document, "profileLogoutTitle").getAttributeNS(ANDROID_NS, "textSize"))
    }

    @Test
    fun `profile page uses the approved space themed visual shell`() {
        val document = parseLayout()
        val profilePage = findElement(document, "profilePage")
        val brandCard = findElement(document, "profileBrandCard")
        val logo = findElement(document, "profileBrandLogo")
        val planet = findElement(document, "profileBrandPlanet")
        val currentVersionCard = findElement(document, "profileCurrentVersionCard")
        val notificationSubtitle = findElement(document, "profileNotificationsSubtitle")

        assertEquals("@drawable/bg_profile_screen", profilePage.getAttributeNS(ANDROID_NS, "background"))
        assertEquals("@color/profile_panel_surface", brandCard.getAttributeNS(APP_NS, "cardBackgroundColor"))
        assertEquals("@drawable/img_profile_cloud_logo", logo.getAttributeNS(ANDROID_NS, "src"))
        assertEquals("@drawable/img_profile_planet", planet.getAttributeNS(ANDROID_NS, "src"))
        assertEquals("visible", notificationSubtitle.getAttributeNS(ANDROID_NS, "visibility"))
        assertEquals("@string/profile_notifications_subtitle", notificationSubtitle.getAttributeNS(ANDROID_NS, "text"))
        assertEquals("com.google.android.material.card.MaterialCardView", currentVersionCard.tagName)
        assertEquals("false", findElement(document, "profileAboutRow").getAttributeNS(ANDROID_NS, "clickable"))
    }

    @Test
    fun `layout keeps explicit progress indicators for login detail switch and send actions`() {
        val document = parseLayout()

        assertNotNull(findElementOrNull(document, "connectProgress"))
        assertNotNull(findElementOrNull(document, "detailTransitionProgress"))
        assertNotNull(findElementOrNull(document, "sendProgress"))
    }

    @Test
    fun `layout includes a dedicated motion overlay for visible loading transitions`() {
        val document = parseLayout()

        assertNotNull(findElementOrNull(document, "motionOverlay"))
        assertNotNull(findElementOrNull(document, "motionOverlayCard"))
        assertNotNull(findElementOrNull(document, "motionOverlayTitle"))
        assertNotNull(findElementOrNull(document, "motionOverlaySubtitle"))
    }

    @Test
    fun `detail header removes duplicate detail title copy`() {
        val document = parseLayout()

        assertNotNull(findElementOrNull(document, "detailTopTitle"))
        assertNotNull(findElementOrNull(document, "detailTopSubtitle"))
        assertNull(findElementOrNull(document, "threadTitle"))
        assertNull(findElementOrNull(document, "detailElapsedText"))
        assertNull(findElementOrNull(document, "threadSummary"))
    }

    @Test
    fun `detail header omits notification and settings actions`() {
        val document = parseLayout()

        assertNull(findElementOrNull(document, "detailBellBadge"))
        assertNull(findElementOrNull(document, "detailSettingsButton"))
    }

    @Test
    fun `composer attachment action uses centered icon image button in a dedicated toolbar row`() {
        val document = parseLayout()
        val pickImageButton = findElement(document, "pickImageButton")
        val composerToolbar = findElement(document, "composerToolbar")

        assertEquals("androidx.appcompat.widget.AppCompatImageButton", pickImageButton.tagName)
        assertEquals("centerInside", pickImageButton.getAttributeNS(ANDROID_NS, "scaleType"))
        assertEquals(composerCardParent(document), composerToolbar.parentNode)
    }

    @Test
    fun `pending images use a horizontal multi preview list`() {
        val document = parseLayout()
        val pendingImageListScroll = findElement(document, "pendingImageListScroll")
        val pendingImageList = findElement(document, "pendingImageList")

        assertEquals("HorizontalScrollView", pendingImageListScroll.tagName)
        assertEquals("true", pendingImageListScroll.getAttributeNS(ANDROID_NS, "fillViewport"))
        assertEquals("none", pendingImageListScroll.getAttributeNS(ANDROID_NS, "scrollbars"))
        assertEquals("horizontal", pendingImageList.getAttributeNS(ANDROID_NS, "orientation"))
        assertNull(findElementOrNull(document, "pendingImagePreview"))
        assertNull(findElementOrNull(document, "pendingImageName"))
        assertNull(findElementOrNull(document, "clearPendingImageButton"))
    }

    @Test
    fun `detail page exposes explicit visual todo markers for uncertain screenshot content`() {
        val document = parseLayout()

        assertNotNull(findElementOrNull(document, "detailTodoTodoLabel"))
        assertNotNull(findElementOrNull(document, "detailFilesTodoLabel"))
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

    private fun findElementByTextReferenceOrNull(document: Document, textReference: String): Element? {
        val nodes = document.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.getAttributeNS(ANDROID_NS, "text") == textReference) {
                return element
            }
        }
        return null
    }

    private fun findDescendantByTextReferenceOrNull(parent: Element, textReference: String): Element? {
        val nodes = parent.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.getAttributeNS(ANDROID_NS, "text") == textReference) {
                return element
            }
        }
        return null
    }

    private fun findDirectChildByDrawable(parent: Element, drawable: String): Element? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            if (element.getAttributeNS(ANDROID_NS, "src") == drawable) {
                return element
            }
        }
        return null
    }

    private fun childIndex(parent: Element, child: Element): Int {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            if (children.item(index) == child) {
                return index
            }
        }
        error("Child was not found under parent")
    }

    private fun composerCardParent(document: Document) = findElement(document, "composerCard")

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val APP_NS = "http://schemas.android.com/apk/res-auto"
    }
}
