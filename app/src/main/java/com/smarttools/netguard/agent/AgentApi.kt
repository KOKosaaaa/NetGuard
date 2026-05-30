package com.smarttools.netguard.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire-level DTOs for the netguard-agent HTTP API.
 *
 * Hand-rolled JSON (org.json) rather than kotlinx.serialization or moshi
 * because the schema is tiny (~12 message types) and we're avoiding extra
 * codegen + APK growth. If the surface triples we'll revisit.
 */

// --- /v1/health -----------------------------------------------------------

data class HealthResponse(
    val ok: Boolean,
    val version: String,
    val startedAt: String,
) {
    companion object {
        fun fromJson(j: JSONObject) = HealthResponse(
            ok = j.optBoolean("ok"),
            version = j.optString("version"),
            startedAt = j.optString("started_at"),
        )
    }
}

// --- /v1/auth/pair --------------------------------------------------------

data class PairRequest(
    val pairToken: String,
    val deviceName: String,
    val appVersion: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("pair_token", pairToken)
        put("device_name", deviceName)
        put("app_version", appVersion)
    }.toString()
}

data class PairResponse(
    val bearer: String,
    val expiresAt: String,
) {
    companion object {
        fun fromJson(j: JSONObject) = PairResponse(
            bearer = j.getString("bearer"),
            expiresAt = j.getString("expires_at"),
        )
    }
}

// --- /v1/auth/rotate ------------------------------------------------------

data class RotateResponse(
    val rotated: Boolean,
    val bearer: String?,
    val expiresAt: String?,
) {
    companion object {
        fun fromJson(j: JSONObject) = RotateResponse(
            rotated = j.optBoolean("rotated"),
            bearer = j.optString("bearer").takeIf { it.isNotEmpty() },
            expiresAt = j.optString("expires_at").takeIf { it.isNotEmpty() },
        )
    }
}

// --- /v1/status -----------------------------------------------------------

data class StatusResponse(
    val agentVersion: String,
    val agentUptimeS: Long,
    val loadAvg: List<Float>,
    val cpuCount: Int,
    val memTotalMb: Int,
    val memUsedMb: Int,
    val memFreeMb: Int,
    val diskTotalGb: Long,
    val diskUsedGb: Long,
    val diskFreeGb: Long,
    val hostUptimeS: Long,
    val services: List<ServiceState>,
) {
    companion object {
        fun fromJson(j: JSONObject): StatusResponse {
            val agent = j.getJSONObject("agent")
            val host = j.getJSONObject("host")
            val mem = host.getJSONObject("memory")
            val disk = host.getJSONObject("disk_root")
            val loadArr = host.getJSONArray("load_avg")
            val load = (0 until loadArr.length()).map { loadArr.getDouble(it).toFloat() }
            val svcArr = j.optJSONArray("services") ?: JSONArray()
            val services = (0 until svcArr.length()).map { idx ->
                val s = svcArr.getJSONObject(idx)
                ServiceState(
                    name = s.getString("name"),
                    active = s.getBoolean("active"),
                    version = s.optString("version").takeIf { it.isNotEmpty() && it != "null" },
                    pid = if (s.isNull("pid")) null else s.optInt("pid"),
                    since = s.optString("since").takeIf { it.isNotEmpty() && it != "null" },
                )
            }
            return StatusResponse(
                agentVersion = agent.getString("version"),
                agentUptimeS = agent.getLong("uptime_s"),
                loadAvg = load,
                cpuCount = host.getInt("cpu_count"),
                memTotalMb = mem.getInt("total_mb"),
                memUsedMb = mem.getInt("used_mb"),
                memFreeMb = mem.getInt("free_mb"),
                diskTotalGb = disk.getLong("total_gb"),
                diskUsedGb = disk.getLong("used_gb"),
                diskFreeGb = disk.getLong("free_gb"),
                hostUptimeS = host.getLong("uptime_s"),
                services = services,
            )
        }
    }
}

data class ServiceState(
    val name: String,
    val active: Boolean,
    val version: String?,
    val pid: Int?,
    val since: String?,
)

// --- /v1/xray/deploy ------------------------------------------------------

data class DeployXrayRequest(
    val firstInbound: InboundSpec? = null,
) {
    fun toJson(): String = JSONObject().apply {
        if (firstInbound != null) put("first_inbound", firstInbound.toJsonObject())
    }.toString()
}

data class InboundSpec(
    val protocol: String = "vless",
    val port: Int = 0,
    val uuid: String = "",
    val serverName: String = "",
    val label: String = "",
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("protocol", protocol)
        if (port != 0) put("port", port)
        if (uuid.isNotEmpty()) put("uuid", uuid)
        if (serverName.isNotEmpty()) put("server_name", serverName)
        if (label.isNotEmpty()) put("label", label)
    }
}

