package com.smarttools.netguard.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import libXray.LibXray

/**
 * Runs xray-core in-process via the libXray JNI bindings (gomobile AAR),
 * isolated in its own `:xray` OS process (see android:process in the manifest).
 *
 * WHY this exists (the phantom-process bug):
 * The previous design forked `libxray.so` as a raw child process through
 * ProcessBuilder. Android 12+'s phantom-process monitor kills native children
 * of app processes once they burn CPU or exceed the phantom cap — so xray got
 * killed mid-session and the tunnel entered a crash/restart loop. Running xray
 * as JNI threads inside a *managed* app process (this service) makes it invisible
 * to the phantom killer: it is no longer a forked process, it is the app process.
 * The `:xray` split keeps an xray panic from taking down the VpnService.
 *
 * Socket protection is intentionally NOT wired (no registerDialerController):
 * TunnelVpnService already calls VpnService.Builder.addDisallowedApplication for
 * our own package in every routing mode, so every socket opened under our UID —
 * including xray's outbounds in this process — bypasses the tun by routing rule.
 * That removes the need for a cross-process protect() callback.
 *
 * Control is via startService actions; readiness/liveness is observed by the
 * caller polling xray's local SOCKS port (waitForPort), so no Messenger/AIDL is
 * needed. TunnelVpnService also binds with BIND_IMPORTANT purely to pin this
 * process's priority to the foreground VpnService while the tunnel is up.
 */
class XrayService : Service() {

    companion object {
        private const val TAG = "XrayService"
        const val ACTION_START = "com.smarttools.netguard.xray.START"
        const val ACTION_STOP = "com.smarttools.netguard.xray.STOP"
        const val EXTRA_DAT_DIR = "datDir"
        const val EXTRA_CONFIG_JSON = "configJson"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val datDir = intent.getStringExtra(EXTRA_DAT_DIR)
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
                if (datDir.isNullOrEmpty() || configJson.isNullOrEmpty()) {
                    Log.e(TAG, "START missing datDir/configJson; ignoring")
                } else {
                    startXray(datDir, configJson)
                }
            }
            ACTION_STOP -> {
                stopXray()
                stopSelf()
            }
            else -> Log.w(TAG, "Unknown action ${intent?.action}")
        }
        // Do not auto-restart with a null intent on process death: TunnelVpnService
        // owns the lifecycle and re-issues START on its own recovery path.
        return START_NOT_STICKY
    }

    private fun startXray(datDir: String, configJson: String) {
        try {
            if (LibXray.getXrayState()) {
                // A stale instance from a previous start — stop before re-running,
                // libXray keeps a single global instance.
                LibXray.stopXray()
            }
            Log.i(TAG, "Starting xray (libXray ${safeVersion()}) in :xray process")
            val req = LibXray.newXrayRunFromJSONRequest(datDir, configJson)
            val resp = LibXray.runXrayFromJSON(req)
            // resp is a base64-encoded CallResponse{success,err}; log only whether
            // it reported success to avoid leaking config. getXrayState() + the
            // caller's SOCKS port poll are the real liveness signals.
            Log.i(TAG, "runXray issued, state=${LibXray.getXrayState()} (resp len=${resp.length})")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start xray: ${e.message}", e)
        }
    }

    private fun stopXray() {
        try {
            if (LibXray.getXrayState()) {
                LibXray.stopXray()
                Log.i(TAG, "xray stopped")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping xray: ${e.message}", e)
        }
    }

    private fun safeVersion(): String = try { LibXray.xrayVersion() } catch (_: Throwable) { "?" }

    override fun onDestroy() {
        stopXray()
        super.onDestroy()
    }
}
