package com.smarttools.netguard.service

import android.app.Service
import android.content.Intent
import android.os.*
import libXray.LibXray
import java.util.concurrent.Executors

/** A separate process hosts temporary Xray; it cannot stop the active :xray core. */
class CandidateProbeService : Service() {
    companion object {
        internal const val START = 1
        internal const val STOP = 2
        private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "candidate-core").apply { isDaemon = true } }
        private var owner: CandidateProbeService? = null // accessed only on executor
        private var request = -1L
    }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val id = message.data.getLong("id")
        val reply = message.replyTo
        when (message.what) {
            START -> {
                val config = message.data.getString("config") ?: return@Handler true
                val directory = message.data.getString("directory") ?: return@Handler true
                @Suppress("DEPRECATION")
                val network = message.data.getParcelable<android.net.Network>("network") ?: return@Handler true
                executor.execute {
                    owner = this; request = id
                    val ok = try {
                        if (LibXray.getXrayState()) LibXray.stopXray()
                        check(getSystemService(android.net.ConnectivityManager::class.java).bindProcessToNetwork(network))
                        LibXray.runXrayFromJSON(LibXray.newXrayRunFromJSONRequest(directory, config))
                        LibXray.getXrayState()
                    } catch (_: Throwable) { false }
                    respond(reply, id, START, ok)
                }
            }
            STOP -> executor.execute {
                if (owner === this && request == id) stopCore()
                respond(reply, id, STOP, true)
            }
        }
        true
    })
    private fun respond(reply: Messenger?, id: Long, command: Int, ok: Boolean) {
        runCatching { reply?.send(Message.obtain(null, command).apply {
            data = Bundle().apply { putLong("id", id); putBoolean("ok", ok) }
        }) }
    }
    private fun stopCore() {
        runCatching { if (LibXray.getXrayState()) LibXray.stopXray() }
        getSystemService(android.net.ConnectivityManager::class.java).bindProcessToNetwork(null)
        owner = null; request = -1
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() {
        executor.execute { if (owner === this) stopCore() }
        super.onDestroy()
    }
}
