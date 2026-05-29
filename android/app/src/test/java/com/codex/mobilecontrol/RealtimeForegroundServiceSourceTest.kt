package com.codex.mobilecontrol

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RealtimeForegroundServiceSourceTest {

    @Test
    fun `foreground service keeps repository connected from saved config`() {
        val source = readServiceSource()

        assertTrue(source.contains("class RealtimeForegroundService : Service()"))
        assertTrue(source.contains("startForeground("))
        assertTrue(source.contains("GatewayPreferences.loadConfig(applicationContext)"))
        assertTrue(source.contains("GatewayPreferences.loadLastOpenedThreadId(applicationContext)"))
        assertTrue(source.contains("AppGraph.repositoryFactory(applicationContext)"))
        assertTrue(source.contains(".connect(config, requestedThreadId)"))
        assertTrue(source.contains("DebugTraceLogger.log(\"foreground-service\""))
    }

    @Test
    fun `foreground service exposes explicit start stop helpers`() {
        val source = readServiceSource()

        assertTrue(source.contains("ACTION_START"))
        assertTrue(source.contains("ACTION_STOP"))
        assertTrue(source.contains("fun setEnabled(context: Context, enabled: Boolean)"))
        assertTrue(source.contains("ContextCompat.startForegroundService("))
        assertTrue(source.contains("GatewayPreferences.saveBackgroundConnectionEnabled(context, enabled)"))
        assertTrue(source.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
    }

    private fun readServiceSource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/codex/mobilecontrol/RealtimeForegroundService.kt"),
            File("src/main/java/com/codex/mobilecontrol/RealtimeForegroundService.kt")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("RealtimeForegroundService.kt not found from ${System.getProperty("user.dir")}")
    }
}
