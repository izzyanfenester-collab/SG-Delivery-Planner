package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class OverspeedSafety878Test {
    private fun limit(kind:RoadLimitSource875=RoadLimitSource875.OFFICIAL)=
        RoadLimit875(80,kind,"https://www.lta.gov.sg/","2026-11-21")
    private fun road(at:Long, kind:RoadLimitSource875=RoadLimitSource875.OFFICIAL,segment:String="PIE:F")=
        RoadSpeedState875(limit(kind),segment,"PIE",observedAtMs=at)
    private fun fix(at:Long,kmh:Double,accuracy:Float=5f,speedAccuracy:Float=.5f)=
        RoadSpeedFix875(GpsPoint(1.33,103.92),at,accuracy,90f,(kmh/3.6).toFloat(),speedAccuracy)

    @Test fun verified80RequiresTwoSecondsAbove83ThenSignalsOnce() {
        val m=OverspeedSafetyMonitor878()
        assertFalse(m.observe(road(1000),fix(1000,82.0),true).confirmed)
        assertFalse(m.observe(road(2000),fix(2000,85.0),true).confirmed)
        assertFalse(m.observe(road(3000),fix(3000,86.0),true).confirmed)
        val active=m.observe(road(4100),fix(4100,85.0),true)
        assertTrue(active.confirmed);assertTrue(active.voiceDue)
        assertEquals(80,active.verifiedLimitKmh)
        m.markRequested(4100)
        assertFalse(m.observe(road(5100),fix(5100,86.0),true).voiceDue)
        m.playbackStarted(5100)
        assertFalse(m.observe(road(6100),fix(6100,87.0),true).voiceDue)
    }

    @Test fun cooldownPreventsSpammingAcrossSeveralOverspeedEvents() {
        val m=OverspeedSafetyMonitor878()
        m.observe(road(1000),fix(1000,85.0),true)
        m.observe(road(2000),fix(2000,85.0),true)
        assertTrue(m.observe(road(3200),fix(3200,85.0),true).voiceDue)
        m.markRequested(3200);m.playbackStarted(3200)
        m.observe(road(4200),fix(4200,79.0),true)
        assertFalse(m.observe(road(5300),fix(5300,79.0),true).confirmed)
        m.observe(road(6300),fix(6300,86.0),true)
        m.observe(road(7400),fix(7400,86.0),true)
        assertFalse(m.observe(road(8500),fix(8500,86.0),true).voiceDue)
        for(at in 9500L..95500L step 1000L)m.observe(road(at),fix(at,86.0),true)
        assertTrue(m.observe(road(96500),fix(96500,86.0),true).voiceDue)
    }

    @Test fun unknownMapDataAndPoorGpsNeverWarn() {
        val m=OverspeedSafetyMonitor878()
        for(at in listOf(1000L,2100L,3200L)) {
            assertFalse(m.observe(road(at,RoadLimitSource875.MAP_DATA),fix(at,100.0),true).confirmed)
        }
        assertFalse(m.observe(RoadSpeedState875(),fix(4500,100.0),true).voiceDue)
        assertFalse(m.observe(road(5800),fix(5800,100.0,50f),true).confirmed)
        assertFalse(m.observe(road(6800),fix(6800,100.0,5f,3f),true).confirmed)
        assertFalse(m.observe(road(7800),fix(7800,100.0),false).confirmed)
    }

    @Test fun expiredOrWrongRoadSampleClearsState() {
        val m=OverspeedSafetyMonitor878()
        m.observe(road(1000),fix(1000,86.0),true)
        m.observe(road(2100),fix(2100,86.0),true)
        assertTrue(m.observe(road(3300),fix(3300,86.0),true).confirmed)
        assertFalse(m.observe(road(4500),fix(4600,88.0),true).confirmed)
        m.observe(road(5700),fix(5700,88.0),true)
        assertFalse(m.observe(road(6800,segment="CTE:F"),fix(6800,88.0),true).confirmed)
        assertFalse(m.observe(road(16000),fix(16000,88.0),true).confirmed)
    }

    @Test fun rejectedAudioDoesNotConsumeCooldown() {
        val m=OverspeedSafetyMonitor878()
        m.observe(road(1000),fix(1000,85.0),true)
        m.observe(road(2100),fix(2100,85.0),true)
        assertTrue(m.observe(road(3300),fix(3300,85.0),true).voiceDue)
        m.markRequested(3300);m.playbackNotStarted()
        assertTrue(m.observe(road(4400),fix(4400,85.0),true).voiceDue)
    }
}
