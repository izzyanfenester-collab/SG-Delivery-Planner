package com.izzyan.sgdeliveryplanner

import org.json.JSONArray
import org.json.JSONObject
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
}
