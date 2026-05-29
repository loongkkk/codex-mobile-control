package com.codex.mobilecontrol.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugTraceLoggerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `persistent trace keeps recent failure lines across memory reset`() {
        val logFile = temporaryFolder.newFile("debug-trace.log")
        logFile.delete()

        DebugTraceLogger.clear()
        DebugTraceLogger.configurePersistentLog(
            file = logFile,
            maxPersistentEntries = 3,
            maxPersistentBytes = 4096
        )

        DebugTraceLogger.log("diagnostics-export", "old-line")
        DebugTraceLogger.log("diagnostics-export", "gatewayDiagnosticsFetched")
        DebugTraceLogger.log("diagnostics-export", "saveToDownloadsException file=first.zip")
        DebugTraceLogger.log("diagnostics-export", "saveToDownloadsFailed file=first.zip")

        val rollingSnapshot = DebugTraceLogger.snapshot()
        assertFalse(rollingSnapshot.any { it.contains("old-line") })
        assertTrue(rollingSnapshot.any { it.contains("saveToDownloadsException") })
        assertTrue(rollingSnapshot.any { it.contains("saveToDownloadsFailed") })

        DebugTraceLogger.configurePersistentLog(
            file = logFile,
            maxPersistentEntries = 3,
            maxPersistentBytes = 4096
        )

        val restartedSnapshot = DebugTraceLogger.snapshot()
        assertFalse(restartedSnapshot.any { it.contains("old-line") })
        assertTrue(restartedSnapshot.any { it.contains("saveToDownloadsException") })
        assertTrue(restartedSnapshot.any { it.contains("saveToDownloadsFailed") })

        DebugTraceLogger.configurePersistentLog(null)
        DebugTraceLogger.clear()
    }
}
