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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(color = PremiumNavy.copy(alpha = 0.96f), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("SG Delivery GPS Pro", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Surface(color = PremiumGold, shape = RoundedCornerShape(14.dp)) {
                                Text("GPS NAV", color = PremiumNavy, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                        }
                    }

                    Surface(color = PremiumNavy.copy(alpha = 0.97f), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (session.arrived) "✓" else NavigationDisplayTuning.maneuverArrow(session.instruction),
                                color = Color.White, fontSize = 37.sp, fontWeight = FontWeight.Bold)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val nextTurn = session.turnMeters?.takeIf { !session.arrived }
                                if (nextTurn != null) Text("In ${metricDistance(nextTurn)}", color = PremiumGold,
                                    fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(if (session.arrived) "Arrived" else session.instruction,
                                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                session.street?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = Color(0xFFB9C9E0), fontSize = 13.sp, maxLines = 1)
                                }
                            }
                            TextButton(onClick = { session.toggleMute() }) {
                                Text(if (session.muted) "Unmute" else "Mute", color = PremiumGold)
                            }
                        }
                    }
                    if (session.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = PremiumGold)
                    session.message?.let { message ->
                        Surface(color = PremiumNavy.copy(alpha = 0.95f), shape = RoundedCornerShape(10.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(message, Modifier.weight(1f), color = Color(0xFFFFD492), fontSize = 13.sp)
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { session.cameraMode = "follow"; session.cameraRequest++ },
                        modifier = Modifier.size(54.dp), contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = PremiumNavy)) {
                        Text("◎", color = PremiumGold, fontSize = 29.sp)
                    }
                    FilledTonalButton(onClick = { session.cameraMode = "overview"; session.cameraRequest++ },
                        modifier = Modifier.size(54.dp), contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = PremiumNavy)) {
                        Text("▱", color = PremiumGold, fontSize = 27.sp)
                    }
                    FilledTonalButton(onClick = { showCameras = true },
                        modifier = Modifier.size(54.dp), contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = PremiumNavy)) {
                        Text("⌖", color = PremiumGold, fontSize = 27.sp)
                    }
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("© OpenStreetMap contributors · OpenFreeMap", color = Color.White, fontSize = 10.sp,
                        modifier = Modifier.background(PremiumNavy.copy(alpha = 0.85f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp))
                    Surface(color = PremiumNavy.copy(alpha = 0.97f), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            val remaining = session.remainingSeconds
                            val eta = if (remaining != null) Instant.ofEpochMilli(
                                navigationEta(System.currentTimeMillis(), remaining))
                                .atZone(ZoneId.of("Asia/Singapore"))
                                .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) else "--:--"
                            Column { Text("ETA", color = Color(0xFFB9C9E0), fontSize = 12.sp)
                                Text(eta, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                            Column { Text(session.remainingMeters?.let { metricDistance(it) } ?: "-- km",
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(remaining?.let { duration(it) } ?: "-- min", color = Color(0xFFB9C9E0), fontSize = 13.sp) }
                            Text(stop.postal, color = PremiumGold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                    Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PremiumGold, contentColor = PremiumNavy),
                        shape = RoundedCornerShape(14.dp)) {
                        Text("Back to Delivery", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (showCameras) TrafficCameraDialog(session) { showCameras = false }
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
    var muted by mutableStateOf(false); private set
    var cameraMode by mutableStateOf("follow")
    var cameraRequest by mutableIntStateOf(0)
    var mapMessage by mutableStateOf<String?>(null)
    val trafficCameras = TrafficCameraClient()
    private val adaptiveEta = AdaptiveNavigationEta()
    private var baseRemainingSeconds: Double? = null
    private val feed = SessionLocationEngine()
    private val sdk = AndroidMapLibreNavigation(context, locationEngine = feed, options = MapLibreNavigationOptions(
        defaultMilestonesEnabled = false, enableOffRouteDetection = false, enableFasterRouteDetection = false,
        manuallyEndNavigationUponCompletion = true, isDebugLoggingEnabled = false
    ))
    private val client = ValhallaClient()
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
                if (arrival.update(navigationDistance(point, GpsPoint(destination.lat, destination.lon)), fix.accuracy, remainingMeters)) {
                    arrived = true; instruction = "Arrived"; remainingMeters = 0.0; baseRemainingSeconds = 0.0; remainingSeconds = 0.0
                    requestJob?.cancel(); sdk.stopNavigation(); speak("You have arrived at the destination.", "arrival"); return@collect
                }
                if (route == null && !initialAttempt) { initialAttempt = true; requestRoute(fix) }
                else if (route != null && !loading && reroute.shouldReroute(distanceFromNavigationRoute(point, shape), fix.accuracy, now)) requestRoute(fix)
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
                val newRoute = client.route(GpsPoint(fix.latitude, fix.longitude), GpsPoint(destination.lat, destination.lon))
                if (!active || arrived) return@launch
                shape = decodeNavigationGeometry(newRoute.geometry); route = newRoute
                adaptiveEta.reset(); baseRemainingSeconds = newRoute.duration
                remainingMeters = newRoute.distance; remainingSeconds = newRoute.duration
                instruction = "Follow the route"; voiceGate.reset(); sdk.startNavigation(newRoute)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { reroute.failed(SystemClock.elapsedRealtime()); message = ROUTE_UNAVAILABLE }
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
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000).setMinUpdateIntervalMillis(1_000)
        .setMaxUpdateDelayMillis(2_000).setWaitForAccurateLocation(true).build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach { trySend(it) } }
    }
    try { client.requestLocationUpdates(request, callback, Looper.getMainLooper()).addOnFailureListener { close(it) } }
    catch (e: SecurityException) { close(e) }
    awaitClose { client.removeLocationUpdates(callback) }
}
