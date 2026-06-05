package com.smarttools.netguard.service

import android.util.Log
import com.smarttools.netguard.model.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * Owns one or more librelay.so subprocesses and a local SOCKS5 round-robin
 * load balancer that fans out new connections across them.
 *
 * Single-link profile.address = one Telemost URL; one child relay.
 * Multi-link profile.address = newline-separated URLs; N child relays + LB.
 *
 * In both cases tun2socks talks to a single "exposed" SOCKS5 port; the LB
 * is the demultiplexer. New TCP connections round-robin across upstreams,
 * so workloads with many concurrent connections (typical browsing) get
 * aggregate bandwidth, while a single big stream is still capped at one
 * Telemost room's per-stream limit.
 */
// Gates WLB_CARRIER_MUX (several users share one room). MUST match the server
// creators (agent telemostMultiClient) - mux<->single-client framing is
// incompatible, so flip both together. Default false = one user per room.
private const val TELEMOST_MULTI_CLIENT = false

class TelemostRelayManager(
    private val nativeLibDir: String,
    private val onLog: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onTunnelLost: () -> Unit = {}
) {
    companion object {
        private const val TAG = "TelemostRelay"
        private const val SIGNALING_PORT_BASE = 9001
        private const val INTERNAL_SOCKS_BASE = 38000

        // Random Russian name per join so the device shows as a normal user in
        // participant lists, not "NetGuard" (OpSec: don't leak the technique).
        private val BOT_NAMES = arrayOf(
            "Гоша", "Миша", "Дмитрий", "Александр", "Иван", "Сергей",
            "Андрей", "Николай", "Алексей", "Виктор", "Олег", "Павел",
            "Юрий", "Игорь", "Константин", "Артём", "Денис", "Кирилл",
            "Максим", "Антон", "Владимир", "Роман", "Евгений", "Тимур",
            "Богдан", "Глеб", "Семён", "Лев", "Степан", "Фёдор"
        )

        private fun pickDisplayName(): String =
            BOT_NAMES[java.util.Random().nextInt(BOT_NAMES.size)]
    }

    private val instances = mutableListOf<RelayInstance>()
    // In-flight room-join coroutines from the current start(); stop() cancels
    // them so a repeated start() can't leave orphan librelay processes (which
    // was inflating a 6-room profile to 9+ relays).
    private var spawnDeferreds: List<Deferred<RelayInstance?>> = emptyList()
    // Every RelayInstance the current start() created, tracked from the moment
    // of creation (before its librelay process spawns). `instances` only holds
    // grace-window joiners; a straggler or failed join still spawned a process,
    // and if it never entered `instances` the old stop() left it - plus its
    // respawning watchdog - running forever. Across trigger/reconnect cycles
    // these piled up (~30 orphan WebRTC stacks seen cooking the CPU with the VPN
    // already off). stop() kills everything here, so no process outlives a stop.
    private val allInstances = java.util.Collections.synchronizedList(mutableListOf<RelayInstance>())
    private var lb: SocksRoundRobinLb? = null
    private var stripeMux: StripeMux? = null

    /** First relay's process — used by the existing watchdog hook. */
    fun process(): Process? = instances.firstOrNull()?.process

    /**
     * Live relay count. The tunnel watchdog tears down only when this hits zero
     * (a single dead room self-recovers and is tolerated by the LB/mux). Returns
     * 1 on a concurrent-modification race so we never tear down spuriously.
     */
    fun aliveCount(): Int = try {
        instances.count { it.process?.isAlive == true }
    } catch (_: Exception) {
        1
    }

    /**
     * Start N relays (one per link parsed from [profile.address]) and bind the
     * SOCKS5 LB on [exposedSocksPort]. All relays share the same SOCKS auth so
     * the LB stays byte-transparent.
     *
     * Returns true once at least one relay reached TUNNEL_CONNECTED and the LB
     * is listening. Single-link profiles return true the moment that one
     * relay is up; multi-link profiles tolerate per-relay failures as long
     * as one succeeds.
     */
    suspend fun start(
        profile: ServerProfile,
        exposedSocksPort: Int,
        socksUser: String,
        socksPass: String,
        scope: CoroutineScope,
        timeoutMs: Long = 30_000,
        // Opt-in: fan out with the striping mux (one flow split across ALL
        // rooms) instead of round-robin LB. Requires stripe-server on the exit.
        useStriping: Boolean = false,
        stripePort: Int = 38500
    ): Boolean {
        stop()
        val links = profile.address.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (links.isEmpty()) {
            onLog("No Telemost links in profile address")
            return false
        }

        // Spawn relays in parallel (serial left slow rooms un-joined within the
        // timeout - only 3/6 came up). Each gets the FULL timeout; a per-index
        // stagger avoids hammering Yandex's signaling API in one burst.
        val perRelayTimeout = timeoutMs.coerceAtLeast(8_000L)
        // Create + register every instance synchronously BEFORE spawning, so even
        // a straggler or a join cancelled mid-spawn is in allInstances and stop()
        // will kill its process (orphan-relay / overheating fix).
        val pending = links.mapIndexed { i, link ->
            RelayInstance(
                idx = i,
                joinLink = link,
                nativeLibDir = nativeLibDir,
                socksPort = INTERNAL_SOCKS_BASE + i,
                signalingPort = SIGNALING_PORT_BASE + i,
                socksUser = socksUser,
                socksPass = socksPass,
                onLog = { line -> onLog("[#${i + 1}] $line") },
                onStatus = onStatus,
                onTunnelLost = onTunnelLost
            ).also { allInstances.add(it) }
        }
        val deferreds = pending.mapIndexed { i, inst ->
            scope.async(Dispatchers.IO) {
                delay(i * 250L) // gentle stagger, not serial
                val ok = try {
                    inst.start(scope, perRelayTimeout)
                } catch (e: Throwable) {
                    // Cancelled or failed mid-spawn: kill the process so it
                    // doesn't linger as an orphan. Rethrow to keep cancellation.
                    inst.stop()
                    throw e
                }
                if (ok) inst else { inst.stop(); null }
            }
        }
        spawnDeferreds = deferreds
        // Form the pool from whoever joined within a grace window; don't wait
        // for the slowest/dead room (that made connect take ~timeout seconds).
        val graceMs = minOf(timeoutMs, 12_000L)
        withTimeoutOrNull(graceMs) { deferreds.awaitAll() }
        instances.addAll(deferreds.mapNotNull { if (it.isCompleted) it.getCompleted() else null })
        // Reap any straggler that connects after the grace (not in the LB).
        deferreds.filter { !it.isCompleted }.forEach { d ->
            scope.launch { runCatching { d.await() }.getOrNull()?.stop() }
        }

        Log.i(TAG, "rooms joined within grace: ${instances.size}/${links.size} (these become striping upstreams)")
        if (instances.isEmpty()) {
            onLog("All ${links.size} Telemost relays failed to connect")
            return false
        }

        val upstreams = instances.map { InetSocketAddress("127.0.0.1", it.socksPort) }

        if (useStriping) {
            // Striping: one flow's bytes are split across all rooms and
            // reassembled by the exit's stripe-server (reached via a SOCKS5
            // CONNECT to 127.0.0.1:stripePort through each relay).
            val mux = StripeMux(exposedSocksPort, upstreams, "127.0.0.1", stripePort, onLog)
            if (!mux.start(scope)) {
                onLog("StripeMux failed to start on port $exposedSocksPort")
                stop()
                return false
            }
            this.stripeMux = mux
            onLog("Telemost striping up: ${instances.size}/${links.size} relays, mux on :$exposedSocksPort -> stripe-server :$stripePort")
            return true
        }

        val lb = SocksRoundRobinLb(exposedSocksPort, upstreams)
        if (!lb.start(scope)) {
            onLog("LB failed to bind on port $exposedSocksPort")
            stop()
            return false
        }
        this.lb = lb
        onLog("Telemost multi-channel up: ${instances.size}/${links.size} relays, LB on :$exposedSocksPort")
        return true
    }

    fun stop() {
        // Cancel in-flight joins FIRST so they don't keep spawning processes.
        spawnDeferreds.forEach { it.cancel() }
        spawnDeferreds = emptyList()
        try { lb?.stop() } catch (_: Exception) {}
        lb = null
        try { stripeMux?.stop() } catch (_: Exception) {}
        stripeMux = null
        // Kill EVERY spawned relay, not just the grace-window joiners in
        // `instances` - stragglers/failed joins also spawned a librelay process
        // and must not outlive the tunnel (orphan-relay / overheating fix).
        val all = synchronized(allInstances) { ArrayList(allInstances) }
        all.forEach { try { it.stop() } catch (_: Exception) {} }
        allInstances.clear()
        instances.clear()
    }

    /**
     * Owns a single librelay.so subprocess + its stdin/stdout protocol. Public
     * surface is intentionally small: start/stop and a process handle for the
     * watchdog.
     */
    private class RelayInstance(
        private val idx: Int,
        private val joinLink: String,
        private val nativeLibDir: String,
        val socksPort: Int,
        private val signalingPort: Int,
        private val socksUser: String,
        private val socksPass: String,
        private val onLog: (String) -> Unit,
        private val onStatus: (String) -> Unit,
        private val onTunnelLost: () -> Unit
    ) {
        @Volatile var process: Process? = null
        @Volatile private var stdinWriter: BufferedWriter? = null
        @Volatile private var tunnelConnected = false
        @Volatile private var sawReady = false
        @Volatile private var stopped = false
        @Volatile private var scopeRef: CoroutineScope? = null

        /** Spawns the librelay subprocess + stdout reader. Re-usable by the
         *  watchdog for respawn. Returns false if the process couldn't start. */
        private fun spawnProcess(scope: CoroutineScope): Boolean {
            // Don't spawn if stop() already fired - keeps the watchdog from
            // resurrecting a process as the tunnel is torn down (orphan fix).
            if (stopped) return false
            val bin = File(nativeLibDir, "librelay.so")
            if (!bin.exists()) {
                onLog("librelay.so not found at ${bin.absolutePath}")
                return false
            }
            // No SOCKS5 auth on upstreams: auth can fragment across pump reads
            // and fail; all sockets are 127.0.0.1 so it adds no security.
            val pb = ProcessBuilder(
                bin.absolutePath,
                "--mode", "telemost-headless-joiner",
                "--ws-port", signalingPort.toString(),
                "--socks-port", socksPort.toString()
            )
            // Valid-VP8 carrier: frames are real VP8 prefix + AEAD data, so the
            // SFU forwards at full bitrate (~2.5 Mbit/room). Server creators
            // MUST run carrier too; legacy <-> carrier is incompatible.
            pb.environment()["WLB_VALID_VP8_TUNNEL"] = "1"
            if (TELEMOST_MULTI_CLIENT) pb.environment()["WLB_CARRIER_MUX"] = "1"
            pb.redirectErrorStream(true)
            val proc = try {
                pb.start()
            } catch (e: Exception) {
                onLog("spawn failed: ${e.message}")
                return false
            }
            // stop() may have raced in during pb.start() - kill the fresh
            // process instead of tracking it, or it orphans.
            if (stopped) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
                return false
            }
            sawReady = false
            tunnelConnected = false
            process = proc
            stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))
            Log.i(TAG, "librelay.so #${idx + 1} started")
            scope.launch(Dispatchers.IO) {
                try {
                    proc.inputStream.bufferedReader().forEachLine { handleLine(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "#${idx + 1} stdout reader exited: ${e.message}")
                }
            }
            return true
        }

        suspend fun start(scope: CoroutineScope, timeoutMs: Long): Boolean {
            scopeRef = scope
            if (!spawnProcess(scope)) return false
            val ok = withTimeoutOrNull(timeoutMs) {
                while (!tunnelConnected) {
                    if (process?.isAlive != true) return@withTimeoutOrNull false
                    delay(150)
                }
                true
            } ?: false
            if (!ok) {
                onLog("did not reach TUNNEL_CONNECTED in ${timeoutMs}ms")
                stop()
                return false
            }
            launchWatchdog(scope)
            return true
        }

        /** Per-relay watchdog: respawns this relay if its process dies, so one
         *  dead room recovers on its own instead of dropping throughput. */
        private fun launchWatchdog(scope: CoroutineScope) {
            scope.launch(Dispatchers.IO) {
                while (!stopped) {
                    delay(3_000)
                    if (stopped) break
                    // Respawn ONLY on actual process death. NOT on mere
                    // TUNNEL_LOST: room churn self-heals, and rejoining a live
                    // relay mid-transfer tore down active connections.
                    if (process?.isAlive != true) {
                        if (stopped) break
                        onLog("relay #${idx + 1} process died — respawning")
                        try { stdinWriter?.close() } catch (_: Exception) {}
                        if (!spawnProcess(scope)) {
                            delay(5_000) // spawn failed — back off, retry next tick
                        }
                    }
                }
            }
        }

        private fun handleLine(line: String) {
            when {
                line.startsWith("STATUS:") -> {
                    val status = line.removePrefix("STATUS:")
                    onLog(status)
                    onStatus(status)
                    when {
                        status == "READY" -> {
                            if (!sawReady) { sawReady = true; sendJoin() }
                        }
                        status == "TUNNEL_CONNECTED" -> tunnelConnected = true
                        status == "TUNNEL_LOST" -> {
                            tunnelConnected = false
                            try { onTunnelLost() } catch (_: Exception) {}
                        }
                    }
                }
                line.startsWith("RESOLVE:") -> {
                    val host = line.removePrefix("RESOLVE:")
                    Thread {
                        val ip = try {
                            val addrs = InetAddress.getAllByName(host)
                            val a4 = addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull()
                            a4?.hostAddress.orEmpty()
                        } catch (_: Exception) { "" }
                        writeStdin(ip)
                    }.start()
                }
                else -> onLog(line)
            }
        }

        private fun sendJoin() {
            val name = pickDisplayName()
            Log.d(TAG, "#${idx + 1} joining as \"$name\"")
            val json = JSONObject().apply {
                put("joinLink", joinLink)
                put("displayName", name)
                put("tunnelMode", "video")
            }.toString()
            writeStdin("JOIN:$json")
        }

        private fun writeStdin(s: String) {
            val w = stdinWriter ?: return
            try {
                synchronized(w) {
                    w.write(s)
                    w.newLine()
                    w.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "#${idx + 1} stdin write failed: ${e.message}")
            }
        }

        fun stop() {
            stopped = true // tell the watchdog to stop respawning
            try { stdinWriter?.close() } catch (_: Exception) {}
            stdinWriter = null
            val p = process
            process = null
            tunnelConnected = false
            sawReady = false
            if (p != null) {
                // Graceful leave: SIGTERM lets librelay self-kick off the SFU
                // (no ghost left in the room) before SIGKILL. On a daemon thread
                // so stopping N relays stays non-blocking.
                try { p.destroy() } catch (_: Exception) {}
                Thread {
                    try {
                        if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) p.destroyForcibly()
                    } catch (_: Exception) {
                        try { p.destroyForcibly() } catch (_: Exception) {}
                    }
                }.apply { isDaemon = true }.start()
            }
        }
    }
}
