package com.izzyan.sgdeliveryplanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.google.gson.JsonObject
import retrofit2.HttpException
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale

private val LocationMuted = SGColors.Silver

private fun locationCoordinates(location: StartLocation): String =
    String.format(Locale.ENGLISH, "%.6f, %.6f", location.latitude, location.longitude)

/** Home only changes after a confirmed choice; opening or cancelling the picker is harmless. */
@Composable
fun StartLocationControls(vm: PlannerViewModel) {
    var showChoices by remember { mutableStateOf(false) }
    var showEndChoices by remember { mutableStateOf(false) }
    var replaceHome by remember { mutableStateOf(false) }
    val selected = vm.selectedStartLocation
    val isCheckpoint = selected.type == woodlandsStartLocation.type

    PremiumCard(
        Modifier.fillMaxWidth(), borderColor = PremiumGold.copy(alpha = .5f),
        containerColor = SGColors.Card
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Start location", color = PremiumGold, style = MaterialTheme.typography.labelMedium)
                    Text(selected.displayName, color = Color.White,
                        style = MaterialTheme.typography.titleMedium, maxLines = 2)
                }
                Button(
                    onClick = { showChoices = true }, enabled = !vm.busy,
                    modifier = Modifier.height(40.dp), shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PremiumGold, contentColor = PremiumNavy)
                ) { Text("Change", fontWeight = FontWeight.Bold) }
            }
            if (selected.address.isNotBlank()) {
                Text(selected.address, color = LocationMuted,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (selected.postalCode.isNotBlank()) Text("Singapore " + selected.postalCode,
                color = LocationMuted, style = MaterialTheme.typography.bodySmall)
        }
    }

    PremiumCard(
        Modifier.fillMaxWidth(), borderColor = PremiumGold.copy(alpha = .5f),
        containerColor = SGColors.Card
    ) {
        val chosenEnd = vm.selectedEndLocation
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("End location", color = PremiumGold, style = MaterialTheme.typography.labelMedium)
                    Text(if (chosenEnd.useLastDelivery) "Last delivery (automatic)"
                         else chosenEnd.location?.displayName ?: "Last delivery (automatic)",
                        color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                }
                Button(
                    onClick = { showEndChoices = true }, enabled = !vm.busy,
                    modifier = Modifier.height(40.dp), shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PremiumGold, contentColor = PremiumNavy)
                ) { Text("Change", fontWeight = FontWeight.Bold) }
            }
            if (chosenEnd.useLastDelivery) {
                Text("Route ends at the final optimized delivery stop. No return journey.",
                    color = LocationMuted, style = MaterialTheme.typography.bodySmall)
            } else {
                val destination = chosenEnd.location
                if (destination != null) {
                    if (destination.address.isNotBlank()) Text(destination.address,
                        color = LocationMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    if (destination.postalCode.isNotBlank()) Text("Singapore " + destination.postalCode,
                        color = LocationMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if(showEndChoices) EndpointDialog(vm,true){showEndChoices=false}
    if(showChoices) EndpointDialog(vm,false){showChoices=false}
}
@Composable
private fun EndpointDialog(vm:PlannerViewModel,end:Boolean,close:()->Unit){
    val initial=if(end) vm.selectedEndLocation.location else vm.selectedStartLocation
    var choice by remember { mutableStateOf(if(end&&vm.selectedEndLocation.useLastDelivery) 1 else if(initial?.type in setOf("WOODLANDS","WOODLANDS_JB")) 0 else 2) }
    fun apply(point:StartLocation){if(end)vm.selectEndLocation(point) else vm.selectStartLocation(point);close()}
    AlertDialog(onDismissRequest=close,containerColor=PremiumNavy,titleContentColor=PremiumGold,textContentColor=Color.White,
        title={Text(if(end)"Choose End Location" else "Choose Start Location")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
            LocationChoice(if(end)"Woodlands Checkpoint towards JB (Default)" else "Woodlands Checkpoint towards Singapore (Default)","",choice==0){choice=0}
            LocationChoice(if(end)"Last Delivery" else "Custom / Search Postal Code","",choice==1){choice=1}
            LocationChoice("Longitude, Latitude / Home","",choice==2){choice=2}
            if(choice==1&&!end) LocationSearchPanel("Search postal code / place"){apply(it)}
            if(choice==2) CoordinateEndpointCard(vm,initial,::apply)
        }},confirmButton={if(choice==0||(choice==1&&end))TextButton(onClick={
            if(choice==1){vm.selectEndLastDelivery();close()}else apply(if(end)woodlandsEndLocation else woodlandsStartLocation)
        }){Text("Confirm Location",color=PremiumGold)}},dismissButton={TextButton(onClick=close){Text("Close")}})
}
@Composable
private fun CoordinateEndpointCard(vm:PlannerViewModel,initial:StartLocation?,confirm:(StartLocation)->Unit){
    val context=LocalContext.current
    var lat by remember{mutableStateOf(initial?.takeUnless{it.type.startsWith("WOODLANDS")}?.latitude?.toString().orEmpty())}
    var lon by remember{mutableStateOf(initial?.takeUnless{it.type.startsWith("WOODLANDS")}?.longitude?.toString().orEmpty())}
    var home by remember{mutableStateOf(initial?.type=="HOME")}
    var status by remember{mutableStateOf("")}
    var loading by remember{mutableStateOf(false)}
    fun gps(){
        loading=true
        try{
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,null)
                .addOnSuccessListener{point->loading=false;if(point==null)status="GPS unavailable" else {lat=point.latitude.toString();lon=point.longitude.toString();home=false;status="Accuracy: ${point.accuracy.toInt()} m"}}
                .addOnFailureListener{loading=false;status="GPS unavailable"}
        }catch(e:SecurityException){loading=false;status="Location permission denied"}
    }
    val permission=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()){grants->if(grants.values.any{it})gps()else status="Location permission denied"}
    val point=coordinateLocation(lat,lon)
    OutlinedTextField(lat,{value->val pair=value.split(",");if(pair.size==2){lat=pair[0].trim();lon=pair[1].trim()}else lat=value;home=false},label={Text("Latitude")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(lon,{lon=it;home=false},label={Text("Longitude")},singleLine=true,modifier=Modifier.fillMaxWidth())
    if(home)Text("Location Name: Home",color=PremiumGold)
    LocationOutlineButton(if(loading)"Locating…" else "Use Current Location",{
        if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED ||
           androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)gps()
        else permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION))
    },!loading)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        TextButton(onClick={vm.savedHome?.let{lat=it.latitude.toString();lon=it.longitude.toString();home=true}},enabled=vm.savedHome!=null){Text("Use Saved Home")}
        TextButton(onClick={point?.let{vm.saveHome(it);home=true;status="Home saved"}},enabled=point!=null){Text("Save as Home")}
    }
    if(status.isNotBlank())Text(status,color=LocationMuted)
    if(point==null&&(lat.isNotBlank()||lon.isNotBlank()))Text("Invalid latitude or longitude",color=MaterialTheme.colorScheme.error)
    LocationGoldButton("Confirm Location",{point?.let{confirm(if(home)it.copy(type="HOME",label="Home")else it)}},point!=null&&!loading)
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
    SGPrimaryButton(label, onClick, Modifier.fillMaxWidth(), enabled, 52.dp)
}

