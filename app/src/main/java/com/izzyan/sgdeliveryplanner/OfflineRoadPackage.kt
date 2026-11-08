package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal data class OfflineRoadStatus(
    val ready: Boolean,
    val version: String,
    val nodeCount: Int,
    val archiveBytes: Long,
    val lastUpdated: Long,
    val detail: String
)

internal data class OfflineRoadManifest(
    val version: String, val url: String, val sha256: String, val sizeBytes: Long
)

/** The manifest refers only to an explicitly published, ODbL-attributed GitHub Release. */
internal const val OFFLINE_ROAD_MANIFEST_URL =
    "https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/download/offline-sg-current/manifest.json"

internal fun parseOfflineRoadManifest(json: String): OfflineRoadManifest {
    val root = JsonParser.parseString(json).asJsonObject
    require(root["schema"]?.asInt == 1 && root["country"]?.asString == "SG")
    val version = root["version"]?.asString.orEmpty()
    val url = root["package_url"]?.asString.orEmpty()
    val sha = root["sha256"]?.asString.orEmpty()
    val bytes = root["size_bytes"]?.asLong ?: 0L
    require(version.length in 8..70 && sha.matches(Regex("[a-f0-9]{64}")))
    require(url.startsWith("https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/download/"))
    require(bytes in 1024..180_000_000L)
    return OfflineRoadManifest(version, url, sha, bytes)
}

internal fun hasUsableInternet(context: Context): Boolean {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivity.activeNetwork ?: return false
    return connectivity.getNetworkCapabilities(network)?.hasCapability(
        NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}

/**
 * Manual ZIP import and SHA-verified downloaded packages.
 * Atomic last-good swap: never replace a working graph with an invalid download.
 */
internal class OfflineRoadPackageStore(private val context: Context) {
    private val folder = File(context.filesDir, "offline_sg_roads").apply { if (!exists()) mkdirs() }
    private val active = File(folder, "sg-road-graph.zip")
    private val old = File(folder, "sg-road-graph.previous.zip")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("offline_sg_roads", Context.MODE_PRIVATE)

    companion object {
        private val lock = Mutex()
        @Volatile private var cached: Pair<String, OfflineRoadGraph>? = null
    }

    private fun fingerprint(): String = "${active.absolutePath}:${active.length()}:${active.lastModified()}"

    suspend fun loadOrNull(): OfflineRoadGraph? = withContext(Dispatchers.IO) {
        if (!active.isFile) return@withContext null
        cached?.takeIf { it.first == fingerprint() }?.let { return@withContext it.second }
        lock.withLock {
            cached?.takeIf { it.first == fingerprint() }?.second
                ?: runCatching { OfflineRoadGraph.readZip(active) }.getOrNull()?.also {
                    cached = fingerprint() to it
                }
        }
    }

    suspend fun status(): OfflineRoadStatus = withContext(Dispatchers.IO) {
        val graph = loadOrNull()
        OfflineRoadStatus(
            graph != null,
            prefs.getString("version", "Not installed").orEmpty(),
            graph?.nodes?.size ?: 0,
            if (active.isFile) active.length() else 0,
            prefs.getLong("installed_at", 0),
            if (graph == null) "Offline driving road graph not installed. Online routing still required."
            else "Offline road graph installed. Full offline map tiles and voice navigation require separate validation."
        )
    }

    suspend fun installPickedArchive(uri: Uri): OfflineRoadStatus = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("Could not open selected road graph ZIP.")
        install(stream, null, "Manual import")
        status()
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                md.update(bytes, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun install(stream: InputStream, manifest: OfflineRoadManifest?, version: String) {
        lock.withLock {
            val tmp = File(folder, "download-${System.nanoTime()}.zip")
            try {
                stream.use { input ->
                    tmp.outputStream().use { output ->
                        val bytes = ByteArray(32_768)
                        var total = 0L
                        while (true) {
                            val read = input.read(bytes)
                            if (read < 0) break
                            total += read
                            require(total <= 180_000_000L) { "Offline road package exceeds 180MB." }
                            output.write(bytes, 0, read)
                        }
                        output.flush()
                        (output as java.io.FileOutputStream).fd.sync()
                    }
                }
                if (manifest != null) {
                    require(tmp.length() == manifest.sizeBytes) { "Road package size differs from manifest." }
                    require(digest(tmp).equals(manifest.sha256, ignoreCase = true)) { "Road package SHA-256 mismatch." }
                }
                val graph = OfflineRoadGraph.readZip(tmp)
                // Full Singapore release must contain enough car road data. Small
                // fixtures are accepted only in unit tests, never via user import.
                require(graph.nodes.size >= 10_000) { "Not a complete Singapore driving graph." }
                ZipFile(tmp).use { zip ->
                    val meta = zip.getEntry("metadata.json") ?: error("Missing road graph metadata.")
                    val json = JsonParser.parseString(zip.getInputStream(meta).bufferedReader().readText()).asJsonObject
                    require(json["schema"]?.asInt == 1 && json["country"]?.asString == "SG")
                }
                if (old.exists()) old.delete()
                if (active.exists()) check(active.renameTo(old)) { "Cannot preserve old offline graph." }
                try {
                    check(tmp.renameTo(active)) { "Could not install offline road package." }
                    cached = fingerprint() to graph
                    check(prefs.edit().putString("version", version)
                        .putLong("installed_at", System.currentTimeMillis())
                        .commit()) { "Offline map installation metadata could not be written." }
                } catch (failure: Exception) {
                    if (active.exists()) active.delete()
                    if (old.exists()) old.renameTo(active)
                    cached = null
                    throw failure
                }
            } finally {
                tmp.delete()
            }
        }
    }

    suspend fun checkForUpdates(): OfflineRoadStatus = withContext(Dispatchers.IO) {
        require(hasUsableInternet(context)) { "Internet is required to check offline road graph releases." }
        val request = Request.Builder().url(OFFLINE_ROAD_MANIFEST_URL)
            .header("User-Agent", "SGDelivery/830 OfflineRoadUpdater").build()
        val manifestJson = client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "No published Singapore road package is available (HTTP ${response.code})." }
            response.body?.string() ?: error("Empty road update manifest.")
        }
        val manifest = parseOfflineRoadManifest(manifestJson)
        if (loadOrNull() != null && prefs.getString("version", "") == manifest.version)
            return@withContext status()
        val download = Request.Builder().url(manifest.url)
            .header("User-Agent", "SGDelivery/830 OfflineRoadUpdater").build()
        client.newCall(download).execute().use { response ->
            require(response.isSuccessful) { "Road package download failed: HTTP ${response.code}." }
            val body = response.body ?: error("Road package is empty.")
            install(body.byteStream(), manifest, manifest.version)
        }
        status()
    }
}

internal class OfflineRoadRefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        OfflineRoadPackageStore(applicationContext).checkForUpdates()
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { Result.retry() }
}

internal object OfflineRoadUpdateScheduler {
    private const val NAME = "sg_delivery_offline_road_weekly"
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val work = PeriodicWorkRequestBuilder<OfflineRoadRefreshWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, work)
    }
}
