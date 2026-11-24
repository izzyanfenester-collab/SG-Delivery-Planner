package com.izzyan.sgdeliveryplanner

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max

/** Real Android MapLibre 13.5 renderer. Frozen OSM geometry and provider route; no HTTP. */
class BuildingNative886Test {
    @get:Rule val ui=createAndroidComposeRule<BuildingReplayActivity886>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block:(BuildingReplayActivity886)->T):T {
        var value:Any?=null;ui.activityRule.scenario.onActivity { value=block(it) }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun asset(name:String):String=instrumentation.context.assets.open("buildings886/$name").use { stream ->
        (if(name.endsWith(".bin"))GZIPInputStream(stream) else stream).bufferedReader().use { it.readText() }
    }
    private fun save(name:String,bytes:ByteArray,mime:String) {
        val resolver=ui.activity.contentResolver
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/SGDeliveryB886")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
    }
    private fun image(name:String,bitmap:Bitmap) {
        val bytes=ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes))
        save(name,bytes.toByteArray(),"image/png")
    }
    private fun snapshot():Bitmap {
        // Native renderer is asynchronous; wait for paint and glyph work, not Compose semantics alone.
        ui.waitForIdle();Thread.sleep(850)
        val done=CountDownLatch(1);var bitmap:Bitmap?=null
        main { it.map!!.snapshot { result->bitmap=result;done.countDown() } }
        assertTrue("Native snapshot timed out",done.await(30,TimeUnit.SECONDS))
        return requireNotNull(bitmap)
    }
    private fun screenshot(name:String) {
        ui.waitForIdle();Thread.sleep(350)
        image(name,requireNotNull(instrumentation.uiAutomation.takeScreenshot()))
    }
    private fun load() {
        ui.waitUntil(30_000) { ui.activity.ready }
        val glyph=File(ui.activity.filesDir,"b886-fonts/Noto Sans Regular/0-255.pbf")
        glyph.parentFile!!.mkdirs()
        instrumentation.context.assets.open("buildings886/NotoSansRegular-0-255.pbf").use { input ->
            glyph.outputStream().use { input.copyTo(it) }
        }
        val geometry=asset("woodlands-geometry.bin");val template=asset("woodlands-style.json")
        val route=asset("woodlands-route.bin")
        main { a->a.load(route);a.installScene(geometry,template,"file://${ui.activity.filesDir}/b886-fonts/{fontstack}/{range}.pbf") }
        ui.waitUntil(30_000) { main { it.sceneReady } }
        gpsBeforeNext(5,35.0)
        main { it.move() };snapshot()
    }
    private fun gpsBeforeNext(step:Int,metres:Double) {
        val points=main { decodeNavigationGeometry(it.route!!.legs.single().steps[step].geometry) }
        var distance=points.zipWithNext().sumOf { navigationDistance(it.first,it.second) }-metres
        for((a,b)in points.zipWithNext()) {
            val length=navigationDistance(a,b)
            if(distance<=length || b==points.last()) {
                val f=(distance/length).coerceIn(0.0,1.0)
                val gps=GpsPoint(a.lat+(b.lat-a.lat)*f,a.lon+(b.lon-a.lon)*f)
                val bearing=Math.toDegrees(atan2((b.lon-a.lon)*cos(Math.toRadians(a.lat)),b.lat-a.lat)).toFloat()
                main { it.replayGps(gps,bearing) };return
            };distance-=length
        }
        error("Recorded route has no sample")
    }
    private fun blue(c:Int)=Color.red(c)<16 && Color.green(c) in 120..145 && Color.blue(c)>242
    private fun bluePreservation(control:Bitmap,actual:Bitmap):Pair<Int,Double> {
        var total=0;var retained=0
        for(y in 0 until control.height)for(x in 0 until control.width)if(blue(control.getPixel(x,y))) {
            total++;if(blue(actual.getPixel(x,y)))retained++
        }
        return total to retained.toDouble()/max(1,total)
    }
    private fun geometryState():List<String> = main { a ->
        val layer=a.map!!.style!!.getLayer("building-3d") as FillExtrusionLayer
        listOf(layer.sourceId,layer.minZoom.toString(),layer.maxZoom.toString(),layer.filter.toString(),
            layer.fillExtrusionHeight.expression.toString(),layer.fillExtrusionBase.expression.toString(),
            a.activeLine!!.points.toString(),a.activeLine!!.color.toString(),a.activeLine!!.width.toString(),
            a.casingLine!!.points.toString(),a.casingLine!!.color.toString(),a.casingLine!!.width.toString())
    }
    private fun camera():JSONObject=main { a->val c=a.map!!.cameraPosition
        JSONObject().put("latitude",c.target!!.latitude).put("longitude",c.target!!.longitude)
            .put("zoom",c.zoom).put("tilt",c.tilt).put("bearing",c.bearing)
            .put("gpsLatitude",a.lastGps!!.lat).put("gpsLongitude",a.lastGps!!.lon)
            .put("routeStep",a.progress!!.stepIndex).put("metresToTurn",a.progress!!.stepDistanceRemaining)
    }

    @Test fun actualWoodlandsBeforeAfterKeepsBlueRouteAndOriginalGeometry() {
        load();val cases=JSONArray()
        for(night in listOf(false,true))for(bearing in listOf(22.0,125.0)) {
            main { it.night=night;it.move(bearing=bearing);it.before=true;it.applyBuildingStyle() }
            val unchanged=geometryState();val position=camera().toString()
            val name="${if(night)"night" else "day"}-bearing${bearing.toInt()}"
            val before=snapshot();screenshot("B886-BEFORE-$name.png")
            main { it.before=false;it.applyBuildingStyle() }
            val after=snapshot();screenshot("B886-AFTER-$name.png")
            assertEquals("Same route and unchanged geometry/base/heights/filter/zoom",unchanged,geometryState())
            assertEquals("Identical camera/GPS for before and after",position,camera().toString())
            main { a->val layer=a.map!!.style!!.getLayer("building-3d") as FillExtrusionLayer
                assertEquals(0.55f,layer.fillExtrusionOpacity.value!!,0.001f)
                assertNotNull("Data-driven colour parsed by native SDK",layer.fillExtrusionColor.expression)
                layer.setProperties(fillExtrusionOpacity(0f))
            }
            val withoutBuildings=snapshot()
            val (pixels,retained)=bluePreservation(withoutBuildings,after)
            image("B886-route-depth-control-$name.png",withoutBuildings)
            assertTrue("Native blue route core must be rendered",pixels>200)
            // Real pixel check: route is neither obscured nor recoloured by transparent extrusions.
            assertTrue("Blue route weakened by building depth: $retained",retained>=0.985)
            var differences=0;var roofAndWallPixels=0
            for(y in 0 until after.height)for(x in 0 until after.width) {
                if(after.getPixel(x,y)!=before.getPixel(x,y))differences++
                if(after.getPixel(x,y)!=withoutBuildings.getPixel(x,y))roofAndWallPixels++
            }
            assertTrue("Building appearance really changed",differences>2000)
            assertTrue("3D buildings remain visibly present",roofAndWallPixels>2000)
            main { it.applyBuildingStyle() }
            val features=main { a->a.map!!.queryRenderedFeatures(RectF(0f,0f,a.mapView.width.toFloat(),a.mapView.height.toFloat()),"building-3d") }
            assertTrue("Actual Woodlands building geometry rendered",features.isNotEmpty())
            cases.put(JSONObject().put("name",name).put("camera",JSONObject(position))
                .put("beforeOpacity",0.8).put("afterOpacity",0.55).put("visibleBuildingFeatures",features.size)
                .put("routeBlueCorePixels",pixels).put("unchangedBlueFraction",retained)
                .put("changedAppearancePixels",differences).put("retainedBuildingPixels",roofAndWallPixels)
                .put("geometryRouteAndHeightUnchanged",true))
        }
        val result=JSONObject().put("result","PASS").put("cases",cases).put("source",JSONObject(asset("source-manifest.json")))
            .put("nativeSdk","MapLibre Android 13.5.0").put("actualAndroidGpuRenderer",true)
            .put("productionBuildingPolicy",true).put("originalNativeRouteAnnotationApi",true)
            .put("offlineRecordedGeometry",true).put("physicalDrive",false).put("aiGeneratedScreenshots",false)
        save("B886-native-before-after-results.json",result.toString(2).toByteArray(),"application/json")
    }

    @Test fun stableColoursOfflineModesReloadAndPaintDoesNotCancelCameraAnimation() {
        load();main { it.before=false;it.night=true;it.applyBuildingStyle() }
        val baseGeometry=geometryState()
        val native=main { it.map!! }
        val style=main { native.style!! }
        val layer=main { style.getLayer("building-3d")!! }
        val ids=main { style.layers.map { it.id } }
        val sources=main { style.sources.map { it.id } }
        val palette=main { (layer as FillExtrusionLayer).fillExtrusionColor.expression.toString() }
        val modes=JSONArray()
        for((zoom,tilt,bearing) in listOf(Triple(16.0,60.0,290.0),Triple(17.5,0.0,90.0),Triple(17.15,58.0,22.0))) {
            main { it.move(zoom,tilt,bearing);it.applyBuildingStyle() };snapshot()
            main { a->assertSame(style,a.map!!.style);assertSame(layer,style.getLayer("building-3d"))
                assertEquals(palette,(layer as FillExtrusionLayer).fillExtrusionColor.expression.toString())
                assertEquals(ids,style.layers.map { it.id });assertEquals(sources,style.sources.map { it.id })
            }
            modes.put(JSONObject().put("zoom",zoom).put("tilt",tilt).put("bearing",bearing).put("sameLayersAndSources",true))
        }
        assertEquals(baseGeometry,geometryState())
        // Real GPS samples update persistent polylines, not building layers or colour seeds.
        val line=main { it.activeLine!! }
        gpsBeforeNext(5,30.0);gpsBeforeNext(5,25.0)
        main { assertSame(line,it.activeLine);assertSame(layer,it.map!!.style!!.getLayer("building-3d")) }
        // Change only paint while an actual native cinematic camera animation is running.
        val animation=CountDownLatch(1)
        val animationStarted=android.os.SystemClock.uptimeMillis()
        main { a->a.animationCancelled=false;a.animationFinished=false
            val target=CameraPosition.Builder(native.cameraPosition).zoom(16.2).tilt(55.0).bearing(65.0).build()
            native.animateCamera(CameraUpdateFactory.newCameraPosition(target),1800,object:MapLibreMap.CancelableCallback {
                override fun onCancel(){a.animationCancelled=true;animation.countDown()}
                override fun onFinish(){a.animationFinished=true;animation.countDown()}
            })
        }
        Thread.sleep(300)
        main { assertFalse("Animation must still be running when paint is applied",it.animationFinished)
            it.night=false;it.applyBuildingStyle() }
        assertTrue("Camera animation callback timed out",animation.await(15,TimeUnit.SECONDS))
        val animationMillis=android.os.SystemClock.uptimeMillis()-animationStarted
        assertTrue("Native animation must span the requested duration",animationMillis>=1400L)
        main { assertTrue(it.animationFinished);assertFalse(it.animationCancelled) }
        // Reload the locally stored map, then reapply through the same production entry point.
        main { it.night=true;it.reloadScene() }
        ui.waitUntil(30_000) { main { it.sceneReady } }
        main { a->assertEquals(0.55f,(a.map!!.style!!.getLayer("building-3d") as FillExtrusionLayer).fillExtrusionOpacity.value!!,0.001f)
            assertEquals(palette,(a.map!!.style!!.getLayer("building-3d") as FillExtrusionLayer).fillExtrusionColor.expression.toString())
            assertEquals(106,ErpStore876(a).load().records.size);assertEquals(101,ErpStore876(a).load().sites.size)
        }
        main { it.move() };snapshot();screenshot("B886-AFTER-offline-style-reload.png")
        save("B886-native-stability-results.json",JSONObject().put("result","PASS").put("modes",modes)
            .put("sameBuildingLayerAcrossGpsAndModeUpdates",true).put("sameRouteAnnotationAcrossGpsUpdates",true)
            .put("noNewNetworkSources",true).put("offlineStyleReload",true).put("animationFinished",true)
            .put("animationCancelled",false).put("animationElapsedMillis",animationMillis)
            .put("animationActiveWhenPaintChanged",true).put("erpRecords",106).put("erpSites",101)
            .put("dayNightChangesPaintOnly",true).toString(2).toByteArray(),"application/json")
    }
}
