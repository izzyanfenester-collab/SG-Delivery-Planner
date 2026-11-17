package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonParser
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal const val PANDA_ASSET_ROOT="voicepacks/panda_warrior_v2"
internal data class PandaClip(val id:String,val path:String,val text:String,val seconds:Double)
internal class PandaVoicePack(private val context:Context) {
    val clips:Map<String,PandaClip> by lazy {
        runCatching {
            val root=context.assets.open("$PANDA_ASSET_ROOT/manifest.json").bufferedReader().use {
                JsonParser.parseReader(it).asJsonObject
            }
            val list=root["clips"].asJsonArray.map { element ->
                val c=element.asJsonObject
                val id=c["id"].asString;val path=c["file"].asString
                require(id.matches(Regex("[a-z0-9_]+")) && path=="audio/$id.mp3")
                PandaClip(id,path,c["text"].asString,c["duration_seconds"].asDouble)
            }
            require(list.size==185 && list.map{it.id}.toSet().size==185)
            val files=context.assets.list("$PANDA_ASSET_ROOT/audio")?.toSet().orEmpty()
            require(list.all{it.path.substringAfter('/') in files})
            val pcm=context.assets.list("$PANDA_ASSET_ROOT/audio_pcm")?.toSet().orEmpty()
            require(list.all{"${it.id}.pcm" in pcm})
            list.associateBy{it.id}
        }.getOrDefault(emptyMap())
    }
    val available get()=clips.size==185
    fun open(id:String)=clips[id]?.let { context.assets.openFd("$PANDA_ASSET_ROOT/${it.path}") }
}
/** Separate optional authentic Panda V2 camera pack. Never synthesize or TTS-fallback.
 * A manifest is activated only when every actual MP3 and derived PCM exists.
 */
internal const val PANDA_CAMERA_ROOT="voicepacks/panda_warrior_camera"
internal class PandaCameraVoicePack(private val context:Context) {
    val clips:Map<String,PandaClip> by lazy {
        runCatching {
            val json=context.assets.open("$PANDA_CAMERA_ROOT/manifest.json")
                .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            val parsed=json["clips"].asJsonArray.map { value ->
                val obj=value.asJsonObject
                val id=obj["id"].asString
                require(id.matches(Regex("[a-z0-9_]+")) && id in CameraVoiceAssetIds865.ALL)
                val file=obj["file"].asString
                require(file=="audio/camera_alerts/$id.mp3")
                PandaClip(id,file,obj["text"].asString,obj["duration_seconds"].asDouble)
            }
            require(parsed.size==24 && parsed.map{it.id}.toSet().size==24)
            val mp3=context.assets.list("$PANDA_CAMERA_ROOT/audio/camera_alerts")?.toSet().orEmpty()
            val pcm=context.assets.list("$PANDA_CAMERA_ROOT/audio_pcm")?.toSet().orEmpty()
            require(parsed.all{it.path.substringAfterLast('/') in mp3 && "${it.id}.pcm" in pcm})
            parsed.associateBy{it.id}
        }.getOrDefault(emptyMap())
    }
    val available get()=clips.size==24
}

/** Original MP3s stay intact. Prepared PCM is decoded from those same recordings.
 * Buffer a whole sentence into one WAV before playback, with controlled gaps.
 * Preparation runs off the navigation thread and is cancelled on route change. */
