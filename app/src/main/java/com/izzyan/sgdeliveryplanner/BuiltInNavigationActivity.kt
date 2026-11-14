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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

internal fun builtInNavigationIntent(context: Context, plan: Plan): Intent? =
    navigationDestination(plan)?.let { builtInNavigationIntent(context, it) }

/** Single destination snapshot; the activity gets its origin from live device GPS. */
internal fun builtInNavigationIntent(context: Context, stop: Place): Intent =
    Intent(context, BuiltInNavigationActivity::class.java)
        .putExtra("postal", stop.postal).putExtra("address", stop.address)
        .putExtra("lat", stop.lat).putExtra("lon", stop.lon)

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
        var showLocalTrafficConsent by remember { mutableStateOf(false) }
        var showGpsSettings by remember { mutableStateOf(false) }
        val mapAppearanceStore = remember {
            NavigationMapAppearanceStore(this@BuiltInNavigationActivity)
        }
        var mapAppearance by remember { mutableStateOf(mapAppearanceStore.read()) }
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
        val displayedRoute=session.route
        // Log when Compose commits the new direction state. This is not a GPU frame timestamp.
        SideEffect { if (!session.offRoute) session.directionRendered(displayedRoute) }
        val navigationScreenWidth = LocalConfiguration.current.screenWidthDp
        val compactNavHeader = navigationScreenWidth < 385
        // Measure the real opaque panels, including wrapping road names and
        // accessibility font scales. Controls fit between them on small phones.
        var topPanelHeightPx by remember { mutableIntStateOf(0) }
        var bottomPanelHeightPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val topInset = if (topPanelHeightPx > 0)
            with(density) { topPanelHeightPx.toDp() } + 6.dp else 156.dp
        val bottomInset = if (bottomPanelHeightPx > 0)
            with(density) { bottomPanelHeightPx.toDp() } + 6.dp else 162.dp
        Scaffold(containerColor = PremiumNavy) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // Full-screen vector map: UI panels overlay the road view without shrinking it.
                NavigationMap(session, Modifier.matchParentSize(), permitted, mapAppearance)

                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .onSizeChanged { topPanelHeightPx = it.height }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(color = PremiumNavy, shape = RoundedCornerShape(SGDimensions.RadiusCard),
                        border = BorderStroke(1.dp, SGColors.Gold.copy(alpha = 0.80f))) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            val cue = if (session.arrived) NavigationPanelCue(NavigationSign.ARRIVE) else session.panelCue
                            Image(
                                painter = painterResource(cue.sign.drawable),
                                contentDescription = if (cue.sign == NavigationSign.UTURN)
                                    if (cue.mirrorHorizontally) "Left U-turn" else "Right U-turn"
                                else cue.sign.name.lowercase(Locale.ROOT).replace('_', ' '),
                                modifier = Modifier.size(NavigationPanelSizing.directionIconDp(navigationScreenWidth).dp)
                                    .graphicsLayer { scaleX = if (cue.mirrorHorizontally) -1f else 1f },
                                contentScale = ContentScale.Fit
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val exitMaxWidth = maxWidth * NavigationPanelSizing.EXIT_MAX_WIDTH_FRACTION
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        BoxWithConstraints(Modifier.weight(1f)) {
                                            val distance = if (session.arrived) "Arrived" else if(session.offRoute) "—" else session.turnMeters?.let { metricDistance(it) } ?: "GPS"
                                            val preferred = if (compactNavHeader) 32f else 38f
                                            val measure = rememberTextMeasurer()
                                            val available = with(LocalDensity.current) { maxWidth.toPx() }
                                            val measured = measure.measure(AnnotatedString(distance),
                                                style = TextStyle(fontSize = preferred.sp, fontWeight = FontWeight.Bold),
                                                maxLines = 1, softWrap = false).size.width
                                            val font = NavigationPanelSizing.fitTextSp(preferred, available, measured)
                                            Text(distance, color = SGColors.White, fontSize = font.sp,
                                                lineHeight = (font * 1.18f).sp,
                                                fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                        }
                                        cue.expresswayExit?.let {
                                            ExitRoadSign(it, Modifier.widthIn(max = exitMaxWidth))
                                        }
                                    }
                                }
                                Text(if (session.arrived) "Destination reached" else
                                    NavigationDisplayTuning.nextRoadLabel(session.instruction, session.street),
                                    color = SGColors.White,
                                    fontSize = if (compactNavHeader) 19.sp else 22.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    lineHeight = if (compactNavHeader) 22.sp else 26.sp,
                                    fontWeight = FontWeight.SemiBold)
                                session.street?.takeIf {
                                    !session.arrived && it.isNotBlank() && !NavigationDisplayTuning.nextRoadLabel(
                                        session.instruction, null).equals(it, ignoreCase = true)
                                }?.let { currentStreet ->
                                    Text("Current: $currentStreet", color = SGColors.Silver,
                                        fontSize = if (compactNavHeader) 12.sp else 14.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                cue.roundaboutExit?.let { exit ->
                                    Text("Roundabout exit $exit", color = SGColors.Gold,
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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

                // Controls occupy the middle map area and scroll on shorter phones.
                Column(
                    Modifier.align(Alignment.TopEnd).fillMaxHeight()
                        .padding(top = topInset, bottom = bottomInset, end = 10.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
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
                    GpsControl(Icons.Filled.Settings, "Settings") { showGpsSettings = true }
                }

                Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = bottomInset),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    session.location?.takeIf { it.hasSpeed() }?.let { fix ->
                        Surface(color = SGColors.Card,
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

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onSizeChanged { bottomPanelHeightPx = it.height }.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("© OpenStreetMap contributors · OpenFreeMap", color = Color.White, fontSize = 10.sp,
                        modifier = Modifier.background(PremiumNavy.copy(alpha = 0.85f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp))
                    Surface(color = PremiumNavy, shape = RoundedCornerShape(SGDimensions.RadiusCard)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            val remaining = session.remainingSeconds
                            val eta = if (remaining != null) Instant.ofEpochMilli(
                                navigationEta(System.currentTimeMillis(), remaining))
                                .atZone(ZoneId.of("Asia/Singapore"))
                                .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) else "--:--"
                            Column(Modifier.weight(1f)) {
                                Text("ARRIVAL", color = SGColors.Silver, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                                Text(eta, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 29.sp)
                                NavigationRemainingSummary("${session.remainingMeters?.let { metricDistance(it) } ?: "-- km"}  ·  ${remaining?.let { duration(it) } ?: "-- min"}")
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
                                    modifier = Modifier.widthIn(min = if (compactNavHeader) 100.dp else 114.dp)
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
        if (showGpsSettings) {
            AlertDialog(
                onDismissRequest = { showGpsSettings = false },
                containerColor = PremiumNavy,
                title = {
                    Text("Navigation Settings", color = SGColors.Gold,
                        fontWeight = FontWeight.Bold, fontSize = 19.sp)
                },
                text = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        NavigationSettingsChoices(
                            "Map Display", listOf("2D", "3D"),
                            if (session.threeDimensional) "3D" else "2D"
                        ) { session.setDimension(it == "3D") }
                        NavigationVoiceSettings(session)
                        NavigationSettingsChoices(
                            "Display Mode", listOf("Day", "Night", "Auto Day/Night"),
                            when (mapAppearance) {
                                NavigationMapAppearance.DAY -> "Day"
                                NavigationMapAppearance.NIGHT -> "Night"
                                NavigationMapAppearance.AUTO -> "Auto Day/Night"
                            }
                        ) { selected ->
                            val choice = when (selected) {
                                "Day" -> NavigationMapAppearance.DAY
                                "Night" -> NavigationMapAppearance.NIGHT
                                else -> NavigationMapAppearance.AUTO
                            }
                            mapAppearanceStore.save(choice)
                            mapAppearance = choice
                        }
                        TextButton(onClick = {
                            showGpsSettings = false
                            showCameras = true
                        }) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Traffic cameras", color = Color.White)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showGpsSettings = false }) {
                        Text("Done", color = SGColors.Gold, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
        if (showCameras) TrafficCameraDialog(session) { showCameras = false }
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

    /** Chamfered yellow sign, shown only for a provider-supplied expressway exit. */
    @Composable
    private fun ExitRoadSign(number: String, modifier: Modifier = Modifier) {
        val shape = remember {
            GenericShape { size, _ ->
                val c = minOf(size.height * 0.30f, size.width * 0.12f)
                moveTo(c, 0f); lineTo(size.width - c, 0f); lineTo(size.width, c)
                lineTo(size.width, size.height - c); lineTo(size.width - c, size.height)
                lineTo(c, size.height); lineTo(0f, size.height - c); lineTo(0f, c); close()
            }
        }
        Surface(modifier = modifier, color = Color(0xFFFFD21F), shape = shape) {
            BoxWithConstraints(Modifier.padding(horizontal = 9.5.dp, vertical = 3.5.dp)) {
                val label = "EXIT $number"
                val preferred = NavigationPanelSizing.EXIT_FONT_SP
                val measure = rememberTextMeasurer()
                val available = with(LocalDensity.current) { maxWidth.toPx() }
                val measured = measure.measure(AnnotatedString(label),
                    style = TextStyle(fontSize = preferred.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1, softWrap = false).size.width
                val font = NavigationPanelSizing.fitTextSp(preferred, available, measured)
                Text(label, color = Color(0xFF111111), fontSize = font.sp,
                    lineHeight = (font * 1.5f).sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, softWrap = false)
            }
        }
    }

    /** Keep KM and duration on the same line, reducing type only when needed
     * on narrow displays or accessibility settings; never hide the duration. */
    @Composable
    private fun NavigationRemainingSummary(summary: String) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measure = rememberTextMeasurer()
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val preferred = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium)
            val measured = measure.measure(AnnotatedString(summary), style = preferred,
                maxLines = 1, softWrap = false).size.width
            val size = if (measured > 0 && widthPx < measured)
                (17f * widthPx / measured).coerceIn(13f, 17f).sp else 17.sp
            Text(summary, color = SGColors.Silver, fontSize = size,
                fontWeight = FontWeight.Medium, maxLines = 2)
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
    @Composable
    private fun NavigationSettingsChoices(
        title: String, options: List<String>, selected: String, onSelect: (String) -> Unit
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = SGColors.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                options.forEach { option ->
                    val active = option == selected
                    FilledTonalButton(
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f).height(39.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (active) PremiumGold else SGColors.SecondaryBackground,
                            contentColor = if (active) PremiumNavy else Color.White
                        )
                    ) {
                        Text(option, fontWeight = FontWeight.Bold,
                            fontSize = if (option.length > 8) 10.sp else 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
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
    var panelCue by mutableStateOf(NavigationPanelCue()); private set
    private var routeSignMetadata: List<RouteSignMetadata> = emptyList()
    var turnMeters by mutableStateOf<Double?>(null); private set
    var remainingMeters by mutableStateOf<Double?>(null); private set
    var remainingSeconds by mutableStateOf<Double?>(null); private set
    var street by mutableStateOf<String?>(null); private set
    var arrived by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    private val gpsModePreferences = GpsNavigationModePreferences(context)
    var muted by mutableStateOf(!gpsModePreferences.isSoundEnabled()); private set
    var cameraMode by mutableStateOf("follow")
    var cameraRequest by mutableIntStateOf(0)
    var zoomOverride by mutableStateOf<Double?>(null); private set
    var threeDimensional by mutableStateOf(gpsModePreferences.is3dEnabled()); private set
    var localTrafficVisible by mutableStateOf(false); private set
    var trafficState by mutableStateOf(LocalTrafficState.EMPTY); private set
    fun toggleDimension() = setDimension(!threeDimensional)
    fun setDimension(enabled: Boolean) {
        if (threeDimensional == enabled) return
        threeDimensional = enabled
        gpsModePreferences.set3dEnabled(enabled)
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
    private val reroute = ReroutePolicy()
    private val instantDetector = InstantRerouteDetector()
    private val requestGate = RerouteRequestGate()
    var offRoute by mutableStateOf(false); private set
    private var latestReliableFix: RerouteFix? = null
    private var latency: RerouteLatency? = null
    private var latencyRoute: DirectionsRoute? = null
    private data class RerouteLatency(val id: Long, val fix: Long, val detected: Long, val requested: Long,
        var response: Long = 0, var applied: Long = 0, var line: Long = 0, var direction: Long = 0)
    private fun recordLatency(event: String, at: Long, trace: RerouteLatency? = latency) {
        trace ?: return
        android.util.Log.d("SGReroute851", "id=${trace.id} phase=$event elapsedMs=$at sinceFixMs=${at-trace.fix}")
    }
    fun routeLineRendered(rendered: DirectionsRoute?) {
        val trace=latency ?: return
        if(rendered != null && rendered === latencyRoute && trace.line == 0L) {
            trace.line=SystemClock.elapsedRealtime(); recordLatency("line-submitted",trace.line,trace)
        }
    }
    fun directionRendered(rendered: DirectionsRoute?) {
        val trace=latency ?: return
        if(rendered != null && rendered === latencyRoute && trace.direction == 0L) {
            trace.direction=SystemClock.elapsedRealtime(); recordLatency("direction-composed",trace.direction,trace)
        }
    }
    private var arrival = ArrivalDetector()
    private val voiceGate = VoiceCueGate()
    private val gpsSanityFilter = NavigationGpsSanityFilter()
    private val main = android.os.Handler(Looper.getMainLooper())
    private var active = false
    private val voiceAudio = NavigationVoiceAudio(context)
    private val pandaGate = PandaVoiceGate()
    var voice by mutableStateOf(gpsModePreferences.voice()); private set
    var voiceVolume by mutableStateOf(gpsModePreferences.voiceVolume()); private set
    val pandaAvailable get() = voiceAudio.pack.available
    val previewingVoice get() = voiceAudio.previewing
    private var gpsWasLost=false
    private var startAnnounced=false
    private var approachAnnounced=false
    private var unavailableAnnounced=false
    fun selectVoice(value: NavigationVoice) {
        if(value==NavigationVoice.PANDA && !pandaAvailable) return
        if(voice==value) return
        voiceAudio.stop(); voice=value; gpsModePreferences.setVoice(value)
        pandaGate.reset(); voiceGate.reset()
    }
    fun changeVoiceVolume(value:Float) {
        voiceVolume=value.coerceIn(0f,1f);gpsModePreferences.setVoiceVolume(voiceVolume)
        voiceAudio.setVolume(voiceVolume)
    }
    fun previewVoice() {
        if(!active || muted || !pandaAvailable) return
        voiceAudio.play(VoicePhrase("preview", VoicePriority.PREVIEW,
            listOf(VoicePart("start_navigation","Alright, buddy! Adventure awaits! Let's roll!"))))
    }
    fun stopVoicePreview()=voiceAudio.stopPreview()
    private var requestJob: Job? = null
    private var lastFix = 0L
    private var scope: CoroutineScope? = null

    init {
        sdk.addProgressChangeListener { _, p -> main.post { if (active && !arrived && !offRoute && p.directionsRoute == route) updateProgress(p) } }
        voiceAudio.setVolume(voiceVolume)
    }

    suspend fun run(): Unit = coroutineScope {
        active = true; scope = this; adaptiveEta.reset(); arrival = ArrivalDetector(); reroute.resetDrift(); instantDetector.reset(); requestGate.clear(); voiceGate.reset(); pandaGate.reset(); gpsSanityFilter.reset()
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
                        reroute.resetDrift(); instantDetector.reset(); arrival = ArrivalDetector(); adaptiveEta.reset(); remainingSeconds = baseRemainingSeconds
                        if(!gpsWasLost) {
                            gpsWasLost=true; voiceAudio.stop()
                            pandaStatus("gps_lost","GPS signal lost. Keep following road signs.")
                        }
                    }
                }
            }
            fusedLocations(context).collect { fix ->
                val now = SystemClock.elapsedRealtime()
                if (!GpsPoint(fix.latitude, fix.longitude).valid() || now - fix.elapsedRealtimeNanos / 1_000_000 > 15_000 || !fix.hasAccuracy() || !fix.accuracy.isFinite() || fix.accuracy < 0 || fix.accuracy > 50) {
                    message = "GPS accuracy is low. Waiting for a precise location…"
                    reroute.resetDrift(); instantDetector.reset(); arrival = ArrivalDetector(); return@collect
                }
                // Accept raw GPS coordinates as truth only after rejecting an
                // impossible one-sample jump. Reacquisition after a gap is allowed.
                if (!gpsSanityFilter.accept(NavigationGpsSample(
                        GpsPoint(fix.latitude, fix.longitude),
                        fix.elapsedRealtimeNanos / 1_000_000L,
                        fix.accuracy, fix.speed.takeIf { fix.hasSpeed() }))) {
                    message = "GPS position jump ignored. Checking the next fix…"
                    return@collect
                }
                lastFix = now; location = fix
                if(gpsWasLost) { gpsWasLost=false; pandaStatus("gps_restored","GPS signal restored.") }
                val reliable = RerouteFix(GpsPoint(fix.latitude,fix.longitude),fix.accuracy,
                    fix.speed.takeIf { fix.hasSpeed() },
                    fix.bearing.takeIf { fix.hasBearing() && (!fix.hasBearingAccuracy() || fix.bearingAccuracyDegrees <= 35f) },
                    fix.elapsedRealtimeNanos / 1_000_000L)
                latestReliableFix = reliable
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
                if (!offRoute && arrival.update(navigationDistance(point, GpsPoint(destination.lat, destination.lon)), fix.accuracy, remainingMeters)) {
                    arrived = true; instruction = "Arrived"; remainingMeters = 0.0; baseRemainingSeconds = 0.0; remainingSeconds = 0.0
                    requestJob?.cancel(); sdk.stopNavigation(); voiceAudio.stop()
                    if(voice==NavigationVoice.PANDA) pandaStatus("arrived","You have arrived at the destination.",VoicePriority.CRITICAL)
                    else speak("You have arrived at the destination.", "arrival")
                    return@collect
                }
                if (route == null && !loading && reliable.accuracy <= 35 && now - reliable.timeMs <= 2_500 &&
                    reroute.requestAllowed(now)) requestRoute(reliable)
                else if (route != null) {
                    val decision = if (now - reliable.timeMs <= 2_500) instantDetector.observe(reliable,shape)
                    else { instantDetector.reset(); null }
                    if (decision != null) {
                        if (!offRoute) {
                            offRoute = true; arrival = ArrivalDetector()
                            instruction = "Recalculating route…"; street = null; turnMeters = null
                            panelCue = NavigationPanelCue(); voiceAudio.stop(); pandaGate.reset()
                            // Start playback asynchronously; the routing coroutine never waits for it.
                            val statusClip=if(decision.reason=="missed-junction") "wrong_turn" else "rerouting"
                            // Dispatch the routing request in this GPS callback before
                            // opening audio focus or preparing a status MP3.
                            main.post { if(active && offRoute && !arrived)
                                pandaStatus(statusClip,"Recalculating route.") }
                        }
                        if (!loading && reroute.requestAllowed(now)) requestRoute(reliable,decision)
                        else if (loading && requestGate.shouldSupersede(reliable) && reroute.requestAllowed(now))
                            requestRoute(reliable,decision)
                    } else if (offRoute && loading && now - reliable.timeMs <= 2_500 &&
                        requestGate.shouldSupersede(reliable) && reroute.requestAllowed(now)) {
                        requestRoute(reliable,RerouteDecision("changed-heading",reliable.timeMs))
                    } else if (offRoute && !loading && reroute.requestAllowed(now) && now - reliable.timeMs <= 2_500 &&
                        reliable.accuracy<=25 && (reliable.speed?:0f)>=3) {
                        requestRoute(reliable,RerouteDecision("retry-latest",reliable.timeMs))
                    }
                }
                feed.updates.emit(org.maplibre.navigation.core.location.Location(
                    latitude = fix.latitude, longitude = fix.longitude, accuracyMeters = fix.accuracy,
                    speedMetersPerSeconds = if (fix.hasSpeed()) fix.speed else null,
                    bearing = if (fix.hasBearing()) fix.bearing else null, timeMilliseconds = fix.time, provider = fix.provider
                ))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "GPS or destination unavailable. Please retry." }
        finally { active = false; requestGate.clear(); requestJob?.cancel(); requestJob = null; loading = false; sdk.stopNavigation(); voiceAudio.stop(); scope = null }
    }

    private fun requestRoute(fix: RerouteFix, decision: RerouteDecision? = null) {
        val owner=scope ?: return
        val now=SystemClock.elapsedRealtime()
        if (!reroute.requestAllowed(now) || !active || arrived) return
        // Invalidate before cancelling; an old coroutine's finally must not
        // clear the newer request's loading state or apply its route/signs.
        val id=requestGate.begin(fix)
        requestJob?.cancel()
        reroute.requested(now); loading=true
        message=if(decision!=null || offRoute) "Recalculating route…" else null
        val trace=decision?.let { RerouteLatency(id,it.confirmingFixMs,now,SystemClock.elapsedRealtime()) }
        latency=trace; latencyRoute=null
        trace?.let { recordLatency("detected",it.detected,it); recordLatency("request-start",it.requested,it) }
        val target=GpsPoint(destination.lat,destination.lon)
        requestJob=owner.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                val heading=fix.bearing?.takeIf { (fix.speed?:0f)>=3 && fix.accuracy<=20 }
                val result=client.routeResult(fix.point,target,heading)
                if (!active || arrived || !requestGate.isCurrent(id)) return@launch
                trace?.let { it.response=SystemClock.elapsedRealtime(); recordLatency("response-parsed",it.response,it) }
                val latest=latestReliableFix
                if (latest!=null && latest.timeMs>fix.timeMs && latest.accuracy<=20 &&
                    navigationDistance(fix.point,latest.point)>12 && !routeFitsLatestFix(result.shape,latest)) {
                    // The vehicle missed this response too. Never paint obsolete instructions.
                    offRoute=true
                    requestRoute(latest,RerouteDecision("stale-route",latest.timeMs))
                    return@launch
                }
                val newRoute=result.route
                shape=result.shape
                localTraffic.reset(shape,newRoute.distance,newRoute.duration)
                trafficState=LocalTrafficState.EMPTY
                routeSignMetadata=result.signs
                val steps=newRoute.legs.single().steps
                val current=steps.first()
                val next=steps.getOrNull(1) ?: current
                street=current.name
                turnMeters=current.distance.coerceAtLeast(0.0)
                instruction=next.maneuver.instruction?.takeIf { it.isNotBlank() }
                    ?: current.bannerInstructions?.firstOrNull()?.primary?.text
                    ?: "Continue on the route"
                panelCue=NavigationPanelSigns.cue(next.maneuver.type?.toString(),next.maneuver.modifier?.toString(),
                    instruction,routeSignMetadata.getOrNull(if(steps.size>1) 1 else 0),routeSignMetadata.firstOrNull())
                adaptiveEta.reset(); baseRemainingSeconds=newRoute.duration
                remainingMeters=newRoute.distance; remainingSeconds=newRoute.duration
                instantDetector.reset(); reroute.succeeded(); offRoute=false; message=null
                voiceGate.reset(); pandaGate.reset(); voiceAudio.stop()
                unavailableAnnounced=false; approachAnnounced=false
                latencyRoute=newRoute; route=newRoute
                sdk.startNavigation(newRoute)
                // Publish the first actual new maneuver immediately, not after the next GPS tick.
                if(trace!=null) {
                    trace.applied=SystemClock.elapsedRealtime(); recordLatency("route-applied",trace.applied,trace)
                    // Announce the real first maneuver once, even when it is farther than
                    // the regular 300m voice cue range. Subsequent SDK cues still apply.
                    voiceGate.accept(0,instruction,turnMeters?:Double.MAX_VALUE)
                    if(voice==NavigationVoice.PANDA) {
                        // The new direction always wins over wrong-turn/status audio.
                        announcePanda(if(steps.size>1) 1 else 0,next.maneuver.type?.toString(),
                            next.maneuver.modifier?.toString(),next.name,instruction,
                            turnMeters?:0.0, panelCue, force=true,
                            following=steps.getOrNull(2)?.let { PandaFollowing(it.maneuver.type?.toString(),it.maneuver.modifier?.toString(),it.maneuver.instruction) },
                            nextLength=next.distance,newRouteReady=true)
                    } else speak("Route updated. In ${metricDistance(turnMeters?:0.0)}, $instruction","reroute-$id")
                } else if(!startAnnounced) {
                    startAnnounced=true
                    if(voice==NavigationVoice.PANDA && (turnMeters?:0.0)>80)
                        pandaStatus("start_navigation","Start navigation.",VoicePriority.CHARACTER)
                }
                location?.let { currentFix ->
                    feed.updates.tryEmit(org.maplibre.navigation.core.location.Location(
                        latitude=currentFix.latitude,longitude=currentFix.longitude,accuracyMeters=currentFix.accuracy,
                        speedMetersPerSeconds=currentFix.speed.takeIf { currentFix.hasSpeed() },
                        bearing=currentFix.bearing.takeIf { currentFix.hasBearing() },
                        timeMilliseconds=currentFix.time,provider=currentFix.provider))
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){
                if(requestGate.isCurrent(id)) {
                    reroute.failed(SystemClock.elapsedRealtime(),(e as? NavigationRateLimited)?.retryMs)
                    message=ROUTE_UNAVAILABLE
                    if(!unavailableAnnounced) { unavailableAnnounced=true
                        pandaStatus("route_unavailable","Route unavailable. Please follow road signs.") }
                }
            } finally { if(requestGate.isCurrent(id)) loading=false }
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
        // The next SDK step, not GPS heading or a road number, drives the panel.
        val nextIndex = p.stepIndex + if (current.nextStep != null) 1 else 0
        panelCue = NavigationPanelSigns.cue(
            next.maneuver.type?.toString(), next.maneuver.modifier?.toString(), instruction,
            routeSignMetadata.getOrNull(nextIndex),
            if (current.nextStep != null) routeSignMetadata.getOrNull(p.stepIndex) else null
        )
        if (SystemClock.elapsedRealtime() - lastFix < 15_000 && !muted) {
            if(voice==NavigationVoice.PANDA) {
                val steps=p.directionsRoute.legs.single().steps
                val following=steps.getOrNull(nextIndex+1)?.let {
                    PandaFollowing(it.maneuver.type?.toString(),it.maneuver.modifier?.toString(),it.maneuver.instruction)
                }
                val due=current.step.voiceInstructions?.any {
                    turnMeters!!<=it.distanceAlongGeometry+5 && !it.announcement.isNullOrBlank()
                }==true
                if(!approachAnnounced && (remainingMeters?:Double.MAX_VALUE) in 35.0..100.0 &&
                    panelCue.sign==NavigationSign.ARRIVE) {
                    approachAnnounced=true
                    pandaStatus("approaching_destination","Approaching destination.",VoicePriority.CHARACTER)
                }
                announcePanda(nextIndex,next.maneuver.type?.toString(),next.maneuver.modifier?.toString(),
                    next.name,instruction,turnMeters?:0.0,panelCue,force=false,following=following,
                    nextLength=next.distance,sdkDue=due)
                return
            }
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
    fun toggleMute() = setSoundEnabled(muted)
    fun setSoundEnabled(enabled: Boolean) {
        if (muted == !enabled) return
        muted = !enabled
        gpsModePreferences.setSoundEnabled(enabled)
        if (muted) voiceAudio.stop() else { voiceGate.reset(); pandaGate.reset() }
    }
    private fun speak(text:String,id:String) {
        if(active && !muted) voiceAudio.play(VoicePhrase(id,VoicePriority.CRITICAL,listOf(VoicePart(null,text))))
    }
    private fun pandaStatus(id:String,fallback:String,priority:VoicePriority=VoicePriority.STATUS) {
        if(active && !muted && voice==NavigationVoice.PANDA)
            voiceAudio.play(VoicePhrase(id,priority,listOf(VoicePart(id,fallback))))
    }
    private fun announcePanda(index:Int,type:String?,modifier:String?,road:String?,text:String,distance:Double,
        cue:NavigationPanelCue,force:Boolean,following:PandaFollowing?,nextLength:Double,sdkDue:Boolean=true,
        newRouteReady:Boolean=false) {
        if(!active || muted || offRoute || !distance.isFinite()) return
        val speed=location?.speed?.takeIf{location?.hasSpeed()==true}?:0f
        voiceAudio.stopObsoleteManeuver(index)
        val phrase=PandaVoiceMapper.maneuver(type,modifier,text,road,cue,distance,speed,following,nextLength)
        if(pandaGate.accept(index,phrase,distance,force,sdkDue)) {
            // Optional reassurance only AFTER the real maneuver, only with enough time.
            val parts=if(newRouteReady && phrase.priority!=VoicePriority.CRITICAL &&
                distance/maxOf(speed.toDouble(),5.0)>15 && distance>250)
                phrase.parts+VoicePart("new_route_ready","New route ready.")
            else phrase.parts
            voiceAudio.play(phrase.copy(key="step-$index-${phrase.key}",parts=parts))
        }
    }
    override fun close() { active=false;requestJob?.cancel();sdk.onDestroy();voiceAudio.close() }
}

@SuppressLint("MissingPermission") // Called only after fine-location runtime permission is granted.
private fun fusedLocations(context: Context): Flow<Location> = kotlinx.coroutines.flow.callbackFlow {
    val client = LocationServices.getFusedLocationProviderClient(context)
    // Foreground navigation only: request shorter, non-batched fixes for smoother puck animation.
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500)
        .setMinUpdateIntervalMillis(250).setMaxUpdateDelayMillis(0)
        .setWaitForAccurateLocation(false).build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach { trySend(it) } }
    }
    try { client.requestLocationUpdates(request, callback, Looper.getMainLooper()).addOnFailureListener { close(it) } }
    catch (e: SecurityException) { close(e) }
    awaitClose { client.removeLocationUpdates(callback) }
}.buffer(kotlinx.coroutines.channels.Channel.CONFLATED)
