package com.smarttools.netguard.service

import java.security.MessageDigest
import com.smarttools.netguard.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, in-memory evidence. Never contains URLs, payloads, credentials or raw exceptions. */
internal class RouteDiagnostics(private val now: () -> Long = System::currentTimeMillis) {
    companion object { val shared = RouteDiagnostics(); const val MAX_ROWS = 96 }
    data class Token(val session: Long, val context: Long)
    internal data class Counters(var opened: Long = 0, var active: Int = 0, var up: Long = 0,
                                var down: Long = 0, var failures: Long = 0, var last: Long = 0)
    internal class Row(val key: String, val token: Token, val host: String?, val port: Int, val tls: Boolean, val reason: String) {
        var phase = "NOT_CHECKED"
        var catalog = "NOT_TESTED"
        var checkedAt = 0L
        var candidate: SitePath? = null
        var held: SitePath? = null
        val samples = linkedMapOf<String, SiteMeasurement>()
        val traffic = linkedMapOf<String, Counters>()
        var lastReason = reason
    }
    private val rows = LinkedHashMap<String, Row>()
    private var session = 0L
    private var context = 0L
    private var networkKnown = false
    private var running = false
    private var automatic = false
    private var startedAt = 0L
    private var endedAt = 0L

    @Synchronized fun begin(auto: Boolean): Token {
        session++; context = 0; networkKnown = false; running = true; automatic = auto
        startedAt = now(); endedAt = 0; rows.clear()
        return Token(session, context)
    }
    @Synchronized fun network(token: Token): Token {
        if (token.session == session && running) {
            if (networkKnown) context++ else networkKnown = true
        }
        return Token(token.session, context)
    }
    @Synchronized fun stop(token: Token) {
        if (token.session == session) { running = false; endedAt = now() }
    }
    private fun row(token: Token, origin: SiteOrigin?, reason: String): Row {
        // Only validated hostnames can enter diagnostics. Never retain an IP target or input URL.
        val host = origin?.host?.let(SiteRoutingPolicy::host)?.takeUnless { it.split('.').all { label -> label.all(Char::isDigit) } }
        val key = "${token.context}|${host ?: reason}|${origin?.port ?: 0}|${origin?.tls ?: false}"
        return rows.getOrPut(key) {
            Row(key, token, host, origin?.port ?: 0, origin?.tls ?: false, reason)
        }.also { while (rows.size > MAX_ROWS) rows.remove(rows.keys.first()) }
    }
    private fun current(token: Token) = running && token.session == session && token.context == context

    @Synchronized fun phase(token: Token, origin: SiteOrigin, phase: String) {
        if (!current(token)) return
        val row = row(token, origin, "AUTO")
        if (phase == "CHECKING") { row.samples.clear(); row.candidate = null; row.held = null; row.catalog = "NOT_TESTED" }
        row.phase = phase
    }
    @Synchronized fun catalog(token: Token, origin: SiteOrigin, reason: String) {
        if (current(token)) row(token, origin, "AUTO").catalog = reason
    }
    @Synchronized fun clear() { rows.clear() }

    @Synchronized fun sample(token: Token, origin: SiteOrigin, label: String, result: SiteMeasurement) {
        if (!current(token)) return
        val samples = row(token, origin, "AUTO").samples
        samples[label.take(48)] = result
        while (samples.size > 8) samples.remove(samples.keys.first())
    }
    @Synchronized fun decision(token: Token, record: SiteRouteRecord, cached: Boolean) {
        if (!current(token)) return
        val row = row(token, record.origin, "AUTO")
        if (cached && row.phase == "CHECKING") return
        row.phase = if (cached) "CACHE" else "CHECKED"
        row.checkedAt = record.checkedAt
        row.held = record.path
        row.candidate = record.dpiPath?.takeIf { record.dpi?.outcome == "VERIFIED_TWICE" }
        row.samples["DIRECT"] = record.direct; row.samples["VPN"] = record.vpn
        if (record.dpi?.outcome == "VERIFIED_TWICE") row.catalog = "VERIFIED_TWICE"
        record.dpi?.let { row.samples["${record.dpiPath}:confirmed"] = it }
    }

    @Synchronized fun connectFailed(token: Token, origin: SiteOrigin, path: SitePath) {
        if (!current(token)) return
        val row = row(token, origin, "AUTO")
        row.lastReason = "CONNECT_FAILED"
        row.traffic.getOrPut(path.name) { Counters() }.apply { failures++; last = now() }
    }

