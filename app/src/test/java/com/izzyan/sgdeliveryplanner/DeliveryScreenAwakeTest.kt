package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeliveryScreenAwakeTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Test fun deliveryOnlyStaysAwakeWhileResumedAndClearsOnExit() {
        val view = View(ApplicationProvider.getApplicationContext<Context>())
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        val scope = DeliveryScreenAwake(view, owner.lifecycle, "Delivery")
        assertFalse(view.keepScreenOn)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertTrue(view.keepScreenOn)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertFalse("Minimized/paused activity must not keep the display awake", view.keepScreenOn)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertTrue(view.keepScreenOn)
        scope.close()
        assertFalse(view.keepScreenOn)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertFalse("Leaving Delivery must remove its observer", view.keepScreenOn)
    }

    @Test fun otherScreensUseNormalTimeoutEvenInTheForeground() {
        val view = View(ApplicationProvider.getApplicationContext<Context>())
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        listOf("Home", "Route", "History", "Settings", "Map", "Summary", "LocationPicker").forEach { screen ->
            DeliveryScreenAwake(view, owner.lifecycle, screen).use { assertFalse(screen, view.keepScreenOn) }
        }
    }
}
