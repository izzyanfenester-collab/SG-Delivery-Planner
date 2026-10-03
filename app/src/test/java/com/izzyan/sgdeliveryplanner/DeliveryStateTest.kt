package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class DeliveryStateTest {
    private val reviewTime = "2026-10-03T16:48"

    private fun plan(size: Int = 4): Plan = schedule(
        (0 until size).map { depot.copy(postal = (730100 + it).toString()) },
        (0..size).map { Leg(2.0, 300.0, 60.0) },
        LocalDateTime.of(2026, 10, 3, 10, 0),
        8,
        "Normal",
        listOf(listOf(depot.lon, depot.lat))
    )

    @Test fun summaryCountsAllStatusesAndRoundsSuccessRate() {
        val base = plan(35)
        val mixed = base.copy(stops = base.stops.mapIndexed { index, stop ->
            stop.copy(status = when { index < 30 -> "DELIVERED"; index < 33 -> "ON_HOLD"; else -> "SKIPPED" })
        }, reviewedFinishedAt = reviewTime)
        val summary = deliverySummary(mixed)
        assertEquals(35, summary.totalParcel)
        assertEquals(30, summary.delivered)
        assertEquals(3, summary.onHold)
        assertEquals(2, summary.skipped)
        assertEquals(0, summary.pending)
        assertEquals(85.7, summary.successRate, 0.0)
        assertEquals(24_480.0, summary.totalRouteSeconds, 0.0)
        assertEquals(72.0, summary.totalKm, 0.0)
        assertEquals(base.returned, summary.returned)
    }

    @Test fun successRateRoundsHalfUpToOneDecimal() {
        val base = plan(16)
        val oneDelivered = base.copy(stops = base.stops.mapIndexed { index, stop ->
            stop.copy(status = if (index == 0) "DELIVERED" else "PENDING")
        })
        assertEquals(6.3, deliverySummary(oneDelivered).successRate, 0.0)
    }

    @Test fun pendingStopsRemainCountedAfterNextAndCompleteReview() {
        var updated = reviewStop(plan(), "DELIVERED", "2026-10-03T10:20")
        updated = reviewStop(updated, "ON_HOLD", "2026-10-03T10:35", "Customer not home")
        updated = reviewStop(updated, "SKIP", "2026-10-03T10:50")
        updated = reviewStop(updated, "NEXT", reviewTime)
        val summary = deliverySummary(updated)
        assertEquals(1, summary.delivered)
        assertEquals(1, summary.onHold)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.pending)
        assertEquals(25.0, summary.successRate, 0.0)
        assertEquals(4, updated.current)
        assertEquals(reviewTime, updated.reviewedFinishedAt)
        assertNull(updated.actualCompletion)
        assertTrue(updated.stops.all { it.reviewedAt != null })
        assertEquals("PENDING", updated.stops.last().status)
    }

    @Test fun reviewFindsNextUnreviewedStopAndWrapsWithoutRepeatingPending() {
        val base = plan().copy(current = 2)
        val third = reviewStop(base, "NEXT", reviewTime)
        assertEquals(3, third.current)
        val fourth = reviewStop(third, "DELIVERED", reviewTime)
        assertEquals(0, fourth.current)
        val first = reviewStop(fourth, "NEXT", reviewTime)
        assertEquals(1, first.current)
        val finished = reviewStop(first, "SKIP", reviewTime)
        assertEquals(4, finished.current)
        assertEquals(reviewTime, finished.reviewedFinishedAt)
    }

    @Test fun holdReasonsAndOtherNotesPersistThroughJson() {
        val held = reviewStop(plan(), "ON_HOLD", reviewTime, "Other", "  Gate locked; call building security  ")
        val decoded = PlanJson.decode(PlanJson.encode(held))
        assertEquals(held, decoded)
        assertEquals("ON_HOLD", decoded.stops.first().status)
        assertEquals("Other", decoded.stops.first().holdReason)
        assertEquals("Gate locked; call building security", decoded.stops.first().holdNote)
        assertEquals(reviewTime, decoded.stops.first().reviewedAt)
        assertNull(decoded.stops.first().completedAt)
    }

    @Test fun holdReasonIsOptionalAndManualNoteOnlyAppliesToOther() {
        val optional = reviewStop(plan(), "ON_HOLD", reviewTime)
        assertNull(optional.stops.first().holdReason)
        assertNull(optional.stops.first().holdNote)
        val namedReason = reviewStop(plan(), "ON_HOLD", reviewTime, "Payment issue", "hidden note")
        assertEquals("Payment issue", namedReason.stops.first().holdReason)
        assertNull(namedReason.stops.first().holdNote)
        val longNote = reviewStop(plan(), "ON_HOLD", reviewTime, "Other", "a".repeat(300))
        assertEquals(160, longNote.stops.first().holdNote!!.length)
    }

    @Test fun changingHeldStopToDeliveredClearsHoldAndPreservesMoney() {
        val held = reviewStop(plan().copy(cashOnHand = "157.25", tax = "9.10", summarySavedAt = reviewTime), "ON_HOLD", reviewTime, "Other", "Gate locked")
        val delivered = reviewStop(held.copy(current = 0, reviewedFinishedAt = null), "DELIVERED", "2026-10-03T17:00")
        assertEquals("DELIVERED", delivered.stops.first().status)
        assertEquals("2026-10-03T17:00", delivered.stops.first().completedAt)
        assertNull(delivered.stops.first().holdReason)
        assertNull(delivered.stops.first().holdNote)
        assertEquals("157.25", delivered.cashOnHand)
        assertEquals("9.10", delivered.tax)
        assertNull(delivered.summarySavedAt)
    }

    @Test fun nextDoesNotUndoAnExistingStatusWhenRevisited() {
        val held = reviewStop(plan(), "ON_HOLD", reviewTime, "Other", "Payment pending")
        val next = reviewStop(held.copy(current = 0), "NEXT", "2026-10-03T17:00")
        assertEquals("ON_HOLD", next.stops.first().status)
        assertEquals("Other", next.stops.first().holdReason)
        assertEquals("Payment pending", next.stops.first().holdNote)
        assertEquals(reviewTime, next.stops.first().reviewedAt)
        val delivered = reviewStop(plan(), "DELIVERED", reviewTime)
        assertEquals(reviewTime, reviewStop(delivered.copy(current = 0), "NEXT", "2026-10-03T17:00").stops.first().completedAt)
    }

    @Test fun nextOnReviewedStopPreservesSavedSummaryFinishAndMoney() {
        val completed = reviewStop(reviewStop(plan(2), "DELIVERED", "2026-10-03T10:15"), "DELIVERED", "2026-10-03T10:30")
            .copy(cashOnHand = "125.25", tax = "7.10", summarySavedAt = "2026-10-03T10:35")
        val before = deliverySummary(completed)
        val after = reviewStop(completed.copy(current = 0), "NEXT", "2026-10-03T17:00")
        assertEquals(before, deliverySummary(after))
        assertEquals(completed.stops, after.stops)
        assertEquals(completed.reviewedFinishedAt, after.reviewedFinishedAt)
        assertEquals(completed.actualCompletion, after.actualCompletion)
        assertEquals(completed.summarySavedAt, after.summarySavedAt)
        assertEquals("125.25", after.cashOnHand)
        assertEquals("7.10", after.tax)
        assertEquals(2, after.current)
    }

    @Test fun nextOnReopenedUnreviewedHoldSetsNewReviewTimestamp() {
        val held = reviewStop(plan(), "ON_HOLD", reviewTime, "Other", "Gate locked")
        val reopened = held.copy(current = 0, stops = held.stops.mapIndexed { index, stop ->
            if (index == 0) stop.copy(reviewedAt = null) else stop
        })
        val reviewed = reviewStop(reopened, "NEXT", "2026-10-03T17:00")
        assertEquals("2026-10-03T17:00", reviewed.stops.first().reviewedAt)
        assertEquals("ON_HOLD", reviewed.stops.first().status)
        assertEquals("Gate locked", reviewed.stops.first().holdNote)
    }

    @Test fun nextOnReviewedStopWithMissingFinishUsesExistingReviewTime() {
        val completed = reviewStop(reviewStop(plan(2), "DELIVERED", "2026-10-03T10:15"), "SKIP", "2026-10-03T10:30")
        val advanced = reviewStop(completed.copy(current = 0, reviewedFinishedAt = null), "NEXT", "2026-10-03T17:00")
        assertEquals("2026-10-03T10:30", advanced.reviewedFinishedAt)
    }

    @Test fun allDeliveredSetsActualCompletionAndFinish() {
        val completed = reviewStop(reviewStop(plan(2), "DELIVERED", "2026-10-03T10:15"), "DELIVERED", "2026-10-03T10:30")
        assertEquals("2026-10-03T10:30", completed.actualCompletion)
        assertEquals("2026-10-03T10:30", deliverySummary(completed).finish)
        assertEquals(100.0, deliverySummary(completed).successRate, 0.0)
    }

    @Test fun unfinishedSummaryUsesPlannedFinalLeaveAndClampsEarlyFinish() {
        val base = plan()
        assertEquals(base.stops.last().leave, deliverySummary(base).finish)
        assertEquals(0.0, deliverySummary(base.copy(reviewedFinishedAt = "2026-10-03T09:00")).totalRouteSeconds, 0.0)
        assertEquals("2026-10-03T10:35", deliverySummary(base.copy(actualCompletion = "2026-10-03T10:35")).finish)
    }

    @Test fun emptySummaryHasNoDivisionByZero() {
        val empty = plan().copy(stops = emptyList())
        assertEquals(0, deliverySummary(empty).totalParcel)
        assertEquals(0.0, deliverySummary(empty).successRate, 0.0)
        assertEquals(empty.start, deliverySummary(empty).finish)
    }

    @Test fun currencyAcceptsSafeEditsAndFormatsToTwoDecimals() {
        listOf("", ".", ".5", "0", "0.", "5.1", "157.25", "999999999.99").forEach { assertTrue(it, validCurrencyInput(it)) }
        assertEquals("0.00", normalizedCurrency(""))
        assertEquals("0.50", normalizedCurrency(".5"))
        assertEquals("5.10", normalizedCurrency("5.1"))
        assertEquals("5.00", normalizedCurrency("5."))
        assertEquals("SGD 157.25", formatCurrency("157.25"))
        assertEquals("SGD 0.00", formatCurrency(""))
        assertNull(normalizedCurrency("."))
    }

    @Test fun currencyRejectsNegativeLettersGroupingWhitespaceAndExcessDigits() {
        listOf("-1", "+1", "1.234", "1000000000", "1,000", "SGD 1.00", "1e3", " 1", "1 ", "1..2", "１２", "NaN").forEach {
            assertFalse(it, validCurrencyInput(it))
            assertNull(it, normalizedCurrency(it))
        }
    }

    @Test fun savedSummaryMoneyAndTimestampsSurviveJsonRoundtrip() {
        val saved = plan().copy(cashOnHand = "432.10", tax = "7.25", summarySavedAt = reviewTime, reviewedFinishedAt = reviewTime)
        assertEquals(saved, PlanJson.decode(PlanJson.encode(saved)))
    }

    @Test fun legacyVersionOneJsonKeepsRouteAndNormalizesCompleted() {
        val base = plan(3)
        val legacy = JsonParser.parseString(PlanJson.encode(base)).asJsonObject
        listOf("cashOnHand", "tax", "summarySavedAt", "reviewedFinishedAt", "deliveryStateVersion").forEach(legacy::remove)
        legacy.getAsJsonArray("stops").forEach { element ->
            listOf("holdReason", "holdNote", "reviewedAt").forEach(element.asJsonObject::remove)
        }
        legacy.getAsJsonArray("stops")[0].asJsonObject.apply {
            addProperty("status", "COMPLETED")
            addProperty("completedAt", "2026-10-03T10:20")
        }
        legacy.addProperty("current", 1)
        val restored = PlanJson.decode(legacy.toString())
        assertEquals(base.id, restored.id)
        assertEquals(base.geometry, restored.geometry)
        assertEquals(base.returnLeg, restored.returnLeg)
        assertEquals(base.stops.map { it.place.postal }, restored.stops.map { it.place.postal })
        assertEquals(1, restored.current)
        assertEquals("DELIVERED", restored.stops[0].status)
        assertEquals("2026-10-03T10:20", restored.stops[0].reviewedAt)
        assertNull(restored.stops[1].reviewedAt)
        assertNull(restored.reviewedFinishedAt)
        assertEquals("0.00", restored.cashOnHand)
        assertEquals("0.00", restored.tax)
    }

    @Test fun explicitNullMoneyAndMissingStatusAreSafe() {
        val old = JsonParser.parseString(PlanJson.encode(plan())).asJsonObject
        old.add("cashOnHand", com.google.gson.JsonNull.INSTANCE)
        old.add("tax", com.google.gson.JsonNull.INSTANCE)
        old.getAsJsonArray("stops")[0].asJsonObject.remove("status")
        val restored = PlanJson.decode(old.toString())
        assertEquals("0.00", restored.cashOnHand)
        assertEquals("0.00", restored.tax)
        assertEquals("PENDING", restored.stops.first().status)
    }

    @Test fun fullyReviewedLegacyRouteUsesLatestReviewForFinish() {
        val old = plan(2).copy(current = 2, stops = plan(2).stops.map { it.copy(status = "SKIPPED", reviewedAt = reviewTime) })
        val legacy = JsonParser.parseString(PlanJson.encode(old)).asJsonObject.apply { remove("deliveryStateVersion") }
        val restored = PlanJson.decode(legacy.toString())
        assertEquals(reviewTime, restored.reviewedFinishedAt)
    }

    @Test fun versionTwoKeepsReopenedNonpendingStopUnreviewedAfterRestart() {
        listOf("DELIVERED", "ON_HOLD").forEach { status ->
            val completed = reviewStop(reviewStop(plan(2), "DELIVERED", "2026-10-03T10:15"), "ON_HOLD", "2026-10-03T10:30", "Other", "Gate closed")
            val reopened = completed.copy(
                current = 1,
                reviewedFinishedAt = null,
                stops = completed.stops.mapIndexed { index, stop ->
                    if (index == 1) stop.copy(status = status, reviewedAt = null) else stop
                }
            )
            val json = PlanJson.encode(reopened)
            assertEquals(2, JsonParser.parseString(json).asJsonObject.get("deliveryStateVersion").asInt)
            val restored = PlanJson.decode(json)
            assertEquals(reopened, restored)
            assertNull(restored.stops[1].reviewedAt)
            assertNull(restored.reviewedFinishedAt)
            assertEquals(1, restored.current)
        }
    }

    @Test fun versionTwoDoesNotInferAnIntentionallyAbsentReviewFinish() {
        val completed = reviewStop(reviewStop(plan(2), "DELIVERED", "2026-10-03T10:15"), "DELIVERED", "2026-10-03T10:30")
        val finishCleared = completed.copy(reviewedFinishedAt = null)
        assertNull(PlanJson.decode(PlanJson.encode(finishCleared)).reviewedFinishedAt)
    }

    @Test fun outOfBoundsCurrentIsSafeAndUnknownActionRejected() {
        val base = plan()
        assertEquals(base.copy(current = -1), reviewStop(base.copy(current = -1), "DELIVERED", reviewTime))
        assertEquals(base.copy(current = 9), reviewStop(base.copy(current = 9), "DELIVERED", reviewTime))
        try {
            reviewStop(base, "INVALID", reviewTime)
            fail("Unknown actions must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
