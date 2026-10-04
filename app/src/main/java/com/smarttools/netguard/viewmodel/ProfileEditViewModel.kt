package com.smarttools.netguard.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smarttools.netguard.App
import com.smarttools.netguard.core.CredentialManager
import com.smarttools.netguard.model.*
import com.smarttools.netguard.service.TunnelVpnService
import com.smarttools.netguard.util.AddressValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileEditViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val profileRepo = app.profileRepository

    private val _profile = MutableStateFlow(ServerProfile())
    val profile: StateFlow<ServerProfile> = _profile.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    fun loadProfile(id: Long) {
        _saved.value = false
        if (id <= 0) return
        viewModelScope.launch {
            profileRepo.getById(id)?.let {
                _profile.value = if (it.isWbStream) it.copy(protocol = Protocol.WBSTREAM) else it
            }
        }
    }

    fun updateProfile(updater: (ServerProfile) -> ServerProfile) {
        _profile.value = updater(_profile.value)
    }

    private fun isValidAddress(address: String): Boolean {
        if (address.isBlank() || address.length > 253) return false
        if (address.equals("localhost", ignoreCase = true)) return false
        // Delegate the SSRF guard (private / loopback / CGNAT / IPv4-mapped /
        // hex / octal / integer-form) to the same validator that ProfileParser
        // uses, so the Profile Edit screen cannot bypass it.
        if (AddressValidator.isPrivateOrReserved(address)) return false

        val ipv4 = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
        if (ipv4.matches(address)) {
            return address.split(".").all { (it.toIntOrNull() ?: -1) in 0..255 }
        }
        if (address.contains(":")) {
            // Reject anything that doesn't parse as a real IPv6 — the previous
            // implementation accepted "foo::bar:baz" silently.
            return try {
                java.net.InetAddress.getByName("[$address]"); true
            } catch (_: Exception) { false }
        }
        val domain = Regex("^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$")
        return domain.matches(address)
    }

    fun save() {
        viewModelScope.launch {
            val original = _profile.value
            val p = if (original.isWbStream) {
                val rooms = runCatching { WbStreamLink.parse(original.address) }.getOrElse {
                    _testResult.value = com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.wb_stream_invalid_room)
                    return@launch
                }
                original.copy(protocol = Protocol.WBSTREAM, address = rooms.links.joinToString("\n"), port = 443)
            } else if (original.protocol == Protocol.TELEMOST) {
                val validated = runCatching { com.smarttools.netguard.core.ProfileParser.parseSingleUri(original.toUri()) }.getOrNull()
                if (validated == null) { _testResult.value = com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.profile_room_invalid); return@launch }
                original.copy(address = validated.address, port = 443)
            } else original
            if (!p.protocol.usesRelay && (p.address.isBlank() || !isValidAddress(p.address))) return@launch
            if (p.port !in 1..65535) return@launch

            if (p.id == 0L) {
                val id = profileRepo.insert(p)
                _profile.value = p.copy(id = id)
            } else {
                profileRepo.update(p)
            }
            _saved.value = true
        }
    }

    private var deleting = false
    fun delete() {
        if (deleting) return
        viewModelScope.launch {
            deleting = true
            try {
                val p = _profile.value
                if (p.id > 0) {
                    val saved = profileRepo.getById(p.id) ?: p
                    _testResult.value = com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.profile_deleting_rooms)
                    com.smarttools.netguard.agent.WbProfileDeletion.delete(app, saved)
                }
                _saved.value = true
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _testResult.value = com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.profile_delete_unconfirmed)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _testResult.value = com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.profile_kept_details, (if (e is com.smarttools.netguard.agent.WbRoomDeletionException) com.smarttools.netguard.util.LocalizedResources.string(app, e.messageId) else e.message) ?: com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.room_delete_failed)) }
            finally { deleting = false }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            val p = _profile.value
            if (p.protocol.usesRelay) {
                _testResult.value = com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.relay_test_hint)
                return@launch
            }
            if (p.address.isBlank() || !isValidAddress(p.address)) {
                _testResult.value = com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.profile_address_invalid)
                return@launch
            }
            // Direct TCP probe from the user's real IP to the VPN server is a
            // textbook leak — it tells the ISP / corp DPI exactly which VPN
            // provider this device talks to. Only allow the test once the
            // tunnel is up so the SYN goes through xray.
            val state = TunnelVpnService.connectionState.value
            if (state !is ConnectionState.Connected) {
                _testResult.value = com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.profile_test_connect_first)
                return@launch
            }
            val socksPort = CredentialManager.getPort()
            val user = CredentialManager.getUser()
            val pass = CredentialManager.getPass()
            if (socksPort == null || user == null || pass == null) {
                _testResult.value = com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.profile_test_proxy_unready)
                return@launch
            }
            _testResult.value = com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.profile_testing)
            val ms = pingViaSocks(p.address, p.port, socksPort, user, pass)
            _testResult.value = if (ms >= 0) "${ms}ms" else com.smarttools.netguard.util.LocalizedResources.string(app, com.smarttools.netguard.R.string.profile_test_failed)
        }
    }

    /**
     * Measure end-to-end RTT to (host, port) by performing a SOCKS5 CONNECT
     * through the local authenticated proxy. This is the same authenticated
     * bridge that tun2socks uses, so the test reflects what real apps will
     * see when they egress through the tunnel.
     */
    private suspend fun pingViaSocks(
        host: String, port: Int,
        socksPort: Int, user: String, pass: String,
    ): Int = withContext(Dispatchers.IO) {
        val sock = Socket()
        val started = System.currentTimeMillis()
        try {
            sock.connect(InetSocketAddress("127.0.0.1", socksPort), 1500)
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()
            // greeting: ver=5, nmethods=1, USERPASS_AUTH(0x02)
            out.write(byteArrayOf(0x05, 0x01, 0x02))
            out.flush()
            val greet = ByteArray(2)
            if (inp.read(greet) != 2 || greet[0] != 0x05.toByte() || greet[1] != 0x02.toByte()) return@withContext -1
            // userpass sub-negotiation
            val u = user.toByteArray(Charsets.US_ASCII)
            val p = pass.toByteArray(Charsets.US_ASCII)
            val auth = ByteArray(3 + u.size + p.size)
            auth[0] = 0x01
            auth[1] = u.size.toByte()
            System.arraycopy(u, 0, auth, 2, u.size)
            auth[2 + u.size] = p.size.toByte()
            System.arraycopy(p, 0, auth, 3 + u.size, p.size)
            out.write(auth); out.flush()
            val authResp = ByteArray(2)
            if (inp.read(authResp) != 2 || authResp[1] != 0x00.toByte()) return@withContext -1
            // CONNECT request: ver=5, cmd=connect(0x01), rsv=0, atyp=domain(0x03)
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = ((port shr 8) and 0xff).toByte()
            req[6 + hostBytes.size] = (port and 0xff).toByte()
            out.write(req); out.flush()
            val resp = ByteArray(4)
            if (inp.read(resp) != 4 || resp[1] != 0x00.toByte()) return@withContext -1
            // Drain bind addr/port (we don't care about values)
            when (resp[3].toInt() and 0xff) {
                0x01 -> inp.skip(6)             // ipv4 + port
                0x03 -> {
                    val len = inp.read()
                    if (len < 0) return@withContext -1
                    inp.skip(len.toLong() + 2)
                }
                0x04 -> inp.skip(18)            // ipv6 + port
                else -> return@withContext -1
            }
            (System.currentTimeMillis() - started).toInt()
        } catch (_: Exception) {
            -1
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    fun getShareUri(): String {
        return _profile.value.toUri()
    }
}
