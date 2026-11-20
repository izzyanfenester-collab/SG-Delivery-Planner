package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.floor

internal data class RoadSpeedManifest875(val version:Long,val sha256:String,val gzipSha256:String,
    val bytes:Long,val gzipBytes:Long,val ways:Int,val edges:Int,val knownDirections:Int,val checkedDate:String) {
    companion object {
        fun parse(text:String):RoadSpeedManifest875 {
            require(text.length<=100_000)
            val j=JSONObject(text);require(j.getInt("schema")==1 && j.getString("jurisdiction")=="SG")
            return RoadSpeedManifest875(j.getLong("version"),j.getString("sha256"),j.getString("gzip_sha256"),
                j.getLong("bytes"),j.getLong("gzip_bytes"),j.getInt("ways"),j.getInt("edges"),
                j.getInt("known_directions"),j.getString("checked_date")).also {
                require(it.version>0 && it.sha256.matches(Regex("[a-f0-9]{64}")) && it.gzipSha256.matches(Regex("[a-f0-9]{64}")))
                require(it.bytes in 4096..268_435_456 && it.gzipBytes in 100..67_108_864)
                require(it.ways in 1..400_000 && it.edges in 1..3_000_000 && it.knownDirections in 0..800_000)
                require(it.checkedDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            }
        }
    }
}
internal data class RoadSpeedCatalogInfo875(val version:Long,val ways:Int,val edges:Int,
    val knownDirections:Int,val checkedDate:String)

/** Dedicated read-only catalog; never opens or migrates the user's delivery/payment databases. */
internal class RoadSpeedStore875(context:Context):Closeable {
    private val app=context.applicationContext
    // Reconstructible map data must not inflate delivery/user-data backups.
    private val folder=File(app.noBackupFilesDir,"road-speed-875").apply{mkdirs()}
    private val current=File(folder,"current.db")
    private var db:SQLiteDatabase?=null
    private var stamp=0L
    private var jurisdiction:List<List<List<GpsPoint>>> = emptyList()
    var metadata:RoadSpeedCatalogInfo875?=null; private set
    private fun open(file:File)=SQLiteDatabase.openDatabase(file.path,null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    private fun scalar(db:SQLiteDatabase,sql:String):Long=db.rawQuery(sql,null).use{require(it.moveToFirst());it.getLong(0)}
    private fun meta(db:SQLiteDatabase,key:String):String=db.rawQuery("SELECT value FROM metadata WHERE key=?",arrayOf(key))
        .use{require(it.moveToFirst());it.getString(0)}
    private fun info(db:SQLiteDatabase):RoadSpeedCatalogInfo875 {
        val j=JSONObject(meta(db,"info"))
        return RoadSpeedCatalogInfo875(j.getLong("version"),j.getInt("ways"),j.getInt("edges"),
            j.getInt("known_directions"),j.getString("checked_date"))
    }
    private fun readVersion(file:File):Long?=runCatching{open(file).use{meta(it,"version").toLong()}}.getOrNull()

    @Synchronized fun ensureBundled():RoadSpeedCatalogInfo875 {
        if(metadata!=null && current.isFile){reopen();return requireNotNull(metadata)}
        val bundled=app.assets.open("sg_road_speed_limits_875_manifest.json").bufferedReader().use{RoadSpeedManifest875.parse(it.readText())}
        synchronized(installLock) {
            val existing=if(current.isFile)readVersion(current) else null
            if(existing==null || existing<bundled.version) {
                try {app.assets.open("sg_road_speed_limits_875.db.gz").use{install(it,bundled,false)}}
                catch(e:Exception){if(existing==null)throw e
                    android.util.Log.w("SGRoadSpeed875","Bundled update invalid; keeping previous valid catalog",e)}
            }
        }
        reopen()
        return requireNotNull(metadata)
    }

    /** Validate all bytes and schema before AtomicFile replaces the previous good snapshot. */
    fun install(input:InputStream,expected:RoadSpeedManifest875,downloaded:Boolean=true):Boolean = synchronized(installLock) {
        val old=if(current.isFile)readVersion(current) else null
        if(downloaded && old!=null && expected.version<=old)return@synchronized false
        if(downloaded && old!=null)open(current).use{existing->val prior=info(existing)
            require(expected.ways>=prior.ways*0.80 && expected.knownDirections>=prior.knownDirections*0.70){"Unexpected catalog coverage collapse"}}
        val compressed=File.createTempFile("download-",".gz",folder)
        val candidate=File.createTempFile("validate-",".db",folder)
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            var size=0L
            FileOutputStream(compressed).use{out->
                val buffer=ByteArray(65536)
                while(true){val n=input.read(buffer);if(n<0)break;size+=n
                    require(size<=expected.gzipBytes && size<=67_108_864);digest.update(buffer,0,n);out.write(buffer,0,n)}
                out.fd.sync()
            }
            require(size==expected.gzipBytes && hex(digest.digest())==expected.gzipSha256){"Compressed speed catalog hash mismatch"}
            val rawHash=MessageDigest.getInstance("SHA-256");var rawSize=0L
            GZIPInputStream(compressed.inputStream()).use{gzip->FileOutputStream(candidate).use{out->
                val buffer=ByteArray(65536)
                while(true){val n=gzip.read(buffer);if(n<0)break;rawSize+=n
                    require(rawSize<=expected.bytes && rawSize<=268_435_456);rawHash.update(buffer,0,n);out.write(buffer,0,n)}
                out.fd.sync()
            }}
            require(rawSize==expected.bytes && hex(rawHash.digest())==expected.sha256){"Speed catalog hash mismatch"}
            open(candidate).use{checked->
                require(scalar(checked,"PRAGMA application_id")==0x5347534cL && scalar(checked,"PRAGMA user_version")==1L)
                require(checked.rawQuery("PRAGMA quick_check",null).use{it.moveToFirst()&&it.getString(0)=="ok"})
                require(scalar(checked,"SELECT COUNT(*) FROM sqlite_master WHERE type IN ('view','trigger')")==0L)
                require(meta(checked,"version").toLong()==expected.version && meta(checked,"jurisdiction")=="SG")
                require(scalar(checked,"SELECT COUNT(*) FROM road_way")==expected.ways.toLong())
                require(scalar(checked,"SELECT COUNT(*) FROM road_edge")==expected.edges.toLong())
                require(scalar(checked,"SELECT COUNT(*) FROM road_way WHERE forward_kmh IS NOT NULL")+
                    scalar(checked,"SELECT COUNT(*) FROM road_way WHERE backward_kmh IS NOT NULL")==expected.knownDirections.toLong())
                require(scalar(checked,"SELECT COUNT(*) FROM road_way WHERE forward_kmh NOT BETWEEN 5 AND 90 OR backward_kmh NOT BETWEEN 5 AND 90")==0L)
                require(scalar(checked,"""SELECT COUNT(*) FROM road_way WHERE
                    (forward_kmh IS NOT NULL AND (forward_kind IS NULL OR forward_source IS NULL OR forward_kind NOT IN ('OFFICIAL','MAP_DATA','GENERAL_RULE') OR forward_source NOT LIKE 'https://%')) OR
                    (backward_kmh IS NOT NULL AND (backward_kind IS NULL OR backward_source IS NULL OR backward_kind NOT IN ('OFFICIAL','MAP_DATA','GENERAL_RULE') OR backward_source NOT LIKE 'https://%'))""")==0L)
                require(scalar(checked,"SELECT COUNT(*) FROM road_edge WHERE lat1 NOT BETWEEN 1.1 AND 1.6 OR lat2 NOT BETWEEN 1.1 AND 1.6 OR lon1 NOT BETWEEN 103.5 AND 104.3 OR lon2 NOT BETWEEN 103.5 AND 104.3")==0L)
                require(!checked.rawQuery("PRAGMA foreign_key_check",null).use{it.moveToFirst()})
                require(info(checked)==RoadSpeedCatalogInfo875(expected.version,expected.ways,expected.edges,expected.knownDirections,expected.checkedDate))
                require(scalar(checked,"""SELECT COUNT(*) FROM road_edge e JOIN road_way w ON w.id=e.way_id
                    WHERE e.verified_kmh IS NOT NULL AND (e.verified_kmh NOT BETWEEN 5 AND 90 OR
                    e.verified_source NOT LIKE 'https://data.gov.sg/%' OR e.verified_date IS NULL OR
                    w.forward_allowed+w.backward_allowed!=1 OR e.verified_kmh!=COALESCE(w.forward_kmh,w.backward_kmh))""")==0L)
                parseJurisdiction(meta(checked,"boundary"))
            }
            val atomic=AtomicFile(current)
            val stream=atomic.startWrite()
            try {candidate.inputStream().use{it.copyTo(stream)};atomic.finishWrite(stream)}
            catch(t:Throwable){atomic.failWrite(stream);throw t}
            generation++
            true
        } finally {compressed.delete();candidate.delete()}
    }

    @Synchronized private fun reopen() = synchronized(installLock) {
        val next=current.lastModified() xor current.length() xor generation
        if(db!=null && stamp==next)return@synchronized
        db?.close();db=open(current);stamp=next
        val opened=requireNotNull(db)
        metadata=info(opened)
        jurisdiction=parseJurisdiction(meta(opened,"boundary"))
    }
    @Synchronized fun candidates(point:GpsPoint):List<RoadSpeedSegment875> {
        if(!current.isFile)return emptyList()
        reopen()
        if(!contains(point))return emptyList()
        val x=floor(point.lon*1000).toLong();val y=floor(point.lat*1000).toLong()
        val cells=ArrayList<String>()
        for(dy in -1..1)for(dx in -1..1)cells+=( (y+dy)*1_000_000L+x+dx).toString()
        val marks=cells.joinToString(","){"?"}
        val sql="""SELECT DISTINCT e.source_id,w.id,w.name,w.highway,e.lat1,e.lon1,e.lat2,e.lon2,
            w.layer,w.tunnel,w.bridge,w.forward_allowed,w.backward_allowed,
            w.forward_kmh,w.backward_kmh,w.forward_kind,w.backward_kind,w.forward_source,w.backward_source,
            w.checked_date,w.restrictions,w.reason,e.verified_kmh,e.verified_source,e.verified_date
            FROM edge_cell c JOIN road_edge e ON e.id=c.edge_id JOIN road_way w ON w.id=e.way_id
            WHERE c.cell IN ($marks) LIMIT 2000""".trimIndent()
        return requireNotNull(db).rawQuery(sql,cells.toTypedArray()).use{c->
            val result=ArrayList<RoadSpeedSegment875>()
            while(c.moveToNext()) {
                fun limit(value:Int,kind:Int,source:Int):RoadLimit875?=if(c.isNull(value))null else
                    RoadLimit875(c.getInt(value),RoadLimitSource875.valueOf(c.getString(kind)),c.getString(source),c.getString(19))
                var forward=limit(13,15,17);var backward=limit(14,16,18)
                if(!c.isNull(22)) {
                    val official=RoadLimit875(c.getInt(22),RoadLimitSource875.OFFICIAL,c.getString(23),c.getString(24))
                    if(c.getInt(11)!=0 && c.getInt(12)==0)forward=official
                    if(c.getInt(12)!=0 && c.getInt(11)==0)backward=official
                }
                result+=RoadSpeedSegment875(c.getString(0),c.getLong(1),c.getString(2),c.getString(3),
                    GpsPoint(c.getDouble(4),c.getDouble(5)),GpsPoint(c.getDouble(6),c.getDouble(7)),
                    c.getInt(8),c.getInt(9)!=0,c.getInt(10)!=0,c.getInt(11)!=0,c.getInt(12)!=0,
                    forward,backward,c.getString(20),c.getString(21))
            }
            // Do not select a numeric road out of a truncated ambiguous dense-area query.
            if(result.size>=2000)emptyList() else result
        }
    }
    private fun contains(point:GpsPoint)=jurisdiction.any{rings->rings.isNotEmpty()&&inRing(point,rings.first())&&rings.drop(1).none{inRing(point,it)}}
    @Synchronized override fun close(){db?.close();db=null;stamp=0L;metadata=null;jurisdiction=emptyList()}
    companion object {
        private val installLock=Any()
        private var generation=0L
        private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
        private fun parseJurisdiction(text:String):List<List<List<GpsPoint>>> {
            val shape=JSONObject(text);require(shape.getString("type")=="MultiPolygon")
            val polygons=shape.getJSONArray("coordinates");require(polygons.length() in 1..500)
            fun ring(a:JSONArray):List<GpsPoint> {
                require(a.length() in 4..100_000)
                return (0 until a.length()).map{val p=a.getJSONArray(it);GpsPoint(p.getDouble(1),p.getDouble(0)).also{v->require(v.valid())}}
            }
            return (0 until polygons.length()).map{p->val rings=polygons.getJSONArray(p)
                (0 until rings.length()).map{ring(rings.getJSONArray(it))}}
        }
        private fun inRing(p:GpsPoint,ring:List<GpsPoint>):Boolean {
            var inside=false;var j=ring.lastIndex
            for(i in ring.indices){val a=ring[i];val b=ring[j]
                if((a.lat>p.lat)!=(b.lat>p.lat) && p.lon<(b.lon-a.lon)*(p.lat-a.lat)/(b.lat-a.lat)+a.lon)inside=!inside
                j=i
            }
            return inside
        }
    }
}
