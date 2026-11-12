package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun locationPostalLabel(postal: String): String =
    when (postal.length) {
        5 -> "Malaysia " + postal
        6 -> "Singapore " + postal
        else -> postal
    }

/** Android geocoding is online/device-provider dependent; never invent coordinates. */
internal suspend fun searchMalaysiaLocations(context: Context, term: String): List<StartLocation> =
    withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) error("No geocoding provider")
        @Suppress("DEPRECATION")
        val matches = Geocoder(context, Locale.ENGLISH).getFromLocationName(
            term.trim() + " Malaysia", 8, 0.8, 99.0, 7.5, 120.0
        ).orEmpty()
        matches.filter { it.countryCode.equals("MY", ignoreCase = true) && it.hasLatitude() && it.hasLongitude() }
            .mapNotNull { address ->
                val full = address.getAddressLine(0).orEmpty().trim()
                val label = address.featureName?.takeIf { it.isNotBlank() } ?: full
                StartLocation("CUSTOM", address.latitude, address.longitude,
                    label.ifBlank { term.trim() }, full, address.postalCode.orEmpty().trim())
                    .takeIf { it.isValid() && GpsPoint(it.latitude, it.longitude).valid() }
            }.distinctBy { it.latitude to it.longitude }.take(6)
    }

internal data class FreeTripPreview(val meters: Double, val seconds: Double, val points: List<GpsPoint>)

/** Separate screen: no PlannerViewModel, Plan, database or delivery-state writes. */
@Composable
internal fun FreeNavigationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf<StartLocation?>(null) }
    var destination by remember { mutableStateOf<StartLocation?>(null) }
    var choosingStart by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<FreeTripPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val jb = remember { StartLocation("CUSTOM", 1.4927, 103.7414, "Johor Bahru") }
    fun choose(location: StartLocation) {
        if (choosingStart) origin = location else destination = location
        preview = null
        error = ""
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Free Navigation", style = MaterialTheme.typography.headlineSmall, color = PremiumGold)
        Text("Malaysia & Singapore • Personal trips, independent of deliveries")
        PremiumCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Start location", color = PremiumGold)
                Text(origin?.displayName ?: "Current GPS location")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = origin == null && !choosingStart, enabled = !busy,
                        onClick = { origin = null; choosingStart = false; preview = null; error = "" },
                        label = { Text("Current GPS") })
                    FilterChip(selected = choosingStart, enabled = !busy,
                        onClick = { choosingStart = true }, label = { Text("Custom start") })
                }
                Text("Destination", color = PremiumGold)
                Text(destination?.reportLabel ?: "Choose a destination")
                OutlinedButton(onClick = { choosingStart = false }, enabled = !busy) {
                    Text("Choose destination")
                }
            }
        }
        Text(if (choosingStart) "Search start location" else "Search destination", color = PremiumGold)
        if (!busy) {
            key(choosingStart) {
                LocationSearchPanel("Place, street or postal code", initialCountry = "MY", onChosen = ::choose)
            }
        }
        Text("Or tap the map to select " + if (choosingStart) "a start location" else "a destination")
        LocationMap(if (choosingStart) origin else destination, jb, onChoose = { point ->
            if (!busy) choose(StartLocation("CUSTOM", point.latitude, point.longitude, "Map location",
                "${point.latitude}, ${point.longitude}"))
        }, routePoints = preview?.points.orEmpty())
        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val start = origin
        val end = destination
        OutlinedButton(onClick = {
            if (start != null && end != null) {
                scope.launch {
                    busy = true; error = ""; preview = null
                    try {
                        val route = ValhallaClient().route(GpsPoint(start.latitude, start.longitude),
                            GpsPoint(end.latitude, end.longitude))
                        preview = FreeTripPreview(route.distance, route.duration, decodeNavigationGeometry(route.geometry))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = "Route unavailable. Check your internet connection and try again." }
                    finally { busy = false }
                }
            }
        }, enabled = !busy && start != null && end != null, modifier = Modifier.fillMaxWidth()) {
            Text("Preview custom route")
        }
        preview?.let { Text("Route preview: " + metricDistance(it.meters) + " • " + duration(it.seconds), color = PremiumGold) }
        if (start != null) Text("Custom start is for planning. Live driving always begins from your current GPS location.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            if (end != null) {
                val destinationPlace = end.asPlace().copy(address = end.displayName)
                context.startActivity(builtInNavigationIntent(context, destinationPlace, freeNavigation = true))
            }
        }, enabled = !busy && end != null, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PremiumGold, contentColor = PremiumNavy)) {
            Text("Start Navigation")
        }
        Text("Malaysia search and live navigation require internet. Existing offline Singapore delivery maps are unchanged.",
            style = MaterialTheme.typography.bodySmall)
    }
}
