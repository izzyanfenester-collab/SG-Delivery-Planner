package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** The device gets a real routing graph package, not merely cached map tiles. */
internal data class OfflineMapStatus(
    val installed: Boolean,
    val version: String,
    val installedAt: Long,
    val checkedAt: Long,
    val packageBytes: Long,
    val roadVertices: Int,
    val lastError: String
)

internal fun hasValidatedNetwork(context: Context): Boolean {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val active = connectivity.activeNetwork ?: return false
    val capabilities = connectivity.getNetworkCapabilities(active) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Downloads only the published package of this repository, verifies both ZIP and
 * graph SHA-256, and activates the new graph with a single persisted pointer.
 * Existing graph file and metadata remain available if download/extraction fails.
 */
internal class OfflineMapPackageManager(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "sg_offline_routing")
    private val prefs = app.getSharedPreferences("sg_offline_routing", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true).build()

    companion object {
        private val lock = Mutex()
        private const val RELEASE_ROOT =
            "https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/download/sg-offline-data/"
        private const val MANIFEST_URL = RELEASE_ROOT + "latest.json"
        private const val MAX_PACKAGE_BYTES = 180L * 1024 * 1024
        private const val MAX_GRAPH_BYTES = 250L * 1024 * 1024
    }

    fun graphFile(): File? {
        val fileName = prefs.getString("file", null) ?: return null
        if (!fileName.matches(Regex("sg-routing-[a-f0-9]{64}\\.bin"))) return null
        return File(directory, fileName).takeIf { it.isFile && it.length() >= 64 }
    }

    fun status(): OfflineMapStatus = OfflineMapStatus(
        installed = graphFile() != null,
        version = prefs.getString("version", "Not installed").orEmpty(),
        installedAt = prefs.getLong("installedAt", 0),
        checkedAt = prefs.getLong("checkedAt", 0),
        packageBytes = prefs.getLong("packageBytes", 0),
        roadVertices = prefs.getInt("roadVertices", 0),
        lastError = prefs.getString("error", "").orEmpty()
    )

    private fun String.isSha256() = matches(Regex("[0-9a-f]{64}"))

    private fun digest() = MessageDigest.getInstance("SHA-256")
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fetchJson(url: String): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", "SGDelivery/832 offline maps").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Singapore offline package not published yet (HTTP " + response.code + ")")
            val text = response.body?.string() ?: throw IOException("Empty offline map metadata")
            if (text.length > 40_000) throw IOException("Offline map metadata too large")
            return JSONObject(text)
        }
    }

    private fun download(url: String, output: File, expectedSize: Long, expectedHash: String) {
        require(url.startsWith(RELEASE_ROOT) && !url.contains("..")) {
            "Offline map source must be an approved SG Delivery release"
        }
        val req = Request.Builder().url(url).header("User-Agent", "SGDelivery/832 offline maps").build()
        val hash = digest()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Map package download failed: HTTP " + response.code)
            val body = response.body ?: throw IOException("Empty map download")
            body.byteStream().use { input ->
                FileOutputStream(output).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_PACKAGE_BYTES) throw IOException("Offline package exceeds storage limit")
                        hash.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                    out.fd.sync()
                    if (total != expectedSize || hash.digest().hex() != expectedHash)
                        throw IOException("Offline map package checksum/size mismatch")
                }
            }
        }
    }

    private fun extractGraph(zip: File, graph: File, expectedSize: Long, expectedHash: String) {
        val hash = digest()
        var graphFound = false
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name == "sg-routing.bin" && !entry.isDirectory) {
                    if (graphFound) throw IOException("Duplicate road graph entry")
                    graphFound = true
                    FileOutputStream(graph).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var bytesWritten = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            bytesWritten += n
                            if (bytesWritten > MAX_GRAPH_BYTES)
                                throw IOException("Uncompressed road graph too large")
                            output.write(buf, 0, n)
                            hash.update(buf, 0, n)
                        }
                        output.fd.sync()
                        if (bytesWritten != expectedSize)
                            throw IOException("Offline graph uncompressed length mismatch")
                    }
                }
                input.closeEntry()
            }
        }
        if (!graphFound || hash.digest().hex() != expectedHash)
            throw IOException("Offline road graph checksum mismatch")
    }

    /**
     * Returns true only after a complete verified update. Throws on failures,
     * without touching the active map package. Runs on IO, never on the UI thread.
     */
    suspend fun checkAndUpdate(progress: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!hasValidatedNetwork(app)) throw IOException("No internet. Existing offline map remains available.")
                directory.mkdirs()
                prefs.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
                val manifest = fetchJson(MANIFEST_URL)
                val version = manifest.getString("version")
                val zipSha = manifest.getString("sha256")
                val graphSha = manifest.getString("graph_sha256")
                val url = manifest.getString("url")
                val expectedZip = manifest.getLong("bytes")
                val expectedGraph = manifest.getLong("graph_bytes")
                val expectedNodes = manifest.getInt("nodes")
                require(version.matches(Regex("[0-9]{8}")) &&
                    zipSha.isSha256() && graphSha.isSha256() &&
                    expectedZip in 1024..MAX_PACKAGE_BYTES &&
                    expectedGraph in 1024..MAX_GRAPH_BYTES &&
                    expectedNodes >= 1000) { "Malformed offline map manifest" }
                val currentVersion = prefs.getString("version", "") ?: ""
                if (graphFile() != null && version <= currentVersion) {
                    prefs.edit().remove("error").apply()
                    return@withLock false
                }
                val tempZip = File(directory, "download.partial")
                val tempGraph = File(directory, "graph.partial")
                try {
                    tempZip.delete()
                    tempGraph.delete()
                    progress("Downloading Singapore offline road network…")
                    download(url, tempZip, expectedZip, zipSha)
                    progress("Verifying road-network graph…")
                    extractGraph(tempZip, tempGraph, expectedGraph, graphSha)
                    OfflineRoadGraph.open(tempGraph).use { graph ->
                        require(graph.nodeCount == expectedNodes) {
                            "Offline road node count mismatch"
                        }
                    }
                    val finalName = "sg-routing-" + graphSha + ".bin"
                    val finalFile = File(directory, finalName)
                    Files.move(tempGraph.toPath(), finalFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    val previous = prefs.getString("file", null)
                    // Atomic SharedPreferences commit: old package remains active on failed update.
                    val saved = prefs.edit()
                        .putString("file", finalName)
                        .putString("previous", previous)
                        .putString("version", version)
                        .putLong("installedAt", System.currentTimeMillis())
                        .putLong("packageBytes", expectedZip)
                        .putInt("roadVertices", expectedNodes)
                        .remove("error").commit()
                    if (!saved) throw IOException("Could not activate verified offline road graph")
                    directory.listFiles()?.filter {
                        it.name.startsWith("sg-routing-") && it.name.endsWith(".bin") &&
                            it.name != finalName && it.name != previous
                    }?.forEach { it.delete() }
                    progress("Offline Singapore road network installed: " + version)
                    true
                } catch (failure: Exception) {
                    prefs.edit().putString("error", failure.message ?: "Map update failed").apply()
                    throw failure
                } finally {
                    tempZip.delete()
                    tempGraph.delete()
                }
            }
        }
}

internal class WeeklyOfflineMapWorker(app: Context, params: WorkerParameters) : CoroutineWorker(app, params) {
    override suspend fun doWork(): Result {
        return try {
            OfflineMapPackageManager(applicationContext).checkAndUpdate()
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            // Broken or unpublished package should not erase the installed map.
            Result.failure()
        }
    }
}

internal object OfflineMapUpdateScheduler {
    fun schedule(context: Context) {
        val constraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = PeriodicWorkRequestBuilder<WeeklyOfflineMapWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraint).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "sg_offline_roads_weekly",
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }
}
