package com.smarttools.netguard.agent

import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One client = one [ManagedServer]. Cheap to construct: OkHttpClient is
 * built per-call (connection pool is on the underlying client we share),
 * the pin is baked in.
 *
 * Why per-server: each agent has its own self-signed cert with its own
 * SPKI hash. CertificatePinner is configured per-host, so reusing one
 * giant OkHttpClient across servers would require a pin entry for each
 * host added at construction time — refactor for later.
 *
 * Threading: every call here is **blocking** — callers run them from
 * `Dispatchers.IO` inside their suspend functions.
 */
class AgentApiClient(
    private val server: ManagedServer,
    private val appVersion: String,
) {
    private val baseUrl = "https://${server.host}:${server.port}/v1"

    private val http: OkHttpClient by lazy { buildClient(server.host, server.spkiPin) }

    fun health(): HealthResponse {
        val resp = doGet("/health", auth = false)
        return HealthResponse.fromJson(JSONObject(resp))
    }

    fun status(): StatusResponse {
        val resp = doGet("/status", auth = true)
        return StatusResponse.fromJson(JSONObject(resp))
    }

    fun rotate(): RotateResponse {
        val resp = doPost("/auth/rotate", body = "{}", auth = true)
        return RotateResponse.fromJson(JSONObject(resp))
    }

    fun revoke() {
        doPost("/auth/revoke", body = "{}", auth = true, expectEmpty = true)
    }

    fun deployXray(req: DeployXrayRequest): TaskAck {
        val resp = doPost("/xray/deploy", body = req.toJson(), auth = true)
        return TaskAck.fromJson(JSONObject(resp))
    }

    fun task(id: String): TaskState {
        val resp = doGet("/tasks/$id", auth = true)
        return TaskState.fromJson(JSONObject(resp))
    }

    fun inbounds(): List<InboundRow> {
        val resp = doGet("/xray/inbounds", auth = true)
        return InboundRow.listFromJson(JSONObject(resp))
    }

    /**
     * Sync — adds a VLESS inbound to a running xray and returns the new
     * vless:// URI. Caller usually exposes this as "Add another profile"
     * once the server is set up.
     */
    fun addProfile(req: AddProfileRequest): InboundResult {
        val resp = doPost("/xray/profile", body = req.toJson(), auth = true)
        return InboundResult.fromJson(JSONObject(resp))
    }

    fun deleteProfile(inboundId: String) {
        val req = Request.Builder()
            .url("$baseUrl/xray/profile/$inboundId")
            .delete()
            .applyAuth(true)
            .build()
        execute(req, allowEmpty = true)
    }

    /**
     * Wipe an externally-installed xray so a subsequent /xray/deploy can
     * proceed. Async — returns a task_id; poll /tasks/{id} for completion.
     * Used after /xray/deploy returned E_XRAY_PREEXISTING and the user
     * confirmed "Wipe and reinstall" in the UI dialog.
     */
    fun uninstallXray(): TaskAck {
        val resp = doPost("/xray/uninstall", body = "{}", auth = true)
        return TaskAck.fromJson(JSONObject(resp))
    }

    // --- bypass rules -----------------------------------------------------

    fun listBypassRules(): List<BypassRule> {
        val resp = doGet("/bypass/rules", auth = true)
        return BypassRule.listFromJson(JSONObject(resp))
    }

    fun addBypassRule(req: AddBypassRuleRequest): BypassRule {
        val resp = doPost("/bypass/rules", body = req.toJson(), auth = true)
        return BypassRule.fromJson(JSONObject(resp))
    }

    /** PUT — atomic batch replace. Empty list clears all rules. */
    fun replaceBypassRules(rules: List<AddBypassRuleRequest>): List<BypassRule> {
        val arr = org.json.JSONArray()
        rules.forEach { arr.put(it.toJsonObject()) }
        val body = JSONObject().put("rules", arr).toString()
        val req = Request.Builder()
            .url("$baseUrl/bypass/rules")
            .put(body.toRequestBody(JSON))
            .applyAuth(true)
            .build()
        val out = execute(req)
        return BypassRule.listFromJson(JSONObject(out))
    }

    fun deleteBypassRule(id: String) {
        val req = Request.Builder()
            .url("$baseUrl/bypass/rules/$id")
            .delete()
            .applyAuth(true)
            .build()
        execute(req, allowEmpty = true)
    }

    // --- HTTP plumbing ----------------------------------------------------

    private fun doGet(path: String, auth: Boolean): String {
        val req = Request.Builder().url(baseUrl + path).get().applyAuth(auth).build()
        return execute(req)
    }

    private fun doPost(
        path: String,
        body: String,
        auth: Boolean,
        expectEmpty: Boolean = false,
    ): String {
        val req = Request.Builder()
            .url(baseUrl + path)
            .post(body.toRequestBody(JSON))
            .applyAuth(auth)
            .build()
        return execute(req, allowEmpty = expectEmpty)
    }

    private fun Request.Builder.applyAuth(auth: Boolean): Request.Builder {
        if (auth) header("Authorization", "Bearer ${server.bearer}")
        header("X-NetGuard-App-Version", appVersion)
        header("Accept", "application/json")
        return this
    }

    private fun execute(req: Request, allowEmpty: Boolean = false): String {
        http.newCall(req).execute().use { resp -> return handle(resp, allowEmpty) }
    }

    private fun handle(resp: Response, allowEmpty: Boolean): String {
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            throw AgentApiError.fromBody(resp.code, body)
        }
        if (allowEmpty && body.isBlank()) return ""
        return body
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Built per-server because CertificatePinner is host-scoped at
         * construction time; cheap because we share connection pools by
         * keeping a single application-wide client factory.
         */
        private fun buildClient(host: String, spkiPin: String): OkHttpClient {
            val pinner = CertificatePinner.Builder()
                .add(host, "sha256/${spkiPin.spkiHexToBase64()}")
                .build()
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .certificatePinner(pinner)
                .build()
        }

        /**
         * OkHttp wants `sha256/<base64>` while the agent emits the SPKI
         * hash as lowercase hex (matches the journalctl line + e2e test).
         * Convert at the boundary.
         */
        private fun String.spkiHexToBase64(): String {
            require(length == 64) { "expected 64-char hex SHA256, got ${length}: $this" }
            val bytes = ByteArray(32) { i ->
                substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.NO_WRAP,
            )
        }
    }
}