    /** Route is the actual connected path, not the cache winner or session lease. */
    @Synchronized fun flow(token: Token, origin: SiteOrigin?, path: String, reason: String): Flow {
        if (token.session != session || !running) return Flow(null, null, token)
        val row = row(token, origin, reason)
        row.lastReason = reason
        val counter = row.traffic.getOrPut(path) { Counters() }
        counter.opened++; counter.active++; counter.last = now()
        return Flow(row, counter, token)
    }
    inner class Flow internal constructor(private val row: Row?, private val counter: Counters?, private val token: Token) {
        private var closed = false
        fun progress(up: Long, down: Long) = synchronized(this@RouteDiagnostics) {
            if (!closed && token.session == session && row != null && counter != null && rows[row.key] === row) {
                counter.up += up.coerceAtLeast(0); counter.down += down.coerceAtLeast(0)
                counter.last = now()
            }
        }
        fun close(failed: Boolean) = synchronized(this@RouteDiagnostics) {
            if (!closed) {
                closed = true
                if (token.session == session && counter != null) {
                    counter.active--; if (failed) counter.failures++
                }
            }
        }
    }

    private data class Snapshot(val session: Long, val context: Long, val running: Boolean,
        val automatic: Boolean, val startedAt: Long, val endedAt: Long, val rows: Map<String, Row>)
    @Synchronized private fun snapshot(): Snapshot = Snapshot(session, context, running, automatic, startedAt, endedAt,
        rows.mapValues { (_, r) -> Row(r.key, r.token, r.host, r.port, r.tls, r.reason).also { copy ->
            copy.phase = r.phase; copy.catalog = r.catalog; copy.checkedAt = r.checkedAt
            copy.candidate = r.candidate; copy.held = r.held; copy.lastReason = r.lastReason
            copy.samples.putAll(r.samples)
            r.traffic.forEach { (path, c) -> copy.traffic[path] = c.copy() }
        } })

    data class UiRow(val key: String, val title: String, val probe: String, val traffic: String, val details: String)
    data class UiSnapshot(val status: String, val evidence: String, val rows: List<UiRow>)
    fun presentation(context: android.content.Context, dpiEnabled: Boolean): UiSnapshot {
        val s = snapshot()
        fun t(id: Int, vararg args: Any) = context.getString(id, *args)
        fun enabled(value: Boolean) = t(if (value) R.string.diag_enabled else R.string.diag_disabled)
        fun code(value: String): String {
            val id = when (value) {
                "NOT_TESTED", "NOT_CHECKED" -> R.string.diag_code_not_tested
                "NOT_TLS" -> R.string.diag_code_not_tls
                "NOT_ELIGIBLE" -> R.string.diag_code_not_eligible
                "DIRECT_OK" -> R.string.diag_code_direct_ok
                "DIRECT_LIMITED" -> R.string.diag_code_direct_limited
                "VPN_NOT_VERIFIED" -> R.string.diag_code_vpn_unverified
                "EARLY_FAILURE" -> R.string.diag_code_early_failure
                "RATE_LIMIT" -> R.string.diag_code_rate_limit
                "ATTEMPTED" -> R.string.diag_code_attempted
                "DPI_DISABLED" -> R.string.diag_code_dpi_disabled
                "VERIFIED_TWICE" -> R.string.diag_code_verified
                "FIRST_OR_CACHE" -> R.string.diag_code_first_cache
                "UNSUPPORTED_HELLO" -> R.string.diag_code_unsupported
                "ONLY_VPN" -> R.string.diag_code_only_vpn
                "OPAQUE" -> R.string.diag_code_opaque
                "EXCLUDED" -> R.string.diag_code_excluded
                "AUTO_OFF" -> R.string.diag_code_auto_off
                "UDP" -> R.string.diag_code_udp
                "CONNECT_FAILED" -> R.string.diag_code_connect_failed
                "AUTO" -> R.string.diag_code_auto
                else -> return value
            }
            return t(id)
        }
        fun path(value: String): String = when (value) {
            "DIRECT" -> t(R.string.diag_path_direct)
            "VPN" -> t(R.string.diag_path_vpn)
            "RULES" -> t(R.string.diag_path_rules)
            "TLS_RECORD_HEADER" -> t(R.string.diag_path_tls_first)
            "TLS_RECORD_SNI" -> t(R.string.diag_path_tls_sni)
            "VPN_UDP_CONTROL" -> t(R.string.diag_path_vpn_udp)
            "RULES_UDP_CONTROL" -> t(R.string.diag_path_rules_udp)
            else -> value
        }
        val current = s.rows.values.filter { it.token.context == s.context }
        val bypass = current.flatMap { it.traffic.filterKeys { p -> p in LocalDpi.paths.map { it.name } }.values }
        val evidence = when {
            bypass.any { it.up > 0 && it.down > 0 } -> t(R.string.diag_evidence_both)
            current.any { it.candidate != null } -> t(R.string.diag_evidence_verified)
            else -> t(R.string.diag_evidence_none)
        }
        return UiSnapshot(t(R.string.diag_status, enabled(s.running), enabled(s.automatic), enabled(dpiEnabled)), evidence,
            s.rows.values.toList().asReversed().map { r ->
                val phase = t(when (r.phase) {
                    "CHECKING" -> R.string.diag_phase_checking
                    "CACHE" -> R.string.diag_phase_cached
                    "CHECKED" -> R.string.diag_phase_checked
                    "RATE_LIMIT" -> R.string.diag_phase_budget
                    "DEFERRED" -> R.string.diag_phase_deferred
                    else -> R.string.diag_phase_none
                })
                val routes = r.traffic.entries.joinToString("\n") { (route, c) ->
                    t(R.string.diag_traffic_row, path(route), com.smarttools.netguard.util.TrafficFormatter.formatBytes(c.up),
                        com.smarttools.netguard.util.TrafficFormatter.formatBytes(c.down), c.failures)
                }.ifEmpty { t(R.string.diag_traffic_none) }
                val details = buildString {
                    appendLine(t(R.string.diag_catalog_detail, "${code(r.catalog)} [${r.catalog}]"))
                    appendLine(t(R.string.diag_reason_detail, "${code(r.lastReason)} [${r.lastReason}]"))
                    appendLine(t(R.string.diag_network_detail, t(if (r.token.context == s.context) R.string.diag_network_current else R.string.diag_network_previous)))
                    r.samples.forEach { (label, m) -> appendLine("$label: ${m.stage}/${m.outcome}, HTTP ${m.status}, ${m.bytes} B, ${m.millis} ms") }
                    r.traffic.forEach { (route, c) -> appendLine(t(R.string.diag_connections_detail, path(route), c.opened, c.active)) }
                }.trimEnd()
                UiRow("${s.session}:${r.key}", r.host?.let { "$it:${r.port}" } ?: code(r.reason),
                    t(R.string.diag_probe_summary, phase, code(r.catalog)), t(R.string.diag_traffic_heading, routes), details)
            })
    }

