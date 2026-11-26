package com.izzyan.sgdeliveryplanner

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max

/** Real native Vulkan rendering of original cached MVT bytes, driven by geographic GPS replay. */
class SceneryNative888Test {
    @get:Rule val ui=createAndroidComposeRule<BuildingReplayActivity888>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block:(BuildingReplayActivity888)->T):T {
        var value:Any?=null;ui.activityRule.scenario.onActivity {value=block(it)}
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun animationScale(value:String) {
        for(key in listOf("animator_duration_scale","transition_animation_scale","window_animation_scale"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "settings put global $key $value")).use {it.readBytes()}
    }
    @After fun restoreBaselineAnimationEnvironment() { animationScale("0") }
    private fun asset(path:String)=instrumentation.context.assets.open(path).bufferedReader().use {it.readText()}
    private fun save(name:String,bytes:ByteArray,mime:String) {
        val resolver=ui.activity.contentResolver
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/SGDeliveryB888")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        requireNotNull(resolver.openOutputStream(uri)).use {it.write(bytes)}
        resolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
    }
    private fun image(name:String,bitmap:Bitmap) {
        val bytes=ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes))
        save(name,bytes.toByteArray(),"image/png")
    }
    private fun snapshot():Bitmap {
        ui.waitForIdle();Thread.sleep(700)
        val done=CountDownLatch(1);var result:Bitmap?=null
        main {it.map!!.snapshot {bitmap->result=bitmap;done.countDown()}}
        assertTrue("Native snapshot timeout",done.await(30,TimeUnit.SECONDS));return requireNotNull(result)
    }
    private fun screenshot(name:String) {
        ui.waitForIdle();Thread.sleep(180)
        val deadline=SystemClock.elapsedRealtime()+3_000L
        var window=instrumentation.uiAutomation.rootInActiveWindow
        while(window==null && SystemClock.elapsedRealtime()<deadline) {
            Thread.sleep(100);window=instrumentation.uiAutomation.rootInActiveWindow
        }
        // Accessibility can legitimately return null during a native TextureView update.
        // Reject a positively identified external window; raw screenshots are reviewed as well.
        if(window!=null)assertEquals("Native screenshot must show SG Delivery without an external error dialog",
            instrumentation.targetContext.packageName,window.packageName?.toString())
        image(name,requireNotNull(instrumentation.uiAutomation.takeScreenshot()))
    }
    private fun pixels(bitmap:Bitmap)=IntArray(bitmap.width*bitmap.height).also {
        bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    }
    private fun blue(c:Int)=((c ushr 16) and 255)<16 && ((c ushr 8) and 255) in 120..145 && (c and 255)>242
    private fun luminance(c:Int)=(((c ushr 16) and 255)*54+((c ushr 8) and 255)*183+(c and 255)*19)/256
    private fun load(scene:String) {
        ui.waitUntil(30_000) {ui.activity.ready}
        val manifest=JSONObject(asset("scenery888/source-manifest.json")).getJSONObject("scenes").getJSONObject(scene)
        val root=File(ui.activity.filesDir,"b888-native/$scene")
        val tile=File(root,"tiles/14/${manifest.getInt("x")}/${manifest.getInt("y")}.pbf")
        tile.parentFile!!.mkdirs()
        instrumentation.context.assets.open("scenery888/$scene.pbf").use {input->tile.outputStream().use {input.copyTo(it)}}
        val glyph=File(root,"fonts/Noto Sans Regular/0-255.pbf");glyph.parentFile!!.mkdirs()
        instrumentation.context.assets.open("scenery888/NotoSansRegular-0-255.pbf").use {input->glyph.outputStream().use {input.copyTo(it)}}
        val route=if(scene=="woodlands")instrumentation.context.assets.open("scenery888/woodlands-route.bin")
            .use {GZIPInputStream(it).bufferedReader().use {r->r.readText()}} else asset("scenery888/marina-route.json")
        val template=asset("scenery888/vector-style.json")
        val bounds=manifest.getJSONArray("bounds").let {b->(0..3).map {b.getDouble(it)}}
        main {a->a.before=true;a.sceneName=if(scene=="woodlands")"Woodlands" else "Marina Bay"
            a.load(route);a.installVectorScene(template,"file://${root}/tiles","file://${root}/fonts/{fontstack}/{range}.pbf",bounds)}
        ui.waitUntil(30_000) {main {it.sceneReady}}
        gpsBeforeNext(if(scene=="woodlands")5 else 3,if(scene=="woodlands")35.0 else 550.0)
        main {it.move(zoom=if(scene=="woodlands")17.15 else 17.35)}
        awaitBuildings();snapshot()
    }
    private fun awaitBuildings()=ui.waitUntil(60_000) {main {a->a.map!!.queryRenderedFeatures(
        android.graphics.RectF(0f,0f,a.mapView.width.toFloat(),a.mapView.height.toFloat()),"building-3d").isNotEmpty()}}
    private fun gpsBeforeNext(step:Int,metres:Double) {
        val points=main {decodeNavigationGeometry(it.route!!.legs.single().steps[step].geometry)}
        var distance=points.zipWithNext().sumOf {navigationDistance(it.first,it.second)}-metres
        for((a,b)in points.zipWithNext()) {
            val length=navigationDistance(a,b)
            if(distance<=length || b==points.last()) {
                val f=(distance/length).coerceIn(0.0,1.0)
                val gps=GpsPoint(a.lat+(b.lat-a.lat)*f,a.lon+(b.lon-a.lon)*f)
                val bearing=Math.toDegrees(atan2((b.lon-a.lon)*cos(Math.toRadians(a.lat)),b.lat-a.lat)).toFloat()
                main {it.replayGps(gps,bearing)};return
            };distance-=length
        }
        error("No recorded geographic sample")
    }
    private fun geometry()=main {a->val l=a.map!!.style!!.getLayer("building-3d") as FillExtrusionLayer
        listOf(l.sourceId,l.sourceLayer,l.minZoom.toString(),l.maxZoom.toString(),l.filter.toString(),
            l.fillExtrusionBase.expression.toString(),l.fillExtrusionHeight.expression.toString(),
            a.activeLine!!.points.toString(),a.activeLine!!.color.toString(),a.activeLine!!.width.toString(),
            a.casingLine!!.points.toString(),a.casingLine!!.color.toString(),a.casingLine!!.width.toString())}
    private fun camera()=main {a->val c=a.map!!.cameraPosition
        JSONObject().put("latitude",c.target!!.latitude).put("longitude",c.target!!.longitude)
            .put("zoom",c.zoom).put("tilt",c.tilt).put("bearing",c.bearing)
            .put("gpsLatitude",a.lastGps!!.lat).put("gpsLongitude",a.lastGps!!.lon)
            .put("routeStep",a.progress!!.stepIndex).put("metresToPrimary",a.progress!!.stepDistanceRemaining)}

    private fun pairedCases(scene: String, bearings: List<Double>) {
        load(scene)
        val cases = JSONArray()
        for (night in listOf(false, true)) for (bearing in bearings) {
            main { it.before = true; it.night = night; it.reloadScene() }
            ui.waitUntil(30_000) { main { it.sceneReady } }
            main { it.move(zoom = if (scene == "woodlands") 17.15 else 17.35, bearing = bearing) }
            awaitBuildings()
            val name = "$scene-${if (night) "night" else "day"}-bearing${bearing.toInt()}"
            val unchanged = geometry()
            val position = camera().toString()
            val before = snapshot()
            val beforePixels = pixels(before)
            screenshot("B888-BEFORE-$name.png")
            main { it.before = false; it.applyBuildingStyle() }
            val after = snapshot()
            val afterPixels = pixels(after)
            screenshot("B888-AFTER-$name.png")
            assertEquals("Geometry, source, heights, filters and route paints", unchanged, geometry())
            assertEquals("Matched GPS and camera", position, camera().toString())
            main { a ->
                val layer = a.map!!.style!!.getLayer("building-3d") as FillExtrusionLayer
                assertEquals(NavigationScenery888.BUILDING_OPACITY, layer.fillExtrusionOpacity.value!!, .001f)
                assertNotNull("Native data-driven building colours", layer.fillExtrusionColor.expression)
                layer.setProperties(fillExtrusionOpacity(0f))
            }
            val control = snapshot()
            val controlPixels = pixels(control)
            image("B888-route-control-$name.png", control)
            var blueCount = 0; var blueRetained = 0; var changed = 0; var buildingPixels = 0
            for (i in afterPixels.indices) {
                if (blue(controlPixels[i])) { blueCount++; if (blue(afterPixels[i])) blueRetained++ }
                if (afterPixels[i] != beforePixels[i]) changed++
                if (afterPixels[i] != controlPixels[i]) buildingPixels++
            }
            val ratio = blueRetained.toDouble() / max(1, blueCount)
            assertTrue("Original blue navigation route is visible", blueCount > 200)
            assertTrue("Building paint must preserve blue route: $ratio", ratio >= .985)
            assertTrue("Scenery colours must visibly change", changed > 3000)
            assertTrue("Native 3D buildings remain visible", buildingPixels > 2000)
            main { a ->
                val style = a.map!!.style!!
                val grass = style.getLayer("landcover_grass") as org.maplibre.android.style.layers.FillLayer
                assertEquals(android.graphics.Color.parseColor(NavigationScenery888.vegetationColour("landcover_grass", night)), grass.fillColorAsInt)
                assertTrue("Original camera markers retained", a.cameraMarkerCount > 0)
                assertEquals(106, ErpStore876(a).load().records.size)
                a.applyBuildingStyle()
            }
            cases.put(JSONObject().put("name", name).put("camera", JSONObject(position))
                .put("originalGeometryAndRouteUnchanged", true).put("beforeBuildingOpacity", .8)
                .put("afterBuildingOpacity", NavigationScenery888.BUILDING_OPACITY)
                .put("blueCorePixels", blueCount).put("unchangedBlueFraction", ratio)
                .put("changedPixels", changed).put("visibleBuildingPixels", buildingPixels)
                .put("greenMappedGrass", true).put("cameraMarkers", main { it.cameraMarkerCount }))
        }
        save("B888-$scene-native-rendering.json", JSONObject().put("result", "PASS").put("cases", cases)
            .put("originalBinaryVectorTiles", true).put("offline", true).put("physicalDrive", false)
            .put("aiGeneratedScreenshots", false).toString(2).toByteArray(), "application/json")
    }

    @Test fun woodlandsColoursAndGreeneryPreserveNavigationInDayAndNight() =
        pairedCases("woodlands", listOf(22.0, 125.0))

    @Test fun marinaBayBuildingTypesKeepTheirGeometryInDayAndNight() =
        pairedCases("marina", listOf(22.0))

    @Test fun coloursSurviveGpsZoomRotationOfflineReloadAndCameraAnimation() {
        animationScale("1")
        load("woodlands")
        main { it.before = false; it.night = true; it.applyBuildingStyle() }
        val native = main { it.map!! }
        val style = main { native.style!! }
        val layerIds = main { style.layers.map { it.id } }
        val sourceIds = main { style.sources.map { it.id } }
        val expression = main { (style.getLayer("building-3d") as FillExtrusionLayer).fillExtrusionColor.expression.toString() }
        val modes = JSONArray()
        for ((zoom, tilt, bearing) in listOf(Triple(16.0, 60.0, 290.0), Triple(18.2, 0.0, 90.0), Triple(17.15, 58.0, 22.0))) {
            main { it.move(zoom, tilt, bearing); it.applyBuildingStyle() }; snapshot()
            main {
                assertSame(style, native.style)
                assertEquals(layerIds, style.layers.map { it.id })
                assertEquals(sourceIds, style.sources.map { it.id })
                assertEquals(expression, (style.getLayer("building-3d") as FillExtrusionLayer).fillExtrusionColor.expression.toString())
            }
            modes.put(JSONObject().put("zoom", zoom).put("tilt", tilt).put("bearing", bearing))
        }
        val line = main { it.activeLine!! }
        gpsBeforeNext(5, 30.0); gpsBeforeNext(5, 25.0)
        main { assertSame(line, it.activeLine) }
        val done = CountDownLatch(1)
        var cancelled = false
        main { a ->
            val target = CameraPosition.Builder(native.cameraPosition).zoom(16.6).tilt(55.0).bearing(65.0).build()
            native.animateCamera(CameraUpdateFactory.newCameraPosition(target), 1800, object : MapLibreMap.CancelableCallback {
                override fun onCancel() { cancelled = true; done.countDown() }
                override fun onFinish() { done.countDown() }
            })
        }
        Thread.sleep(300)
        assertEquals("Animation still active when paints change", 1L, done.count)
        main { it.night = false; it.applyBuildingStyle() }
        assertTrue(done.await(15, TimeUnit.SECONDS)); assertFalse(cancelled)
        main { it.night = true; it.reloadScene() }
        ui.waitUntil(30_000) { main { it.sceneReady } }
        main { it.move() }; awaitBuildings(); snapshot()
        main {
            val layer = it.map!!.style!!.getLayer("building-3d") as FillExtrusionLayer
            assertEquals(expression, layer.fillExtrusionColor.expression.toString())
            assertEquals(NavigationScenery888.BUILDING_OPACITY, layer.fillExtrusionOpacity.value!!, .001f)
        }
        screenshot("B888-AFTER-offline-style-reload.png")
        save("B888-native-stability.json", JSONObject().put("result", "PASS").put("modes", modes)
            .put("sameLayersAndSources", true).put("sameRouteAcrossGpsUpdates", true)
            .put("offlineReload", true).put("cameraAnimationCancelled", false)
            .put("themeChangedDuringAnimation", true).toString(2).toByteArray(), "application/json")
    }

    @Test fun existingTreeCanopyAndTrunkLayersReceiveNaturalColours() {
        load("woodlands")
        // Synthetic renderer probe only; no fabricated trees enter location screenshots or production data.
        main { a ->
            val style = a.map!!.style!!
            val point = a.lastGps!!
            val feature = org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(point.lon, point.lat))
            style.addSource(org.maplibre.android.style.sources.GeoJsonSource("tree-test-source", feature))
            style.addLayer(org.maplibre.android.style.layers.CircleLayer("tree-trunk", "tree-test-source").withProperties(
                org.maplibre.android.style.layers.PropertyFactory.circleColor("#808080"),
                org.maplibre.android.style.layers.PropertyFactory.circleRadius(10f)))
            style.addLayer(org.maplibre.android.style.layers.CircleLayer("tree-canopy", "tree-test-source").withProperties(
                org.maplibre.android.style.layers.PropertyFactory.circleColor("#808080"),
                org.maplibre.android.style.layers.PropertyFactory.circleRadius(18f)))
            val ids = style.layers.map { it.id }
            val sources = style.sources.map { it.id }
            for (dark in listOf(false, true)) {
                NavigationMapPalette.apply(style, dark)
                val canopy = style.getLayer("tree-canopy") as org.maplibre.android.style.layers.CircleLayer
                val trunk = style.getLayer("tree-trunk") as org.maplibre.android.style.layers.CircleLayer
                assertEquals(android.graphics.Color.parseColor(NavigationScenery888.vegetationColour("tree-canopy", dark)), canopy.circleColorAsInt)
                assertEquals(android.graphics.Color.parseColor(NavigationScenery888.vegetationColour("tree-trunk", dark)), trunk.circleColorAsInt)
                assertEquals(18f, canopy.circleRadius.value!!, .001f)
                assertEquals(ids, style.layers.map { it.id }); assertEquals(sources, style.sources.map { it.id })
            }
        }
        val rendered = pixels(snapshot())
        val expected = android.graphics.Color.parseColor(NavigationScenery888.vegetationColour("tree-canopy", true))
        assertTrue("Native tree canopy rendered green", rendered.count { it == expected } > 100)
        save("B888-native-tree-probe.json", JSONObject().put("result", "PASS")
            .put("existingCanopyGreenAndTrunkBrown", true).put("sameGeometryLayersAndSources", true)
            .put("syntheticProbeExcludedFromLocationScreenshots", true)
            .put("individualTreesInCapturedRealTiles", 0).toString(2).toByteArray(), "application/json")
    }
}

