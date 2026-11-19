package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

/** Deterministic B866 recorded-GPS-distance replay; requires follow-up on-road/Bluetooth QA. */
class CameraVoiceCritical866Test {
    private fun camera(id:String,category:String="FIXED_SPEED_CAMERA")=EnforcementCamera(
        id,category,"Published SPF location",GpsPoint(1.35,103.82),
        "Test Road","Towards junction","Junction",
        "https://www.police.gov.sg/knowledge-hub/traffic/traffic-matters/speed-enforcement-camera-locations",
        "2026-07-01",null,false,null,null,null,"PUBLISHED_LOCATION_NOT_LIVE_STATUS",null)
    private fun approach(cam:EnforcementCamera,distance:Double,valid:Boolean=true)=
        CameraApproach(cam,distance,valid,valid)
    private val settings=CameraAlertPreferences()
    private fun cue(planner:CameraVoicePlanner865,cam:EnforcementCamera,metres:Double,enabled:Boolean=true):CameraVoiceCue865? =
        planner.due(approach(cam,metres),settings,true,enabled,600.0,12f)
    private fun event(cue:CameraVoiceCue865,state:PandaPlaybackState866,elapsed:Long)=
        PandaPlaybackEvent866(cue.phrase.key,state,"test",elapsed)

    @Test fun fixedCamera2200To1800CrossingGeneratesRealRecordedClip() {
        val p=CameraVoicePlanner865();val cam=camera("S1")
        assertNull(cue(p,cam,2200.0))
        val warning=cue(p,cam,1800.0)
        assertEquals("speed_camera_ahead",warning?.clipId)
        assertEquals(VoicePriority.CAMERA,warning?.phrase?.priority)
    }
    @Test fun redLight2150To1890CrossingGeneratesSpecificRedLightClip() {
        val p=CameraVoicePlanner865();val cam=camera("R1","RED_LIGHT_CAMERA")
        assertNull(cue(p,cam,2150.0))
        assertEquals("red_light_camera_ahead",cue(p,cam,1890.0)?.clipId)
    }
    @Test fun controllerAcceptedButNotStartedMustNeverCountAsPlayed() {
        val p=CameraVoicePlanner865();val cam=camera("S2")
        val first=cue(p,cam,450.0)!!
        p.markRequested(first)
        assertTrue(p.isRequested(first))
        assertFalse(p.isStarted(first))
        assertNull(cue(p,cam,430.0)) // pending, no duplicate audio
        p.playbackEvent(first,event(first,PandaPlaybackState866.CANCELLED,2_000L))
        assertFalse(p.isRequested(first))
        assertFalse(p.isStarted(first))
        assertNotNull(cue(p,cam,420.0)) // retry while still ahead
    }
    @Test fun preparedAndActuallyStartedCountsAsDelivered() {
        val p=CameraVoicePlanner865();val cam=camera("S3")
        val first=cue(p,cam,480.0)!!
        p.markRequested(first)
        p.playbackEvent(first,event(first,PandaPlaybackState866.STARTED,3_000L))
        assertTrue(p.isStarted(first))
        p.playbackEvent(first,event(first,PandaPlaybackState866.COMPLETED,4_700L))
        assertNull(cue(p,cam,440.0))
    }
    @Test fun interruptionWithin850MsRetriesWhenSafe() {
        val p=CameraVoicePlanner865();val cam=camera("R3","RED_LIGHT_CAMERA")
        val first=cue(p,cam,270.0)!!
        p.markRequested(first)
        p.playbackEvent(first,event(first,PandaPlaybackState866.STARTED,1_000L))
        p.playbackEvent(first,event(first,PandaPlaybackState866.INTERRUPTED,1_140L))
        assertFalse(p.isStarted(first))
        assertEquals("red_light_camera_ahead",cue(p,cam,240.0)?.clipId)
    }
    @Test fun noReplayAfterClearlyAudiblePartialWarning() {
        val p=CameraVoicePlanner865();val cam=camera("S4")
        val first=cue(p,cam,470.0)!!
        p.markRequested(first)
        p.playbackEvent(first,event(first,PandaPlaybackState866.STARTED,1_000L))
        p.playbackEvent(first,event(first,PandaPlaybackState866.INTERRUPTED,2_700L))
        assertTrue(p.isStarted(first))
        assertNull(cue(p,cam,420.0))
    }
    @Test fun highPriorityTurnDefersUntilTurnFinished() {
        val p=CameraVoicePlanner865();val cam=camera("S5")
        assertNotNull(p.due(approach(cam,470.0),settings,true,true,60.0,12f))
        assertEquals("immediate-maneuver-priority",CameraVoicePriority872.deferredReason(60.0,null))
        assertEquals("speed_camera_ahead",cue(p,cam,435.0)?.clipId)
    }
    @Test fun differentCameraIdsAreIndependent() {
        val p=CameraVoicePlanner865()
        val first=cue(p,camera("S6"),420.0)!!
        p.markRequested(first)
        p.playbackEvent(first,event(first,PandaPlaybackState866.STARTED,1000))
        assertEquals("speed_camera_ahead",cue(p,camera("S7"),400.0)?.clipId)
    }
    @Test fun cameraAfterRouteChangeReevaluatesAndOldCallbackIsIgnored() {
        val p=CameraVoicePlanner865();val cam=camera("S8")
        val old=cue(p,cam,470.0)!!
        p.markRequested(old)
        p.reset()
        p.playbackEvent(old,event(old,PandaPlaybackState866.STARTED,8_000L))
        assertFalse(p.isStarted(old))
        assertNotNull(cue(p,cam,460.0))
    }
    @Test fun resetAfterLongDepartureAllowsSecondGenuineApproach() {
        val p=CameraVoicePlanner865();val cam=camera("S9")
        val first=cue(p,cam,450.0)!!
        p.markRequested(first)
        p.playbackEvent(first,event(first,PandaPlaybackState866.STARTED,500L))
        p.observeVisible(listOf(approach(cam,300.0)),1_000L)
        p.observeVisible(emptyList(),2_000L)
        p.observeVisible(emptyList(),93_000L)
        assertNotNull(cue(p,cam,450.0))
    }
    @Test fun beforeCameraOnlyAndNoWrongDirectionOrUnverifiedAverageZone() {
        val p=CameraVoicePlanner865()
        assertNull(cue(p,camera("PAST"),-20.0))
        assertNull(p.due(approach(camera("OPPOSITE"),300.0,false),
            settings,true,true,600.0,12f))
        assertNull(cue(p,camera("AVE","AVERAGE_SPEED_ZONE"),420.0))
    }
}
