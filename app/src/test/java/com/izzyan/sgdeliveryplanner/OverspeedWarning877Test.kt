package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class OverspeedWarning877Test {
    private val options=OverspeedOptions877()
    private fun fix(t:Long,kmh:Double=86.0)=RoadSpeedFix875(GpsPoint(1.30,103.80),t,3f,0f,(kmh/3.6).toFloat(),0.2f)
    private fun road(t:Long,n:Int=80,kind:RoadLimitSource875=RoadLimitSource875.OFFICIAL)=RoadSpeedState875(
        RoadLimit875(n,kind,"https://www.lta.gov.sg/unit-test-fixture","2026-11-21"),"fixture:F","Fixture",observedAtMs=t)
    private fun confirm(d:OverspeedDetector877,n:Int=80,kmh:Double=86.0,start:Long=1_000):OverspeedState877 {
        var s=OverspeedState877()
        for(t in listOf(start,start+1_000,start+2_000))s=d.observe(fix(t,kmh),road(t,n),options,t)
        return s
    }
    @Test fun defaultsAreOnWithThreeKmhToleranceAndNinetySecondCooldown(){assertTrue(options.enabled&&options.redText&&options.voice);assertEquals(3,options.toleranceKmh);assertEquals(90,options.cooldownSeconds)}
    @Test fun seventyFiveOnVerifiedEightyStaysNormal(){assertFalse(confirm(OverspeedDetector877(),kmh=75.0).confirmed)}
    @Test fun eightySixOnVerifiedEightyRequiresTwoSeconds(){val d=OverspeedDetector877();assertFalse(d.observe(fix(1000),road(1000),options,1000).confirmed);assertFalse(d.observe(fix(2000),road(2000),options,2000).confirmed);assertTrue(d.observe(fix(3000),road(3000),options,3000).confirmed)}
    @Test fun ninetySixOnVerifiedNinetyIsRed(){assertTrue(confirm(OverspeedDetector877(),90,96.0).confirmed)}
    @Test fun noiseNearLimitDoesNotCreateEvent(){val d=OverspeedDetector877();listOf(79.0,81.0,80.0,82.0,83.0,81.0).forEachIndexed{i,v->val t=(i+1)*1000L;assertFalse(d.observe(fix(t,v),road(t),options,t).confirmed)}}
    @Test fun excursionMustBeContinuous(){val d=OverspeedDetector877();for((t,speed) in listOf(1000L to 86.0,2000L to 82.0,3000L to 86.0,4000L to 87.0)){assertFalse(d.observe(fix(t,speed),road(t),options,t).confirmed)}}
    @Test fun returningToSeventyNineRestoresNormalAfterOneSecond(){val d=OverspeedDetector877();confirm(d);assertTrue(d.observe(fix(4000,79.0),road(4000),options,4000).confirmed);assertFalse(d.observe(fix(5000,79.0),road(5000),options,5000).confirmed)}
    @Test fun tinyOverLimitDoesNotClearAnAlreadyConfirmedEvent(){val d=OverspeedDetector877();confirm(d);assertTrue(d.observe(fix(4000,81.0),road(4000),options,4000).confirmed)}
    @Test fun unknownLimitNeverBecomesZero(){val d=OverspeedDetector877();assertFalse(d.observe(fix(1000),RoadSpeedState875(),options,1000).confirmed);assertNull(d.state.limitKmh)}
    @Test fun mapAndGeneralDataNeverBecomeVerifiedOverspeed(){for(kind in listOf(RoadLimitSource875.MAP_DATA,RoadLimitSource875.GENERAL_RULE)){val d=OverspeedDetector877();for(t in 1000L..5000L step 1000)assertFalse(d.observe(fix(t),road(t,kind=kind),options,t).confirmed)}}
    @Test fun missingDirectionCannotTrigger(){val d=OverspeedDetector877();assertFalse(d.observe(fix(1000),road(1000).copy(segmentId="fixture"),options,1000).confirmed)}
    @Test fun poorGpsPositionAccuracyClearsState(){val d=OverspeedDetector877();confirm(d);assertFalse(d.observe(fix(4000).copy(accuracy=26f),road(4000),options,4000).confirmed)}
    @Test fun missingOrPoorSpeedAccuracyDoesNotTrigger(){for(a in listOf(null,2f,Float.NaN)){val d=OverspeedDetector877();assertFalse(d.observe(fix(1000).copy(speedAccuracy=a),road(1000),options,1000).confirmed)}}
    @Test fun staleAndFutureFixesCannotTrigger(){val d=OverspeedDetector877();assertFalse(d.observe(fix(1000),road(1000),options,7000).confirmed);assertFalse(d.observe(fix(10000),road(10000),options,1000).confirmed)}
    @Test fun roadAndSpeedMustDescribeSameFix(){val d=OverspeedDetector877();assertFalse(d.observe(fix(2000),road(1000),options,2000).confirmed)}
    @Test fun repeatedOrOutOfOrderFixDoesNotAdvanceConfirmation(){val d=OverspeedDetector877();for(t in listOf(2000L,2000L,1000L,2000L))assertFalse(d.observe(fix(t),road(t),options,2000).confirmed)}
    @Test fun samplingGapIsNotContinuousEvidence(){val d=OverspeedDetector877();d.observe(fix(1000),road(1000),options,1000);assertFalse(d.observe(fix(5000),road(5000),options,5000).confirmed)}
    @Test fun staleDisplayClearsAfterFiveSeconds(){val d=OverspeedDetector877();confirm(d);assertFalse(d.expire(8001).confirmed)}
    @Test fun verifiedRoadChangeClearsOldWarning(){val d=OverspeedDetector877();confirm(d);assertFalse(d.observe(fix(4000,86.0),road(4000,90).copy(segmentId="new:F"),options,4000).confirmed)}
    @Test fun shortGeometryEdgesOnSameVerifiedDirectionalWayRemainContinuous(){val d=OverspeedDetector877();for(i in 1..4){val t=i*1000L;val s=d.observe(fix(t),road(t).copy(segmentId="21309187:$i:0:F"),options,t);assertEquals(i>=3,s.confirmed)};assertEquals(1L,d.state.eventId)}
    @Test fun masterOffClearsAnExistingEvent(){val d=OverspeedDetector877();confirm(d);assertFalse(d.observe(fix(4000),road(4000),options.copy(enabled=false),4000).confirmed)}
    @Test fun genuineNewEventGetsNewIdentity(){val d=OverspeedDetector877();val a=confirm(d);d.observe(fix(4000,79.0),road(4000),options,4000);d.observe(fix(5000,79.0),road(5000),options,5000);val b=confirm(d,start=6000);assertTrue(b.confirmed);assertTrue(b.eventId>a.eventId)}
    @Test fun requestIsPendingUntilActualPlayback(){val g=OverspeedVoiceGate877();val s=OverspeedState877(true,1);assertTrue(g.due(s,1000,90));g.requested(1,1000);assertFalse(g.due(s,5000,90));g.playback(1,false,true,5000);assertTrue(g.due(s,6000,90))}
    @Test fun cooldownStartsAtPlaybackStarted(){val g=OverspeedVoiceGate877();val s=OverspeedState877(true,1);g.requested(1,1000);g.playback(1,true,false,1500);g.playback(1,false,true,7000);assertFalse(g.due(s,91499,90));assertTrue(g.due(s,91500,90))}
    @Test fun newEventAlsoRespectsMinimumCooldown(){val g=OverspeedVoiceGate877();g.requested(1,1000);g.playback(1,true,false,1500);g.cancel();assertFalse(g.due(OverspeedState877(true,2),20000,90));assertTrue(g.due(OverspeedState877(true,2),92000,90))}
    @Test fun failedPlaybackDoesNotBecomeSpoken(){val g=OverspeedVoiceGate877();g.requested(1,1000);g.playback(1,false,true,1100);assertTrue(g.due(OverspeedState877(true,1),6000,90))}
    @Test fun criticalManeuverAndCameraVoiceHavePriority(){assertNotNull(CameraVoicePriority872.deferredReason(800.0,VoicePriority.CRITICAL));assertNotNull(CameraVoicePriority872.deferredReason(800.0,VoicePriority.CAMERA));assertNotNull(CameraVoicePriority872.deferredReason(80.0,null,"turn"));assertNull(CameraVoicePriority872.deferredReason(800.0,null,"continue"))}
}
