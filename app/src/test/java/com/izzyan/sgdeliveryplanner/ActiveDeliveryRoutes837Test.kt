package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** Build #837: multiple independent unfinished routes survive Room restarts,
 * date/postal duplicates, route switches, delivery proofs and updated progress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ActiveDeliveryRoutes837Test {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private val databases = mutableListOf<PlannerDb>()
    private val now = "2026-11-12T12:00:00"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "active-routes-837-${UUID.randomUUID()}.db"
    }

    @After fun cleanUp() {
        databases.forEach { if (it.isOpen) it.close() }
        context.deleteDatabase(databaseName)
    }

    private fun database(): PlannerDb =
        Room.databaseBuilder(context, PlannerDb::class.java, databaseName)
            .build().also(databases::add)

    private fun plan(date: LocalDateTime, count: Int, customer: String): Plan {
        val places = List(count) { index ->
            Place("018956", 1.30 + index * 0.0001, 103.80 + index * 0.0001,
                "Block ${index + 1}", "Test", "Address ${index + 1}")
        }
        val plan = schedule(places, List(count + 1) { Leg(2.5, 180.0, 0.0) },
            date, 10, "Normal", emptyList())
        return plan.copy(stops = plan.stops.mapIndexed { index, stop ->
            val order = CustomerOrder(
                orderId = stop.orderId,
                postalCode = stop.place.postal,
                customerName = "$customer ${index + 1}",
                phoneNumber = "+6590000000",
                fullAddress = stop.place.address,
                parcelPrice = BigDecimal("25.00"),
                paymentStatus = if (index % 2 == 0) "COD" else "PAYNOW",
                originalMessage = "Customer $customer order ${index + 1}"
            )
            stop.copy(order = order)
        })
    }

    @Test fun threeDifferentDatesCanResumeOlderRouteWithoutLosingNewerRoutes() = runBlocking {
        val a = plan(LocalDateTime.of(2026, 11, 12, 8, 0), 25, "A")
        val b = plan(LocalDateTime.of(2026, 11, 13, 8, 0), 30, "B")
        val c = plan(LocalDateTime.of(2026, 11, 14, 8, 0), 20, "C")
        val partialA = a.copy(
            stops = a.stops.mapIndexed { index, stop ->
                if (index < 10) stop.copy(status = "DELIVERED",
                    completedAt = now, reviewedAt = now) else stop
            },
            current = 10
        )
        val db = database()
        listOf(partialA, b, c).forEach { route ->
            db.dao().save(SavedRoute(route.id, PlanJson.encode(route), route.created))
        }
        assertEquals(3, db.dao().allHistoryForBackup().size)
        assertEquals(SavedDeliveryStatus.IN_PROGRESS, savedDeliveryStatus(partialA))
        assertEquals(SavedDeliveryStatus.NOT_STARTED, savedDeliveryStatus(b))
        assertEquals(SavedDeliveryStatus.NOT_STARTED, savedDeliveryStatus(c))
        val restoredA = loadPersistedRouteForDelivery(db.dao(), a.id)
        assertEquals(25, restoredA.stops.size)
        assertEquals(10, restoredA.current)
        assertEquals(10, deliverySummary(restoredA).delivered)
        assertEquals(15, restoredA.stops.count { normalizedStatus(it.status) != "DELIVERED" })
        assertEquals(a.stops.map { it.orderId }, restoredA.stops.map { it.orderId })

        // Switching to B and returning to A must never alter one record via the other.
        val restoredB = loadPersistedRouteForDelivery(db.dao(), b.id)
        assertEquals(30, restoredB.stops.size)
        assertEquals(0, deliverySummary(restoredB).delivered)
        assertEquals(partialA, loadPersistedRouteForDelivery(db.dao(), a.id))
        assertEquals(c, loadPersistedRouteForDelivery(db.dao(), c.id))
        db.close()

        // Same Room file, new connection: proves restart persistence.
        val rebooted = database()
        assertEquals(partialA, loadPersistedRouteForDelivery(rebooted.dao(), a.id))
        assertEquals(b, loadPersistedRouteForDelivery(rebooted.dao(), b.id))
        assertEquals(c, loadPersistedRouteForDelivery(rebooted.dao(), c.id))
    }

    @Test fun routesOnSameDateAndPostalCodesStayIsolatedWithDifferentProofs() = runBlocking {
        val date = LocalDateTime.of(2026, 11, 12, 10, 0)
        val a = plan(date, 2, "A")
        val b = plan(date, 2, "B")
        assertNotEquals(a.id, b.id)
        assertEquals(a.deliveryDate, b.deliveryDate)
        assertEquals(a.stops.map { it.place.postal }, b.stops.map { it.place.postal })
        val editedA = updateOrderProof(a, a.stops[0].orderId,
            ProofKind.DELIVERY, "delivery_routeA.jpg", now)
        val editedB = updateOrderProof(b, b.stops[0].orderId,
            ProofKind.PAYMENT, "payment_routeB.jpg", now)
        val db = database()
        listOf(editedA, editedB).forEach {
            db.dao().save(SavedRoute(it.id, PlanJson.encode(it), it.created))
        }
        val loadedA = loadPersistedRouteForDelivery(db.dao(), a.id)
        val loadedB = loadPersistedRouteForDelivery(db.dao(), b.id)
        assertEquals("delivery_routeA.jpg", loadedA.stops[0].order?.proofFileId)
        assertNull(loadedA.stops[0].order?.paymentProofFileId)
        assertNull(loadedB.stops[0].order?.proofFileId)
        assertEquals("payment_routeB.jpg", loadedB.stops[0].order?.paymentProofFileId)
        assertEquals("A 1", loadedA.stops[0].order?.customerName)
        assertEquals("B 1", loadedB.stops[0].order?.customerName)
        assertEquals("COD", loadedA.stops[0].order?.paymentStatus)
        assertEquals("COD", loadedB.stops[0].order?.paymentStatus)
        db.close()
        val restored = database()
        assertEquals(editedA, loadPersistedRouteForDelivery(restored.dao(), a.id))
        assertEquals(editedB, loadPersistedRouteForDelivery(restored.dao(), b.id))
    }

    @Test fun selectingCorruptOrMissingRouteCannotModifyExistingSavedRecords() = runBlocking {
        val a = plan(LocalDateTime.of(2026, 11, 12, 8, 0), 2, "A")
        val b = plan(LocalDateTime.of(2026, 11, 13, 8, 0), 2, "B")
        val db = database()
        db.dao().save(SavedRoute(a.id, PlanJson.encode(a), a.created))
        // Row ID points to B but embedded Plan ID is A: fail closed.
        db.dao().save(SavedRoute(b.id, PlanJson.encode(a), b.created))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { loadPersistedRouteForDelivery(db.dao(), b.id) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { loadPersistedRouteForDelivery(db.dao(), "absent-route-id") }
        }
        assertEquals(a, loadPersistedRouteForDelivery(db.dao(), a.id))
        assertEquals(PlanJson.encode(a), db.dao().route(b.id)?.json)
    }

    @Test fun completedRouteRemainsViewOnlyWhileOnHoldCountsAsInProgress() = runBlocking {
        val a = plan(LocalDateTime.of(2026, 11, 12, 8, 0), 2, "A")
        val held = a.copy(stops = a.stops.toMutableList().apply {
            this[0] = this[0].copy(status = "ON_HOLD", reviewedAt = now,
                holdReason = "Customer not home")
        }, current = 1)
        assertEquals(SavedDeliveryStatus.IN_PROGRESS, savedDeliveryStatus(held))
        val reviewed = held.copy(reviewedFinishedAt = now)
        assertEquals(SavedDeliveryStatus.COMPLETED, savedDeliveryStatus(reviewed))
        val delivered = a.copy(stops = a.stops.map { it.copy(
            status = "DELIVERED", reviewedAt = now, completedAt = now
        ) }, current = a.stops.size)
        assertEquals(SavedDeliveryStatus.COMPLETED, savedDeliveryStatus(delivered))
        val db = database()
        db.dao().save(SavedRoute(reviewed.id, PlanJson.encode(reviewed), reviewed.created))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { loadPersistedRouteForDelivery(db.dao(), reviewed.id) }
        }
        assertEquals(reviewed, PlanJson.decode(requireNotNull(db.dao().route(reviewed.id)).json))
    }

    @Test fun savingProgressForRouteAReplacesOnlyItsRouteId() = runBlocking {
        val a = plan(LocalDateTime.of(2026, 11, 12, 8, 0), 2, "A")
        val b = plan(LocalDateTime.of(2026, 11, 13, 8, 0), 2, "B")
        val db = database()
        db.dao().save(SavedRoute(a.id, PlanJson.encode(a), a.created))
        db.dao().save(SavedRoute(b.id, PlanJson.encode(b), b.created))
        val updatedA = reviewStop(a, "DELIVERED", now)
        db.dao().save(SavedRoute(updatedA.id, PlanJson.encode(updatedA), updatedA.created))
        assertEquals(1, deliverySummary(loadPersistedRouteForDelivery(db.dao(), a.id)).delivered)
        assertEquals(0, deliverySummary(loadPersistedRouteForDelivery(db.dao(), b.id)).delivered)
        assertEquals(2, db.dao().allHistoryForBackup().size)
    }
}
