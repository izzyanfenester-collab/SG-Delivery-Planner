package com.izzyan.sgdeliveryplanner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.abs

/** Original Excel-derived data. All 91 numbers are reference-only, not certified legal limits. */
internal data class SpeedLimitRow868(
    val excelId:String,val cameraId:String,val category:String,
    val sourceNumber:Int,val cameraPoint:String,val locationDirection:String,
    val latitude:Double,val longitude:Double,val referenceKmh:Int?,
    val verificationStatus:String,val gpsPrecision:String,val notes:String,
    val sources:String,val checkedDate:String,
    val verifiedSegmentId:String?=null,val verifiedKmh:Int?=null,
    val verifiedSource:String?=null
) {
    val reducedPrecision get()=gpsPrecision.contains("3 dp",ignoreCase=true)
    val isOfficialSegmentVerified get()=verifiedSegmentId!=null && verifiedKmh!=null &&
        !verifiedSource.isNullOrBlank() && verifiedSource.contains("gov.sg",true)
}

/** Map spreadsheet source numbering to the existing 341 SPF camera IDs. */
internal object SpeedLimitSourceId868 {
    fun cameraId(id:String):String {
        val num=id.substringAfter("-")
        fun pad(n:String)=n.toInt().toString().padStart(3,'0')
        return when {
            id.startsWith("F-")->"FIXED_SPEED_CAMERA-"+pad(num)
            id.startsWith("M-")->"MOBILE_ENFORCEMENT_LOCATION-"+pad(num)
            id.startsWith("L-")->"POLICE_LASER_LOCATION-"+pad(num)
            id.startsWith("KM-")->"LTA_SPEED_CAMERA-"+pad(num.take(2))+
                (if(num=="10A") "" else num.drop(2).takeIf{it.isNotBlank()}?.let{"-"+it}.orEmpty())
            id.startsWith("A-")->{
                require(num.length==3 && num[0]=='0' && num[1] in '1'..'2')
                "AVERAGE_SPEED_ZONE-"+pad(num[2].toString())+"-"+
                    if(num[1]=='1') "D1" else "D2"
            }
            else->error("Unknown reference ID: "+id)
        }
    }
}

/** RFC4180 CSV parser preserves notes and sources without dropping quoted commas. */
internal object SpeedLimitCsv868 {
    fun parse(text:String):List<List<String>> {
        val out=mutableListOf<List<String>>()
        var row=mutableListOf<String>()
        val field=StringBuilder()
        var quoted=false
        var i=0
        while(i<text.length){
            when(val c=text[i]){
                '"' -> if(quoted && i+1<text.length && text[i+1]=='"'){
                    field.append('"');i++
                }else quoted=!quoted
                ',' -> if(quoted)field.append(c) else { row.add(field.toString());field.setLength(0) }
                '\n' -> if(quoted)field.append(c) else {
                    row.add(field.toString().trimEnd('\r'));field.setLength(0)
                    if(row.any{it.isNotBlank()})out.add(row)
                    row=mutableListOf()
                }
                else->field.append(c)
            }
            i++
        }
        require(!quoted){"Unclosed CSV quote"}
        if(field.isNotEmpty()||row.isNotEmpty()){
            row.add(field.toString().trimEnd('\r'))
            if(row.any{it.isNotBlank()})out.add(row)
        }
        return out
    }
}

