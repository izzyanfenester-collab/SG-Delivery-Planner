package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonParser
import java.util.Locale

internal const val PANDA_ASSET_ROOT="voicepacks/panda_warrior"
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
            require(list.size==67 && list.map{it.id}.toSet().size==67)
            val files=context.assets.list("$PANDA_ASSET_ROOT/audio")?.toSet().orEmpty()
            require(list.all{it.path.substringAfter('/') in files})
            list.associateBy{it.id}
        }.getOrDefault(emptyMap())
    }
    val available get()=clips.size==67
    fun open(id:String)=clips[id]?.let { context.assets.openFd("$PANDA_ASSET_ROOT/${it.path}") }
}
/** A single playback pipeline for recorded clips, supplemental TTS and preview.
 * All public methods are main-thread confined. Preparation never suspends routing. */
internal class NavigationVoiceAudio(context:Context):AutoCloseable {
    private val app=context.applicationContext
    val pack=PandaVoicePack(app)
    private val manager=app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler=Handler(Looper.getMainLooper())
    private val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).setOnAudioFocusChangeListener({change->
            if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) stop()
        },handler).build()
    private var focusHeld=false
    private var player:MediaPlayer?=null
    private var tts:TextToSpeech?=null
    private var ttsReady=false
    private var closed=false
    private var generation=0L
    private var partIndex=0
    private var current:VoicePhrase?=null
    private var volume=1f
    var previewing by mutableStateOf(false);private set
    init {
        tts=TextToSpeech(app){status->handler.post {
            if(closed)return@post
            val engine=tts?:return@post
            if(status==TextToSpeech.SUCCESS){
                val lang=engine.setLanguage(Locale.US)
                ttsReady=lang!=TextToSpeech.LANG_MISSING_DATA && lang!=TextToSpeech.LANG_NOT_SUPPORTED
                engine.setAudioAttributes(attrs)
                // Prefer an installed English voice for dynamic instructions.
                runCatching { engine.voices?.firstOrNull{!it.isNetworkConnectionRequired && it.locale.language=="en" && it.locale.country=="US"}?.let{engine.voice=it} }
                engine.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                    override fun onStart(id:String?){Log.d("SGVoice852","tts-start id=$id")}
                    override fun onDone(id:String?){handler.post{completeTts(id)}}
                    @Deprecated("Deprecated in Java") override fun onError(id:String?){handler.post{completeTts(id)}}
                    override fun onError(id:String?,errorCode:Int){handler.post{completeTts(id)}}
                })
            }
        }}
    }
    fun setVolume(value:Float){volume=value.coerceIn(0f,1f);runCatching{player?.setVolume(volume,volume)}}
    fun play(phrase:VoicePhrase):Boolean {
        if(closed || volume<=0f || phrase.parts.isEmpty())return false
        val old=current
        if(old!=null && (old.key==phrase.key || old.priority.rank>phrase.priority.rank))return false
        stop()
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return false
        focusHeld=true;current=phrase;partIndex=0;previewing=phrase.priority==VoicePriority.PREVIEW
        playPart(generation)
        return true
    }
    private fun playPart(token:Long) {
        if(token!=generation || closed)return
        val phrase=current?:return
        val part=phrase.parts.getOrNull(partIndex)?:run{stop();return}
        val requested=SystemClock.elapsedRealtime()
        if(part.clip!=null) {
            try {
                val fd=pack.open(part.clip)?:throw IllegalStateException("Clip unavailable")
                val media=MediaPlayer()
                player=media
                fd.use{media.setAudioAttributes(attrs);media.setDataSource(it.fileDescriptor,it.startOffset,it.length)}
                media.setVolume(volume,volume)
                media.setOnPreparedListener {
                    if(token==generation && player===media) {
                        try {media.start();Log.d("SGVoice852","clip=${part.clip} startCallMs=${SystemClock.elapsedRealtime()-requested}")}
                        catch(_:Exception){fallbackMedia(token,media,part.fallback)}
                    }
                }
                media.setOnCompletionListener {
                    if(token==generation && player===media){releasePlayer();partIndex++;playPart(token)}
                }
                media.setOnErrorListener { _,_,_->fallbackMedia(token,media,part.fallback);true }
                media.prepareAsync()
                return
            } catch(_:Exception){releasePlayer()}
        }
        speakPart(token,part.fallback)
    }
    private fun fallbackMedia(token:Long,media:MediaPlayer,text:String) {
        if(token!=generation || player!==media)return
        releasePlayer();speakPart(token,text)
    }
    private fun speakPart(token:Long,text:String) {
        if(token!=generation)return
        val engine=tts
        if(!ttsReady || engine==null || text.isBlank()){partIndex++;playPart(token);return}
        val id="$token:$partIndex"
        val result=engine.speak(text,TextToSpeech.QUEUE_FLUSH,Bundle().apply{putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume)},id)
        if(result==TextToSpeech.ERROR){partIndex++;playPart(token)}
    }
    private fun completeTts(id:String?) {
        if(current==null || id!="$generation:$partIndex")return
        partIndex++;playPart(generation)
    }
    private fun releasePlayer(){val old=player;player=null;runCatching{old?.release()}}
    fun stopPreview(){if(previewing)stop()}
    fun stop(){
        generation++;current=null;previewing=false
        releasePlayer();runCatching{tts?.stop()}
        if(focusHeld){focusHeld=false;runCatching{manager.abandonAudioFocusRequest(focus)}}
    }
    override fun close(){stop();closed=true;tts?.shutdown();tts=null;ttsReady=false}
}
/** Delivery events use the same controller, scoped to MainActivity's foreground
 * lifetime. The navigation activity has its own controller and MainActivity
 * stops playback before launching it. */
internal object DeliveryVoiceAnnouncements {
    private var audio:NavigationVoiceAudio?=null
    fun play(context:Context,id:String) {
        val prefs=GpsNavigationModePreferences(context)
        if(!prefs.isSoundEnabled() || prefs.voice()!=NavigationVoice.PANDA)return
        val fallback=when(id){"next_delivery"->"On to the next delivery.";"delivery_complete"->"All deliveries completed.";else->return}
        val controller=audio?:NavigationVoiceAudio(context).also{audio=it}
        controller.setVolume(prefs.voiceVolume())
        controller.play(VoicePhrase(id,VoicePriority.CHARACTER,listOf(VoicePart(id,fallback))))
    }
    fun stop(){audio?.stop()}
    fun close(){audio?.close();audio=null}
}
