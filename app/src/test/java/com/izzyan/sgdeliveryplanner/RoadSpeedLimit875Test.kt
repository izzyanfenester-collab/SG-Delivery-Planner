package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class RoadSpeedLimit875Test {
    private fun value(n:Int,kind:RoadLimitSource875=RoadLimitSource875.MAP_DATA)=
        RoadLimit875(n,kind,if(kind==RoadLimitSource875.MAP_DATA)"https://www.openstreetmap.org/way/1" else "https://www.lta.gov.sg/", "2026-11-20")
    private fun road(n:Int?=80,id:Long=1,lon:Double=103.8,name:String="Test Road",kind:RoadLimitSource875=RoadLimitSource875.MAP_DATA)=
        RoadSpeedSegment875("$id:0",id,name,"motorway",GpsPoint(1.30,lon),GpsPoint(1.31,lon),
            backwardAllowed=false,forward=n?.let{value(it,kind)})
    private fun fix(t:Long,lat:Double=1.3005,lon:Double=103.8,heading:Float?=0f,speed:Float?=20f,
        accuracy:Float=5f,hint:String?="Test Road",route:Float?=0f)=
        RoadSpeedFix875(GpsPoint(lat,lon),t,accuracy,heading,speed,0.5f,hint,route)
    private fun warm(tracker:RoadSpeedLimitTracker875,r:RoadSpeedSegment875):RoadSpeedState875 {
        tracker.observe(fix(1000),listOf(r));return tracker.observe(fix(2000),listOf(r))
    }
    @Test fun independentlyVerified80Displays80AfterConsistentRoadMatch(){
        assertEquals(80,warm(RoadSpeedLimitTracker875(),road(80,kind=RoadLimitSource875.OFFICIAL)).numberKmh)
    }
    @Test fun independentlyVerified90Displays90(){assertEquals(90,warm(RoadSpeedLimitTracker875(),road(90,kind=RoadLimitSource875.OFFICIAL)).numberKmh)}
    @Test fun verified70TunnelDisplays70(){assertEquals(70,warm(RoadSpeedLimitTracker875(),road(70,kind=RoadLimitSource875.OFFICIAL).copy(tunnel=true,layer=-1)).numberKmh)}
    @Test fun mapNumbersAreExplicitlyLabeledMapData(){val s=warm(RoadSpeedLimitTracker875(),road());assertEquals("MAP DATA",s.badgeLabel);assertTrue(s.sourceNote.contains("OSM"))}
    @Test fun applicableGeneralRuleIsLabeledSeparately(){assertEquals("GENERAL RULE",warm(RoadSpeedLimitTracker875(),road(50,kind=RoadLimitSource875.GENERAL_RULE)).badgeLabel)}
    @Test fun noNumberIsInventedFromGpsSpeedOrRoadClass(){val t=RoadSpeedLimitTracker875();val r=road(null);t.observe(fix(1000,speed=30f),listOf(r));assertNull(t.observe(fix(2000,speed=30f),listOf(r)).numberKmh)}
    @Test fun noCameraCandidateIsRequiredForRoadLimit(){assertEquals(80,warm(RoadSpeedLimitTracker875(),road()).numberKmh)}
    @Test fun startInsideRoadNeedsOnlyTwoValidSamples(){val t=RoadSpeedLimitTracker875();assertNull(t.observe(fix(1000),listOf(road())).numberKmh);assertEquals(80,t.observe(fix(2000),listOf(road())).numberKmh)}
    @Test fun genuine80To90TransitionClearsOldThenConfirmsNew(){
        val t=RoadSpeedLimitTracker875();val a=road().copy(b=GpsPoint(1.301,103.8));val b=road(90,2).copy(a=GpsPoint(1.301,103.8))
        assertEquals(80,warm(t,a).numberKmh)
        assertNull(t.observe(fix(3000,lat=1.302),listOf(a,b)).numberKmh)
        assertEquals(90,t.observe(fix(4000,lat=1.3021),listOf(a,b)).numberKmh)
    }
    @Test fun expresswayToTunnelUpdatesApplicableNumber(){
        val t=RoadSpeedLimitTracker875();warm(t,road(90));val tunnel=road(80,2).copy(tunnel=true,layer=-1)
        t.observe(fix(3000),listOf(tunnel));assertEquals(80,t.observe(fix(4000),listOf(tunnel)).numberKmh)
    }
    @Test fun exitRampDoesNotInheritExpresswayLimit(){
        val t=RoadSpeedLimitTracker875();warm(t,road(90));val ramp=road(50,2,name="Exit ramp").copy(highway="motorway_link")
        assertNull(t.observe(fix(3000,hint="Exit ramp"),listOf(ramp)).numberKmh)
        assertEquals(50,t.observe(fix(4000,hint="Exit ramp"),listOf(ramp)).numberKmh)
    }
    @Test fun enteringUnknownRoadImmediatelyRemovesOldNumber(){val t=RoadSpeedLimitTracker875();warm(t,road());assertNull(t.observe(fix(3000),listOf(road(null,2))).numberKmh)}
    @Test fun oppositeDirectionCameraRoadDoesNotSupplyLimit(){
        val t=RoadSpeedLimitTracker875();val other=road(90,2,lon=103.80003).copy(forwardAllowed=false,backwardAllowed=true,forward=null,backward=value(90))
        t.observe(fix(1000),listOf(road(80),other));assertEquals(80,t.observe(fix(2000),listOf(road(80),other)).numberKmh)
    }
    @Test fun unknownParallelRoadCannotBeSkippedToFindANumber(){
        val t=RoadSpeedLimitTracker875();val known=road(90,1,lon=103.80006);val unknown=road(null,2,name="Other Road")
        t.observe(fix(1000,hint=null),listOf(known,unknown));assertNull(t.observe(fix(2000,hint=null),listOf(known,unknown)).numberKmh)
    }
    @Test fun indistinguishableFlyoverLevelsRemainUnknown(){val t=RoadSpeedLimitTracker875();val top=road(90,2,name="Other Road").copy(layer=1,bridge=true);t.observe(fix(1000),listOf(road(80),top));assertNull(t.observe(fix(2000),listOf(road(80),top)).numberKmh)}
    @Test fun sameRoadBridgeSeamWithSameLimitDoesNotFlicker(){
        val t=RoadSpeedLimitTracker875();val a=road().copy(b=GpsPoint(1.301,103.8));warm(t,a)
        val b=road(80,2).copy(a=GpsPoint(1.301,103.8),layer=1,bridge=true)
        assertEquals(80,t.observe(fix(3000,lat=1.301),listOf(a,b)).numberKmh)
    }
    @Test fun sameLimitContinuesAcrossSplitMappedWaysWithoutBlanking(){
        val t=RoadSpeedLimitTracker875();val a=road().copy(b=GpsPoint(1.301,103.8));warm(t,a)
        val b=road(80,2).copy(a=GpsPoint(1.301,103.8))
        assertEquals(80,t.observe(fix(3000,lat=1.302),listOf(a,b)).numberKmh)
    }
    @Test fun oneNoisyFixKeepsConfidentOriginalRoad(){
        val t=RoadSpeedLimitTracker875();val old=road(80);warm(t,old)
        val other=road(90,2,lon=103.80006,name="Parallel Road")
        assertEquals(80,t.observe(fix(3000,lon=103.800035),listOf(old,other)).numberKmh)
        assertEquals(80,t.observe(fix(4000),listOf(old,other)).numberKmh)
    }
    @Test fun persistentParallelAmbiguityExpiresPreviousLimit(){
        val t=RoadSpeedLimitTracker875();val old=road(80);warm(t,old);val other=road(90,2,lon=103.80006,name="Parallel Road")
        t.observe(fix(3000,lon=103.800035),listOf(old,other));t.observe(fix(4000,lon=103.800035),listOf(old,other))
        assertNull(t.observe(fix(5000,lon=103.800035),listOf(old,other)).numberKmh)
    }
    @Test fun lowAccuracyDoesNotCarryPreviousNumber(){val t=RoadSpeedLimitTracker875();warm(t,road());assertNull(t.observe(fix(3000,accuracy=26f),listOf(road())).numberKmh)}
    @Test fun twentyMetreAccuracyCanUseAnOtherwiseUnambiguousRoad(){val t=RoadSpeedLimitTracker875();t.observe(fix(1000,accuracy=20f),listOf(road()));assertEquals(80,t.observe(fix(2000,accuracy=20f),listOf(road())).numberKmh)}
    @Test fun passedRoadGeometryIsNotExtendedBeyondItsEndpoint(){val t=RoadSpeedLimitTracker875();val r=road().copy(b=GpsPoint(1.301,103.8));warm(t,r);assertNull(t.observe(fix(3000,lat=1.302),listOf(r)).numberKmh)}
    @Test fun staleGpsExpiresNumber(){val t=RoadSpeedLimitTracker875();warm(t,road());assertNull(t.expire(8500).numberKmh)}
    @Test fun gpsGapRequiresNewApproachConfirmation(){val t=RoadSpeedLimitTracker875();warm(t,road());assertNull(t.observe(fix(20000),listOf(road())).numberKmh);assertEquals(80,t.observe(fix(21000),listOf(road())).numberKmh)}
    @Test fun outOfOrderFixCannotOverwriteNewerRoadState(){val t=RoadSpeedLimitTracker875();val s=warm(t,road());assertEquals(s,t.observe(fix(1000),listOf(road(90,2))))}
    @Test fun routeIntentCannotGuessDirectionOnBidirectionalRoad(){val t=RoadSpeedLimitTracker875();val r=road(70).copy(backwardAllowed=true,backward=value(90));t.observe(fix(1000,heading=null,speed=0f),listOf(r));assertNull(t.observe(fix(2000,heading=null,speed=0f),listOf(r)).numberKmh)}
    @Test fun stationarySameLimitInBothDirectionsCanDisplayNumber(){val t=RoadSpeedLimitTracker875();val r=road(80).copy(backwardAllowed=true,backward=value(80));t.observe(fix(1000,heading=null,speed=0f),listOf(r));assertEquals(80,t.observe(fix(2000,heading=null,speed=0f),listOf(r)).numberKmh)}
    @Test fun mapDataNeverCausesVerifiedOverspeedWarning(){val t=RoadSpeedLimitTracker875();for(i in 1..6)t.observe(fix(i*1000L,speed=40f),listOf(road()),true);assertFalse(t.expire(6000).overspeedVisual)}
    @Test fun verifiedOverspeedNeedsReliableRepeatedSpeedAndClearsWithHysteresis(){
        val t=RoadSpeedLimitTracker875();val r=road(80,kind=RoadLimitSource875.OFFICIAL);warm(t,r)
        var s=RoadSpeedState875();for(i in 3..5)s=t.observe(fix(i*1000L,speed=30f),listOf(r),true)
        assertTrue(s.overspeedVisual)
        for(i in 6..8)s=t.observe(fix(i*1000L,speed=15f),listOf(r),true)
        assertFalse(s.overspeedVisual)
    }
    @Test fun numericSourceCannotExceedSingaporeRoadRange(){try{value(150);fail("Out of range")}catch(_:IllegalArgumentException){}}
}
