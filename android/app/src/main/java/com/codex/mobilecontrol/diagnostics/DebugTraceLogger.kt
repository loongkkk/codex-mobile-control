package com.codex.mobilecontrol.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DebugTraceLogger {
    private const val TAG = "CodexTrace"
    private const val MAX_ENTRIES = 400
    private const val DEFAULT_MAX_PERSISTENT_ENTRIES = 2_000
    private const val DEFAULT_MAX_PERSISTENT_BYTES = 2 * 1024 * 1024

    private val entries = ArrayDeque<String>()
    private var persistentLogFile: File? = null
    private var maxPersistentEntries = DEFAULT_MAX_PERSISTENT_ENTRIES
    private var maxPersistentBytes = DEFAULT_MAX_PERSISTENT_BYTES
    private var persistentWritesSinceTrim = 0
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun initialize(context: Context) {
        configurePersistentLog(
            file = File(File(context.filesDir, "diagnostics"), "debug-trace.log")
        )
    }

    fun configurePersistentLog(
        file: File?,
        maxPersistentEntries: Int = DEFAULT_MAX_PERSISTENT_ENTRIES,
        maxPersistentBytes: Int = DEFAULT_MAX_PERSISTENT_BYTES
    ) {
        synchronized(entries) {
            persistentLogFile = file
            this.maxPersistentEntries = maxPersistentEntries.coerceAtLeast(1)
            this.maxPersistentBytes = maxPersistentBytes.coerceAtLeast(1024)
            if (file != null) {
                runCatching {
                    file.parentFile?.mkdirs()
                    if (!file.exists()) {
                        file.createNewFile()
                    }
                    trimPersistentLogLocked(file)
                    persistentWritesSinceTrim = 0
                }
            }
        }
    }

    fun log(category: String, message: String) {
        val line = "${timestampFormatter.format(Date())} [$category] $message"
        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(line)
            persistentLogFile?.let { file ->
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText("$line\n", Charsets.UTF_8)
                    persistentWritesSinceTrim += 1
                    if (
                        persistentWritesSinceTrim >= 25 ||
                        file.length() > maxPersistentBytes
                    ) {
                        trimPersistentLogLocked(file)
                        persistentWritesSinceTrim = 0
                    }
                }
            }
        }
        runCatching {
            Log.d(TAG, line)
        }
    }

    fun snapshot(): List<String> {
        return synchronized(entries) {
            val file = persistentLogFile
            if (file != null && file.exists()) {
                runCatching {
                    trimPersistentLogLocked(file)
                    file.readLines(Charsets.UTF_8)
                }.getOrElse {
                    entries.toList()
                }
            } else {
                entries.toList()
            }
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            persistentLogFile?.let { file ->
                runCatching {
                    file.writeText("", Charsets.UTF_8)
                }
            }
            persistentWritesSinceTrim = 0
        }
    }

    private fun trimPersistentLogLocked(file: File) {
        if (!file.exists()) {
            return
        }

        val lines = file.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .takeLast(maxPersistentEntries)
            .toMutableList()
        while (
            lines.size > 1 &&
            lines.joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.UTF_8).size >
            maxPersistentBytes
        ) {
            lines.removeAt(0)
        }
        var text = lines.joinToString(separator = "\n")
        if (text.isNotEmpty()) {
            text += "\n"
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxPersistentBytes) {
            text = bytes.copyOfRange(bytes.size - maxPersistentBytes, bytes.size)
                .toString(Charsets.UTF_8)
                .substringAfter('\n')
        }
        file.writeText(text, Charsets.UTF_8)
    }
}
