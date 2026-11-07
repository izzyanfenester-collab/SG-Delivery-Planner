package com.izzyan.sgdeliveryplanner

import android.location.Location
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RunnerTrafficObservationTest {
    private fun moving(): Location = Location("fused").apply {
        latitude = 1.3000
        longitude = 103.8000
        speed = 10f
        accuracy = 5f
        bearing = 90f
        time = System.currentTimeMillis()
    }

    @Test fun validMovingRunnerMayContributeAnonymously() {
        assertNotNull(RunnerTrafficObservation.fromLocation(moving(), 1000.0, true))
    }

    @Test fun parkedSlowOrInDeliveryCannotContribute() {
        assertNull(RunnerTrafficObservation.fromLocation(moving(), 1000.0, false))
        assertNull(RunnerTrafficObservation.fromLocation(moving(), 20.0, true))
        assertNull(RunnerTrafficObservation.fromLocation(moving().apply { speed = 0f }, 1000.0, true))
        assertNull(RunnerTrafficObservation.fromLocation(moving().apply { accuracy = 90f }, 1000.0, true))
    }
}
