package com.codex.mobilecontrol

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MainActivityManifestTest {

    @Test
    fun `main activity uses adjust resize for ime`() {
        val document = parseManifest()
        val mainActivity = findActivity(document, ".MainActivity")
        val softInputMode = mainActivity.getAttributeNS(ANDROID_NS, "windowSoftInputMode")

        assertTrue(
            "Expected windowSoftInputMode to include adjustResize but was '$softInputMode'",
            softInputMode.contains("adjustResize")
        )
    }

    @Test
    fun `main activity keeps ime hidden on passive detail entry`() {
        val document = parseManifest()
        val mainActivity = findActivity(document, ".MainActivity")
        val softInputMode = mainActivity.getAttributeNS(ANDROID_NS, "windowSoftInputMode")

        assertTrue(
            "Expected windowSoftInputMode to include stateAlwaysHidden but was '$softInputMode'",
            softInputMode.contains("stateAlwaysHidden")
        )
    }

    @Test
    fun `application declares custom launcher icons`() {
        val document = parseManifest()
        val application = findApplication(document)

        assertTrue(
            "Expected android:icon to point at launcher icon",
            application.getAttributeNS(ANDROID_NS, "icon") == "@mipmap/ic_launcher"
        )
        assertTrue(
            "Expected android:roundIcon to point at round launcher icon",
            application.getAttributeNS(ANDROID_NS, "roundIcon") == "@mipmap/ic_launcher_round"
        )
    }

    @Test
    fun `application can vibrate waiting input notifications`() {
        val document = parseManifest()

        assertTrue(hasPermission(document, "android.permission.VIBRATE"))
    }

    @Test
    fun `application declares realtime foreground service`() {
        val document = parseManifest()
        val service = findService(document, ".RealtimeForegroundService")

        assertTrue(hasPermission(document, "android.permission.FOREGROUND_SERVICE"))
        assertTrue(hasPermission(document, "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING"))
        assertTrue(service.getAttributeNS(ANDROID_NS, "exported") == "false")
        assertTrue(
            service.getAttributeNS(ANDROID_NS, "foregroundServiceType")
                .contains("remoteMessaging")
        )
    }

    @Test
    fun `application exposes diagnostics zip files through file provider`() {
        val document = parseManifest()
        val provider = findProvider(document, "androidx.core.content.FileProvider")

        assertTrue(provider.getAttributeNS(ANDROID_NS, "authorities") == "\${applicationId}.diagnostics")
        assertTrue(provider.getAttributeNS(ANDROID_NS, "grantUriPermissions") == "true")
        assertTrue(
            hasProviderMetaData(
                provider,
                "android.support.FILE_PROVIDER_PATHS",
                "@xml/diagnostic_file_paths"
            )
        )
    }

    @Test
    fun `application declares backup rules for sensitive local state`() {
        val document = parseManifest()
        val application = findApplication(document)

        assertTrue(
            "Expected dataExtractionRules to exclude saved gateway tokens and caches",
            application.getAttributeNS(ANDROID_NS, "dataExtractionRules") == "@xml/data_extraction_rules"
        )
        assertTrue(
            "Expected fullBackupContent to exclude saved gateway tokens and caches",
            application.getAttributeNS(ANDROID_NS, "fullBackupContent") == "@xml/backup_rules"
        )
    }

    @Test
    fun `backup rules exclude gateway preferences and debug traces`() {
        val backupRules = parseXmlResource("backup_rules.xml")
        val extractionRules = parseXmlResource("data_extraction_rules.xml")

        assertTrue(
            "Expected full backup rules to exclude gateway shared preferences",
            hasBackupExclude(backupRules, "sharedpref", "codex_mobile_control.xml")
        )
        assertTrue(
            "Expected full backup rules to exclude persistent debug traces",
            hasBackupExclude(backupRules, "file", "diagnostics/")
        )
        assertTrue(
            "Expected cloud backup rules to exclude gateway shared preferences",
            hasBackupExclude(extractionRules, "sharedpref", "codex_mobile_control.xml")
        )
        assertTrue(
            "Expected device transfer rules to exclude gateway shared preferences",
            hasNestedBackupExclude(
                extractionRules,
                "device-transfer",
                "sharedpref",
                "codex_mobile_control.xml"
            )
        )
        assertTrue(
            "Expected data extraction rules to exclude persistent debug traces",
            hasBackupExclude(extractionRules, "file", "diagnostics/")
        )
    }

    private fun parseManifest(): Document {
        val candidates = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml")
        )
        val manifestFile = candidates.firstOrNull(File::isFile)
            ?: error("AndroidManifest.xml not found from ${System.getProperty("user.dir")}")

        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifestFile)
    }

    private fun parseXmlResource(fileName: String): Document {
        val candidates = listOf(
            File("app/src/main/res/xml/$fileName"),
            File("src/main/res/xml/$fileName")
        )
        val resourceFile = candidates.firstOrNull(File::isFile)
            ?: error("$fileName not found from ${System.getProperty("user.dir")}")

        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(resourceFile)
    }

    private fun findApplication(document: Document): Element {
        return document.getElementsByTagName("application").item(0) as? Element
            ?: error("application element was not found")
    }

    private fun findActivity(document: Document, name: String): Element {
        val nodes = document.getElementsByTagName("activity")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.getAttributeNS(ANDROID_NS, "name") == name) {
                return element
            }
        }
        error("Activity '$name' was not found")
    }

    private fun findProvider(document: Document, name: String): Element {
        val nodes = document.getElementsByTagName("provider")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.getAttributeNS(ANDROID_NS, "name") == name) {
                return element
            }
        }
        error("Provider '$name' was not found")
    }

    private fun findService(document: Document, name: String): Element {
        val nodes = document.getElementsByTagName("service")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.getAttributeNS(ANDROID_NS, "name") == name) {
                return element
            }
        }
        error("Service '$name' was not found")
    }

    private fun hasProviderMetaData(provider: Element, name: String, resource: String): Boolean {
        val nodes = provider.getElementsByTagName("meta-data")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.getAttributeNS(ANDROID_NS, "name") == name &&
                element.getAttributeNS(ANDROID_NS, "resource") == resource
            ) {
                return true
            }
        }
        return false
    }

    private fun hasPermission(document: Document, name: String): Boolean {
        val nodes = document.getElementsByTagName("uses-permission")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.getAttributeNS(ANDROID_NS, "name") == name) {
                return true
            }
        }
        return false
    }

    private fun hasBackupExclude(document: Document, domain: String, path: String): Boolean {
        val nodes = document.getElementsByTagName("exclude")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.getAttribute("domain") == domain &&
                element.getAttribute("path") == path
            ) {
                return true
            }
        }
        return false
    }

    private fun hasNestedBackupExclude(
        document: Document,
        parentTag: String,
        domain: String,
        path: String
    ): Boolean {
        val parents = document.getElementsByTagName(parentTag)
        for (parentIndex in 0 until parents.length) {
            val parent = parents.item(parentIndex) as? Element ?: continue
            val excludes = parent.getElementsByTagName("exclude")
            for (excludeIndex in 0 until excludes.length) {
                val element = excludes.item(excludeIndex) as? Element ?: continue
                if (element.getAttribute("domain") == domain &&
                    element.getAttribute("path") == path
                ) {
                    return true
                }
            }
        }
        return false
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
