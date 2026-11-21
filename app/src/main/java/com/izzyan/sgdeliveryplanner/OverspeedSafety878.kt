package com.izzyan.sgdeliveryplanner

import android.content.Context

/** Overspeed is only actionable against a known, directional OFFICIAL road limit.
 * Validated map data and the general rule still display as advisory road limits,
 * but are not represented as certified limits for automatic speech.
 */
internal data class OverspeedState878(
    val confirmed:Boolean=false,
    val voiceDue:Boolean=false,
    val verifiedLimitKmh:Int?=null,
    val speedKmh:Double?=null,
    val roadSegmentId:String?=null
)

internal class OverspeedSafetyMonitor878 {
    private var segment:String?=null
    private var lastFixMs=0L
    private var aboveSinceMs:Long?=null
    private var belowSinceMs:Long?=null
    private var active=false
    private var lastSpokenMs:Long?=null
    private var pendingPlayback=false
    private var lastAttemptMs=0L
    var state:OverspeedState878=OverspeedState878()
        private set

    fun reset(clearCooldown:Boolean=false) {
        segment=null;lastFixMs=0;aboveSinceMs=null;belowSinceMs=null
        active=false;pendingPlayback=false;lastAttemptMs=0
        state=OverspeedState878()
        if(clearCooldown)lastSpokenMs=null
    }

    fun observe(road:RoadSpeedState875,fix:RoadSpeedFix875,enabled:Boolean,
                nowMs:Long=fix.timeMs,cooldownMs:Long=90_000L):OverspeedState878 {
        val limit=road.limit
        val reliable=enabled && limit?.kind==RoadLimitSource875.OFFICIAL &&
            road.segmentId!=null && road.observedAtMs==fix.timeMs &&
            fix.accuracy.isFinite() && fix.accuracy in 0.1f..25f &&
            fix.speed!=null && fix.speed.isFinite() && fix.speed>=0f &&
            fix.speedAccuracy!=null && fix.speedAccuracy.isFinite() &&
            fix.speedAccuracy in 0f..1.5f && nowMs>0 &&
            (lastFixMs==0L || nowMs>lastFixMs)
        if(!reliable || (lastFixMs>0 && nowMs-lastFixMs>6000L)) {
            reset()
            return state
        }
        val roadId=requireNotNull(road.segmentId)
        if(segment!=roadId) {
            segment=roadId;aboveSinceMs=null;belowSinceMs=null;active=false
            pendingPlayback=false;lastAttemptMs=0L
        }
        lastFixMs=nowMs
        val speed=fix.speed!!.toDouble()*3.6
        val numeric=requireNotNull(limit).kmh
        if(speed>numeric+3.0) {
            belowSinceMs=null
            if(aboveSinceMs==null)aboveSinceMs=nowMs
            if(nowMs-requireNotNull(aboveSinceMs)>=2_000L)active=true
        } else {
            aboveSinceMs=null
            if(speed<=numeric.toDouble()) {
                if(belowSinceMs==null)belowSinceMs=nowMs
                if(nowMs-requireNotNull(belowSinceMs)>=1_000L) {
                    active=false;pendingPlayback=false
                }
            } else belowSinceMs=null
        }
        val cooled=lastSpokenMs==null || nowMs-requireNotNull(lastSpokenMs)>=cooldownMs
        val due=active && cooled && !pendingPlayback
        state=OverspeedState878(active,due,numeric,speed,roadId)
        return state
    }

    fun markRequested(nowMs:Long) {
        if(state.confirmed) {
            pendingPlayback=true
            lastAttemptMs=nowMs
        }
    }

    /** Only a real MediaPlayer STARTED event consumes the voice cooldown. */
    fun playbackStarted(nowMs:Long,cooldownMs:Long=90_000L) {
        if(state.confirmed && pendingPlayback) {
            lastSpokenMs=nowMs
            pendingPlayback=false
            state=state.copy(voiceDue=false)
        }
    }
    fun playbackNotStarted() { pendingPlayback=false }
    fun requestTimedOut(nowMs:Long):Boolean {
        if(pendingPlayback && nowMs-lastAttemptMs>6_000L) {
            pendingPlayback=false
            return true
        }
        return false
    }
}

/** Small independent preference namespace; existing CameraSafetyPrefs remains authoritative
 * for master overspeed and voice enable/disable, avoiding duplicated settings.
 */
internal class OverspeedPreferences878(context:Context) {
    private val store=context.applicationContext.getSharedPreferences("sg_overspeed_safety_878",Context.MODE_PRIVATE)
    fun redSpeedFont()=store.getBoolean("red_speed_font",true)
    fun setRedSpeedFont(enabled:Boolean){store.edit().putBoolean("red_speed_font",enabled).apply()}
    fun cooldownSeconds()=store.getInt("voice_cooldown_seconds",90).takeIf{it in setOf(90,120,180)}?:90
    fun setCooldownSeconds(seconds:Int){require(seconds in setOf(90,120,180));store.edit().putInt("voice_cooldown_seconds",seconds).apply()}
}
