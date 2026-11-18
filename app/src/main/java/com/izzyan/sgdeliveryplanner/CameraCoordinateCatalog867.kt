package com.izzyan.sgdeliveryplanner

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs

/** Build 867: 100 published SPF speed-enforcement coordinates from user's screenshot.
 * Existing SPF SQLite records remain canonical. Do not duplicate or round coordinates.
 * Fail closed if a coordinate, category, road, or supplied direction has changed.
 */
internal object CameraCoordinateCatalog867 {
    const val ASSET = "sg_speed_camera_reference_867.json"
    const val PRIMARY_METRES = 2_000.0
    const val REMINDER_METRES = 500.0
    private val categories = setOf(
        "FIXED_SPEED_CAMERA", "LTA_SPEED_CAMERA",
        "MOBILE_ENFORCEMENT_LOCATION", "POLICE_LASER_LOCATION",
        "AVERAGE_SPEED_ZONE"
    )
    private val expectedCounts = mapOf(
        "FIXED_SPEED_CAMERA" to 20,
        "LTA_SPEED_CAMERA" to 13,
        "MOBILE_ENFORCEMENT_LOCATION" to 5,
        "AVERAGE_SPEED_ZONE" to 12,
        "POLICE_LASER_LOCATION" to 50
    )
    data class Verified(
        val matchingIds:Set<String>, val eligibleSpeedIds:Set<String>,
        val averageZonePointIds:Set<String>
    ) {
        val size get()=matchingIds.size
        val ready get()=size==100
    }
    fun load(context:Context,cameras:List<EnforcementCamera>):Verified =
        context.assets.open(ASSET).bufferedReader().use {
            verify(it.readText(),cameras)
        }
    fun verify(json:String,cameras:List<EnforcementCamera>):Verified {
        val root=JSONObject(json)
        require(root.getInt("schema_version")==1 && root.getInt("record_count")==100)
        val rows=root.getJSONArray("cameras")
        require(rows.length()==100)
        val counts=mutableMapOf<String,Int>()
        val existing=cameras.associateBy{it.id}
        val matching=mutableSetOf<String>()
        val speeds=mutableSetOf<String>()
        val averages=mutableSetOf<String>()
        for(i in 0 until rows.length()){
            val row=rows.getJSONObject(i)
            val id=row.getString("camera_id")
            val type=row.getString("category")
            require(type in categories && matching.add(id))
            counts[type]=(counts[type]?:0)+1
            val other=existing[id]?:error("Missing coordinate record: $id")
            require(other.category==type)
            require(abs(row.getDouble("latitude")-other.point.lat)<1e-9 &&
                    abs(row.getDouble("longitude")-other.point.lon)<1e-9) {
                "Coordinate changed unexpectedly for $id"
            }
            require(row.getString("official_name")==other.name)
            require(row.getString("road_name")==other.road)
            require(row.optString("direction_text","")==other.directionText.orEmpty())
            if(type=="AVERAGE_SPEED_ZONE") averages.add(id) else speeds.add(id)
        }
        require(counts==expectedCounts)
        require(speeds.size==88 && averages.size==12)
        return Verified(matching,speeds,averages)
    }
}
