package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class PostalSnapshot(val version: Int, val url: String, val bytes: Long)

/** Pick only complete public snapshots. Some upstream updates are partial/error files. */
internal fun latestCompletePostalSnapshot(files: JsonArray): PostalSnapshot? {
    val format = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ROOT)
    val nameFormat = Regex("onemap_([0-9]{8})\\.csv")
    return files.mapNotNull { item ->
        if (!item.isJsonObject) return@mapNotNull null
        val json = item.asJsonObject
        val filename = json.get("name")?.asString.orEmpty()
        val match = nameFormat.matchEntire(filename) ?: return@mapNotNull null
        val date = runCatching { LocalDate.parse(match.groupValues[1], format) }.getOrNull()
            ?: return@mapNotNull null
        val size = json.get("size")?.asLong ?: return@mapNotNull null
        if (size < 8_000_000L || size > 35_000_000L) return@mapNotNull null
        val url = json.get("download_url")?.asString.orEmpty()
        if (!url.startsWith("https://raw.githubusercontent.com/dhanyyudi/onemap-building-sg/")) return@mapNotNull null
        PostalSnapshot(date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth, url, size)
    }.maxByOrNull { it.version }
}

/** No user account: check weekly for new public CSV, then atomically replace the local index. */
internal class PostalSnapshotUpdater(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val endpoint =
        "https://api.github.com/repos/dhanyyudi/onemap-building-sg/contents/data?ref=main"

    suspend fun checkForUpdate(): Int = withContext(Dispatchers.IO) {
        val index = OfflinePostalIndex(context)
        val current = index.version()
        val manifest = Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SG-Delivery-Postal-Refresh")
            .build()
        val candidate = client.newCall(manifest).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Postal manifest unavailable: ${response.code}")
            val json = JsonParser.parseString(response.body?.string() ?: "").asJsonArray
            latestCompletePostalSnapshot(json)
        } ?: return@withContext current
        if (candidate.version <= current) return@withContext current

        val temp = File(context.cacheDir, "sg_postals_new.csv")
        try {
            val request = Request.Builder().url(candidate.url)
                .header("User-Agent", "SG-Delivery-Postal-Refresh")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Postal download failed: ${response.code}")
                val stream = response.body?.byteStream() ?: throw IllegalStateException("Empty postal download")
                stream.use { source ->
                    temp.outputStream().use { destination ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read == -1) break
                            downloaded += read
                            if (downloaded > 35_000_000L) throw IllegalStateException("Postal download too large")
                            destination.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (temp.length() < 8_000_000L) throw IllegalStateException("Postal source incomplete")
            index.installCsv(temp, candidate.version)
            index.version()
        } finally {
            temp.delete()
        }
    }
}

class PostalDatasetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val version = PostalSnapshotUpdater(applicationContext).checkForUpdate()
            applicationContext.getSharedPreferences("sg_postal_updates", Context.MODE_PRIVATE)
                .edit().putInt("dataset_version", version)
                .putLong("last_checked", System.currentTimeMillis())
                .apply()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the offline seed and cached routes; retry on the next weekly check.
            Result.success()
        }
    }
}

internal object PostalDatasetScheduler {
    private const val JOB_NAME = "sg_delivery_public_postal_dataset_weekly"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val job = PeriodicWorkRequestBuilder<PostalDatasetRefreshWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(JOB_NAME, ExistingPeriodicWorkPolicy.KEEP, job)
    }
}
