package com.izzyan.sgdeliveryplanner

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class WhatsAppImportViewModelTest {
    private lateinit var vm: PlannerViewModel
    private val store = ViewModelStore()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun setup() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        vm = PlannerViewModel(app)
        store.put("import", vm)
        dispatcher.scheduler.runCurrent()
    }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    @Test fun appendsOnlyUniqueSelectedCodesAndPreservesActivePlan() {
        val plan = schedule(listOf(Place("731625", 1.35, 103.8, "1", "Area", "Road")),
            List(2) { Leg(1.0, 100.0, 10.0) }, LocalDateTime.of(2026, 10, 6, 10, 0), 8, "Normal Traffic", emptyList())
        vm.route = plan; vm.screen = "Delivery"; vm.input = "792452"
        vm.openWhatsAppImport(listOf("792452", "731625", "650202"))
        assertEquals(setOf("792452", "731625"), vm.existingImportCodes())
        vm.addWhatsAppCodes(listOf("792452", "731625", "650202", "650202", "Customer name"))
        assertEquals("792452\n650202", vm.input)
        assertSame(plan, vm.route)
        assertEquals("Found 3 / Added 1 / Duplicate 2 / Failed 0", vm.notice)
        assertEquals("Home", vm.screen)
        assertEquals(vm.input, app.getSharedPreferences("settings", 0).getString("input", null))
        assertFalse(vm.showWhatsAppImport); assertNull(vm.whatsAppImportCodes)
    }
    @Test fun cancellingAndBusyImportNeverChangesInputsOrDeliveryState() {
        vm.input = "792452"
        vm.openWhatsAppImport(listOf("650202")); vm.closeWhatsAppImport()
        assertEquals("792452", vm.input)
        assertNull(vm.whatsAppImportCodes)
        vm.busy = true; vm.addWhatsAppCodes(listOf("650202"))
        assertEquals("792452", vm.input)
    }
    @Test fun oversizedSelectionIsRejectedWithoutPartialImport() {
        val codes = (1..100).map { it.toString().padStart(6, '0') }
        vm.openWhatsAppImport(codes)
        vm.addWhatsAppCodes(codes)
        assertEquals("", vm.input)
        assertTrue(vm.showWhatsAppImport)
        assertTrue(vm.message.contains("50 stops"))
    }
    @Test fun exactlyFiftyCodesCanBeImportedAndExistingInputCountsTowardLimit() {
        val codes = (1..50).map { it.toString().padStart(6, '0') }
        vm.addWhatsAppCodes(codes)
        assertEquals(codes, parseInput(vm.input).valid)
        vm.openWhatsAppImport(listOf("999999"))
        vm.addWhatsAppCodes(listOf("999999"))
        assertEquals(codes, parseInput(vm.input).valid)
        assertTrue(vm.showWhatsAppImport)
    }
}
