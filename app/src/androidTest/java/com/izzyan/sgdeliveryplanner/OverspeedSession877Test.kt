package com.izzyan.sgdeliveryplanner

import android.Manifest
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.lifecycleScope
import androidx.test.rule.GrantPermissionRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Controlled Android Location replay enters the actual session's asynchronous
 * road-catalog lookup. No fake limits, UI-only state override or preview playback.
 * Location-provider delivery and a physical drive are outside this fixture.
 */
class OverspeedSession877Test {
    @get:Rule(order=0) val permission=GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
    @get:Rule(order=1) val ui=createAndroidComposeRule<ErpOverspeedVerificationActivity877>()
    private fun <T> main(block:(ErpOverspeedVerificationActivity877)->T):T=ui.runOnIdle{block(ui.activity)}
    private fun raw(session:NavigationSession,name:String):Any?=session.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(session)
    @Suppress("UNCHECKED_CAST")
    private fun set(session:NavigationSession,name:String,value:Any?) {
        val delegate=runCatching{session.javaClass.getDeclaredField(name+'$'+"delegate").apply{isAccessible=true}}.getOrNull()
        if(delegate!=null)(delegate.get(session) as MutableState<Any?>).value=value
        else session.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(session,value)
    }
    private data class VerifiedSite(val a:GpsPoint,val b:GpsPoint,val road:String,val kmh:Int) {
        val point get()=GpsPoint((a.lat+b.lat)/2,(a.lon+b.lon)/2)
        val heading get()=((Math.toDegrees(atan2((b.lon-a.lon)*cos(Math.toRadians(a.lat)),b.lat-a.lat))+360)%360).toFloat()
    }
    private fun site(kmh:Int):VerifiedSite {
        val app=ErpOverspeedEvidence877.app
        RoadSpeedStore875(app).use{it.ensureBundled()}
        SQLiteDatabase.openDatabase(File(app.noBackupFilesDir,"road-speed-875/current.db").path,null,SQLiteDatabase.OPEN_READONLY).use{db->
            db.rawQuery("SELECT e.lat1,e.lon1,e.lat2,e.lon2,w.name FROM road_edge e JOIN road_way w ON w.id=e.way_id WHERE e.verified_kmh=? AND w.forward_allowed=1 AND w.backward_allowed=0",arrayOf(kmh.toString())).use{c->
                while(c.moveToNext()) {
                    val row=VerifiedSite(GpsPoint(c.getDouble(0),c.getDouble(1)),GpsPoint(c.getDouble(2),c.getDouble(3)),c.getString(4),kmh)
                    if(navigationDistance(row.a,row.b)>40)return row
                }
            }
        }
        error("Verified $kmh km/h record missing from unchanged baseline catalog")
    }
    private fun feed(site:VerifiedSite,speed:Double):Long {
        val now=SystemClock.elapsedRealtime()
        val fix=Location("gps").apply {
            latitude=site.point.lat;longitude=site.point.lon;accuracy=2f
            this.speed=(speed/3.6).toFloat();speedAccuracyMetersPerSecond=.2f
            bearing=site.heading;bearingAccuracyDegrees=2f
            time=System.currentTimeMillis();elapsedRealtimeNanos=now*1_000_000
        }
        main{a->set(a.session,"location",fix)
            a.nativeMap?.locationComponent?.forceLocationUpdate(fix)
            a.session.javaClass.getDeclaredMethod("updateRoadSpeed875",Location::class.java).apply{isAccessible=true}.invoke(a.session,fix)}
        ui.waitUntil(10_000){main{it.session.roadSpeedLimitState875.observedAtMs==now || it.session.roadSpeedLimitState875.limit==null}}
        return now
    }
    private fun red(tag:String):Int {
        val image=ui.onNodeWithTag(tag).captureToImage().toPixelMap();var count=0
        for(y in 0 until image.height)for(x in 0 until image.width){val p=image[x,y];if(p.red>.7f && p.green<.45f && p.blue<.45f)count++}
        return count
    }
    @Test fun liveSessionRoadLookupChangesRealTextAndPlaysAuthenticOverspeedOnce() {
        val blocked=ErpOverspeedEvidence877.verifyNetworkBlocked()
        ui.waitUntil(120_000){main{it.ready && it.erp.catalog!=null}}
        val eighty=site(80);val ninety=site(90)
        val fixture=ErpOverspeedEvidence877.instrumentation.context.assets.open("valhalla-singapore-877-fixture.json").bufferedReader().use{it.readText()}
        val route=parseNavigationRoute(fixture,GpsPoint(1.35,103.8),GpsPoint(1.36,103.81))
        main{a->
            val audio=a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,maxOf(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/2),0)
            a.session.setSoundEnabled(true);a.session.changeVoiceVolume(1f)
            a.session.updateOverspeedOptions877(OverspeedOptions877())
            set(a.session,"scope",a.lifecycleScope);set(a.session,"active",true)
            set(a.session,"route",route);set(a.session,"shape",listOf(eighty.a,eighty.b))
            set(a.session,"turnMeters",1000.0)
        }
        repeat(3){feed(eighty,75.0);Thread.sleep(1000)}
        assertEquals(80,main{it.session.roadSpeedLimitState875.limit?.kmh})
        assertEquals(RoadLimitSource875.OFFICIAL,main{it.session.roadSpeedLimitState875.limit?.kind})
        assertFalse(main{it.session.overspeed877.red})
        assertEquals(0,red("navigation-current-speed"));assertEquals(0,red("navigation-speed-unit"))
        ErpOverspeedEvidence877.shell("logcat -c")
        // Hold urgent guidance across the two-second overspeed confirmation.
        main{a->val audio=raw(a.session,"voiceAudio") as NavigationVoiceAudio
            assertTrue(audio.play(VoicePhrase("critical-turn-replay-877",VoicePriority.CRITICAL,
                List(3){VoicePart("turn_right","Critical right turn replay")})))}
        var deferred=false;var becameRed=false
        repeat(15) {
            feed(eighty,86.0);Thread.sleep(1000)
            deferred=deferred || main{it.session.overspeed877.state.confirmed && it.session.overspeed877.diagnostic.startsWith("DEFERRED")}
            becameRed=becameRed || main{it.session.overspeed877.red}
        }
        assertTrue("Critical instruction did not retain priority",deferred)
        assertTrue(becameRed)
        assertTrue(red("navigation-current-speed")>4);assertTrue(red("navigation-speed-unit")>4)
        val log=ErpOverspeedEvidence877.shell("logcat -d -v brief -s SGPanda866:V SGOverspeed877:V")
        val starts=log.lineSequence().count{it.contains("PLAYBACK_STARTED") && it.contains("key=overspeed-877-")}
        assertEquals("Continuous overspeed must not spam",1,starts)
        assertTrue(log.contains("COMPLETED: overspeed_warning.mp3"))
        repeat(3){feed(eighty,79.0);Thread.sleep(1000)}
        assertFalse(main{it.session.overspeed877.red})
        assertEquals(0,red("navigation-current-speed"));assertEquals(0,red("navigation-speed-unit"))
        main{set(it.session,"shape",listOf(ninety.a,ninety.b))}
        repeat(5){feed(ninety,96.0);Thread.sleep(1000)}
        assertEquals(90,main{it.session.roadSpeedLimitState875.limit?.kmh})
        assertTrue(main{it.session.overspeed877.red})
        // Unknown location immediately clears a previous verified-road warning.
        val unknown=VerifiedSite(GpsPoint(1.45,103.63),GpsPoint(1.451,103.63),"Unmatched location fixture",0)
        feed(unknown,96.0);Thread.sleep(500)
        assertFalse(main{it.session.overspeed877.red})
        assertNull(main{it.session.roadSpeedLimitState875.limit})
        ErpOverspeedEvidence877.save("navigation-speed-playback.log",log.toByteArray(),"text/plain")
        ErpOverspeedEvidence877.json("overspeed-session-results.json",JSONObject().put("result","PASS")
            .put("network",blocked).put("input","controlled Android Location replay through NavigationSession.updateRoadSpeed875")
            .put("verified80Road",eighty.road).put("verified90Road",ninety.road)
            .put("normal75",true).put("red86",becameRed).put("redNumberAndUnitPixels",true)
            .put("normal79Restored",true).put("red96On90",true).put("unknownNoWarning",true)
            .put("criticalGuidanceDeferredOverspeed",deferred).put("overspeedPlaybackStarted",true)
            .put("overspeedPlaybackCompleted",true).put("continuousVoiceStartCount",starts)
            .put("realGpsProviderAndPhysicalDriveTested",false))
        main{a->(raw(a.session,"roadSpeedStore875") as RoadSpeedStore875).close()}
    }
}
