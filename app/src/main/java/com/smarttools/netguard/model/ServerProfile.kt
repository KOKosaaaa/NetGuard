package com.smarttools.netguard.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "profiles",
    indices = [
        Index("subscriptionId"),
        Index("isSelected")
    ]
)
data class ServerProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val protocol: Protocol = Protocol.VLESS,
    val address: String = "",
    val port: Int = 443,

    // VLESS / VMess
    val uuid: String = "",
    val alterId: Int = 0,
    val encryption: String = "none",
    val flow: String = "",

    // Trojan
    val password: String = "",

    // Shadowsocks
    val method: String = "aes-256-gcm",

    // Hysteria2
    val hysteriaAuth: String = "",
    val hysteriaObfs: String = "",
    val hysteriaObfsPassword: String = "",

    // Transport
    val network: TransportType = TransportType.TCP,
    val headerType: String = "",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val authority: String = "",
    val mode: String = "",
    val seed: String = "",

    // Security
    val security: SecurityType = SecurityType.NONE,
    val sni: String = "",
    // Empty means "follow the global TlsFingerprintMode setting". A non-empty
    // value (set by ProfileParser when the URL explicitly carries `fp=...`)
    // is treated as a per-profile override and wins over the global setting.
    // Previously this defaulted to "chrome", which made global TLS fingerprint
    // rotation a no-op for any profile that did not explicitly override it.
    val fingerprint: String = "",
    val alpn: String = "",
    val allowInsecure: Boolean = false,

    // Reality
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",

    // DNS
    val dns: String = "",

    // Metadata
    val subscriptionId: Long = 0,
    val sortOrder: Int = 0,
    val lastPingMs: Int = -1,
    val isSelected: Boolean = false,
    val isFavorite: Boolean = false
) {
    fun toUri(): String {
        return when (protocol) {
            Protocol.VLESS -> buildVlessUri()
            Protocol.VMESS -> buildVmessUri()
            Protocol.TROJAN -> buildTrojanUri()
            Protocol.SHADOWSOCKS -> buildShadowsocksUri()
            Protocol.HYSTERIA2 -> buildHysteria2Uri()
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun buildVlessUri(): String {
        val params = mutableListOf<String>()
        if (encryption.isNotEmpty()) params.add("encryption=${enc(encryption)}")
        if (flow.isNotEmpty()) params.add("flow=${enc(flow)}")
        params.add("type=${network.value}")
        if (security != SecurityType.NONE) params.add("security=${security.value}")
        if (sni.isNotEmpty()) params.add("sni=${enc(sni)}")
        if (fingerprint.isNotEmpty()) params.add("fp=${enc(fingerprint)}")
        if (alpn.isNotEmpty()) params.add("alpn=${enc(alpn)}")
        if (publicKey.isNotEmpty()) params.add("pbk=${enc(publicKey)}")
        if (shortId.isNotEmpty()) params.add("sid=${enc(shortId)}")
        if (spiderX.isNotEmpty()) params.add("spx=${enc(spiderX)}")
        if (host.isNotEmpty()) params.add("host=${enc(host)}")
        if (path.isNotEmpty()) params.add("path=${enc(path)}")
        if (serviceName.isNotEmpty()) params.add("serviceName=${enc(serviceName)}")
        if (headerType.isNotEmpty()) params.add("headerType=${enc(headerType)}")
        val query = params.joinToString("&")
        val fragment = enc(name)
        return "vless://$uuid@$address:$port?$query#$fragment"
    }

    private fun buildVmessUri(): String {
        val json = com.google.gson.JsonObject().apply {
            addProperty("v", "2")
            addProperty("ps", name)
            addProperty("add", address)
            addProperty("port", port.toString())
            addProperty("id", uuid)
            addProperty("aid", alterId.toString())
            addProperty("scy", if (encryption.isEmpty()) "auto" else encryption)
            addProperty("net", network.value)
            addProperty("type", if (headerType.isEmpty()) "none" else headerType)
            addProperty("host", host)
            addProperty("path", path)
            addProperty("tls", if (security == SecurityType.TLS) "tls" else "")
            addProperty("sni", sni)
            addProperty("alpn", alpn)
            addProperty("fp", fingerprint)
        }
        val encoded = android.util.Base64.encodeToString(
            json.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        return "vmess://$encoded"
    }

    private fun buildTrojanUri(): String {
        val params = mutableListOf<String>()
        params.add("type=${network.value}")
        if (security != SecurityType.NONE) params.add("security=${security.value}")
        if (sni.isNotEmpty()) params.add("sni=${enc(sni)}")
        if (fingerprint.isNotEmpty()) params.add("fp=${enc(fingerprint)}")
        if (alpn.isNotEmpty()) params.add("alpn=${enc(alpn)}")
        if (host.isNotEmpty()) params.add("host=${enc(host)}")
        if (path.isNotEmpty()) params.add("path=${enc(path)}")
        if (serviceName.isNotEmpty()) params.add("serviceName=${enc(serviceName)}")
        if (headerType.isNotEmpty()) params.add("headerType=${enc(headerType)}")
        val query = params.joinToString("&")
        val fragment = enc(name)
        return "trojan://${enc(password)}@$address:$port?$query#$fragment"
    }

    private fun buildShadowsocksUri(): String {
        val userInfo = android.util.Base64.encodeToString(
            "$method:$password".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
        )
        return "ss://$userInfo@$address:$port#${enc(name)}"
    }

    private fun buildHysteria2Uri(): String {
        val params = mutableListOf<String>()
        if (sni.isNotEmpty()) params.add("sni=${enc(sni)}")
        if (hysteriaObfs.isNotEmpty()) params.add("obfs=${enc(hysteriaObfs)}")
        if (hysteriaObfsPassword.isNotEmpty()) params.add("obfs-password=${enc(hysteriaObfsPassword)}")
        if (allowInsecure) params.add("insecure=1")
        val query = params.joinToString("&")
        return "hysteria2://${enc(hysteriaAuth)}@$address:$port?$query#${enc(name)}"
    }
}

enum class Protocol(val value: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),
    SHADOWSOCKS("shadowsocks"),
    HYSTERIA2("hysteria2");

    companion object {
        fun fromString(s: String): Protocol = entries.firstOrNull {
            it.value.equals(s, ignoreCase = true)
        } ?: VLESS
    }
}

enum class TransportType(val value: String) {
    TCP("tcp"),
    WS("ws"),
    GRPC("grpc"),
    HTTP_UPGRADE("httpupgrade"),
    SPLIT_HTTP("splithttp"),
    KCP("kcp"),
    QUIC("quic"),
    H2("h2");

    companion object {
        fun fromString(s: String): TransportType = entries.firstOrNull {
            it.value.equals(s, ignoreCase = true)
        } ?: TCP
    }
}

enum class SecurityType(val value: String) {
    NONE("none"),
    TLS("tls"),
    REALITY("reality");

    companion object {
        fun fromString(s: String): SecurityType = entries.firstOrNull {
            it.value.equals(s, ignoreCase = true)
        } ?: NONE
    }
}
