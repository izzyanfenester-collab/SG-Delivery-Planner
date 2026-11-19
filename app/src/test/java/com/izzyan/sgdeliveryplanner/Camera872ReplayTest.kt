package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

/** Synthetic GPS traces exercise production matching/planning, not physical driving. */
class Camera872ReplayTest {
    private val prefs=CameraAlertPreferences()
    private fun point(x:Double,y:Double=0.0)=GpsPoint(1.3+y/111_195.0,
        103.75+x/(111_195.0*cos(Math.toRadians(1.3))))
    private val route=listOf(point(0.0),point(8_000.0))
    private fun camera(category:String="FIXED_SPEED_CAMERA",id:String=category)=EnforcementCamera(
        id,category,"Synthetic verified approach",point(5_000.0),"Camera Road","End Road",null,
        "https://www.police.gov.sg/test","2026-11-19",90.0,true,null,null,null,
        if(category=="RED_LIGHT_SPEED_CAMERA")"VERIFIED_SPEED_ENFORCEMENT" else "PUBLISHED_LOCATION_NOT_LIVE_STATUS",
        if(category=="AVERAGE_SPEED_ZONE")"SYNTHETIC-ZONE" else null,
        averageZoneEntryVerified=category=="AVERAGE_SPEED_ZONE",
        zoneEvidenceUrl=if(category=="AVERAGE_SPEED_ZONE")"https://www.police.gov.sg/test-zone-evidence" else null,
        speedEnforcementEvidenceUrl=if(category=="RED_LIGHT_SPEED_CAMERA")"https://www.police.gov.sg/test-dual-evidence" else null)
    private val categories=mapOf("FIXED_SPEED_CAMERA" to "speed_camera_ahead",
        "LTA_SPEED_CAMERA" to "speed_camera_ahead","MOBILE_ENFORCEMENT_LOCATION" to "mobile_speed_camera",
        "POLICE_LASER_LOCATION" to "police_speed_laser","AVERAGE_SPEED_ZONE" to "average_speed_zone_ahead",
        "RED_LIGHT_CAMERA" to "red_light_camera_ahead","RED_LIGHT_SPEED_CAMERA" to "red_light_possible_speed")
    private fun scan(cam:EnforcementCamera,distance:Double)=CameraApproachLogic.find(
        point(5_000.0-distance),route,"Camera Road",listOf("End Road"),6f,listOf(cam),16f,90.0,5f).single()
    private fun due(p:CameraVoicePlanner865,a:CameraApproach)=p.due(a,prefs,true,true,800.0,16f)
    private fun started(p:CameraVoicePlanner865,cue:CameraVoiceCue865) {
        p.markRequested(cue)
        p.playbackEvent(cue,PandaPlaybackEvent866(cue.phrase.key,PandaPlaybackState866.STARTED,"fixture-start",1000L))
    }

