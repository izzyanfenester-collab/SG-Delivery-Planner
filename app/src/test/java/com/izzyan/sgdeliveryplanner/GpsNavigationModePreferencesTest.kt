package com.izzyan.sgdeliveryplanner

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GpsNavigationModePreferencesTest {
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
