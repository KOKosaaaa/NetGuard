package com.smarttools.netguard.service

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.DataInputStream
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Debug APK only. End-to-end native TUN provenance test; no external server. */
class RouteProbeVpnService : VpnService() {
    private var fd: ParcelFileDescriptor? = null
    private var proxy: AdaptiveSiteProxy? = null
    private val pool = Executors.newCachedThreadPool()
    private val listeners = mutableListOf<ServerSocket>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    companion object {
        val ready = LinkedBlockingQueue<String>()
        val owners = LinkedBlockingQueue<String>()
        val sourceTuples = LinkedBlockingQueue<String>()
        val vpnRequests = AtomicInteger()
        val normalRequests = AtomicInteger()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pool.execute {
            try {
                val normal = backend(false)
                val vpn = backend(true)
                val cm = getSystemService(ConnectivityManager::class.java)
                val policy = AppRoutePolicy(setOf(android.os.Process.myUid()), true) { flow ->
                    val uid = try { cm.getConnectionOwnerUid(flow.protocol, flow.local, flow.remote) }
                        catch (e: Exception) { owners.offer("lookup_error:${e.javaClass.simpleName}:$flow"); throw e }
                    owners.offer("${flow.protocol}|${flow.local.port}|${flow.remote.port}|$uid")
                    uid
                }
                proxy = AdaptiveSiteProxy(SiteRouteTransport(normal,vpn,{ protect(it) }), { "instrumentation" },
                    forceVpn = { flow -> if (flow == null) owners.offer("missing_metadata"); policy.forceVpn(flow) }, automatic = false,
                    observeSource = { sourceTuples.offer(it ?: "no_source") })
                val endpoint = proxy!!.endpoint
                fd = Builder().addAddress("10.10.10.1",30).addAddress("fd00::1",126)
                    .addRoute("0.0.0.0",0).addRoute("::",0).setMtu(1280)
                    .addAllowedApplication(packageName).establish()
                check(fd != null)
                val cfg = java.io.File(filesDir,"route-probe.yml")
                cfg.writeText("misc:\n  log-level: warn\n  task-stack-size: 86016\ntunnel:\n  mtu: 1280\nsocks5:\n  address: '127.0.0.1'\n  port: ${endpoint.port}\n  udp: 'udp'\n  username: '${endpoint.user}'\n  password: '${endpoint.password}'\n")
                check(hev.sockstun.TProxyService.TProxyStartService(cfg.path, fd!!.fd))
                ready.offer("ready")
            } catch (e: Throwable) { ready.offer(e.stackTraceToString()) }
        }
        return START_NOT_STICKY
    }
    private fun backend(vpn: Boolean): LocalSocks {
        val listener = ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));listeners.add(listener)
        pool.execute {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                pool.execute {
                    try { socket.use { s ->
                        s.soTimeout = 10000
                        val input = DataInputStream(s.getInputStream()); val output = s.getOutputStream()
                        check(input.readUnsignedByte()==5)
                        input.skipBytes(input.readUnsignedByte());output.write(byteArrayOf(5,0))
                        check(input.readUnsignedByte()==5);val command=input.readUnsignedByte();input.readUnsignedByte();SocksWire.address(input)
                        if(vpn)vpnRequests.incrementAndGet() else normalRequests.incrementAndGet()
                        if(command==3) DatagramSocket(0,InetAddress.getByName("127.0.0.1")).use { udp ->
                            udp.soTimeout=5000;output.write(SocksWire.reply(udp.localPort))
                            val packet=DatagramPacket(ByteArray(4096),4096);udp.receive(packet);udp.send(packet)
                            input.read()
                        } else {
                            output.write(SocksWire.reply());val data=ByteArray(128);val n=input.read(data)
                            if(n>0)output.write(data,0,n)
                            Unit
                        }
                    } } catch (_: Exception) {} finally { sockets.remove(socket) }
                }
            }
        }
        return LocalSocks(listener.localPort,"","")
    }
    override fun onDestroy() {
        runCatching { hev.sockstun.TProxyService.TProxyStopService() }
        proxy?.close();fd?.close();listeners.forEach { it.close() };sockets.forEach { it.close() };pool.shutdownNow()
        super.onDestroy()
    }
}