    @Test fun everyCategoryCrosses2000WithoutExactCoordinateOrKnownLimit() {
        for((category,clip) in categories) {
            val p=CameraVoicePlanner865();val cam=camera(category)
            for(d in listOf(3000.0,2500.0,2100.0))assertNull("$category at $d",due(p,scan(cam,d)))
            val cue=due(p,scan(cam,1920.0))!!
            assertEquals(category, cue.category);assertEquals(clip,cue.clipId)
            assertTrue(cue.crossedThreshold);assertEquals(2000.0,cue.warningThresholdMetres!!,0.001)
            assertEquals(2100.0,cue.previousMetresAhead!!,0.1)
            assertNull(cam.speedLimitKmh)
            println("B872 GPS_REPLAY category=$category previous=${cue.previousMetresAhead} current=${cue.metresAhead} threshold=2000 crossed=true mp3=$clip.mp3 event=GENERATED fixture=true")
            started(p,cue)
            assertNull(due(p,scan(cam,1500.0)))
        }
    }
    @Test fun largeSampleJumpAndInitialInsideThresholdWorkForEveryCategory() {
        for(category in categories.keys) {
            val cam=camera(category);val p=CameraVoicePlanner865()
            assertNull(due(p,scan(cam,2300.0)))
            assertTrue(due(p,scan(cam,1800.0))!!.crossedThreshold)
            val fresh=due(CameraVoicePlanner865(),scan(cam,1500.0))!!
            assertNull(fresh.previousMetresAhead);assertEquals("main",fresh.stage)
        }
    }
    @Test fun eventIsGeneratedAtThresholdEvenDuringCriticalTurnAndRetriedAfterwards() {
        val p=CameraVoicePlanner865();val cam=camera()
        assertNull(due(p,scan(cam,2180.0)))
        val cue=p.due(scan(cam,1920.0),prefs,true,true,60.0,16f)!!
        assertTrue(cue.crossedThreshold)
        assertNotNull(CameraVoicePriority872.deferredReason(60.0,VoicePriority.CRITICAL,"turn"))
        assertNull(CameraVoicePriority872.deferredReason(600.0,null,"turn"))
        assertNotNull(due(p,scan(cam,1800.0)))
        assertNull(CameraVoicePriority872.deferredReason(60.0,null,"continue"))
    }
    @Test fun explicitOppositeHeadingAndParallelRoadNeverProduceCameraVoice() {
        val cam=camera().copy(directionBearing=270.0)
        val a=scan(cam,1900.0)
        assertFalse(a.directionConfirmed);assertFalse(a.genericSafetyEligible)
        assertNull(due(CameraVoicePlanner865(),a))
        assertTrue(CameraApproachLogic.find(point(3100.0),route,"Parallel Road",listOf("End Road"),
            6f,listOf(camera()),16f,90.0,5f).isEmpty())
        assertTrue(CameraApproachLogic.find(point(3100.0),route,"Camera Road",listOf("End Road"),
            6f,listOf(camera().copy(point=point(5000.0,40.0))),16f,90.0,5f).isEmpty())
        assertTrue(CameraApproachLogic.find(point(5100.0),route,"Camera Road",listOf("End Road"),
            6f,listOf(camera()),16f,90.0,5f).isEmpty())
    }
    @Test fun sameCoordinatesOnUnrelatedFlyoverRoadAreRejected() {
        val road=CameraRoadCorridor872("Elevated Road",0.0,8000.0)
        assertTrue(CameraApproachLogic.find(point(3100.0),route,"Elevated Road",listOf("End Road"),
            6f,listOf(camera()),16f,90.0,5f,listOf(road)).isEmpty())
    }
    @Test fun primaryWarnsOnLaterRouteRoadBeforeRunnerJoinsIt() {
        val corridors=listOf(CameraRoadCorridor872("Feeder Road",0.0,4000.0),
            CameraRoadCorridor872("Camera Road",4000.0,8000.0))
        val approach=CameraApproachLogic.find(point(3100.0),route,"Feeder Road",listOf("End Road"),
            6f,listOf(camera()),16f,90.0,5f,corridors).single()
        assertEquals("Camera Road",approach.matchedRoad)
        assertEquals("speed_camera_ahead",due(CameraVoicePlanner865(),approach)?.clipId)
    }
    @Test fun straightLineRadiusCannotReplaceLongCurvedRouteDistance() {
        val path=listOf(point(0.0),point(1000.0),point(1000.0,1100.0),point(0.0,1100.0))
        val cam=camera().copy(point=path.last(),directionBearing=270.0)
        val approach=CameraApproachLogic.find(path.first(),path,"Camera Road",listOf("End Road"),
            6f,listOf(cam),16f,90.0,5f).single()
        assertTrue(approach.routeMetresAhead>3000)
        assertNull(due(CameraVoicePlanner865(),approach))
    }
    @Test fun briefMissingRoadRecoversRealRouteDistanceAndExpiresSafely() {
        val r=CameraRouteRecovery872();val cam=camera()
        val corridors=listOf(CameraRoadCorridor872("Camera Road",0.0,8000.0))
        fun tick(x:Double,time:Long,shape:List<GpsPoint>,road:String?)=r.scan(point(x),time,shape,
            road,listOf("End Road"),6f,16f,90.0,5f,listOf(cam),corridors)
        assertEquals(1,tick(2800.0,1000,route,"Camera Road").size)
        val recovered=tick(3100.0,2000,emptyList(),null).single()
        assertTrue(recovered.coordinateFallbackUsed)
        assertEquals(1900.0,recovered.routeMetresAhead,0.1)
        assertEquals("speed_camera_ahead",due(CameraVoicePlanner865(),recovered)?.clipId)
        assertTrue(tick(3300.0,20_000,emptyList(),null).isEmpty())
        r.reset();assertTrue(tick(3400.0,21_000,emptyList(),null).isEmpty())
    }
    @Test fun averageZoneRequiresEvidenceAndUsesOneEntranceThenEntryAndExit() {
        val p=CameraVoicePlanner865();val entry=camera("AVERAGE_SPEED_ZONE")
        val unknown=entry.copy(zoneEvidenceUrl=null)
        assertNull(due(p,scan(unknown,1800.0)))
        assertTrue(p.reason(scan(unknown,1800.0),prefs).contains("evidence-missing"))
        val ahead=due(p,scan(entry,1900.0))!!;started(p,ahead)
        val inside=CameraApproach(entry,-3.0,true,true)
        val enter=due(p,inside)!!;assertEquals("average_speed_zone_enter",enter.clipId);started(p,enter)
        val exit=entry.copy(id="ZONE-EXIT",averageZoneEntryVerified=false,averageZoneExitVerified=true)
        assertNull(due(p,CameraApproach(exit,80.0,true,true)))
        val out=due(p,CameraApproach(exit,-3.0,true,true))!!
        assertEquals("average_speed_zone_exit",out.clipId);started(p,out)
        assertNull(due(p,CameraApproach(exit,-10.0,true,true)))
    }
    @Test fun verifiedDualVoiceNeverLeaksToUnverifiedRedLightRecord() {
        val verified=camera("RED_LIGHT_SPEED_CAMERA")
        assertEquals("red_light_possible_speed",due(CameraVoicePlanner865(),scan(verified,1900.0))?.clipId)
        val unknown=verified.copy(speedEnforcementEvidenceUrl=null)
        assertEquals("red_light_camera_ahead",due(CameraVoicePlanner865(),scan(unknown,1900.0))?.clipId)
    }
    @Test fun groupedPhysicalApproachKeepsIdentityWhenOneCoordinateFallsBehind() {
        val a=camera(id="A");val b=camera(id="B").copy(point=point(5005.0))
        val records=listOf(a,b)
        val first=CameraVoiceGroup867.combine(listOf(CameraApproach(a,1900.0,true,true),
            CameraApproach(b,1905.0,true,true)),records).single()
        val p=CameraVoicePlanner865();started(p,due(p,first)!!)
        val last=CameraVoiceGroup867.combine(listOf(CameraApproach(b,30.0,true,true)),records).single()
        assertEquals(first.voiceGroupId,last.voiceGroupId)
        assertNull(p.due(last,prefs.copy(coordinateReminder=false),true,true,800.0,16f))
    }
    @Test fun settingsPreserveOptOutsAndFailureDoesNotCommitPrimary() {
        val cam=camera();val a=scan(cam,1900.0);val p=CameraVoicePlanner865()
        assertNull(p.due(a,prefs.copy(master=false),true,true,800.0,16f))
        assertNull(p.due(a,prefs.copy(speed=false),true,true,800.0,16f))
        val cue=due(p,a)!!;p.markRequested(cue)
        assertFalse(p.isStarted(cue))
        p.playbackEvent(cue,PandaPlaybackEvent866(cue.phrase.key,PandaPlaybackState866.FAILED,"decode-failed",2000))
        assertNotNull(due(p,scan(cam,1800.0)))
        p.reset();assertNotNull(due(p,scan(cam,1500.0)))
    }
    @Test fun coLocatedMarkersKeepAnOriginalCoordinateAndOtherCategoriesStaySeparate() {
        val a=camera(id="A");val b=camera(id="B").copy(point=point(5005.0))
        val red=camera("RED_LIGHT_CAMERA",id="RED")
        val sites=CameraMarkerSites872.group(listOf(b,red,a))
        assertEquals(2,sites.size)
        val speed=sites.single{it.camera.category=="FIXED_SPEED_CAMERA"}
        assertEquals("A",speed.camera.id);assertEquals(a.point,speed.camera.point)
        assertEquals(2,speed.recordCount)
    }
}
