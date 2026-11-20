package com.izzyan.sgdeliveryplanner

import kotlin.math.*

internal enum class RoadLimitSource875(val label:String) {
    OFFICIAL("VERIFIED"), MAP_DATA("MAP DATA"), GENERAL_RULE("GENERAL RULE")
}
internal data class RoadLimit875(val kmh:Int,val kind:RoadLimitSource875,val source:String,val checkedDate:String) {
    init { require(kmh in 5..90 && source.startsWith("https://") && checkedDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) }
}
internal data class RoadSpeedSegment875(
    val id:String,val wayId:Long,val name:String,val highway:String,
    val a:GpsPoint,val b:GpsPoint,val layer:Int=0,val tunnel:Boolean=false,val bridge:Boolean=false,
    val forwardAllowed:Boolean=true,val backwardAllowed:Boolean=true,
    val forward:RoadLimit875?=null,val backward:RoadLimit875?=null,
    val restrictions:String="",val unknownReason:String="No validated maxspeed record"
)
internal data class RoadSpeedFix875(
    val point:GpsPoint,val timeMs:Long,val accuracy:Float,val heading:Float?=null,
    val speed:Float?=null,val speedAccuracy:Float?=null,
    val roadHint:String?=null,val routeBearing:Float?=null
)
internal data class RoadSpeedState875(
    val limit:RoadLimit875?=null,val segmentId:String?=null,val roadName:String?=null,
    val diagnostics:String="Waiting for current road match",val overspeedVisual:Boolean=false,
    val observedAtMs:Long=0
) {
    val numberKmh get()=limit?.kmh
    val hasNumericLimit get()=limit!=null
    val highlightOverspeed get()=overspeedVisual
    // Keep the existing narrow meter panel; full source details live in diagnostics.
    val note:String? get()=limit?.checkedDate
    val badgeLabel get()=limit?.kind?.label?:"CHECK SIGNS"
    val sourceNote get()=limit?.let{when(it.kind){
        RoadLimitSource875.OFFICIAL->"LTA / statute · ${it.checkedDate}"
        RoadLimitSource875.MAP_DATA->"OSM · ${it.checkedDate}"
        RoadLimitSource875.GENERAL_RULE->"SG general rule · ${it.checkedDate}"
    }}?:"Road limit not confirmed"
}

internal object RoadSpeedGeometry875 {
    data class Projection(val distance:Double,val fraction:Double,val bearing:Float)
    fun project(p:GpsPoint,a:GpsPoint,b:GpsPoint):Projection? {
        val scale=111195.0*cos(Math.toRadians(p.lat))
        val x=(b.lon-a.lon)*scale;val y=(b.lat-a.lat)*111195.0
        val n=x*x+y*y;if(n<0.25)return null
        val px=(p.lon-a.lon)*scale;val py=(p.lat-a.lat)*111195.0
        val t=(px*x+py*y)/n
        // Reject a passed segment rather than extending its legal limit along the next road.
        if(t<0 && -t*sqrt(n)>2.5 || t>1 && (t-1)*sqrt(n)>2.5)return null
        val clamped=t.coerceIn(0.0,1.0)
        return Projection(hypot(px-clamped*x,py-clamped*y),t,
            ((Math.toDegrees(atan2(x,y))+360.0)%360.0).toFloat())
    }
    fun delta(a:Float,b:Float)=abs(((a-b+540f)%360f)-180f)
    fun name(value:String?):String {
        val text=value.orEmpty().lowercase().replace(Regex("[^a-z0-9]"),"")
        return when(text){
            "pie"->"panislandexpressway";"bke"->"bukittimahexpressway"
            "kje"->"kranjiexpressway";"sle"->"seletarexpressway"
            "tpe"->"tampinesexpressway";"cte"->"centralexpressway"
            "ecp"->"eastcoastparkway";"aye"->"ayerrajahexpressway"
            "kpe"->"kallangpayalebarexpressway";"mce"->"marinacoastalexpressway"
            else->text
        }
    }
}

