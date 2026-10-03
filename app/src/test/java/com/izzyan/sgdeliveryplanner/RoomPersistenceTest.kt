package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the real Room file and generated DAO, rather than an in-memory JSON round trip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RoomPersistenceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private val databases = mutableListOf<PlannerDb>()

    @Before
    fun prepareDatabase() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "delivery-persistence-${UUID.randomUUID()}.db"
    }

    @After
    fun removeDatabase() {
        databases.forEach { it.close() }
        context.deleteDatabase(databaseName)
    }

    private fun openDatabase(): PlannerDb =
        Room.databaseBuilder(context, PlannerDb::class.java, databaseName).build().also(databases::add)

    @Test
    fun closingAndReopeningRoomRetainsHoldDetailsAndManualSummaryAmounts() = runBlocking {
        val base = samplePlan()
        val reviewed = "2026-10-03T11:00:00"
        val plan = base.copy(
            stops = listOf(
                base.stops[0].copy(status = "DELIVERED", completedAt = reviewed, reviewedAt = reviewed),
                base.stops[1].copy(
                    status = "ON_HOLD", holdReason = "Other", holdNote = "Security asked for an afternoon retry",
                    reviewedAt = reviewed
                ),
                base.stops[2].copy(status = "SKIPPED", reviewedAt = reviewed),
                base.stops[3].copy(status = "PENDING", reviewedAt = reviewed)
            ),
            current = base.stops.size,
            reviewedFinishedAt = reviewed,
            cashOnHand = "245.75",
            tax = "12.30",
            summarySavedAt = "2026-10-03T11:01:00"
        )
        val first = openDatabase()
        first.dao().save(SavedRoute(plan.id, PlanJson.encode(plan), plan.created))
        first.close()

        val reopened = openDatabase()
        val row = reopened.dao().route(plan.id)
        assertNotNull(row)
        val restored = PlanJson.decode(requireNotNull(row).json)
        assertEquals(plan, restored)
        assertEquals("Other", restored.stops[1].holdReason)
        assertEquals("Security asked for an afternoon retry", restored.stops[1].holdNote)
        assertEquals("245.75", restored.cashOnHand)
        assertEquals("12.30", restored.tax)
        assertEquals("2026-10-03T11:01:00", restored.summarySavedAt)
        val summary = deliverySummary(restored)
        assertEquals(4, summary.totalParcel)
        assertEquals(1, summary.delivered)
        assertEquals(1, summary.onHold)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.pending)
        assertEquals(25.0, summary.successRate, 0.0)
        assertEquals(3600.0, summary.totalRouteSeconds, 0.0)
    }

    @Test
    fun legacyVersionOneRowsSurviveRestartAndReceiveSafePayloadDefaults() = runBlocking {
        val base = samplePlan().copy(current = 1)
        val legacy = JsonParser.parseString(PlanJson.encode(base)).asJsonObject.apply {
            remove("deliveryStateVersion")
            remove("cashOnHand")
            add("tax", JsonNull.INSTANCE)
            remove("summarySavedAt")
            remove("reviewedFinishedAt")
            getAsJsonArray("stops").forEachIndexed { index, element ->
                element.asJsonObject.apply {
                    remove("holdReason")
                    remove("holdNote")
                    remove("reviewedAt")
                    if (index == 0) {
                        addProperty("status", "COMPLETED")
                        addProperty("completedAt", "2026-10-03T10:15:00")
                    }
                }
            }
        }.toString()
        val place = CachedPlace("018956", "{\"cached\":true}", 100L)
        val leg = CachedLeg("saved-road-leg", "{\"km\":4.2}", 200L)
        val first = openDatabase()
        first.dao().save(SavedRoute(base.id, legacy, base.created))
        first.dao().place(place)
        first.dao().leg(leg)
        first.close()

        val reopened = openDatabase()
        val row = requireNotNull(reopened.dao().route(base.id))
        assertEquals(legacy, row.json)
        assertEquals(base.created, row.created)
        assertEquals(place, reopened.dao().place(place.postal))
        assertEquals(leg, reopened.dao().leg(leg.key))
        val restored = PlanJson.decode(row.json)
        assertEquals(base.id, restored.id)
        assertEquals(base.geometry, restored.geometry)
        assertEquals(base.stops.map { it.place }, restored.stops.map { it.place })
        assertEquals(1, restored.current)
        assertEquals("DELIVERED", restored.stops[0].status)
        assertEquals("2026-10-03T10:15:00", restored.stops[0].completedAt)
        assertEquals("2026-10-03T10:15:00", restored.stops[0].reviewedAt)
        assertNull(restored.stops[1].holdReason)
        assertNull(restored.stops[1].holdNote)
        assertEquals("0.00", restored.cashOnHand)
        assertEquals("0.00", restored.tax)
        assertNull(restored.summarySavedAt)
        val summary = deliverySummary(restored)
        assertEquals(1, summary.delivered)
        assertEquals(3, summary.pending)

        // Saving the upgraded payload and restarting again must not discard the legacy history.
        reopened.dao().save(SavedRoute(restored.id, PlanJson.encode(restored), restored.created))
        reopened.close()
        val secondRestart = openDatabase()
        assertEquals(restored, PlanJson.decode(requireNotNull(secondRestart.dao().route(base.id)).json))
    }

    @Test
    fun restartingAnActiveRevisitKeepsTheHeldStopUnreviewedAndSelected() = runBlocking {
        val base = samplePlan()
        val reviewed = "2026-10-03T11:00:00"
        val resumed = base.copy(
            stops = base.stops.mapIndexed { index, stop ->
                if (index == 1) stop.copy(
                    status = "ON_HOLD", holdReason = "Customer not home", reviewedAt = null
                ) else stop.copy(status = "DELIVERED", completedAt = reviewed, reviewedAt = reviewed)
            },
            current = 1,
            reviewedFinishedAt = null,
            cashOnHand = "120.00",
            tax = "4.50"
        )
        val first = openDatabase()
        first.dao().save(SavedRoute(resumed.id, PlanJson.encode(resumed), resumed.created))
        first.close()

        val reopened = openDatabase()
        val restored = PlanJson.decode(requireNotNull(reopened.dao().route(resumed.id)).json)
        assertEquals(resumed, restored)
        assertEquals(1, restored.current)
        assertEquals("ON_HOLD", restored.stops[restored.current].status)
        assertEquals("Customer not home", restored.stops[restored.current].holdReason)
        assertNull(restored.stops[restored.current].reviewedAt)
        assertNull(restored.reviewedFinishedAt)
        assertEquals("120.00", restored.cashOnHand)
        assertEquals("4.50", restored.tax)
    }

    private fun samplePlan(): Plan {
        val places = listOf("018956", "238858", "560123", "738099").mapIndexed { index, postal ->
            Place(postal, 1.30 + index * 0.02, 103.80 + index * 0.01, "${index + 1}", "Test area", "Test address")
        }
        return schedule(
            places = places,
            legs = List(places.size + 1) { Leg(4.2, 600.0, 60.0) },
            start = LocalDateTime.of(2026, 10, 3, 10, 0),
            service = 8,
            mode = "Normal Traffic",
            geometry = listOf(listOf(103.80, 1.30), listOf(103.81, 1.32))
        )
    }
}
