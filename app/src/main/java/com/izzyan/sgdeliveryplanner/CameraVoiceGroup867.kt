package com.izzyan.sgdeliveryplanner

import java.util.Locale
import kotlin.math.*

/** Keep every source record and map marker; group only voice warnings from
 * closely adjacent cameras on same road/category (e.g. paired directions or MCE A/B).
 */
internal object CameraVoiceGroup867 {
    private fun key(s:String)=s.lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*\\)"),"")
        .replace(Regex("\\b(rd|road)\\b"),"road")
        .replace(Regex("\\s+\\d+(?:\\.\\d+)?\\s*km\\b"),"")
        .replace(Regex("\\s+")," ").trim()
    private fun distance(a:GpsPoint,b:GpsPoint):Double =
        hypot((a.lat-b.lat)*111_195.0,
            (a.lon-b.lon)*111_195.0*cos(Math.toRadians(a.lat)))
    fun combine(approaches:List<CameraApproach>):List<CameraApproach> {
        val chosen=mutableListOf<CameraApproach>()
        for(approach in approaches.sortedWith(
            compareByDescending<CameraApproach>{it.alertAllowed}
                .thenByDescending{it.directionConfirmed}
                .thenBy{it.camera.id})) {
            val matching=chosen.indexOfFirst {
                !it.camera.isRedLight && !it.camera.isAverage &&
                it.camera.category==approach.camera.category &&
                key(it.camera.road)==key(approach.camera.road) &&
                abs(it.routeMetresAhead-approach.routeMetresAhead)<90.0 &&
                distance(it.camera.point,approach.camera.point)<=65.0
            }
            if(matching==-1)chosen+=approach
        }
        return chosen.sortedBy{it.routeMetresAhead}
    }
}
