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
class NavigationChooserTest {
    private fun plan() = schedule((1..3).map { Place("00500$it", 1.35 + it / 1000.0, 103.8, "$it", "Area", "$it Road") },
        List(4) { Leg(1.0, 100.0, 10.0) }, LocalDateTime.of(2026, 10, 5, 10, 0), 8, "Normal Traffic", emptyList()).copy(current = 1)

    @Test fun builtInChoiceUsesExistingActivityAndOnlyCurrentStop() {
        val context = RecordingContext()
        val plan = plan()
        assertTrue(openDeliveryNavigation(context, plan, NavigationChoice.BUILT_IN))
        val intent = context.attempts.single()
        assertEquals(BuiltInNavigationActivity::class.java.name, intent.component!!.className)
        assertEquals(plan.stops[1].place.postal, intent.getStringExtra("postal"))
        assertEquals(plan.stops[1].place.lat, intent.getDoubleExtra("lat", 0.0), 0.0)
        assertNull(intent.data)
    }

    @Test fun googleMapsUsesOnlyCurrentStopCoordinatesAndDrivingMode() {
        val context = RecordingContext()
        val plan = plan()
        val expected = externalNavigationIntent(plan.stops[1].place, NavigationChoice.GOOGLE_MAPS)
        installHandler(context, expected)
        assertTrue(openDeliveryNavigation(context, plan, NavigationChoice.GOOGLE_MAPS))
        val intent = context.attempts.single()
        assertEquals("com.google.android.apps.maps", intent.`package`)
        assertEquals("google.navigation:q=1.352%2C103.8&mode=d", intent.data.toString())
        assertNoPayload(intent)
    }

    @Test fun wazeUsesOnlyCurrentStopCoordinatesAndStartsNavigation() {
        val context = RecordingContext()
        val plan = plan()
        installHandler(context, externalNavigationIntent(plan.stops[1].place, NavigationChoice.WAZE))
        assertTrue(openDeliveryNavigation(context, plan, NavigationChoice.WAZE))
        val intent = context.attempts.single()
        assertEquals("com.waze", intent.`package`)
        assertEquals("waze", intent.data!!.scheme)
        assertEquals("1.352,103.8", intent.data!!.getQueryParameter("ll"))
        assertEquals("yes", intent.data!!.getQueryParameter("navigate"))
        assertEquals(setOf("ll", "navigate"), intent.data!!.queryParameterNames)
        assertNoPayload(intent)
    }

    @Test fun missingCoordinatesFallBackToEncodedAddressOrPostalCode() {
        val stop = plan().stops[1].place.copy(lat = Double.NaN, lon = Double.NaN, address = "A & B Road #01-02")
        val maps = externalNavigationIntent(stop, NavigationChoice.GOOGLE_MAPS).data!!
        assertEquals("q=A%20%26%20B%20Road%20%2301-02%20Singapore%20005002&mode=d", maps.encodedSchemeSpecificPart)
        val waze = externalNavigationIntent(stop.copy(address = ""), NavigationChoice.WAZE).data!!
        assertEquals("Singapore 005002", waze.getQueryParameter("q"))
        assertNull(waze.getQueryParameter("ll"))
        assertEquals("yes", waze.getQueryParameter("navigate"))
    }

    @Test fun missingExternalAppsShowMessageAndNeverOpenAnotherAppOrBrowser() {
        NavigationChoice.entries.filter { it != NavigationChoice.BUILT_IN }.forEach { choice ->
            val context = RecordingContext()
            assertFalse(openDeliveryNavigation(context, plan(), choice))
            assertTrue(context.attempts.isEmpty())
            assertEquals("${choice.label} is not installed.", ShadowToast.getTextOfLatestToast())
        }
    }

    @Test fun launchRaceAndSecurityDenialAreHandledWithoutFallback() {
        listOf(ActivityNotFoundException("Uninstalled"), SecurityException("Denied")).forEach { failure ->
            val context = RecordingContext(failure)
            installHandler(context, externalNavigationIntent(plan().stops[1].place, NavigationChoice.WAZE))
            assertFalse(openDeliveryNavigation(context, plan(), NavigationChoice.WAZE))
            assertEquals(1, context.attempts.size)
        }
    }

    @Test fun handoffPreservesCompleteDeliverySessionAndClipboard() {
        val plan = plan()
        val before = plan.copy()
        val context = RecordingContext()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Existing", "Private text"))
        NavigationChoice.entries.forEach { choice ->
            if (choice != NavigationChoice.BUILT_IN) installHandler(context, externalNavigationIntent(plan.stops[1].place, choice))
            assertTrue(openDeliveryNavigation(context, plan, choice))
            assertEquals(before, plan)
            assertEquals(1, plan.current)
            assertEquals("Private text", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        assertFalse(openDeliveryNavigation(context, plan.copy(current = 3), NavigationChoice.GOOGLE_MAPS))
        assertEquals(3, context.attempts.size)
    }

    private fun assertNoPayload(intent: Intent) {
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertNull(intent.extras); assertNull(intent.clipData); assertNull(intent.type)
    }
    private fun installHandler(context: Context, intent: Intent) {
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = intent.`package`; name = "$packageName.NavigationActivity"; exported = true }
        })
    }
    private class RecordingContext(private val failure: RuntimeException? = null) : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        val attempts = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) { attempts.add(Intent(intent)); failure?.let { throw it } }
    }
}
