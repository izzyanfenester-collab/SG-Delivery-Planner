package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class RunnerWorkflowTest {
    private val a = Place("730171", 1.444, 103.786, "21", "Woodlands", "Woodlands")
    private val b = Place("520220", 1.353, 103.944, "220", "Tampines", "Tampines")

    private fun plan(): Plan = schedule(
        listOf(a, a, b),
        (0..3).map { Leg(1.0, 130.0, 30.0) },
        LocalDateTime.of(2026, 11, 8, 11, 0),
        10, "Normal", emptyList()
    ).let { p ->
        p.copy(stops = p.stops.mapIndexed { i, stop ->
            stop.copy(orderId = "parcel-$i",
                order = CustomerOrder(orderId = "parcel-$i", postalCode = stop.place.postal,
                    customerName = "Customer $i", paymentStatus = if (i == 0) "COD" else "PAYNOW",
                    parcelPrice = BigDecimal("55.00")))
        })
    }

    @Test fun samePostalIsOneVisitButNeverOneOrder() {
        val p = plan()
        val group = requireNotNull(locationParcelProgress(p, 0))
        assertEquals(listOf(0, 1), group.indices)
        assertEquals(2, group.total)
        assertEquals(0, group.delivered)
        val reviewed = reviewStop(p, "DELIVERED", "2026-11-08T11:40:00")
        val updated = requireNotNull(locationParcelProgress(reviewed, 1))
        assertEquals(1, updated.delivered)
        assertEquals(1, updated.unresolved)
        assertEquals("PENDING", reviewed.stops[1].status)
        assertEquals("parcel-1", reviewed.stops[1].orderId)
    }

    @Test fun missingProofRequiresReviewNotInventedEvidence() {
        val p = plan()
        val r = completionReadiness(p.stops[0])
        assertTrue(r.isPayOnDelivery)
        assertTrue(r.shouldReviewMissingEvidence)
        assertFalse(r.hasDeliveryProof)
        assertFalse(r.hasPaymentProof)
        assertTrue(paymentReviewLabel(p.stops[0]).contains("confirm"))
        val withProof = p.stops[0].copy(order = p.stops[0].order!!.copy(
            proofFileId = "delivery.jpg", paymentProofFileId = "payment.jpg"))
        assertFalse(completionReadiness(withProof).shouldReviewMissingEvidence)
    }

    @Test fun newlyStructuredExceptionsSurviveJsonAndClearOnDelivery() {
        val p = plan()
        val held = reviewStop(p, "ON_HOLD", "2026-11-08T12:00:00",
            "Wrong address | Damaged parcel")
        val restored = PlanJson.decode(PlanJson.encode(held))
        assertEquals("Wrong address | Damaged parcel", restored.stops[0].holdReason)
        val delivered = reviewStop(restored.copy(current = 0), "DELIVERED",
            "2026-11-08T12:14:00")
        assertEquals("DELIVERED", delivered.stops[0].status)
        assertNull(delivered.stops[0].holdReason)
    }

    @Test fun etaTemplatesNeverInventPastEtaOrWrongParcel() {
        val stop = plan().stops[0].copy(
            etaArrival = "2026-11-08T13:30:00",
            order = plan().stops[0].order!!.copy(phoneNumber = "+6591234567"))
        val msg = requireNotNull(smartEtaMessage(stop, RunnerEtaMessage.UPCOMING,
            LocalDateTime.of(2026, 11, 8, 13, 0)))
        assertTrue(msg.contains("1:30 PM"))
        assertTrue(msg.contains("730171"))
        assertTrue(msg.contains("S$55.00"))
        val past = requireNotNull(smartEtaMessage(stop, RunnerEtaMessage.UPCOMING,
            LocalDateTime.of(2026, 11, 8, 14, 0)))
        assertFalse(past.contains("1:30 PM"))
        assertTrue(past.contains("confirm the arrival time"))
        assertNull(smartEtaMessage(stop.copy(order = stop.order!!.copy(phoneNumber = null)),
            RunnerEtaMessage.UPCOMING))
    }
}
