package com.izzyan.sgdeliveryplanner

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SummaryPersistenceAndroidTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var vm: PlannerViewModel
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() {
        val app: Application = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vm = PlannerViewModel(app)
        store = ViewModelStore().apply { put("planner", vm) }
        pumpUntil { !vm.oneMapTokenBusy && !vm.busy }
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun summaryIsPersistedBeforePreviewAndClosingRetainsSavedValuesAndProgress() {
        val home = StartLocation("HOME", 1.32, 103.82, "Home", "Saved home address", "012345")
        val plan = schedule(listOf(depot.copy(postal = "001234")),
            listOf(Leg(2.5, 300.0, 60.0), Leg(4.0, 600.0, 120.0)),
            LocalDateTime.of(2026, 10, 3, 10, 0), 8, "Normal", emptyList(), home)
        vm.route = reviewStop(plan, "DELIVERED", "2026-10-03T10:20:00")
        vm.screen = "Summary"

        vm.saveSummary("320", "12.5")
        assertNull("No preview while the save is pending", vm.reportPreview)
        pumpUntil { !vm.busy }
        val preview = requireNotNull(vm.reportPreview)
        val record = runBlocking { vm.repo.dao.route(plan.id) }
        assertNotNull("Preview is only shown after the database contains the summary", record)
        val persisted = vm.repo.decode(requireNotNull(record))
        assertEquals(preview, persisted)
        assertEquals("320.00", preview.cashOnHand)
        assertEquals("12.50", preview.tax)
        assertEquals(home, preview.startLocation)
        assertEquals(1, deliverySummary(preview).delivered)
        assertNotNull(preview.summarySavedAt)

        vm.closeReportPreview()
        assertNull(vm.reportPreview)
        assertEquals(preview, vm.route)
        assertEquals("Summary", vm.screen)
        assertEquals(preview, vm.repo.decode(requireNotNull(runBlocking { vm.repo.dao.route(plan.id) })))
    }

    @Test
    fun invalidMoneyDoesNotSaveOrOpenPreview() {
        val plan = schedule(listOf(depot.copy(postal = "001234")),
            listOf(Leg(2.0, 300.0, 0.0), Leg(2.0, 300.0, 0.0)),
            LocalDateTime.of(2026, 10, 3, 10, 0), 8, "Normal", emptyList())
        vm.route = plan
        vm.saveSummary("-1", "12.345")
        scheduler.runCurrent()
        assertFalse(vm.busy)
        assertNull(vm.reportPreview)
        assertEquals(plan, vm.route)
        assertNull(runBlocking { vm.repo.dao.route(plan.id) })
        assertTrue(vm.message.contains("SGD"))
    }

    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            scheduler.runCurrent()
            if (done()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Timed out waiting for the summary save")
    }
}
