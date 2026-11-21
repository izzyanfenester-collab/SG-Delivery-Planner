package com.izzyan.sgdeliveryplanner

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * B879 visual safety is intentionally independent of the B878 audio gate.
 * An OFFICIAL or direction-matched MAP DATA road limit may drive an advisory
 * RED speed display; only confirmed OFFICIAL limits may drive the voice.
 * Never derive a limit from the current vehicle speed or camera proximity.
 */
internal data class OverspeedVisualState879(
    val red:Boolean=false,
    val speedReliable:Boolean=false,
    val shownSpeedKmh:Int?=null,
    val applicableLimitKmh:Int?=null,
    val limitSource:RoadLimitSource875?=null,
    val roadSegmentId:String?=null,
    val diagnostic:String="Waiting for a matched road and fresh GPS speed"
)

internal class OverspeedVisualMonitor879 {
    private var previousSpeedKmh:Int?=null
    private var previousFixTimeMs:Long=0
    private var previousRoadId:String?=null
    private var consecutiveWithoutAccuracy=0
    var state:OverspeedVisualState879=OverspeedVisualState879()
        private set

    fun reset(reason:String="Current limit or GPS unavailable") {
        previousSpeedKmh=null;previousFixTimeMs=0;previousRoadId=null
        consecutiveWithoutAccuracy=0
        state=OverspeedVisualState879(diagnostic=reason)
    }

    fun observe(road:RoadSpeedState875,fix:RoadSpeedFix875,enabled:Boolean):OverspeedVisualState879 {
        val limit=road.limit
        val kmh=fix.speed?.takeIf{it.isFinite()&&it>=0f}?.let{(it*3.6f).roundToInt()}
        val sourceEligible=limit?.kind in setOf(RoadLimitSource875.OFFICIAL,RoadLimitSource875.MAP_DATA)
        val validRoad=sourceEligible && !road.segmentId.isNullOrBlank() && road.observedAtMs>0L &&
            fix.timeMs>=road.observedAtMs && fix.timeMs-road.observedAtMs<=3000L
        val validFix=kmh!=null && fix.timeMs>0L &&
            fix.accuracy.isFinite() && fix.accuracy in 0.1f..25f
        if(!enabled || !validRoad || !validFix) {
            val why=when {
                !enabled->"Overspeed Warning disabled in Settings"
                limit==null->"CHECK SIGNS: no applicable road speed limit"
                !sourceEligible->"Road limit is a general rule, not verified for this segment"
                road.segmentId.isNullOrBlank()->"No confident road/direction match"
                road.observedAtMs<=0L || fix.timeMs<road.observedAtMs ||
                    fix.timeMs-road.observedAtMs>3000L->"Road limit lookup stale"
                else->"GPS speed or horizontal accuracy unavailable"
            }
            reset(why);return state
        }
        val id=requireNotNull(road.segmentId)
        val current=requireNotNull(kmh)
        val sameRoad=previousRoadId==id
        val elapsed=fix.timeMs-previousFixTimeMs
        // Two callbacks can evaluate one fix after the asynchronous road lookup.
        // They must not count as two independent GPS confirmations.
        val freshFix=previousFixTimeMs==0L || fix.timeMs>previousFixTimeMs
        val neighbor=sameRoad && elapsed in 250L..3500L &&
            previousSpeedKmh!=null && abs(current-requireNotNull(previousSpeedKmh))<=15
        if(!sameRoad || (freshFix && elapsed>3500L))
            consecutiveWithoutAccuracy=0
        if(freshFix) {
            consecutiveWithoutAccuracy=if(neighbor)consecutiveWithoutAccuracy+1 else 1
        }
        val accuracy=fix.speedAccuracy
        val explicitAccuracy=accuracy!=null && accuracy.isFinite() && accuracy in 0f..3f
        val missingAccuracy=accuracy==null
        // A large sudden jump requires another consistent sample, even if
        // Android reports deceptively optimistic accuracy for that single fix.
        val implausibleJump=sameRoad && elapsed in 250L..3500L &&
            previousSpeedKmh!=null && abs(current-requireNotNull(previousSpeedKmh))>35
        val reliable=(!implausibleJump) &&
            (explicitAccuracy || (missingAccuracy && consecutiveWithoutAccuracy>=2))
        if(freshFix) {
            previousRoadId=id;previousSpeedKmh=current;previousFixTimeMs=fix.timeMs
        }
        val red=reliable && current>requireNotNull(limit).kmh
        val reason=when {
            implausibleJump->"One-sample GPS speed jump: awaiting confirmation"
            accuracy!=null && !explicitAccuracy->"GPS reported low speed accuracy"
            missingAccuracy && consecutiveWithoutAccuracy<2->"Speed accuracy absent: awaiting second consistent GPS fix"
            !reliable->"GPS speed confidence insufficient"
            red->"VISUAL OVERSPEED: ${current} km/h > ${limit.kmh} km/h [${limit.kind.label}]"
            else->"Current speed is at or below matched road limit"
        }
        state=OverspeedVisualState879(red,reliable,current,limit.kmh,limit.kind,id,reason)
        return state
    }
}
