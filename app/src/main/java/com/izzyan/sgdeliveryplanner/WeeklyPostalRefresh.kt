package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

internal data class WeeklyRefreshResult(val checked: Int, val updated: Int, val failed: Int)

/** Recheck only previously used postals that are at least a week old.
 * Failures never replace or delete cached locations, route history, or payments.
 */
internal suspend fun refreshKnownPostalCodes(
    dao: PlannerDao,
    lookup: OneMapLookup,
    cutoffMillis: Long,
    limit: Int = 50,
    pause: suspend (Long) -> Unit = { delay(it) }
): WeeklyRefreshResult {
    val candidates = dao.stalePlaces(cutoffMillis, limit)
    var checked = 0
    var updated = 0
    var failed = 0
    for (candidate in candidates) {
        // The original OneMap public endpoint may rate-limit, so be polite.
        if (checked > 0) pause(1_200L)
        checked++
        try {
            lookup.resolve(candidate.postal, forceRefresh = true)
            updated++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (blocked: OneMapAuthenticationError) {
            failed++
            break // No token: do not repeatedly request all saved postals.
        } catch (throttled: OneMapRateLimitError) {
            failed++
            break
        } catch (denied: HttpException) {
            failed++
            if (denied.code() == 401 || denied.code() == 403) break
        } catch (_: Exception) {
            failed++ // Keep the existing cached place untouched.
        }
    }
    return WeeklyRefreshResult(checked, updated, failed)
}

class WeeklyPostalRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val dao = Repository(applicationContext).dao
            val report = refreshKnownPostalCodes(
                dao = dao,
                lookup = OneMapLookup(dao, createOneMapApi()),
                cutoffMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
            )
            applicationContext.getSharedPreferences("weekly_postal_refresh", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_check", System.currentTimeMillis())
                .putInt("last_updated", report.updated)
                .putInt("last_failed", report.failed)
                .apply()
            // Respect weekly cadence; repeated immediate retries can trigger rate limits.
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.success() // Preserve current app use and retry at the next interval.
        }
    }
}

internal object WeeklyPostalScheduler {
    private const val JOB_NAME = "sg_delivery_weekly_postal_refresh"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WeeklyPostalRefreshWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(JOB_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
