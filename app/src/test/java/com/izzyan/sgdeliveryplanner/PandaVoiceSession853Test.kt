package com.izzyan.sgdeliveryplanner
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PandaVoiceSession853Test {
    @Test fun selectedVoiceAndVolumeSurviveFreshSessionPreferenceInstance(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("sg_delivery_gps_navigation",0).edit().clear().commit()
        val a=GpsNavigationModePreferences(app)
        assertEquals(NavigationVoice.DEFAULT,a.voice())
        a.setVoice(NavigationVoice.PANDA);a.setSoundEnabled(true);a.setVoiceVolume(1f)
        val b=GpsNavigationModePreferences(app)
        assertEquals(NavigationVoice.PANDA,b.voice());assertTrue(b.isSoundEnabled())
        assertEquals(1f,b.voiceVolume(),0f)
        b.setVoice(NavigationVoice.DEFAULT)
        assertEquals(NavigationVoice.DEFAULT,GpsNavigationModePreferences(app).voice())
    }
}
