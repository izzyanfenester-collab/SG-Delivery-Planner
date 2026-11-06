package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

data class PostalRefreshResult(val checked: Int, val updated: Int, val failed: Int)

internal class PostalUpdatePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("postal_updates", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    val lastSuccessfulCheck: Long get() = prefs.getLong("last_success", 0L)

    fun recordSuccess() { prefs.edit().putLong("last_success", System.currentTimeMillis()).apply() }
}

/** Only previously used, stale postal codes are refreshed; no full-Singapore scraping. */
class PostalRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = PostalUpdatePreferences(applicationContext)
        if (!prefs.enabled || !OneMapCredentialStore(applicationContext).configured()) return Result.success()
        return try {
            val report = Repository(applicationContext).refreshPostalCache(force = false, limit = 12)
            if (report.failed > 0) Result.retry()
            else {
                prefs.recordSuccess()
                Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

internal object PostalUpdateScheduler {
    private const val WORK_NAME = "sg_delivery_postal_refresh"

    fun configure(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!PostalUpdatePreferences(context).enabled) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<PostalRefreshWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints).build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
