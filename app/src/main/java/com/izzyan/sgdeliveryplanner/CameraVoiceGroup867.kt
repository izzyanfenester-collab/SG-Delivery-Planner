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
    fun combine(approaches:List<CameraApproach>,
        records:List<EnforcementCamera> = approaches.map{it.camera}):List<CameraApproach> {
        fun family(camera:EnforcementCamera)=when {
            camera.isRedLight -> "red-light"
            camera.category in setOf("FIXED_SPEED_CAMERA","LTA_SPEED_CAMERA") -> "fixed-speed"
            else -> camera.category
        }
        fun identity(camera:EnforcementCamera):String {
            if(camera.isAverage && camera.verifiedZoneBoundary)
                return "zone-${camera.zoneId}-${if(camera.averageZoneEntryVerified)"entry" else "exit"}"
            // Use the whole snapshot, so the group ID cannot change as one paired
            // point drops behind the vehicle or leaves the candidate window.
            return records.filter { family(it)==family(camera) &&
                key(it.road)==key(camera.road) && distance(it.point,camera.point)<=65.0
            }.minOfOrNull{it.id}?:camera.id
        }
        val chosen=linkedMapOf<String,CameraApproach>()
        for(approach in approaches.sortedWith(
            compareByDescending<CameraApproach>{it.camera.verifiedDualEnforcement}
                .thenByDescending{it.alertAllowed}
                .thenByDescending{it.directionConfirmed}.thenBy{it.camera.id})) {
            val group=identity(approach.camera)
            chosen.putIfAbsent(group,approach.copy(voiceGroupId=group,voiceGroupCategory=family(approach.camera)))
        }
        return chosen.values.sortedBy{it.routeMetresAhead}
    }
}
