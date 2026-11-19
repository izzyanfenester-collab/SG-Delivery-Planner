package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import java.time.Duration

/** Real production controller and supplied MP3 files; Android MediaPlayer is
 * simulated by Robolectric. This does NOT certify speaker/Bluetooth/on-road audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@LooperMode(LooperMode.Mode.PAUSED)
class Camera872PlaybackTest {
    private lateinit var audio:NavigationVoiceAudio
    private lateinit var manager:AudioManager
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        audio=NavigationVoiceAudio(context)
        // Generic repository CI omits voice assets; the B872 release workflow
        // restores B871 assets and explicitly rejects skipped acceptance tests.
        assumeTrue("Run restore_b871_assets872.py for authentic playback tests",audio.available209)
        manager=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.setStreamVolume(AudioManager.STREAM_MUSIC,5,0)
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(5000,0) }
    }
    @After fun close(){if(::audio.isInitialized)audio.close()}
    private fun spinUntil(predicate:()->Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if(predicate())return
            Thread.sleep(5)
        }
        assertTrue("Timed out awaiting async production audio callback",predicate())
    }
    private fun camera(id:String,category:String="FIXED_SPEED_CAMERA")=EnforcementCamera(
        id,category,"Fixture",GpsPoint(1.3,103.8),"Fixture Road","Exit Road",null,
        "https://www.police.gov.sg/test",null,90.0,true,null,null,null,
        if(category=="RED_LIGHT_SPEED_CAMERA")"VERIFIED_SPEED_ENFORCEMENT" else "PUBLISHED_LOCATION_NOT_LIVE_STATUS",
        if(category=="AVERAGE_SPEED_ZONE")"FIXTURE-ZONE" else null,
        averageZoneEntryVerified=category=="AVERAGE_SPEED_ZONE",
        zoneEvidenceUrl="https://www.police.gov.sg/test-fixture",
        speedEnforcementEvidenceUrl="https://www.police.gov.sg/test-fixture")
    private fun cue(p:CameraVoicePlanner865,cam:EnforcementCamera):CameraVoiceCue865 {
        p.due(CameraApproach(cam,2150.0,true,false),CameraAlertPreferences(),true,true,800.0,16f)
        return p.due(CameraApproach(cam,1890.0,true,true),CameraAlertPreferences(),true,true,800.0,16f)!!
    }
    @Test fun allSevenCategoriesReachActualControllerStartCallbackUsingOriginalMp3Offline() {
        for(category in listOf("FIXED_SPEED_CAMERA","LTA_SPEED_CAMERA","MOBILE_ENFORCEMENT_LOCATION",
            "POLICE_LASER_LOCATION","AVERAGE_SPEED_ZONE","RED_LIGHT_CAMERA","RED_LIGHT_SPEED_CAMERA")) {
            val planner=CameraVoicePlanner865();val cue=cue(planner,camera(category,category))
            val events=mutableListOf<PandaPlaybackEvent866>()
            assertTrue(audio.play(cue.phrase) { e->events+=e;planner.playbackEvent(cue,e) })
            planner.markRequested(cue)
            assertFalse(planner.isStarted(cue))
            spinUntil{events.any{it.state==PandaPlaybackState866.STARTED}}
            assertTrue(audio.isPlaybackStarted);assertTrue(planner.isStarted(cue))
            println("B872 CONTROLLER_REPLAY category=$category previous=2150 current=1890 threshold=2000 crossed=true mp3=${cue.clipId}.mp3 playback=STARTED backend=Robolectric-ShadowMediaPlayer physical-device=false")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            assertTrue(events.any{it.state==PandaPlaybackState866.COMPLETED})
            assertNull(audio.activePhraseKey)
        }
    }
    @Test fun equalPriorityCameraCannotInterruptAnotherCameraButCriticalTurnCan() {
        val p=CameraVoicePlanner865();val a=cue(p,camera("ONE"));val b=cue(p,camera("TWO"))
        val events=mutableListOf<PandaPlaybackEvent866>()
        assertTrue(audio.play(a.phrase){events+=it})
        spinUntil{audio.isPlaybackStarted}
        assertFalse(audio.play(b.phrase));assertEquals(a.phrase.key,audio.activePhraseKey)
        val turn=VoicePhrase("urgent-turn",VoicePriority.CRITICAL,listOf(VoicePart("turn_right","Turn right")))
        assertTrue(audio.play(turn))
        assertTrue(events.any{it.state==PandaPlaybackState866.INTERRUPTED})
        assertFalse(audio.play(b.phrase))
        audio.stop("urgent-turn-finished")
        assertTrue(audio.play(b.phrase));spinUntil{audio.isPlaybackStarted}
        assertEquals(b.phrase.key,audio.activePhraseKey)
    }
    @Test fun cameraPassedDuringPreparationIsCancelledBeforeStartAndNotMarkedPlayed() {
        val p=CameraVoicePlanner865();val c=cue(p,camera("STALE"))
        val events=mutableListOf<PandaPlaybackEvent866>()
        assertTrue(audio.play(c.phrase,stillRelevant={false}){events+=it;p.playbackEvent(c,it)})
        p.markRequested(c)
        spinUntil{events.any{it.state==PandaPlaybackState866.CANCELLED}}
        assertFalse(events.any{it.state==PandaPlaybackState866.STARTED})
        assertFalse(p.isStarted(c));assertFalse(p.isRequested(c))
    }
    @Test fun systemMutedFailureIsExplicitAndApproachCanRetryAfterVolumeRestored() {
        val p=CameraVoicePlanner865();val c=cue(p,camera("VOLUME"))
        manager.setStreamVolume(AudioManager.STREAM_MUSIC,0,0)
        val events=mutableListOf<PandaPlaybackEvent866>()
        assertTrue(audio.play(c.phrase){events+=it;p.playbackEvent(c,it)})
        p.markRequested(c)
        spinUntil{events.any{it.state==PandaPlaybackState866.FAILED}}
        assertEquals("system-media-volume-zero-failed",events.last().reason)
        assertFalse(p.isStarted(c))
        manager.setStreamVolume(AudioManager.STREAM_MUSIC,5,0)
        val retry=cue(p,camera("VOLUME"))
        assertTrue(audio.play(retry.phrase){p.playbackEvent(retry,it)})
        p.markRequested(retry);spinUntil{audio.isPlaybackStarted}
        assertTrue(p.isStarted(retry))
    }
    @Test fun preparationTimeoutReleasesPendingAndRouteChangeCancelsOldClip() {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(5000,-1) }
        val p=CameraVoicePlanner865();val c=cue(p,camera("TIMEOUT"))
        val events=mutableListOf<PandaPlaybackEvent866>()
        assertTrue(audio.play(c.phrase){events+=it;p.playbackEvent(c,it)})
        p.markRequested(c)
        // Let the file-copy worker post construction before advancing Android time.
        repeat(30){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue(events.any{it.state==PandaPlaybackState866.FAILED && it.reason=="prepare-timeout-failed"})
        assertFalse(p.isRequested(c));assertFalse(p.isStarted(c))
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(5000,0) }
        assertTrue(audio.play(c.phrase))
        audio.stopObsoleteCameraForRouteChange()
        repeat(10){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertFalse(audio.isPlaybackStarted);assertNull(audio.activePhraseKey)
    }
    @Test fun everyCameraIconIsRerasterizedTwentyPercentLarger() {
        for(category in CameraSafetySnapshotParser.categories) {
            val bitmap=CameraSafetyMapIcons.bitmap(category)
            assertEquals(58,bitmap.width);assertEquals(68,bitmap.height)
            assertTrue(bitmap.width.toDouble()/CameraSafetyMapIcons.BASE_WIDTH in 1.15..1.25)
            assertTrue(bitmap.height.toDouble()/CameraSafetyMapIcons.BASE_HEIGHT in 1.15..1.25)
            bitmap.recycle()
        }
    }
}
