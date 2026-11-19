package com.izzyan.sgdeliveryplanner

import kotlin.math.*

internal data class CameraMarkerSite872(val camera:EnforcementCamera,val recordCount:Int)

/** Co-located records share one symbol; all database rows and alert approaches
 * remain intact. The marker uses an original published coordinate, never an offset. */
internal object CameraMarkerSites872 {
    fun group(records:List<EnforcementCamera>):List<CameraMarkerSite872> {
        val sites=mutableListOf<CameraMarkerSite872>()
        for(camera in records.sortedBy{it.id}) {
            val index=sites.indexOfFirst {
                it.camera.category==camera.category && it.camera.road.equals(camera.road,true) &&
                    hypot((it.camera.point.lat-camera.point.lat)*111_195.0,
                        (it.camera.point.lon-camera.point.lon)*111_195.0*
                            cos(Math.toRadians(camera.point.lat)))<=8.0
            }
            if(index<0)sites+=CameraMarkerSite872(camera,1)
            else sites[index]=sites[index].copy(recordCount=sites[index].recordCount+1)
        }
        return sites
    }
}
