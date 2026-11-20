package com.izzyan.sgdeliveryplanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.location.Location
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*

@RunWith(AndroidJUnit4::class)
class ErpNativeMap876Test {
    @get:Rule val rule=createAndroidComposeRule<ErpMapVerificationActivity876>()
    private fun <T> main(block:(ErpMapVerificationActivity876)->T):T {
        var answer:Any?=null;rule.activityRule.scenario.onActivity{answer=block(it)}
        @Suppress("UNCHECKED_CAST") return answer as T
    }
    private fun ready(){rule.waitUntil(120000){main{it.ready && it.erpState.catalog!=null && it.nativeMap?.style?.getLayer(ErpMapLayer876.LAYER)!=null}}}
    private fun folder()=File(rule.activity.filesDir,"erp-876-native-evidence").apply{mkdirs()}
    private fun snapshot(name:String):Bitmap {
        val done=CountDownLatch(1);var bitmap:Bitmap?=null
        main{it.nativeMap!!.snapshot{b->bitmap=b;done.countDown()}}
        assertTrue("Native render snapshot timeout",done.await(30,TimeUnit.SECONDS))
        val image=requireNotNull(bitmap)
        File(folder(),name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        return image
    }
    private fun move(point:GpsPoint,zoom:Double,pitch:Double,bearing:Double){
        main{it.nativeMap!!.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(point.lat,point.lon)).zoom(zoom).tilt(pitch).bearing(bearing).build()))}
        Thread.sleep(450)
        rule.waitForIdle()
    }
    @Test fun offlineNativeRenderingAnchoringVisibilitySelectionAndLayerPreservation(){
        ready()
        val catalog=main{it.erpState.catalog!!};assertEquals(106,catalog.records.size);assertEquals(101,catalog.sites.size)
        val observations=JSONArray()
        // No network basemap: the actual native SDK renders all supplied locations.
        main{a->
            a.nativeMap!!.style!!.getLayer("mapbox-location-foreground-layer")!!.setProperties(visibility(Property.NONE))
            val bounds=LatLngBounds.Builder().includes(catalog.sites.map{LatLng(it.point.lat,it.point.lon)}).build()
            a.nativeMap!!.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds,100))
        }
        Thread.sleep(600)
        val all=main{a->a.nativeMap!!.queryRenderedFeatures(RectF(0f,0f,a.mapView.width.toFloat(),a.mapView.height.toFloat()),ErpMapLayer876.LAYER)
            .map{it.getStringProperty("site_key")}.toSet()}
        assertEquals(catalog.byKey.keys,all)
        snapshot("B876-offline-all-101-locations.png")
        val target=catalog.sites.maxByOrNull{s->catalog.sites.filter{it.key!=s.key}.minOf{navigationDistance(s.point,it.point)}}!!
        val modes=listOf(Triple(16.0,0.0,0.0),Triple(17.0,0.0,90.0),Triple(16.0,54.0,135.0))
        for((i,mode)in modes.withIndex()){
            move(target.point,mode.first,mode.second,mode.third)
            for(night in listOf(false,true)){
                main{a->(a.nativeMap!!.style!!.getLayer("local-test-background") as BackgroundLayer)
                    .setProperties(backgroundColor(if(night)Color.rgb(16,44,64) else Color.rgb(223,233,229)))
                    NavigationMapPalette.apply(a.nativeMap!!.style!!,night)
                }
                Thread.sleep(250)
                val at=main{it.nativeMap!!.projection.toScreenLocation(LatLng(target.point.lat,target.point.lon))}
                val hits=main{it.nativeMap!!.queryRenderedFeatures(at,ErpMapLayer876.LAYER).map{it.getStringProperty("site_key")}}
                assertTrue("ERP center anchoring failed in mode $i",target.key in hits)
                val image=snapshot("B876-native-${if(mode.second==0.0)"2D" else "3D"}-z${mode.first.toInt()}-b${mode.third.toInt()}-${if(night)"night" else "day"}.png")
                val density=rule.activity.resources.displayMetrics.density
                val points=ArrayList<Pair<Int,Int>>()
                for(y in max(0,(at.y-18*density).toInt())..min(image.height-1,(at.y+18*density).toInt()))
                    for(x in max(0,(at.x-32*density).toInt())..min(image.width-1,(at.x+32*density).toInt())){
                        val c=image.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
                        if(r<60 && g in 35..110 && b in 85..180 && b>g+20 && b>r*2)points+=x to y
                    }
                assertTrue("Blue/white original ERP image not visible",points.size>30)
                val width=(points.maxOf{it.first}-points.minOf{it.first}+1)/density
                val height=(points.maxOf{it.second}-points.minOf{it.second}+1)/density
                assertEquals("Constant 42dp sign width",42f,width,3f)
                assertEquals("Original 2.4:1 aspect ratio",17.5f,height,3f)
                observations.put(JSONObject().put("zoom",mode.first).put("pitch",mode.second).put("bearing",mode.third)
                    .put("night",night).put("rendered_width_dp",width).put("rendered_height_dp",height).put("anchor_hit",true))
            }
        }
        // Exercise the same controller repeatedly: neither source nor layer is recreated.
        val stats=main{a->val controller=ErpMapLayer876(a,a.nativeMap!!)
            repeat(100){controller.sync(a.nativeMap!!.style!!,catalog,true)}
            controller.sourceWrites to controller.layerInstalls}
        assertEquals(1,stats.first);assertEquals(0,stats.second)
        // Real Compose visibility control changes only this native layer.
        main{a->a.nativeMap!!.addPolyline(PolylineOptions().add(LatLng(1.32,103.91),LatLng(1.33,103.92)).color(Color.rgb(0,132,255)).width(11f))
            a.nativeMap!!.addMarker(MarkerOptions().position(LatLng(1.32,103.91)).icon(CameraSafetyMapIcons.icon(a,"FIXED_SPEED_CAMERA")))
        }
        rule.onNode(isToggleable()).performClick()
        rule.waitUntil(10000){main{it.nativeMap!!.style!!.getLayer(ErpMapLayer876.LAYER)!!.visibility.value==Property.NONE}}
        main{a->assertNotNull(a.nativeMap!!.style!!.getSource(ErpMapLayer876.SOURCE));assertEquals(1,a.nativeMap!!.markers.size);assertEquals(1,a.nativeMap!!.polylines.size)}
        assertFalse(ErpPreferences876(rule.activity).visible())
        rule.onNode(isToggleable()).performClick()
        rule.waitUntil(10000){main{it.nativeMap!!.style!!.getLayer(ErpMapLayer876.LAYER)!!.visibility.value==Property.VISIBLE}}
        // Inspect a real shared coordinate through the production map click listener/card.
        val shared=catalog.sites.single{s->s.records.any{it.uniqueId=="627"}}
        move(shared.point,18.0,0.0,0.0)
        main { activity ->
            fun stationaryFix(ageMillis: Long): Location = Location("stationary-test").apply {
                latitude = shared.point.lat
                longitude = shared.point.lon
                accuracy = 3f
                speed = 0f
                speedAccuracyMetersPerSecond = .2f
                elapsedRealtimeNanos = (SystemClock.elapsedRealtime() - ageMillis) * 1_000_000L
            }
            activity.erpState.observe(stationaryFix(3000L))
            activity.erpState.observe(stationaryFix(0L))
        }
        val tapPixel=main{it.nativeMap!!.projection.toScreenLocation(LatLng(shared.point.lat,shared.point.lon))}
        rule.onRoot().performTouchInput{click(Offset(tapPixel.x,tapPixel.y))}
        // Both records are present in the native marker's scrollable details dialog.
        // On shorter emulator viewports, scroll each record into view before asserting visibility.
        rule.onNodeWithText("Unique ID: 627").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Unique ID: 838").performScrollTo().assertIsDisplayed()
        val screen=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(folder(),"B876-shared-record-information-card.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)}
        rule.onNodeWithText("Done").performClick()
        main{a->val moving=Location("moving-test").apply{latitude=shared.point.lat;longitude=shared.point.lon;accuracy=3f;speed=8f;speedAccuracyMetersPerSecond=.2f;elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()};a.erpState.observe(moving)}
        rule.onRoot().performTouchInput{click(Offset(tapPixel.x,tapPixel.y))}
        rule.onNodeWithText("ERP GANTRY").assertDoesNotExist()
        val order=main{a->val layers=a.nativeMap!!.style!!.layers.map{it.id};layers.indexOf(ErpMapLayer876.LAYER)<layers.indexOf("mapbox-location-foreground-layer")}
        assertTrue("Vehicle arrow must render above ERP",order)
        File(folder(),"B876-native-map-results.json").writeText(JSONObject().put("records",106).put("locations",101)
            .put("all_101_rendered_offline",true).put("modes",observations).put("repeat_sync_calls",100)
            .put("repeat_source_writes",stats.first).put("repeat_layer_installs",stats.second)
            .put("visibility_toggle",true).put("camera_route_retained",true).put("shared_record_selection",true)
            .put("moving_selection_blocked",true).put("vehicle_arrow_above_erp",order)
            .put("map_style","local source-free renderer, not a physical road-driving session").toString(2))
    }
}
