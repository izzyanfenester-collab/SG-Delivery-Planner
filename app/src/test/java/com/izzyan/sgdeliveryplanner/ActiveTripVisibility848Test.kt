package com.izzyan.sgdeliveryplanner

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActiveTripVisibility848Test {
    private lateinit var app: Application
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var vm: PlannerViewModel
    private lateinit var store: ViewModelStore
    private val now = "2026-11-13T12:00:00"

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        store = ViewModelStore()
        vm = PlannerViewModel(app)
        store.put("planner", vm)
        scheduler.runCurrent()
        pump()
    }

    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun plan(day: Int) = schedule(
        listOf(depot.copy(postal = "001234"), depot.copy(postal = "001235")),
        List(3) { Leg(1.0, 60.0, 0.0) }, LocalDateTime.of(2026,11,day,8,0),
        10,"Normal",emptyList()
    )

    @Test fun completedTripClearsSelectionButRetainsFinalSummaryAndOtherTrips() {
        val a = reviewStop(plan(13),"DELIVERED",now)
        val b = reviewStop(plan(14),"ON_HOLD",now,"No answer")
        val c = plan(15)
        runBlocking { listOf(a,b,c).forEach { vm.repo.save(it) } }
        vm.route = a
        vm.screen = "Delivery"
        app.getSharedPreferences("settings",0).edit().putString("active",a.id).commit()
        vm.progress("DELIVERED")
        pump()
        val completed = requireNotNull(vm.route)
        assertEquals(SavedDeliveryStatus.COMPLETED, savedDeliveryStatus(completed))
        assertNull(vm.activeRoute)
        assertNull(app.getSharedPreferences("settings",0).getString("active",null))
        assertEquals("Summary", vm.screen)
        assertNotNull(vm.deliveryReportOrderId)
        assertEquals(a.stops.map { it.orderId },completed.stops.map { it.orderId })
        vm.saveSummary("100","5","3.60","Final")
        pump()
        assertNotNull(vm.reportPreview)
        assertNull(vm.activeRoute)
        assertEquals("5.00", vm.route!!.tax)
        val rows = runBlocking { vm.repo.dao.allHistoryForBackup() }
        assertEquals(setOf(b.id,c.id), unfinishedSavedPlans(rows).filter { it.id in setOf(a.id,b.id,c.id) }.map { it.id }.toSet())
        assertEquals(b,runBlocking { loadPersistedRouteForDelivery(vm.repo.dao,b.id) })
        assertEquals(c,runBlocking { loadPersistedRouteForDelivery(vm.repo.dao,c.id) })
        vm.closeReportPreview()
        vm.continueSavedRoute(b.id,"Route")
        pump()
        assertEquals(b,vm.activeRoute)
        assertEquals("Route",vm.screen)
        vm.continueSavedRoute(a.id)
        pump()
        assertEquals(b,vm.activeRoute)
        assertEquals(b.id,app.getSharedPreferences("settings",0).getString("active",null))
    }

    @Test fun restartDiscardsLegacyCompletedPointerWithoutDeletingHistory() {
        val a = reviewStop(reviewStop(plan(13),"DELIVERED",now),"DELIVERED",now)
        runBlocking { vm.repo.save(a) }
        app.getSharedPreferences("settings",0).edit().putString("active",a.id).commit()
        val reopened = PlannerViewModel(app)
        store.put("reopened",reopened)
        scheduler.runCurrent()
        pump(reopened)
        assertNull(reopened.activeRoute)
        assertNull(reopened.route)
        assertNull(app.getSharedPreferences("settings",0).getString("active",null))
        assertEquals(a,runBlocking { PlanJson.decode(requireNotNull(reopened.repo.dao.route(a.id)).json) })
    }

    @Test fun existingCompletionStatusAndExplicitRevisitArePreserved() {
        val pending = plan(13)
        val reviewed = reviewStop(reviewStop(pending,"ON_HOLD",now,"No answer"),"SKIP",now)
        assertEquals(SavedDeliveryStatus.COMPLETED,savedDeliveryStatus(reviewed))
        assertNull(activeDeliveryPlan(reviewed))
        assertEquals(pending,activeDeliveryPlan(pending))
        vm.route = reviewed
        vm.screen = "Summary"
        vm.revisit(0)
        pump()
        assertEquals(SavedDeliveryStatus.IN_PROGRESS,savedDeliveryStatus(requireNotNull(vm.activeRoute)))
        assertEquals("Delivery",vm.screen)
        assertEquals(reviewed.id,app.getSharedPreferences("settings",0).getString("active",null))
    }

    private fun pump(target: PlannerViewModel = vm) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do { scheduler.runCurrent(); if (!target.busy) return; Thread.sleep(5) } while (System.nanoTime() < deadline)
        fail("Timed out waiting for saved route")
    }
}
