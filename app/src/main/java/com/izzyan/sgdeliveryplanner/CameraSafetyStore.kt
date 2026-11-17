package com.izzyan.sgdeliveryplanner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** A dedicated SQLite/Room-equivalent camera store, separate from all user delivery data.
 * Validated source snapshot is imported in ONE transaction; on failure old data survives.
 */
internal class CameraSafetyStore(context:Context):SQLiteOpenHelper(
    context.applicationContext,"sg_spf_camera_safety.db",null,1) {
    private val app=context.applicationContext
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE camera (
            id TEXT PRIMARY KEY NOT NULL, category TEXT NOT NULL, name TEXT NOT NULL,
            latitude REAL NOT NULL, longitude REAL NOT NULL,
            road TEXT NOT NULL, direction_text TEXT, junction TEXT,
            bearing REAL, direction_verified INTEGER NOT NULL DEFAULT 0,
            speed_limit INTEGER, speed_source TEXT, speed_verified_at TEXT,
            enforcement_status TEXT NOT NULL, zone_id TEXT,
            source_url TEXT NOT NULL, source_date TEXT)""")
        db.execSQL("CREATE INDEX camera_location ON camera(latitude, longitude)")
        db.execSQL("CREATE INDEX camera_category ON camera(category)")
        db.execSQL("CREATE TABLE snapshot_meta (id INTEGER PRIMARY KEY CHECK(id=1), version TEXT NOT NULL, updated_at TEXT NOT NULL)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        // Never drop user or camera data on an unrecognized schema change.
        if(oldVersion!=newVersion) throw IllegalStateException("Manual camera schema migration required")
    }
    @Synchronized fun isPopulated():Boolean = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM camera",null).use { it.moveToFirst() && it.getInt(0)>0 }
    @Synchronized fun ensureBundled():Boolean {
        if(isPopulated())return true
        return runCatching {
            val json=app.assets.open("sg_enforcement_cameras.json")
                .bufferedReader().use { it.readText() }
            replace(json)
        }.getOrDefault(false)
    }
    @Synchronized fun replace(json:String):Boolean {
        // Parse and validate before entering the transaction or changing any existing row.
        val validated=CameraSafetySnapshotParser.parse(json)
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.delete("camera",null,null)
            for(camera in validated.cameras) {
                val row=ContentValues().apply {
                    put("id",camera.id);put("category",camera.category);put("name",camera.name)
                    put("latitude",camera.point.lat);put("longitude",camera.point.lon)
                    put("road",camera.road);put("direction_text",camera.directionText)
                    put("junction",camera.junction);put("bearing",camera.directionBearing)
                    put("direction_verified",if(camera.directionVerified)1 else 0)
                    put("speed_limit",camera.speedLimitKmh);put("speed_source",camera.speedLimitSource)
                    put("speed_verified_at",camera.speedLimitVerifiedAt)
                    put("enforcement_status",camera.speedEnforcementStatus)
                    put("zone_id",camera.zoneId)
                    put("source_url",camera.sourceUrl);put("source_date",camera.sourcePublicationDate)
                }
                if(db.insertOrThrow("camera",null,row)<0)error("Unable to insert validated camera")
            }
            val meta=ContentValues().apply {
                put("id",1);put("version",validated.version);put("updated_at",validated.refreshedAt)
            }
            db.insertWithOnConflict("snapshot_meta",null,meta,SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }
    @Synchronized fun metadata():Pair<String,String> {
        return readableDatabase.rawQuery("SELECT version,updated_at FROM snapshot_meta WHERE id=1",null)
            .use { if(it.moveToFirst())it.getString(0) to it.getString(1) else "Not installed" to "Never" }
    }
    @Synchronized fun cameras():List<EnforcementCamera> {
        val results=ArrayList<EnforcementCamera>()
        readableDatabase.rawQuery("""SELECT id,category,name,latitude,longitude,road,direction_text,
            junction,bearing,direction_verified,speed_limit,speed_source,speed_verified_at,
            enforcement_status,zone_id,source_url,source_date FROM camera""",null).use { c ->
            while(c.moveToNext()) {
                fun nullable(i:Int)=if(c.isNull(i))null else c.getString(i)
                results += EnforcementCamera(
                    id=c.getString(0),category=c.getString(1),name=c.getString(2),
                    point=GpsPoint(c.getDouble(3),c.getDouble(4)),road=c.getString(5),
                    directionText=nullable(6),junction=nullable(7),
                    directionBearing=if(c.isNull(8))null else c.getDouble(8),
                    directionVerified=c.getInt(9)==1,
                    speedLimitKmh=if(c.isNull(10))null else c.getInt(10),
                    speedLimitSource=nullable(11),speedLimitVerifiedAt=nullable(12),
                    speedEnforcementStatus=c.getString(13),zoneId=nullable(14),
                    sourceUrl=c.getString(15),sourcePublicationDate=nullable(16))
            }
        }
        return results
    }
}

internal data class CameraAlertPreferences(
    val master:Boolean=true, val speed:Boolean=true, val redLight:Boolean=true,
    val mobile:Boolean=true, val voice:Boolean=true,val icons:Boolean=true,
    val speedLimitDisplay:Boolean=true,val overspeed:Boolean=false
) {
    fun categoryEnabled(category:String)=master && when(category) {
        "RED_LIGHT_CAMERA"->redLight
        "MOBILE_ENFORCEMENT_LOCATION","POLICE_LASER_LOCATION"->mobile
        else->speed
    }
}
internal class CameraSafetyPrefs(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("sg_camera_safety_865",Context.MODE_PRIVATE)
    fun get()=CameraAlertPreferences(
        prefs.getBoolean("master",true),prefs.getBoolean("speed",true),
        prefs.getBoolean("redLight",true),prefs.getBoolean("mobile",true),
        prefs.getBoolean("voice",true),prefs.getBoolean("icons",true),
        prefs.getBoolean("speedLimitDisplay",true),prefs.getBoolean("overspeed",false))
    fun save(value:CameraAlertPreferences){
        prefs.edit().putBoolean("master",value.master).putBoolean("speed",value.speed)
            .putBoolean("redLight",value.redLight).putBoolean("mobile",value.mobile)
            .putBoolean("voice",value.voice).putBoolean("icons",value.icons)
            .putBoolean("speedLimitDisplay",value.speedLimitDisplay)
            .putBoolean("overspeed",value.overspeed).apply()
    }
}

/** Weekly online check against this TESTING branch's manually SPF-validated
 * JSON snapshot. Never scrape untrusted HTML on the device, never invent limits.
 */
internal object CameraSafetyRemote {
    const val URL="https://raw.githubusercontent.com/izzyanfenester-collab/SG-Delivery-Planner/feature/build-865-camera-safety-testing/app/src/main/assets/sg_enforcement_cameras.json"
    fun download():String {
        val connection=(URL(URL).openConnection() as HttpURLConnection)
        connection.connectTimeout=10000
        connection.readTimeout=12000
        connection.setRequestProperty("Accept","application/json")
        connection.setRequestProperty("User-Agent","SG-Delivery-Camera-Safety-865")
        try {
            if(connection.responseCode!=200)error("Camera data not published (HTTP ${connection.responseCode})")
            val length=connection.contentLength
            if(length>2_000_000)error("Camera data exceeds safety limit")
            return connection.inputStream.bufferedReader().use{r->
                r.readText().also { require(it.length<2_000_000) }
            }
        } finally{connection.disconnect()}
    }
}

internal class CameraSafetyUpdateWorker(context:Context,params:WorkerParameters):
    CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        runCatching {
            CameraSafetyStore(applicationContext).use { store ->
                store.ensureBundled()
                store.replace(CameraSafetyRemote.download())
            }
        }.fold({Result.success()},{Result.retry()})
    }
}
internal object CameraSafetyUpdateScheduler {
    fun schedule(context:Context){
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request=PeriodicWorkRequestBuilder<CameraSafetyUpdateWorker>(7,TimeUnit.DAYS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "sg_official_spf_camera_update_865",ExistingPeriodicWorkPolicy.KEEP,request)
    }
}