internal object PandaWav {
    const val SAMPLE_RATE=24000
    fun write(out:OutputStream,parts:List<ByteArray>,gapMs:Int=65) {
        require(parts.isNotEmpty() && parts.all{it.isNotEmpty()&&it.size%2==0})
        val gap=ByteArray(SAMPLE_RATE*2*gapMs/1000)
        val size=parts.sumOf{it.size}+gap.size*(parts.size-1)
        require(size in 1..4_000_000)
        fun ascii(s:String)=out.write(s.toByteArray(Charsets.US_ASCII))
        fun le(n:Int,bytes:Int){repeat(bytes){out.write((n ushr (it*8)) and 255)}}
        ascii("RIFF");le(size+36,4);ascii("WAVEfmt ");le(16,4);le(1,2);le(1,2)
        le(SAMPLE_RATE,4);le(SAMPLE_RATE*2,4);le(2,2);le(16,2);ascii("data");le(size,4)
        parts.forEachIndexed{i,bytes->if(i>0)out.write(gap);out.write(bytes)}
    }
}
/** Delivered on main thread; ACCEPTED is NOT proof of audible playback. */
internal enum class PandaPlaybackState866 { STARTED, COMPLETED, INTERRUPTED, CANCELLED, FAILED }
internal data class PandaPlaybackEvent866(
    val phraseKey:String,
    val state:PandaPlaybackState866,
    val reason:String,
    val elapsedMs:Long
)
internal class NavigationVoiceAudio(context:Context):AutoCloseable {
    private val app=context.applicationContext
    val pack=PandaVoicePack(app)
    val cameraPack=PandaCameraVoicePack(app)
    val available209 get()=pack.available && cameraPack.available && (pack.clips.keys intersect cameraPack.clips.keys).isEmpty()
    private val manager=app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler=Handler(Looper.getMainLooper())
    private val executor=Executors.newSingleThreadExecutor()
    private var preparation:Future<*>?=null
    private val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).setOnAudioFocusChangeListener({change->
            if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) stop()
        },handler).build()
    private var focusHeld=false
    private var player:MediaPlayer?=null
    private var waveFile:File?=null
    private var closed=false
    private var generation=0L
    private var current:VoicePhrase?=null
    private var playbackObserver:((PandaPlaybackEvent866)->Unit)?=null
    private var playbackStarted=false
    val activePriority get()=current?.priority
    val activePhraseKey get()=current?.key
    val isPlaybackStarted get()=playbackStarted
    private fun notifyCurrent(state:PandaPlaybackState866,reason:String){
        val key=current?.key ?: return
        val receiver=playbackObserver ?: return
        // All existing callers run on the main thread; MP3 worker posts callbacks to it.
        runCatching { receiver(PandaPlaybackEvent866(key,state,reason,SystemClock.elapsedRealtime())) }
            .onFailure { Log.e("SGPanda866","playback observer threw",it) }
    }

    private var volume=1f
    var previewing by mutableStateOf(false);private set
    companion object { private var owner:NavigationVoiceAudio?=null }
    fun setVolume(value:Float){volume=value.coerceIn(0f,1f);runCatching{player?.setVolume(volume,volume)};if(volume<=0f)stop()}
    fun play(
        phrase:VoicePhrase,
        observer:((PandaPlaybackEvent866)->Unit)?=null
    ):Boolean {
        if(closed || volume<=0f || phrase.parts.isEmpty()) {
            Log.w("SGPanda866","REJECTED key=${phrase.key} reason=closed-muted-or-empty")
            return false
        }
        if(!pack.available || phrase.parts.any{it.clip !in pack.clips && it.clip !in cameraPack.clips}) {
            Log.w("SGPanda866","REJECTED key=${phrase.key} reason=missing-authentic-audio")
            return false
        }
        val oldOwner=owner
        val old=oldOwner?.current
        if(old!=null && (old.key==phrase.key || old.priority.rank>phrase.priority.rank)) {
            Log.d("SGPanda866","REJECTED key=${phrase.key} reason=busy priority=${old.priority}")
            return false
        }
        oldOwner?.stop("preempted_by_${phrase.priority}")
        if(oldOwner!==this) stop("new_owner")
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w("SGPanda866","REJECTED key=${phrase.key} reason=audio-focus-denied")
            return false
        }
        focusHeld=true;owner=this;current=phrase
        playbackObserver=observer;playbackStarted=false
        previewing=phrase.priority==VoicePriority.PREVIEW
        val token=generation
        val requested=SystemClock.elapsedRealtime()
        preparation=executor.submit {
            var file:File?=null
            try {
                val parts=phrase.parts.map { part ->
                    if(Thread.currentThread().isInterrupted)throw InterruptedException()
                    app.assets.open("${if(part.clip in cameraPack.clips)PANDA_CAMERA_ROOT else PANDA_ASSET_ROOT}/audio_pcm/${part.clip}.pcm")
                        .use{it.readBytes()}
                }
                val created=File.createTempFile("panda866_",".wav",app.cacheDir)
                file=created
                created.outputStream().use{PandaWav.write(it,parts)}
                if(Thread.currentThread().isInterrupted)throw InterruptedException()
                handler.post {
                    if(token!=generation || closed){created.delete();return@post}
                    waveFile=created
                    try {
                        val media=MediaPlayer();player=media
                        media.setAudioAttributes(attrs)
                        media.setDataSource(created.absolutePath)
                        media.setVolume(volume,volume)
                        media.setOnPreparedListener {
                            if(token==generation && player===media) {
                                try {
                                    media.start()
                                    playbackStarted=true
                                    Log.i("SGPanda866","PLAYBACK_STARTED key=${phrase.key} clips=${phrase.parts.map{it.clip}} latencyMs=${SystemClock.elapsedRealtime()-requested}")
                                    notifyCurrent(PandaPlaybackState866.STARTED,"mediaplayer-started")
                                }catch(e:Exception){
                                    Log.e("SGPanda866","PLAYBACK_FAILED key=${phrase.key} start",e)
                                    stop("start-failed")
                                }
                            }
                        }
                        media.setOnCompletionListener {
                            if(token==generation&&player===media)stop("completed")
                        }
                        media.setOnErrorListener { _,what,extra ->
                            if(token==generation&&player===media) {
                                Log.e("SGPanda866","PLAYBACK_FAILED key=${phrase.key} media=$what/$extra")
                                stop("media-error-$what-$extra")
                            }
                            true
                        }
                        media.prepareAsync()
                    }catch(e:Exception){
                        Log.e("SGPanda866","PLAYBACK_FAILED key=${phrase.key} prepare",e)
                        stop("prepare-failed")
                    }
                }
            }catch(e:Exception){
                file?.delete()
                if(e !is InterruptedException)handler.post {
                    if(token==generation){
                        Log.e("SGPanda866","PLAYBACK_FAILED key=${phrase.key} asset/pcm",e)
                        stop("asset-prepare-failed")
                    }
                }
            }
        }
        Log.d("SGPanda866","PLAYBACK_ACCEPTED key=${phrase.key}; waiting for real STARTED")
        return true
    }
    fun stopPreview(){if(previewing)stop("preview-stopped")}
    /** Invalidate old-route camera speech; do not cancel unrelated urgent guidance. */
    fun stopObsoleteCameraForRouteChange(){
        if(current?.priority==VoicePriority.CAMERA)stop("route-changed")
    }
    fun stopObsoleteManeuver(index:Int) {
        val key=current?.key ?: return
        if(key.startsWith("step-") && !key.startsWith("step-$index-")) stop("obsolete-maneuver")
    }
    fun stop(reason:String="stopped"){
        if(current!=null) {
            val state=when {
                reason=="completed" -> PandaPlaybackState866.COMPLETED
                reason.contains("fail") || reason.startsWith("media-error") ->
                    PandaPlaybackState866.FAILED
                playbackStarted -> PandaPlaybackState866.INTERRUPTED
                else -> PandaPlaybackState866.CANCELLED
            }
            Log.i("SGPanda866","PLAYBACK_${state.name} key=${current?.key} reason=$reason wasStarted=$playbackStarted")
            notifyCurrent(state,reason)
        }
        // Invalidate async PCM/MediaPlayer callbacks before releasing resources.
        generation++;current=null;previewing=false
        playbackObserver=null;playbackStarted=false
        preparation?.cancel(true);preparation=null
        val old=player;player=null;runCatching{old?.release()}
        waveFile?.delete();waveFile=null
        if(owner===this)owner=null
        if(focusHeld){focusHeld=false;runCatching{manager.abandonAudioFocusRequest(focus)}}
    }
    override fun close(){stop("session-closed");closed=true;executor.shutdownNow()}
}
/** Delivery events use the same controller, scoped to MainActivity's foreground
 * lifetime. The navigation activity has its own controller and MainActivity
 * stops playback before launching it. */
internal object DeliveryVoiceAnnouncements {
    private var audio:NavigationVoiceAudio?=null
    private var foreground=false
    fun resume(){foreground=true}
    fun pause(){foreground=false;stop()}
    fun play(context:Context,id:String) {
        if(!foreground)return
        val prefs=GpsNavigationModePreferences(context)
        if(!prefs.isSoundEnabled())return
        val fallback=when(id){"next_delivery"->"On to the next delivery.";"delivery_complete"->"All deliveries completed.";else->return}
        val controller=audio?:NavigationVoiceAudio(context).also{audio=it}
        controller.setVolume(prefs.voiceVolume())
        controller.play(VoicePhrase(id,VoicePriority.CHARACTER,listOf(VoicePart(id,fallback))))
    }
    fun stop(){audio?.stop()}
    fun close(){foreground=false;audio?.close();audio=null}
}
