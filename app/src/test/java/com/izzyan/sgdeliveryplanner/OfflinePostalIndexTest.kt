package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OfflinePostalIndexTest {
    @Test fun bundledPostalIndexResolvesKnownPostalsWithoutCallingOneMap() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val index = OfflinePostalIndex(context)
        assertEquals(20260215, index.version())
        assertTrue("Expected complete offline seed", index.count() >= 100_000)
        val postals = listOf("791436", "822446", "822204", "521227", "520259",
            "520135", "520868", "461051", "320035", "310160")
        for (postal in postals) {
            val place = index.find(postal)
            assertNotNull("Missing $postal from offline data", place)
            assertEquals(postal, place!!.postal)
            assertTrue(place.lat in 1.1..1.6)
            assertTrue(place.lon in 103.5..104.1)
        }
        // Do not fabricate unverified coordinates for a code missing upstream.
        assertNull(index.find("410316"))
    }

    @Test fun startAndEndLocationSearchUsesOfflineAddressesAndPostalCodes() = runBlocking {
        val api = OfflinePostalSearchApi(ApplicationProvider.getApplicationContext())
        val postal = api.search("791436")
        assertEquals(1, postal.get("found").asInt)
        assertEquals("791436", postal.getAsJsonArray("results")[0].asJsonObject.get("POSTAL").asString)
        val street = api.search("TAMPINES")
        assertTrue(street.get("found").asInt > 0)
        assertTrue(street.getAsJsonArray("results").size() <= 6)
    }

    @Test fun weeklyUpdateRejectsPartialSnapshotsAndSelectsNewestCompleteData() {
        val files = JsonParser.parseString("""[
            {"name":"onemap_15022026.csv","size":10459823,"download_url":"https://raw.githubusercontent.com/dhanyyudi/onemap-building-sg/main/data/onemap_15022026.csv"},
            {"name":"onemap_01032026.csv","size":3025,"download_url":"https://raw.githubusercontent.com/dhanyyudi/onemap-building-sg/main/data/onemap_01032026.csv"},
            {"name":"onemap_01052026.csv","size":11500000,"download_url":"https://raw.githubusercontent.com/dhanyyudi/onemap-building-sg/main/data/onemap_01052026.csv"}
        ]""").asJsonArray
        val latest = latestCompletePostalSnapshot(files)
        assertNotNull(latest)
        assertEquals(20260501, latest!!.version)
    }
}
