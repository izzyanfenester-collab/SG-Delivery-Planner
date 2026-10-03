package com.izzyan.sgdeliveryplanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale

private val LocationMuted = Color(0xFFB7C7E1)

private fun locationCoordinates(location: StartLocation): String =
    String.format(Locale.ENGLISH, "%.6f, %.6f", location.latitude, location.longitude)

/** Home only changes after a confirmed choice; opening or cancelling the picker is harmless. */
@Composable
fun StartLocationControls(vm: PlannerViewModel) {
    var showChoices by remember { mutableStateOf(false) }
    var replaceHome by remember { mutableStateOf(false) }
    val selected = vm.selectedStartLocation
    val isCheckpoint = selected.type == woodlandsStartLocation.type

    PremiumCard(
        Modifier.fillMaxWidth(), borderColor = PremiumGold.copy(alpha = .5f),
        containerColor = PremiumNavy
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Start & End", color = PremiumGold, style = MaterialTheme.typography.labelLarge)
            Text(selected.displayName, color = Color.White, style = MaterialTheme.typography.titleLarge)
            if (selected.address.isNotBlank()) Text(selected.address, color = LocationMuted)
            Text(locationCoordinates(selected), color = LocationMuted, style = MaterialTheme.typography.bodySmall)
            Text("Your route returns to this location after the final delivery.", color = LocationMuted,
                style = MaterialTheme.typography.bodySmall)
            LocationGoldButton("Change", { showChoices = true }, enabled = !vm.busy)
        }
    }

    if (showChoices) {
        var chooseCustom by remember { mutableStateOf(!isCheckpoint) }
        AlertDialog(
            onDismissRequest = { showChoices = false },
            containerColor = PremiumNavy,
            titleContentColor = PremiumGold,
            textContentColor = Color.White,
            title = { Text("Start and end location") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LocationChoice("Woodlands Checkpoint", "Default start and return location", !chooseCustom) {
                        chooseCustom = false
                    }
                    LocationChoice("Custom / Home", "Choose a map point or use your saved Home", chooseCustom) {
                        chooseCustom = true
                    }
                    if (chooseCustom) {
                        if (vm.savedHome == null) {
                            Text("No Home location saved", color = PremiumGold,
                                style = MaterialTheme.typography.titleSmall)
                            Text("Choose a location on the map and confirm it. You can then set that location as Home.",
                                color = LocationMuted, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text("Saved Home: ${vm.savedHome!!.displayName}", color = LocationMuted)
                            Text(locationCoordinates(vm.savedHome!!), color = LocationMuted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        LocationGoldButton("Choose on Map", {
                            showChoices = false
                            vm.goLocationPicker()
                        }, enabled = !vm.busy)
                        LocationOutlineButton("Use Home", {
                            vm.useHome()
                            showChoices = false
                        }, enabled = !vm.busy && vm.savedHome?.isValid() == true)
                        LocationOutlineButton(if (vm.savedHome == null) "Set Home" else "Update Home", {
                            if (vm.savedHome != null) replaceHome = true
                            else {
                                vm.saveHome(selected)
                                showChoices = false
                            }
                        }, enabled = !vm.busy && !isCheckpoint && selected.isValid())
                        if (isCheckpoint) Text("Choose a custom location on the map before setting Home.",
                            color = LocationMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (!chooseCustom) TextButton(onClick = {
                    vm.selectStartLocation(woodlandsStartLocation)
                    showChoices = false
                }, enabled = !vm.busy) { Text("Use Woodlands Checkpoint", color = PremiumGold) }
            },
            dismissButton = { TextButton(onClick = { showChoices = false }) { Text("Close", color = LocationMuted) } }
        )
    }
    if (replaceHome) {
        AlertDialog(
            onDismissRequest = { replaceHome = false },
            containerColor = PremiumNavy,
            titleContentColor = PremiumGold,
            textContentColor = Color.White,
            title = { Text("Replace Home?") },
            text = { Text("Save ${selected.displayName} as your Home location? This replaces the Home saved on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveHome(selected)
                    replaceHome = false
                    showChoices = false
                }, enabled = !vm.busy) { Text("Replace Home", color = PremiumGold) }
            },
            dismissButton = { TextButton(onClick = { replaceHome = false }) { Text("Cancel", color = LocationMuted) } }
        )
    }
}

@Composable
private fun LocationChoice(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = PremiumGold, unselectedColor = LocationMuted))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(description, color = LocationMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LocationGoldButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PremiumGold, contentColor = PremiumNavy)
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
private fun LocationOutlineButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, PremiumGold.copy(alpha = .6f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PremiumGold)
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun StartLocationPicker(vm: PlannerViewModel) {
    val context = LocalContext.current
    val initial = remember {
        vm.selectedStartLocation.takeIf { it.type != woodlandsStartLocation.type && it.isValid() }
    }
    var selected by remember { mutableStateOf(initial) }
    var latitude by remember { mutableStateOf(initial?.latitude?.toString().orEmpty()) }
    var longitude by remember { mutableStateOf(initial?.longitude?.toString().orEmpty()) }
    var lookingUpAddress by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf("") }

    fun choosePoint(point: GeoPoint) {
        val candidate = StartLocation("CUSTOM", point.latitude, point.longitude, "Custom Location")
        if (!candidate.isValid()) {
            validation = "Choose a location with valid coordinates."
            return
        }
        selected = candidate
        latitude = String.format(Locale.ENGLISH, "%.6f", point.latitude)
        longitude = String.format(Locale.ENGLISH, "%.6f", point.longitude)
        validation = ""
    }

    // Coordinates remain a valid choice when reverse geocoding or map tiles are unavailable.
    LaunchedEffect(selected?.latitude, selected?.longitude) {
        val point = selected ?: return@LaunchedEffect
        if (point.address.isNotBlank()) return@LaunchedEffect
        lookingUpAddress = true
        try {
            val address = withContext(Dispatchers.IO) {
                runCatching {
                    if (!Geocoder.isPresent()) null else {
                        @Suppress("DEPRECATION")
                        val addresses = Geocoder(context, Locale.ENGLISH)
                            .getFromLocation(point.latitude, point.longitude, 1)
                        addresses?.firstOrNull()
                    }
                }.getOrNull()
            }
            if (address != null) {
                val readable = address.getAddressLine(0).orEmpty().replace('\n', ' ').trim()
                selected = point.copy(
                    address = readable,
                    postalCode = address.postalCode.orEmpty().trim().takeIf { it.matches(Regex("[0-9]{6}")) }.orEmpty()
                )
            }
        } finally { lookingUpAddress = false }
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Choose your start and end location", style = MaterialTheme.typography.headlineSmall)
        Text("Pan and zoom the map, then tap to place the marker. Tap again to move it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LocationMap(selected, initial ?: woodlandsStartLocation, ::choosePoint)
        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PremiumCard(Modifier.fillMaxWidth(), borderColor = PremiumGold.copy(alpha = .5f), containerColor = PremiumNavy) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Selected location", color = PremiumGold, style = MaterialTheme.typography.titleMedium)
                val point = selected
                if (point == null) Text("Tap the map to choose a location.", color = LocationMuted)
                else {
                    Text(point.displayName, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(locationCoordinates(point), color = LocationMuted)
                    Text(if (lookingUpAddress) "Finding the address…" else point.address.ifBlank {
                        "Address unavailable. The selected coordinates will be used."
                    }, color = LocationMuted, style = MaterialTheme.typography.bodyMedium)
                }
                Text("This point will be used for both the start and return of your next route.",
                    color = LocationMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("If map tiles are unavailable, enter the coordinates below.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        OutlinedButton(onClick = {
            val lat = latitude.toDoubleOrNull()
            val lon = longitude.toDoubleOrNull()
            if (lat == null || lon == null || !StartLocation("CUSTOM", lat, lon, "Custom Location").isValid())
                validation = "Enter a latitude from -90 to 90 and a longitude from -180 to 180."
            else choosePoint(GeoPoint(lat, lon))
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Use coordinates") }
        if (validation.isNotBlank()) Text(validation, color = MaterialTheme.colorScheme.error)
        LocationGoldButton("Confirm Location", {
            selected?.takeIf { it.isValid() }?.let {
                vm.selectStartLocation(it)
                vm.goBack()
            }
        }, enabled = !vm.busy && selected?.isValid() == true)
        OutlinedButton(onClick = vm::goBack, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Cancel") }
    }
}

@Composable
private fun LocationMap(selected: StartLocation?, initialCenter: StartLocation, onChoose: (GeoPoint) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestChoose by rememberUpdatedState(onChoose)
    val map = remember(context) {
        runCatching {
            MapView(context).apply {
                setMultiTouchControls(true)
                setTileSource(TileSourceFactory.MAPNIK)
                controller.setZoom(14.5)
                controller.setCenter(GeoPoint(initialCenter.latitude, initialCenter.longitude))
            }
        }.getOrNull()
    }
    if (map == null) {
        PremiumCard(Modifier.fillMaxWidth()) {
            Text("The map could not be opened. Enter coordinates below to choose your location.", Modifier.padding(20.dp))
        }
        return
    }
    val marker = remember(map) {
        Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = goldLocationMarker(context.resources)
        }
    }
    DisposableEffect(map, lifecycle) {
        val events = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean { latestChoose(point); return true }
            override fun longPressHelper(point: GeoPoint): Boolean { latestChoose(point); return true }
        })
        map.overlays.add(0, events)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            map.onPause()
            map.onDetach()
        }
    }
    AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().height(340.dp), update = { view ->
        if (selected == null) view.overlays.remove(marker)
        else {
            marker.position = GeoPoint(selected.latitude, selected.longitude)
            marker.title = selected.displayName
            marker.snippet = selected.address.ifBlank { locationCoordinates(selected) }
            if (!view.overlays.contains(marker)) view.overlays.add(marker)
        }
        view.invalidate()
    })
}

private fun goldLocationMarker(resources: android.content.res.Resources): BitmapDrawable {
    val bitmap = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.rgb(228, 188, 105)
    val path = android.graphics.Path().apply {
        moveTo(40f, 98f); lineTo(13f, 53f); lineTo(67f, 53f); close()
    }
    canvas.drawPath(path, paint)
    canvas.drawCircle(40f, 36f, 34f, paint)
    paint.color = android.graphics.Color.rgb(7, 23, 45)
    canvas.drawCircle(40f, 36f, 24f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(40f, 36f, 8f, paint)
    return BitmapDrawable(resources, bitmap)
}
