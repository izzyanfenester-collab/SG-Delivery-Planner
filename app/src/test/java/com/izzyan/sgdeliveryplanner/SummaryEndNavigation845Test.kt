package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SummaryEndNavigation845Test {
    private val custom = StartLocation("CUSTOM", 1.39, 103.91, "Custom End", "1 Custom Road", "519001")
    private fun plan() = schedule(
        (1..3).map { Place("00500$it", 1.35 + it / 1000.0, 103.8, "$it", "Area", "$it Road") },
        List(4) { Leg(1.0, 100.0, 10.0) }, LocalDateTime.of(2026, 11, 12, 10, 0),
        10, "Normal Traffic", emptyList(), endLocation = custom
    ).copy(current = 3, cashOnHand = "12.00", tax = "3.00", remark = "Saved report")

    @Test fun savedCustomEndAndCheckpointAreNotCurrentDeliveryOrHardcoded() {
        val p = plan()
        assertEquals(custom.asPlace(), endNavigationDestination(p))
        assertEquals(custom.asPlace(), endNavigationDestination(p.copy(current = 0)))
        assertEquals(woodlandsStartLocation.asPlace(),
            endNavigationDestination(p.copy(endLocation = woodlandsStartLocation)))
        assertEquals(p.startLocation.asPlace(), endNavigationDestination(p.copy(endLocation = null)))
    }

    @Test fun automaticEndUsesFinalOptimizedStopEvenIfEarlierStopsRemain() {
        val p = plan().copy(endAtLastDelivery = true, current = 0)
        assertEquals(p.stops.last().place, endNavigationDestination(p))
        assertEquals(p.stops.last().place, endNavigationDestination(p.copy(current = 3)))
        assertNull(endNavigationDestination(p.copy(stops = emptyList())))
    }

    @Test fun builtInDefaultOpensOnlyEndSnapshotAndLeavesReportUnchanged() {
        val context = RecordingContext()
        val p = plan()
        val before = PlanJson.encode(p)
        val prefs = NavigationPreferences(context)
        prefs.updateDefault(NavigationChoice.BUILT_IN)
        assertTrue(prefs.launchEnd(context, p, prefs.default!!))
        val intent = context.attempts.single()
        assertEquals(BuiltInNavigationActivity::class.java.name, intent.component!!.className)
        assertEquals(custom.postalCode, intent.getStringExtra("postal"))
        assertEquals(custom.latitude, intent.getDoubleExtra("lat", 0.0), 0.0)
        assertEquals(custom.longitude, intent.getDoubleExtra("lon", 0.0), 0.0)
        assertEquals(setOf("postal","address","lat","lon"), intent.extras!!.keySet())
        assertEquals(before, PlanJson.encode(p))
        assertEquals(3, p.current)
    }

    @Test fun allExistingExternalChoicesUseOnlyTheSavedEnd() {
        val context = RecordingContext()
        val p = plan().copy(endAtLastDelivery = true)
        val end = p.stops.last().place
        val prefs = NavigationPreferences(context)
        for (choice in NavigationChoice.entries.filter { it != NavigationChoice.BUILT_IN }) {
            val expected = externalNavigationIntent(end, choice)
            installHandler(context, expected)
            prefs.updateDefault(choice)
            assertTrue(prefs.launchEnd(context, p, prefs.default!!))
            val actual = context.attempts.last()
            assertEquals(expected.data, actual.data)
            assertEquals(choice.packageName, actual.`package`)
            assertNull(actual.extras)
        }
    }

    @Test fun unavailableDefaultOffersFallbackWithoutChangingPreferenceOrRoute() {
        val context = RecordingContext()
        val prefs = NavigationPreferences(context)
        val p = plan()
        val before = PlanJson.encode(p)
        prefs.updateDefault(NavigationChoice.GOOGLE_MAPS)
        assertFalse(prefs.launchEnd(context, p, prefs.default!!))
        assertTrue(context.attempts.isEmpty())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Google Maps"))
        assertTrue(prefs.launchEnd(context, p, NavigationChoice.BUILT_IN))
        assertEquals(NavigationChoice.GOOGLE_MAPS, prefs.default)
        assertEquals(before, PlanJson.encode(p))
    }

    @Test fun invalidEndDoesNotLaunchOrFallBackToStart() {
        val context = RecordingContext()
        val p = plan().copy(endAtLastDelivery = true, stops = emptyList())
        assertFalse(openEndNavigation(context, p, NavigationChoice.BUILT_IN))
        assertTrue(context.attempts.isEmpty())
    }

    private fun installHandler(context: Context, intent: Intent) {
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = intent.`package`; name = "$packageName.NavigationActivity"; exported = true }
        })
    }
    private class RecordingContext : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        val attempts = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) { attempts.add(Intent(intent)) }
    }
}
