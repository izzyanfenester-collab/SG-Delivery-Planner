package com.izzyan.sgdeliveryplanner

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** B865 synthetic route-replay tests. Not a substitute for road/device verification. */
class CameraSafety865Test {
    private val p=GpsPoint(1.3000,103.8050)
    private val path=listOf(GpsPoint(1.3000,103.8000),GpsPoint(1.3000,103.8100))
    private fun camera(id:String,category:String="FIXED_SPEED_CAMERA",direction:String?="Next Road",
                       point:GpsPoint=p,road:String="Alpha Road")=EnforcementCamera(
        id,category,"Alpha Road towards Next Road",point,road,direction,null,
        "https://www.police.gov.sg/Knowledge-Hub/Traffic/Traffic-Matters/Speed-Enforcement-Camera-Locations",
        "2026-07-01",null,false,null,null,null,
        "PUBLISHED_LOCATION_NOT_LIVE_STATUS",null)
    @Test fun bundledSpfSnapshotHasExactPublishedCategoriesAndNoInventedSpeedLimits() {
        val f=File("src/main/assets/sg_enforcement_cameras.json")
        assertTrue("Validated SPF snapshot must be bundled before building",f.exists())
        val snapshot=CameraSafetySnapshotParser.parse(f.readText())
        assertEquals(341,snapshot.cameras.size)
        assertEquals(241,snapshot.redLightCount)
        assertEquals(20,snapshot.cameras.count{it.category=="FIXED_SPEED_CAMERA"})
        assertEquals(13,snapshot.cameras.count{it.category=="LTA_SPEED_CAMERA"})
        assertEquals(5,snapshot.cameras.count{it.category=="MOBILE_ENFORCEMENT_LOCATION"})
        assertEquals(12,snapshot.cameras.count{it.category=="AVERAGE_SPEED_ZONE"})
        assertEquals(50,snapshot.cameras.count{it.category=="POLICE_LASER_LOCATION"})
        assertTrue(snapshot.cameras.all{it.speedLimitKmh==null && it.directionBearing==null})
        assertTrue(snapshot.cameras.filter{it.isRedLight}.all{
            it.speedEnforcementStatus=="POSSIBLE_UNVERIFIED"})
    }
    @Test fun projectionUsesRouteDistanceNotStraightLineDistance() {
        val projection=CameraRouteGeometry.project(p,path)!!
        assertTrue(projection.lateralMetres<0.1)
        assertTrue(projection.progressedMetres in 540.0..570.0)
        assertTrue(CameraRouteGeometry.angularGap(350.0,10.0)==20.0)
    }
    @Test fun publishedTextDirectionNeedsMatchingRoadAndUpcomingRoute() {
        val current=GpsPoint(1.3,103.803)
        val valid=camera("ONE")
        val reverse=camera("OPPOSITE",direction="Another Road")
        val otherStreet=camera("WRONGROAD",road="Nearby Service Road")
        val adjacent=camera("FLYOVER",point=GpsPoint(1.301,103.805))
        val found=CameraApproachLogic.find(current,path,"Alpha Rd",
            listOf("Next Road"),8f,listOf(valid,reverse,otherStreet,adjacent),16f)
        assertEquals(2,found.size)
        assertTrue(found.first{it.camera.id=="ONE"}.alertAllowed)
        assertFalse(found.first{it.camera.id=="OPPOSITE"}.alertAllowed)
    }
    @Test fun unknownBearingAndUncorroboratedDestinationNeverGiveAudibleWarnings() {
        val found=CameraApproachLogic.find(GpsPoint(1.3,103.803),path,"Alpha Road",
            listOf("Unrelated Street"),7f,listOf(camera("NOTVERIFIED")),15f)
        assertEquals(1,found.size)
        assertFalse(found.single().directionConfirmed)
        assertFalse(found.single().alertAllowed)
    }
    @Test fun offRouteOrLowAccuracyPreventsCameraApproach() {
        val current=GpsPoint(1.3,103.803)
        assertTrue(CameraApproachLogic.find(current,path,"Alpha Rd",
            listOf("Next Road"),37f,listOf(camera("A")),15f).isEmpty())
        assertTrue(CameraApproachLogic.find(GpsPoint(1.306,103.803),path,
            "Alpha Rd",listOf("Next Road"),8f,listOf(camera("A")),15f).isEmpty())
        val behind=CameraApproachLogic.find(GpsPoint(1.3,103.807),path,
            "Alpha Rd",listOf("Next Road"),8f,listOf(camera("A")),15f)
        assertTrue(behind.isEmpty())
    }
    @Test fun cameraVoiceGateSuppressesRepeatAndResetsOnReroute(){
        val approach=CameraApproach(camera("TEST"),250.0,true,true)
        val gate=CameraAlertGate()
        assertTrue(gate.shouldPlay(approach,true))
        assertFalse(gate.shouldPlay(approach,true))
        gate.reset()
        assertTrue(gate.shouldPlay(approach,true))
        assertFalse(gate.shouldPlay(approach,false))
    }
}
