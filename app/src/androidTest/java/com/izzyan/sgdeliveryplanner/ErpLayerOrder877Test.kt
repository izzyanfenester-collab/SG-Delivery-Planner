package com.izzyan.sgdeliveryplanner

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

class ErpLayerOrder877Test {
    @get:Rule(order=0) val permission=GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
    @get:Rule(order=1) val ui=createAndroidComposeRule<ErpOverspeedVerificationActivity877>()
    private fun <T> main(block:(ErpOverspeedVerificationActivity877)->T):T=ui.runOnIdle{block(ui.activity)}
    private fun ready(){ui.waitUntil(120000){main{it.ready && it.erp.catalog!=null && it.nativeMap?.style?.getLayer(ErpMapLayer876.LAYER)!=null}}}
    private fun snapshot():Bitmap {
        val done=CountDownLatch(1);var result:Bitmap?=null
        main{it.nativeMap!!.snapshot{image->result=image;done.countDown()}}
        assertTrue(done.await(15,TimeUnit.SECONDS));return requireNotNull(result)
    }
    private fun difference(before:Bitmap,after:Bitmap,point:LatLng):Pair<Int,Int> {
        val screen=main{it.nativeMap!!.projection.toScreenLocation(point)}
        val density=ui.activity.resources.displayMetrics.density
        val halfW=(20*density).toInt();val halfH=(7*density).toInt()
        var samples=0;var changed=0
        for(y in (screen.y.toInt()-halfH)..(screen.y.toInt()+halfH))for(x in (screen.x.toInt()-halfW)..(screen.x.toInt()+halfW)) {
            if(x in 0 until after.width && y in 0 until after.height) {
                samples++;val a=before.getPixel(x,y);val b=after.getPixel(x,y)
                if(abs(Color.red(a)-Color.red(b))+abs(Color.green(a)-Color.green(b))+abs(Color.blue(a)-Color.blue(b))>30)changed++
            }
        }
        return changed to samples
    }
    private fun settledSnapshot(point:LatLng):Bitmap {
        ui.waitUntil(15_000){main{it.nativeMap!!.queryRenderedFeatures(it.nativeMap!!.projection.toScreenLocation(point),ErpMapLayer876.LAYER).isNotEmpty()}}
        // Native symbol/tile fades can continue after a moveCamera callback.
        // Compare settled frames, rather than interpreting that fade as occlusion.
        Thread.sleep(500)
        var previous=snapshot()
        repeat(16) {
            Thread.sleep(300)
            val next=snapshot();val delta=difference(previous,next,point)
            previous.recycle()
            if(delta.first.toDouble()/delta.second<.005)return next
            previous=next
        }
        previous.recycle();error("Native ERP render did not settle")
    }
    @Test fun realNativeErpRemainsAboveRouteCasingAndNewRerouteLines() {
        val offline=ErpOverspeedEvidence877.verifyNetworkBlocked()
        ready()
        val site=main{a->a.erp.catalog!!.sites.maxBy{p->a.erp.catalog!!.sites.filter{it.key!=p.key}.minOf{navigationDistance(p.point,it.point)}}}
        val cases=JSONArray()
        val point=LatLng(site.point.lat,site.point.lon)
        main{a->a.nativeMap!!.locationComponent.forceLocationUpdate(Location("gps").apply {
            latitude=site.point.lat-.001;longitude=site.point.lon;accuracy=2f;speed=10f
            bearing=0f;time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
        })}
        for((name,pitch,bearing) in listOf(Triple("2d",0.0,0.0),Triple("2d-rotate",0.0,90.0),Triple("3d-rotate",54.0,135.0))) {
            for(night in listOf(false,true)) {
                main{a->val map=a.nativeMap!!;NavigationMapPalette.apply(map.style!!,night)
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(point).zoom(17.0).tilt(pitch).bearing(bearing).build()))}
                Thread.sleep(600)
                val before=settledSnapshot(point)
                val route=main{a->val map=a.nativeMap!!
                    listOf(map.addPolyline(PolylineOptions().add(LatLng(point.latitude-.002,point.longitude),LatLng(point.latitude+.002,point.longitude)).color(Color.rgb(4,51,123)).width(17f)),
                        map.addPolyline(PolylineOptions().add(LatLng(point.latitude-.002,point.longitude),LatLng(point.latitude+.002,point.longitude)).color(Color.rgb(0,132,255)).width(11f)))}
                Thread.sleep(600)
                val after=settledSnapshot(point)
                val (changed,samples)=difference(before,after,point)
                val order=main{a->a.nativeMap!!.style!!.layers.map{it.id}}
                ErpOverspeedEvidence877.png("ERP-$name-${if(night)"night" else "day"}-before.png",before)
                ErpOverspeedEvidence877.png("ERP-$name-${if(night)"night" else "day"}.png",after)
                ErpOverspeedEvidence877.json("ERP-$name-${if(night)"night" else "day"}-order.json",JSONObject().put("layers",JSONArray(order)).put("changed",changed).put("pixels",samples))
                val erp=order.indexOf(ErpMapLayer876.LAYER)
                val annotations=order.indexOfFirst{it.endsWith(".annotations.points")}
                assertTrue(annotations>=0 && erp>annotations)
                assertTrue(order.withIndex().filter{it.value.contains(".annotations.shape.")}.all{it.index<erp})
                val vehicle=order.withIndex().filter{it.value.contains("location") && (it.value.contains("foreground")||it.value.contains("bearing"))}
                assertTrue("Native vehicle stack absent",vehicle.isNotEmpty())
                assertTrue("Native vehicle must stay above ERP",vehicle.all{it.index>erp})
                assertTrue("Route/casing obscured ERP in $name night=$night: $changed/$samples",changed.toDouble()/samples<.02)
                assertEquals(101,main{it.erp.catalog!!.sites.size})
                cases.put(JSONObject().put("mode",name).put("night",night).put("changedPixels",changed).put("pixels",samples)
                    .put("erpAboveRouteAndCasing",true).put("vehicleAboveErp",true))
                main{a->route.forEach{a.nativeMap!!.removePolyline(it)}}
                before.recycle();after.recycle()
            }
        }
        main{a->a.nativeMap!!.addMarker(MarkerOptions().position(LatLng(point.latitude+.0002,point.longitude))
            .icon(CameraSafetyMapIcons.icon(a,"FIXED_SPEED_CAMERA")))}
        assertTrue(main{it.nativeMap!!.markers.isNotEmpty()})
        // Style reload must rebuild exactly one ERP source/layer, not stale positions.
        main{it.reloadMap()};ready()
        val stats=main{a->val layer=ErpMapLayer876(a,a.nativeMap!!);repeat(100){layer.sync(a.nativeMap!!.style!!,a.erp.catalog!!,true)}
            layer.sourceWrites to layer.layerInstalls}
        assertEquals(1,stats.first);assertEquals(0,stats.second)
        ErpOverspeedEvidence877.json("erp-layer-results.json",JSONObject().put("result","PASS").put("network",offline)
            .put("cases",cases).put("records",106).put("locations",101).put("styleReload",true)
            .put("cameraMarkersRetained",true).put("repeatSyncCalls",100).put("repeatLayerInstalls",stats.second))
    }
}
