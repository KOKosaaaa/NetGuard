package com.smarttools.netguard.agent

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
    private val baseUrl = if (server.endpointUrl.isNotEmpty()) {
        // CF Tunnel mode: endpointUrl is the full root (no port). Trust the
        // CF cert via standard system anchors; bearer carries auth.
        server.endpointUrl.trimEnd('/') + "/v1"
    } else {
        "https://${server.host}:${server.port}/v1"
    }

    private val http: OkHttpClient by lazy {
        if (server.endpointUrl.isNotEmpty()) {
            buildClientStandard()
        } else {
            buildClient(server.host, server.spkiPin)
        }
    }

    fun health(): HealthResponse {
        val resp = doGet("/health", auth = false)
        return HealthResponse.fromJson(JSONObject(resp))
    }

    /**
     * Exchange a one-shot pair-token (read from the agent over SSH at
     * bootstrap time) for a long-lived bearer. The [ManagedServer] this
     * client was built with is expected to have an empty bearer for
     * this call — `auth = false` skips the Authorization header.
     */
    fun pair(req: PairRequest): PairResponse {
        val resp = doPost("/auth/pair", body = req.toJson(), auth = false)
        return PairResponse.fromJson(JSONObject(resp))
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

    /**
     * Fire-and-forget warmup — agent pre-downloads release archives
     * (xray today, sing-box / telemost when those deploy paths ship)
     * into /var/cache/netguard-agent so the next deploy skips the
     * GitHub round-trip. Returns the task_id so the caller can poll if
     * they care, but the normal pattern is to ignore it.
     */
    fun warmupAgent(): TaskAck {
        val resp = doPost("/agent/warmup", body = "{}", auth = true)
        return TaskAck.fromJson(JSONObject(resp))
    }

    /**
     * Install everything up-front on a freshly-paired server: xray (empty
     * config, running), sing-box, and a staged Telemost binary+unit. Makes
     * the user's first profile-create instant instead of paying the xray
     * download then. Async — returns a task_id; the app fires this
     * fire-and-forget after Add-Server (the work runs server-side even if
     * the app closes). Old agents without the endpoint 404 — caller
     * tolerates it (they just fall back to lazy install on first profile).
     */
    fun provisionServer(): TaskAck {
        val resp = doPost("/agent/provision", body = "{}", auth = true)
        return TaskAck.fromJson(JSONObject(resp))
    }

    /**
     * Spin up [count] Telemost-bypass instances on the managed server,
     * each joining its own fresh room with the user's Yandex identity.
     * Returns a task_id; caller polls /v1/tasks/{id} for completion
     * and reads `result.rooms[]` to build the multi-channel URI.
     */
    fun wbStreamAvailable(): Boolean = JSONObject(doGet("/wbstream/health", auth = true)).optBoolean("supported")
    fun wbStreamTransportRevision(): Int = JSONObject(doGet("/wbstream/health", auth = true)).optInt("transport_revision", 0)
    fun deleteWbStream(room: String): TaskAck = TaskAck.fromJson(JSONObject(doPost(
        "/wbstream/delete", body = JSONObject().put("room", room).toString(), auth = true)))
    fun wbStreamRooms(): TelemostRooms = TelemostRooms.fromJson(JSONObject(doGet("/wbstream/rooms", auth = true)))
    fun deployWbStream(room: String, update: Boolean = false, ownerSession: JSONObject? = null): TaskAck = TaskAck.fromJson(JSONObject(doPost(
        "/wbstream/deploy", body = JSONObject().put("room", room).put("update", update).apply {
            if (ownerSession != null) put("owner_session", ownerSession)
        }.toString(), auth = true)))

    fun deployTelemost(count: Int, cookiesJson: String): TaskAck {
        val body = JSONObject().apply {
            put("count", count)
            put("cookies_json", cookiesJson)
        }.toString()
        val resp = doPost("/telemost/deploy", body = body, auth = true)
        return TaskAck.fromJson(JSONObject(resp))
    }

    /** Change the number of running Telemost instances (0..12) without a
     *  full re-deploy. Async — poll /tasks/{id}. */
    fun scaleTelemost(targetCount: Int): TaskAck {
        val body = JSONObject().put("target_count", targetCount).toString()
        return TaskAck.fromJson(JSONObject(doPost("/telemost/scale", body = body, auth = true)))
    }

    /** Replace the Yandex cookies + restart instances (session expired). Async. */
    fun updateTelemostCookies(cookiesJson: String): TaskAck {
        val body = JSONObject().put("cookies_json", cookiesJson).toString()
        return TaskAck.fromJson(JSONObject(doPost("/telemost/cookies", body = body, auth = true)))
    }

    /** Provisioned rooms + per-instance live systemd state. Sync read. */
    fun telemostRooms(): TelemostRooms {
        return TelemostRooms.fromJson(JSONObject(doGet("/telemost/rooms", auth = true)))
    }

    /** Permanently remove the Telemost install (binary, units, cookies,
     *  rooms file). Async — poll /tasks/{id}. */
    fun uninstallTelemost(): TaskAck {
        return TaskAck.fromJson(JSONObject(doPost("/telemost/uninstall", body = "{}", auth = true)))
    }

    /** Upload a new agent binary (raw body) for an in-place self-update.
     *  The agent verifies the sha256, smoke-tests it, swaps it in and
     *  restarts. Returns immediately; caller polls /v1/health for the new
     *  version. Sync/blocking. */
    fun uploadAgentBinary(binary: ByteArray, sha256: String): String {
        val req = Request.Builder()
            .url("$baseUrl/agent/update-upload?sha256=$sha256")
            .post(binary.toRequestBody("application/octet-stream".toMediaType()))
            .applyAuth(true)
            .build()
        // Inherit certificate verification/pinning and auth, but allow a
        // bounded large upload through a slow conference transport.
        val uploadHttp = http.newBuilder()
            .writeTimeout(4, TimeUnit.MINUTES)
            .readTimeout(4, TimeUnit.MINUTES)
            .callTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(false)
            .build()
        return uploadHttp.newCall(req).execute().use { handle(it, false) }
    }

    /** Create + enable a swapfile so a low-RAM VPS survives Telemost peaks.
     *  sizeMb 0 → agent default (512). Async. */
    fun setupSwap(sizeMb: Int = 0): TaskAck {
        val body = if (sizeMb > 0) JSONObject().put("size_mb", sizeMb).toString() else "{}"
        return TaskAck.fromJson(JSONObject(doPost("/agent/swap-setup", body = body, auth = true)))
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

    /**
     * Full self-destruct: the agent wipes every deployed service (xray,
     * sing-box, Telemost) AND itself, then leaves the box clean. Returns
     * as soon as the agent launches the detached purge script; the agent
     * stops answering a few seconds later, so callers confirm by polling
     * /v1/health until it fails. Throws AgentApiError (404) on old agents
     * that don't have this endpoint — update the agent first.
     */
    fun purgeAgent() {
        doPost("/agent/purge", body = "{}", auth = true)
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

    // --- bypass outbounds (user-defined upstream proxies) ---------------

    fun listBypassOutbounds(): List<BypassOutbound> {
        val resp = doGet("/bypass/outbounds", auth = true)
        return BypassOutbound.listFromJson(JSONObject(resp))
    }

    fun addBypassOutbound(req: AddBypassOutboundRequest): BypassOutbound {
        val resp = doPost("/bypass/outbounds", body = req.toJson(), auth = true)
        return BypassOutbound.fromJson(JSONObject(resp))
    }

    fun deleteBypassOutbound(id: String) {
        val req = Request.Builder()
            .url("$baseUrl/bypass/outbounds/$id")
            .delete()
            .applyAuth(true)
            .build()
        execute(req, allowEmpty = true)
    }

    // --- service control -----------------------------------------------

    fun restartService(name: String) {
        doPost("/services/$name/restart", body = "{}", auth = true)
    }

    fun startService(name: String) {
        doPost("/services/$name/start", body = "{}", auth = true)
    }

    fun stopService(name: String) {
        doPost("/services/$name/stop", body = "{}", auth = true)
    }

    fun serviceLogs(name: String, lines: Int = 200): ServiceLogsResponse {
        val resp = doGet("/services/$name/logs?lines=$lines", auth = true)
        return ServiceLogsResponse.fromJson(JSONObject(resp))
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

        /** Pair only with the SPKI received over the bootstrap SSH connection.
         * TLS must prove that key before the one-shot token is sent; never learn
         * a replacement pin from an unauthenticated second TLS connection.
         */
        fun bootstrapPair(
            host: String,
            port: Int,
            pairToken: String,
            deviceName: String,
            appVersion: String,
            expectedSpkiPin: String,
        ): Pair<PairResponse, String> {
            val client = bootstrapClient(expectedSpkiPin)
            val body = PairRequest(pairToken, deviceName, appVersion).toJson()
            val req = Request.Builder()
                .url("https://$host:$port/v1/auth/pair")
                .post(body.toRequestBody(JSON))
                .header("Accept", "application/json")
                .build()
            val pairResp = client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw AgentApiError.fromBody(resp.code, text)
                }
                PairResponse.fromJson(org.json.JSONObject(text))
            }
            return pairResp to expectedSpkiPin
        }

        internal fun bootstrapClient(expectedSpkiPin: String): OkHttpClient = buildClient("", expectedSpkiPin)

        /** The self-signed agent certificate is authenticated by the exact SSH-delivered SPKI. */
        private fun buildClient(host: String, spkiPin: String): OkHttpClient {
            val expected = hexToBytes(spkiPin)
            val pinningTm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String,
                ) { throw java.security.cert.CertificateException("Client certificate authentication is not supported") }
                override fun checkServerTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String,
                ) {
                    val leaf = chain.firstOrNull()
                        ?: throw java.security.cert.CertificateException(
                            "empty server cert chain")
                    val der = leaf.publicKey?.encoded
                        ?: throw java.security.cert.CertificateException(
                            "leaf public key has no encoded form")
                    val sha = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(der)
                    if (!sha.contentEquals(expected)) {
                        throw java.security.cert.CertificateException(
                            "SPKI pin mismatch (expected $spkiPin)")
                    }
                }
                override fun getAcceptedIssuers():
                    Array<java.security.cert.X509Certificate> = emptyArray()
            }
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<javax.net.ssl.TrustManager>(pinningTm),
                    java.security.SecureRandom())
            }
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .sslSocketFactory(sslCtx.socketFactory, pinningTm)
                // CN on the self-signed cert is "netguard-agent", not
                // the IP we connect to. Hostname check would refuse it
                // even though the SPKI is right.
                .hostnameVerifier { _, _ -> true }
                .build()
        }

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length == 64) { "expected 64-char hex SHA256, got ${hex.length}" }
            return ByteArray(32) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        /**
         * Standard-TLS OkHttp client for CF-Tunnel-fronted agents. Uses
         * system trust anchors — the CF edge cert is a public CA chain,
         * so default validation is the right thing. UA is set to a
         * Chrome-like string because Cloudflare Bot Fight Mode rejects
         * requests with empty UA on default zone settings.
         */
        private fun buildClientStandard(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 13) NetGuard-Agent-Client"
                        )
                        .build()
                    chain.proceed(req)
                }
                .build()
        }

        /**
         * One-shot pair against a CF-Tunnel-fronted agent. Hits
         * `<endpointUrl>/v1/auth/pair` with standard TLS verify (no
         * SPKI pin), returns the bearer for the [ManagedServer] we are
         * about to insert. Caller persists the row with
         * [ManagedServer.endpointUrl] set so subsequent calls go through
         * [buildClientStandard].
         */
        fun quickPairByUrl(
            endpointUrl: String,
            pairToken: String,
            deviceName: String,
            appVersion: String,
        ): PairResponse {
            val client = buildClientStandard()
            val base = endpointUrl.trimEnd('/')
            val body = PairRequest(pairToken, deviceName, appVersion).toJson()
            val req = Request.Builder()
                .url("$base/v1/auth/pair")
                .post(body.toRequestBody(JSON))
                .header("Accept", "application/json")
                .header("X-NetGuard-App-Version", appVersion)
                .build()
            return client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw AgentApiError.fromBody(resp.code, text)
                }
                PairResponse.fromJson(JSONObject(text))
            }
        }

    }
}
