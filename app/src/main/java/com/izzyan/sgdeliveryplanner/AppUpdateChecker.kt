package com.izzyan.sgdeliveryplanner

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

private const val RELEASES_API =
    "https://api.github.com/repos/izzyanfenester-collab/SG-Delivery-Planner/releases/latest"
private const val LATEST_RELEASE_PAGE =
    "https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/latest"
private const val RELEASE_TAG_PATH =
    "/izzyanfenester-collab/SG-Delivery-Planner/releases/tag/"
private const val RELEASE_PATH = "/izzyanfenester-collab/SG-Delivery-Planner/releases/download/"
private const val UPDATE_PREFS = "sg_delivery_app_update"
private const val UPDATE_CHANNEL = "sg_delivery_app_releases"
internal const val APP_UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

internal data class PublishedAppRelease(val build: Int, val version: String, val apkUrl: String)

/** Only accept APK assets published under our own public GitHub Releases path. */
internal fun parseOfficialAppRelease(json: String): PublishedAppRelease? {
    val root = JSONObject(json)
    if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
    val tag = root.optString("tag_name")
    val match = Regex("^v[0-9]+\\.[0-9]+\\.[0-9]+-b([0-9]+)$").matchEntire(tag)
        ?: return null
    val build = match.groupValues[1].toIntOrNull() ?: return null
    val expectedName = "SG-Delivery-B${build}-Official.apk"
    val assets = root.optJSONArray("assets") ?: return null
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        if (asset.optString("name") != expectedName) continue
        if (asset.optLong("size") < 1_000_000L) continue
        val url = asset.optString("browser_download_url")
        val uri = runCatching { URI(url) }.getOrNull() ?: continue
        if (uri.scheme != "https" || uri.host != "github.com" || uri.rawQuery != null ||
            !uri.path.startsWith(RELEASE_PATH) || !uri.path.endsWith("/$expectedName")) continue
        return PublishedAppRelease(build, tag.removePrefix("v"), url)
    }
    return null
}

/** Parse only the verified official GitHub latest-release redirect. */
internal fun parseOfficialLatestRedirect(location: String): PublishedAppRelease? {
    val uri = runCatching { URI(LATEST_RELEASE_PAGE).resolve(location) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != "github.com" || uri.port != -1 ||
        uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val path = uri.rawPath ?: return null
    if (!path.startsWith(RELEASE_TAG_PATH)) return null
    val tag = path.removePrefix(RELEASE_TAG_PATH)
    val match = Regex("^v[0-9]+\\.[0-9]+\\.[0-9]+-b([0-9]+)$")
        .matchEntire(tag) ?: return null
    val build = match.groupValues[1].toIntOrNull() ?: return null
    return PublishedAppRelease(build, tag.removePrefix("v"),
        "https://github.com" + RELEASE_PATH + tag + "/SG-Delivery-B" + build + "-Official.apk")
}

internal fun installedAppBuild(context: Context): Int {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val named = Regex("-b([0-9]+)$").find(info.versionName.orEmpty())
        ?.groupValues?.get(1)?.toIntOrNull()
    @Suppress("DEPRECATION")
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    return named ?: code
}

internal data class AppUpdateState(
    val currentBuild: Int,
    val latestBuild: Int?,
    val latestVersion: String?,
    val downloadUrl: String?,
    val lastChecked: Long,
    val error: String
) {
    val updateAvailable: Boolean
        get() = error.isBlank() && latestBuild != null &&
            latestBuild > currentBuild && !downloadUrl.isNullOrBlank()

    companion object {
        fun load(context: Context): AppUpdateState {
            val prefs = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
            val storedBuild = prefs.getInt("latest_build", 0)
            return AppUpdateState(
                installedAppBuild(context), storedBuild.takeIf { it > 0 },
                prefs.getString("latest_version", null), prefs.getString("download_url", null),
                prefs.getLong("checked_at", 0), prefs.getString("last_error", "").orEmpty()
            )
        }
    }
}

internal class AppReleaseChecker(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val noRedirectClient = client.newBuilder().followRedirects(false).build()

    /** Fallback without api.github.com rate limits: GitHub's latest stable-release
     * redirect yields the real published tag, never a hardcoded current version.
     */
    private fun checkPublicReleasePage(): PublishedAppRelease? {
        val request = Request.Builder().url(LATEST_RELEASE_PAGE)
            .header("User-Agent", "SG-Delivery-Android-Update-Checker")
            .header("Cache-Control", "no-cache")
            .build()
        val release = noRedirectClient.newCall(request).execute().use { response ->
            if (response.code !in listOf(301, 302, 303, 307, 308)) return null
            parseOfficialLatestRedirect(response.header("Location").orEmpty())
        } ?: return null
        // Verify the expected APK asset exists before showing a download button.
        val assetRequest = Request.Builder().url(release.apkUrl).head()
            .header("User-Agent", "SG-Delivery-Android-Update-Checker").build()
        return noRedirectClient.newCall(assetRequest).execute().use { response ->
            release.takeIf { response.isSuccessful ||
                response.code in listOf(301, 302, 303, 307, 308) }
        }
    }

    /** Run off the main thread; no user login or API token required. */
    fun checkNow(): AppUpdateState {
        val request = Request.Builder().url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SG-Delivery-Android-Update-Checker")
            .header("Cache-Control", "no-cache").build()
        var published: PublishedAppRelease? = null
        var reason = "Could not check for updates. Check your connection and try again."
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) reason = "GitHub API unavailable (HTTP " + response.code + ")."
                else {
                    published = runCatching {
                        parseOfficialAppRelease(response.body?.string().orEmpty())
                    }.getOrNull()
                    if (published == null) reason = "No valid official APK was found."
                }
            }
        } catch (_: Exception) {
            reason = "Could not reach GitHub API."
        }
        if (published == null) {
            published = try { checkPublicReleasePage() } catch (_: Exception) { null }
        }
        val error = if (published == null)
            "$reason Alternative release check also unavailable. Try again later."
        else ""
        val prefs = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putLong("checked_at", System.currentTimeMillis())
            putString("last_error", error)
            if (published != null) {
                putInt("latest_build", requireNotNull(published).build)
                putString("latest_version", requireNotNull(published).version)
                putString("download_url", requireNotNull(published).apkUrl)
            } else {
                // Clear stale #833 metadata so HTTP 403 cannot show a false update.
                remove("latest_build")
                remove("latest_version")
                remove("download_url")
            }
        }.apply()
        return AppUpdateState.load(context)
    }
}

internal object AppUpdateNotifier {
    fun notifyIfNew(context: Context, state: AppUpdateState) {
        if (!state.updateAvailable) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val latest = requireNotNull(state.latestBuild)
        val prefs = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt("notified_build", 0) >= latest) return
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(UPDATE_CHANNEL, "SG Delivery app updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val download = Intent(Intent.ACTION_VIEW, Uri.parse(state.downloadUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(context, 0, download,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SG Delivery Build #$latest available")
            .setContentText("Tap to download the official APK update.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(812, notification)
        prefs.edit().putInt("notified_build", latest).apply()
    }
}

internal class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val state = AppReleaseChecker(applicationContext).checkNow()
        runCatching { AppUpdateNotifier.notifyIfNew(applicationContext, state) }
        Result.success()
    }
}

internal object AppUpdateScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "sg_delivery_official_app_update_daily", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
