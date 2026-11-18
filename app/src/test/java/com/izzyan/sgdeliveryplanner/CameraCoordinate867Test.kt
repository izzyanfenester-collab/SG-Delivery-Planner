package com.izzyan.sgdeliveryplanner

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Camera coordinates from user's image; synthetic route tests, NOT physical driving QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CameraCoordinate867Test {
    private val shape=listOf(GpsPoint(1.3,103.8),GpsPoint(1.3,103.84))
    private fun camera(id:String="TEST-KPE",road:String="Kallang-Paya Lebar Expressway 2.3 Km",
        category:String="LTA_SPEED_CAMERA",direction:String="Tampines Expressway",
        point:GpsPoint=GpsPoint(1.3,103.8216))=EnforcementCamera(
        id,category,road+" towards "+direction,point,road,direction,null,
        "https://www.police.gov.sg/Knowledge-Hub/Traffic/Traffic-Matters/Speed-Enforcement-Camera-Locations",
        "2026-07-01",null,false,null,null,null,
        "PUBLISHED_LOCATION_NOT_LIVE_STATUS",null)
    private fun scan(lon:Double,records:List<EnforcementCamera>,street:String="KPE",
        upcoming:List<String> =listOf("Tampines Expressway"),heading:Double?=90.0,
        accuracy:Float=7f,shapePath:List<GpsPoint> =shape)=CameraApproachLogic.find(
            GpsPoint(1.3,lon),shapePath,street,upcoming,accuracy,records,14f,
            heading,7f)

    @Test fun attachedScreenshotCoordinatesMatchOriginalSpfRecordsWithoutDuplicates() {
        val snapshot=CameraSafetySnapshotParser.parse(
            File("src/main/assets/sg_enforcement_cameras.json").readText())
        val catalog=CameraCoordinateCatalog867.verify(
            File("src/main/assets/sg_speed_camera_reference_867.json").readText(),
            snapshot.cameras)
        assertEquals(100,catalog.size)
        assertEquals(88,catalog.eligibleSpeedIds.size)
        assertEquals(12,catalog.averageZonePointIds.size)
        assertEquals(241,snapshot.redLightCount)
        assertEquals(341,snapshot.cameras.size)
        val fixed=snapshot.cameras.first{it.id=="FIXED_SPEED_CAMERA-001"}
        assertEquals(1.298237,fixed.point.lat,1e-10)
        assertEquals(103.781679,fixed.point.lon,1e-10)
        val mce=snapshot.cameras.first{it.id=="LTA_SPEED_CAMERA-007-A"}
        assertEquals(1.27931,mce.point.lat,1e-10)
        assertEquals(103.87229,mce.point.lon,1e-10)
        val mobile=snapshot.cameras.first{it.id=="MOBILE_ENFORCEMENT_LOCATION-001"}
        assertEquals(1.4228408,mobile.point.lat,1e-10)
        assertEquals(103.7710338,mobile.point.lon,1e-10)
    }

    @Test fun actualRouteDistanceCrossesTwoKilometresWithoutExactMatch() {
        val camera=camera()
        val outside=scan(103.8018,listOf(camera)).single()
        assertTrue(outside.routeMetresAhead>2_000)
        assertFalse(outside.alertAllowed)
        val inside=scan(103.8054,listOf(camera)).single()
        assertTrue(inside.routeMetresAhead in 1_700.0..1_900.0)
        assertTrue(inside.directionConfirmed)
        assertTrue(inside.alertAllowed)
        val planner=CameraVoicePlanner865()
        assertNull(planner.due(outside,CameraAlertPreferences(),true,true,800.0,14f))
        val due=planner.due(inside,CameraAlertPreferences(),true,true,800.0,14f)
        assertEquals("speed_camera_ahead",due?.clipId)
        assertTrue(due!!.crossedThreshold)
        assertEquals(2_000.0,due.warningThresholdMetres!!,0.01)
    }

    @Test fun navigationStartsInsideTwoKilometresAndPlaysPrimaryOnce() {
        val planner=CameraVoicePlanner865()
        val cam=camera("START")
        val approach=CameraApproach(cam,1_500.0,true,true)
        val cue=planner.due(approach,CameraAlertPreferences(),true,true,900.0,14f)!!
        assertEquals("speed_camera_ahead",cue.clipId)
        planner.markRequested(cue)
        assertNull(planner.due(approach.copy(routeMetresAhead=1_460.0),
            CameraAlertPreferences(),true,true,900.0,14f))
        planner.playbackEvent(cue,PandaPlaybackEvent866(cue.phrase.key,PandaPlaybackState866.STARTED,
            "real-mediaplayer",1_000L))
        assertNull(planner.due(approach.copy(routeMetresAhead=1_400.0),
            CameraAlertPreferences(),true,true,900.0,14f))
    }

    @Test fun optionalFiveHundredMetreReminderCrossingAndDisableSetting() {
        val planner=CameraVoicePlanner865();val cam=camera("REMINDER")
        val nearBefore=CameraApproach(cam,1_900.0,true,true)
        val main=planner.due(nearBefore,CameraAlertPreferences(),true,true,600.0,13f)!!
        planner.markScheduled(main)
        assertNull(planner.due(nearBefore.copy(routeMetresAhead=540.0),
            CameraAlertPreferences(),true,true,600.0,13f))
        val reminder=planner.due(nearBefore.copy(routeMetresAhead=475.0),
            CameraAlertPreferences(),true,true,600.0,13f)
        assertEquals("speed_camera_near",reminder?.clipId)
        assertEquals(500.0,reminder!!.warningThresholdMetres!!,0.01)
        val disabled=CameraVoicePlanner865()
        val original=disabled.due(nearBefore,CameraAlertPreferences(),true,true,600.0,13f)!!
        disabled.markScheduled(original)
        disabled.due(nearBefore.copy(routeMetresAhead=540.0),
            CameraAlertPreferences(),true,true,600.0,13f)
        assertNull(disabled.due(nearBefore.copy(routeMetresAhead=475.0),
            CameraAlertPreferences(coordinateReminder=false),true,true,600.0,13f))
    }

    @Test fun headingAndRoadAreRequiredAndWrongParallelRoadDoesNotWarn() {
        assertTrue(scan(103.8054,listOf(camera()),street="Adjacent Road").isEmpty())
        assertTrue(scan(103.8054,listOf(camera()),
            shapePath=listOf(GpsPoint(1.302,103.8),GpsPoint(1.302,103.84))).isEmpty())
        val unknown=scan(103.8054,listOf(camera(direction="Unrelated Road")),
            upcoming=listOf("Different Road"),heading=270.0).single()
        assertFalse(unknown.directionConfirmed)
        assertFalse(unknown.genericSafetyEligible)
    }

    @Test fun twoPublishedOpposingFixedCamerasAtSameSiteProduceOneVoiceAlert() {
        val a=camera("A","Ayer Rajah Expressway","FIXED_SPEED_CAMERA","City",
            GpsPoint(1.30000,103.82155))
        val b=camera("B","Ayer Rajah Expressway","FIXED_SPEED_CAMERA","Jurong",
            GpsPoint(1.30002,103.82157))
        val candidates=scan(103.8054,listOf(a,b),street="AYE",
            upcoming=listOf("Ayer Rajah Expressway"))
        assertEquals(2,candidates.size)
        assertTrue(candidates.all{it.directionConfirmed && it.alertAllowed})
        assertEquals(1,CameraVoiceGroup867.combine(candidates).size)
    }

    @Test fun neverGroupDifferentCameraTypesAtSameCoordinates() {
        val a=CameraApproach(camera("S"),480.0,true,true)
        val b=CameraApproach(camera("M",category="MOBILE_ENFORCEMENT_LOCATION"),480.0,true,true)
        assertEquals(2,CameraVoiceGroup867.combine(listOf(a,b)).size)
    }

    @Test fun fallbackOnlyAfterTwoConsistentAccurateApproachingCoordinates() {
        val tracker=CameraGpsFallback867()
        val cam=camera("FALLBACK",road="Ayer Rajah Expressway")
        fun scan(lon:Double,road:String="AYE",bearing:Double=90.0,accuracy:Float=5f,ms:Long)=
            tracker.scan(GpsPoint(1.3,lon),ms,accuracy,12f,bearing,5f,road,listOf(cam))
        assertTrue(scan(103.801,ms=1_000).isEmpty())
        val result=scan(103.804,ms=2_000)
        assertEquals(1,result.size)
        assertTrue(result.single().coordinateFallbackUsed)
        assertTrue(result.single().genericSafetyEligible)
        assertFalse(result.single().directionConfirmed)
        tracker.reset()
        assertTrue(scan(103.801,road="Wrong Road",ms=3_000).isEmpty())
        assertTrue(scan(103.804,road="Wrong Road",ms=4_000).isEmpty())
        tracker.reset()
        assertTrue(scan(103.801,ms=5_000).isEmpty())
        assertTrue(scan(103.804,bearing=270.0,ms=6_000).isEmpty())
    }
}
