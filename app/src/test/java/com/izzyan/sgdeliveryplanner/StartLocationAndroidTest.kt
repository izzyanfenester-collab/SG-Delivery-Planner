package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Persist real preferences and route payloads to protect Home updates and historical origins. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class StartLocationAndroidTest {
    private lateinit var app: Application
    private lateinit var scheduler: TestCoroutineScheduler
    private val viewModels = mutableListOf<ViewModelStore>()
    private val custom = StartLocation(
        "CUSTOM", 1.320456, 103.812345, "Collection office",
        "8 Test Avenue, Singapore 005003", "005003"
    )

    @Before
    fun clearPreferences() {
        app = ApplicationProvider.getApplicationContext()
        assertTrue(app.getSharedPreferences("start_end_locations", Context.MODE_PRIVATE).edit().clear().commit())
        assertTrue(app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit())
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun clearViewModels() {
        viewModels.forEach(ViewModelStore::clear)
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun firstLaunchUsesWoodlandsAndHasNoSavedHome() {
        val settings = StartLocationSettings(app)
        assertEquals(woodlandsStartLocation, settings.selected())
        assertNull(settings.home())
        assertEquals(depot, settings.selected().asPlace())
    }

    @Test
    fun selectedCustomLocationPersistsAllFieldsAcrossSettingsRecreation() {
        StartLocationSettings(app).select(custom)
        val reopened = StartLocationSettings(app)
        assertEquals(custom, reopened.selected())
        assertNull(reopened.home())
        val place = reopened.selected().asPlace()
        assertEquals(custom.latitude, place.lat, 0.0)
        assertEquals(custom.longitude, place.lon, 0.0)
        assertEquals(custom.postalCode, place.postal)
        assertEquals(custom.label, place.block)
        assertEquals(custom.address, place.address)
    }

    @Test
    fun savedHomeAndUpdatedHomePersistWithTheSelectedLocationUpdatedTogether() {
        val first = StartLocationSettings(app)
        first.select(custom)
        val home = first.saveHome(custom)
        assertEquals("HOME", home.type)
        assertEquals("Home", home.displayName)
        assertEquals(custom.latitude, home.latitude, 0.0)
        assertEquals(custom.address, home.address)
        assertEquals(home, StartLocationSettings(app).home())
        assertEquals(home, StartLocationSettings(app).selected())

        val secondCustom = custom.copy(
            latitude = 1.410123, longitude = 103.765432,
            label = "New collection office", address = "12 New Test Road", postalCode = "730001"
        )
        val updated = StartLocationSettings(app).saveHome(secondCustom)
        val reopened = StartLocationSettings(app)
        assertEquals(updated, reopened.home())
        assertEquals("HOME", updated.type)
        assertEquals(secondCustom.latitude, updated.latitude, 0.0)
        assertEquals(secondCustom.longitude, updated.longitude, 0.0)
        assertEquals(secondCustom.address, updated.address)
        assertEquals(secondCustom.postalCode, updated.postalCode)
        assertEquals(updated, reopened.selected())

        reopened.select(requireNotNull(reopened.home()))
        assertEquals(updated, StartLocationSettings(app).selected())
    }

    @Test
    fun invalidLocationCannotOverwriteSelectedLocationOrSavedHome() {
        val settings = StartLocationSettings(app)
        settings.select(custom)
        val home = settings.saveHome(custom)
        listOf(
            custom.copy(latitude = 91.0),
            custom.copy(longitude = -181.0),
            custom.copy(latitude = Double.NaN),
            custom.copy(longitude = Double.POSITIVE_INFINITY),
            custom.copy(type = "UNKNOWN")
        ).forEach { invalid ->
            assertThrows(PlannerError::class.java) { settings.select(invalid) }
            assertThrows(PlannerError::class.java) { settings.saveHome(invalid) }
            assertEquals(home, StartLocationSettings(app).selected())
            assertEquals(home, StartLocationSettings(app).home())
        }
    }

    @Test
    fun corruptPersistedLocationsFallBackSafelyInsteadOfUsingInvalidCoordinates() {
        val prefs = app.getSharedPreferences("start_end_locations", Context.MODE_PRIVATE)
        assertTrue(prefs.edit()
            .putString("selected", "{\"type\":\"CUSTOM\",\"latitude\":999,\"longitude\":103.8}")
            .putString("home", "not-json")
            .commit())
        assertEquals(woodlandsStartLocation, StartLocationSettings(app).selected())
        assertNull(StartLocationSettings(app).home())
    }

    @Test
    fun historyKeepsItsOriginalHomeSnapshotAfterHomeIsReplaced() {
        val settings = StartLocationSettings(app)
        val originalHome = settings.saveHome(custom)
        settings.select(originalHome)
        val scheduled = plan(originalHome)
        val record = SavedRoute(scheduled.id, PlanJson.encode(scheduled), scheduled.created)

        val newHome = settings.saveHome(custom.copy(
            latitude = 1.440123, longitude = 103.775432,
            address = "44 Replacement Home Road", postalCode = "730099"
        ))
        settings.select(newHome)

        val reopened = PlanJson.decode(record.json)
        assertEquals(scheduled, reopened)
        assertEquals(originalHome, reopened.startLocation)
        assertNotEquals(StartLocationSettings(app).home(), reopened.startLocation)
        assertEquals(originalHome.asPlace(), reopened.startLocation.asPlace())
        assertEquals(scheduled.stops, reopened.stops)
        assertEquals(scheduled.totalKm, reopened.totalKm, 0.0)
    }

    @Test
    fun legacyMissingOrNullStartLocationAlwaysRemainsWoodlandsRegardlessOfCurrentSelection() {
        val settings = StartLocationSettings(app)
        settings.select(custom)
        settings.saveHome(custom)
        val original = plan(woodlandsStartLocation)
        val missing = JsonParser.parseString(PlanJson.encode(original)).asJsonObject.apply {
            remove("startLocation")
        }
        val explicitNull = JsonParser.parseString(PlanJson.encode(original)).asJsonObject.apply {
            add("startLocation", JsonNull.INSTANCE)
        }
        listOf(missing.toString(), explicitNull.toString()).forEach { json ->
            val restored = PlanJson.decode(json)
            assertEquals(woodlandsStartLocation, restored.startLocation)
            assertEquals(original.stops, restored.stops)
            assertEquals(original.start, restored.start)
            assertEquals(original.returned, restored.returned)
        }
    }

    @Test
    fun historicalCustomRoutePreservesLabelAddressPostalAndCoordinates() {
        val scheduled = plan(custom)
        val restored = PlanJson.decode(PlanJson.encode(scheduled))
        assertEquals(custom, restored.startLocation)
        assertEquals(custom.reportLabel, restored.startLocation.reportLabel)
        assertEquals(custom.asPlace(), restored.startLocation.asPlace())
    }

    @Test
    fun roadCacheKeysSeparateDirectionServerAndDifferentCoordinatesWithSamePostalCode() {
        val from = custom.asPlace()
        val to = depot.copy(postal = "100004", lat = 1.350123, lon = 103.850456)
        val endpoint = "https://routing.example"
        val key = roadCacheKey(endpoint, from, to)
        assertNotEquals(key, roadCacheKey(endpoint, to, from))
        assertNotEquals(key, roadCacheKey("https://different-routing.example", from, to))
        assertNotEquals(key, roadCacheKey(endpoint, from.copy(lat = from.lat + 0.001), to))
        assertNotEquals(key, roadCacheKey(endpoint, from, to.copy(lon = to.lon + 0.001)))
        assertEquals(key, roadCacheKey(endpoint, from.copy(block = "Renamed Home", address = "New label only"), to))
    }

    @Test
    fun googleMapsUsesHistoricalCustomOrHomeOriginForBothEndsAndPreservesWaypointOrder() {
        val places = listOf(
            depot.copy(postal = "560003", lat = 1.380123, lon = 103.820456),
            depot.copy(postal = "005003", lat = 1.300789, lon = 103.850123),
            depot.copy(postal = "730001", lat = 1.440456, lon = 103.790789)
        )
        listOf(custom, custom.copy(type = "HOME", label = "Home")).forEach { location ->
            val saved = schedule(
                places, List(places.size + 1) { Leg(4.2, 600.0, 60.0) },
                LocalDateTime.of(2026, 10, 3, 10, 0), 8, "Normal Traffic", emptyList(), location
            )
            // Changing the current selection must not redirect an older route to another Home.
            StartLocationSettings(app).saveHome(custom.copy(latitude = 1.450999, longitude = 103.760888))
            val intent = routeGoogleMapsIntent(saved)
            val uri = requireNotNull(intent.data)
            val coordinates = "${location.latitude},${location.longitude}"

            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https", uri.scheme)
            assertEquals("www.google.com", uri.host)
            assertEquals("1", uri.getQueryParameter("api"))
            assertEquals("driving", uri.getQueryParameter("travelmode"))
            assertEquals(coordinates, uri.getQueryParameter("origin"))
            assertEquals(coordinates, uri.getQueryParameter("destination"))
            assertEquals(places.joinToString("|") { "${it.lat},${it.lon}" }, uri.getQueryParameter("waypoints"))
        }
    }

    @Test
    fun backNavigationReturnsThroughPreviousScreensAndDoesNotDuplicateCurrentScreen() {
        val navigation = ScreenHistory()
        assertEquals("Home", navigation.current)
        assertFalse(navigation.canGoBack)
        navigation.navigate("Route")
        navigation.navigate("Delivery")
        navigation.navigate("Delivery")
        navigation.navigate("LocationPicker")
        assertEquals("Delivery", navigation.back())
        assertEquals("Route", navigation.back())
        assertEquals("Home", navigation.back())
        assertFalse(navigation.canGoBack)
        assertEquals("Home", navigation.back())
    }

    @Test
    fun updatingHomeSelectsLatestSavedCoordinatesAndLeavesActiveRouteSnapshotUnchanged() {
        val vm = model()
        vm.saveHome(custom)
        val originalHome = requireNotNull(vm.savedHome)
        val active = plan(originalHome)
        vm.route = active
        val newLocation = custom.copy(
            latitude = 1.440123, longitude = 103.775432,
            address = "44 Replacement Home Road", postalCode = "730099"
        )

        vm.saveHome(newLocation)
        val updatedHome = requireNotNull(vm.savedHome)
        assertEquals("HOME", updatedHome.type)
        assertEquals(newLocation.latitude, updatedHome.latitude, 0.0)
        assertEquals(newLocation.longitude, updatedHome.longitude, 0.0)
        assertEquals(updatedHome, vm.selectedStartLocation)
        assertSame(active, vm.route)
        assertEquals(originalHome, requireNotNull(vm.route).startLocation)

        val restarted = model()
        assertEquals(updatedHome, restarted.savedHome)
        assertEquals(updatedHome, restarted.selectedStartLocation)
        restarted.selectStartLocation(woodlandsStartLocation)
        restarted.useHome()
        assertEquals(updatedHome, restarted.selectedStartLocation)
        assertEquals(updatedHome, StartLocationSettings(app).selected())
    }

    @Test
    fun backingOutOfSettingsAndMapPickerPreservesCurrentStopStatusesAndUserInput() {
        val vm = model()
        val active = plan(custom).let { scheduled ->
            scheduled.copy(
                stops = scheduled.stops.map { stop -> stop.copy(
                    status = "ON_HOLD", holdReason = "Access issue", reviewedAt = "2026-10-03T10:30"
                ) },
                cashOnHand = "120.00", tax = "4.50"
            )
        }
        vm.route = active
        vm.input = "005003 730001"
        vm.screen = "Route"
        vm.screen = "Delivery"
        vm.screen = "Settings"
        vm.goLocationPicker()

        assertEquals("LocationPicker", vm.screen)
        vm.goBack()
        assertEquals("Settings", vm.screen)
        vm.goBack()
        assertEquals("Delivery", vm.screen)
        assertSame(active, vm.route)
        assertEquals(0, requireNotNull(vm.route).current)
        assertEquals("ON_HOLD", requireNotNull(vm.route).stops[0].status)
        assertEquals("120.00", requireNotNull(vm.route).cashOnHand)
        assertEquals("4.50", requireNotNull(vm.route).tax)
        assertEquals("005003 730001", vm.input)
    }

    @Test
    fun missingHomeAndBusyPlanningCannotSilentlyChangeSelectedLocationOrNavigation() {
        val vm = model()
        vm.useHome()
        assertEquals(woodlandsStartLocation, vm.selectedStartLocation)
        assertNull(vm.savedHome)
        assertTrue(vm.message.contains("No Home location saved"))
        vm.screen = "Delivery"
        vm.busy = true

        vm.selectStartLocation(custom)
        vm.saveHome(custom)
        vm.goLocationPicker()
        vm.goBack()

        assertEquals(woodlandsStartLocation, vm.selectedStartLocation)
        assertNull(vm.savedHome)
        assertEquals("Delivery", vm.screen)
        assertEquals(woodlandsStartLocation, StartLocationSettings(app).selected())
        assertNull(StartLocationSettings(app).home())
    }

    private fun model(): PlannerViewModel = PlannerViewModel(app).also { model ->
        viewModels += ViewModelStore().apply { put("planner", model) }
    }

    private fun plan(location: StartLocation): Plan = schedule(
        listOf(depot.copy(postal = "005003", block = "8", area = "Test area")),
        listOf(Leg(4.2, 600.0, 60.0), Leg(4.8, 660.0, 80.0)),
        LocalDateTime.of(2026, 10, 3, 10, 0),
        8,
        "Normal Traffic",
        emptyList(),
        location
    )
}
