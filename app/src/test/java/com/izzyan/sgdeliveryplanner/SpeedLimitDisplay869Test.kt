package com.izzyan.sgdeliveryplanner

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * B869 pure UI-model and GPS-source regression tests.
 * The real Android display is bound directly to SpeedLimitDisplay869.render().
 * No synthetic number is treated as a posted legal speed limit.
 */
class SpeedLimitDisplay869Test {
    private fun source(
        id:String="F-01",status:String="Rujukan (semak papan tanda)",
        value:Int?=90,precision:String="Asal fail"
    )=SpeedLimitRow868(
        excelId=id,cameraId="FIXED_SPEED_CAMERA-001",category="Fixed",
        sourceNumber=1,cameraPoint="",locationDirection="Ayer Rajah Expressway towards City",
        latitude=1.298237,longitude=103.781679,
        referenceKmh=value,verificationStatus=status,gpsPrecision=precision,
        notes="Reference only; must check posted signs",sources="https://example.com",
        checkedDate="2026-11-18"
    )

    private val site=EnforcementCamera(
        "FIXED_SPEED_CAMERA-001","FIXED_SPEED_CAMERA","AYE towards City",
        GpsPoint(1.298237,103.781679),"Ayer Rajah Expressway","City",
        null,"https://www.police.gov.sg", "2026-11-18",
        null,false,null,null,null,"PUBLISHED_LOCATION_NOT_LIVE_STATUS",null
    )
    private fun relevant(distance:Double=210.0)=CameraApproach(
        site,distance,directionConfirmed=true,alertAllowed=true)

    private fun stable(row:SpeedLimitRow868,speed:Float=22f):SpeedLimitState868 {
        val tracker=SpeedLimitTracker868()
        tracker.observe(listOf(relevant()),listOf(row),5f,speed,0.25f)
        return tracker.observe(listOf(relevant(195.0)),listOf(row),5f,speed,0.25f)
    }

    @Test fun referenceNumbersAreLargeNumericAndTruthfullyLabeled(){
        val model=SpeedLimitDisplay869.render(stable(source(status="Rujukan (semak papan tanda)",value=90)))
        assertEquals(90,model.numberKmh)
        assertEquals(SpeedLimitBadge869.REFERENCE,model.badge)
        assertEquals("CAMERA-AREA REF",model.note)
        assertTrue(model.hasNumericLimit)
        assertFalse(model.highlightOverspeed)
    }

    @Test fun unverifiedNumericFromSegmentMatchingIsNotOfficial(){
        val model=SpeedLimitDisplay869.render(stable(source(status="Padanan segmen (semak papan tanda)",value=70)))
        assertEquals(70,model.numberKmh)
        assertEquals(SpeedLimitBadge869.UNVERIFIED,model.badge)
        assertFalse(model.highlightOverspeed)
        assertEquals(70,SpeedLimitDisplay869.render(stable(
            source(status="Padanan jalan (semak papan tanda)",value=70))).numberKmh)
    }

    @Test fun unverifiedNumericMarkedForCheckingStillDisplaysWithoutOverspeed(){
        val row=source(status="Perlu semakan",value=60)
        val model=SpeedLimitDisplay869.render(stable(row,35f))
        assertEquals(60,model.numberKmh)
        assertEquals(SpeedLimitBadge869.UNVERIFIED,model.badge)
        assertFalse(model.highlightOverspeed)
        // Untrusted state values cannot force a legal overspeed visual in UI.
        assertFalse(SpeedLimitDisplay869.render(SpeedLimitState868(
            referenceKmh=60,referenceStatus="Perlu semakan",overspeedVisual=true
        )).highlightOverspeed)
    }

    @Test fun nineBlankSpeedValuesNeverBecomeZeroOrAssumedFifty(){
        val state=stable(source(status="Perlu semakan",value=null))
        val model=SpeedLimitDisplay869.render(state)
        assertNull(model.numberKmh)
        assertEquals(SpeedLimitBadge869.UNKNOWN,model.badge)
        assertFalse(model.hasNumericLimit)
        assertFalse(model.highlightOverspeed)
    }

