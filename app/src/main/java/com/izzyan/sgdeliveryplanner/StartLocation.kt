package com.izzyan.sgdeliveryplanner

import android.content.Context
import com.google.gson.Gson

/** Every route stores its own immutable depot snapshot, independent of later Home changes. */
data class StartLocation(
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val address: String = "",
    val postalCode: String = ""
) {
    val displayName: String get() = when (type) {
        "WOODLANDS" -> "Woodlands Checkpoint"
        "HOME" -> "Home"
        else -> label.trim().let {
            if ((it.isEmpty() || it == "Custom Location") && address.isNotBlank()) address else it.ifEmpty { "Custom Location" }
        }
    }
    val reportLabel: String get() = if (address.isNotBlank() && address != displayName)
        "$displayName — $address" else displayName

    fun isValid(): Boolean = runCatching {
        // Gson can populate nulls from a damaged persisted payload despite Kotlin's types.
        label.trim(); address.trim(); postalCode.trim()
        type in setOf("WOODLANDS", "CUSTOM", "HOME") &&
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    }.getOrDefault(false)

    fun asPlace(): Place {
        require(isValid()) { "Choose a valid Start & End location." }
        return if (type == "WOODLANDS") depot else Place(postalCode, latitude, longitude,
            displayName, if (type == "HOME") "Home" else "Custom Location", address.ifBlank { displayName })
    }
}

val woodlandsStartLocation = StartLocation("WOODLANDS", depot.lat, depot.lon,
    "Woodlands Checkpoint", "21 Woodlands Crossing, Singapore 738203", depot.postal)

class StartLocationSettings(context: Context) {
    private val prefs = context.getSharedPreferences("start_end_locations", Context.MODE_PRIVATE)
    private val gson = Gson()
    fun selected(): StartLocation = read("selected") ?: woodlandsStartLocation
    fun home(): StartLocation? = read("home")?.takeIf { it.type == "HOME" }
    fun select(location: StartLocation) {
        checkLocation(location)
        if (!prefs.edit().putString("selected", gson.toJson(location)).commit())
            throw PlannerError("Could not save the Start & End location. Please try again.")
    }
    fun saveHome(location: StartLocation): StartLocation {
        checkLocation(location)
        val home = location.copy(type = "HOME", label = "Home",
            address = location.address.ifBlank { location.displayName })
        val snapshot = gson.toJson(home)
        if (!prefs.edit().putString("home", snapshot).putString("selected", snapshot).commit())
            throw PlannerError("Could not save your Home location. Please try again.")
        return home
    }
    private fun read(key: String): StartLocation? = runCatching {
        gson.fromJson(prefs.getString(key, null), StartLocation::class.java)?.takeIf { it.isValid() }
    }.getOrNull()
    private fun checkLocation(location: StartLocation) {
        if (!location.isValid()) throw PlannerError("Select valid coordinates before saving this location.")
    }
}

/** Coordinate-scoped road keys prevent one custom depot from reusing another depot's legs. */
fun roadCacheKey(endpoint: String, from: Place, to: Place): String =
    "$endpoint|${from.postal}@${from.lat},${from.lon}|${to.postal}@${to.lat},${to.lon}"

class ScreenHistory {
    private val screens = mutableListOf<String>()
    var current: String = "Home"
        private set
    val canGoBack: Boolean get() = screens.isNotEmpty() || current != "Home"
    fun navigate(screen: String) {
        if (screen == current) return
        screens += current
        current = screen
    }
    fun back(): String {
        current = if (screens.isEmpty()) "Home" else screens.removeAt(screens.lastIndex)
        return current
    }
}
