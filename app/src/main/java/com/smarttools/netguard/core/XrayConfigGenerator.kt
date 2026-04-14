package com.smarttools.netguard.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.smarttools.netguard.model.*

object XrayConfigGenerator {

    data class GeneratedConfig(
        val json: String,
        val socksPort: Int?,
        val socksUser: String?,
        val socksPass: String?
    )

    fun generate(
        profile: ServerProfile,
        settings: AppSettings,
        useSocksInbound: Boolean = true
    ): GeneratedConfig {
        val root = JsonObject()

        root.add("log", buildLog())
        root.add("dns", buildDns(settings))

        // No API, no stats — prevents data leakage via gRPC/REST (keys omitted entirely)

        var socksPort: Int? = null
        var socksUser: String? = null
        var socksPass: String? = null

        if (useSocksInbound) {
            // Authenticated SOCKS5 on random ephemeral port (for tun2socks)
            val (user, pass, port) = CredentialManager.generate()
            socksPort = port
            socksUser = user
            socksPass = pass
            val httpPort = CredentialManager.getHttpPort()!!
            val noAuthSocksPort = CredentialManager.getNoAuthSocksPort()!!
            root.add("inbounds", buildInbounds(port, httpPort, noAuthSocksPort, user, pass))
        } else {
            root.add("inbounds", JsonArray())
        }

        root.add("outbounds", buildOutbounds(profile))
        root.add("routing", buildRouting(settings))

        return GeneratedConfig(
            json = root.toString(),
            socksPort = socksPort,
            socksUser = socksUser,
            socksPass = socksPass
        )
    }

    private fun buildLog(): JsonObject {
        return JsonObject().apply {
            addProperty("loglevel", "warning")
            addProperty("access", "")
            addProperty("error", "")
        }
    }

    private fun buildDns(settings: AppSettings): JsonObject {
        return JsonObject().apply {
            val servers = JsonArray()
            if (settings.dohEnabled) {
                // Primary: DoH through proxy — encrypted and tunneled
                val dohServer = JsonObject().apply {
                    addProperty("address", "https+local://${settings.primaryDns}/dns-query")
                    add("domains", JsonArray())
                }
                servers.add(dohServer)
            } else {
                val primaryServer = JsonObject().apply {
                    addProperty("address", settings.primaryDns)
                    add("domains", JsonArray())
                }
                servers.add(primaryServer)
            }
            // Secondary DNS fallback
            servers.add(com.google.gson.JsonPrimitive(settings.secondaryDns))
            // Localhost fallback for internal resolution
            servers.add(com.google.gson.JsonPrimitive("localhost"))
            add("servers", servers)
            addProperty("queryStrategy", if (settings.enableIpv6) "UseIP" else "UseIPv4")
            addProperty("disableCache", false)
            addProperty("disableFallback", false)
            // Tag for routing — DNS queries go through proxy, not direct
            addProperty("tag", "dns-out")
        }
    }

