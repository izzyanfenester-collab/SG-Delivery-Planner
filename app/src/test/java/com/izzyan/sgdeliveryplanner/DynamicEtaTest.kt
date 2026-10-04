package com.izzyan.sgdeliveryplanner

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class DynamicEtaTest {
    private fun plan() = schedule(
        List(3) { depot.copy(postal = "73010$it") },
        List(4) { Leg(2.0, 300.1, 59.1) },
        LocalDateTime.of(2026, 10, 3, 13, 16), 8, "Normal", emptyList())

    @Test fun lateCompletionCascadesTravelBufferAndEightMinuteServiceWithoutReordering() {
        val original = plan()
        val updated = reviewStop(original, "DELIVERED", "2026-10-03T13:47:23")
        assertEquals("2026-10-03T13:47:23", updated.stops[0].completedAt)
        assertEquals("2026-10-03T13:53:23", updated.stops[1].etaArrival)
        assertEquals("2026-10-03T14:01:23", updated.stops[1].etaLeave)
        assertEquals("2026-10-03T14:07:23", updated.stops[2].etaArrival)
        assertEquals("2026-10-03T14:15:23", updated.stops[2].etaLeave)
        assertEquals("2026-10-03T14:21:23", updated.etaReturned)
        assertEquals(original.stops.map { it.place }, updated.stops.map { it.place })
        assertEquals(original.stops.map { it.arrival to it.leave }, updated.stops.map { it.arrival to it.leave })
        assertEquals(original.geometry, updated.geometry)
        assertEquals(updated, PlanJson.decode(PlanJson.encode(updated)))
    }

    @Test fun earlyCompletionShiftsEarlierAndCrossMidnightUsesFullDate() {
        val early = reviewStop(plan(), "DELIVERED", "2026-10-03T13:20:00")
        assertEquals("2026-10-03T13:26", LocalDateTime.parse(early.stops[1].etaArrival).toString())
        val midnight = reviewStop(plan(), "DELIVERED", "2026-10-03T23:57:59")
        assertEquals("2026-10-04T00:03:59", midnight.stops[1].etaArrival)
    }

    @Test fun latestActualCompletionAnchorsRemainingStopsAndRecordedTimesAreImmutable() {
        val first = reviewStop(plan(), "DELIVERED", "2026-10-03T13:47:00")
        val second = reviewStop(first, "DELIVERED", "2026-10-03T14:10:37")
        assertEquals("2026-10-03T14:16:37", second.stops[2].etaArrival)
        assertEquals(first.stops[0].completedAt, second.stops[0].completedAt)
        val reopened = second.copy(current = 0)
        assertEquals(reopened, reviewStop(reopened, "DELIVERED", "2026-10-03T17:00"))
        assertEquals(reopened, reviewStop(reopened, "SKIP", "2026-10-03T17:00"))
        val backwardsClock = reviewStop(first, "DELIVERED", "2026-10-03T13:40:00")
        assertEquals("2026-10-03T13:53", LocalDateTime.parse(backwardsClock.stops[2].etaArrival).toString())
    }

    @Test fun skippedReviewedStopsDoNotDelayTheNextPendingEta() {
        val skipped = reviewStop(plan(), "SKIP", "2026-10-03T13:30")
        val updated = reviewStop(skipped, "DELIVERED", "2026-10-03T13:47:23")
        assertNull(updated.stops[0].etaArrival)
        assertEquals("2026-10-03T13:53:23", updated.stops[2].etaArrival)
        assertEquals("2026-10-03T14:01:23", updated.stops[2].etaLeave)
    }

    @Test fun holdSkipAndNextDoNotInventActualDeliveryTimes() {
        listOf("ON_HOLD", "SKIP", "NEXT").forEach {
            val updated = reviewStop(plan(), it, "2026-10-03T13:47")
            assertNull(updated.stops[0].completedAt)
            assertTrue(updated.stops.all { stop -> stop.etaArrival == null })
        }
        assertFalse(isRouteCompleted(plan()))
        var completed = plan()
        repeat(3) { completed = reviewStop(completed, "DELIVERED", "2026-10-03T14:00") }
        assertTrue(isRouteCompleted(completed))
    }
}
