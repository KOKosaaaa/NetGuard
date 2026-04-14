package com.smarttools.netguard.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LogBuffer {

    enum class LogLevel { INFO, WARN, ERROR }

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: LogLevel,
        val message: String
    )

    private const val MAX_ENTRIES = 500
    private val entries = mutableListOf<LogEntry>()
    private val _flow = MutableStateFlow<List<LogEntry>>(emptyList())
    val flow: StateFlow<List<LogEntry>> = _flow.asStateFlow()
    private var pendingFlowUpdate = false

    // Redact sensitive data from log messages
    // Quick-check keywords to skip regex when no sensitive data is present
    private val QUICK_CHECK_KEYWORDS = arrayOf("password", "pass=", "bearer", "authorization", "-")
    private val REDACT_PATTERNS = listOf(
        Regex("password\\s*[=:]\\s*\\S+", RegexOption.IGNORE_CASE),
        Regex("pass\\s*=\\s*\\S+", RegexOption.IGNORE_CASE),
        Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", RegexOption.IGNORE_CASE),
        Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE),
        Regex("Authorization\\s*:\\s*\\S+", RegexOption.IGNORE_CASE)
    )

    private fun redact(msg: String): String {
        // Fast path: skip regex if no potential sensitive keywords
        val lower = msg.lowercase()
        val needsRedaction = QUICK_CHECK_KEYWORDS.any { lower.contains(it) }
        if (!needsRedaction) return msg
        var result = msg
        for (pattern in REDACT_PATTERNS) {
            result = result.replace(pattern, "***")
        }
        return result
    }

    @Synchronized
    fun add(level: LogLevel, message: String) {
        entries.add(LogEntry(level = level, message = redact(message)))
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        // Throttle flow updates: batch rapid adds
        if (!pendingFlowUpdate) {
            pendingFlowUpdate = true
            _flow.value = entries.toList()
            pendingFlowUpdate = false
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        _flow.value = emptyList()
    }

    /** Parse xray log level from line like "2024/01/01 12:00:00 [Warning] ..." */
    fun parseXrayLevel(line: String): LogLevel {
        return when {
            line.contains("[Error]") || line.contains("[Fatal]") -> LogLevel.ERROR
            line.contains("[Warning]") -> LogLevel.WARN
            else -> LogLevel.INFO
        }
    }
}
