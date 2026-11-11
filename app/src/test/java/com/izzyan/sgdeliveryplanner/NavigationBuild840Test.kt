package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Build #840 regression checks; Android device driving remains a separate QA gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NavigationBuild840Test {
    @Test fun freshInstallDefaultsTo3dAndVoiceOn() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("sg_delivery_gps_navigation", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val store = GpsNavigationModePreferences(ctx)
            assertTrue(store.is3dEnabled())
            assertTrue(store.isSoundEnabled())
            store.set3dEnabled(false)
            store.setSoundEnabled(false)
            val restarted = GpsNavigationModePreferences(ctx)
            assertFalse(restarted.is3dEnabled())
            assertFalse(restarted.isSoundEnabled())
            restarted.set3dEnabled(true)
            restarted.setSoundEnabled(true)
            assertTrue(GpsNavigationModePreferences(ctx).isSoundEnabled())
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun autoAppearanceDefaultsAndPersists() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("sg_delivery_navigation_appearance", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            assertEquals(NavigationMapAppearance.AUTO, NavigationMapAppearanceStore(ctx).read())
            NavigationMapAppearanceStore(ctx).save(NavigationMapAppearance.NIGHT)
            assertEquals(NavigationMapAppearance.NIGHT, NavigationMapAppearanceStore(ctx).read())
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun basemapRepaintNeverOverridesNativePolylineAnnotations() {
        assertTrue(NavigationMapPalette.isNavigationAnnotation("mapbox-android-polyline-layer"))
        assertTrue(NavigationMapPalette.isNavigationAnnotation("maplibre-android-polyline-layer"))
        assertTrue(NavigationMapPalette.isNavigationAnnotation("sg-navigation-route"))
        assertFalse(NavigationMapPalette.isNavigationAnnotation("road_primary"))
    }

    @Test fun singaporeSunriseSunsetUsesGeographicCoordinatesWithoutApi() {
        val day = LocalDate.of(2026, 11, 11)
        val sunrise = requireNotNull(NavigationSunClock.localMinute(day, 1.35, 103.82, 8 * 3600, true))
        val sunset = requireNotNull(NavigationSunClock.localMinute(day, 1.35, 103.82, 8 * 3600, false))
        assertTrue("Sunrise should be in local morning: $sunrise", sunrise in 340..475)
        assertTrue("Sunset should be in local evening: $sunset", sunset in 1040..1200)
    }

    @Test fun manualOverrideAndAutoFallbackAreStable() {
        val zone = ZoneId.of("Asia/Singapore")
        fun at(hour: Int) = ZonedDateTime.of(2026, 11, 11, hour, 0, 0, 0, zone)
        assertTrue(NavigationMapThemeLogic.isNightAtLocation(NavigationMapAppearance.AUTO, at(5), 1.35, 103.82))
        assertFalse(NavigationMapThemeLogic.isNightAtLocation(NavigationMapAppearance.AUTO, at(12), 1.35, 103.82))
        assertTrue(NavigationMapThemeLogic.isNightAtLocation(NavigationMapAppearance.AUTO, at(23), 1.35, 103.82))
        assertTrue(NavigationMapThemeLogic.isNightAtLocation(NavigationMapAppearance.AUTO, at(6), null, null))
        assertFalse(NavigationMapThemeLogic.isNightAtLocation(NavigationMapAppearance.AUTO, at(12), null, null))
        assertFalse(NavigationMapThemeLogic.isNightAtLocation(NavigationMapAppearance.DAY, at(23), 1.35, 103.82))
        assertTrue(NavigationMapThemeLogic.isNightAtLocation(NavigationMapAppearance.NIGHT, at(12), 1.35, 103.82))
    }
}
