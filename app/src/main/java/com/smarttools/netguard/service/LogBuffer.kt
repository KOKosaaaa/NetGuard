package com.smarttools.netguard.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object LogBuffer {

    enum class LogLevel { INFO, WARN, ERROR }

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: LogLevel,
        val message: String
    )

    private const val MAX_ENTRIES = 500
    // ArrayDeque so trimming the oldest entry is O(1) instead of O(n) on
    // ArrayList during xray log bursts.
    private val entries = ArrayDeque<LogEntry>(MAX_ENTRIES + 16)
    private val _flow = MutableStateFlow<List<LogEntry>>(emptyList())
    val flow: StateFlow<List<LogEntry>> = _flow.asStateFlow()

    // Real debounce instead of the previous broken `pendingFlowUpdate` flag.
    // CONFLATED channel collapses many quick `add()` notifications into one
    // batched StateFlow update every UPDATE_DEBOUNCE_MS.
    private const val UPDATE_DEBOUNCE_MS = 120L
    private val updateScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val updateChannel = Channel<Unit>(Channel.CONFLATED)

    init {
        updateScope.launch {
            for (signal in updateChannel) {
                delay(UPDATE_DEBOUNCE_MS)
                val snapshot = synchronized(entries) { entries.toList() }
                _flow.value = snapshot
            }
        }
    }

    private val REDACT_PATTERNS = listOf(
        // password=, passwd=, pwd=, pass=, secret=, api_key=, token=
        // matches both = and : separators, with optional surrounding quotes
        Regex(
            "(?i)\\b(password|passwd|pwd|pass|secret|api[_-]?key|apikey|token)\\s*[=:]\\s*[\"']?[^\"'\\s,;}]+",
        ),
        // JSON form: "password":"value", "id":"uuid", "token":"…"
        Regex(
            "(?i)\"(password|passwd|secret|token|api[_-]?key|id|uuid)\"\\s*:\\s*\"[^\"]*\"",
        ),
        // UUID
        Regex(
            "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}",
            RegexOption.IGNORE_CASE,
        ),
        // Bearer / Authorization
        Regex("(?i)bearer\\s+\\S+"),
        Regex("(?i)authorization\\s*:\\s*\\S+"),
        // Reality publicKey / shortId — long base64-ish strings tagged
        // with the standard URI param names.
        Regex(
            "(?i)\\b(pbk|publickey|sid|shortid)\\s*[=:]\\s*[A-Za-z0-9+/=_-]{8,}",
        ),
        // Shadowsocks URL base64 block (between :// and @) — decodes to
        // method:password.
        Regex("ss://[A-Za-z0-9+/=_-]{8,}@", RegexOption.IGNORE_CASE),
    )

    // Hostname / IP only redacted in lines that are clearly network errors so
    // we don't blanket-mask normal log lines that mention domains in safe
    // contexts.
    private val HOST_PATTERN = Regex(
        "([a-zA-Z0-9][a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}|\\d+\\.\\d+\\.\\d+\\.\\d+)(:\\d+)?",
    )
    private val NET_ERROR_HINTS = arrayOf("dial", "connect to", "dns", "resolve")

    private fun redact(msg: String): String {
        var result = msg
        for (pattern in REDACT_PATTERNS) {
            result = pattern.replace(result) { match ->
                val text = match.value
                val sep = text.indexOfAny(charArrayOf('=', ':'))
                if (sep in 1..19) "${text.substring(0, sep + 1)}***"
                else "***"
            }
        }
        if (NET_ERROR_HINTS.any { msg.contains(it, ignoreCase = true) }) {
            result = HOST_PATTERN.replace(result, "***:****")
        }
        return result
    }

    /**
     * Public entrypoint so TunnelVpnService can run the same redaction on the
     * raw xray / tun2socks lines before they hit `Log.d`. Without this, raw
     * server addresses and credentials reach `adb logcat` even though the
     * in-app Log viewer is sanitised.
     */
    fun redactPublic(msg: String): String = redact(msg)

    fun add(level: LogLevel, message: String) {
        synchronized(entries) {
            entries.addLast(LogEntry(level = level, message = redact(message)))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        updateChannel.trySend(Unit)
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
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
