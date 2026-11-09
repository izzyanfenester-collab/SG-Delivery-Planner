package com.izzyan.sgdeliveryplanner

import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppUpdateCheckerTest {
    private fun release(tag: String, assetName: String, url: String): String = JSONObject()
        .put("tag_name", tag)
        .put("draft", false)
        .put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject()
            .put("name", assetName)
            .put("size", 67_000_000)
            .put("browser_download_url", url)))
        .toString()

    @Test fun acceptsOnlyOfficialApkFromOwnerRelease() {
        val version = parseOfficialAppRelease(release(
            "v1.4.7-b812", "SG-Delivery-B812-Official.apk",
            "https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/download/v1.4.7-b812/SG-Delivery-B812-Official.apk"
        ))
        assertEquals(812, version?.build)
        assertEquals("1.4.7-b812", version?.version)
    }

    @Test fun rejectsOtherHostsAndUnexpectedApkNames() {
        assertNull(parseOfficialAppRelease(release(
            "v1.4.7-b812", "SG-Delivery-B812-Official.apk",
            "https://example.com/SG-Delivery-B812-Official.apk"
        )))
        assertNull(parseOfficialAppRelease(release(
            "v1.4.7-b812", "SG-Delivery-B812-Testing.apk",
            "https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/download/v1.4.7-b812/SG-Delivery-B812-Testing.apk"
        )))
        assertNull(parseOfficialAppRelease(release(
            "testing-b785-independent-end", "SG-Delivery-B785-Official.apk",
            "https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/download/testing-b785-independent-end/SG-Delivery-B785-Official.apk"
        )))
    }

    @Test fun onlyNewerBuildPromptsRunner() {
        val latest = AppUpdateState(811, 812, "1.4.7-b812", "https://github.com/example.apk", 0, "")
        assertTrue(latest.updateAvailable)
        assertFalse(latest.copy(currentBuild = 812).updateAvailable)
        assertFalse(latest.copy(downloadUrl = null).updateAvailable)
    }
    @Test fun fallbackAcceptsOnlyOfficialGithubLatestRedirect() {
        val tag = "/izzyanfenester-collab/SG-Delivery-Planner/releases/tag/v1.4.22-b834"
        val redirect = parseOfficialLatestRedirect("https://github.com" + tag)
        assertEquals(834, redirect?.build)
        assertEquals("1.4.22-b834", redirect?.version)
        assertEquals(
            "https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/download/v1.4.22-b834/SG-Delivery-B834-Official.apk",
            redirect?.apkUrl
        )
        assertEquals(redirect, parseOfficialLatestRedirect(tag))
    }

    @Test fun fallbackRejectsUnsafeHostsOrUnexpectedTags() {
        val path = "/izzyanfenester-collab/SG-Delivery-Planner/releases/tag/v1.4.22-b834"
        assertNull(parseOfficialLatestRedirect("http://github.com" + path))
        assertNull(parseOfficialLatestRedirect("https://github.com.evil.invalid" + path))
        assertNull(parseOfficialLatestRedirect("https://github.com@evil.invalid" + path))
        assertNull(parseOfficialLatestRedirect("https://github.com" + path + "?fake=1"))
        assertNull(parseOfficialLatestRedirect("https://github.com" + path + "#fragment"))
        assertNull(parseOfficialLatestRedirect(path + "-testing"))
        assertNull(parseOfficialLatestRedirect("/other/project/releases/tag/v1.4.22-b834"))
    }

    private fun fakeGithub(apiCode: Int = 403, pageCode: Int = 302, assetCode: Int = 302): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val code = when {
                req.url.host == "api.github.com" -> apiCode
                req.url.encodedPath.endsWith("/releases/latest") -> pageCode
                req.method == "HEAD" && req.url.encodedPath.endsWith("SG-Delivery-B834-Official.apk") -> assetCode
                else -> 404
            }
            val response = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(code).message("Mock GitHub")
                .body("".toResponseBody())
            if (code == 302 && req.url.encodedPath.endsWith("/releases/latest"))
                response.header("Location", "https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/tag/v1.4.22-b834")
            if (code == 302 && req.method == "HEAD")
                response.header("Location", "https://objects.githubusercontent.com/download")
            response.build()
        }.build()

    @Test fun http403FallsBackToWebsiteAndFindsCurrentOfficialWithoutToken() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pref = context.getSharedPreferences("sg_delivery_app_update", Context.MODE_PRIVATE)
        pref.edit().clear().commit()
        try {
            val state = AppReleaseChecker(context, fakeGithub()).checkNow()
            assertEquals(834, state.latestBuild)
            assertEquals("1.4.22-b834", state.latestVersion)
            assertEquals("", state.error)
            assertTrue(state.downloadUrl!!.contains("SG-Delivery-B834-Official.apk"))
        } finally {
            pref.edit().clear().commit()
        }
    }

    @Test fun failedApiAndFallbackDoNotReportCachedStaleVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pref = context.getSharedPreferences("sg_delivery_app_update", Context.MODE_PRIVATE)
        pref.edit().clear()
            .putInt("latest_build", 833)
            .putString("latest_version", "1.4.21-b833")
            .putString("download_url", "https://github.com/old")
            .commit()
        try {
            val state = AppReleaseChecker(context, fakeGithub(pageCode = 403)).checkNow()
            assertNull(state.latestBuild)
            assertNull(state.downloadUrl)
            assertFalse(state.updateAvailable)
            assertTrue(state.error.contains("403"))
        } finally {
            pref.edit().clear().commit()
        }
    }

    @Test fun fallbackRequiresActualPublishedApkAsset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pref = context.getSharedPreferences("sg_delivery_app_update", Context.MODE_PRIVATE)
        pref.edit().clear().commit()
        try {
            val state = AppReleaseChecker(context, fakeGithub(assetCode = 404)).checkNow()
            assertNull(state.latestBuild)
            assertFalse(state.updateAvailable)
        } finally {
            pref.edit().clear().commit()
        }
    }

    @Test fun errorMustSuppressPreviouslyCachedUpdate() {
        val stale = AppUpdateState(
            832, 833, "1.4.21-b833",
            "https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/download/v1.4.21-b833/SG-Delivery-B833-Official.apk",
            1, "GitHub API unavailable (HTTP 403)."
        )
        assertFalse(stale.updateAvailable)
    }

}
