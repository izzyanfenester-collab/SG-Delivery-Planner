package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.app.Activity
import org.robolectric.Robolectric
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavigationPreferencesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val activity get() = Robolectric.buildActivity(Activity::class.java).setup().get()
    private fun plan() = schedule(listOf(Place("005001", 1.35, 103.8, "1", "Area", "Road")),
        List(2) { Leg(1.0, 100.0, 10.0) }, LocalDateTime.of(2026, 10, 5, 10, 0), 8, "Normal Traffic", emptyList())

    @Test fun freshInstallAndUnknownValuesAskEveryTime() {
        assertNull(NavigationPreferences(context).default)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("defaultNavigation", "unknown").commit()
        assertNull(NavigationPreferences(context).default)
    }
    @Test fun defaultPersistsAcrossPreferenceRecreationAndAskEveryTimeClearsIt() {
        val prefs = NavigationPreferences(context)
        NavigationChoice.entries.forEach { choice ->
            prefs.updateDefault(choice)
            assertEquals(choice, prefs.default)
            assertEquals(choice, NavigationPreferences(context).default)
        }
        prefs.updateDefault(null)
        assertNull(prefs.default)
        assertNull(NavigationPreferences(context).default)
    }
    @Test fun justOnceLaunchesWithoutSavingAndLeavesExistingDefaultUnchanged() {
        val prefs = NavigationPreferences(context)
        assertTrue(prefs.launch(activity, plan(), NavigationChoice.BUILT_IN, false))
        assertNull(prefs.default)
        assertNull(NavigationPreferences(context).default)
        prefs.updateDefault(NavigationChoice.WAZE)
        assertTrue(prefs.launch(activity, plan(), NavigationChoice.BUILT_IN, false))
        assertEquals(NavigationChoice.WAZE, NavigationPreferences(context).default)
    }
    @Test fun setDefaultLaunchesAndPersistsSelectedProvider() {
        val prefs = NavigationPreferences(context)
        assertTrue(prefs.launch(activity, plan(), NavigationChoice.BUILT_IN, true))
        assertEquals(NavigationChoice.BUILT_IN, prefs.default)
        assertEquals(NavigationChoice.BUILT_IN, NavigationPreferences(context).default)
    }
    @Test fun unavailableDefaultIsNotSilentlyChangedAndAlternativeCanLaunchJustOnce() {
        val prefs = NavigationPreferences(context)
        val plan = plan()
        prefs.updateDefault(NavigationChoice.GOOGLE_MAPS)
        assertFalse(openDeliveryNavigation(context, plan, prefs.default!!))
        assertEquals(NavigationChoice.GOOGLE_MAPS, prefs.default)
        assertTrue(prefs.launch(activity, plan, NavigationChoice.BUILT_IN, false))
        assertEquals(NavigationChoice.GOOGLE_MAPS, NavigationPreferences(context).default)
        assertEquals(0, plan.current)
    }
    @Test fun explicitSetDefaultToUnavailableProviderPersistsUntilUserChangesIt() {
        val prefs = NavigationPreferences(context)
        assertFalse(prefs.launch(activity, plan(), NavigationChoice.WAZE, true))
        assertEquals(NavigationChoice.WAZE, NavigationPreferences(context).default)
        prefs.updateDefault(null)
        assertNull(prefs.default)
    }
}