data class TaskAck(
    val taskId: String,
    val status: String,
) {
    companion object {
        fun fromJson(j: JSONObject) = TaskAck(
            taskId = j.getString("task_id"),
            status = j.getString("status"),
        )
    }
}

// --- /v1/tasks/{id} -------------------------------------------------------

data class TaskState(
    val taskId: String,
    val type: String,
    val status: String,   // pending | running | done | failed | rolled_back
    val step: String,
    val progress: Int,
    val log: List<String>,
    val resultJson: String?,
    val error: TaskError?,
    val startedAt: String,
    val finishedAt: String?,
) {
    val isTerminal: Boolean get() = status in setOf("done", "failed", "rolled_back")

    companion object {
        fun fromJson(j: JSONObject): TaskState {
            val logArr = j.optJSONArray("log") ?: JSONArray()
            val log = (0 until logArr.length()).map { logArr.getString(it) }
            val errObj = j.optJSONObject("error")
            val err = errObj?.let {
                TaskError(
                    code = it.optString("code"),
                    message = it.optString("message"),
                    retryable = it.optBoolean("retryable"),
                )
            }
            return TaskState(
                taskId = j.getString("task_id"),
                type = j.getString("type"),
                status = j.getString("status"),
                step = j.optString("step"),
                progress = j.optInt("progress"),
                log = log,
                resultJson = j.optJSONObject("result")?.toString(),
                error = err,
                startedAt = j.getString("started_at"),
                finishedAt = j.optString("finished_at").takeIf { it.isNotEmpty() },
            )
        }
    }
}

data class TaskError(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

// --- /v1/xray/profile (sync) ---------------------------------------------

data class AddProfileRequest(
    val protocol: String = "vless",
    val port: Int = 0,
    val uuid: String = "",
    val serverName: String = "",
    val label: String = "",
    /**
     * Multi-hop chain wiring. When non-null, the agent attaches a VLESS
     * outbound + a routing rule so traffic from this new inbound is
     * forwarded into [chainTo] instead of falling through to freedom.
     * Caller (ChainOrchestrator) walks the chain from EXIT → ENTRY, so
     * by the time we set chainTo, the next-hop profile already exists.
     */
    val chainTo: ChainTarget? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("protocol", protocol)
        if (port != 0) put("port", port)
        if (uuid.isNotEmpty()) put("uuid", uuid)
        if (serverName.isNotEmpty()) put("server_name", serverName)
        if (label.isNotEmpty()) put("label", label)
        if (chainTo != null) put("chain_to", chainTo.toJsonObject())
    }.toString()
}

/** Mirrors the agent's ChainTarget — fields from the next-hop's vless URI. */
data class ChainTarget(
    val host: String,
    val port: Int,
    val uuid: String,
    val serverName: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val flow: String = "",
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("host", host)
        put("port", port)
        put("uuid", uuid)
        if (serverName.isNotEmpty()) put("server_name", serverName)
        if (publicKey.isNotEmpty()) put("public_key", publicKey)
        if (shortId.isNotEmpty()) put("short_id", shortId)
        if (flow.isNotEmpty()) put("flow", flow)
    }

    companion object {
        /**
         * Parse a vless URI into its ChainTarget components. The caller
         * usually swaps in a proper public hostname/IP — vless URIs from
         * the agent carry the agent's view of its own hostname which is
         * useless externally.
         */
        fun fromVlessUri(uri: String, overrideHost: String? = null): ChainTarget? {
            // vless://<uuid>@<host>:<port>?<params>#label
            val rx = Regex("^vless://([^@]+)@([^:/?#]+):(\\d+)(\\?[^#]*)?")
            val m = rx.find(uri) ?: return null
            val uuid = m.groupValues[1]
            val host = overrideHost ?: m.groupValues[2]
            val port = m.groupValues[3].toInt()
            val qs = m.groupValues[4].removePrefix("?")
            val params = qs.split('&').mapNotNull {
                val idx = it.indexOf('=')
                if (idx < 0) null else it.substring(0, idx) to
                    java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
            }.toMap()
            return ChainTarget(
                host = host,
                port = port,
                uuid = uuid,
                serverName = params["sni"].orEmpty(),
                publicKey = params["pbk"].orEmpty(),
                shortId = params["sid"].orEmpty(),
                flow = params["flow"].orEmpty(),
            )
        }
    }
}

data class InboundResult(
    val inboundId: String,
    val protocol: String,
    val port: Int,
    val profileUri: String,
) {
    companion object {
        fun fromJson(j: JSONObject) = InboundResult(
            inboundId = j.getString("inbound_id"),
            protocol = j.getString("protocol"),
            port = j.getInt("port"),
            profileUri = j.getString("profile_uri"),
        )
    }
}

