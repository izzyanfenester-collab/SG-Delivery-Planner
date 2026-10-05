package com.izzyan.sgdeliveryplanner

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeliveryDateAndroidTest {
    private lateinit var app: Application
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var store: ViewModelStore
    private lateinit var vm: PlannerViewModel

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        store = ViewModelStore()
        vm = newViewModel("initial")
    }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun newViewModel(key: String): PlannerViewModel = PlannerViewModel(app).also {
        store.put(key, it)
        pumpUntil { !it.busy }
    }

    @Test fun homeAndSettingsDatePickersUseOneEditableValueAndRestoreTheSelection() {
        assertEquals(LocalDate.now(ZoneId.of("Asia/Singapore")), vm.deliveryDate)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            vm.screen = "Home"
            val homePicker = deliveryDatePicker(activity.get(), vm.deliveryDate, vm::selectDeliveryDate)
            homePicker.show()
            homePicker.datePicker.updateDate(2028, 1, 29)
            homePicker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            vm.screen = "Settings"
            assertEquals(LocalDate.of(2028, 2, 29), vm.deliveryDate)
            val settingsPicker = deliveryDatePicker(activity.get(), vm.deliveryDate, vm::selectDeliveryDate)
            settingsPicker.show()
            assertEquals(2028, settingsPicker.datePicker.year)
            assertEquals(1, settingsPicker.datePicker.month)
            assertEquals(29, settingsPicker.datePicker.dayOfMonth)
            settingsPicker.datePicker.updateDate(2029, 0, 7)
            settingsPicker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            vm.screen = "Home"
            assertEquals(LocalDate.of(2029, 1, 7), vm.deliveryDate)
            vm.startMinute = 23 * 60 + 55
            vm.persist()
            assertEquals(LocalDateTime.of(2029, 1, 7, 23, 55), vm.scheduledStart())
            val restored = newViewModel("restart")
            assertEquals(vm.deliveryDate, restored.deliveryDate)
            assertEquals(vm.scheduledStart(), restored.scheduledStart())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun cancellingPickerKeepsTheSharedDateAndInvalidOldSettingFallsBackToToday() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val original = vm.deliveryDate
            val picker = deliveryDatePicker(activity.get(), original, vm::selectDeliveryDate)
            picker.show()
            picker.datePicker.updateDate(2030, 5, 12)
            picker.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(original, vm.deliveryDate)
        } finally { activity.pause().stop().destroy() }
        app.getSharedPreferences("settings", 0).edit().putString("deliveryDate", "invalid old preference").commit()
        assertEquals(LocalDate.now(ZoneId.of("Asia/Singapore")), newViewModel("invalid").deliveryDate)
    }

    @Test fun startedRouteKeepsItsDateAndTimesWhenScheduleChangesAndHistoryReopens() {
        vm.selectDeliveryDate(LocalDate.of(2028, 2, 29))
        vm.startMinute = 23 * 60 + 55
        val plan = schedule(listOf(depot), List(2) { Leg(2.0, 300.0, 60.0) },
            vm.scheduledStart(), 8, "Normal", emptyList())
        vm.route = reviewStop(plan, "DELIVERED", "2028-03-01T00:20:37")
        val started = requireNotNull(vm.route)
        runBlocking { vm.repo.save(started) }
        vm.screen = "Settings"
        vm.selectDeliveryDate(LocalDate.of(2030, 6, 12))
        assertEquals(started, vm.route)
        assertEquals(LocalDate.of(2028, 2, 29), vm.route!!.deliveryDate)
        val row = requireNotNull(runBlocking { vm.repo.dao.route(plan.id) })
        vm.open(row)
        assertEquals(started, vm.route)
        assertEquals(LocalDate.of(2030, 6, 12), vm.deliveryDate)
        assertTrue(buildSummaryReport(started).startsWith("Delivery Report Summary\nDate: 29 Feb 2028\n"))
        assertTrue(buildSummaryReport(started).contains("Finish: 12:20 AM (1 Mar 2028)"))
        assertEquals("Runner_Route_Planning_2028-02-29.xlsx", deliveryExcelFileName(started))
        val bytes = ByteArrayOutputStream().also { writeDeliveryScheduleXlsx(started, it) }.toByteArray()
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { book ->
            val sheet = book.getSheetAt(0)
            val dateRow = sheet.first { it.getCell(0)?.toString() == "Date" }
            assertEquals(LocalDate.of(2028, 2, 29), dateRow.getCell(1).localDateTimeCellValue.toLocalDate())
        }
        assertEquals("2028-03-01T00:20:37", vm.route!!.stops[0].completedAt)
        assertEquals(started.etaReturned, vm.route!!.etaReturned)
    }

    @Test fun oldHistoryUsesItsSavedStartDateWithoutNeedingNewJsonFields() {
        val old = schedule(listOf(depot), List(2) { Leg(2.0, 300.0, 60.0) },
            LocalDateTime.of(2025, 12, 31, 23, 55), 8, "Normal", emptyList())
        val json = JsonParser.parseString(PlanJson.encode(old)).asJsonObject.apply {
            remove("deliveryDate")
            remove("deliveryStateVersion")
            remove("exchangeRate")
            remove("remark")
        }.toString()
        runBlocking { vm.repo.dao.save(SavedRoute(old.id, json, old.created)) }
        vm.selectDeliveryDate(LocalDate.of(2030, 1, 1))
        val restored = vm.repo.decode(requireNotNull(runBlocking { vm.repo.dao.route(old.id) }))
        assertEquals(LocalDate.of(2025, 12, 31), restored.deliveryDate)
        assertEquals(old.start, restored.start)
        assertEquals(old.stops, restored.stops)
        assertEquals(old.geometry, restored.geometry)
        assertTrue(buildSummaryReport(restored).contains("Date: 31 Dec 2025\n"))
        assertEquals("Runner_Route_Planning_2025-12-31.xlsx", deliveryExcelFileName(restored))
    }

    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            scheduler.runCurrent()
            if (done()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Timed out waiting for the ViewModel")
    }
}
