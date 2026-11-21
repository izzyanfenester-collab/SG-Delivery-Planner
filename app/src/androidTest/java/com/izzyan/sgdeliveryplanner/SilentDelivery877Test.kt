package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production Activity, delivery buttons, Room, report preview and audio controller.
 * Fixture records live only in the isolated TESTING package. No road drive is claimed.
 */
class SilentDelivery877Test {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val results get() = File(app.filesDir, "b877-evidence").apply { mkdirs() }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun configureAudio() = ui.runOnIdle {
        GpsNavigationModePreferences(app).apply { setSoundEnabled(true); setVoiceVolume(1f) }
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
            maxOf(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2), 0)
    }

    private fun fixture(): Plan {
        val places = List(3) { index ->
            Place("460011", 1.323 + index * .0001, 103.935 + index * .0001,
                "11", "Native acceptance fixture", "Block 11, #05-12${index + 1}, Singapore 460011")
        }
        val plan = schedule(places, List(4) { Leg(1.0, 120.0, 0.0) },
            LocalDateTime.of(2026, 11, 21, 10, 0), 8, "Normal", emptyList())
        return plan.copy(stops = plan.stops.mapIndexed { index, stop ->
            stop.copy(order = CustomerOrder(orderId = stop.orderId,
                postalCode = stop.place.postal, customerName = "Acceptance order ${index + 1}",
                fullAddress = stop.place.address, parcelPrice = BigDecimal("25.00"),
                paymentStatus = if (index % 2 == 0) "COD" else "PAYNOW"))
        })
    }

    @Test fun productionDeliveryAndReportFlowsAreSilentAndPersistNormally() {
        configureAudio()
        lateinit var vm: PlannerViewModel
        ui.runOnIdle { vm = ViewModelProvider(ui.activity)[PlannerViewModel::class.java] }
        ui.waitUntil(15_000) { !vm.busy }
        val fixture = fixture()
        runBlocking { vm.repo.save(fixture) }
        ui.runOnIdle {
            vm.route = fixture
            vm.deliveryReportOrderId = null
            vm.screen = "Delivery"
        }
        ui.waitForIdle()
        shell("logcat -c")
        val phases = JSONArray()

        fun silentPhase(name: String, action: () -> Unit) {
            shell("logcat -c")
            action()
            ui.waitUntil(15_000) { !vm.busy }
            ui.waitForIdle()
            Thread.sleep(700)
            val log = shell("logcat -d -v brief -s SGPanda866:V TextToSpeech:V")
            assertFalse("$name requested Panda playback:\n$log",
                log.contains("key=") || log.contains("speak("))
            File(results, "$name.log").writeText(log)
            phases.put(JSONObject().put("flow", name).put("result", "PASS")
                .put("pandaPlaybackRequested", false))
        }
        fun click(label: String) {
            ui.onNodeWithText(label, useUnmergedTree = true).performScrollTo().performClick()
        }
        fun report(orderIndex: Int) {
            ui.waitUntil(15_000) { vm.deliveryReportOrderId == fixture.stops[orderIndex].orderId }
            ui.waitUntil(20_000) {
                ui.onAllNodesWithContentDescription("Delivery Report for stop ${orderIndex + 1}")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText("Share Report").assertIsEnabled()
        }
        fun closeReport() { ui.onNodeWithText("Close").performClick() }
        fun persisted(): Plan = runBlocking { vm.repo.decode(vm.repo.dao.route(fixture.id)!!) }

        silentPhase("01-delivered-and-auto-next") {
            click("Delivered")
            report(0)
            assertEquals("DELIVERED", persisted().stops[0].status)
            assertEquals(1, vm.route!!.current)
            assertNotNull(persisted().stops[0].completedAt)
        }
        silentPhase("02-save-delivery-report-image") {
            val stop = persisted().stops[0]
            val image = generateSingleDeliveryReport(app, stop, 0)
            assertTrue(image.isFile && image.length() > 0)
            assertTrue(singleDeliveryReportText(stop, 0, "Acceptance")
                .contains("Acceptance order 1"))
            closeReport()
        }
        silentPhase("03-next-stop") {
            click("Next stop")
            ui.waitUntil(15_000) { vm.route?.current == 2 && !vm.busy }
            assertNotNull(persisted().stops[1].reviewedAt)
            assertEquals("PENDING", persisted().stops[1].status)
        }
        silentPhase("04-finish-reviewed-route") {
            click("Delivered")
            report(2)
            closeReport()
            assertEquals("Summary", vm.screen)
            assertNotNull(persisted().reviewedFinishedAt)
        }
        silentPhase("05-revisit-unfinished-stop") {
            ui.runOnIdle { vm.revisit(1) }
            ui.waitUntil(15_000) { vm.route?.current == 1 && !vm.busy }
            assertEquals("Delivery", vm.screen)
        }
        silentPhase("06-complete-final-delivery") {
            click("Delivered")
            report(1)
            closeReport()
            assertTrue(isRouteCompleted(persisted()))
            assertEquals(3, deliverySummary(persisted()).delivered)
            assertNotNull(persisted().actualCompletion)
        }
        silentPhase("07-open-completed-report") {
            ui.runOnIdle { vm.deliveryReportOrderId = fixture.stops[0].orderId }
            report(0)
            closeReport()
        }
        silentPhase("08-return-to-delivery-activity") {
            ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(isRouteCompleted(persisted()))
        }
        silentPhase("09-open-completed-history") {
            val record = runBlocking { vm.repo.dao.route(fixture.id)!! }
            ui.runOnIdle { vm.open(record) }
            assertNotNull(vm.reportPreview)
            assertEquals(fixture.stops.map { it.orderId }, persisted().stops.map { it.orderId })
            assertEquals(fixture.stops.map { it.order }, persisted().stops.map { it.order })
        }
        assertTrue(GpsNavigationModePreferences(app).isSoundEnabled())
        assertEquals(1f, GpsNavigationModePreferences(app).voiceVolume(), 0f)
        File(results, "delivery-flows.json").writeText(JSONObject()
            .put("result", "PASS").put("environment", "Android API 35 emulator; offline")
            .put("flows", phases).put("persistedDelivered", 3)
            .put("gpsVoiceEnabled", true).put("voiceVolume", 1).toString(2))
    }

    private fun provePlayback(id: String, priority: VoicePriority) {
        configureAudio()
        val states = CopyOnWriteArrayList<PandaPlaybackEvent866>()
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        lateinit var controller: NavigationVoiceAudio
        instrumentation.runOnMainSync { controller = NavigationVoiceAudio(app) }
        try {
            assertTrue(controller.available209)
            var accepted = false
            instrumentation.runOnMainSync {
                accepted = controller.play(VoicePhrase("b877-$id", priority,
                    listOf(VoicePart(id, "Preserved offline $id"))),
                    observer = { event ->
                        states.add(event)
                        if (event.state == PandaPlaybackState866.STARTED) started.countDown()
                        if (event.state == PandaPlaybackState866.COMPLETED) completed.countDown()
                    })
            }
            assertTrue("Audio controller rejected $id", accepted)
            assertTrue("No real MediaPlayer STARTED for $id: $states", started.await(15, TimeUnit.SECONDS))
            assertTrue("No real MediaPlayer COMPLETED for $id: $states", completed.await(20, TimeUnit.SECONDS))
            assertFalse(states.any { it.state == PandaPlaybackState866.FAILED })
            File(results, "audio-$id.json").writeText(JSONObject()
                .put("result", "PASS").put("clip", id).put("offline", true)
                .put("started", true).put("completed", true)
                .put("events", JSONArray(states.map { JSONObject()
                    .put("state", it.state.name).put("reason", it.reason)
                    .put("elapsedMs", it.elapsedMs) })).toString(2))
        } finally { instrumentation.runOnMainSync { controller.close() } }
    }
    @Test fun preservedOfflineTurnPlaybackActuallyStartsAndCompletes() =
        provePlayback("turn_right", VoicePriority.CRITICAL)
    @Test fun preservedOfflineSpeedCameraPlaybackActuallyStartsAndCompletes() =
        provePlayback("speed_camera_ahead", VoicePriority.CAMERA)
    @Test fun preservedOfflineRedLightCameraPlaybackActuallyStartsAndCompletes() =
        provePlayback("red_light_camera_ahead", VoicePriority.CAMERA)
}
