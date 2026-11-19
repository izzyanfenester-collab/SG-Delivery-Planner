package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

/** Build #865 isolated trigger-fix replay: no real driving/device certification. */
class CameraVoiceTriggerFix865Test {
    private val route=listOf(GpsPoint(1.3000,103.8000),GpsPoint(1.3000,103.8100))
    private val gps=GpsPoint(1.3000,103.8035)
    private fun camera(
        id:String="SPF-TEST", category:String="RED_LIGHT_CAMERA",
        road:String="Pan Island Expressway", target:String?="Tuas",
        junction:String?="Near Junction",point:GpsPoint=GpsPoint(1.3000,103.8050)
    )=EnforcementCamera(
        id,category,"SPF test publication",point,road,target,junction,
        "https://www.police.gov.sg/test","2026-07-01",
        null,false,null,null,null,"PUBLISHED_LOCATION_NOT_LIVE_STATUS",null
    )
    private fun search(cameras:List<EnforcementCamera>, road:String="PIE",
        bearing:Double?=90.0,accuracy:Float=8f,upcoming:List<String> = listOf("PIE"))=
        CameraApproachLogic.find(gps,route,road,upcoming,accuracy,cameras,12f,
            bearing,10f)

    @Test fun originalPieAbbreviationNoLongerBlocksRouteRoadMatching() {
        val result=search(listOf(camera()))
        assertEquals(1,result.size)
        assertTrue(result.single().genericSafetyEligible)
        assertFalse(result.single().directionConfirmed)
        assertFalse(result.single().alertAllowed)
    }
    @Test fun unknownCameraBearingOnlyPermitsGenericSafetyRecording() {
        val cam=camera()
        val a=search(listOf(cam)).single()
        val cue=CameraVoicePlanner865().due(a,CameraAlertPreferences(),
            true,true,800.0,12f)
        assertEquals("red_light_camera_near",cue?.clipId)
        assertEquals("safety",cue?.stage)
        assertFalse(cue!!.phrase.parts.any{it.clip=="red_light_camera_ahead"})
    }
    @Test fun noSafetyCueWithoutStableGenuineHeadingOrAccurateGeometry() {
        assertTrue(search(listOf(camera()),bearing=270.0).isEmpty())
        assertFalse(search(listOf(camera()),bearing=null).single().genericSafetyEligible)
        assertFalse(search(listOf(camera()),accuracy=20f).single().genericSafetyEligible)
        assertTrue(search(listOf(camera()),road="Adjacent Flyover").isEmpty())
    }
    @Test fun publishedTargetMatchedToUpcomingActualRoadAllowsOriginalCameraClip() {
        val cam=camera(target="Farrer Rd",road="Adam Rd")
        val a=search(listOf(cam),road="Adam Road",upcoming=listOf("Farrer Road"))
            .single()
        assertTrue(a.directionConfirmed)
        assertTrue(a.alertAllowed)
        val cue=CameraVoicePlanner865().due(a,CameraAlertPreferences(),
            true,true,900.0,12f)
        assertEquals("red_light_camera_ahead",cue?.clipId)
    }
    @Test fun anotherSpfPositionCorroboratesDirectionWithoutClaimingOfficialBearing() {
        val cam=camera(road="Alpha Rd",target="Target Ave")
        val corroborator=camera(id="SPF-TARGET",road="Alpha Road",
            target="Further Rd",junction="Target Avenue",
            point=GpsPoint(1.3000,103.8100))
        val a=search(listOf(cam,corroborator),road="Alpha Road",
            upcoming=listOf("Different Road")).first {it.camera.id=="SPF-TEST"}
        assertTrue(a.directionConfirmed)
        assertNull(a.camera.directionBearing) // Never invented or stored as verified.
        assertTrue(a.alertAllowed)
    }
    @Test fun oppositeDrivingDirectionCannotClaimSpecificCamera() {
        val backwards=listOf(GpsPoint(1.3000,103.8100),GpsPoint(1.3000,103.8000))
        val cam=camera(road="Alpha Rd",target="Target Ave")
        val corroborator=camera(id="SPF-TARGET",road="Alpha Road",target="Different Road",
            junction="Target Avenue",point=GpsPoint(1.3000,103.8100))
        val result=CameraApproachLogic.find(GpsPoint(1.3,103.807),backwards,
            "Alpha Road",listOf("Other Road"),8f,
            listOf(cam,corroborator),10f,270.0,9f)
        val a=result.first {it.camera.id=="SPF-TEST"}
        assertFalse(a.directionConfirmed)
        assertFalse(a.alertAllowed)
    }
    @Test fun gpsVoicePriorityAndDuplicateGateStillApply() {
        val a=search(listOf(camera())).single()
        val planner=CameraVoicePlanner865()
        assertNotNull(planner.due(a,CameraAlertPreferences(),true,true,50.0,12f))
        assertNotNull(CameraVoicePriority872.deferredReason(50.0,null))
        val cue=planner.due(a,CameraAlertPreferences(),true,true,600.0,12f)!!
        planner.markScheduled(cue)
        assertNull(planner.due(a,CameraAlertPreferences(),true,true,600.0,12f))
    }
}
