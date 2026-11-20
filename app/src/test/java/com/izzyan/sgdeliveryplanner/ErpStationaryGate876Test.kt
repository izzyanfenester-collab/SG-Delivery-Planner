package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class ErpStationaryGate876Test {
    private fun fix(t:Long,speed:Float?=0f,error:Float?=0.2f,accuracy:Float=3f,lat:Double=1.3)=
        ErpMotionFix876(GpsPoint(lat,103.8),t,accuracy,speed,error)
    private fun stopped():ErpStationaryGate876=ErpStationaryGate876().apply{observe(fix(1000));observe(fix(4000))}
    @Test fun oneStationarySampleIsNotEnough(){val g=ErpStationaryGate876();g.observe(fix(1000));assertFalse(g.allowed(1000))}
    @Test fun freshConsistentStationaryFixesAllowInspection(){assertTrue(stopped().allowed(4000))}
    @Test fun shortPauseDoesNotUnlockCard(){val g=ErpStationaryGate876();g.observe(fix(1000));g.observe(fix(1500));assertFalse(g.allowed(1500))}
    @Test fun vehicleMovingImmediatelyLocksCard(){val g=stopped();g.observe(fix(5000,speed=5f));assertFalse(g.allowed(5000))}
    @Test fun staleGpsLocksCard(){assertFalse(stopped().allowed(10000))}
    @Test fun missingSpeedOrUncertainSpeedCannotClaimStationary(){for(f in listOf(fix(5000,speed=null),fix(5000,error=null),fix(5000,speed=0.4f,error=0.8f))){val g=stopped();g.observe(f);assertFalse(g.allowed(5000))}}
    @Test fun poorAccuracyLocksCard(){val g=stopped();g.observe(fix(5000,accuracy=30f));assertFalse(g.allowed(5000))}
    @Test fun gpsGapRequiresStationaryConfirmationAgain(){val g=stopped();g.observe(fix(15000));assertFalse(g.allowed(15000));g.observe(fix(18000));assertTrue(g.allowed(18000))}
    @Test fun positionJumpCannotAppearStationary(){val g=stopped();g.observe(fix(5000,lat=1.301));assertFalse(g.allowed(5000))}
    @Test fun repeatedTimestampDoesNotCountAsAnotherFix(){val g=ErpStationaryGate876();g.observe(fix(1000));g.observe(fix(1000));assertFalse(g.allowed(4000))}
    @Test fun clockBeforeFixIsRejected(){assertFalse(stopped().allowed(3000))}
}
