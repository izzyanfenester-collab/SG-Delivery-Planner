package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class Camera872StorageTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Before fun clean(){context.deleteDatabase("sg_spf_camera_safety.db")}
    @After fun cleanup(){context.deleteDatabase("sg_spf_camera_safety.db")}
    private fun source()=JSONObject(File("src/main/assets/sg_enforcement_cameras.json").readText())

    @Test fun evidenceSurvivesOfflineDatabaseRoundTripWithoutChangingCoordinates() {
        val json=source()
        val rows=json.getJSONArray("cameras")
        val index=(0 until rows.length()).first{rows.getJSONObject(it).getString("category")=="AVERAGE_SPEED_ZONE"}
        val row=rows.getJSONObject(index)
        val id=row.getString("camera_id");val lat=row.getDouble("latitude");val lon=row.getDouble("longitude")
        row.put("average_zone_entry_verified",true)
        row.put("average_zone_evidence_url","https://www.police.gov.sg/test-fixture")
        CameraSafetyStore(context).use {
            assertTrue(it.replace(json.toString()))
            val roundTrip=it.cameras().single{c->c.id==id}
            assertTrue(roundTrip.verifiedZoneBoundary)
            assertEquals(lat,roundTrip.point.lat,0.0);assertEquals(lon,roundTrip.point.lon,0.0)
            assertEquals(341,it.cameras().size)
        }
    }
    @Test fun sourceFlagsWithoutIndividualEvidenceNeverInventVerifiedBoundariesOrDualCapability() {
        val json=source();val rows=json.getJSONArray("cameras")
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            if(row.getString("category")=="AVERAGE_SPEED_ZONE") {
                row.put("average_zone_entry_verified",true)
                row.put("average_zone_evidence_url","https://www.police.gov.sg.evil.example/fixture")
            }
            if(row.getString("category")=="RED_LIGHT_CAMERA") {
                row.put("speed_enforcement_status","VERIFIED_SPEED_ENFORCEMENT")
                row.put("speed_enforcement_evidence_url","https://example.org/fixture")
            }
        }
        val parsed=CameraSafetySnapshotParser.parse(json.toString())
        assertFalse(parsed.cameras.any{it.verifiedZoneBoundary || it.verifiedDualEnforcement})
    }
    @Test fun oldCameraDatabaseMigratesWithoutDeletingRowsOrMetadata() {
        val path=context.getDatabasePath("sg_spf_camera_safety.db")
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            db.execSQL("""CREATE TABLE camera(id TEXT PRIMARY KEY, category TEXT, name TEXT,
                latitude REAL, longitude REAL, road TEXT, direction_text TEXT, junction TEXT,
                bearing REAL, direction_verified INTEGER, speed_limit INTEGER, speed_source TEXT,
                speed_verified_at TEXT, enforcement_status TEXT, zone_id TEXT, source_url TEXT, source_date TEXT)""")
            db.execSQL("CREATE TABLE snapshot_meta(id INTEGER PRIMARY KEY,version TEXT,updated_at TEXT)")
            db.execSQL("""INSERT INTO camera VALUES('PRESERVE','RED_LIGHT_CAMERA','Existing camera',1.3,103.8,
                'Existing Road',NULL,NULL,NULL,0,NULL,NULL,NULL,'POSSIBLE_UNVERIFIED',NULL,
                'https://www.police.gov.sg/test',NULL)""")
            db.execSQL("INSERT INTO snapshot_meta VALUES(1,'existing-snapshot','existing-date')")
            db.version=1
        }
        CameraSafetyStore(context).use { store ->
            val row=store.cameras().single()
            assertEquals("PRESERVE",row.id);assertEquals(1.3,row.point.lat,0.0)
            assertFalse(row.verifiedDualEnforcement);assertFalse(row.verifiedZoneBoundary)
            assertEquals("existing-snapshot" to "existing-date",store.metadata())
            assertEquals(2,store.readableDatabase.version)
        }
    }
    @Test fun savedUserPreferencesRemainOptedOutAcrossReadWrite() {
        val store=CameraSafetyPrefs(context)
        val previous=store.get()
        try {
            val custom=previous.copy(master=false,mobile=false,voice=false,coordinateReminder=false)
            store.save(custom)
            assertEquals(custom,CameraSafetyPrefs(context).get())
        } finally {store.save(previous)}
    }
}
