package com.smarttools.netguard.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import java.net.InetSocketAddress

/** Round-robin selection per TCP connection; forwarding uses one NIO event loop. */
class SocksRoundRobinLb(private val listenPort: Int, private val upstreams: List<InetSocketAddress>) {
    private var forwarder: NioSocksForwarder? = null
    private var cancellation: DisposableHandle? = null

    fun start(scope: CoroutineScope): Boolean {
        stop()
        return try {
            NioSocksForwarder(listenPort, upstreams).also { forwarder = it; it.start() }
            cancellation = scope.coroutineContext[Job]?.invokeOnCompletion { stop() }
            true
        } catch (e: Exception) {
            stop()
            Log.w("SocksLB", "Forwarder start failed: ${e.javaClass.simpleName}")
            false
        }
    }

    fun stop() {
        cancellation?.dispose(); cancellation = null
        forwarder?.stop(); forwarder = null
    }
}
