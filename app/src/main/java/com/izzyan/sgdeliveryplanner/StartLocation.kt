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
        "WOODLANDS_JB" -> "Woodlands Checkpoint towards JB"
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
        type in setOf("WOODLANDS", "WOODLANDS_JB", "CUSTOM", "HOME") &&
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    }.getOrDefault(false)

    fun asPlace(): Place {
        require(isValid()) { "Choose a valid location." }
        return if (type == "WOODLANDS") depot else Place(postalCode, latitude, longitude,
            displayName, if (type == "HOME") "Home" else "Custom Location", address.ifBlank { displayName })
    }
}

/** End choice is stored separately from Start; Last Delivery means no return leg. */
data class EndLocationSelection(val useLastDelivery: Boolean = true, val location: StartLocation? = null) {
    fun isValid(): Boolean = useLastDelivery || location?.isValid() != false // null means legacy End = Start
}

val woodlandsStartLocation = StartLocation("WOODLANDS", depot.lat, depot.lon,
    "Woodlands Checkpoint", "21 Woodlands Crossing, Singapore 738203", depot.postal)

// OSM node 7102270869, one-way car departure way 760260082, after outbound booths
// and before joining the JB-bound Causeway at node 7102270871. Preserve inbound depot.
val woodlandsEndLocation = StartLocation("WOODLANDS_JB", 1.4467448, 103.7679164,
    "Woodlands Checkpoint towards JB", "Woodlands Checkpoint, departure towards Malaysia", "738203")
fun defaultEndLocation() = EndLocationSelection(false, woodlandsEndLocation)

fun supportedPlanningCoordinates(lat:Double,lon:Double):Boolean =
    lat.isFinite() && lon.isFinite() && ((lat in 0.85..7.6 && lon in 99.5..104.7) ||
        (lat in 0.8..7.6 && lon in 109.0..119.5))
fun coordinateLocation(latitude:String,longitude:String):StartLocation? {
    val lat=latitude.trim().toDoubleOrNull() ?: return null
    val lon=longitude.trim().toDoubleOrNull() ?: return null
    if(!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    val label=String.format(java.util.Locale.US,"%.6f, %.6f",lat,lon)
    return StartLocation("CUSTOM",lat,lon,label)
}
fun pastedCoordinatePair(value:String):Pair<String,String>? {
    val parts=value.trim().split(',')
    if(parts.size!=2 || coordinateLocation(parts[0],parts[1])==null) return null
    return parts[0].trim() to parts[1].trim()
}
class StartLocationSettings(context: Context) {
    private val prefs = context.getSharedPreferences("start_end_locations", Context.MODE_PRIVATE)
    private val gson = Gson()
    fun selected(): StartLocation = read("selected") ?: woodlandsStartLocation
    fun home(): StartLocation? = read("home")?.takeIf { it.type == "HOME" }
    fun selectedEnd(): EndLocationSelection = when(prefs.getString("end_mode", null)) {
        "LAST_DELIVERY" -> EndLocationSelection()
        "FIXED" -> read("end_selected")?.let { EndLocationSelection(false, it) } ?: defaultEndLocation()
        else -> defaultEndLocation()
    }
    fun selectEndLastDelivery() {
        if (!prefs.edit().putString("end_mode", "LAST_DELIVERY").commit())
            throw PlannerError("Could not save the end location. Please try again.")
    }
    fun selectEndLocation(location: StartLocation) {
        checkLocation(location)
        if (!prefs.edit().putString("end_mode", "FIXED").putString("end_selected", gson.toJson(location)).commit())
            throw PlannerError("Could not save the end location. Please try again.")
    }
    fun select(location: StartLocation) {
        checkLocation(location)
        if (!prefs.edit().putString("selected", gson.toJson(location)).commit())
            throw PlannerError("Could not save the start location. Please try again.")
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
    fun saveHomeOnly(location:StartLocation):StartLocation {
        checkLocation(location)
        val home=location.copy(type="HOME",label="Home",
            address=location.address.ifBlank { String.format(java.util.Locale.US,"%.6f, %.6f",location.latitude,location.longitude) })
        if(!prefs.edit().putString("home",gson.toJson(home)).commit())
            throw PlannerError("Could not save your Home location. Please try again.")
        return home
    }
    private fun read(key: String): StartLocation? = runCatching {
        gson.fromJson(prefs.getString(key, null), StartLocation::class.java)?.takeIf { it.isValid() }
    }.getOrNull()
    private fun checkLocation(location: StartLocation) {
        if (!location.isValid()) throw PlannerError("Select valid coordinates before saving this location.")
        if (!supportedPlanningCoordinates(location.latitude,location.longitude)) throw PlannerError("Location is outside Singapore/Malaysia routing coverage.")
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
