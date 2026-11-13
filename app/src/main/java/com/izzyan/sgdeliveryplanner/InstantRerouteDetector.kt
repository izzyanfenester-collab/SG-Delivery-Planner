package com.izzyan.sgdeliveryplanner

import kotlin.math.*

internal data class RerouteFix(val point: GpsPoint, val accuracy: Float, val speed: Float?,
    val bearing: Float?, val timeMs: Long)
internal data class RerouteDecision(val reason: String, val confirmingFixMs: Long)
private data class Junction(val point: GpsPoint, val incoming: Float, val outgoing: Float)
private data class Projection(val distance: Double, val segment: Int, val bearing: Float, val along: Double)
private fun projection(p: GpsPoint, shape: List<GpsPoint>, start: Int = 0, end: Int = shape.lastIndex): Projection? {
    val scale = 111195.0 * cos(Math.toRadians(p.lat))
    var best: Projection? = null
    for (k in start.coerceAtLeast(0) until end.coerceAtMost(shape.lastIndex)) {
        val a=shape[k]; val b=shape[k+1]
        val dx=(b.lon-a.lon)*scale; val dy=(b.lat-a.lat)*111195.0
        val len2=dx*dx+dy*dy
        if(len2<1) continue
        val x=(p.lon-a.lon)*scale; val y=(p.lat-a.lat)*111195.0
        val t=((x*dx+y*dy)/len2).coerceIn(0.0,1.0)
        val distance=hypot(x-t*dx,y-t*dy)
        val bearing=NavigationRoadMatcher.normalizeBearing(Math.toDegrees(atan2(dx,dy)).toFloat())
        if(best==null || distance<best.distance) best=Projection(distance,k,bearing,t*sqrt(len2))
    }
    return best
}
private fun alongHeading(origin: GpsPoint, point: GpsPoint, heading: Float): Double {
    val theta=Math.toRadians(heading.toDouble())
    return (point.lat-origin.lat)*111195.0*cos(theta) +
        (point.lon-origin.lon)*111195.0*cos(Math.toRadians(origin.lat))*sin(theta)
}
private fun junctionAhead(shape: List<GpsPoint>, match: Projection): Junction? {
    var walked = -match.along
    for(i in match.segment+1 until shape.lastIndex) {
        walked += navigationDistance(shape[i-1],shape[i])
        if(walked>120) break
        // Use 10–25m chords rather than single tiny OSM segments.
        var before=i-1
        while(before>0 && navigationDistance(shape[before],shape[i])<12) before--
        var after=i+1
        while(after<shape.lastIndex && navigationDistance(shape[i],shape[after])<12) after++
        val incoming=NavigationRoadMatcher.bearingFromTwoPoints(shape[before],shape[i]) ?: continue
        val outgoing=NavigationRoadMatcher.bearingFromTwoPoints(shape[i],shape[after]) ?: continue
        if(NavigationRoadMatcher.angleDifference(incoming,outgoing)>=35f)
            return Junction(shape[i],incoming,outgoing)
    }
    return null
}

/** Raw fixes only. Prior trusted approach plus a confirming fix past the junction
 * can trigger immediately; ambiguous deviations need two consistent observations. */
