package com.izzyan.sgdeliveryplanner

import kotlin.math.*

/** Named, measured sections of the active route. No geographic-radius voice trigger. */
internal data class CameraRoadCorridor872(val road:String,val startMetres:Double,val endMetres:Double) {
    companion object {
        fun from(steps:List<Pair<String,Double>>,shape:List<GpsPoint>):List<CameraRoadCorridor872> {
            val measured=shape.zipWithNext().sumOf { (a,b) ->
                hypot((a.lat-b.lat)*111_195.0,
                    (a.lon-b.lon)*111_195.0*cos(Math.toRadians((a.lat+b.lat)/2)))
            }
            val source=steps.sumOf{it.second.coerceAtLeast(0.0)}
            if(source<=0 || measured<=0)return emptyList()
            var start=0.0
            return steps.map { (road,length) ->
                val end=start+length.coerceAtLeast(0.0)*measured/source
                CameraRoadCorridor872(road,start,end).also{start=end}
            }
        }
    }
}

/** Recover a brief route/name dropout from the last real route, same road and
 * successive accurate headings. Never substitute straight-line distance for route metres.
 * Reset whenever navigation recalculates: old geometry must not survive a reroute. */
internal class CameraRouteRecovery872 {
    private var shape:List<GpsPoint> = emptyList()
    private var corridors:List<CameraRoadCorridor872> = emptyList()
    private var road:String?=null
    private var savedMs=Long.MIN_VALUE
    private var heading:Double?=null
    private var headingMs=Long.MIN_VALUE
    fun reset(){shape=emptyList();corridors=emptyList();road=null;savedMs=Long.MIN_VALUE;heading=null}

    fun scan(current:GpsPoint,now:Long,activeShape:List<GpsPoint>,currentRoad:String?,
        upcoming:List<String>,accuracy:Float,speed:Float?,bearing:Double?,bearingAccuracy:Float?,
        records:List<EnforcementCamera>,activeCorridors:List<CameraRoadCorridor872>):List<CameraApproach> {
        val previousHeading=heading
        val previousMs=headingMs
        heading=bearing;headingMs=now
        if(activeShape.size>=2 && !currentRoad.isNullOrBlank()) {
            shape=activeShape;corridors=activeCorridors;road=currentRoad;savedMs=now
            return CameraApproachLogic.find(current,activeShape,currentRoad,upcoming,accuracy,
                records,speed,bearing,bearingAccuracy,activeCorridors)
        }
        if(shape.size<2 || now-savedMs !in 0L..12_000L || accuracy !in 0f..12f ||
            (speed?:0f)<3f || bearing==null || previousHeading==null ||
            now-previousMs !in 1L..5_000L || bearingAccuracy==null ||
            bearingAccuracy !in 0f..20f ||
            CameraRouteGeometry.angularGap(previousHeading,bearing)>20.0) return emptyList()
        val vehicle=CameraRouteGeometry.project(current,shape)?:return emptyList()
        if(vehicle.lateralMetres>8.0 ||
            CameraRouteGeometry.angularGap(bearing,vehicle.segmentBearing)>15.0)return emptyList()
        return CameraApproachLogic.find(current,shape,currentRoad?:road,upcoming,accuracy,
            records,speed,bearing,bearingAccuracy,corridors).map {
                it.copy(coordinateFallbackUsed=true,matchReason="recovered-real-route-corridor")
            }
    }
}
