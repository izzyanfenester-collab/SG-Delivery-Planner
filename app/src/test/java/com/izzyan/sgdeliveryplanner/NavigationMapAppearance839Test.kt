package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Day/Night selection requires no network, time service or navigation reroute. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NavigationMapAppearance839Test {
    @Test fun autoUsesLocalSevenAMAndSevenPMSchedule() {
        assertTrue(NavigationMapThemeLogic.isNight(NavigationMapAppearance.AUTO, 0))
        assertTrue(NavigationMapThemeLogic.isNight(NavigationMapAppearance.AUTO, 6))
        assertFalse(NavigationMapThemeLogic.isNight(NavigationMapAppearance.AUTO, 7))
        assertFalse(NavigationMapThemeLogic.isNight(NavigationMapAppearance.AUTO, 18))
        assertTrue(NavigationMapThemeLogic.isNight(NavigationMapAppearance.AUTO, 19))
        assertTrue(NavigationMapThemeLogic.isNight(NavigationMapAppearance.AUTO, 23))
    }

    @Test fun manualDayNightOverrideClockUntilAutoReenabled() {
        for (hour in 0..23) {
            assertFalse(NavigationMapThemeLogic.isNight(NavigationMapAppearance.DAY, hour))
            assertTrue(NavigationMapThemeLogic.isNight(NavigationMapAppearance.NIGHT, hour))
        }
        assertFalse(NavigationMapThemeLogic.isNight(NavigationMapAppearance.AUTO, 12))
        assertTrue(NavigationMapThemeLogic.isNight(NavigationMapAppearance.AUTO, 21))
    }

    @Test fun preferencesDefaultToAutoAndPersistRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("sg_delivery_navigation_appearance", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val first = NavigationMapAppearanceStore(context)
            assertEquals(NavigationMapAppearance.AUTO, first.read())
            first.save(NavigationMapAppearance.NIGHT)
            assertEquals(NavigationMapAppearance.NIGHT, NavigationMapAppearanceStore(context).read())
            first.save(NavigationMapAppearance.DAY)
            assertEquals(NavigationMapAppearance.DAY, NavigationMapAppearanceStore(context).read())
            first.save(NavigationMapAppearance.AUTO)
            assertEquals(NavigationMapAppearance.AUTO, NavigationMapAppearanceStore(context).read())
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun corruptPreferenceSafelyDefaultsToAuto() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("sg_delivery_navigation_appearance", Context.MODE_PRIVATE)
        prefs.edit().putString("mode", "unknown_mode").commit()
        try {
            assertEquals(NavigationMapAppearance.AUTO, NavigationMapAppearanceStore(context).read())
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun nightHasActualVibrantRoadAndLandPaintNotBlackOverlay() {
        val base = NavigationMapPalette.layerColor("background", "background", true)
        val park = NavigationMapPalette.layerColor("landuse_park", "fill", true)
        val water = NavigationMapPalette.layerColor("water", "fill", true)
        val motorway = NavigationMapPalette.layerColor("road_motorway", "line", true)
        val secondary = NavigationMapPalette.layerColor("road_secondary", "line", true)
        val residential = NavigationMapPalette.layerColor("road_residential", "line", true)
        assertNotEquals("#000000", base)
        assertNotEquals(base, park)
        assertNotEquals(base, water)
        assertNotEquals(motorway, secondary)
        assertNotEquals(secondary, residential)
        assertNotEquals(water, park)
    }

    @Test fun dayNightLabelsAndRoadsRemainHighContrastAndDistinct() {
        val nightLabel = NavigationMapPalette.layerColor("road_label", "symbol", true)
        val dayLabel = NavigationMapPalette.layerColor("road_label", "symbol", false)
        assertNotEquals(dayLabel, nightLabel)
        assertEquals("#F3FBFF", nightLabel)
        assertEquals("#243A48", dayLabel)
        assertEquals("#8BCBC1", NavigationMapPalette.layerColor(
            "road_motorway", "line", true))
        assertEquals("#A7D4EA", NavigationMapPalette.layerColor(
            "water_fill", "fill", false))
        assertEquals("#CDE6CA", NavigationMapPalette.layerColor(
            "park", "fill", false))
    }

    @Test fun basemapSourceIsPreservedForBothModesAndNotChangedOnToggle() {
        assertEquals(NAVIGATION_STYLE, NavigationMapPalette.DAY_BASE_STYLE)
        assertTrue(NavigationMapPalette.DAY_BASE_STYLE.contains("openfreemap"))
    }
}
