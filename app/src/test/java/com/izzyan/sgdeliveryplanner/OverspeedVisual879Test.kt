package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class OverspeedVisual879Test {
    private fun road(at:Long,kind:RoadLimitSource875=RoadLimitSource875.OFFICIAL,
                     limit:Int=90,segment:String="PIE-80:F")=
        RoadSpeedState875(RoadLimit875(limit,kind,"https://www.lta.gov.sg/","2026-11-21"),
            segment,"PIE",observedAtMs=at)
    private fun fix(at:Long,kmh:Double,reportedSpeedAccuracy:Float?=0.6f,
                    horizontalAccuracy:Float=4f)=RoadSpeedFix875(
        GpsPoint(1.33,103.92),at,horizontalAccuracy,90f,
        (kmh/3.6).toFloat(),reportedSpeedAccuracy)

    @Test fun screenshot150Against90TurnsRedForOfficialVerifiedLimit() {
        val m=OverspeedVisualMonitor879()
        val result=m.observe(road(1000),fix(1000,150.0),true)
        assertEquals(150,result.shownSpeedKmh)
        assertEquals(90,result.applicableLimitKmh)
        assertTrue(result.red)
        assertTrue(result.speedReliable)
        assertEquals(RoadLimitSource875.OFFICIAL,result.limitSource)
    }

    @Test fun validatedMapData90TurnsRedWithoutOfficialVoiceRequirement() {
        val m=OverspeedVisualMonitor879()
        val result=m.observe(road(1000,RoadLimitSource875.MAP_DATA),fix(1000,150.0),true)
        assertEquals(90,result.applicableLimitKmh)
        assertTrue(result.red)
        assertEquals(RoadLimitSource875.MAP_DATA,result.limitSource)
    }

    @Test fun absentSpeedAccuracyUsesSuccessiveConsistentGpsSamples() {
        val m=OverspeedVisualMonitor879()
        assertFalse(m.observe(road(1000,RoadLimitSource875.MAP_DATA),fix(1000,145.0,null),true).red)
        assertTrue(m.observe(road(2000,RoadLimitSource875.MAP_DATA),fix(2000,148.0,null),true).red)
        assertTrue(m.observe(road(3000,RoadLimitSource875.MAP_DATA),fix(3000,150.0,null),true).red)
    }

    @Test fun oneGpsSpeedSpikeIsNotTreatedAsPersistentOverspeed() {
        val m=OverspeedVisualMonitor879()
        assertFalse(m.observe(road(1000),fix(1000,70.0),true).red)
        val spike=m.observe(road(2000),fix(2000,150.0),true)
        assertFalse(spike.red)
        assertTrue(spike.diagnostic.contains("jump"))
        assertTrue(m.observe(road(3000),fix(3000,148.0),true).red)
    }

    @Test fun returnToNormalWhiteAndNoPersistentVisualState() {
        val m=OverspeedVisualMonitor879()
        assertTrue(m.observe(road(1000,limit=90),fix(1000,150.0),true).red)
        assertFalse(m.observe(road(2000,limit=90),fix(2000,85.0),true).red)
        assertFalse(m.observe(road(3000,limit=90),fix(3000,80.0),true).red)
    }

    @Test fun exactLimit80IsWhiteAnd85Red() {
        val m=OverspeedVisualMonitor879()
        assertFalse(m.observe(road(1000,limit=80),fix(1000,80.0),true).red)
        assertTrue(m.observe(road(2000,limit=80),fix(2000,85.0),true).red)
        assertFalse(m.observe(road(3000,limit=80),fix(3000,79.0),true).red)
    }

    @Test fun unverifiedGeneralRuleOrMissingSegmentCannotProduceRed() {
        val m=OverspeedVisualMonitor879()
        assertFalse(m.observe(road(1000,RoadLimitSource875.GENERAL_RULE),fix(1000,150.0),true).red)
        assertFalse(m.observe(RoadSpeedState875(),fix(2000,150.0),true).red)
        assertFalse(m.observe(road(3000,segment=""),fix(3000,150.0),true).red)
    }

    @Test fun staleRoadOrInvalidSpeedAndAccuracyNeverGenerateFalseRed() {
        val m=OverspeedVisualMonitor879()
        assertFalse(m.observe(road(1000),fix(5500,150.0),true).red)
        assertFalse(m.observe(road(6000),fix(6000,150.0,5.0f),true).red)
        assertFalse(m.observe(road(7000),fix(7000,150.0,0.5f,35f),true).red)
        assertFalse(m.observe(road(8000),fix(8000,150.0),false).red)
    }

    @Test fun limitChangeAndDistinctRoadRecoversWithoutReusingOldLimit() {
        val m=OverspeedVisualMonitor879()
        assertTrue(m.observe(road(1000,limit=80),fix(1000,86.0),true).red)
        assertFalse(m.observe(road(2000,limit=90,segment="CTE-90:F"),fix(2000,86.0),true).red)
        assertTrue(m.observe(road(3000,limit=90,segment="CTE-90:F"),fix(3000,150.0),true).red)
    }

    @Test fun fallbackSamplesMustBeNewGpsUpdatesNotRepeatedAsyncCallbacks() {
        val m=OverspeedVisualMonitor879()
        assertFalse(m.observe(road(1000,RoadLimitSource875.MAP_DATA),fix(1000,150.0,null),true).red)
        assertFalse(m.observe(road(1000,RoadLimitSource875.MAP_DATA),fix(1000,150.0,null),true).red)
        assertTrue(m.observe(road(2000,RoadLimitSource875.MAP_DATA),fix(2000,149.0,null),true).red)
    }

    @Test fun voiceEligibilityStaysOfficialEvenWhenMapDataVisualIsRed() {
        val visual=OverspeedVisualMonitor879()
        val voice=OverspeedSafetyMonitor878()
        val fix=fix(1000,150.0,null)
        assertTrue(visual.observe(road(1000,RoadLimitSource875.MAP_DATA),fix(1000,150.0,0.8f),true).red)
        assertFalse(voice.observe(road(1000,RoadLimitSource875.MAP_DATA),fix,true,
            validatedFallbackSpeed=true).confirmed)
        assertFalse(voice.observe(road(2100,RoadLimitSource875.MAP_DATA),fix(2100,150.0,null),true,
            validatedFallbackSpeed=true).voiceDue)
    }

    @Test fun officialVoiceUsesTwoSecondsAndFallbackConfidenceWithoutSpeedAccuracy() {
        val voice=OverspeedSafetyMonitor878()
        assertFalse(voice.observe(road(1000),fix(1000,95.0,null),true,
            validatedFallbackSpeed=true).confirmed)
        assertFalse(voice.observe(road(2000),fix(2000,96.0,null),true,
            validatedFallbackSpeed=true).confirmed)
        assertTrue(voice.observe(road(3100),fix(3100,97.0,null),true,
            validatedFallbackSpeed=true).voiceDue)
    }
}
