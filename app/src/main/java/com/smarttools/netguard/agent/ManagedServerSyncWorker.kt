package com.smarttools.netguard.agent

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smarttools.netguard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * Polls every managed server's /v1/status on a periodic schedule and
 * refreshes the cached telemetry shown on the server list cell.
 *
 * Failures are intentionally tolerant: a single server being unreachable
 * (offline VPS, expired bearer, transient TLS handshake) does not affect
 * the others. We log but never crash — UI will surface "last seen N ago"
 * via the cached lastSeenAt vs current time.
 *
 * Bearer auto-rotation: any X-Bearer-Rotate-Available header would be
 * an HTTP header rather than a body field. OkHttp gives us the response
 * headers via interceptors, but for the periodic sync path we just call
 * rotate() explicitly if expiry is < RotateWindow away. Simpler, no
 * interceptor wiring needed for v1.
 */
class ManagedServerSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = ManagedServerRepository.get(applicationContext)
        val servers = repo.getAll()
        if (servers.isEmpty()) return@withContext Result.success()

        var ok = 0
        var failed = 0
        for (s in servers) {
            try {
                val client = AgentApiClient(s, BuildConfig.VERSION_NAME)
                val status = client.status()
                repo.applyTelemetry(s.id, status)

                // Rotate the bearer if we're inside the rotation window —
                // the agent will return rotated=true with a fresh token,
                // otherwise rotated=false and we keep the existing one.
                val expiryMs = s.bearerExpiresAt
                val needsRotate = expiryMs > 0 &&
                    (expiryMs - System.currentTimeMillis()) < ROTATE_WINDOW_MS
                if (needsRotate) {
                    try {
                        val rot = client.rotate()
                        if (rot.rotated && rot.bearer != null && rot.expiresAt != null) {
                            val newExpiry = parseIso(rot.expiresAt)
                            repo.applyRotation(s.id, rot.bearer, newExpiry)
                            Log.i(TAG, "rotated bearer for ${s.host}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "rotate ${s.host} failed: ${e.message}")
                    }
                }
                ok++
            } catch (e: AgentApiError) {
                Log.w(TAG, "${s.host}: ${e.code} ${e.errorMessage}")
                failed++
            } catch (e: Exception) {
                Log.w(TAG, "${s.host}: ${e.javaClass.simpleName} ${e.message}")
                failed++
            }
        }
        Log.i(TAG, "sync done: ok=$ok failed=$failed total=${servers.size}")
        Result.success()
    }

    private fun parseIso(s: String): Long = try {
        Instant.parse(s).toEpochMilli()
    } catch (_: DateTimeParseException) {
        0L
    }

    companion object {
        private const val TAG = "MgdSrvSync"
        private const val UNIQUE_NAME = "managed-server-sync"
        private const val ROTATE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

        /**
         * Schedule periodic sync. KEEP policy so re-enrolling doesn't
         * reset the interval timer on every app start. Interval is 30
         * minutes — frequent enough that "last seen" stays useful but
         * not so chatty that we drain a phone's battery on weak signal.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<ManagedServerSyncWorker>(
                30, TimeUnit.MINUTES,
                // Flex window: WorkManager can fire any time in the last 10
                // minutes of the period. Lets the scheduler batch with
                // other system work to save battery.
                10, TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
