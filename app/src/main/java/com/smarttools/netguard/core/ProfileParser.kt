package com.smarttools.netguard.core

import android.util.Base64
import android.util.Log
import com.google.gson.JsonParser
import com.smarttools.netguard.model.*
import com.smarttools.netguard.util.AddressValidator
import java.net.URI
import java.net.URLDecoder

object ProfileParser {

    private const val TAG = "ProfileParser"
    /** Max total input size to prevent OOM from deep links / clipboard */
    private const val MAX_INPUT_BYTES = 512 * 1024 // 512 KB
    /** Max single URI length */
    private const val MAX_URI_LENGTH = 8192
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
            .filter { it.isNotEmpty() }

        for (line in lines) {
            try {
                val profile = parseSingleUri(line)
                if (profile != null) {
                    profiles.add(profile)
                } else {
                    errors.add("Unsupported URI: ${line.take(50)}...")
                }
            } catch (e: Exception) {
                errors.add("Error parsing: ${line.take(50)}... — ${e.message}")
                Log.w(TAG, "Failed to parse URI: $line", e)
            }
        }

        return ParseResult(profiles, errors)
    }

    fun parseSubscription(rawContent: String): ParseResult {
        // Subscription is base64-encoded list of URIs
        val decoded = try {
            String(Base64.decode(rawContent.trim(), Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            // Not base64 — try as plain text
            rawContent
        }
        return parseMultiline(decoded)
    }

    fun parseSingleUri(uri: String): ServerProfile? {
        val trimmed = uri.trim()
        if (trimmed.length > MAX_URI_LENGTH) {
            throw IllegalArgumentException("URI too long (max $MAX_URI_LENGTH chars)")
        }
        return when {
            trimmed.startsWith("vless://") -> parseVless(trimmed)
            trimmed.startsWith("vmess://") -> parseVmess(trimmed)
            trimmed.startsWith("trojan://") -> parseTrojan(trimmed)
            trimmed.startsWith("ss://") -> parseShadowsocks(trimmed)
            trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://") -> parseHysteria2(trimmed)
            else -> null
        }
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
            spiderX = urlDecode(params["spx"] ?: ""),
            host = params["host"] ?: "",
            path = urlDecode(params["path"] ?: ""),
            serviceName = params["serviceName"] ?: "",
            authority = params["authority"] ?: "",
            headerType = params["headerType"] ?: "",
            mode = params["mode"] ?: "",
            seed = params["seed"] ?: ""
        )
    }

    // ======================== VMess ========================
    private fun parseVmess(uri: String): ServerProfile {
        // vmess://base64json
        val encoded = uri.removePrefix("vmess://").trim()
        val jsonStr = String(Base64.decode(encoded, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
        val json = JsonParser.parseString(jsonStr).asJsonObject

        val host = json.get("add")?.asString ?: ""
        val port = json.get("port")?.asString?.toIntOrNull() ?: 443
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
            path = urlDecode(params["path"] ?: ""),
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
            String(Base64.decode(userInfoEncoded, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
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
        val decoded = String(Base64.decode(encoded, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
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
