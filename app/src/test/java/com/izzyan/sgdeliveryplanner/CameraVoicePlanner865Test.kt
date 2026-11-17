package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class CameraVoicePlanner865Test {
    private fun camera(
        category:String="FIXED_SPEED_CAMERA",
        limit:Int?=null,
        directionVerified:Boolean=false
    )=EnforcementCamera(
        id="TEST-1", category=category, name="Published test location",
        point=GpsPoint(1.32,103.82),
        road="Test Road",directionText="Towards junction",junction="Test junction",
        sourceUrl="https://www.police.gov.sg/test",
        sourcePublicationDate="2026-07-01", directionBearing=null,
        directionVerified=directionVerified, speedLimitKmh=limit,
        speedLimitSource=if(limit==null)null else "https://www.lta.gov.sg/verified",
        speedLimitVerifiedAt=if(limit==null)null else "2026-11-18",
        speedEnforcementStatus="PUBLISHED_LOCATION_NOT_LIVE_STATUS",zoneId=null
    )
    private fun approach(camera:EnforcementCamera,distance:Double,confirmed:Boolean=true) =
        CameraApproach(camera,distance,confirmed,confirmed)
    private val preferences=CameraAlertPreferences()

    @Test fun exactOriginalCameraClipIsUsedOncePerApproach() {
        val planner=CameraVoicePlanner865()
        val cam=camera()
        val first=planner.due(approach(cam,460.0),preferences,true,true,900.0,12f)
        assertNotNull(first)
        assertEquals("speed_camera_ahead",first!!.clipId)
        assertEquals(VoicePriority.CAMERA,first.phrase.priority)
        planner.markScheduled(first)
        assertNull(planner.due(approach(cam,430.0),preferences,true,true,900.0,12f))
        val near=planner.due(approach(cam,170.0),preferences,true,true,900.0,12f)
        assertNotNull(near)
        assertEquals("speed_camera_near",near!!.clipId)
        planner.markScheduled(near)
        assertNull(planner.due(approach(cam,90.0),preferences,true,true,900.0,12f))
    }
    @Test fun redLightCameraUsesItsRecordedVoice() {
        val planner=CameraVoicePlanner865()
        val first=planner.due(approach(camera("RED_LIGHT_CAMERA"),260.0),
            preferences,true,true,1000.0,12f)
        assertEquals("red_light_camera_ahead",first?.clipId)
    }
    @Test fun neverWarnOnUnverifiedWrongDirectionOrNoAudio() {
        val planner=CameraVoicePlanner865()
        assertNull(planner.due(approach(camera(),400.0,confirmed=false),
            preferences,true,true,900.0,10f))
        assertNull(planner.due(approach(camera(),400.0),
            preferences,true,false,900.0,10f))
        assertNull(planner.due(approach(camera(),400.0),
            preferences,true,true,60.0,10f))
    }
    @Test fun averageSpeedZoneRequiresActualVerifiedGeometry() {
        assertNull(CameraVoicePlanner865().due(approach(camera("AVERAGE_SPEED_ZONE"),200.0),
            preferences,true,true,900.0,10f))
    }
    @Test fun unverifiedSpeedLimitCannotBeSpoken() {
        assertNull(CameraSpeedLimitVoice865.phrase(camera(limit=null)))
        assertNull(CameraSpeedLimitVoice865.phrase(camera(limit=80,directionVerified=false)))
        val verified=CameraSpeedLimitVoice865.phrase(camera(limit=80,directionVerified=true))
        assertEquals(listOf("speed_limit_prefix","number_80","kilometres_per_hour"),
            verified?.parts?.map{it.clip})
    }
    @Test fun rerouteResetsPerApproachDeduplication() {
        val planner=CameraVoicePlanner865()
        val first=planner.due(approach(camera(),400.0),preferences,true,true,900.0,10f)!!
        planner.markScheduled(first)
        assertNull(planner.due(approach(camera(),390.0),preferences,true,true,900.0,10f))
        planner.reset()
        assertNotNull(planner.due(approach(camera(),400.0),preferences,true,true,900.0,10f))
    }
    @Test fun settingsDisableIndividualCategories() {
        val planner=CameraVoicePlanner865()
        assertNull(planner.due(approach(camera("RED_LIGHT_CAMERA"),280.0),
            preferences.copy(redLight=false),true,true,900.0,10f))
        assertNull(planner.due(approach(camera(),280.0),
            preferences.copy(master=false),true,true,900.0,10f))
    }
}
