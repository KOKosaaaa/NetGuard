package com.smarttools.netguard.core

import android.util.Base64
import android.util.Log
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.smarttools.netguard.model.*
import com.smarttools.netguard.util.AddressValidator
import java.net.URI
import java.net.URLDecoder

object ProfileParser {

    private const val TAG = "ProfileParser"
    /** Max total input size to prevent OOM from deep links / clipboard */
    private const val MAX_INPUT_BYTES = 2 * 1024 * 1024
    /** Max single URI length */
    private const val MAX_URI_LENGTH = 64 * 1024
    /** Max profile name length to prevent UI DoS */
    private const val MAX_NAME_LENGTH = 256

    data class ParseResult(
        val profiles: List<ServerProfile>,
        val errors: List<String>
    )

    fun parseMultiline(input: String): ParseResult {
        if (input.length > MAX_INPUT_BYTES) {
            return ParseResult(emptyList(), listOf("Input too large (max ${MAX_INPUT_BYTES / 1024}KB)"))
        }

        val profiles = mutableListOf<ServerProfile>()
        val errors = mutableListOf<String>()

        val lines = input.trim()
            .replace("\r\n", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

        for (line in lines) {
            try {
                val profile = parseSingleUri(line)
                if (profile != null) {
                    profiles.add(profile)
                } else {
                    errors.add("Unsupported profile format")
                }
            } catch (e: Exception) {
                errors.add("Invalid profile: ${e.javaClass.simpleName}")
                Log.w(TAG, "Failed to parse profile (${e.javaClass.simpleName})")
            }
        }

        return ParseResult(profiles, errors)
    }

    fun parseSubscription(rawContent: String): ParseResult {
        // If the body is already a list of URIs (vless://, telemost://, ...),
        // skip the base64 step. Some sub providers and direct paste workflows
        // hand us plaintext lines that happen to look "base64-ish" enough to
        // not throw, yielding garbage that doesn't match any URI prefix.
        if (rawContent.length > MAX_INPUT_BYTES) return ParseResult(emptyList(), listOf("Subscription too large"))
        val trimmed = rawContent.trim().removePrefix("\uFEFF")
        // JSON subscription: an array (or single object) of full xray configs,
        // the "v2rayN/Happ JSON" convention. Carries routing rules a URI list
        // can't. We only consume the proxy outbound of each config.
        if (looksLikeJson(trimmed)) {
            return parseJsonSubscription(trimmed)
        }
        val looksLikeUri = trimmed.startsWith("vless://") || trimmed.startsWith("vmess://") ||
            trimmed.startsWith("trojan://") || trimmed.startsWith("ss://") ||
            trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://") ||
            trimmed.startsWith("telemost://") || trimmed.startsWith("wbstream://") ||
            trimmed.startsWith("https://stream.wb.ru/room/")
        val decoded = if (looksLikeUri) {
            rawContent
        } else {
            try {
                String(decodeBase64(trimmed), Charsets.UTF_8)
            } catch (_: Exception) {
                rawContent
            }
        }
        // Base64 payload may itself decode to a JSON subscription.
        val decTrimmed = decoded.trim()
        if (looksLikeJson(decTrimmed)) {
            return parseJsonSubscription(decTrimmed)
        }
        return parseMultiline(decoded)
    }

    // ==================== JSON subscription (full xray-config array) ====================

    private fun looksLikeJson(s: String): Boolean {
        val t = s.trimStart()
        return t.startsWith("[") || t.startsWith("{")
    }

    /** Parse a JSON subscription: array (or single object) of full xray configs. */
    fun parseJsonSubscription(content: String): ParseResult {
        if (content.length > MAX_INPUT_BYTES) return ParseResult(emptyList(), listOf("Subscription too large"))
        val profiles = mutableListOf<ServerProfile>()
        val errors = mutableListOf<String>()
        val root = try {
            JsonParser.parseString(content)
        } catch (e: Exception) {
            return ParseResult(emptyList(), listOf("Invalid JSON subscription"))
        }
        val configs = when {
            root.isJsonArray -> root.asJsonArray.toList()
            root.isJsonObject -> listOf(root)
            else -> return ParseResult(emptyList(), listOf("JSON subscription is neither array nor object"))
        }
        for ((i, el) in configs.withIndex()) {
            try {
                if (!el.isJsonObject) continue
                val p = configToProfile(el.asJsonObject)
                if (p != null) profiles.add(p.copy(xrayConfigJson = el.toString()))
                else errors.add("Config #${i + 1}: no usable proxy outbound")
            } catch (e: Exception) {
                errors.add("Config #${i + 1}: invalid or unsupported configuration")
                Log.w(TAG, "Failed to parse JSON config #${i + 1}")
            }
        }
        return ParseResult(profiles, errors)
    }

    private fun configToProfile(cfg: JsonObject): ServerProfile? {
        val name = jStr(cfg, "remarks") ?: jStr(cfg, "ps") ?: ""
        val outbounds = cfg.getAsJsonArray("outbounds") ?: return null
        // Pick the proxy outbound: prefer tag=="proxy", else first real-protocol
        // outbound that isn't the direct/block freedom/blackhole.
        val proxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")
        var ob: JsonObject? = null
        for (e in outbounds) {
            if (!e.isJsonObject) continue
            val o = e.asJsonObject
            val proto = jStr(o, "protocol")?.lowercase() ?: continue
            val tag = jStr(o, "tag")?.lowercase() ?: ""
            if (proto in proxyProtocols && tag != "direct" && tag != "block") {
                if (tag == "proxy") { ob = o; break }
                if (ob == null) ob = o
            }
        }
        if (ob == null) return null
        val proto = jStr(ob, "protocol")!!.lowercase()
        val settings = ob.getAsJsonObject("settings")
        val ss = ob.getAsJsonObject("streamSettings")
        return when (proto) {
            "vless" -> jsonVless(name, settings, ss)
            "vmess" -> jsonVmess(name, settings, ss)
            "trojan" -> jsonTrojan(name, settings, ss)
            "shadowsocks" -> jsonShadowsocks(name, settings)
            else -> null
        }
    }

    private data class StreamInfo(
        val network: TransportType, val security: SecurityType,
        val sni: String, val fp: String, val alpn: String, val allowInsecure: Boolean,
        val pbk: String, val sid: String, val spx: String,
        val host: String, val path: String, val serviceName: String, val mode: String,
        val headerType: String, val authority: String
    )

    private fun parseStream(ss: JsonObject?): StreamInfo {
        var net = jStr(ss, "network") ?: "tcp"
        // xray "xhttp" is the same transport our model calls SPLIT_HTTP ("splithttp").
        if (net.equals("xhttp", true)) net = "splithttp"
        val sec = jStr(ss, "security") ?: "none"
        var sni = ""; var fp = "chrome"; var alpn = ""; var insecure = false
        var pbk = ""; var sid = ""; var spx = ""
        if (sec.equals("reality", true)) {
            val r = ss?.getAsJsonObject("realitySettings")
            sni = jStr(r, "serverName") ?: ""
            pbk = jStr(r, "publicKey") ?: jStr(r, "password") ?: ""
            sid = jStr(r, "shortId") ?: ""
            spx = jStr(r, "spiderX") ?: ""
            fp = jStr(r, "fingerprint") ?: "chrome"
        } else if (sec.equals("tls", true)) {
            val t = ss?.getAsJsonObject("tlsSettings")
            sni = jStr(t, "serverName") ?: ""
            fp = jStr(t, "fingerprint") ?: "chrome"
            t?.getAsJsonArray("alpn")?.let { arr -> alpn = arr.joinToString(",") { it.asString } }
            insecure = t?.get("allowInsecure")?.let { !it.isJsonNull && it.asBoolean } ?: false
        }
        var host = ""; var path = ""; var serviceName = ""; var mode = ""
        var headerType = ""; var authority = ""
        when (net.lowercase()) {
            "grpc" -> {
                val g = ss?.getAsJsonObject("grpcSettings")
                serviceName = jStr(g, "serviceName") ?: ""
                authority = jStr(g, "authority") ?: ""
                if (g?.get("multiMode")?.let { !it.isJsonNull && it.asBoolean } == true) mode = "multi"
            }
            "ws" -> {
                val w = ss?.getAsJsonObject("wsSettings")
                path = jStr(w, "path") ?: "/"
                host = jStr(w?.getAsJsonObject("headers"), "Host") ?: ""
            }
            "httpupgrade" -> {
                val h = ss?.getAsJsonObject("httpupgradeSettings")
                path = jStr(h, "path") ?: "/"
                host = jStr(h, "host") ?: ""
            }
            "splithttp" -> {
                val x = ss?.getAsJsonObject("xhttpSettings")
                    ?: ss?.getAsJsonObject("splithttpSettings")
                path = jStr(x, "path") ?: "/"
                host = jStr(x, "host") ?: ""
                mode = jStr(x, "mode") ?: ""
            }
            "tcp" -> {
                val t = ss?.getAsJsonObject("tcpSettings")
                headerType = jStr(t?.getAsJsonObject("header"), "type") ?: ""
            }
        }
        return StreamInfo(
            TransportType.fromString(net), SecurityType.fromString(sec),
            sni, fp, alpn, insecure, pbk, sid, spx,
            host, path, serviceName, mode, headerType, authority
        )
    }

    private fun jsonVless(name: String, settings: JsonObject?, ss: JsonObject?): ServerProfile {
        val vnext = settings?.getAsJsonArray("vnext")?.firstOrNull()?.asJsonObject
            ?: throw IllegalArgumentException("vless: no vnext")
        val host = jStr(vnext, "address") ?: throw IllegalArgumentException("vless: no address")
        val port = jInt(vnext, "port", 443)
        val user = vnext.getAsJsonArray("users")?.firstOrNull()?.asJsonObject
            ?: throw IllegalArgumentException("vless: no users")
        val uuid = jStr(user, "id") ?: ""
        if (uuid.isBlank()) throw IllegalArgumentException("vless: empty UUID")
        requireValidEndpoint(host, port)
        val s = parseStream(ss)
        return ServerProfile(
            name = safeName(name, "$host:$port"),
            protocol = Protocol.VLESS, address = host, port = port,
            uuid = uuid, encryption = jStr(user, "encryption") ?: "none", flow = jStr(user, "flow") ?: "",
            network = s.network, security = s.security, sni = s.sni, fingerprint = s.fp,
            alpn = s.alpn, allowInsecure = s.allowInsecure, publicKey = s.pbk, shortId = s.sid,
            spiderX = s.spx, host = s.host, path = s.path, serviceName = s.serviceName,
            authority = s.authority, headerType = s.headerType, mode = s.mode
        )
    }

    private fun jsonVmess(name: String, settings: JsonObject?, ss: JsonObject?): ServerProfile {
        val vnext = settings?.getAsJsonArray("vnext")?.firstOrNull()?.asJsonObject
            ?: throw IllegalArgumentException("vmess: no vnext")
        val host = jStr(vnext, "address") ?: throw IllegalArgumentException("vmess: no address")
        val port = jInt(vnext, "port", 443)
        val user = vnext.getAsJsonArray("users")?.firstOrNull()?.asJsonObject
            ?: throw IllegalArgumentException("vmess: no users")
        val uuid = jStr(user, "id") ?: ""
        if (uuid.isBlank()) throw IllegalArgumentException("vmess: empty id")
        requireValidEndpoint(host, port)
        val s = parseStream(ss)
        return ServerProfile(
            name = safeName(name, "$host:$port"),
            protocol = Protocol.VMESS, address = host, port = port,
            uuid = uuid, alterId = jInt(user, "alterId", 0),
            encryption = jStr(user, "security") ?: "auto",
            network = s.network, security = s.security, sni = s.sni, fingerprint = s.fp,
            alpn = s.alpn, allowInsecure = s.allowInsecure, host = s.host, path = s.path,
            serviceName = s.serviceName, headerType = s.headerType, mode = s.mode
        )
    }

    private fun jsonTrojan(name: String, settings: JsonObject?, ss: JsonObject?): ServerProfile {
        val srv = settings?.getAsJsonArray("servers")?.firstOrNull()?.asJsonObject
            ?: throw IllegalArgumentException("trojan: no servers")
        val host = jStr(srv, "address") ?: throw IllegalArgumentException("trojan: no address")
        val port = jInt(srv, "port", 443)
        val pw = jStr(srv, "password") ?: ""
        if (pw.isBlank()) throw IllegalArgumentException("trojan: empty password")
        requireValidEndpoint(host, port)
        val s = parseStream(ss)
        return ServerProfile(
            name = safeName(name, "$host:$port"),
            protocol = Protocol.TROJAN, address = host, port = port, password = pw,
            network = s.network,
            security = if (s.security == SecurityType.NONE) SecurityType.TLS else s.security,
            sni = s.sni, fingerprint = s.fp, alpn = s.alpn, allowInsecure = s.allowInsecure,
            host = s.host, path = s.path, serviceName = s.serviceName, headerType = s.headerType
        )
    }

    private fun jsonShadowsocks(name: String, settings: JsonObject?): ServerProfile {
        val srv = settings?.getAsJsonArray("servers")?.firstOrNull()?.asJsonObject
            ?: throw IllegalArgumentException("ss: no servers")
        val host = jStr(srv, "address") ?: throw IllegalArgumentException("ss: no address")
        val port = jInt(srv, "port", 8388)
        requireValidEndpoint(host, port)
        return ServerProfile(
            name = safeName(name, "$host:$port"),
            protocol = Protocol.SHADOWSOCKS, address = host, port = port,
            method = jStr(srv, "method") ?: "aes-256-gcm", password = jStr(srv, "password") ?: ""
        )
    }

    private fun requireValidEndpoint(host: String, port: Int) {
        if (host.isEmpty()) throw IllegalArgumentException("Empty host")
        if (port !in 1..65535) throw IllegalArgumentException("Invalid port: $port")
        if (host.equals("localhost", ignoreCase = true)) {
            throw IllegalArgumentException("Private/loopback address not allowed: $host")
        }
        AddressValidator.requirePublicAddress(host)
    }

    private fun jStr(o: JsonObject?, key: String): String? {
        val e = o?.get(key) ?: return null
        return if (e.isJsonNull) null else try { e.asString } catch (_: Exception) { null }
    }

    private fun jInt(o: JsonObject?, key: String, default: Int): Int {
        val e = o?.get(key) ?: return default
        return if (e.isJsonNull) default else try { e.asInt } catch (_: Exception) {
            try { e.asString.toInt() } catch (_: Exception) { default }
        }
    }

    fun parseSingleUri(uri: String): ServerProfile? {
        val trimmed = SubscriptionLink.unwrap(uri)
        if (trimmed.length > MAX_URI_LENGTH) {
            throw IllegalArgumentException("URI too long (max $MAX_URI_LENGTH chars)")
        }
        return when {
            trimmed.startsWith("vless://") -> parseVless(trimmed)
            trimmed.startsWith("vmess://") -> parseVmess(trimmed)
            trimmed.startsWith("trojan://") -> parseTrojan(trimmed)
            trimmed.startsWith("ss://") -> parseShadowsocks(trimmed)
            trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://") -> parseHysteria2(trimmed)
            trimmed.startsWith("telemost://") -> parseTelemost(trimmed)
            trimmed.startsWith("https://stream.wb.ru/room/") ||
                trimmed.startsWith("wbstream://") -> parseWbStream(trimmed)
            else -> null
        }
    }

    // WB Stream carrier bypass: the user pastes a room link straight from the
    // headless room-host (https://stream.wb.ru/room/<id>, optional #name). It
    // rides the same relay path as Telemost (Protocol.TELEMOST -> librelay.so),
    // and TelemostRelayManager auto-detects the stream.wb.ru address to launch the
    // wbstream-headless-joiner mode with per-conn ARQ instead of the Telemost mode.
    // Multi-room (newline-separated links) is supported for future throughput
    // scaling; a single link is the N=1 case.
    private fun parseWbStream(uri: String): ServerProfile {
        val normalized = if (uri.startsWith("wbstream://"))
            "https://stream.wb.ru/room/" + uri.removePrefix("wbstream://")
        else uri
        val (body, fragment) = splitFragment(normalized)
        val name = urlDecode(fragment)
        val links = body.split('\n', '\r', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        for (l in links) {
            if (!l.startsWith("https://stream.wb.ru/room/")) {
                throw IllegalArgumentException("WB Stream link must be https://stream.wb.ru/room/<id>, got: ${l.take(60)}")
            }
        }
        if (links.isEmpty()) throw IllegalArgumentException("No WB Stream room link found")
        return ServerProfile(
            name = safeName(name, if (links.size > 1) "WB Stream-x${links.size}" else "WB Stream"),
            protocol = Protocol.TELEMOST,
            address = links.joinToString("\n"),
            port = 443
        )
    }

    private fun parseTelemost(uri: String): ServerProfile {
        val withoutScheme = uri.removePrefix("telemost://")
        val (encoded, fragment) = splitFragment(withoutScheme)
        val name = urlDecode(fragment)
        val decoded = try {
            String(
                decodeBase64(encoded),
                Charsets.UTF_8
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid base64 in telemost URI: ${e.message}")
        }
        // Multi-link profile: newline-separated join URLs. The Telemost relay
        // manager spawns one librelay.so per link and round-robins TCP
        // connections across them via a local SOCKS5 LB. Single-link profiles
        // (no newline) are a degenerate N=1 case using the same code path.
        val links = decoded.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        if (links.isEmpty()) {
            throw IllegalArgumentException("Telemost URI has no join links after decoding")
        }
        for (l in links) {
            if (!l.startsWith("https://telemost.yandex.ru/j/")) {
                throw IllegalArgumentException("Telemost link must be https://telemost.yandex.ru/j/<id>, got: ${l.take(60)}")
            }
        }
        return ServerProfile(
            name = safeName(name, if (links.size > 1) "Telemost-x${links.size}" else "Telemost"),
            protocol = Protocol.TELEMOST,
            address = links.joinToString("\n"),
            port = 443
        )
    }

    // ======================== VLESS ========================
    private fun parseVless(uri: String): ServerProfile {
        // vless://uuid@host:port?params#name
        val withoutScheme = uri.removePrefix("vless://")
        val (mainPart, fragment) = splitFragment(withoutScheme)
        val name = urlDecode(fragment)

        val atIndex = mainPart.indexOf('@')
        if (atIndex < 0) throw IllegalArgumentException("Invalid VLESS URI: missing '@'")
        val uuid = mainPart.substring(0, atIndex)
        // An empty UUID parses fine but produces a config xray refuses, so
        // the server looks "dead" and the failover budget gets burned on it.
        if (uuid.isBlank()) throw IllegalArgumentException("Invalid VLESS URI: empty UUID")
        val rest = mainPart.substring(atIndex + 1)

        val (hostPort, queryString) = splitQuery(rest)
        val (host, port) = parseHostPort(hostPort, 443)
        val params = parseQueryParams(queryString)

        return ServerProfile(
            name = safeName(name, "$host:$port"),
            protocol = Protocol.VLESS,
            address = host,
            port = port,
            uuid = uuid,
            encryption = params["encryption"] ?: "none",
            flow = params["flow"] ?: "",
            network = TransportType.fromString(params["type"] ?: "tcp"),
            security = SecurityType.fromString(params["security"] ?: "none"),
            sni = params["sni"] ?: params["peer"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
            alpn = params["alpn"] ?: "",
            allowInsecure = params["allowInsecure"] == "1",
            publicKey = params["pbk"] ?: "",
            shortId = params["sid"] ?: "",
            spiderX = params["spx"] ?: "",
            host = params["host"] ?: "",
            path = params["path"] ?: "",
            serviceName = params["serviceName"] ?: "",
            authority = params["authority"] ?: "",
            headerType = params["headerType"] ?: "",
            mode = params["mode"] ?: "",
            seed = params["seed"] ?: "",
            xhttpExtra = parseExtra(params["extra"])
        )
    }

    // ======================== VMess ========================
    private fun parseVmess(uri: String): ServerProfile {
        // vmess://base64json
        val encoded = uri.removePrefix("vmess://").trim()
        val jsonStr = String(decodeBase64(encoded), Charsets.UTF_8)
        val json = JsonParser.parseString(jsonStr).asJsonObject

        val host = json.get("add")?.asString ?: ""
        val port = json.get("port")?.asString?.toIntOrNull() ?: 443
        // Same SSRF guard as parseHostPort: VMess JSON otherwise lets a
        // malicious subscription provider hand us 127.0.0.1 / private / CGNAT
        // and have xray probe a local service on connect.
        if (host.isEmpty()) throw IllegalArgumentException("Empty host in VMess URI")
        if (port !in 1..65535) throw IllegalArgumentException("Invalid port in VMess URI: $port")
        if (host.equals("localhost", ignoreCase = true)) {
            throw IllegalArgumentException("Private/loopback address not allowed: $host")
        }
        AddressValidator.requirePublicAddress(host)
        if ((json.get("id")?.asString ?: "").isBlank()) {
            throw IllegalArgumentException("Empty id in VMess URI")
        }
        val rawName = json.get("ps")?.asString ?: ""
        val name = safeName(rawName, "$host:$port")

        val net = json.get("net")?.asString ?: "tcp"
        val tlsVal = json.get("tls")?.asString ?: ""

        return ServerProfile(
            name = name,
            protocol = Protocol.VMESS,
            address = host,
            port = port,
            uuid = json.get("id")?.asString ?: "",
            alterId = json.get("aid")?.asString?.toIntOrNull() ?: 0,
            encryption = json.get("scy")?.asString ?: "auto",
            network = TransportType.fromString(net),
            security = when {
                tlsVal.equals("tls", true) -> SecurityType.TLS
                tlsVal.equals("reality", true) -> SecurityType.REALITY
                else -> SecurityType.NONE
            },
            sni = json.get("sni")?.asString ?: "",
            fingerprint = json.get("fp")?.asString ?: "chrome",
            alpn = json.get("alpn")?.asString ?: "",
            host = json.get("host")?.asString ?: "",
            path = json.get("path")?.asString ?: "",
            headerType = json.get("type")?.asString ?: "",
        )
    }

    // ======================== Trojan ========================
    private fun parseTrojan(uri: String): ServerProfile {
        // trojan://password@host:port?params#name
        val withoutScheme = uri.removePrefix("trojan://")
        val (mainPart, fragment) = splitFragment(withoutScheme)
        val name = urlDecode(fragment)

        val atIndex = mainPart.indexOf('@')
        if (atIndex < 0) throw IllegalArgumentException("Invalid Trojan URI: missing '@'")
        val password = urlDecode(mainPart.substring(0, atIndex))
        if (password.isBlank()) throw IllegalArgumentException("Invalid Trojan URI: empty password")
        val rest = mainPart.substring(atIndex + 1)

        val (hostPort, queryString) = splitQuery(rest)
        val (host, port) = parseHostPort(hostPort, 443)
        val params = parseQueryParams(queryString)

        val securityStr = params["security"] ?: "tls"

        return ServerProfile(
            name = safeName(name, "$host:$port"),
            protocol = Protocol.TROJAN,
            address = host,
            port = port,
            password = password,
            network = TransportType.fromString(params["type"] ?: "tcp"),
            security = SecurityType.fromString(securityStr),
            sni = params["sni"] ?: params["peer"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
            alpn = params["alpn"] ?: "",
            allowInsecure = params["allowInsecure"] == "1",
            host = params["host"] ?: "",
            path = params["path"] ?: "",
            serviceName = params["serviceName"] ?: "",
            headerType = params["headerType"] ?: "",
        )
    }

    // ======================== Shadowsocks ========================
    private fun parseShadowsocks(uri: String): ServerProfile {
        val withoutScheme = uri.removePrefix("ss://")
        val (mainPart, fragment) = splitFragment(withoutScheme)
        val name = urlDecode(fragment)

        // SIP002 format: base64(method:password)@host:port
        // Legacy format: base64(method:password@host:port)
        return if (mainPart.contains("@")) {
            parseSsSip002(mainPart, name)
        } else {
            parseSsLegacy(mainPart, name)
        }
    }

    private fun parseSsSip002(mainPart: String, name: String): ServerProfile {
        val atIndex = mainPart.lastIndexOf('@')
        if (atIndex < 0) throw IllegalArgumentException("Invalid SS SIP002 URI: missing '@'")
        val userInfoEncoded = mainPart.substring(0, atIndex)
        val hostPortQuery = mainPart.substring(atIndex + 1)

        val userInfo = try {
            String(decodeBase64(userInfoEncoded), Charsets.UTF_8)
        } catch (_: Exception) {
            urlDecode(userInfoEncoded)
        }

        val colonIndex = userInfo.indexOf(':')
        val method = if (colonIndex >= 0) userInfo.substring(0, colonIndex) else "aes-256-gcm"
        val password = if (colonIndex >= 0) userInfo.substring(colonIndex + 1) else userInfo

        val (hostPort, _) = splitQuery(hostPortQuery)
        val (host, port) = parseHostPort(hostPort, 8388)

        return ServerProfile(
            name = safeName(name, "$host:$port"),
            protocol = Protocol.SHADOWSOCKS,
            address = host,
            port = port,
            method = method,
            password = password,
        )
    }

    private fun parseSsLegacy(encoded: String, name: String): ServerProfile {
        val decoded = String(decodeBase64(encoded), Charsets.UTF_8)
        // format: method:password@host:port
        val atIndex = decoded.lastIndexOf('@')
        if (atIndex < 0) throw IllegalArgumentException("Invalid SS legacy format: missing @")
        val userInfo = decoded.substring(0, atIndex)
        val hostPort = decoded.substring(atIndex + 1)

        val colonIndex = userInfo.indexOf(':')
        if (colonIndex < 0) throw IllegalArgumentException("Invalid SS legacy format: missing method:password separator")
        val method = userInfo.substring(0, colonIndex)
        val password = userInfo.substring(colonIndex + 1)

        val (host, port) = parseHostPort(hostPort, 8388)

        return ServerProfile(
            name = safeName(name, "$host:$port"),
            protocol = Protocol.SHADOWSOCKS,
            address = host,
            port = port,
            method = method,
            password = password,
        )
    }

    // ======================== Hysteria2 ========================
    private fun parseHysteria2(uri: String): ServerProfile {
        // hysteria2://auth@host:port?params#name
        // hy2://auth@host:port?params#name
        val withoutScheme = uri
            .removePrefix("hysteria2://")
            .removePrefix("hy2://")

        val (mainPart, fragment) = splitFragment(withoutScheme)
        val name = urlDecode(fragment)

        val atIndex = mainPart.indexOf('@')
        val auth = if (atIndex >= 0) urlDecode(mainPart.substring(0, atIndex)) else ""
        val rest = if (atIndex >= 0) mainPart.substring(atIndex + 1) else mainPart

        val (hostPort, queryString) = splitQuery(rest)
        val (host, port) = parseHostPort(hostPort, 443)
        val params = parseQueryParams(queryString)

        return ServerProfile(
            name = safeName(name, "$host:$port"),
            protocol = Protocol.HYSTERIA2,
            address = host,
            port = port,
            hysteriaAuth = auth,
            hysteriaObfs = params["obfs"] ?: "",
            hysteriaObfsPassword = params["obfs-password"] ?: "",
            sni = params["sni"] ?: "",
            allowInsecure = params["insecure"] == "1",
            security = SecurityType.TLS,
        )
    }

    // ======================== Helpers ========================
    private fun decodeBase64(value: String): ByteArray {
        val compact = value.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
        return java.util.Base64.getDecoder().decode(compact)
    }

    private fun parseExtra(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val json = if (value.trimStart().startsWith("{")) value else String(decodeBase64(value), Charsets.UTF_8)
        val parsed = JsonParser.parseString(json)
        require(parsed.isJsonObject) { "XHTTP extra must be an object" }
        return parsed.toString()
    }

    private fun splitFragment(s: String): Pair<String, String> {
        val idx = s.indexOf('#')
        return if (idx >= 0) {
            Pair(s.substring(0, idx), s.substring(idx + 1))
        } else {
            Pair(s, "")
        }
    }

    private fun splitQuery(s: String): Pair<String, String> {
        val idx = s.indexOf('?')
        return if (idx >= 0) {
            Pair(s.substring(0, idx), s.substring(idx + 1))
        } else {
            Pair(s, "")
        }
    }

    private fun parseHostPort(s: String, defaultPort: Int): Pair<String, Int> {
        val (host, port) = if (s.startsWith("[")) {
            // IPv6: [::1]:port
            val endBracket = s.indexOf(']')
            if (endBracket < 0) {
                Pair(s.removePrefix("["), defaultPort)
            } else {
                val h = s.substring(1, endBracket)
                val portStr = if (endBracket + 1 < s.length && s[endBracket + 1] == ':') {
                    s.substring(endBracket + 2)
                } else ""
                Pair(h, portStr.toIntOrNull() ?: defaultPort)
            }
        } else {
            val lastColon = s.lastIndexOf(':')
            if (lastColon >= 0) {
                val h = s.substring(0, lastColon)
                val p = s.substring(lastColon + 1).toIntOrNull() ?: defaultPort
                Pair(h, p)
            } else {
                Pair(s, defaultPort)
            }
        }
        if (host.isEmpty()) throw IllegalArgumentException("Empty host in URI")
        if (port !in 1..65535) throw IllegalArgumentException("Invalid port: $port")
        // localhost and obviously-bogus strings that InetAddress would not reject
        // by resolution alone.
        val h = host.lowercase()
        if (h == "localhost") {
            throw IllegalArgumentException("Private/loopback address not allowed: $host")
        }
        // All other private / reserved / CGNAT / hex-IPv4 / IPv6-mapped checks
        // now live in AddressValidator (resolves numerically so alternative
        // textual forms of the same address cannot bypass the filter).
        AddressValidator.requirePublicAddress(host)
        return Pair(host, port)
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        query.split("&").forEach { param ->
            val eq = param.indexOf('=')
            if (eq >= 0) {
                val key = param.substring(0, eq)
                val value = urlDecode(param.substring(eq + 1))
                map[key] = value
            }
        }
        return map
    }

    private fun urlDecode(s: String): String {
        return try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
    }

    /** Truncate profile name to prevent UI DoS from malicious subscriptions */
    private fun safeName(name: String, fallback: String): String {
        val n = name.ifEmpty { fallback }
        return if (n.length > MAX_NAME_LENGTH) n.take(MAX_NAME_LENGTH) else n
    }
}
