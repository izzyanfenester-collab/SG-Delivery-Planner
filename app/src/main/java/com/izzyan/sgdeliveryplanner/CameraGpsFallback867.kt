package com.izzyan.sgdeliveryplanner

import java.util.Locale
import kotlin.math.*

/** Emergency GPS-coordinate estimate ONLY when route polyline is temporarily unavailable.
 * Not a route distance and never a direction-specific camera enforcement claim.
 * Road name, valid GPS heading/accuracy and successive decreasing readings are required.
 */
internal class CameraGpsFallback867 {
    private data class Seen(val distance:Double,val recordedMs:Long)
    private val last=mutableMapOf<String,Seen>()
    fun reset()=last.clear()

    private fun roadKey(value:String):String {
        val base=value.lowercase(Locale.ROOT)
            .replace(Regex("\\([^)]*\\)"),"")
            .replace(Regex("\\s+\\d+(?:\\.\\d+)?\\s*km\\b"),"")
            .substringBefore(" at ")
            .replace("-", " ")
            .replace(Regex("\\b(rd|road)\\b"),"road")
            .replace(Regex("\\b(ave|avenue)\\b"),"avenue")
            .replace(Regex("\\s+")," ").trim()
        return mapOf(
            "pie" to "pan island expressway",
            "aye" to "ayer rajah expressway",
            "bke" to "bukit timah expressway",
            "cte" to "central expressway",
            "ecp" to "east coast parkway",
            "kpe" to "kallang paya lebar expressway",
            "mce" to "marina coastal expressway",
            "sle" to "seletar expressway",
            "tpe" to "tampines expressway",
            "kje" to "kranji expressway"
        )[base]?:base
    }
    private fun matchingRoad(vehicleRoad:String?,published:String):Boolean {
        if(vehicleRoad.isNullOrBlank()) return false
        return roadKey(vehicleRoad)==roadKey(published)
    }
    fun scan(
        location:GpsPoint,nowMs:Long,accuracyMetres:Float,
        speedMetresPerSecond:Float?,headingDegrees:Double?,
        headingAccuracyDegrees:Float?,currentMatchedRoad:String?,
        publishedSpeedCameras:List<EnforcementCamera>
    ):List<CameraApproach> {
        if(!location.valid() || accuracyMetres>8f || !accuracyMetres.isFinite() ||
            (speedMetresPerSecond?:0f)<3.5f ||
            headingDegrees==null || !headingDegrees.isFinite() ||
            headingAccuracyDegrees==null || headingAccuracyDegrees>12f ||
            headingAccuracyDegrees<0f) return emptyList()
        val result=ArrayList<CameraApproach>()
        for(camera in publishedSpeedCameras) {
            if(camera.isAverage || camera.isRedLight ||
                !matchingRoad(currentMatchedRoad,camera.road)) continue
            val dNorth=(camera.point.lat-location.lat)*111_195.0
            val dEast=(camera.point.lon-location.lon)*111_195.0*
                cos(Math.toRadians(location.lat))
            val geoMetres=hypot(dEast,dNorth)
            if(geoMetres>2_600.0 || geoMetres<70.0) continue
            val headingToCamera=(Math.toDegrees(atan2(dEast,dNorth))+360.0)%360.0
            if(CameraRouteGeometry.angularGap(headingToCamera,headingDegrees)>12.0) continue
            val previous=last.put(camera.id,Seen(geoMetres,nowMs))
            // Not enough evidence from a single GPS fix or a stationary position.
            if(previous==null || nowMs-previous.recordedMs !in 500L..15_000L ||
                previous.distance-geoMetres<3.0 ||
                geoMetres>CameraCoordinateCatalog867.PRIMARY_METRES)continue
            // Generic, recorded safety message only: never say that an unverified
            // camera enforces this carriageway or that radius == road distance.
            result+=CameraApproach(camera,geoMetres,
                directionConfirmed=false,alertAllowed=false,
                genericSafetyEligible=true,coordinateFallbackUsed=true)
        }
        return result.sortedBy{it.routeMetresAhead}
    }
}