    @Test fun verifiedOnlyAfterAuthoritativeSegmentEvidenceAndRealGpsMatch(){
        val row=source().copy(
            verifiedSegmentId="TEST-AUTHORITY-SEGMENT",
            verifiedKmh=80,
            verifiedSource="https://www.lta.gov.sg/test-official-direction-specific-segment"
        )
        val tracker=SpeedLimitTracker868()
        tracker.observe(listOf(relevant()),listOf(row),5f,30f,0.2f,authoritativeSegmentMatch=false)
        val notYet=tracker.observe(listOf(relevant(200.0)),listOf(row),5f,30f,0.2f,authoritativeSegmentMatch=false)
        assertEquals(SpeedLimitBadge869.REFERENCE,SpeedLimitDisplay869.render(notYet).badge)
        repeat(4){tracker.observe(listOf(relevant()),listOf(row),5f,30f,0.2f,authoritativeSegmentMatch=true)}
        val verified=SpeedLimitDisplay869.render(tracker.observe(
            listOf(relevant()),listOf(row),5f,30f,0.2f,authoritativeSegmentMatch=true))
        assertEquals(80,verified.numberKmh)
        assertEquals(SpeedLimitBadge869.VERIFIED,verified.badge)
        assertTrue(verified.highlightOverspeed)
    }

    @Test fun wrongDirectionOrCrossingRoadCannotDisplayReferenceNumber(){
        val wrongDirection=CameraApproach(site,200.0,false,false)
        val tracker=SpeedLimitTracker868()
        repeat(3){val state=tracker.observe(listOf(wrongDirection),listOf(source()),5f,30f,0.2f)
            assertNull(SpeedLimitDisplay869.render(state).numberKmh)}
        assertEquals(SpeedLimitBadge869.UNKNOWN,SpeedLimitDisplay869.render(
            tracker.observe(emptyList(),listOf(source()),5f,30f,0.2f)).badge)
    }

    @Test fun lowAccuracyOrPrecisionThreeDecimalPointsCannotOfferCameraSpeed(){
        val tracker=SpeedLimitTracker868()
        repeat(2){tracker.observe(listOf(relevant()),listOf(source()),5f,20f,0.2f)}
        val poor=tracker.observe(listOf(relevant()),listOf(source()),25f,20f,0.2f)
        assertEquals(SpeedLimitBadge869.UNKNOWN,SpeedLimitDisplay869.render(poor).badge)
        val lowPrecision=source(precision="longitude 3 dp")
        repeat(3){
            assertEquals(SpeedLimitBadge869.UNKNOWN,SpeedLimitDisplay869.render(
                tracker.observe(listOf(relevant()),listOf(lowPrecision),5f,20f,0.2f)).badge)
        }
    }

    @Test fun previousCameraValueClearsImmediatelyAfterRoadMatchIsLost(){
        val tracker=SpeedLimitTracker868()
        tracker.observe(listOf(relevant()),listOf(source()),5f,20f,0.2f)
        val first=tracker.observe(listOf(relevant()),listOf(source()),5f,20f,0.2f)
        assertEquals(90,SpeedLimitDisplay869.render(first).numberKmh)
        val cleared=tracker.observe(emptyList(),listOf(source()),5f,20f,0.2f)
        assertEquals(SpeedLimitBadge869.UNKNOWN,SpeedLimitDisplay869.render(cleared).badge)
        assertNull(SpeedLimitDisplay869.render(cleared).numberKmh)
    }

    @Test fun originalWorkbookClassificationRemainsUnmodified(){
        val entries=SpeedLimitCsv868.parse(
            File("src/main/assets/sg_speed_limits_reference_868.csv").readText()).drop(1)
        assertEquals(100,entries.size)
        assertEquals(20,entries.count{it[8]=="Rujukan (semak papan tanda)"})
        assertEquals(12,entries.count{it[8].startsWith("Rujukan zon")})
        assertEquals(13,entries.count{it[8].startsWith("Padanan segmen")})
        assertEquals(46,entries.count{it[8].startsWith("Padanan jalan")})
        assertEquals(9,entries.count{it[8]=="Perlu semakan" && it[7].isBlank()})
        assertEquals(91,entries.count{it[7].isNotBlank()})
    }
}
