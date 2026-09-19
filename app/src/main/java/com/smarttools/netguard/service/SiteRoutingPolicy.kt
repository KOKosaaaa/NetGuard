package com.smarttools.netguard.service

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.IDN
import androidx.annotation.Keep

@Keep
enum class SitePath { VPN, DIRECT }
@Keep
data class SiteOrigin(val host: String, val port: Int, val tls: Boolean) {
    val key: String get() = "$host|$port|$tls"
}
@Keep
data class SiteMeasurement(val quality: Int = 0, val millis: Long = -1)
@Keep
data class SiteRouteRecord(
    val origin: SiteOrigin, val target: String, val path: SitePath,
    val checkedAt: Long, val direct: SiteMeasurement, val vpn: SiteMeasurement
)

/** Only hostnames and route measurements are persisted, never URLs, headers or payloads. */
class SiteRoutingPolicy(
    private val contextKey: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val changed: () -> Unit = {}
) {
    companion object {
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_SITES = 1024
        fun host(value: String): String? = runCatching {
            val name = IDN.toASCII(value.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase()
            name.takeIf { it.length in 1..253 && it.contains('.') &&
                it != "localhost" && !it.endsWith(".localhost") &&
                it.split('.').all { label -> label.isNotEmpty() && label.length <= 63 } }
        }.getOrNull()
    }
    private val records = LinkedHashMap<String, SiteRouteRecord>(32, 0.75f, true)
    private val active = HashMap<String, Pair<SitePath, Int>>()
    private data class Grace(val path: SitePath, val until: Long)
    private val grace = HashMap<String, Grace>()
    private val failed = HashSet<String>()

    private fun heldPath(key: String): SitePath? = active[key]?.first ?: grace[key]?.takeIf { it.until > now() }?.path

    private fun valid(record: SiteRouteRecord): Boolean {
        if (record.checkedAt <= 0) return false
        val age = now() - record.checkedAt
        val ttl = if (record.direct.quality == 0 && record.vpn.quality == 0) 60_000L else WEEK_MS
        return age >= 0 && age < ttl
    }

    @Synchronized fun cached(origin: SiteOrigin): SiteRouteRecord? {
        val record = records[origin.key] ?: return null
        val held = heldPath(origin.key)
        return record.takeIf { held != null || valid(it) }?.let { it.copy(path = held ?: it.path) }
    }

    @Synchronized fun needsCheck(origin: SiteOrigin): Boolean = records[origin.key]?.let { !valid(it) } ?: true

    /** New traffic never waits for the comparison. A new origin starts on VPN. */
    @Synchronized fun forConnection(origin: SiteOrigin, target: String): SiteRouteRecord {
        val record = records[origin.key] ?: SiteRouteRecord(origin, target, SitePath.VPN, 0, SiteMeasurement(), SiteMeasurement()).also {
            records[origin.key] = it
            trim()
        }
        return record.copy(path = heldPath(origin.key) ?: record.path)
    }

    @Synchronized fun record(origin: SiteOrigin, target: String, direct: SiteMeasurement, vpn: SiteMeasurement): SiteRouteRecord {
        val old = records[origin.key]
        val path = when {
            direct.quality > vpn.quality -> SitePath.DIRECT
            vpn.quality > direct.quality -> SitePath.VPN
            direct.quality == 0 -> SitePath.VPN
            // Small jitter must not change a site's egress IP on weekly refresh.
            old?.path == SitePath.DIRECT && vpn.millis + 100 >= direct.millis * 0.8 -> SitePath.DIRECT
            direct.millis >= 0 && direct.millis + 100 < vpn.millis * 0.8 -> SitePath.DIRECT
            else -> SitePath.VPN
        }
        val result = SiteRouteRecord(origin, target, path, now(), direct, vpn)
        records[origin.key] = result
        trim()
        changed()
        // Store the measured winner even during an active session. Keep that
        // session's egress pinned; its future replacement can use the winner.
        return result.copy(path = heldPath(origin.key) ?: result.path)
    }

    @Synchronized fun pin(record: SiteRouteRecord): SitePath {
        val key = record.origin.key
        val old = active[key]
        val path = heldPath(key) ?: record.path
        if (old == null) failed.remove(key)
        active[key] = path to ((old?.second ?: 0) + 1)
        return path
    }

    @Synchronized fun release(origin: SiteOrigin) {
        val old = active[origin.key] ?: return
        if (old.second <= 1) {
            active.remove(origin.key)
            if (origin.key !in failed) grace[origin.key] = Grace(old.first, now() + 120_000)
        } else active[origin.key] = old.first to old.second - 1
    }

    @Synchronized fun connectionFailed(origin: SiteOrigin, path: SitePath) {
        val r = records[origin.key] ?: return
        grace.remove(origin.key)
        failed.add(origin.key)
        // New connections will recheck an actually failed route; existing ones
        // retain their lease and are never killed or replayed here.
        records[origin.key] = r.copy(checkedAt = 0,
            direct = if (path == SitePath.DIRECT) SiteMeasurement() else r.direct,
            vpn = if (path == SitePath.VPN) SiteMeasurement() else r.vpn)
        changed()
    }

    @Synchronized fun expired(): List<SiteRouteRecord> = records.values.filter {
        !valid(it)
    }

    private fun trim() {
        val keys = records.keys.iterator()
        while (records.size > MAX_SITES && keys.hasNext()) {
            val key = keys.next()
            if (!active.containsKey(key)) { keys.remove(); grace.remove(key); failed.remove(key) }
        }
    }

    @Synchronized fun export(): String = Gson().toJson(mapOf("version" to 1, "context" to contextKey, "sites" to records.values.toList()))

    @Synchronized fun restore(json: String?) {
        if (json.isNullOrBlank() || json.length > 1024 * 1024) return
        runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            if (root["version"].asInt != 1 || root["context"].asString != contextKey) return
            val gson = Gson()
            root.getAsJsonArray("sites").toList().takeLast(MAX_SITES).forEach { item ->
                runCatching item@{
                    if (item.asJsonObject["path"].asString !in SitePath.entries.map { it.name }) return@item
                    val r = gson.fromJson(item, SiteRouteRecord::class.java)
                    if (host(r.origin.host) != r.origin.host || r.origin.port !in 1..65535 || r.checkedAt <= 0 ||
                        r.checkedAt > now() || r.target.isNullOrBlank() || r.target.length > 253 ||
                        r.direct.quality !in 0..2 || r.vpn.quality !in 0..2) return@item
                    records[r.origin.key] = r
                }
            }
        }
    }
}