    private fun buildInbounds(socksPort: Int, httpPort: Int, noAuthSocksPort: Int, user: String, pass: String): JsonArray {
        val socksInbound = JsonObject().apply {
            addProperty("tag", "tun-in")
            addProperty("port", socksPort)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "password")
                add("accounts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("user", user)
                        addProperty("pass", pass)
                    })
                })
                addProperty("udp", true)
            })
            add("sniffing", JsonObject().apply {
                addProperty("enabled", true)
                add("destOverride", JsonArray().apply {
                    add("http")
                    add("tls")
                    add("quic")
                    add("fakedns")
                })
                addProperty("routeOnly", true)
            })
        }
        val httpInbound = JsonObject().apply {
            addProperty("tag", "http-in")
            addProperty("port", httpPort)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "http")
            add("settings", JsonObject().apply {
                add("accounts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("user", user)
                        addProperty("pass", pass)
                    })
                })
                addProperty("allowTransparent", false)
            })
        }
        // No-auth SOCKS5 for speed test — localhost only, no security risk
        // routeOnly=false so xray overrides destination from SNI (fixes poisoned DNS)
        val noAuthSocksInbound = JsonObject().apply {
            addProperty("tag", "speedtest-in")
            addProperty("port", noAuthSocksPort)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "noauth")
                addProperty("udp", false)
            })
            add("sniffing", JsonObject().apply {
                addProperty("enabled", true)
                add("destOverride", JsonArray().apply {
                    add("http")
                    add("tls")
                    add("quic")
                })
            })
        }
        return JsonArray().apply {
            add(socksInbound)
            add(httpInbound)
            add(noAuthSocksInbound)
        }
    }

    private fun buildOutbounds(profile: ServerProfile): JsonArray {
        val outbounds = JsonArray()
        outbounds.add(buildProxyOutbound(profile))
        outbounds.add(buildDirectOutbound())
        outbounds.add(buildBlockOutbound())
        return outbounds
    }

    private fun buildProxyOutbound(profile: ServerProfile): JsonObject {
        return when (profile.protocol) {
            Protocol.VLESS -> buildVlessOutbound(profile)
            Protocol.VMESS -> buildVmessOutbound(profile)
            Protocol.TROJAN -> buildTrojanOutbound(profile)
            Protocol.SHADOWSOCKS -> buildShadowsocksOutbound(profile)
            Protocol.HYSTERIA2 -> buildHysteria2Outbound(profile)
        }
    }

    private fun buildVlessOutbound(profile: ServerProfile): JsonObject {
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "vless")
            add("settings", JsonObject().apply {
                add("vnext", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", profile.address)
                        addProperty("port", profile.port)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", profile.uuid)
                                addProperty("encryption", profile.encryption.ifEmpty { "none" })
                                if (profile.flow.isNotEmpty()) {
                                    addProperty("flow", profile.flow)
                                }
                            })
                        })
                    })
                })
            })
            add("streamSettings", buildStreamSettings(profile))
        }
    }

    private fun buildVmessOutbound(profile: ServerProfile): JsonObject {
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "vmess")
            add("settings", JsonObject().apply {
                add("vnext", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", profile.address)
                        addProperty("port", profile.port)
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("id", profile.uuid)
                                addProperty("alterId", profile.alterId)
                                addProperty("security", profile.encryption.ifEmpty { "auto" })
                            })
                        })
                    })
                })
            })
            add("streamSettings", buildStreamSettings(profile))
        }
    }

    private fun buildTrojanOutbound(profile: ServerProfile): JsonObject {
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "trojan")
            add("settings", JsonObject().apply {
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", profile.address)
                        addProperty("port", profile.port)
                        addProperty("password", profile.password)
                    })
                })
            })
            add("streamSettings", buildStreamSettings(profile))
        }
    }

    private fun buildShadowsocksOutbound(profile: ServerProfile): JsonObject {
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "shadowsocks")
            add("settings", JsonObject().apply {
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", profile.address)
                        addProperty("port", profile.port)
                        addProperty("method", profile.method)
                        addProperty("password", profile.password)
                    })
                })
            })
            add("streamSettings", buildStreamSettings(profile))
        }
    }

    private fun buildHysteria2Outbound(profile: ServerProfile): JsonObject {
        return JsonObject().apply {
            addProperty("tag", "proxy")
            addProperty("protocol", "hysteria2")
            add("settings", JsonObject().apply {
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("address", profile.address)
                        addProperty("port", profile.port)
                        if (profile.hysteriaAuth.isNotEmpty()) {
                            addProperty("password", profile.hysteriaAuth)
                        }
                    })
                })
            })
            add("streamSettings", JsonObject().apply {
                addProperty("network", "hysteria2")
                if (profile.hysteriaObfs.isNotEmpty()) {
                    add("hysteria2Settings", JsonObject().apply {
                        addProperty("type", profile.hysteriaObfs)
                        addProperty("password", profile.hysteriaObfsPassword)
                    })
                }
                if (profile.security != SecurityType.NONE) {
                    addProperty("security", "tls")
                    add("tlsSettings", JsonObject().apply {
                        if (profile.sni.isNotEmpty()) addProperty("serverName", profile.sni)
                        if (profile.alpn.isNotEmpty()) {
                            add("alpn", JsonArray().apply {
                                profile.alpn.split(",").forEach { add(it.trim()) }
                            })
                        }
                        addProperty("allowInsecure", profile.allowInsecure)
                    })
                }
            })
        }
    }

    private fun buildStreamSettings(profile: ServerProfile): JsonObject {
        return JsonObject().apply {
            addProperty("network", profile.network.value)

            when (profile.network) {
                TransportType.TCP -> {
                    if (profile.headerType.isNotEmpty() && profile.headerType != "none") {
                        add("tcpSettings", JsonObject().apply {
                            add("header", JsonObject().apply {
                                addProperty("type", profile.headerType)
                                if (profile.headerType == "http") {
                                    add("request", JsonObject().apply {
                                        add("headers", JsonObject().apply {
                                            add("Host", JsonArray().apply {
                                                add(profile.host.ifEmpty { profile.address })
                                            })
                                        })
                                        if (profile.path.isNotEmpty()) {
                                            add("path", JsonArray().apply { add(profile.path) })
                                        }
                                    })
                                }
                            })
                        })
                    }
                }
                TransportType.WS -> {
                    add("wsSettings", JsonObject().apply {
                        addProperty("path", profile.path.ifEmpty { "/" })
                        if (profile.host.isNotEmpty()) {
                            add("headers", JsonObject().apply {
                                addProperty("Host", profile.host)
                            })
                        }
                    })
                }
                TransportType.GRPC -> {
                    add("grpcSettings", JsonObject().apply {
                        addProperty("serviceName", profile.serviceName)
                        addProperty("multiMode", profile.mode == "multi")
                        if (profile.authority.isNotEmpty()) {
                            addProperty("authority", profile.authority)
                        }
                    })
                }
                TransportType.HTTP_UPGRADE -> {
                    add("httpupgradeSettings", JsonObject().apply {
                        addProperty("path", profile.path.ifEmpty { "/" })
                        addProperty("host", profile.host.ifEmpty { profile.address })
                    })
                }
                TransportType.SPLIT_HTTP -> {
                    add("splithttpSettings", JsonObject().apply {
                        addProperty("path", profile.path.ifEmpty { "/" })
                        addProperty("host", profile.host.ifEmpty { profile.address })
                    })
                }
                TransportType.KCP -> {
                    add("kcpSettings", JsonObject().apply {
                        addProperty("mtu", 1350)
                        addProperty("tti", 50)
                        addProperty("uplinkCapacity", 12)
                        addProperty("downlinkCapacity", 100)
                        addProperty("congestion", false)
                        addProperty("readBufferSize", 2)
                        addProperty("writeBufferSize", 2)
                        add("header", JsonObject().apply {
                            addProperty("type", profile.headerType.ifEmpty { "none" })
                        })
                        if (profile.seed.isNotEmpty()) {
                            add("seed", com.google.gson.JsonPrimitive(profile.seed))
                        }
                    })
                }
                TransportType.QUIC -> {
                    add("quicSettings", JsonObject().apply {
                        addProperty("security", profile.host.ifEmpty { "none" })
                        addProperty("key", profile.path)
                        add("header", JsonObject().apply {
                            addProperty("type", profile.headerType.ifEmpty { "none" })
                        })
                    })
                }
                TransportType.H2 -> {
                    add("httpSettings", JsonObject().apply {
                        if (profile.host.isNotEmpty()) {
                            add("host", JsonArray().apply {
                                profile.host.split(",").forEach { add(it.trim()) }
                            })
                        }
                        addProperty("path", profile.path.ifEmpty { "/" })
                    })
                }
            }

            when (profile.security) {
                SecurityType.TLS -> {
                    addProperty("security", "tls")
                    add("tlsSettings", JsonObject().apply {
                        if (profile.sni.isNotEmpty()) {
                            addProperty("serverName", profile.sni)
                        }
                        if (profile.fingerprint.isNotEmpty()) {
                            addProperty("fingerprint", profile.fingerprint)
                        }
                        if (profile.alpn.isNotEmpty()) {
                            add("alpn", JsonArray().apply {
                                profile.alpn.split(",").forEach { add(it.trim()) }
                            })
                        }
                        addProperty("allowInsecure", profile.allowInsecure)
                    })
                }
                SecurityType.REALITY -> {
                    addProperty("security", "reality")
                    add("realitySettings", JsonObject().apply {
                        if (profile.sni.isNotEmpty()) {
                            addProperty("serverName", profile.sni)
                        }
                        addProperty("fingerprint", profile.fingerprint.ifEmpty { "chrome" })
                        addProperty("publicKey", profile.publicKey)
                        if (profile.shortId.isNotEmpty()) {
                            addProperty("shortId", profile.shortId)
                        }
                        if (profile.spiderX.isNotEmpty()) {
                            addProperty("spiderX", profile.spiderX)
                        }
                    })
                }
                SecurityType.NONE -> {
                    addProperty("security", "none")
                }
            }
        }
    }

    private fun buildDirectOutbound(): JsonObject {
        return JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
            add("settings", JsonObject().apply {
                // CRITICAL: resolve domains through Xray's internal DNS, not system DNS
                // Prevents DNS leak when traffic goes through "direct" outbound
                addProperty("domainStrategy", "UseIP")
            })
        }
    }

    private fun buildBlockOutbound(): JsonObject {
        return JsonObject().apply {
            addProperty("tag", "block")
            addProperty("protocol", "blackhole")
            add("settings", JsonObject().apply {
                add("response", JsonObject().apply {
                    addProperty("type", "none")
                })
            })
        }
    }

    private fun buildRouting(settings: AppSettings): JsonObject {
        return JsonObject().apply {
            // CRITICAL: IPIfNonMatch forces Xray to resolve domains through its own DNS
            // when no domain-based routing rule matches — prevents system DNS leaks
            addProperty("domainStrategy", "IPIfNonMatch")
            add("rules", JsonArray().apply {
                // CRITICAL: block any reverse connections to localhost through proxy
                add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "block")
                    add("ip", JsonArray().apply {
                        add("127.0.0.0/8")
                        add("::1/128")
                    })
                })

                // CRITICAL: force ALL DNS traffic (port 53) through proxy
                // Prevents apps from making direct DNS queries that leak real IP
                add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("outboundTag", "proxy")
                    addProperty("port", "53")
                })

                if (settings.bypassLan) {
                    // Direct for private/LAN addresses
                    add(JsonObject().apply {
                        addProperty("type", "field")
                        addProperty("outboundTag", "direct")
                        add("ip", JsonArray().apply {
                            add("geoip:private")
                        })
                    })
                    add(JsonObject().apply {
                        addProperty("type", "field")
                        addProperty("outboundTag", "direct")
                        add("domain", JsonArray().apply {
                            add("geosite:private")
                        })
                    })
                }

                when (settings.routingMode) {
                    RoutingMode.GLOBAL_PROXY -> {
                        add(JsonObject().apply {
                            addProperty("type", "field")
                            addProperty("outboundTag", "proxy")
                            addProperty("port", "0-65535")
                        })
                    }
                    RoutingMode.RULE_BASED -> {
                        // RU sites direct
                        add(JsonObject().apply {
                            addProperty("type", "field")
                            addProperty("outboundTag", "direct")
                            add("domain", JsonArray().apply {
                                add("geosite:category-ru")
                            })
                        })
                        add(JsonObject().apply {
                            addProperty("type", "field")
                            addProperty("outboundTag", "direct")
                            add("ip", JsonArray().apply {
                                add("geoip:ru")
                            })
                        })
                        add(JsonObject().apply {
                            addProperty("type", "field")
                            addProperty("outboundTag", "proxy")
                            addProperty("port", "0-65535")
                        })
                    }
                    RoutingMode.DIRECT -> {
                        add(JsonObject().apply {
                            addProperty("type", "field")
                            addProperty("outboundTag", "direct")
                            addProperty("port", "0-65535")
                        })
                    }
                }
            })
        }
    }
}
