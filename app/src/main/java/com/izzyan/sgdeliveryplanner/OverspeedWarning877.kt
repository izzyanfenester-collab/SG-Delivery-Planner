package com.izzyan.sgdeliveryplanner

/** Warning thresholds account for measurement noise; they do not alter a legal limit. */
internal data class OverspeedOptions877(
    val enabled:Boolean=true,val redText:Boolean=true,val voice:Boolean=true,
    val toleranceKmh:Int=3,val cooldownSeconds:Int=90
) {
    init { require(toleranceKmh in 1..5 && cooldownSeconds in 90..300) }
}
internal data class OverspeedState877(
    val confirmed:Boolean=false,val eventId:Long=0,val limitKmh:Int?=null,
    val segment:String?=null,val speedKmh:Double?=null,val atMs:Long=0,
    val reason:String="Waiting for a verified current road and reliable GPS speed"
)
internal class OverspeedDetector877 {
    private var serial=0L
    private var lastFix=0L
    private var key:String?=null
    private var enterAt:Long?=null
    private var exitAt:Long?=null
    var state=OverspeedState877();private set
    fun clear(reason:String):OverspeedState877 {
        key=null;enterAt=null;exitAt=null;lastFix=0
        state=OverspeedState877(eventId=serial,reason=reason)
        return state
    }
    fun expire(now:Long):OverspeedState877 =
        if(lastFix>0 && now-lastFix>5_000)clear("GPS speed stale; overspeed warning cleared") else state
    fun observe(fix:RoadSpeedFix875,road:RoadSpeedState875,options:OverspeedOptions877,now:Long):OverspeedState877 {
        if(!options.enabled)return clear("Overspeed Warning is OFF")
        val limit=road.limit
        val segment=road.segmentId
        if(limit?.kind!=RoadLimitSource875.OFFICIAL || segment==null ||
            !(segment.endsWith(":F") || segment.endsWith(":B")))
            return clear("No verified directional road limit; no overspeed claim")
        if(!fix.point.valid() || !fix.accuracy.isFinite() || fix.accuracy !in 0.1f..20f ||
            fix.speed?.let{it.isFinite() && it>=0}!=true ||
            fix.speedAccuracy?.let{it.isFinite() && it in 0f..1f}!=true ||
            fix.timeMs<=0 || now-fix.timeMs !in -500..5_000 || road.observedAtMs!=fix.timeMs)
            return clear("GPS speed/accuracy or road observation is not current and reliable")
        if(lastFix>0 && fix.timeMs<=lastFix)return expire(now)
        // The offline catalog splits one OSM way into short geometry edges.
        // Crossing an edge on the same verified directional way is continuous
        // evidence, not a new overspeed approach or a reason to flicker red.
        val directionalWay="${segment.substringBefore(':')}:${segment.substringAfterLast(':')}"
        val nextKey="$directionalWay|${limit.kmh}|${limit.source}"
        if(key!=nextKey || lastFix>0 && fix.timeMs-lastFix>2_500) {
            clear("Confirming verified road and speed");key=nextKey
        }
        lastFix=fix.timeMs
        val kmh=requireNotNull(fix.speed).toDouble()*3.6
        var confirmed=state.confirmed
        var reason="Within permitted speed"
        if(kmh>limit.kmh+options.toleranceKmh) {
            exitAt=null
            if(!confirmed) {
                if(enterAt==null)enterAt=fix.timeMs
                confirmed=fix.timeMs-requireNotNull(enterAt)>=2_000
                if(confirmed)serial++
            }
            reason=if(confirmed)"Confirmed overspeed" else "Confirming overspeed for two seconds"
        }else if(confirmed && kmh<=limit.kmh) {
            enterAt=null
            if(exitAt==null)exitAt=fix.timeMs
            if(fix.timeMs-requireNotNull(exitAt)>=1_000){confirmed=false;exitAt=null}
            reason=if(confirmed)"Stabilising return to permitted speed" else "Permitted speed restored"
        }else {
            enterAt=null;exitAt=null
            if(confirmed)reason="Overspeed remains active until permitted speed is restored"
        }
        state=OverspeedState877(confirmed,serial,limit.kmh,segment,kmh,fix.timeMs,reason)
        return state
    }
}

/** A request is pending, never considered spoken, until MediaPlayer STARTED. */
internal class OverspeedVoiceGate877 {
    private var pending:Long?=null
    private var lastStarted:Long?=null
    private var lastAttempt:Long?=null
    fun due(state:OverspeedState877,now:Long,cooldownSeconds:Int):Boolean =
        state.confirmed && pending==null &&
            lastStarted?.let{now-it>=cooldownSeconds*1_000L}!=false &&
            lastAttempt?.let{now-it>=5_000L}!=false
    fun requested(event:Long,now:Long){pending=event;lastAttempt=now}
    fun rejected(now:Long){pending=null;lastAttempt=now}
    fun playback(event:Long,started:Boolean,terminal:Boolean,now:Long) {
        if(pending!=event)return
        if(started)lastStarted=now
        if(terminal)pending=null
    }
    fun cancel(){pending=null;lastAttempt=null}
}