/** Uses actual nearby road geometries, including roads with unknown limits.
 * Neither camera proximity, GPS speed, traffic speed nor routing-cost estimates supply a limit. */
internal class RoadSpeedLimitTracker875 {
    private data class Candidate(val road:RoadSpeedSegment875,val direction:Int,val value:RoadLimit875?,
        val distance:Double,val score:Double) {
        val key get()="${road.wayId}:$direction:${value?.kmh}"
    }
    private var previousFix:RoadSpeedFix875?=null
    private var pending:String?=null
    private var count=0
    private var stable:Candidate?=null
    private var overspeed=0
    private var within=0
    private var wasOver=false
    private var ambiguousHeld=0
    private var last=RoadSpeedState875()
    fun invalidate(reason:String):RoadSpeedState875 {
        pending=null;count=0;stable=null;overspeed=0;within=0;wasOver=false;ambiguousHeld=0
        last=RoadSpeedState875(diagnostics=reason)
        return last
    }
    fun reset(){previousFix=null;invalidate("Waiting for current road match")}
    fun expire(nowMs:Long):RoadSpeedState875 = if(last.observedAtMs>0 && nowMs-last.observedAtMs>6000)
        invalidate("GPS fix stale; check road signs") else last

    fun observe(fix:RoadSpeedFix875,roads:List<RoadSpeedSegment875>,overspeedEnabled:Boolean=false):RoadSpeedState875 {
        if(!fix.point.valid() || !fix.accuracy.isFinite() || fix.accuracy<=0 || fix.accuracy>25 || fix.timeMs<=0)
            return invalidate("GPS accuracy insufficient for current road limit")
        val prior=previousFix
        if(prior!=null && fix.timeMs<=prior.timeMs)return last
        if(prior!=null && fix.timeMs-prior.timeMs>6000)invalidate("Reacquiring road after GPS gap")
        var heading=fix.heading?.takeIf{it.isFinite() && (fix.speed?:0f)>=2f}
        if(heading==null && prior!=null && fix.timeMs-prior.timeMs in 1..12000 &&
            navigationDistance(prior.point,fix.point)>=max(8.0,(prior.accuracy+fix.accuracy)*1.5) &&
            (fix.speed==null || fix.speed>=1.5f) && prior.accuracy<=15 && fix.accuracy<=15) {
            val dx=(fix.point.lon-prior.point.lon)*cos(Math.toRadians(fix.point.lat))
            val dy=fix.point.lat-prior.point.lat
            heading=((Math.toDegrees(atan2(dx,dy))+360.0)%360.0).toFloat()
        }
        // Route intent cannot invent the vehicle's direction on another carriageway.
        previousFix=fix
        val radius=(fix.accuracy*1.3).coerceIn(12.0,28.0)
        val hint=RoadSpeedGeometry875.name(fix.roadHint).takeIf{
            heading!=null && fix.routeBearing!=null && RoadSpeedGeometry875.delta(heading,fix.routeBearing)<=45f
        }.orEmpty()
        val candidates=ArrayList<Candidate>()
        for(road in roads){
            val p=RoadSpeedGeometry875.project(fix.point,road.a,road.b)?:continue
            if(p.distance>radius)continue
            if(heading==null && road.forwardAllowed && road.backwardAllowed && road.forward!=road.backward) {
                // The road is known, its applicable direction-specific value is not.
                candidates+=Candidate(road,0,null,p.distance,p.distance)
                continue
            }
            for(dir in listOf(1,-1)){
                if(dir==1&&!road.forwardAllowed || dir==-1&&!road.backwardAllowed)continue
                if(heading==null && dir==-1 && road.forwardAllowed)continue
                val bearing=if(dir==1)p.bearing else (p.bearing+180f)%360f
                val angle=heading?.let{RoadSpeedGeometry875.delta(it,bearing)}?:0f
                if(angle>50f)continue
                val nameBonus=if(hint.isNotBlank() && RoadSpeedGeometry875.name(road.name)==hint)2.0 else 0.0
                candidates+=Candidate(road,dir,if(dir==1)road.forward else road.backward,
                    p.distance,p.distance+angle*0.06-nameBonus)
            }
        }
        if(candidates.isEmpty())return invalidate("No confident current road/direction match")
        val ranked=candidates.sortedBy{it.score}
        var best=ranked.first()
        val plausible=ranked.filter{it.distance<=best.distance+max(4.0,fix.accuracy*0.7) && it.score<=best.score+5.0}
        fun connected(a:Candidate,b:Candidate)=listOf(a.road.a,a.road.b).any{p->
            listOf(b.road.a,b.road.b).any{navigationDistance(p,it)<1.0}}
        fun sameRoad(a:Candidate,b:Candidate)=a.road.wayId==b.road.wayId ||
            (a.road.name.isNotBlank() && RoadSpeedGeometry875.name(a.road.name)==RoadSpeedGeometry875.name(b.road.name) &&
             (a.road.layer==b.road.layer && a.road.tunnel==b.road.tunnel && a.road.bridge==b.road.bridge || connected(a,b)) &&
             a.value?.kmh==b.value?.kmh && a.value?.kind==b.value?.kind)
        // Parallel/flyover ambiguity is not resolved by straight-line proximity alone.
        val conflict=plausible.firstOrNull{!sameRoad(best,it)}
        if(conflict!=null){
            if(conflict.road.layer!=best.road.layer || conflict.road.tunnel!=best.road.tunnel || conflict.road.bridge!=best.road.bridge)
                return invalidate("Ambiguous stacked road/flyover; check signs")
            val old=stable?.let{s->plausible.firstOrNull{it.key==s.key}}
            if(old!=null && old.distance<=min(8.0,fix.accuracy.toDouble()) &&
                old.score<=best.score+3.0 && hint.isNotBlank() && RoadSpeedGeometry875.name(old.road.name)==hint) {
                ambiguousHeld++
                if(ambiguousHeld>2)return invalidate("Road ambiguity persisted; previous limit expired")
                best=old
            }
            else return invalidate("Ambiguous parallel road/junction; check signs")
        } else ambiguousHeld=0
        val value=best.value?:return invalidate(if(best.direction==0)"Travel direction unconfirmed; check signs" else best.road.unknownReason)
        if(pending!=best.key){
            val continuous=stable?.let{it.value?.kmh==value.kmh && it.road.name.isNotBlank() &&
                RoadSpeedGeometry875.name(it.road.name)==RoadSpeedGeometry875.name(best.road.name)}==true
            pending=best.key;count=if(continuous)2 else 1
            if(!continuous){
                stable=null;overspeed=0;within=0;wasOver=false
                last=RoadSpeedState875(diagnostics="Confirming current road segment and direction")
                return last
            }
        }
        count++
        if(count<2)return last
        stable=best
        val speed=fix.speed?.takeIf{it.isFinite() && it>=0}
        val reliable=speed!=null && fix.speedAccuracy?.let{it.isFinite()&&it in 0f..1.5f}==true
        if(!overspeedEnabled || value.kind!=RoadLimitSource875.OFFICIAL || !reliable){overspeed=0;within=0;wasOver=false}
        else if(speed!!*3.6>value.kmh+4){overspeed++;within=0;if(overspeed>=3)wasOver=true}
        else if(speed!!*3.6<value.kmh-2){within++;overspeed=0;if(within>=3)wasOver=false}
        last=RoadSpeedState875(value,best.road.id+if(best.direction==1)":F" else ":B",best.road.name,
            "${value.kind.label}: ${best.road.name} [${best.road.id}] ${best.distance.toInt()}m; ${value.source}",wasOver,fix.timeMs)
        return last
    }
}
