package com.izzyan.sgdeliveryplanner

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GpsNavigationModePreferencesTest {
    @Test fun pandaOnlyMigratesLegacyAndPreservesMuteVolume() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val store=context.getSharedPreferences("sg_delivery_gps_navigation",Context.MODE_PRIVATE)
        store.edit().clear().commit()
        org.junit.Assert.assertEquals(NavigationVoice.PANDA,GpsNavigationModePreferences(context).voice())
        store.edit().putString("navigation_voice","DEFAULT").putBoolean("navigation_sound_enabled",false)
            .putFloat("navigation_voice_volume",0.35f).commit()
        val prefs=GpsNavigationModePreferences(context)
        org.junit.Assert.assertEquals(NavigationVoice.PANDA,prefs.voice())
        org.junit.Assert.assertEquals("PANDA",store.getString("navigation_voice",null))
        assertFalse(prefs.isSoundEnabled())
        org.junit.Assert.assertEquals(0.35f,prefs.voiceVolume(),0.001f)
    }
    @Test fun threeDimensionalIsDefaultAndSelectionSurvivesNewSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("sg_delivery_gps_navigation", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val settings = GpsNavigationModePreferences(context)
        assertTrue(settings.is3dEnabled())
        settings.set3dEnabled(false)
        assertFalse(GpsNavigationModePreferences(context).is3dEnabled())
        settings.set3dEnabled(true)
        assertTrue(GpsNavigationModePreferences(context).is3dEnabled())
    }
}
