package com.smarttools.netguard.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object SecuritySelfTest {

    data class TestResult(
        val testName: String,
        val passed: Boolean,       // true = safe
        val warning: Boolean = false,  // yellow — informational
        val details: String
    )

    private val KNOWN_VPN_PORTS = listOf(
        1080, 1081, 2080, 3066, 3067, 5353, 5555,
        7890, 7891, 7892, 7893, 8080, 8118, 8443, 8888,
        9050, 9090, 9091, 10808, 10809, 10810, 10853,
        15345, 16000, 16100, 19090, 20170, 28981, 51837
    )

    private val KNOWN_VPN_PACKAGES = listOf(
        "com.v2ray.ang", "ang.v2ray.com", "com.github.nicknob",
        "com.neko.box", "com.neko.nekorelay", "app.hiddify.com",
        "com.github.nicknob.trojan", "org.nicknob.hiddify",
        "com.github.nicknob.tunProxy", "com.karing.app",
        "com.v2ray.v2raytun", "com.github.nicknob.happ",
        "com.github.nicknob.hiddify", "com.github.nicknob.nekoray",
        "com.github.nicknob.sing_box", "org.nicknob.singbox",
        "com.zaneschepke.wireguardautotunnel",
        "com.wireguard.android", "org.strongswan.android",
        "de.blinkt.openvpn", "com.github.nicknob.clash",
        "com.github.nicknob.surfboard"
    )

    private val VPN_KEYWORDS_IN_PACKAGE = listOf(
        "v2ray", "xray", "vpn", "proxy", "tunnel", "sing-box",
        "singbox", "clash", "hiddify", "nekobox", "trojan",
        "shadowsocks", "wireguard", "openvpn", "strongswan"
    )

    suspend fun runAllTests(context: Context): List<TestResult> = coroutineScope {
        val results = listOf(
            async { testSocksPortExposed() },
            async { testHttpPortExposed() },
            async { testXrayApiExposed() },
            async { testClashApiExposed() },
            async { testKnownPortsScan() },
            async { testProcNetTcp() },
            async { testTransportVpnFlag(context) },
            async { testMtuAnomaly() },
            async { testPackageDetectable(context) },
        )
        results.awaitAll()
    }

    // 1. SOCKS5 port 10808 without auth
    private suspend fun testSocksPortExposed(): TestResult = withContext(Dispatchers.IO) {
        val standardPorts = listOf(10808, 1080, 2080, 7890)
        val exposed = mutableListOf<Int>()

        for (port in standardPorts) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                    // Try SOCKS5 handshake without auth
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()
                    // SOCKS5 greeting: version=5, 1 method, method=0 (no auth)
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val response = ByteArray(2)
                    val read = inp.read(response)
                    if (read == 2 && response[0] == 0x05.toByte() && response[1] == 0x00.toByte()) {
                        // SOCKS5 accepted NO AUTH — vulnerable!
                        exposed.add(port)
                    }
                }
            } catch (_: Exception) {
                // Connection refused or timeout — safe
            }
        }

        if (exposed.isEmpty()) {
            TestResult("SOCKS5 Proxy", true, details = "No unauthenticated SOCKS5 found on standard ports")
        } else {
            TestResult("SOCKS5 Proxy", false, details = "VULNERABLE: Open SOCKS5 without auth on ports: ${exposed.joinToString()}")
        }
    }

    // 2. HTTP proxy port 10809
    private suspend fun testHttpPortExposed(): TestResult = withContext(Dispatchers.IO) {
        val standardPorts = listOf(10809, 8080, 8118, 7891)
        val exposed = mutableListOf<Int>()

        for (port in standardPorts) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()
                    // HTTP CONNECT attempt
                    out.write("CONNECT api.ipify.org:443 HTTP/1.1\r\nHost: api.ipify.org\r\n\r\n".toByteArray())
                    out.flush()
                    socket.soTimeout = 500
                    BufferedReader(InputStreamReader(inp)).use { reader ->
                        val response = reader.readLine() ?: ""
                        if (response.contains("200")) {
                            exposed.add(port)
                        }
                    }
                }
            } catch (_: Exception) {
                // Safe
            }
        }

        if (exposed.isEmpty()) {
            TestResult("HTTP Proxy", true, details = "No open HTTP CONNECT proxy found on standard ports")
        } else {
            TestResult("HTTP Proxy", false, details = "VULNERABLE: Open HTTP proxy on ports: ${exposed.joinToString()}")
        }
    }

    // 3. Xray gRPC API
    private suspend fun testXrayApiExposed(): TestResult = withContext(Dispatchers.IO) {
        val apiPorts = listOf(10085, 19085, 23456, 10086)
        val exposed = mutableListOf<Int>()

        for (port in apiPorts) {
            if (isPortOpen(port)) {
                exposed.add(port)
            }
        }

        if (exposed.isEmpty()) {
            TestResult("Xray API", true, details = "No Xray gRPC API ports detected")
        } else {
            TestResult("Xray API", false, details = "VULNERABLE: Xray API ports open: ${exposed.joinToString()}")
        }
    }

    // 4. Clash REST API
    private suspend fun testClashApiExposed(): TestResult = withContext(Dispatchers.IO) {
        val clashPorts = listOf(9090, 19090)
        val exposed = mutableListOf<Int>()

        for (port in clashPorts) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()
                    out.write("GET /connections HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                    out.flush()
                    socket.soTimeout = 500
                    BufferedReader(InputStreamReader(inp)).use { reader ->
                        val response = reader.readLine() ?: ""
                        if (response.contains("200")) {
                            exposed.add(port)
                        }
                    }
                }
            } catch (_: Exception) {
                // Safe
            }
        }

        if (exposed.isEmpty()) {
            TestResult("Clash API", true, details = "No Clash REST API detected")
        } else {
            TestResult("Clash API", false, details = "VULNERABLE: Clash API open on ports: ${exposed.joinToString()} — /connections leaks all IPs")
        }
    }

    // 5. Scan 40+ known VPN client ports
    private suspend fun testKnownPortsScan(): TestResult = withContext(Dispatchers.IO) {
        val open = mutableListOf<Int>()

        coroutineScope {
            val jobs = KNOWN_VPN_PORTS.map { port ->
                async(Dispatchers.IO) {
                    if (isPortOpen(port)) port else null
                }
            }
            jobs.awaitAll().filterNotNull().let { open.addAll(it) }
        }

        if (open.isEmpty()) {
            TestResult("Port Scan", true, details = "No known VPN ports open on localhost")
        } else {
            TestResult("Port Scan", false, details = "Detectable ports open: ${open.joinToString()}")
        }
    }

    // 6. /proc/net/tcp analysis
    private suspend fun testProcNetTcp(): TestResult = withContext(Dispatchers.IO) {
        try {
            val listeningPorts = mutableListOf<Int>()
            val files = listOf("/proc/net/tcp", "/proc/net/tcp6")

            for (path in files) {
                val file = File(path)
                if (!file.exists()) continue
                file.readLines().drop(1).forEach { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val state = parts[3]
                        if (state == "0A") { // LISTEN state
                            val localAddr = parts[1]
                            val portHex = localAddr.substringAfter(":")
                            val port = portHex.toIntOrNull(16) ?: 0
                            // Check if listening on localhost (127.0.0.1 = 0100007F)
                            val addrHex = localAddr.substringBefore(":")
                            if (addrHex == "0100007F" || addrHex == "00000000" ||
                                addrHex.endsWith("00000000000000000100007F") ||
                                addrHex == "00000000000000000000000000000000"
                            ) {
                                if (port in KNOWN_VPN_PORTS) {
                                    listeningPorts.add(port)
                                }
                            }
                        }
                    }
                }
            }

            if (listeningPorts.isEmpty()) {
                TestResult("/proc/net/tcp", true, details = "No known VPN ports found in /proc/net/tcp")
            } else {
                TestResult("/proc/net/tcp", false, details = "Detectable in /proc/net/tcp: ${listeningPorts.distinct().joinToString()}")
            }
        } catch (_: Exception) {
            TestResult("/proc/net/tcp", true, warning = true, details = "Cannot read /proc/net/tcp (may be restricted)")
        }
    }

    // 7. TRANSPORT_VPN flag
    private suspend fun testTransportVpnFlag(context: Context): TestResult = withContext(Dispatchers.IO) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val hasVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val capsString = caps?.toString() ?: ""
            val hasIsVpn = capsString.contains("IS_VPN")

            if (hasVpn || hasIsVpn) {
                TestResult("VPN Flag", true, warning = true,
                    details = "TRANSPORT_VPN is visible (unavoidable on Android). Apps can detect VPN is active, but cannot see server IP.")
            } else {
                TestResult("VPN Flag", true, details = "No TRANSPORT_VPN flag detected (VPN not active or check ran before connection)")
            }
        } catch (_: Exception) {
            TestResult("VPN Flag", true, warning = true, details = "Cannot check NetworkCapabilities")
        }
    }

    // 8. MTU anomaly
    private suspend fun testMtuAnomaly(): TestResult = withContext(Dispatchers.IO) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val tunInterfaces = interfaces.filter {
                it.name.startsWith("tun") || it.name.startsWith("ppp")
            }

            if (tunInterfaces.isEmpty()) {
                return@withContext TestResult("MTU Check", true, details = "No TUN interface detected")
            }

            val mtuIssues = mutableListOf<String>()
            for (iface in tunInterfaces) {
                val mtu = iface.mtu
                if (mtu in 1..1499) {
                    mtuIssues.add("${iface.name}: MTU=$mtu (detectable, should be 1500)")
                }
            }

            if (mtuIssues.isEmpty()) {
                TestResult("MTU Check", true, details = "TUN interface MTU is 1500 (standard)")
            } else {
                TestResult("MTU Check", false, details = "MTU anomaly: ${mtuIssues.joinToString("; ")}")
            }
        } catch (_: Exception) {
            TestResult("MTU Check", true, warning = true, details = "Cannot check interface MTU")
        }
    }

    // 9. Package name detection
    private suspend fun testPackageDetectable(context: Context): TestResult = withContext(Dispatchers.IO) {
        val ourPackage = context.packageName.lowercase()
        val hasKeyword = VPN_KEYWORDS_IN_PACKAGE.any { keyword ->
            ourPackage.contains(keyword)
        }

        if (hasKeyword) {
            TestResult("Package Name", false,
                details = "VULNERABLE: Package name '$ourPackage' contains VPN keywords")
        } else {
            TestResult("Package Name", true,
                details = "Package name '$ourPackage' is neutral")
        }
    }

    // Helper
    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
