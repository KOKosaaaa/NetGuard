package com.smarttools.netguard.service

import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import com.smarttools.netguard.model.AppSettings
import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.util.RandomPort
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal object ServerQualitySelector {
    private val mutex = Mutex()
    private val ids = AtomicLong()
    private val rotations = AtomicLong()
    fun targets(settings: AppSettings): Set<HealthTarget> = HealthTarget.entries.filter { it.name in settings.healthCheckServices }
        .toSet().ifEmpty { setOf(HealthTarget.TELEGRAM, HealthTarget.YOUTUBE, HealthTarget.DISCORD) }
    fun networkKey(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = underlying(context) ?: return null
        // Link changes on the same Network handle also invalidate observations.
        return "$network:${cm.getLinkProperties(network)?.let { "${it.interfaceName}:${it.dnsServers}:${it.linkAddresses}" }}"
    }
    private fun underlying(context: Context): android.net.Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun usable(network: android.net.Network) = cm.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
        cm.activeNetwork?.takeIf { usable(it) }?.let { return it }
        return cm.allNetworks.filter { usable(it) }.maxByOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@maxByOrNull Int.MIN_VALUE
            (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1000 else 0) +
                (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 300 else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 200 else 100)
        }
    }
    suspend fun measure(endpoint: LocalSocks, targets: Set<HealthTarget>, timeoutMs: Long = 10_000): ServerQuality = coroutineScope {
        val probe = SocksServiceProbe(endpoint.port, endpoint.user, endpoint.password)
        val answers = java.util.concurrent.ConcurrentHashMap<HealthTarget, ServiceAnswer>()
        val pending = targets.map { target -> async(Dispatchers.IO) {
            val start = System.nanoTime()
            val answer = ServiceAnswer(probe.check(target), ((System.nanoTime() - start) / 1_000_000).toInt())
            answers[target] = answer
            target to answer
        } }
        try {
            withTimeoutOrNull(timeoutMs) { pending.awaitAll() }
            currentCoroutineContext().ensureActive()
            // A deadline is not evidence that an unfinished destination failed.
            ServerQuality(answers.toMap())
        }
        finally { probe.close(); pending.forEach { it.cancel() } }
    }
    suspend fun best(context: Context, profiles: List<ServerProfile>, settings: AppSettings): ServerProfile? = withContext(Dispatchers.IO) {
        val began = System.nanoTime()
        val initialNetwork = networkKey(context) ?: return@withContext null
        val selected = targets(settings)
        val cachedWinner = profiles.mapNotNull { profile ->
            ServerQualityCache.get(profile, initialNetwork, selected, settings)?.let { profile to it }
        }.minWithOrNull { a, b -> ServerQuality.bestFirst.compare(a.second, b.second) }
        if (cachedWinner != null && cachedWinner.second.available >= minOf(2, selected.size) &&
            networkKey(context) == initialNetwork) return@withContext cachedWinner.first
        // A concurrent failover must not leave the Home button waiting indefinitely.
        val owner = Any()
        try {
        if (withTimeoutOrNull(1000) { mutex.lock(owner); true } != true) return@withContext null
        val network = networkKey(context) ?: return@withContext null
        // The selected endpoint is a useful first candidate, not proof of quality.
        // Rotate the rest so repeated failures do not retry the same first batch.
        val ordered = profiles.sortedWith(compareByDescending<ServerProfile> { it.isSelected }.thenBy { it.sortOrder })
        val ordinary = ordered.filterNot { it.isSelected || it.protocol.usesRelay }
        val offset = if (ordinary.isEmpty()) 0 else (rotations.getAndIncrement() % ordinary.size).toInt()
        val rotated = ordered.filter { it.isSelected || it.protocol.usesRelay } + ordinary.drop(offset) + ordinary.take(offset)
        val winner = FastServerSelectionSearch.choose(rotated, selected.size,
            cached = { ServerQualityCache.get(it, network, selected, settings) },
            probe = { profile ->
                if (networkKey(context) != network) throw NetworkChanged()
                val quality = try { probe(context, profile, settings, selected) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { ServerQuality(emptyMap()) }
                if (networkKey(context) != network) throw NetworkChanged()
                // Do not cache a truncated all-failure sample as a dead server.
                if (quality.responding > 0 || quality.answers.size == selected.size)
                    ServerQualityCache.put(profile, network, selected, quality, settings)
                quality
            }, totalMs = (12_000 - (System.nanoTime() - began) / 1_000_000).coerceAtLeast(0))
        if (networkKey(context) != network) return@withContext null
        if (winner != null) {
            val quality = ServerQualityCache.get(winner, network, selected, settings) ?: return@withContext null
            LogBuffer.add(LogBuffer.LogLevel.INFO, "[server-quality] выбран ${winner.name}: доступно=${quality.available}/${selected.size}, ответило=${quality.responding}/${selected.size}, HTTP/MTProto=${quality.responseMs} ms, TCP=${quality.pingMs} ms")
            return@withContext winner
        }
        // Conference relays cannot be joined speculatively: a new participant
        // can replace the existing carrier. Try an unmeasured room, never call
        // a 23ms TCP connection to stream.wb.ru proof of a working VPN.
        profiles.firstOrNull { it.protocol.usesRelay && ServerQualityCache.get(it, network, selected, settings) == null }
        } catch (_: NetworkChanged) { null }
        finally { if (mutex.holdsLock(owner)) mutex.unlock(owner) }
    }
    private class NetworkChanged : java.io.IOException()
    private suspend fun probe(context: Context, profile: ServerProfile, settings: AppSettings, targets: Set<HealthTarget>): ServerQuality {
        val id = ids.incrementAndGet()
        val physical = underlying(context) ?: return ServerQuality(emptyMap())
        val endpoint = LocalSocks(RandomPort.getAvailable(), UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val config = CandidateProbeConfig.generate(profile, settings, endpoint)
        val directory = java.io.File(context.filesDir, "candidate-probe").apply { mkdirs() }
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val file = java.io.File(directory, name)
            if (!file.exists() || file.length() == 0L) context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val bound = CompletableDeferred<Messenger>()
        val started = CompletableDeferred<Boolean>()
        val stopped = CompletableDeferred<Boolean>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.data.getLong("id") == id) {
                if (message.what == CandidateProbeService.START) started.complete(message.data.getBoolean("ok"))
                if (message.what == CandidateProbeService.STOP) stopped.complete(true)
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { if (binder != null) bound.complete(Messenger(binder)) }
            override fun onServiceDisconnected(name: ComponentName?) { started.complete(false); stopped.complete(false) }
            override fun onNullBinding(name: ComponentName?) { bound.completeExceptionally(java.io.IOException("Probe service unavailable")) }
            override fun onBindingDied(name: ComponentName?) { started.complete(false); stopped.complete(false) }
        }
        var registered = false
        var remote: Messenger? = null
        try {
            registered = context.bindService(Intent(context, CandidateProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!registered) return ServerQuality(emptyMap())
            remote = withTimeout(1500) { bound.await() }
            remote.send(Message.obtain(null, CandidateProbeService.START).apply {
                replyTo = reply; data = Bundle().apply { putLong("id", id); putString("config", config); putString("directory", directory.absolutePath); putParcelable("network", physical) }
            })
            if (!withTimeout(2000) { started.await() }) return ServerQuality(emptyMap())
            // Service RTT already includes transport round trips. Do not add an
            // unbounded local DNS lookup just to break a tie between working exits.
            return measure(endpoint, targets, 3000)
        } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); return ServerQuality(emptyMap()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return ServerQuality(emptyMap()) }
        finally {
            withContext(NonCancellable) {
                runCatching { remote?.send(Message.obtain(null, CandidateProbeService.STOP).apply {
                    replyTo = reply; data = Bundle().apply { putLong("id", id) }
                }) }
                if (remote != null) withTimeoutOrNull(750) { stopped.await() }
                if (registered) runCatching { context.unbindService(connection) }
            }
        }
    }
}