// --- /v1/xray/inbounds ----------------------------------------------------

data class InboundRow(
    val inboundId: String,
    val protocol: String,
    val port: Int,
    val profileUri: String,
    val createdAt: String,
    /**
     * Empty unless this inbound is the entry-or-middle hop of a multi-hop
     * chain. Non-empty value is the tag of the chained VLESS outbound the
     * agent created; chainToUri is the next-hop vless URI for display.
     */
    val chainToTag: String = "",
    val chainToUri: String = "",
) {
    val isChainHop: Boolean get() = chainToTag.isNotEmpty()

    companion object {
        fun fromJson(j: JSONObject) = InboundRow(
            inboundId = j.getString("inbound_id"),
            protocol = j.getString("protocol"),
            port = j.getInt("port"),
            profileUri = j.getString("profile_uri"),
            createdAt = j.getString("created_at"),
            chainToTag = j.optString("chain_to_tag"),
            chainToUri = j.optString("chain_to_uri"),
        )

        fun listFromJson(j: JSONObject): List<InboundRow> {
            val arr = j.getJSONArray("inbounds")
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}

// --- /v1/bypass/rules ----------------------------------------------------

/**
 * Server-side routing rule. kind ∈ domain|cidr|geosite|geoip,
 * action ∈ direct|block|via. For action=via, viaOutboundTag references
 * one of the upstream proxies in /v1/bypass/outbounds.
 */
data class BypassRule(
    val id: String,
    val kind: String,
    val value: String,
    val action: String,
    val viaOutboundTag: String,
    val order: Int,
    val createdAt: String,
) {
    companion object {
        fun fromJson(j: JSONObject) = BypassRule(
            id = j.getString("id"),
            kind = j.getString("kind"),
            value = j.getString("value"),
            action = j.getString("action"),
            viaOutboundTag = j.optString("via_outbound_tag"),
            order = j.optInt("order"),
            createdAt = j.optString("created_at"),
        )

        fun listFromJson(j: JSONObject): List<BypassRule> {
            val arr = j.optJSONArray("rules") ?: return emptyList()
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}

/** Body of POST /v1/bypass/rules (single) and PUT (batch via list). */
data class AddBypassRuleRequest(
    val kind: String,
    val value: String,
    val action: String,         // direct | block | via
    val viaOutboundTag: String = "",
    val order: Int = 0,
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("value", value)
        put("action", action)
        if (viaOutboundTag.isNotEmpty()) put("via_outbound_tag", viaOutboundTag)
        put("order", order)
    }

    fun toJson(): String = toJsonObject().toString()
}

// --- /v1/bypass/outbounds (user-defined upstream proxies) -----------------

data class BypassOutbound(
    val id: String,
    val tag: String,
    val type: String,    // socks | http
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val createdAt: String,
) {
    companion object {
        fun fromJson(j: JSONObject) = BypassOutbound(
            id = j.getString("id"),
            tag = j.getString("tag"),
            type = j.getString("type"),
            host = j.getString("host"),
            port = j.getInt("port"),
            username = j.optString("username"),
            password = j.optString("password"),
            createdAt = j.optString("created_at"),
        )

        fun listFromJson(j: JSONObject): List<BypassOutbound> {
            val arr = j.optJSONArray("outbounds") ?: return emptyList()
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}

data class AddBypassOutboundRequest(
    val tag: String,
    val type: String,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("tag", tag)
        put("type", type)
        put("host", host)
        put("port", port)
        if (username.isNotEmpty()) put("username", username)
        if (password.isNotEmpty()) put("password", password)
    }.toString()
}

// --- /v1/services/{name}/... ---------------------------------------------

data class ServiceLogsResponse(
    val service: String,
    val lines: Int,
    val log: String,
) {
    companion object {
        fun fromJson(j: JSONObject) = ServiceLogsResponse(
            service = j.getString("service"),
            lines = j.optInt("lines"),
            log = j.getString("log"),
        )
    }
}

// --- error envelope -------------------------------------------------------

/** Server returns `{"error":{"code":"E_...","message":"..."}}` on failure. */
class AgentApiError(
    val httpCode: Int,
    val code: String,
    val errorMessage: String,
) : Exception("$code: $errorMessage (HTTP $httpCode)") {
    companion object {
        fun fromBody(httpCode: Int, body: String): AgentApiError {
            return try {
                val obj = JSONObject(body).optJSONObject("error")
                if (obj != null) {
                    AgentApiError(httpCode, obj.optString("code"), obj.optString("message"))
                } else {
                    AgentApiError(httpCode, "E_UNKNOWN", body.take(200))
                }
            } catch (_: Exception) {
                AgentApiError(httpCode, "E_HTTP_$httpCode", body.take(200))
            }
        }
    }
}