    fun render(russian: Boolean, redact: Boolean, dpiEnabled: Boolean): String {
        val snapshot = snapshot()
        val context = snapshot.context; val running = snapshot.running; val automatic = snapshot.automatic
        val startedAt = snapshot.startedAt; val endedAt = snapshot.endedAt; val rows = snapshot.rows
        // Formatting and hashing must never hold the selector's accounting lock.
        return buildString {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
        fun stamp(value: Long) = if (value > 0) time.format(Date(value)) else "—"
        fun word(ru: String, en: String) = if (russian) ru else en
        appendLine(word("ДИАГНОСТИКА ОБХОДА", "BYPASS DIAGNOSTICS"))
        appendLine("ROUTER=${if (running) "ON" else "OFF"}; AUTO=$automatic; DPI=$dpiEnabled")
        appendLine("${word("Сессия", "Session")}: ${stamp(startedAt)}; ${word("остановлена", "stopped")}: ${stamp(endedAt)}")
        appendLine(word("Проверки и реальный трафик показаны отдельно. Байты — запись в сокет, не доказательство загрузки сайта. Данные хранятся до новой VPN-сессии или перезапуска процесса; последние 96 целей.",
            "Probes and application traffic are separate. Bytes mean socket writes, not proof of a complete site load. In memory until a new VPN session or process restart; last 96 targets."))
        appendLine(word("TLS_RECORD_*: локальный обход; DIRECT: напрямую; VPN: туннель; RULES: обычные правила, путь неизвестен.",
            "TLS_RECORD_*: local bypass; DIRECT: direct; VPN: tunnel; RULES: normal rules, path unknown."))
        appendLine(word("VERIFIED_TWICE = две полные проверки, но ещё не применение к приложению. CHECKING = проверяется; CACHE = сохранённая проверка; DEFERRED = проверка не завершена/отложена.",
            "VERIFIED_TWICE = two complete probes, not application use yet. CHECKING = running; CACHE = stored evidence; DEFERRED = incomplete/deferred."))
        val currentRows = rows.values.filter { it.token.context == context }
        val bypass = currentRows.flatMap { it.traffic.filterKeys { path -> path in LocalDpi.paths.map { p -> p.name } }.values }
        appendLine(when {
            bypass.any { it.up > 0 && it.down > 0 } -> word("В этой сети: через локальный обход записаны байты в обе стороны. Это ещё не подтверждает полную загрузку сайта.", "This network: local bypass socket writes observed in both directions; not proof of a complete site load.")
            bypass.any { it.up > 0 } -> word("В этой сети: данные через обход отправлены, обратная передача ещё не наблюдалась.", "This network: bypass upload observed, no downstream socket writes yet.")
            currentRows.any { it.candidate != null } -> word("Способ прошёл проверки, но передача данных приложения через него ещё не наблюдалась.", "A strategy passed probes, but application traffic through it has not been observed yet.")
            else -> word("Применение локального обхода пока не подтверждено.", "Local bypass use has not been confirmed yet.")
        })
        if (redact) appendLine(word("В этом диагностическом снимке домены заменены стабильными site-ID; на его экране видны полные имена. Обычный лог обрабатывается отдельно.", "This diagnostic snapshot uses stable site IDs; its screen shows full names. Ordinary logs use separate redaction rules."))
        if (rows.isEmpty()) appendLine(word("Данных пока нет. Включи Авто и открой сайт. Сам экран не запускает сетевые проверки.",
            "No observations yet. Enable Auto and open a site. This screen does not initiate network probes."))
        for (row in rows.values.toList().asReversed()) {
            val name = row.host?.let { if (redact) "site-" + MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(6).joinToString("") { b -> "%02x".format(b) } else it } ?: row.reason
            appendLine()
            appendLine("$name${if (row.host != null) ":${row.port}" else ""} [${if (row.token.context == context) word("текущая сеть", "current network") else word("предыдущая сеть", "previous network")}]")
            appendLine("${word("Проверка", "Probe")}: ${row.phase}; ${stamp(row.checkedAt)}")
            for ((label, m) in row.samples) appendLine("  $label: ${m.stage}/${m.outcome}, HTTP=${m.status}, ${m.bytes} B, ${m.millis} ms")
            row.candidate?.let { appendLine("${word("Проверенный обход", "Verified strategy")}: $it; ${word("маршрут с учётом закрепления", "route including session pin")}: ${row.held}") }
            appendLine("${word("Каталог обхода", "Bypass catalog")}: ${row.catalog}")
            appendLine("${word("Причина пути", "Route reason")}: ${row.lastReason}")
            if (row.traffic.isEmpty()) appendLine(word("Пользовательский трафик ещё не наблюдался.", "No application traffic observed yet."))
            appendLine(word("Фактически использованный маршрут:", "Actual application route:"))
            for ((path, c) in row.traffic) appendLine("  $path: ${word("соединений", "connections")}=${c.opened}, ${word("открыто", "active")}=${c.active}, ↑${c.up} B ↓${c.down} B, ${word("сбоев", "failures")}=${c.failures}, ${stamp(c.last)}")
        }
        appendLine()
        appendLine(word("Каталог: DIRECT_OK — прямой доступ работает; DIRECT_LIMITED — ответ недостаточен; VPN_NOT_VERIFIED — контроль через VPN не подтверждён; EARLY_FAILURE — сбой DNS/TCP; RATE_LIMIT — лимит проверок; ATTEMPTED — попытки без двойного успеха; DPI_DISABLED — выключено. После очистки считаются новые соединения; текущие проверки не отменяются и могут добавить новые результаты.", "Catalog: DIRECT_OK=direct works; DIRECT_LIMITED=insufficient response; VPN_NOT_VERIFIED=VPN control unverified; EARLY_FAILURE=DNS/TCP failed; RATE_LIMIT=probe limit; ATTEMPTED=no double success; DPI_DISABLED=off. Clear resets evidence; only new connections are counted afterward, but running probes may add fresh results."))
        appendLine(word("Причины: FIRST_OR_CACHE — старт/кэш/закреплённый путь; DPI_DISABLED — выключено; UNSUPPORTED_HELLO — несовместимый TLS; ONLY_VPN — правило приложения; OPAQUE — имя сайта не распознано; EXCLUDED — обычное исключение; AUTO_OFF — Авто выключено; UDP — без TLS-проверки; CONNECT_FAILED — не удалось открыть соединение.",
            "Reasons: FIRST_OR_CACHE=start/cache/session pin; DPI_DISABLED=off; UNSUPPORTED_HELLO=incompatible TLS; ONLY_VPN=app rule; OPAQUE=unknown origin; EXCLUDED=ordinary exclusion; AUTO_OFF=Auto off; UDP=no TLS probe; CONNECT_FAILED=connection could not be opened."))
        }
    }
}
