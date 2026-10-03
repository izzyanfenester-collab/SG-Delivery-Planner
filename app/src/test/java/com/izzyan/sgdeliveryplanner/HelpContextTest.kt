package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class HelpContextTest {
    private fun plan(statuses: List<String>, reviewedIndices: Set<Int> = emptySet()): Plan {
        val places = statuses.indices.map { depot.copy(postal = "73012$it", address = "Delivery address $it") }
        val legs = List(places.size + 1) { Leg(2.0, 240.0, 60.0) }
        val planned = schedule(places, legs, LocalDateTime.of(2026, 10, 3, 9, 0), 8, "Normal", emptyList())
        return planned.copy(stops = planned.stops.mapIndexed { index, stop ->
            stop.copy(status = statuses[index], reviewedAt = if (index in reviewedIndices) "2026-10-03T10:00" else null)
        })
    }

    @Test fun noRouteRetainsErrorAndGivesUsefulEmptyContext() {
        val context = buildHelpContext(null, "Postal code could not be resolved")
        assertTrue(context.contains("Current stop: No route planned yet."))
        assertTrue(context.contains("Total route progress: 0 / 0 deliveries; 0 / 0 reviewed."))
        assertTrue(context.contains("Postal code could not be resolved"))
        assertFalse(context.contains("NaN"))
    }

    @Test fun contextUsesCurrentAndNextStopAndAllOutcomeCounts() {
        val route = plan(listOf("DELIVERED", "ON_HOLD", "SKIPPED", "PENDING", "COMPLETED"), setOf(0, 1, 2, 4)).copy(current = 1)
        val context = buildHelpContext(route, "")
        assertTrue(context.contains("Current stop: 2 / 5, postal code 730121"))
        assertTrue(context.contains("Next stop: 4 / 5, postal code 730123"))
        assertTrue(context.contains("Current status: On hold."))
        assertTrue(context.contains("Delivered: 2; On hold: 1; Skipped: 1; Pending: 1."))
        assertTrue(context.contains("2 / 5 delivered (40%)"))
        assertTrue(context.contains("4 / 5 reviewed."))
        assertTrue(context.contains("SGT"))
        assertTrue(context.contains("App message or route error: None."))
    }

    @Test fun endOfRouteDoesNotClaimPendingStopsWereDelivered() {
        val route = plan(listOf("DELIVERED", "PENDING"), setOf(0, 1)).copy(current = 2)
        val context = buildHelpContext(route, "Check the unresolved delivery")
        assertTrue(context.contains("Current stop: None; you are at the end"))
        assertTrue(context.contains("Next stop: Return to Woodlands Checkpoint, postal code 738203."))
        assertTrue(context.contains("1 / 2 delivered (50%)"))
        assertTrue(context.contains("Pending: 1."))
        assertTrue(context.contains("2 / 2 reviewed."))
        assertFalse(context.contains("All deliveries complete"))
    }

    @Test fun revisitingSkipsReviewedAdjacentStopsAndIncludesHoldDetails() {
        val route = plan(listOf("ON_HOLD", "PENDING", "PENDING", "DELIVERED"), setOf(0, 1, 3))
        val context = buildHelpContext(route.copy(stops = route.stops.mapIndexed { index, stop ->
            if (index == 0) stop.copy(holdReason = "Other", holdNote = "Gate locked; call recipient") else stop
        }), "")
        assertTrue(context.contains("Next stop: 3 / 4, postal code 730122"))
        assertTrue(context.contains("3 / 4 reviewed."))
        assertTrue(context.contains("Pending: 2."))
        assertTrue(context.contains("Hold reason: Other."))
        assertTrue(context.contains("Hold note: Gate locked; call recipient"))
    }

    @Test fun nextStopWrapsToFirstUnreviewedStop() {
        val route = plan(listOf("DELIVERED", "PENDING", "SKIPPED", "DELIVERED"), setOf(0, 2, 3)).copy(current = 3)
        val context = buildHelpContext(route, "")
        assertTrue(context.contains("Next stop: 2 / 4, postal code 730121"))
        assertFalse(context.contains("Next stop: Return to Woodlands Checkpoint"))
    }

    @Test fun lastStopReturnsToDepotAndOutOfRangeCursorIsSafe() {
        val route = plan(listOf("PENDING"))
        assertTrue(buildHelpContext(route, "").contains("Next stop: Return to Woodlands Checkpoint"))
        assertTrue(buildHelpContext(route.copy(current = -7), "").contains("Current stop: 1 / 1"))
        assertTrue(buildHelpContext(route.copy(current = 99), "").contains("Current stop: None"))
        val empty = route.copy(stops = emptyList(), current = 0)
        assertTrue(buildHelpContext(empty, "").contains("0 / 0 delivered (0%)"))
    }
}
