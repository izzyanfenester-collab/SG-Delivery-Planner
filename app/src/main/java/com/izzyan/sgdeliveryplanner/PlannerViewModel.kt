package com.izzyan.sgdeliveryplanner

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PlannerViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    private val prefs = app.getSharedPreferences("settings", 0)
    private val locationSettings = StartLocationSettings(app)
    private val navigation = ScreenHistory()
    private var currentScreen by mutableStateOf("Home")
    private val singapore = ZoneId.of("Asia/Singapore")
    var input by mutableStateOf(prefs.getString("input", "")!!)
    var screen: String
        get() = currentScreen
        set(value) { navigation.navigate(value); currentScreen = navigation.current }
    val canGoBack: Boolean get() { currentScreen; return navigation.canGoBack }
    var selectedStartLocation by mutableStateOf(locationSettings.selected())
        private set
    var savedHome by mutableStateOf(locationSettings.home())
        private set
    var route by mutableStateOf<Plan?>(null)
    var reportPreview by mutableStateOf<Plan?>(null)
        private set
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var notice by mutableStateOf("")
    var service by mutableStateOf(prefs.getInt("service", 8))
    var startMinute by mutableStateOf(prefs.getInt("start", 600))
    var traffic by mutableStateOf(prefs.getString("traffic", "Normal")!!)
    var theme by mutableStateOf(prefs.getString("theme", "System")!!)
    var endpoint by mutableStateOf(prefs.getString("endpoint", "https://router.project-osrm.org")!!)
    val history = repo.dao.history()

    init {
        viewModelScope.launch {
            val active = prefs.getString("active", null)
            if (active != null) busy = true
            try {
                active?.let { id ->
                    repo.dao.route(id)?.let { saved ->
                        route = repo.decode(saved)
                        val restored = route!!
                        screen = when {
                            restored.reviewedFinishedAt != null -> "Summary"
                            restored.stops.any { it.reviewedAt != null } -> "Delivery"
                            else -> "Home"
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = englishError(e, "Could not restore your saved route. Try opening it from History.") }
            finally { busy = false }
        }
    }

    fun persist() {
        prefs.edit().putInt("service", service).putInt("start", startMinute)
            .putString("traffic", traffic).putString("theme", theme)
            .putString("endpoint", endpoint).putString("input", input).apply()
    }

    fun goBack() {
        if (!busy) currentScreen = navigation.back()
    }

    fun goLocationPicker() {
        if (!busy) screen = "LocationPicker"
    }

    fun selectStartLocation(location: StartLocation) {
        if (busy) return
        try {
            locationSettings.select(location)
            selectedStartLocation = location
            message = ""
        } catch (e: Exception) { message = englishError(e, "Could not save the Start & End location. Please try again.") }
    }

    fun saveHome(location: StartLocation) {
        if (busy) return
        try {
            val home = locationSettings.saveHome(location)
            savedHome = home
            selectedStartLocation = home
            message = ""
            notice = "Home location saved. Your next route will start and end at Home."
        } catch (e: Exception) { message = englishError(e, "Could not save your Home location. Please try again.") }
    }

    fun useHome() {
        if (busy) return
        val home = savedHome
        if (home == null) message = "No Home location saved. Choose a location on the map, then tap Set Home."
        else selectStartLocation(home)
    }

    fun closeReportPreview() { reportPreview = null }

    fun optimize() {
        if (busy) return
        val parsed = parseInput(input)
        if (parsed.invalid.isNotEmpty() || parsed.valid.isEmpty()) {
            message = if (parsed.invalid.isEmpty()) "Enter at least one six-digit Singapore postal code."
                else "Use six digits for each Singapore postal code. Check these entries: ${parsed.invalid.joinToString()}"
            return
        }
        persist()
        busy = true
        notice = ""
        viewModelScope.launch {
            try {
                val planned = repo.plan(
                    parsed.valid, LocalDate.now(singapore).atStartOfDay().plusMinutes(startMinute.toLong()),
                    service, traffic, endpoint, selectedStartLocation
                ) { message = it }
                route = planned
                prefs.edit().putString("active", planned.id).apply()
                screen = "Route"
                message = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = englishError(e, "Could not plan your route. Check your internet connection and postal codes, then try again.") }
            finally { busy = false }
        }
    }

    fun open(record: SavedRoute) {
        if (busy) return
        try {
            route = repo.decode(record)
            prefs.edit().putString("active", record.id).apply()
            message = ""
            notice = ""
            screen = "Summary"
        } catch (e: Exception) { message = englishError(e, "Could not open this saved route. Please try another route from History.") }
    }

    fun progress(action: String, holdReason: String? = null, holdNote: String? = null) {
        if (busy) return
        val planned = route ?: return
        if (planned.current !in planned.stops.indices) { screen = "Summary"; return }
        busy = true
        message = ""
        notice = ""
        viewModelScope.launch {
            try {
                val updated = reviewStop(planned, action, LocalDateTime.now(singapore).toString(), holdReason, holdNote)
                // Persist before advancing the visible stop so a failed save does not lose a delivery action.
                repo.save(updated)
                route = updated
                if (updated.reviewedFinishedAt != null) screen = "Summary"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = englishError(e, "Could not save your delivery progress. Please try again.") }
            finally { busy = false }
        }
    }

    fun revisit(index: Int) {
        if (busy) return
        val planned = route ?: return
        if (index !in planned.stops.indices) return
        busy = true
        viewModelScope.launch {
            try {
                // A delivered card opens for viewing/navigation without invalidating its saved summary.
                val updated = if (normalizedStatus(planned.stops[index].status) == "DELIVERED") {
                    planned.copy(current = index)
                } else {
                    val stops = planned.stops.toMutableList()
                    stops[index] = stops[index].copy(reviewedAt = null)
                    planned.copy(stops = stops, current = index, reviewedFinishedAt = null)
                }
                repo.save(updated)
                route = updated
                screen = "Delivery"
                message = ""
                notice = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = englishError(e, "Could not reopen this delivery stop. Please try again.") }
            finally { busy = false }
        }
    }

    fun saveSummary(cash: String, tax: String) {
        if (busy) return
        val planned = route ?: return
        val normalizedCash = normalizedCurrency(cash)
        val normalizedTax = normalizedCurrency(tax)
        if (normalizedCash == null || normalizedTax == null) {
            message = "Enter cash and tax amounts of zero or more in SGD, with up to two decimal places."
            return
        }
        busy = true
        reportPreview = null
        viewModelScope.launch {
            try {
                val updated = planned.copy(cashOnHand = normalizedCash, tax = normalizedTax,
                    summarySavedAt = LocalDateTime.now(singapore).toString())
                repo.save(updated)
                route = updated
                message = ""
                notice = "Summary saved."
                reportPreview = updated
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = englishError(e, "Could not save your summary. Please try again.") }
            finally { busy = false }
        }
    }
}
