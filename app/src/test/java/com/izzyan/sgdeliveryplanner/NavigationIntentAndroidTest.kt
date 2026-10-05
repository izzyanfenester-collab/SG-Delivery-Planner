package com.izzyan.sgdeliveryplanner

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
class NavigationIntentAndroidTest {
    @Test fun deliveryNavigationIsExplicitlyInternalAndContainsOnlyTheCurrentStopSnapshot() {
        val plan = schedule((1..3).map { Place("00500$it", 1.35 + it / 1000.0, 103.8, "$it", "Area", "$it Road") },
            List(4) { Leg(1.0, 100.0, 10.0) }, LocalDateTime.of(2026, 10, 5, 10, 0), 8, "Normal Traffic", emptyList()).copy(current = 1)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = requireNotNull(builtInNavigationIntent(context, plan))
        assertEquals(BuiltInNavigationActivity::class.java.name, intent.component!!.className)
        assertNull(intent.action)
        assertNull(intent.data)
        assertNull(intent.clipData)
        assertEquals(setOf("postal", "address", "lat", "lon"), intent.extras!!.keySet())
        assertEquals(plan.stops[1].place.postal, intent.getStringExtra("postal"))
        assertEquals(plan.stops[1].place.lat, intent.getDoubleExtra("lat", 0.0), 0.0)
        assertEquals(plan.stops[1].place.lon, intent.getDoubleExtra("lon", 0.0), 0.0)
        assertEquals(plan.stops[1].place.address, intent.getStringExtra("address"))
        assertNull(builtInNavigationIntent(context, plan.copy(current = 3)))
    }
}