@Composable
private fun LocationOutlineButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    SGSecondaryButton(label, onClick, Modifier.fillMaxWidth(), enabled, 52.dp)
}

/** Searches the bundled Singapore postal/address index for both Start and End. */
private fun parseLocationMatches(response: JsonObject): List<StartLocation> {
    val items = response.get("results")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return items.mapNotNull { item ->
        if (!item.isJsonObject) return@mapNotNull null
        val row = item.asJsonObject
        fun field(key: String): String =
            row.get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString.orEmpty().trim()
        val lat = field("LATITUDE").toDoubleOrNull() ?: return@mapNotNull null
        val lon = field("LONGITUDE").toDoubleOrNull() ?: return@mapNotNull null
        if (lat !in 1.1..1.6 || lon !in 103.5..104.1) return@mapNotNull null
        val postal = field("POSTAL").takeIf { it.matches(Regex("[0-9]{6}")) }.orEmpty()
        val road = listOf(field("BLK_NO"), field("ROAD_NAME")).filter { it.isNotBlank() }.joinToString(" ")
        val building = field("BUILDING").takeUnless { it.isBlank() || it.equals("NIL", ignoreCase = true) }
        val address = field("ADDRESS").takeUnless { it.equals("NIL", ignoreCase = true) }.orEmpty()
        val label = building ?: road.ifBlank { field("SEARCHVAL").ifBlank { address } }
        StartLocation("CUSTOM", lat, lon, label.ifBlank { "Singapore " + postal }, address, postal)
            .takeIf { it.isValid() }
    }.distinctBy { it.latitude to it.longitude }.take(6)
}

