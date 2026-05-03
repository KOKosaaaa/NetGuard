package com.smarttools.netguard.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smarttools.netguard.database.AppDatabase
import com.smarttools.netguard.repository.SubscriptionRepository

class SubscriptionUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SubUpdateWorker"
        const val WORK_NAME = "subscription_auto_update"
    }

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val repo = SubscriptionRepository(db.subscriptionDao(), db.profileDao(), applicationContext)
            val subs = repo.getAll()
            var successCount = 0
            var errorCount = 0

            for (sub in subs) {
                if (isStopped) {
                    Log.i(TAG, "Worker stopped, will resume remaining subs next run")
                    return Result.retry()
                }
                if (!sub.enabled) continue
                try {
                    val result = repo.updateSubscription(sub)
                    if (result.isSuccess) successCount += result.getOrDefault(0)
                    else errorCount++
                } catch (e: Exception) {
                    errorCount++
                    Log.w(TAG, "Failed to update ${sub.name}: ${e.message}")
                }
            }

            Log.i(TAG, "Auto-update: $successCount profiles updated, $errorCount errors")
            if (errorCount > 0 && successCount == 0) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto-update failed", e)
            Result.retry()
        }
    }
}
