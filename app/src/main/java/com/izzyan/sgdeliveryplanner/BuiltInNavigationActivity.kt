package com.izzyan.sgdeliveryplanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import org.maplibre.android.MapLibre
import org.maplibre.navigation.core.location.engine.LocationEngine
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.navigation.AndroidMapLibreNavigation
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions
import org.maplibre.navigation.core.routeprogress.RouteProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal fun builtInNavigationIntent(context: Context, plan: Plan): Intent? = navigationDestination(plan)?.let { stop ->
    Intent(context, BuiltInNavigationActivity::class.java)
        .putExtra("postal", stop.postal).putExtra("address", stop.address)
        .putExtra("lat", stop.lat).putExtra("lon", stop.lon)
}

/** Foreground-only navigation. This activity has no access to delivery-status or schedule writes. */
class BuiltInNavigationActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(englishAppContext(newBase))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val postal = intent.getStringExtra("postal") ?: run { finish(); return }
        val stop = Place(postal, intent.getDoubleExtra("lat", Double.NaN), intent.getDoubleExtra("lon", Double.NaN), "", "", intent.getStringExtra("address").orEmpty())
        MapLibre.getInstance(this)
        setContent {
            IzzDeliveryTheme(true) {
                NavigationContent(stop)
            }
        }
    }

    @Composable
    private fun NavigationContent(stop: Place) {
        val session = remember { NavigationSession(this@BuiltInNavigationActivity, stop) }
        var permitted by remember { mutableStateOf(hasPreciseLocation()) }
        var retry by remember { mutableIntStateOf(0) }
        var requestedPermission by remember { mutableStateOf(false) }
        var showCameras by remember { mutableStateOf(false) }
        var showFuelStations by remember { mutableStateOf(false) }
        var showLocalTrafficConsent by remember { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permitted = hasPreciseLocation(); requestedPermission = true
        }
        fun requestPermission() = permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        LaunchedEffect(Unit) { if (!permitted) requestPermission() }
        DisposableEffect(session) { onDispose { session.close() } }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) permitted = hasPreciseLocation()
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(permitted, retry) {
            if (permitted) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                try { session.run() } finally { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
        Scaffold(containerColor = PremiumNavy) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // Full-screen vector map: UI panels overlay the road view without shrinking it.
                NavigationMap(session, Modifier.matchParentSize(), permitted)

                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(color = SGColors.Background, shape = RoundedCornerShape(SGDimensions.RadiusCard),
                        border = BorderStroke(1.dp, SGColors.Gold.copy(alpha = 0.80f))) {
                        Column(Modifier.fillMaxWidth().padding(start = 13.dp, end = 7.dp, top = 5.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("SG Delivery GPS Pro", color = SGColors.Gold, fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold)
                                    Text(session.routingSource, color = SGColors.Silver, fontSize = 10.sp,
                                        maxLines = 1)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { session.toggleDimension() },
                                        contentPadding = PaddingValues(horizontal = 5.dp)) {
                                        Text(if (session.threeDimensional) "3D" else "2D",
                                            color = Color.White, fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp)
                                    }
                                    IconButton(onClick = { session.toggleMute() }, modifier = Modifier.size(38.dp)) {
                                        Icon(if (session.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                            contentDescription = if (session.muted) "Enable voice directions" else "Mute voice directions",
                                            tint = Color.White)
                                    }
                                    IconButton(onClick = { showCameras = true }, modifier = Modifier.size(38.dp)) {
                                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Traffic cameras", tint = Color.White)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(if (session.arrived) "✓" else NavigationDisplayTuning.maneuverArrow(session.instruction),
                                    color = SGColors.Gold, fontSize = 52.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(56.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(if (session.arrived) "Arrived" else session.turnMeters?.let { metricDistance(it) } ?: "GPS",
                                        color = SGColors.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                                        maxLines = 1)
                                    Text(if (session.arrived) "Destination reached" else
                                        NavigationDisplayTuning.nextRoadLabel(session.instruction, session.street),
                                        color = SGColors.White, fontSize = 16.sp, maxLines = 2,
                                        lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                                }
                                NavigationDisplayTuning.roundaboutExit(session.instruction)?.let { exit ->
                                    Text(exit, color = SGColors.Gold, fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (session.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PremiumGold)
                    session.message?.let { message ->
                        Surface(color = PremiumNavy.copy(alpha = 0.95f), shape = RoundedCornerShape(10.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(message, Modifier.weight(1f), color = SGColors.Warning, fontSize = 13.sp)
                                if (!session.loading && !session.arrived)
                                    TextButton(onClick = { retry++ }) { Text("Retry", color = PremiumGold) }
                            }
                        }
                    }
                }

                if (!permitted) {
                    Surface(Modifier.align(Alignment.Center).padding(16.dp), color = PremiumNavy,
                        shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (requestedPermission) "Precise GPS permission is needed. Allow location in Android settings if blocked."
                                else "Allow precise location to start 3D navigation.", color = Color.White)
                            Button(onClick = { requestPermission() }, modifier = Modifier.fillMaxWidth()) { Text("Allow location / Retry") }
                            if (requestedPermission) TextButton(onClick = {
                                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:$packageName")))
                            }) { Text("Open app settings") }
                        }
                    }
                }

                Column(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    GpsControl(Icons.Filled.MyLocation, "Follow", session.cameraMode == "follow") {
                        session.recenter()
                    }
                    GpsControl(Icons.Filled.Map, "Route", session.cameraMode == "overview") {
                        session.cameraMode = "overview"; session.cameraRequest++
                    }
                    GpsControl(Icons.Filled.Add, "Zoom +") { session.adjustZoom(0.5) }
                    GpsControl(Icons.Filled.Remove, "Zoom −") { session.adjustZoom(-0.5) }
                    GpsControl(Icons.Filled.Traffic, "Traffic", session.localTrafficVisible) {
                        if (session.localTrafficVisible) session.toggleLocalTraffic()
                        else showLocalTrafficConsent = true
                    }
                }

                Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 143.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { showFuelStations = true },
                        enabled = permitted && session.location != null,
                        modifier = Modifier.width(74.dp).height(70.dp),
                        contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(SGDimensions.RadiusSmall),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = PremiumGold, contentColor = PremiumNavy)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(Icons.Filled.LocalGasStation, contentDescription = "Nearby petrol stations",
                                modifier = Modifier.size(27.dp))
                            Text("Petrol", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    session.location?.takeIf { it.hasSpeed() }?.let { fix ->
                        Surface(color = PremiumNavy.copy(alpha = 0.96f),
                            shape = RoundedCornerShape(SGDimensions.RadiusSmall)) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text((fix.speed.coerceAtLeast(0f) * 3.6f).roundToInt().toString(),
                                    color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                Text("km/h", color = SGColors.Silver, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("© OpenStreetMap contributors · OpenFreeMap", color = Color.White, fontSize = 10.sp,
                        modifier = Modifier.background(PremiumNavy.copy(alpha = 0.85f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp))
                    Surface(color = PremiumNavy.copy(alpha = 0.97f), shape = RoundedCornerShape(SGDimensions.RadiusCard)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            val remaining = session.remainingSeconds
                            val eta = if (remaining != null) Instant.ofEpochMilli(
                                navigationEta(System.currentTimeMillis(), remaining))
                                .atZone(ZoneId.of("Asia/Singapore"))
                                .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) else "--:--"
                            Column(Modifier.weight(1f)) {
                                Text("ARRIVAL", color = SGColors.Silver, fontSize = 11.sp)
                                Text(eta, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 29.sp)
                                Text("${session.remainingMeters?.let { metricDistance(it) } ?: "-- km"}  ·  ${remaining?.let { duration(it) } ?: "-- min"}",
                                    color = SGColors.Silver, fontSize = 14.sp)
                                if (session.localTrafficVisible) {
                                    val level = session.trafficState.ahead
                                    val label = when (level) {
                                        LocalTrafficLevel.SMOOTH -> "Local traffic estimate: Smooth"
                                        LocalTrafficLevel.SLOW -> "Local traffic estimate: Slow"
                                        LocalTrafficLevel.JAMMED -> "Local traffic estimate: Jammed"
                                        LocalTrafficLevel.UNKNOWN -> "Local traffic: insufficient GPS data"
                                    }
                                    Text(label, color = SGColors.Silver, fontSize = 11.sp)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stop.postal, color = SGColors.SelectedGold,
                                    fontWeight = FontWeight.Bold, fontSize = 26.sp,
                                    letterSpacing = 0.8.sp, maxLines = 1, softWrap = false,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.widthIn(min = 114.dp)
                                        .align(Alignment.CenterHorizontally))
                                FilledTonalButton(onClick = { finish() },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = SGColors.SecondaryBackground, contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("Delivery", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showCameras) TrafficCameraDialog(session) { showCameras = false }
        if (showFuelStations) FuelStationDialog(session) { showFuelStations = false }
        if (showLocalTrafficConsent) {
            AlertDialog(
                onDismissRequest = { showLocalTrafficConsent = false },
                title = { Text("Local traffic estimates") },
                text = { Text("Use your phone's driving GPS speed to estimate traffic on roads you travel. " +
                    "Measurements remain on this phone only, are not uploaded, and are not " +
                    "Singapore-wide live traffic. Unknown roads remain uncolored. " +
                    "Stopping for deliveries is excluded.") },
                confirmButton = {
                    TextButton(onClick = {
                        session.toggleLocalTraffic()
                        showLocalTrafficConsent = false
                    }) { Text("Enable local estimates") }
                },
                dismissButton = {
                    TextButton(onClick = { showLocalTrafficConsent = false }) { Text("Cancel") }
                }
            )
        }
    }

    @Composable
    private fun GpsControl(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.width(65.dp).height(61.dp),
            contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(SGDimensions.RadiusSmall),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = if (active) PremiumGold else PremiumNavy.copy(alpha = 0.96f),
                contentColor = if (active) PremiumNavy else Color.White)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(25.dp))
                Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    private fun hasPreciseLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

internal fun metricDistance(meters: Double): String = if (meters < 1000) "${meters.coerceAtLeast(0.0).toInt()} m" else String.format(Locale.ENGLISH, "%.1f km", meters / 1000)

private class SessionLocationEngine : LocationEngine {
    val updates = MutableSharedFlow<org.maplibre.navigation.core.location.Location>(replay = 1, extraBufferCapacity = 2)
    override fun listenToLocation(request: LocationEngine.Request) = updates.asSharedFlow()
    override suspend fun getLastLocation() = updates.replayCache.lastOrNull()
}

internal class NavigationSession(private val context: Context, initialDestination: Place) : AutoCloseable {
    var destination by mutableStateOf(initialDestination); private set
    var route by mutableStateOf<DirectionsRoute?>(null); private set
    var shape: List<GpsPoint> = emptyList(); private set
    var location by mutableStateOf<Location?>(null); private set
    var instruction by mutableStateOf("Waiting for GPS…"); private set
    var turnMeters by mutableStateOf<Double?>(null); private set
    var remainingMeters by mutableStateOf<Double?>(null); private set
    var remainingSeconds by mutableStateOf<Double?>(null); private set
    var street by mutableStateOf<String?>(null); private set
    var arrived by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var routingSource by mutableStateOf("Not connected"); private set
    var muted by mutableStateOf(false); private set
    var cameraMode by mutableStateOf("follow")
    var cameraRequest by mutableIntStateOf(0)
    var zoomOverride by mutableStateOf<Double?>(null); private set
    private val gpsModePreferences = GpsNavigationModePreferences(context)
    var threeDimensional by mutableStateOf(gpsModePreferences.is3dEnabled()); private set
    var localTrafficVisible by mutableStateOf(false); private set
    var trafficState by mutableStateOf(LocalTrafficState.EMPTY); private set
    fun toggleDimension() {
        threeDimensional = !threeDimensional
        gpsModePreferences.set3dEnabled(threeDimensional)
        cameraMode = "follow"
        cameraRequest++
    }
    fun toggleLocalTraffic() {
        localTrafficVisible = !localTrafficVisible
        // Delete any in-memory traffic observations whenever consent changes.
        localTraffic.reset(shape, route?.distance ?: 0.0, route?.duration ?: 0.0)
        trafficState = LocalTrafficState.EMPTY
    }
    var mapMessage by mutableStateOf<String?>(null)
    var fuelPreview by mutableStateOf<FuelStation?>(null); private set
    val fuelStations = FuelStationClient()

    fun recenter() {
        fuelPreview = null
        zoomOverride = null
        cameraMode = "follow"
        cameraRequest++
    }

    fun previewFuelStation(station: FuelStation) {
        fuelPreview = station
        cameraMode = "fuel"
        cameraRequest++
    }

    fun adjustZoom(step: Double) {
        val baseline = zoomOverride ?: NavigationDisplayTuning.zoomForSpeed(
            location?.speed?.takeIf { location?.hasSpeed() == true })
        zoomOverride = NavigationDisplayTuning.adjustedZoom(baseline, step)
        cameraMode = "follow"
        cameraRequest++
    }
    val trafficCameras = TrafficCameraClient()
    private val adaptiveEta = AdaptiveNavigationEta()
    private val localTraffic = LocalNavigationTraffic()
    private var baseRemainingSeconds: Double? = null
    private val feed = SessionLocationEngine()
    private val sdk = AndroidMapLibreNavigation(context, locationEngine = feed, options = MapLibreNavigationOptions(
        defaultMilestonesEnabled = false, enableOffRouteDetection = false, enableFasterRouteDetection = false,
        manuallyEndNavigationUponCompletion = true, isDebugLoggingEnabled = false
    ))
    private val client = ValhallaClient()
    private val offlineRoads = OfflineRoadPackageStore(context.applicationContext)
    private val reroute = ReroutePolicy()
    private var arrival = ArrivalDetector()
    private val voiceGate = VoiceCueGate()
    private val main = android.os.Handler(Looper.getMainLooper())
    private var active = false
    private var speechReady = false
    private var speech: TextToSpeech? = null
    private var requestJob: Job? = null
    private var lastFix = 0L
    private var scope: CoroutineScope? = null

    init {
        sdk.addProgressChangeListener { _, p -> main.post { if (active && !arrived && p.directionsRoute == route) updateProgress(p) } }
        speech = TextToSpeech(context) { status -> main.post {
            if (status == TextToSpeech.SUCCESS) {
                val result = speech?.setLanguage(Locale.US)
                speechReady = result != null && result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                speech?.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                if (!speechReady) message = "English voice unavailable. Follow the on-screen instructions."
            } else message = "Voice unavailable. Follow the on-screen instructions."
        } }
    }

    suspend fun run(): Unit = coroutineScope {
        active = true; scope = this; adaptiveEta.reset(); arrival = ArrivalDetector(); reroute.resetDrift(); voiceGate.reset()
        try {
            if (!GpsPoint(destination.lat, destination.lon).valid()) {
                message = "Resolving destination…"
                destination = Repository(context.applicationContext as android.app.Application).resolve(destination.postal)
                require(GpsPoint(destination.lat, destination.lon).valid())
            }
            route?.takeIf { !arrived }?.let { sdk.startNavigation(it) }
            launch {
                while (isActive) {
                    delay(5_000)
                    if (!arrived && (lastFix == 0L || SystemClock.elapsedRealtime() - lastFix > 15_000)) {
                        message = "GPS signal unavailable. Enable location and move to an open area."
                        reroute.resetDrift(); arrival = ArrivalDetector(); adaptiveEta.reset(); remainingSeconds = baseRemainingSeconds; speech?.stop()
                    }
                }
            }
            var initialAttempt = false
            fusedLocations(context).collect { fix ->
                val now = SystemClock.elapsedRealtime()
                if (!GpsPoint(fix.latitude, fix.longitude).valid() || now - fix.elapsedRealtimeNanos / 1_000_000 > 15_000 || !fix.hasAccuracy() || !fix.accuracy.isFinite() || fix.accuracy < 0 || fix.accuracy > 50) {
                    message = "GPS accuracy is low. Waiting for a precise location…"
                    reroute.resetDrift(); arrival = ArrivalDetector(); return@collect
                }
                lastFix = now; location = fix
                adaptiveEta.observe(if (fix.hasSpeed()) fix.speed.toDouble() else null, fix.accuracy.toDouble(), fix.elapsedRealtimeNanos / 1_000_000,
                    if (fix.hasSpeedAccuracy()) fix.speedAccuracyMetersPerSecond.toDouble() else null)
                baseRemainingSeconds?.let { base -> remainingSeconds = adaptiveEta.remainingSeconds(base, remainingMeters ?: 0.0, now) }
                if (message?.startsWith("GPS") == true) message = null
                if (arrived) return@collect
                val point = GpsPoint(fix.latitude, fix.longitude)
                if (localTrafficVisible && shape.size >= 2) {
                    val matched = NavigationRoadMatcher.match(point, shape, fix.accuracy,
                        fix.speed.takeIf { fix.hasSpeed() }, fix.bearing.takeIf { fix.hasBearing() })
                    localTraffic.observe(matched?.segmentIndex,
                        fix.speed.takeIf { fix.hasSpeed() }, fix.accuracy, now,
                        navigationDistance(point, GpsPoint(destination.lat, destination.lon)),
                        driving = active && !arrived && !loading)
                    val updatedTraffic = localTraffic.snapshot(now, matched?.segmentIndex)
                    if (updatedTraffic != trafficState) trafficState = updatedTraffic
                }
                if (arrival.update(navigationDistance(point, GpsPoint(destination.lat, destination.lon)), fix.accuracy, remainingMeters)) {
                    arrived = true; instruction = "Arrived"; remainingMeters = 0.0; baseRemainingSeconds = 0.0; remainingSeconds = 0.0
                    requestJob?.cancel(); sdk.stopNavigation(); speak("You have arrived at the destination.", "arrival"); return@collect
                }
                if (route == null && !initialAttempt) { initialAttempt = true; requestRoute(fix) }
                else if (route != null && !loading) {
                    val routeDistance = distanceFromNavigationRoute(point, shape)
                    val nearest = NavigationRoadMatcher.match(point, shape, fix.accuracy, null, null)
                    val headingDifference = if (fix.hasSpeed() && fix.speed >= 4f &&
                        fix.hasBearing() && nearest != null)
                        NavigationRoadMatcher.angleDifference(fix.bearing, nearest.bearingDegrees)
                    else null
                    if (reroute.shouldReroute(routeDistance, fix.accuracy, now,
                            headingDifference, fix.speed.takeIf { fix.hasSpeed() }))
                        requestRoute(fix)
                }
                feed.updates.emit(org.maplibre.navigation.core.location.Location(
                    latitude = fix.latitude, longitude = fix.longitude, accuracyMeters = fix.accuracy,
                    speedMetersPerSeconds = if (fix.hasSpeed()) fix.speed else null,
                    bearing = if (fix.hasBearing()) fix.bearing else null, timeMilliseconds = fix.time, provider = fix.provider
                ))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "GPS or destination unavailable. Please retry." }
        finally { active = false; requestJob?.cancel(); requestJob = null; loading = false; sdk.stopNavigation(); speech?.stop(); scope = null }
    }

    private fun requestRoute(fix: Location) {
        if (loading || !reroute.requestAllowed(SystemClock.elapsedRealtime())) {
            if (route == null) message = "Please wait briefly before retrying the route."
            return
        }
        reroute.requested(SystemClock.elapsedRealtime()); loading = true; message = null
        requestJob = scope?.launch {
            try {
                val origin = GpsPoint(fix.latitude, fix.longitude)
                val target = GpsPoint(destination.lat, destination.lon)
                val installed = offlineRoads.loadOrNull()
                val localPreferred = installed != null && !hasUsableInternet(context)
                val newRoute = if (localPreferred) {
                    routingSource = "OFFLINE – local roads (map tiles need internet)"
                    withContext(Dispatchers.Default) {
                        offlineDrivingDirections(installed!!.route(origin, target))
                    }
                } else {
                    try {
                        client.route(origin, target).also { routingSource = "ONLINE – network routing" }
                    } catch (e: CancellationException) { throw e }
                      catch (e: Exception) {
                        if (installed == null) throw e
                        routingSource = "OFFLINE – local roads (map tiles need internet)"
                        withContext(Dispatchers.Default) {
                            offlineDrivingDirections(installed.route(origin, target))
                        }
                    }
                }
                if (!active || arrived) return@launch
                shape = decodeNavigationGeometry(newRoute.geometry)
                localTraffic.reset(shape, newRoute.distance, newRoute.duration)
                trafficState = LocalTrafficState.EMPTY
                route = newRoute
                adaptiveEta.reset(); baseRemainingSeconds = newRoute.duration
                remainingMeters = newRoute.distance; remainingSeconds = newRoute.duration
                instruction = "Follow the route"; voiceGate.reset(); sdk.startNavigation(newRoute)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                reroute.failed(SystemClock.elapsedRealtime())
                message = if (!hasUsableInternet(context))
                    "No offline route available. Install a Singapore driving-road graph in Settings."
                else ROUTE_UNAVAILABLE
            }
            finally { loading = false }
        }
    }

    private fun updateProgress(p: RouteProgress) {
        remainingMeters = p.distanceRemaining.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        baseRemainingSeconds = p.durationRemaining.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        remainingSeconds = adaptiveEta.remainingSeconds(baseRemainingSeconds!!, remainingMeters!!, SystemClock.elapsedRealtime())
        turnMeters = p.stepDistanceRemaining.coerceAtLeast(0.0)
        val current = p.currentLegProgress.currentStepProgress
        val next = current.nextStep ?: current.step
        street = current.step.name
        instruction = next.maneuver.instruction?.takeIf { it.isNotBlank() }
            ?: current.step.bannerInstructions?.firstOrNull()?.primary?.text
            ?: listOfNotNull(next.maneuver.type?.toString()?.replace('_', ' '), next.maneuver.modifier?.toString()?.replace('_', ' ')).joinToString(" ").ifBlank { "Continue on the route" }
        if (SystemClock.elapsedRealtime() - lastFix < 15_000 && !muted && speechReady) {
            val cue = current.step.voiceInstructions?.withIndex()?.lastOrNull {
                turnMeters!! <= it.value.distanceAlongGeometry + 5 && !it.value.announcement.isNullOrBlank()
            }
            if (cue != null && voiceGate.acceptCue(p.stepIndex, cue.index, cue.value.announcement!!)) {
                speak(cue.value.announcement!!, "step-${p.stepIndex}-${cue.index}")
            } else if (current.step.voiceInstructions.isNullOrEmpty() && voiceGate.accept(p.stepIndex, instruction, turnMeters!!)) {
                speak("In ${metricDistance(turnMeters!!)}, $instruction", "step-${p.stepIndex}")
            }
        }
    }
    fun toggleMute() { muted = !muted; if (muted) speech?.stop() else voiceGate.reset() }
    private fun speak(text: String, id: String) { if (active && speechReady && !muted) speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) }
    override fun close() { active = false; requestJob?.cancel(); sdk.onDestroy(); speech?.stop(); speech?.shutdown(); speech = null }
}

@SuppressLint("MissingPermission") // Called only after fine-location runtime permission is granted.
private fun fusedLocations(context: Context): Flow<Location> = kotlinx.coroutines.flow.callbackFlow {
    val client = LocationServices.getFusedLocationProviderClient(context)
    // Foreground navigation only: request shorter, non-batched fixes for smoother puck animation.
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
        .setMinUpdateIntervalMillis(750).setMaxUpdateDelayMillis(1_000)
        .setWaitForAccurateLocation(true).build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach { trySend(it) } }
    }
    try { client.requestLocationUpdates(request, callback, Looper.getMainLooper()).addOnFailureListener { close(it) } }
    catch (e: SecurityException) { close(e) }
    awaitClose { client.removeLocationUpdates(callback) }
}
