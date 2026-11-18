package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** B868 regression: workbook source, SQLite offline import, GPS matching, no false legal limits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SpeedLimitReference868Test {
    private val assetPath="src/main/assets/sg_speed_limits_reference_868.csv"
    private val originalPath="src/main/assets/sg_enforcement_cameras.json"
    private fun fakeCamera(id:String="FIXED_SPEED_CAMERA-001")=EnforcementCamera(
        id,"FIXED_SPEED_CAMERA","Published camera",
        GpsPoint(1.298237,103.781679),
        "Ayer Rajah Expressway","City",null,
        "https://www.police.gov.sg/Knowledge-Hub/Traffic/Traffic-Matters/Speed-Enforcement-Camera-Locations",
        "2026-11-18",null,false,null,null,null,
        "PUBLISHED_LOCATION_NOT_LIVE_STATUS",null)
    private fun reference()=SpeedLimitRow868(
        "F-01","FIXED_SPEED_CAMERA-001","Fixed",1,"",
        "Ayer Rajah Expressway towards City",1.298237,103.781679,
        90,"Rujukan (semak papan tanda)","Asal fail",
        "Had laju expressway mungkin berubah mengikut segmen",
        "https://sgpdriver.com/kb/speed-limits","2026-11-18")
    private fun approach(metres:Double,direction:Boolean=true)=CameraApproach(
        fakeCamera(),metres,direction,direction)

    @Test fun all100OriginalExcelExportRowsArePreservedWithFullNotesAndSources(){
        val raw=File(assetPath).readText()
        val records=SpeedLimitCsv868.parse(raw)
        assertEquals(101,records.size)
        assertEquals(13,records[0].size)
        assertEquals(100,records.drop(1).map{it[0]}.toSet().size)
        assertEquals(91,records.drop(1).count{it[7].isNotBlank()})
        assertEquals(9,records.drop(1).count{it[7].isBlank()})
        assertEquals(12,records.drop(1).count{it[9].contains("3 dp")})
        assertTrue(records.drop(1).all {it.size==13 && it[10].isNotBlank() && it[11].isNotBlank()})
    }

    @Test fun all100CsvPointsMatchPublishedCameraSnapshotExactly(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        SpeedLimitReferenceStore868(context).use{db->
            val all=db.all()
            val spf=CameraSafetySnapshotParser.parse(File(originalPath).readText())
            assertEquals(100,all.size)
            assertEquals(341,spf.cameras.size)
            db.verifyCoordinates(all,spf.cameras)
            assertEquals(1.298237,all.first{it.excelId=="F-01"}.latitude,1e-10)
            assertEquals(103.781679,all.first{it.excelId=="F-01"}.longitude,1e-10)
            assertEquals("LTA_SPEED_CAMERA-007-A",all.first{it.excelId=="KM-07A"}.cameraId)
            assertEquals("LTA_SPEED_CAMERA-010",all.first{it.excelId=="KM-10A"}.cameraId)
            assertEquals("AVERAGE_SPEED_ZONE-001-D2",all.first{it.excelId=="A-021"}.cameraId)
            assertEquals(12,all.count{it.reducedPrecision})
            assertEquals(0,all.count{it.isOfficialSegmentVerified})
            assertEquals(91,all.count{it.referenceKmh!=null})
        }
    }

    @Test fun noProvisionalNumberMayBecomeLegalLimitOrOverspeedWarning(){
        val tracker=SpeedLimitTracker868()
        val row=reference()
        assertNull(tracker.observe(listOf(approach(270.0)),listOf(row),7f,30f,0.3f).verifiedKmh)
        val display=tracker.observe(listOf(approach(260.0)),listOf(row),7f,30f,0.3f)
        assertEquals(90,display.referenceKmh)
        assertNull(display.verifiedKmh)
        assertTrue(display.isReferenceOnly)
        assertFalse(display.overspeedVisual)
        assertTrue(display.diagnostics.contains("not verified"))
    }

    @Test fun noReferenceForWrongLaneFlyoverOrReducedPrecision(){
        val tracker=SpeedLimitTracker868()
        val row=reference()
        repeat(4){
            assertNull(tracker.observe(listOf(approach(250.0,false)),
                listOf(row),5f,20f,0.3f).referenceKmh)
        }
        val reduced=row.copy(gpsPrecision="Semak: longitude 3 dp")
        repeat(3){
            assertNull(tracker.observe(listOf(approach(220.0)),
                listOf(reduced),5f,20f,0.3f).referenceKmh)
        }
    }

    @Test fun trackerClearsOnPoorGpsOrMissingDirectionalCandidate(){
        val tracker=SpeedLimitTracker868()
        repeat(2){tracker.observe(listOf(approach(170.0)),
            listOf(reference()),5f,10f,0.3f)}
        assertEquals(90,tracker.observe(listOf(approach(150.0)),
            listOf(reference()),5f,10f,0.3f).referenceKmh)
        assertNull(tracker.observe(emptyList(),listOf(reference()),5f,10f,0.3f).verifiedKmh)
        assertNull(tracker.observe(emptyList(),listOf(reference()),5f,10f,0.3f).referenceKmh)
        repeat(2){tracker.observe(listOf(approach(200.0)),
            listOf(reference()),6f,10f,0.3f)}
        assertNull(tracker.observe(listOf(approach(190.0)),
            listOf(reference()),30f,10f,0.3f).referenceKmh)
    }

    @Test fun futureAuthoritativeSegmentGeometryCanEnableHysteresisWithoutGuessing(){
        val tracker=SpeedLimitTracker868()
        val verified=reference().copy(
            verifiedSegmentId="LTA-TEST-SEGMENT-123",
            verifiedKmh=80,
            verifiedSource="https://www.lta.gov.sg/verified-geometry-example"
        )
        repeat(2){
            assertNull(tracker.observe(listOf(approach(210.0)),listOf(verified),
                5f,26f,0.2f,authoritativeSegmentMatch=false).verifiedKmh)
        }
        repeat(4){tracker.observe(listOf(approach(200.0)),listOf(verified),
            5f,26f,0.2f,authoritativeSegmentMatch=true)}
        val excessive=tracker.observe(listOf(approach(190.0)),
            listOf(verified),5f,26f,0.2f,authoritativeSegmentMatch=true)
        assertEquals(80,excessive.verifiedKmh)
        assertTrue(excessive.overspeedVisual)
        repeat(4){tracker.observe(listOf(approach(170.0)),listOf(verified),
            5f,10f,0.2f,authoritativeSegmentMatch=true)}
        assertFalse(tracker.observe(listOf(approach(160.0)),
            listOf(verified),5f,10f,0.2f,authoritativeSegmentMatch=true).overspeedVisual)
    }

    @Test fun idMappingPreservesAllDirectionsAndOriginalPointNumbers(){
        assertEquals("FIXED_SPEED_CAMERA-020",SpeedLimitSourceId868.cameraId("F-20"))
        assertEquals("LTA_SPEED_CAMERA-010",SpeedLimitSourceId868.cameraId("KM-10"))
        assertEquals("LTA_SPEED_CAMERA-007-B",SpeedLimitSourceId868.cameraId("KM-07B"))
        assertEquals("MOBILE_ENFORCEMENT_LOCATION-005",SpeedLimitSourceId868.cameraId("M-05"))
        assertEquals("AVERAGE_SPEED_ZONE-006-D1",SpeedLimitSourceId868.cameraId("A-016"))
        assertEquals("AVERAGE_SPEED_ZONE-006-D2",SpeedLimitSourceId868.cameraId("A-026"))
        assertEquals("POLICE_LASER_LOCATION-050",SpeedLimitSourceId868.cameraId("L-50"))
    }
}