@Composable
private fun LocationSearchPanel(placeholder: String, onChosen: (StartLocation) -> Unit) {
    val context = LocalContext.current
    val api = remember(context) { OfflinePostalSearchApi(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StartLocation>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchSequence by remember { mutableIntStateOf(0) }

    fun searchLocation() {
        val term = query.trim()
        if (term.length < 3) {
            message = "Enter a place name, address or 6-digit Singapore postal code."
            return
        }
        searchJob?.cancel()
        searchSequence++
        val activeSequence = searchSequence
        searchJob = scope.launch {
            searching = true
            message = ""
            results = emptyList()
            try {
                val response = api.search(term)
                results = parseLocationMatches(response)
                if (results.isEmpty()) message = "No matching Singapore locations. Try a postal code or more specific address."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = "Unable to search the local postal database. You can still choose on the map."
            } finally {
                if (activeSequence == searchSequence) searching = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                searchJob?.cancel()
                searchSequence++
                searching = false
                query = it
                results = emptyList()
                message = ""
            },
            label = { Text("Search location / postal code") },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { searchLocation() })
        )
        LocationGoldButton(if (searching) "Searching…" else "Search location", ::searchLocation,
            enabled = !searching && query.trim().length >= 3)
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PremiumGold)
        if (message.isNotBlank()) Text(message, color = LocationMuted,
            style = MaterialTheme.typography.bodySmall)
        if (results.isNotEmpty()) Text("Select a matching location:", color = PremiumGold,
            style = MaterialTheme.typography.titleSmall)
        results.forEach { result ->
            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, PremiumGold.copy(alpha = .5f), RoundedCornerShape(12.dp))
                    .clickable { onChosen(result) }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(result.displayName, color = Color.White,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (result.address.isNotBlank() && result.address != result.displayName)
                    Text(result.address, color = LocationMuted, style = MaterialTheme.typography.bodySmall)
                if (result.postalCode.isNotBlank())
                    Text("Singapore " + result.postalCode, color = PremiumGold,
                        style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun StartLocationPicker(vm: PlannerViewModel) {
    val context = LocalContext.current
    val choosingEnd = vm.locationPickerTarget == "END"
    val initial = remember(choosingEnd) {
        (if (choosingEnd) vm.selectedEndLocation.location else vm.selectedStartLocation)
            ?.takeIf { it.type != woodlandsStartLocation.type && it.isValid() }
    }
    var selected by remember { mutableStateOf(initial) }
    var lookingUpAddress by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf("") }

    fun choosePoint(point: GeoPoint) {
        val candidate = StartLocation("CUSTOM", point.latitude, point.longitude, "Custom Location")
        if (!candidate.isValid()) {
            validation = "Choose a location with valid coordinates."
            return
        }
        selected = candidate
        validation = ""
    }

    // Reverse-geocode map taps; searched locations already include address and postal information.
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
        Text(if (choosingEnd) "Choose your end location" else "Choose your start location", style = MaterialTheme.typography.headlineSmall)
        Text("Find a place by name, street address, or six-digit Singapore postal code.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LocationSearchPanel(if (choosingEnd) "End location or postal code" else "Start location or postal code") {
            selected = it
            validation = ""
        }
        Text("Or select a precise point on the map:", color = PremiumGold,
            style = MaterialTheme.typography.titleMedium)
        LocationMap(selected, initial ?: woodlandsStartLocation, ::choosePoint)
        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PremiumCard(Modifier.fillMaxWidth(), borderColor = PremiumGold.copy(alpha = .5f), containerColor = SGColors.Card) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Selected location", color = PremiumGold, style = MaterialTheme.typography.titleMedium)
                val point = selected
                if (point == null) Text("Tap the map to choose a location.", color = LocationMuted)
                else {
                    Text(point.displayName, color = Color.White, style = MaterialTheme.typography.titleLarge)
                    if (point.postalCode.isNotBlank())
                        Text("Singapore " + point.postalCode, color = LocationMuted)
                    Text(if (lookingUpAddress) "Finding the address…" else point.address.ifBlank {
                        "Map point selected. You can confirm this location."
                    }, color = LocationMuted, style = MaterialTheme.typography.bodyMedium)
                }
                Text(if (choosingEnd) "Your route will finish at this point after the final delivery."
                     else "Your next route will start at this point.",
                    color = LocationMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (validation.isNotBlank()) Text(validation, color = MaterialTheme.colorScheme.error)
        LocationGoldButton("Confirm Location", {
            selected?.takeIf { it.isValid() }?.let {
                vm.confirmPickedLocation(it)
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
            Text("The map could not be opened. Search for a place or postal code above.", Modifier.padding(20.dp))
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
    val lastFocused = remember(map) { mutableStateOf<Pair<Double, Double>?>(null) }
    AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().height(340.dp), update = { view ->
        if (selected == null) view.overlays.remove(marker)
        else {
            marker.position = GeoPoint(selected.latitude, selected.longitude)
            val locationPair = selected.latitude to selected.longitude
            if (lastFocused.value != locationPair) {
                view.controller.animateTo(marker.position)
                lastFocused.value = locationPair
            }
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
