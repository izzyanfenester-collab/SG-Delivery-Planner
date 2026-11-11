package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** The chop is display-only, sourced from each saved Plan status.
 * The original compact artwork must be bundled as PNG, not generated at runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HistoryStatusChops838Test {
    private fun plan(): Plan = schedule(
        listOf(
            Place("018956", 1.286, 103.852, "Marina", "Central", "Singapore"),
            Place("018956", 1.286, 103.852, "Marina", "Central", "Singapore")
        ),
        List(3) { Leg(2.0, 180.0, 0.0) },
        LocalDateTime.of(2026, 11, 12, 8, 0), 10, "Normal", emptyList()
    )

    @Test fun chopResourcesAreDistinctAndMappedToCorrectStatus() {
        val assets = SavedDeliveryStatus.values().map(::historyStatusChopDrawable)
        assertEquals(3, assets.distinct().size)
        assertEquals(R.drawable.history_status_completed,
            historyStatusChopDrawable(SavedDeliveryStatus.COMPLETED))
        assertEquals(R.drawable.history_status_not_started,
            historyStatusChopDrawable(SavedDeliveryStatus.NOT_STARTED))
        assertEquals(R.drawable.history_status_in_progress,
            historyStatusChopDrawable(SavedDeliveryStatus.IN_PROGRESS))
    }

    @Test fun threeUserSuppliedTransparentPngAssetsArePackagedAt256Pixels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (status in SavedDeliveryStatus.values()) {
            val bytes = context.resources.openRawResource(historyStatusChopDrawable(status))
                .use { it.readBytes() }
            assertTrue(bytes.size > 4000)
            assertArrayEquals(
                byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10),
                bytes.copyOfRange(0, 8)
            )
            val width = java.nio.ByteBuffer.wrap(bytes, 16, 4).int
            val height = java.nio.ByteBuffer.wrap(bytes, 20, 4).int
            assertEquals(256, width)
            assertEquals(256, height)
            // Indexed transparent PNG: the original transparent corners remain.
            assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("tRNS"))
        }
    }

    @Test fun allStatusesUseExactly35DegreesClockwise() {
        assertEquals(35f, HISTORY_CHOP_CLOCKWISE_DEGREES, 0f)
        assertTrue(HISTORY_CHOP_CLOCKWISE_DEGREES > 0f)
        assertNotEquals(historyStatusAccent(SavedDeliveryStatus.COMPLETED),
            historyStatusAccent(SavedDeliveryStatus.NOT_STARTED))
        assertNotEquals(historyStatusAccent(SavedDeliveryStatus.IN_PROGRESS),
            historyStatusAccent(SavedDeliveryStatus.NOT_STARTED))
    }

    @Test fun chopTracksOnlyPersistedRouteActivityNotDate() {
        val original = plan()
        assertEquals(SavedDeliveryStatus.NOT_STARTED, savedDeliveryStatus(original))
        val savedHold = reviewStop(original, "ON_HOLD", "2026-11-12T09:10:00",
            "Customer not home", null)
        assertEquals(SavedDeliveryStatus.IN_PROGRESS, savedDeliveryStatus(savedHold))
        val again = PlanJson.decode(PlanJson.encode(savedHold))
        assertEquals(SavedDeliveryStatus.IN_PROGRESS, savedDeliveryStatus(again))
        // Finishing the existing workflow, not merely reaching the delivery date,
        // is what marks COMPLETED.
        assertEquals(SavedDeliveryStatus.COMPLETED,
            savedDeliveryStatus(again.copy(reviewedFinishedAt = "2026-11-12T12:00:00")))
        assertEquals(SavedDeliveryStatus.NOT_STARTED,
            savedDeliveryStatus(original.copy(start = "2020-01-01T08:00:00")))
    }

    @Test fun twoRoutesWithIdenticalDateAndPostalKeepIndependentChops() {
        val first = plan()
        val second = plan()
        assertNotEquals(first.id, second.id)
        assertEquals(first.deliveryDate, second.deliveryDate)
        assertEquals(first.stops.map { it.place.postal }, second.stops.map { it.place.postal })
        val active = reviewStop(first, "DELIVERED", "2026-11-12T09:10:00")
        val restoredActive = PlanJson.decode(PlanJson.encode(active))
        val restoredNew = PlanJson.decode(PlanJson.encode(second))
        assertEquals(SavedDeliveryStatus.IN_PROGRESS, savedDeliveryStatus(restoredActive))
        assertEquals(SavedDeliveryStatus.NOT_STARTED, savedDeliveryStatus(restoredNew))
        assertEquals(1, deliverySummary(restoredActive).delivered)
        assertEquals(0, deliverySummary(restoredNew).delivered)
    }
}
