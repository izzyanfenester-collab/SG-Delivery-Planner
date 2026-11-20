package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RoadSpeedStore875Test {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    @Before fun cleanCatalog(){File(context.noBackupFilesDir,"road-speed-875").deleteRecursively()}
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun fixture(version:Long,kmh:Int=80):Pair<RoadSpeedManifest875,ByteArray> {
        val file=File.createTempFile("road-fixture-",".db",context.cacheDir);file.delete()
        SQLiteDatabase.openOrCreateDatabase(file,null).use{db->
            db.rawQuery("PRAGMA journal_mode=DELETE",null).use{it.moveToFirst()}
            db.execSQL("PRAGMA application_id=1397183308");db.execSQL("PRAGMA user_version=1")
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE road_way(id INTEGER PRIMARY KEY,name TEXT,highway TEXT,layer INTEGER,tunnel INTEGER,bridge INTEGER,forward_allowed INTEGER,backward_allowed INTEGER,forward_kmh INTEGER,backward_kmh INTEGER,forward_kind TEXT,backward_kind TEXT,forward_source TEXT,backward_source TEXT,checked_date TEXT,restrictions TEXT,reason TEXT)")
            db.execSQL("CREATE TABLE road_edge(id INTEGER PRIMARY KEY,source_id TEXT,way_id INTEGER REFERENCES road_way(id),lat1 REAL,lon1 REAL,lat2 REAL,lon2 REAL,verified_kmh INTEGER,verified_source TEXT,verified_date TEXT)")
            db.execSQL("CREATE TABLE edge_cell(cell INTEGER,edge_id INTEGER REFERENCES road_edge(id),PRIMARY KEY(cell,edge_id))")
            val info=JSONObject().put("version",version).put("ways",1).put("edges",1).put("known_directions",1).put("checked_date","2026-11-20").toString()
            for((key,value)in mapOf("version" to version.toString(),"jurisdiction" to "SG","info" to info,
                "boundary" to """{"type":"MultiPolygon","coordinates":[[[[103.7,1.2],[103.9,1.2],[103.9,1.5],[103.7,1.5],[103.7,1.2]]]]}"""))
                db.execSQL("INSERT INTO metadata VALUES (?,?)",arrayOf(key,value))
            db.execSQL("INSERT INTO road_way VALUES (1,'Fixture Road','primary',0,0,0,1,0,?,NULL,'MAP_DATA',NULL,'https://www.openstreetmap.org/way/1',NULL,'2026-11-20','','Unknown')",arrayOf(kmh))
            db.execSQL("INSERT INTO road_edge VALUES (1,'1:0',1,1.30,103.8,1.31,103.8,NULL,NULL,NULL)")
            for(y in 1300..1310)db.execSQL("INSERT INTO edge_cell VALUES (?,1)",arrayOf(y*1_000_000L+103800))
        }
        val bytes=file.readBytes();file.delete();val out=ByteArrayOutputStream()
        GZIPOutputStream(out).use{it.write(bytes)};val zipped=out.toByteArray()
        return RoadSpeedManifest875(version,hash(bytes),hash(zipped),bytes.size.toLong(),zipped.size.toLong(),1,1,1,"2026-11-20") to zipped
    }
    @Test fun badOrTruncatedUpdatePreservesPreviousValidOfflineDataAndUserPreferences(){
        context.getSharedPreferences("user-data-test",0).edit().putString("delivery","saved").commit()
        val first=fixture(1);val next=fixture(2,90)
        RoadSpeedStore875(context).use{store->
            assertTrue(store.install(ByteArrayInputStream(first.second),first.first))
            assertEquals(80,store.candidates(GpsPoint(1.3005,103.8)).single().forward?.kmh)
            try{store.install(ByteArrayInputStream(next.second.copyOf(next.second.size/2)),next.first);fail("Truncation must fail")}catch(_:Exception){}
            assertEquals(80,store.candidates(GpsPoint(1.3005,103.8)).single().forward?.kmh)
            assertEquals("saved",context.getSharedPreferences("user-data-test",0).getString("delivery",null))
        }
    }
    @Test fun validatedNewSnapshotRefreshesLookupsAndDowngradeIsIgnored(){
        val a=fixture(1);val b=fixture(2,90)
        RoadSpeedStore875(context).use{reader->
            reader.install(ByteArrayInputStream(a.second),a.first)
            assertEquals(80,reader.candidates(GpsPoint(1.3005,103.8)).single().forward?.kmh)
            RoadSpeedStore875(context).use{writer->assertTrue(writer.install(ByteArrayInputStream(b.second),b.first))}
            assertEquals(90,reader.candidates(GpsPoint(1.3005,103.8)).single().forward?.kmh)
            assertFalse(reader.install(ByteArrayInputStream(a.second),a.first))
            assertTrue(reader.candidates(GpsPoint(1.6,103.8)).isEmpty())
        }
    }
    @Test fun invalidNumericSnapshotWithMatchingHashesCannotReplaceGoodData(){
        val a=fixture(1);val invalid=fixture(2,120)
        RoadSpeedStore875(context).use{store->
            assertTrue(store.install(ByteArrayInputStream(a.second),a.first))
            try{store.install(ByteArrayInputStream(invalid.second),invalid.first);fail("Invalid legal speed must fail validation")}catch(_:IllegalArgumentException){}
            assertEquals(80,store.candidates(GpsPoint(1.3005,103.8)).single().forward?.kmh)
        }
    }
    @Test fun actualBundledOfflineCatalogDrives70_80_90GpsReplayWithoutCameraAlerts(){
        val old=CameraSafetyPrefs(context).get();CameraSafetyPrefs(context).save(old.copy(master=false))
        RoadSpeedStore875(context).use{store->
            val meta=store.ensureBundled();assertTrue(meta.ways>10000);assertTrue(meta.knownDirections>10000)
            val file=File(context.noBackupFilesDir,"road-speed-875/current.db")
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use{db->
                for(kmh in listOf(70,80,90)){
                    var seen=false
                    db.rawQuery("""SELECT e.lat1,e.lon1,e.lat2,e.lon2,w.name,w.tunnel FROM road_edge e JOIN road_way w ON w.id=e.way_id
                        WHERE w.highway='motorway' AND w.forward_allowed=1 AND w.forward_kmh=?
                        AND (?!='70' OR w.tunnel=1)
                        ORDER BY (e.verified_kmh IS NOT NULL OR w.forward_kind='OFFICIAL') DESC,e.id
                        LIMIT 240""".trimIndent(),arrayOf(kmh.toString(),kmh.toString())).use{c->
                        while(c.moveToNext()&&!seen){
                            val a=GpsPoint(c.getDouble(0),c.getDouble(1));val b=GpsPoint(c.getDouble(2),c.getDouble(3))
                            val point=GpsPoint((a.lat+b.lat)/2,(a.lon+b.lon)/2)
                            val bearing=RoadSpeedGeometry875.project(point,a,b)?.bearing?:continue
                            val roads=store.candidates(point);val tracker=RoadSpeedLimitTracker875()
                            val fix=RoadSpeedFix875(point,1000,3f,bearing,20f,0.5f,c.getString(4),bearing)
                            tracker.observe(fix,roads);val result=tracker.observe(fix.copy(timeMs=2000),roads)
                            if(result.numberKmh==kmh){seen=true;println("B875 OFFLINE_REAL_DATA db=${meta.version} road=${c.getString(4)} segment=${result.segmentId} gps=$point accuracy=3m timeMs=2000 heading=$bearing value=$kmh source=${result.limit?.kind} tunnel=${c.getInt(5)} cameraMaster=OFF")}
                        }
                    }
                    assertTrue("No confident real $kmh km/h segment replay",seen)
                }
            }
        }
        CameraSafetyPrefs(context).save(old)
    }
}
