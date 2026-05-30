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
     * Spin up [count] Telemost-bypass instances on the managed server,
     * each joining its own fresh room with the user's Yandex identity.
     * Returns a task_id; caller polls /v1/tasks/{id} for completion
     * and reads `result.rooms[]` to build the multi-channel URI.
     */
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

        /**
         * Connect once without any pinner, read the leaf certificate
         * straight off the live TLS handshake, and derive its SPKI
         * SHA256. This is the pin we must use for every subsequent
         * call — strictly more correct than fetching cert.pem from
         * disk via openssl (different format quirks, file race vs the
         * agent regenerating). Used during Add-Server bootstrap after
         * SSH install finishes and before /v1/auth/pair.
         */
        /**
         * Pair the freshly-installed agent without any cert pinning,
         * relying on the fact that we **just** SSH-bootstrapped this
         * server seconds ago — anyone who could MITM us here could
         * also have hijacked the SSH session. Returns the new bearer
         * + the SPKI pin we should use for **subsequent** OkHttp calls,
         * computed off the live TLS session via SSLSocket.
         */
        fun bootstrapPair(
            host: String,
            port: Int,
            pairToken: String,
            deviceName: String,
            appVersion: String,
        ): Pair<PairResponse, String> {
            val trustAll = trustAllManager()
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll),
                    java.security.SecureRandom())
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .sslSocketFactory(sslCtx.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .build()
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
            // Compute the SPKI pin off a *separate* SSLSocket so future
            // OkHttp calls (which carry a CertificatePinner) bind to the
            // same Android-side encoding of the public key. Doing both
            // through OkHttp would also work, but Response.handshake
            // came back null on at least one Android HTTP/2 path; raw
            // SSLSocket avoids that bug.
            val pin = fetchSpkiFromLive(host, port, sslCtx)
            return pairResp to pin
        }

        private fun trustAllManager() = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<java.security.cert.X509Certificate>,
                authType: String,
            ) {}
            override fun checkServerTrusted(
                chain: Array<java.security.cert.X509Certificate>,
                authType: String,
            ) {}
            override fun getAcceptedIssuers():
                Array<java.security.cert.X509Certificate> = emptyArray()
        }

        private fun fetchSpkiFromLive(
            host: String,
            port: Int,
            sslCtx: javax.net.ssl.SSLContext,
        ): String {
            var lastError: Exception? = null
            repeat(5) { _ ->
                var socket: javax.net.ssl.SSLSocket? = null
                try {
                    socket = sslCtx.socketFactory.createSocket() as javax.net.ssl.SSLSocket
                    socket.soTimeout = 10_000
                    socket.connect(java.net.InetSocketAddress(host, port), 10_000)
                    socket.startHandshake()
                    val leaf = socket.session.peerCertificates.firstOrNull()
                    if (leaf != null) {
                        val spkiDer = leaf.publicKey?.encoded
                            ?: throw IllegalStateException("encoded public key is null")
                        val sha = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(spkiDer)
                        return sha.joinToString("") { "%02x".format(it) }
                    }
                } catch (e: Exception) {
                    lastError = e
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
                try { Thread.sleep(1500L) } catch (_: InterruptedException) {}
            }
            throw lastError ?: IllegalStateException("no peer cert after 5 tries")
        }

        @Deprecated("use bootstrapPair")
        fun fetchSpkiFromLive(host: String, port: Int): String {
            // OkHttp's Response.handshake can come back null on certain
            // Android HTTP/2 paths even after a successful 200 — verified
            // on the user's phone where /v1/health returned 200 five
            // times but every handshake was null. Going through a raw
            // SSLSocket sidesteps that quirk and guarantees we get the
            // peer certificate chain straight off the SSLSession.
            val trustAll = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String,
                ) {}
                override fun checkServerTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String,
                ) {}
                override fun getAcceptedIssuers():
                    Array<java.security.cert.X509Certificate> = emptyArray()
            }
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll),
                    java.security.SecureRandom())
            }
            var lastError: Exception? = null
            repeat(5) { _ ->
                var socket: javax.net.ssl.SSLSocket? = null
                try {
                    socket = sslCtx.socketFactory.createSocket() as javax.net.ssl.SSLSocket
                    socket.soTimeout = 10_000
                    socket.connect(java.net.InetSocketAddress(host, port), 10_000)
                    socket.startHandshake()
                    val peerCerts = socket.session.peerCertificates
                    val leaf = peerCerts.firstOrNull()
                    if (leaf == null) {
                        lastError = IllegalStateException("empty peerCertificates")
                    } else {
                        val spkiDer = leaf.publicKey?.encoded
                            ?: throw IllegalStateException("public key has no encoded form")
                        val sha = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(spkiDer)
                        return sha.joinToString("") { "%02x".format(it) }
                    }
                } catch (e: Exception) {
                    lastError = e
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
                try { Thread.sleep(1500L) } catch (_: InterruptedException) {}
            }
            throw lastError ?: IllegalStateException(
                "live TLS handshake to $host:$port yielded no peer certificate"
            )
        }

        /**
         * Built per-server because CertificatePinner is host-scoped at
         * construction time; cheap because we share connection pools by
         * keeping a single application-wide client factory.
         *
         * The agent uses a self-signed cert that no system CA chains
         * to. Standard OkHttp validation rejects it with
         * CertPathValidatorException ("Trust anchor not found"). We
         * replace the trust check with a permissive TrustManager and
         * rely on [CertificatePinner] to enforce that the SPKI hash
         * matches the one we captured during bootstrap — that's a
         * stronger guarantee than CA validation for this single host.
         */
        /**
         * Builds an OkHttp client that trusts exactly one self-signed
         * cert — the one whose SPKI hash matches [spkiPin].
         *
         * We deliberately do NOT use [CertificatePinner]. OkHttp's
         * internal SPKI extraction goes through `cert.publicKey.encoded`
         * filtered by a system Provider that, on some Android builds,
         * returns subtly different bytes from a vanilla
         * `MessageDigest.digest(cert.publicKey.encoded)`. We captured
         * the pin earlier through SSLSocket → publicKey.encoded; using
         * the same code path in the TrustManager guarantees the two
         * hashes are computed from byte-identical input and the
         * comparison succeeds.
         */
        private fun buildClient(host: String, spkiPin: String): OkHttpClient {
            val expected = hexToBytes(spkiPin)
            val pinningTm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String,
                ) {}
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
