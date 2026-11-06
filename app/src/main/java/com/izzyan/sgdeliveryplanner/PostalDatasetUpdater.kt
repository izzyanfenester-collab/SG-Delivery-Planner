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
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

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
    private val publishedBase =
        "https://raw.githubusercontent.com/izzyanfenester-collab/SG-Delivery-Planner/postal-data-live/postal/"

    /** Missing manifest means the B809 public snapshot remains our fallback. */
    private suspend fun checkThreeSource(index: OfflinePostalIndex, current: Int): Int? {
        val manifestRequest = Request.Builder().url(publishedBase + "manifest.json")
            .header("User-Agent", "SG-Delivery-Postal-Refresh").build()
        val manifest = client.newCall(manifestRequest).execute().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw IllegalStateException("Multi-source manifest unavailable")
            JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
        } ?: return null
        val version = manifest.get("version")?.asInt ?: error("No postal version")
        val expectedSha = manifest.get("sha256")?.asString.orEmpty()
        val count = manifest.get("postal_count")?.asInt ?: 0
        val providers = manifest.get("sources")?.asJsonObject?.size() ?: 0
        if (manifest.get("schema")?.asInt != 1 ||
            manifest.get("archive")?.asString != "singapore-postals.csv.gz" ||
            count < 100000 || providers < 3 ||
            !expectedSha.matches(Regex("[a-f0-9]{64}")) || version !in 20260215..20991231) {
            throw IllegalStateException("Incomplete multi-source database manifest")
        }
        if (version <= current) return current

        val zipped = File(context.cacheDir, "sg_postal_verified.csv.gz")
        val plain = File(context.cacheDir, "sg_postal_verified.csv")
        try {
            val download = Request.Builder().url(publishedBase + "singapore-postals.csv.gz")
                .header("User-Agent", "SG-Delivery-Postal-Refresh").build()
            val hash = MessageDigest.getInstance("SHA-256")
            client.newCall(download).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Multi-source database unavailable")
                val input = response.body?.byteStream() ?: throw IllegalStateException("Empty postal response")
                input.use { source -> zipped.outputStream().use { destination ->
                    val buf = ByteArray(32768)
                    var total = 0L
                    while (true) {
                        val bytes = source.read(buf)
                        if (bytes == -1) break
                        total += bytes
                        if (total > 35_000_000L) throw IllegalStateException("Compressed postal data too large")
                        hash.update(buf, 0, bytes)
                        destination.write(buf, 0, bytes)
                    }
                } }
            }
            val actualSha = hash.digest().joinToString("") { "%02x".format(it) }
            if (actualSha != expectedSha) throw IllegalStateException("Postal database checksum mismatch")
            GZIPInputStream(zipped.inputStream()).use { input -> plain.outputStream().use { output ->
                val buf = ByteArray(32768)
                var total = 0L
                while (true) {
                    val bytes = input.read(buf)
                    if (bytes == -1) break
                    total += bytes
                    if (total > 45_000_000L) throw IllegalStateException("Postal data too large")
                    output.write(buf, 0, bytes)
                }
            } }
            index.installCsv(plain, version)
            val installed = index.count()
            if (installed < 100000) throw IllegalStateException("Postal database incomplete")
            context.getSharedPreferences("sg_postal_updates", Context.MODE_PRIVATE).edit()
                .putInt("source_count", 3).putInt("postal_count", installed)
                .putLong("last_updated", System.currentTimeMillis())
                .apply()
            return index.version()
        } finally {
            zipped.delete()
            plain.delete()
        }
    }

    suspend fun checkForUpdate(): Int = withContext(Dispatchers.IO) {
        val index = OfflinePostalIndex(context)
        val current = index.version()
        checkThreeSource(index, current)?.let { return@withContext it }
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

    /** Called by background scheduling and the Settings manual refresh button. */
    suspend fun checkAndRecord(): PostalUpdateState = withContext(Dispatchers.IO) {
        val before = OfflinePostalIndex(context).count()
        val pref = context.getSharedPreferences("sg_postal_updates", Context.MODE_PRIVATE)
        val wasVersion = OfflinePostalIndex(context).version()
        try {
            val version = checkForUpdate()
            val after = OfflinePostalIndex(context).count()
            val installed = version > wasVersion
            val now = System.currentTimeMillis()
            pref.edit().putInt("dataset_version", version).putInt("postal_count", after)
                .putInt("new_postals", if (installed) (after - before).coerceAtLeast(0) else 0)
                .putLong("last_checked", now)
                .putLong("last_updated", if (installed) now else pref.getLong("last_updated", 0))
                .putString("last_status", if (installed) "Updated" else if (pref.getInt("source_count", 1) == 3) "Up to date" else "Waiting for sources")
                .putString("last_detail", if (pref.getInt("source_count", 1) == 3)
                    "Three-source database verified." else "Offline database kept; waiting for three-source release.")
                .apply()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pref.edit().putLong("last_checked", System.currentTimeMillis())
                .putString("last_status", "Update failed")
                .putString("last_detail", "Connection or update unavailable; existing database kept.").apply()
        }
        PostalUpdateState.load(context, OfflinePostalIndex(context))
    }
}

internal data class PostalUpdateState(
    val version: Int, val total: Int, val sources: Int, val newCodes: Int,
    val lastChecked: Long, val lastUpdated: Long, val status: String, val detail: String
) {
    companion object {
        suspend fun load(context: Context, index: OfflinePostalIndex = OfflinePostalIndex(context)): PostalUpdateState {
            val p = context.getSharedPreferences("sg_postal_updates", Context.MODE_PRIVATE)
            return PostalUpdateState(index.version(), index.count(), p.getInt("source_count", 1),
                p.getInt("new_postals", 0), p.getLong("last_checked", 0),
                p.getLong("last_updated", 0), p.getString("last_status", "Ready offline").orEmpty(),
                p.getString("last_detail", "Weekly check has not run yet.").orEmpty())
        }
    }
}

class PostalDatasetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            PostalSnapshotUpdater(applicationContext).checkAndRecord()
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
