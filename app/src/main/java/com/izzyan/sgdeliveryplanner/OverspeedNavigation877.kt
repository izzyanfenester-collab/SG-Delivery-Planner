package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.*

internal class OverspeedPreferences877(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("sg_overspeed_877",0)
    private val legacy=context.applicationContext.getSharedPreferences("sg_camera_safety_865",0)
    fun get()=OverspeedOptions877(
        prefs.getBoolean("enabled",legacy.getBoolean("overspeed",true)),
        prefs.getBoolean("redText",true),
        prefs.getBoolean("voice",legacy.getBoolean("verifiedOverspeedVoice",true)),
        prefs.getInt("tolerance",3).coerceIn(1,5),
        prefs.getInt("cooldown",90).coerceIn(90,300))
    fun save(value:OverspeedOptions877){prefs.edit().putBoolean("enabled",value.enabled)
        .putBoolean("redText",value.redText).putBoolean("voice",value.voice)
        .putInt("tolerance",value.toleranceKmh).putInt("cooldown",value.cooldownSeconds).apply()}
}
internal data class OverspeedContext877(
    val navigating:Boolean,val muted:Boolean,val turnMetres:Double?,val maneuver:String?,
    val lastCameraStartedMs:Long?=null
)

/** Uses the existing session controller; no second player, focus owner, TTS or queue. */
internal class OverspeedNavigation877(
    context:Context,private val audio:NavigationVoiceAudio,
    private val clock:()->Long={SystemClock.elapsedRealtime()}
) {
    private val store=OverspeedPreferences877(context)
    var options by mutableStateOf(store.get());private set
    private val detector=OverspeedDetector877()
    private val gate=OverspeedVoiceGate877()
    var state by mutableStateOf(OverspeedState877());private set
    var diagnostic by mutableStateOf("Waiting for verified road / reliable GPS");private set
    private var pendingKey:String?=null
    private var attempt=0L
    val red get()=options.enabled && options.redText && state.confirmed
    val assetAvailable get()=audio.available209 && audio.cameraPack.clips[CLIP]?.text==TEXT
    fun update(value:OverspeedOptions877){
        val toleranceChanged=options.toleranceKmh!=value.toleranceKmh
        options=value;store.save(value)
        if(toleranceChanged)clear("GPS warning tolerance changed; confirming new samples")
        if(!value.enabled)clear("Overspeed Warning is OFF")
        if(!value.voice)stopOwn("overspeed-voice-disabled")
    }
    fun observe(fix:RoadSpeedFix875,road:RoadSpeedState875,context:OverspeedContext877) {
        if(!context.navigating){clear("No current navigation approach");return}
        state=detector.observe(fix,road,options,clock())
        if(!state.confirmed){stopOwn("overspeed-ended-or-not-verified");diagnostic=state.reason}
        Log.d("SGOverspeed877","event=${state.eventId} speed=${state.speedKmh} limit=${state.limitKmh} segment=${state.segment} verified=${road.limit?.kind} red=$red reason=${state.reason}")
        tick(context)
    }
    fun clear(reason:String){state=detector.clear(reason);stopOwn(reason);diagnostic=reason}
    private fun stopOwn(reason:String) {
        pendingKey=null;gate.cancel()
        if(audio.activePhraseKey?.startsWith(PREFIX)==true)audio.stop(reason)
    }
    fun tick(context:OverspeedContext877) {
        state=detector.expire(clock())
        if(!context.navigating || !state.confirmed || !options.enabled) {
            if(!context.navigating)state=detector.clear("Navigation approach changed")
            stopOwn("overspeed-no-longer-relevant");return
        }
        if(context.muted || !options.voice){stopOwn("overspeed-guidance-disabled");diagnostic="Voice guidance or overspeed voice is OFF";return}
        val now=clock()
        if(!gate.due(state,now,options.cooldownSeconds))return
        val blocked=CameraVoicePriority872.deferredReason(context.turnMetres,audio.activePriority,context.maneuver)
        if(blocked!=null || context.lastCameraStartedMs?.let{now-it<12_000}==true) {
            diagnostic="DEFERRED: ${blocked?:"recent-camera-warning"}";return
        }
        if(!assetAvailable){diagnostic="FAILED: authentic overspeed_warning.mp3 missing or manifest text mismatch";Log.e("SGOverspeed877",diagnostic);gate.rejected(now);return}
        val event=state.eventId
        val key="$PREFIX$event-${++attempt}"
        pendingKey=key
        val accepted=audio.play(VoicePhrase(key,VoicePriority.CAMERA,listOf(VoicePart(CLIP,TEXT))),
            stillRelevant={pendingKey==key && state.confirmed && state.eventId==event &&
                options.enabled && options.voice && clock()-state.atMs<=5_000}) { playback ->
            if(pendingKey!=key)return@play
            val started=playback.state==PandaPlaybackState866.STARTED
            val terminal=playback.state!=PandaPlaybackState866.STARTED
            gate.playback(event,started,terminal,clock())
            diagnostic="${playback.state}: overspeed_warning.mp3 (${playback.reason})"
            Log.i("SGOverspeed877","event=$event key=$key $diagnostic")
            if(terminal)pendingKey=null
        }
        if(accepted){gate.requested(event,now);diagnostic="PENDING: overspeed_warning.mp3"}
        else {pendingKey=null;gate.rejected(now);diagnostic="DEFERRED: controller busy / audio focus"}
    }
    fun preview(muted:Boolean) {
        if(muted || !assetAvailable){diagnostic="Preview unavailable: guidance muted or authentic MP3 missing";return}
        val accepted=audio.play(VoicePhrase("preview-overspeed-877",VoicePriority.PREVIEW,listOf(VoicePart(CLIP,TEXT))))
        diagnostic=if(accepted)"Preview requested" else "Preview deferred for higher-priority guidance"
    }
    companion object {
        const val PREFIX="overspeed-877-"
        const val CLIP="overspeed_warning"
        const val TEXT="Easy there, warrior! You're above the verified speed limit. Slow down safely!"
    }
}
