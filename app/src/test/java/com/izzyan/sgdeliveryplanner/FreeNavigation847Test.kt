package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FreeNavigation847Test {
    @Test fun malaysiaDestinationDoesNotRequireSingaporePostal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val place = StartLocation("CUSTOM", 1.656, 103.603, "Kulai", "Kulai, Johor", "81000").asPlace()
        val intent = builtInNavigationIntent(context, place, freeNavigation = true)
        assertEquals("81000", intent.getStringExtra("postal"))
        assertEquals("Kulai, Johor", intent.getStringExtra("address"))
        assertTrue(intent.getBooleanExtra("free_navigation", false))
        assertEquals(1.656, intent.getDoubleExtra("lat", 0.0), 0.0)
        assertEquals(103.603, intent.getDoubleExtra("lon", 0.0), 0.0)
        assertEquals(BuiltInNavigationActivity::class.java.name, intent.component!!.className)
        assertFalse(builtInNavigationIntent(context, place).hasExtra("free_navigation"))
    }

    @Test fun postalLabelsPreserveSingaporeAndIdentifyMalaysia() {
        assertEquals("Singapore 738203", locationPostalLabel("738203"))
        assertEquals("Malaysia 81000", locationPostalLabel("81000"))
    }
}
