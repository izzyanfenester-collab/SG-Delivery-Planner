package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OverspeedPreferences877Test {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    @Before fun clean(){context.getSharedPreferences("sg_overspeed_877",0).edit().clear().commit();context.getSharedPreferences("sg_camera_safety_865",0).edit().clear().commit()}
    @Test fun freshInstallDefaultsOn(){assertEquals(OverspeedOptions877(),OverspeedPreferences877(context).get())}
    @Test fun explicitLegacyOffPreferencesArePreserved(){context.getSharedPreferences("sg_camera_safety_865",0).edit().putBoolean("overspeed",false).putBoolean("verifiedOverspeedVoice",false).commit();val p=OverspeedPreferences877(context).get();assertFalse(p.enabled);assertFalse(p.voice)}
    @Test fun newOptionsSurviveRestartAndDoNotModifyCameraPreferences(){val p=OverspeedOptions877(false,false,false,5,180);OverspeedPreferences877(context).save(p);assertEquals(p,OverspeedPreferences877(context).get());assertTrue(context.getSharedPreferences("sg_camera_safety_865",0).all.isEmpty())}
}