/** Local indexed SQLite. The CSV is read on creation, never per GPS fix. */
internal class SpeedLimitReferenceStore868(context:Context):SQLiteOpenHelper(
    context.applicationContext,"sg_reference_speed_limits_868.db",null,1
) {
    private val app=context.applicationContext
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("""
        CREATE TABLE speed_reference(
            excel_id TEXT PRIMARY KEY NOT NULL,camera_id TEXT NOT NULL UNIQUE,
            category TEXT NOT NULL,source_no INTEGER NOT NULL,
            camera_point TEXT NOT NULL,road_direction TEXT NOT NULL,
            latitude REAL NOT NULL,longitude REAL NOT NULL,
            reference_kmh INTEGER,verification_status TEXT NOT NULL,
            gps_precision TEXT NOT NULL,notes TEXT NOT NULL,
            sources TEXT NOT NULL,checked_date TEXT NOT NULL,
            verified_segment_id TEXT,verified_kmh INTEGER,verified_source TEXT
        )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_speed_latlon ON speed_reference(latitude,longitude)")
        db.execSQL("CREATE INDEX idx_speed_camera ON speed_reference(camera_id)")
        db.execSQL("CREATE INDEX idx_speed_category ON speed_reference(category)")
        val csv=app.assets.open("sg_speed_limits_reference_868.csv").bufferedReader().use{
            it.readText().removePrefix("\uFEFF")
        }
        val rows=SpeedLimitCsv868.parse(csv)
        require(rows.size==101 && rows[0][0]=="Camera ID")
        var reference=0;var unknown=0;var lowerPrecision=0
        val ids=mutableSetOf<String>()
        for(row in rows.drop(1)){
            require(row.size==13){"Wrong spreadsheet column count"}
            require(ids.add(row[0])){"Duplicate spreadsheet ID"}
            val lat=row[5].toDouble();val lon=row[6].toDouble()
            require(lat in 1.1..1.55 && lon in 103.55..104.20)
            val limit=row[7].takeIf{it.isNotBlank()}?.toInt()
            require(limit==null || limit in 20..110)
            if(limit==null)unknown++ else reference++
            if(row[9].contains("3 dp"))lowerPrecision++
            val values=ContentValues().apply{
                put("excel_id",row[0]);put("camera_id",SpeedLimitSourceId868.cameraId(row[0]))
                put("category",row[1]);put("source_no",row[2].toInt())
                put("camera_point",row[3]);put("road_direction",row[4])
                put("latitude",lat);put("longitude",lon)
                if(limit!=null)put("reference_kmh",limit) else putNull("reference_kmh")
                put("verification_status",row[8]);put("gps_precision",row[9])
                put("notes",row[10]);put("sources",row[11]);put("checked_date",row[12])
                // Never promote provisional road references to verified values.
                putNull("verified_segment_id");putNull("verified_kmh");putNull("verified_source")
            }
            db.insertOrThrow("speed_reference",null,values)
        }
        require(reference==91 && unknown==9 && lowerPrecision==12){
            "Speed workbook changed: "+reference+"/"+unknown+"/"+lowerPrecision
        }
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){
        error("No destructive speed-limit database upgrade")
    }
    fun all():List<SpeedLimitRow868>{
        val out=mutableListOf<SpeedLimitRow868>()
        readableDatabase.query("speed_reference",null,null,null,null,null,"excel_id").use{c->
            fun index(name:String)=c.getColumnIndexOrThrow(name)
            fun get(name:String)=c.getString(index(name))
            fun opt(name:String)=index(name).let{if(c.isNull(it))null else c.getString(it)}
            while(c.moveToNext()){
                out.add(SpeedLimitRow868(
                    excelId=get("excel_id"),cameraId=get("camera_id"),category=get("category"),
                    sourceNumber=c.getInt(index("source_no")),cameraPoint=get("camera_point"),
                    locationDirection=get("road_direction"),
                    latitude=c.getDouble(index("latitude")),longitude=c.getDouble(index("longitude")),
                    referenceKmh=index("reference_kmh").let{if(c.isNull(it))null else c.getInt(it)},
                    verificationStatus=get("verification_status"),gpsPrecision=get("gps_precision"),
                    notes=get("notes"),sources=get("sources"),checkedDate=get("checked_date"),
                    verifiedSegmentId=opt("verified_segment_id"),
                    verifiedKmh=index("verified_kmh").let{if(c.isNull(it))null else c.getInt(it)},
                    verifiedSource=opt("verified_source")
                ))
            }
        }
        require(out.size==100){"Incomplete speed-limit SQLite import"}
        return out
    }
    fun verifyCoordinates(rows:List<SpeedLimitRow868>,spf:List<EnforcementCamera>){
        require(rows.size==100)
        val original=spf.associateBy{it.id}
        for(row in rows){
            val camera=original[row.cameraId]?:error("Missing SPF camera: "+row.cameraId)
            require(abs(row.latitude-camera.point.lat)<1e-8 &&
                abs(row.longitude-camera.point.lon)<1e-8){"SPF coordinate conflict: "+row.excelId}
        }
    }
}
