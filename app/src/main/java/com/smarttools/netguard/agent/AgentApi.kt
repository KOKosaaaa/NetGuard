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
    val label: String = "",
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("protocol", protocol)
        if (port != 0) put("port", port)
        if (uuid.isNotEmpty()) put("uuid", uuid)
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
    val label: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("protocol", protocol)
        if (port != 0) put("port", port)
        if (uuid.isNotEmpty()) put("uuid", uuid)
        if (label.isNotEmpty()) put("label", label)
    }.toString()
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
) {
    companion object {
        fun fromJson(j: JSONObject) = InboundRow(
            inboundId = j.getString("inbound_id"),
            protocol = j.getString("protocol"),
            port = j.getInt("port"),
            profileUri = j.getString("profile_uri"),
            createdAt = j.getString("created_at"),
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
 * action ∈ direct|block (proxy is the implicit default catch-all).
 */
data class BypassRule(
    val id: String,
    val kind: String,
    val value: String,
    val action: String,
    val order: Int,
    val createdAt: String,
) {
    companion object {
        fun fromJson(j: JSONObject) = BypassRule(
            id = j.getString("id"),
            kind = j.getString("kind"),
            value = j.getString("value"),
            action = j.getString("action"),
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
    val action: String,
    val order: Int = 0,
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("value", value)
        put("action", action)
        put("order", order)
    }

    fun toJson(): String = toJsonObject().toString()
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
