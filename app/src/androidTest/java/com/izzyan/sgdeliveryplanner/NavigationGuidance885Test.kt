package com.izzyan.sgdeliveryplanner

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.cos

/** Actual native/Compose rendering driven by GPS coordinates on recorded Singapore routes. */
class NavigationGuidance885Test {
    @get:Rule val ui=createAndroidComposeRule<NavigationReplayActivity885>()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun save(name:String,bytes:ByteArray,mime:String) {
        val resolver=app.contentResolver
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/SGDeliveryB885")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
    }
    private fun image(name:String,bitmap:Bitmap) {
        val out=ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out))
        save(name,out.toByteArray(),"image/png")
    }
    private fun screenshot(name:String) {
        ui.waitForIdle();Thread.sleep(600)
        image(name,requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
    }
    private fun load(name:String) {
        ui.waitUntil(30_000){ui.activity.ready}
        val json=InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { it.readText() }
        ui.runOnIdle { ui.activity.load(json) }
    }
    /** Sample a geographic coordinate along the actual approaching step, with its heading. */
    private fun gpsBeforeNext(currentStep:Int,metres:Double) {
        val route=requireNotNull(ui.activity.route)
        val points=decodeNavigationGeometry(route.legs.single().steps[currentStep].geometry)
        val length=points.zipWithNext().sumOf { navigationDistance(it.first,it.second) }
        var target=(length-metres).coerceIn(0.0,length)
        for((a,b) in points.zipWithNext()) {
            val distance=navigationDistance(a,b)
            if(target<=distance || b==points.last()) {
                val f=if(distance==0.0)0.0 else (target/distance).coerceIn(0.0,1.0)
                val gps=GpsPoint(a.lat+(b.lat-a.lat)*f,a.lon+(b.lon-a.lon)*f)
                val heading=NavigationRoadMatcher.normalizeBearing(Math.toDegrees(atan2(
                    (b.lon-a.lon)*cos(Math.toRadians(a.lat)),b.lat-a.lat)).toFloat())
                ui.runOnIdle { ui.activity.replayGps(gps,heading) }
                return
            }
            target-=distance
        }
        error("No replay sample")
    }
    private fun observation(name:String):JSONObject {
        val state=ui.activity.guidance
        val p=requireNotNull(ui.activity.progress)
        return JSONObject().put("case",name).put("gps",JSONObject()
            .put("lat",ui.activity.lastGps?.lat).put("lon",ui.activity.lastGps?.lon).put("accuracyM",7))
            .put("sdkStepIndex",p.stepIndex).put("metresToPrimary",p.stepDistanceRemaining)
            .put("laneManeuverIndex",state.lanes?.maneuverIndex)
            .put("laneCount",state.lanes?.lanes?.size ?: 0)
            .put("validLaneIndicesFromLeft",JSONArray(state.lanes?.lanes?.mapIndexedNotNull { i,l->i.takeIf { l.valid } }.orEmpty()))
            .put("thenStepIndex",state.then?.maneuverIndex).put("thenArrow",state.then?.cue?.sign?.name)
    }
    @Test fun recordedGpsAdvancesRealLanesAndThenAndHidesMissingOrObsoleteData() {
        val observations=JSONArray()
        load("navigation-885-pie-recorded.json")
        gpsBeforeNext(0,180.0)
        ui.onNodeWithTag("gps-lane-strip").assertExists()
        for(i in 0..5)ui.onNodeWithTag("gps-lane-$i").assertExists()
        ui.onNodeWithTag("gps-lane-0").assertIsSelected()
        ui.onNodeWithTag("gps-lane-1").assertIsSelected()
        ui.onNodeWithTag("gps-lane-2").assertIsNotSelected()
        ui.onNodeWithText("Then").assertExists()
        assertEquals(2,ui.activity.guidance.then?.maneuverIndex)
        // Verify actual pixels, not semantics alone: white legal arrow and dim inactive arrow.
        val valid=ui.onNodeWithTag("gps-lane-0").captureToImage().asAndroidBitmap()
        val invalid=ui.onNodeWithTag("gps-lane-2").captureToImage().asAndroidBitmap()
        fun whitePixels(bitmap:Bitmap):Int {var count=0
            for(y in 0 until bitmap.height)for(x in 0 until bitmap.width) {
                val c=bitmap.getPixel(x,y)
                if(android.graphics.Color.red(c)>235 && android.graphics.Color.green(c)>235 && android.graphics.Color.blue(c)>235)count++
            };return count
        }
        assertTrue("Suitable lane must have a white rendered arrow",whitePixels(valid)>30)
        assertEquals("Inactive lane must remain muted",0,whitePixels(invalid))
        screenshot("B885-GPS-replay-PIE-exit20B-lanes-and-Then.png")
        observations.put(observation("PIE left exit; both legal lanes highlighted"))
        gpsBeforeNext(0,20.0);gpsBeforeNext(1,180.0);gpsBeforeNext(1,100.0)
        assertEquals(2,ui.activity.guidance.lanes?.maneuverIndex)
        assertEquals(2,ui.activity.guidance.lanes?.lanes?.size)
        ui.onNodeWithTag("gps-then-badge").assertDoesNotExist()
        screenshot("B885-GPS-replay-shared-left-right-lane.png")
        observations.put(observation("Following fork; shared lane left branch highlighted"))
        load("navigation-885-city-recorded.json");gpsBeforeNext(1,60.0)
        assertEquals(2,ui.activity.guidance.lanes?.maneuverIndex)
        assertEquals(NavigationSign.TURN_LEFT,ui.activity.guidance.then?.cue?.sign)
        ui.onNodeWithTag("gps-lane-0").assertIsNotSelected()
        ui.onNodeWithTag("gps-lane-1").assertIsSelected()
        screenshot("B885-GPS-replay-Oldham-right-Then-Orchard-left.png")
        observations.put(observation("Handy Road right to Oldham Lane; Then left to Orchard Road"))
        ui.runOnIdle { ui.activity.invalidateGuidance() }
        ui.onNodeWithTag("gps-lane-strip").assertDoesNotExist()
        ui.onNodeWithTag("gps-then-badge").assertDoesNotExist()
        load("navigation-885-pie-recorded.json");gpsBeforeNext(3,100.0)
        ui.onNodeWithTag("gps-lane-strip").assertDoesNotExist()
        observations.put(observation("No lane data at Upper Thomson exit; later intersections not borrowed"))
        save("B885-GPS-replay-results.json",JSONObject().put("result","PASS")
            .put("offlineRecordedResponses",true).put("noNetworkSourcesInNativeMap",true)
            .put("physicalDrive",false).put("productionGuidanceAndHeader",true)
            .put("observations",observations).toString(2).toByteArray(),"application/json")
    }
    @Test fun nativeGuidanceRemainsReadableAtSmallWidthLargeFontsDayNightAndPitch() {
        val observations=JSONArray()
        load("navigation-885-city-recorded.json");gpsBeforeNext(1,60.0)
        for((width,scale) in listOf(400 to 1f,320 to 1f,320 to 1.5f)) {
            for(night in listOf(true,false)) {
                ui.runOnIdle { ui.activity.widthDp=width;ui.activity.fontScale=scale
                    ui.activity.night=night;ui.activity.pitch=if(night)45.0 else 0.0;ui.activity.paint() }
                ui.waitForIdle()
                ui.onNodeWithTag("gps-lane-strip").assertIsDisplayed()
                ui.onNodeWithText("Then").assertIsDisplayed()
                ui.onNodeWithTag("gps-then-arrow").assertIsDisplayed()
                val lanes=ui.onNodeWithTag("gps-lane-strip").getUnclippedBoundsInRoot()
                val then=ui.onNodeWithTag("gps-then-badge").getUnclippedBoundsInRoot()
                val rail=ui.onNodeWithTag("replay-control-rail").getUnclippedBoundsInRoot()
                assertTrue("Then must be below the lane panel",then.top>=lanes.bottom)
                assertTrue("Controls must begin below complete header",rail.top>=then.bottom)
                screenshot("B885-native-${width}dp-${scale}font-${if(night)"night-3D" else "day-2D"}.png")
                observations.put(JSONObject().put("widthDp",width).put("fontScale",scale).put("night",night)
                    .put("laneHeightDp",lanes.bottom.value-lanes.top.value)
                    .put("thenVisible",true).put("noControlOverlap",true))
            }
        }
        save("B885-native-layout-results.json",JSONObject().put("result","PASS").put("cases",observations)
            .put("palette","Original Official Navy & Gold").toString(2).toByteArray(),"application/json")
    }
}
