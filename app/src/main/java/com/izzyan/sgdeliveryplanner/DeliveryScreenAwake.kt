package com.izzyan.sgdeliveryplanner

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Scoped to a visible Compose screen and the activity's foreground lifecycle. No wake lock. */
internal class DeliveryScreenAwake(
    private val view: View,
    private val lifecycle: Lifecycle,
    private val screen: String
) : AutoCloseable {
    private val observer = LifecycleEventObserver { _, _ -> update() }

    init {
        lifecycle.addObserver(observer)
        update()
    }

    private fun update() {
        view.keepScreenOn = screen == "Delivery" && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    override fun close() {
        lifecycle.removeObserver(observer)
        view.keepScreenOn = false
    }
}