internal class InstantRerouteDetector {
    private var previous: RerouteFix? = null
    private var junction: Junction? = null
    private var lastTrustedSegment: Int? = null
    private var offSince: Long? = null
    fun reset() { previous=null; junction=null; lastTrustedSegment=null; offSince=null }
    fun observe(fix: RerouteFix, shape: List<GpsPoint>): RerouteDecision? {
        val old=previous
        if(!fix.point.valid() || !fix.accuracy.isFinite() || fix.accuracy !in 0f..25f ||
            fix.speed?.let{it.isFinite() && it>=3f}!=true || shape.size<2) {
            reset(); return null
        }
        if(old!=null && (fix.timeMs<=old.timeMs || fix.timeMs-old.timeMs>3500)) reset()
        val prior=previous
        previous=fix
        val heading=fix.bearing?.takeIf{it.isFinite()} ?: prior?.let {
            NavigationRoadMatcher.bearingFromTwoPoints(it.point,fix.point)
        }
        val nearest=projection(fix.point,shape) ?: return null
        val local=lastTrustedSegment?.let { projection(fix.point,shape,(it-2).coerceAtLeast(0),(it+100).coerceAtMost(shape.lastIndex)) } ?: nearest
        val gap=nearest.distance
        val delta=heading?.let { NavigationRoadMatcher.angleDifference(it,local.bearing) }
        val turn=junction
        if(turn!=null && prior!=null && heading!=null && fix.accuracy<=15f) {
            val past=alongHeading(turn.point,fix.point,turn.incoming)
            val oldPast=alongHeading(turn.point,prior.point,turn.incoming)
            val traveled=navigationDistance(prior.point,fix.point)
            val headingWrong=NavigationRoadMatcher.angleDifference(heading,turn.outgoing)>=40f
            val headingContinues=NavigationRoadMatcher.angleDifference(heading,turn.incoming)<=50f
            // Crossing the incoming tangent's junction plane, off the actual routed geometry,
            // with coherent movement. A correct turn or parallel GPS jitter cannot satisfy this.
            if(past>=max(7.0,fix.accuracy*1.1) && past>oldPast+2 && traveled>=3 &&
                gap>=max(8.0,fix.accuracy*1.3) && headingWrong && headingContinues) {
                return RerouteDecision("missed-junction",fix.timeMs)
            }
        }
        val trusted=local.distance<=max(7.0,fix.accuracy*1.15) && (delta==null || delta<=40f)
        if(trusted) {
            lastTrustedSegment=local.segment
            junction=junctionAhead(shape,local)
            offSince=null; return null
        }
        val strong=gap>=max(35.0,fix.accuracy*2.5)
        val diverging=gap>=max(16.0,fix.accuracy*1.8) && delta!=null && delta>=55f
        if(!strong && !diverging) { offSince=null; return null }
        if(offSince==null) offSince=fix.timeMs
        return if(fix.timeMs-requireNotNull(offSince)>=400 && prior!=null &&
            navigationDistance(prior.point,fix.point)>=2)
            RerouteDecision("confirmed-deviation",fix.timeMs) else null
    }
}

/** Latest request wins, but ordinary forward motion is not a new wrong turn.
 * A meaningful direction change can supersede a pending request immediately. */
internal class RerouteRequestGate {
    private var generation=0L
    var origin: RerouteFix?=null; private set
    fun begin(fix:RerouteFix):Long { origin=fix; return ++generation }
    fun isCurrent(id:Long)=id==generation
    fun clear(){ origin=null; generation++ }
    fun shouldSupersede(fix:RerouteFix):Boolean {
        val old=origin ?: return false
        val a=old.bearing ?: return false
        val b=fix.bearing ?: return false
        return fix.accuracy<=15 && (fix.speed?:0f)>=4 &&
            navigationDistance(old.point,fix.point)>=12 &&
            NavigationRoadMatcher.angleDifference(a,b)>=55
    }
}
internal fun routeFitsLatestFix(shape:List<GpsPoint>,fix:RerouteFix):Boolean {
    // Never apply a response whose route has already been missed. Only inspect
    // the first 500m, not a distant return segment of a looping route.
    var distance=0.0; var end=1
    while(end<shape.lastIndex && distance<500) {
        distance+=navigationDistance(shape[end-1],shape[end]); end++
    }
    val match=projection(fix.point,shape,0,end) ?: return false
    if(match.distance>max(20.0,fix.accuracy*2.0)) return false
    val heading=fix.bearing
    return heading==null || (fix.speed?:0f)<3 || NavigationRoadMatcher.angleDifference(heading,match.bearing)<=100
}
