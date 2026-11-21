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
import androidx.compose.foundation.shape.CircleShape
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
    plan.stops.getOrNull(plan.current)?.let { builtInNavigationIntent(context, it) }

/** Label data is bound to the selected order, including duplicate-postal orders. */
internal fun builtInNavigationIntent(context:Context,stop:Stop):Intent =
    builtInNavigationIntent(context,stop.place)
        .putExtra(NAV_DELIVERY_ADDRESS_873,selectedDeliveryAddress873(stop))
        .putExtra(NAV_DELIVERY_ORDER_873,stop.orderId)

/** Single destination snapshot; the activity gets its origin from live device GPS. */
internal fun builtInNavigationIntent(context: Context, stop: Place): Intent =
    Intent(context, BuiltInNavigationActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("postal", stop.postal).putExtra("address", stop.address)
        .putExtra("lat", stop.lat).putExtra("lon", stop.lon)
        .apply { destinationHeading870(stop).block?.let { putExtra("block", it) } }

/** Foreground-only navigation. This activity has no access to delivery-status or schedule writes. */
class BuiltInNavigationActivity : ComponentActivity() {
    private var currentDestination873 by mutableStateOf<NavigationDestinationSnapshot873?>(null)
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(englishAppContext(newBase))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentDestination873=navigationDestinationSnapshot873(intent)?:run{finish();return}
        MapLibre.getInstance(this)
        setContent {
            IzzDeliveryTheme(true) {
                currentDestination873?.let{NavigationContent(it)}
            }
        }
    }

    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        val next=navigationDestinationSnapshot873(intent)?:return
        setIntent(intent)
        currentDestination873=next
    }

    @Composable
    private fun NavigationContent(snapshot:NavigationDestinationSnapshot873) {
        val stop=snapshot.place
        val session = remember(snapshot.sessionKey) { NavigationSession(this@BuiltInNavigationActivity, stop) }
        val erp = rememberErpNavigation876(session.location)
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
        LaunchedEffect(permitted, retry, session) {
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
                NavigationMap(session, Modifier.matchParentSize(), permitted, mapAppearance, erp)

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
                                // The number and unit share ONE live independent visual state.
                                // No MP3/voice-cooldown callback is needed to turn either red.
                                val red879=session.cameraPreferences.overspeed &&
                                    session.redSpeedFontEnabled878 && session.overspeedVisualState879.red
                                Text((fix.speed.coerceAtLeast(0f) * 3.6f).roundToInt().toString(),
                                    color=if(red879)Color(0xFFFF3030) else Color.White,
                                    fontSize=27.sp,fontWeight=FontWeight.Bold)
                                Text("km/h",color=if(red879)Color(0xFFFF3030) else SGColors.Silver,
                                    fontSize=11.sp)
                                if(session.cameraPreferences.speedLimitDisplay){
                                    // A red circle does not imply verification: the distinct
                                    // classification is always immediately beneath the sign.
                                    val limit=session.roadSpeedLimitState875
                                    Spacer(Modifier.height(3.dp))
                                    Text("SPEED LIMIT",color=SGColors.Gold,
                                        fontSize=9.sp,fontWeight=FontWeight.Bold,maxLines=1)
                                    Surface(
                                        color=Color.White,
                                        shape=CircleShape,
                                        border=BorderStroke(
                                            if(limit.highlightOverspeed)4.dp else
                                                if(limit.hasNumericLimit)3.dp else 1.5.dp,
                                            if(limit.highlightOverspeed)Color(0xFFFF3B30) else
                                                if(limit.hasNumericLimit)Color(0xFFD52C2C) else Color.Gray
                                        ),
                                        modifier=Modifier.size(60.dp)
                                    ){
                                        Column(
                                            horizontalAlignment=Alignment.CenterHorizontally,
                                            verticalArrangement=Arrangement.Center,
                                            modifier=Modifier.fillMaxSize()
                                        ){
                                            Text(limit.numberKmh?.toString() ?: "CHECK\nSIGNS",
                                                color=if(limit.hasNumericLimit)Color.Black else Color.Gray,
                                                fontSize=if(limit.hasNumericLimit)24.sp else 10.sp,
                                                fontWeight=FontWeight.Black,
                                                lineHeight=if(limit.hasNumericLimit)25.sp else 12.sp,
                                                maxLines=if(limit.hasNumericLimit)1 else 2)
                                            if(limit.hasNumericLimit){
                                                Text("km/h",color=Color.Black,
                                                    fontSize=8.sp,lineHeight=10.sp,maxLines=1)
                                            }
                                        }
                                    }
                                    Text(limit.badgeLabel,
                                        color=when(limit.limit?.kind){
                                            RoadLimitSource875.OFFICIAL -> Color(0xFF82C89A)
                                            RoadLimitSource875.MAP_DATA -> SGColors.Gold
                                            RoadLimitSource875.GENERAL_RULE -> Color(0xFFFFAF72)
                                            null -> SGColors.Silver
                                        },
                                        fontSize=9.sp,fontWeight=FontWeight.Bold,maxLines=1)
                                    limit.note?.let{
                                        Text(it,color=SGColors.Silver,fontSize=8.sp,
                                            fontWeight=FontWeight.Medium,maxLines=1)
                                    }
                                }
                            }
                        }
                    }
                    session.upcomingEnforcementCamera?.let { camera ->
                        if(session.cameraPreferences.master) {
                            Surface(color=PremiumNavy.copy(alpha=0.90f),
                                shape=RoundedCornerShape(8.dp),
                                border=BorderStroke(1.dp,SGColors.Gold.copy(alpha=0.7f))) {
                                Column(Modifier.widthIn(max=190.dp)
                                    .padding(horizontal=8.dp,vertical=6.dp)) {
                                    Text(when(camera.camera.category){
                                        "RED_LIGHT_CAMERA"->"RED-LIGHT CAMERA"
                                        "MOBILE_ENFORCEMENT_LOCATION"->"MOBILE CAMERA LOCATION"
                                        "POLICE_LASER_LOCATION"->"POLICE LASER LOCATION"
                                        "AVERAGE_SPEED_ZONE"->"AVERAGE SPEED ZONE"
                                        else->"SPEED CAMERA"
                                    },color=SGColors.Gold,fontSize=10.sp,
                                        fontWeight=FontWeight.Bold,maxLines=1)
                                    Text(if(camera.directionConfirmed)
                                        "${camera.routeMetresAhead.toInt()}m along route"
                                        else "Direction not verified",
                                        color=Color.White,fontSize=10.sp,maxLines=1)
                                    if(!camera.directionConfirmed){
                                        Text("Map location only · no voice alert",
                                            color=SGColors.Silver,fontSize=9.sp,maxLines=1)
                                    }
                                }
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
                        val remaining = session.remainingSeconds
                        val eta = if (remaining != null) Instant.ofEpochMilli(
                            navigationEta(System.currentTimeMillis(), remaining))
                            .atZone(ZoneId.of("Asia/Singapore"))
                            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) else "--:--"
                        val trafficLabel=if(session.localTrafficVisible)when(session.trafficState.ahead) {
                            LocalTrafficLevel.SMOOTH -> "Local traffic estimate: Smooth"
                            LocalTrafficLevel.SLOW -> "Local traffic estimate: Slow"
                            LocalTrafficLevel.JAMMED -> "Local traffic estimate: Jammed"
                            LocalTrafficLevel.UNKNOWN -> "Local traffic: insufficient GPS data"
                        } else null
                        NavigationDestinationPanel873(
                            heading=snapshot.heading,eta=eta,
                            remainingSummary="${session.remainingMeters?.let { metricDistance(it) } ?: "-- km"}  ·  ${remaining?.let { duration(it) } ?: "-- min"}",
                            trafficLabel=trafficLabel,onDelivery={finish()})

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
                        CameraSafetySettingsPanel(session)
                        ErpSettingsPanel876(erp)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Smart Auto Zoom", Modifier.weight(1f), color = SGColors.White)
                            Switch(checked = session.smartAutoZoomEnabled,
                                onCheckedChange = { session.updateSmartAutoZoomEnabled(it) })
                        }
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
        ErpInformationCard876(erp)
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

    @Composable
    private fun GpsControl(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.width(65.dp).height(61.dp),
            contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(SGDimensions.RadiusSmall),
            colors = ButtonDefaults.filledTonalButtonColors(
                // Reuse the speedometer/speed-limit host alpha on the surface only.
                containerColor = if (active) PremiumGold.copy(alpha = SGColors.Card.alpha) else SGColors.Card,
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
    var cameraDisplayedZoom: Double? = null
    var smartAutoZoomEnabled by mutableStateOf(gpsModePreferences.isSmartAutoZoomEnabled()); private set
    fun updateSmartAutoZoomEnabled(enabled: Boolean) {
        smartAutoZoomEnabled = enabled
        gpsModePreferences.setSmartAutoZoomEnabled(enabled)
        zoomOverride = null
        cameraRequest++
    }
    var cameraRoadName by mutableStateOf<String?>(null); private set
    var cameraManeuverType by mutableStateOf<String?>(null); private set
    var cameraManeuverIndex by mutableIntStateOf(0); private set
    var followingTurnMeters by mutableStateOf<Double?>(null); private set
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
    private val cameraStore=CameraSafetyStore(context)
    private val speedReferenceStore868=SpeedLimitReferenceStore868(context)
    private val speedLimitTracker868=SpeedLimitTracker868()
    private val cameraPrefsStore=CameraSafetyPrefs(context)
    private val cameraVoicePlanner=CameraVoicePlanner865()
    private val cameraRouteRecovery872=CameraRouteRecovery872()
    private var currentCameraApproaches872:List<CameraApproach> = emptyList()
    private var activeCameraCue872:CameraVoiceCue865?=null
    // Short-lived session token only; never persist raw location history.
    private val cameraAudioSession866=java.lang.Long.toHexString(SystemClock.elapsedRealtimeNanos())

    private var lastCameraScanMs=0L
    var cameraPreferences by mutableStateOf(cameraPrefsStore.get()); private set
    var enforcementCameras by mutableStateOf<List<EnforcementCamera>>(emptyList()); private set
    private var speedRows868:List<SpeedLimitRow868> = emptyList()
    var speedLimitSourceCount868 by mutableIntStateOf(0); private set
    var speedLimitState868 by mutableStateOf(SpeedLimitState868()); private set
    private val roadSpeedStore875=RoadSpeedStore875(context)
    private val roadSpeedTracker875=RoadSpeedLimitTracker875()
    private val overspeedMonitor878=OverspeedSafetyMonitor878()
    private val overspeedVisualMonitor879=OverspeedVisualMonitor879()
    var overspeedVisualState879 by mutableStateOf(OverspeedVisualState879()); private set
    var overspeedDiagnostic879 by mutableStateOf("Waiting for GPS and current road"); private set
    private val overspeedPrefs878=OverspeedPreferences878(context)
    var redSpeedFontEnabled878 by mutableStateOf(overspeedPrefs878.redSpeedFont()); private set
    var overspeedVoiceCooldownSeconds878 by mutableIntStateOf(overspeedPrefs878.cooldownSeconds()); private set
    var overspeedState878 by mutableStateOf(OverspeedState878()); private set
    fun setRedSpeedFont878(enabled:Boolean) {
        redSpeedFontEnabled878=enabled;overspeedPrefs878.setRedSpeedFont(enabled)
    }
    fun setOverspeedCooldown878(seconds:Int) {
        overspeedPrefs878.setCooldownSeconds(seconds)
        overspeedVoiceCooldownSeconds878=seconds
    }
    private var roadSpeedLookup875:Job?=null
    private var roadSpeedGeneration875=0L
    var roadSpeedLimitState875 by mutableStateOf(RoadSpeedState875()); private set
    var roadSpeedCatalogInfo875 by mutableStateOf("Loading offline road catalog…"); private set
    private var coordinateReferenceIds867:Set<String> = emptySet()
    var coordinateCatalogMatchCount by mutableIntStateOf(0); private set
    var cameraDatabaseVersion by mutableStateOf("Not loaded"); private set
    var cameraLastUpdated by mutableStateOf("Never"); private set
    var cameraRefreshMessage by mutableStateOf<String?>(null); private set
    var cameraVoiceDiagnostic by mutableStateOf("No active camera check yet"); private set
    var cameraRefreshBusy by mutableStateOf(false); private set
    var upcomingEnforcementCamera by mutableStateOf<CameraApproach?>(null); private set
    var cameraMarkerBucket by mutableStateOf(""); private set
    val cameraAudioAvailable get()=voiceAudio.available209
    fun updateCameraPreferences(newPrefs:CameraAlertPreferences){
        cameraPreferences=newPrefs;cameraPrefsStore.save(newPrefs)
        if(!newPrefs.overspeed) {
            overspeedMonitor878.reset()
            overspeedState878=OverspeedState878()
            overspeedVisualMonitor879.reset("Overspeed Warning disabled in Settings")
            overspeedVisualState879=overspeedVisualMonitor879.state
        } else if(!cameraPreferences.overspeed) {
            // Preferences have just become ON; reevaluate on the next real fix.
            overspeedVisualMonitor879.reset("Waiting for a new GPS fix after enabling warnings")
        }
        if((!newPrefs.verifiedOverspeedVoice || !newPrefs.overspeed) &&
            voiceAudio.activePhraseKey?.startsWith("overspeed-878-")==true)
            voiceAudio.stop("overspeed-settings-disabled")
        if(!newPrefs.master)upcomingEnforcementCamera=null
        activeCameraCue872?.let { if(!cameraCueRelevant872(it))voiceAudio.stop("camera-settings-disabled") }
    }
    private suspend fun loadCameraStore() {
        val (cameras,meta,reference)=withContext(Dispatchers.IO) {
            cameraStore.ensureBundled()
            val list=cameraStore.cameras()
            Triple(list,cameraStore.metadata(),
                runCatching{CameraCoordinateCatalog867.load(context,list)}
                    .onFailure{android.util.Log.e("SGCamera867","Reference catalog validation failed; route-aware database camera alerts remain enabled",it)}
                    .getOrNull())
        }
        enforcementCameras=cameras
        cameraDatabaseVersion=meta.first
        cameraLastUpdated=meta.second
        coordinateReferenceIds867=reference?.eligibleSpeedIds.orEmpty()
        coordinateCatalogMatchCount=reference?.size?:0
    }
    private suspend fun loadSpeedReference868(){
        try {
            val loaded=withContext(Dispatchers.IO){
                val rows=speedReferenceStore868.all()
                speedReferenceStore868.verifyCoordinates(rows,enforcementCameras)
                rows
            }
            speedRows868=loaded
            speedLimitSourceCount868=loaded.size
            android.util.Log.i("SGSpeed868","100 source rows loaded locally; 91 provisional, 9 unknown")
        }catch(e:Exception){
            speedRows868=emptyList()
            speedLimitSourceCount868=0
            speedLimitState868=SpeedLimitState868(diagnostics="Speed reference database unavailable")
            android.util.Log.e("SGSpeed868","Failed to validate speed reference SQLite",e)
        }
    }

    /** Uses route-matched SPF cameras ONLY as proximity references, not whole-road legal limits. */
    private fun updateSpeedLimit868(fix:Location){
        if(!active || offRoute || route==null || shape.size<2 ||
            speedRows868.size!=100 || enforcementCameras.isEmpty()){
            speedLimitTracker868.reset()
            speedLimitState868=SpeedLimitState868(
                diagnostics="No verified road segment / route unavailable")
            return
        }
        val road=street?.takeIf{it.isNotBlank()} ?: cameraRoadName
        val upcoming=route?.legs?.firstOrNull()?.steps.orEmpty()
            .drop((cameraManeuverIndex-1).coerceAtLeast(0))
            .take(12).mapNotNull{it.name}
        val speedIds=speedRows868.map{it.cameraId}.toHashSet()
        val matched=CameraApproachLogic.find(
            GpsPoint(fix.latitude,fix.longitude),shape,road,upcoming,fix.accuracy,
            enforcementCameras.filter{it.id in speedIds},
            fix.speed.takeIf{fix.hasSpeed()},
            fix.bearing.takeIf{fix.hasBearing()}?.toDouble(),
            fix.bearingAccuracyDegrees.takeIf{fix.hasBearingAccuracy()})
        // None of the 91 numeric workbook entries proves a direction-specific road
        // segment's legally posted limit. They may only be shown as provisional REF.
        speedLimitState868=speedLimitTracker868.observe(
            matched,speedRows868,fix.accuracy,
            fix.speed.takeIf{fix.hasSpeed()},
            fix.speedAccuracyMetersPerSecond.takeIf{fix.hasSpeedAccuracy()},
            authoritativeSegmentMatch=false
        )
    }

    private fun clearRoadSpeed875(reason:String) {
        roadSpeedGeneration875++;roadSpeedLookup875?.cancel()
        roadSpeedLimitState875=roadSpeedTracker875.invalidate(reason)
        overspeedVisualMonitor879.reset(reason)
        overspeedVisualState879=overspeedVisualMonitor879.state
        overspeedDiagnostic879=reason
        overspeedMonitor878.reset()
        overspeedState878=OverspeedState878()
        if(voiceAudio.activePhraseKey?.startsWith("overspeed-878-")==true)
            voiceAudio.stop("speed-limit-unavailable")
    }
    /** Evaluate EXACTLY the currently displayed GPS speed against the same
     * map-matched road state that supplies the existing 90/80/... limit circle.
     * Never couple the UI RED state to offline MP3 availability or voice events.
     */
    private fun updateOverspeedVisual879(fix:Location):OverspeedVisualState879 {
        val sample=RoadSpeedFix875(GpsPoint(fix.latitude,fix.longitude),
            fix.elapsedRealtimeNanos/1_000_000L,fix.accuracy,
            fix.bearing.takeIf { fix.hasBearing() },
            fix.speed.takeIf{fix.hasSpeed()},
            fix.speedAccuracyMetersPerSecond.takeIf{fix.hasSpeedAccuracy()})
        val visual=overspeedVisualMonitor879.observe(roadSpeedLimitState875,sample,
            cameraPreferences.overspeed)
        overspeedVisualState879=visual
        overspeedDiagnostic879="Speed=${visual.shownSpeedKmh ?: "?"} km/h; " +
            "Limit=${visual.applicableLimitKmh ?: "?"} km/h; " +
            "Source=${visual.limitSource?.label ?: "UNKNOWN"}; " +
            "Road=${visual.roadSegmentId ?: "UNMATCHED"}; " +
            "Accuracy=${fix.accuracy}m; speedAccuracy=" +
            "${if(fix.hasSpeedAccuracy()) fix.speedAccuracyMetersPerSecond.toString() else "ABSENT"}; " +
            "Visual=${visual.red}; FontEnabled=${redSpeedFontEnabled878}; " +
            "VoiceEligible=${overspeedState878.confirmed}; " +
            "Reason=${visual.diagnostic}"
        android.util.Log.d("SGOverspeed879",overspeedDiagnostic879)
        return visual
    }

    /** All safety audio goes through the real Panda V2 player. Real STARTED is the
     * only event that consumes a cooldown; blocked/deferred alerts can retry. */
    private fun warnOverspeed878() {
        if(!active || arrived || offRoute || muted || !cameraPreferences.overspeed ||
            !cameraPreferences.verifiedOverspeedVoice || !overspeedState878.voiceDue ||
            !overspeedState878.confirmed || !voiceAudio.available209 ||
            "overspeed_warning" !in voiceAudio.cameraPack.clips ||
            SystemClock.elapsedRealtime()-lastFix>6_000L) return
        if((voiceAudio.activePriority?.rank ?: -1) >= VoicePriority.CAMERA.rank)return
        val segment=overspeedState878.roadSegmentId?:return
        val at=SystemClock.elapsedRealtime()
        val phrase=VoicePhrase("overspeed-878-$segment-$at",VoicePriority.CAMERA,
            listOf(VoicePart("overspeed_warning","Verified road overspeed warning")))
        val accepted=voiceAudio.play(phrase,stillRelevant={
            active && !arrived && !offRoute && !muted && cameraPreferences.overspeed &&
                cameraPreferences.verifiedOverspeedVoice && overspeedState878.confirmed &&
                overspeedState878.roadSegmentId==segment &&
                SystemClock.elapsedRealtime()-lastFix<6_000L
        }) { event ->
            if(event.state==PandaPlaybackState866.STARTED)
                overspeedMonitor878.playbackStarted(event.elapsedMs)
            else if(event.state in setOf(PandaPlaybackState866.INTERRUPTED,
                    PandaPlaybackState866.CANCELLED,PandaPlaybackState866.FAILED))
                overspeedMonitor878.playbackNotStarted()
            android.util.Log.i("SGOverspeed878",
                "audio=${event.state} clip=overspeed_warning.mp3 reason=${event.reason} road=$segment")
        }
        if(accepted) {
            overspeedMonitor878.markRequested(at)
            android.util.Log.i("SGOverspeed878","voice accepted, awaiting MediaPlayer STARTED road=$segment")
        } else android.util.Log.d("SGOverspeed878","voice deferred; audio priority or focus unavailable")
    }

    /** Read-only lookup runs off the GPS/rerouting path; no camera setting gates it. */
    private fun updateRoadSpeed875(fix:Location) {
        val owner=scope?:return
        val generation=++roadSpeedGeneration875
        roadSpeedLookup875?.cancel()
        val sample=RoadSpeedFix875(GpsPoint(fix.latitude,fix.longitude),fix.elapsedRealtimeNanos/1_000_000,
            fix.accuracy,fix.bearing.takeIf{fix.hasBearing() && (!fix.hasBearingAccuracy() || fix.bearingAccuracyDegrees<=35)},
            fix.speed.takeIf{fix.hasSpeed()},fix.speedAccuracyMetersPerSecond.takeIf{fix.hasSpeedAccuracy()},
            street?.takeIf{!offRoute && it.isNotBlank()})
        val routeShape=shape.takeIf{!offRoute}.orEmpty()
        roadSpeedLookup875=owner.launch {
            try {
                val loaded=withContext(Dispatchers.IO){
                    val meta=roadSpeedStore875.ensureBundled()
                    val roads=roadSpeedStore875.candidates(sample.point)
                    val routeMatch=NavigationRoadMatcher.match(sample.point,routeShape,sample.accuracy,sample.speed,sample.heading)
                    Triple(meta,roads,routeMatch?.bearingDegrees)
                }
                if(!active || generation!=roadSpeedGeneration875)return@launch
                if(SystemClock.elapsedRealtime()-sample.timeMs>6000){clearRoadSpeed875("GPS sample stale; check signs");return@launch}
                roadSpeedCatalogInfo875="${loaded.first.ways} road ways · ${loaded.first.knownDirections} mapped directional limits · ${loaded.first.checkedDate}"
                roadSpeedLimitState875=roadSpeedTracker875.observe(sample.copy(routeBearing=loaded.third),loaded.second,cameraPreferences.overspeed)
                // Publish red/white independently of Panda audio. The speed
                // circle can show MAP DATA 90 while official-only voice stays OFF.
                val visualForSample=updateOverspeedVisual879(fix)
                overspeedMonitor878.requestTimedOut(SystemClock.elapsedRealtime())
                overspeedState878=overspeedMonitor878.observe(roadSpeedLimitState875,sample,
                    cameraPreferences.overspeed,sample.timeMs,
                    overspeedVoiceCooldownSeconds878*1_000L,
                    validatedFallbackSpeed=visualForSample.speedReliable &&
                        visualForSample.roadSegmentId==roadSpeedLimitState875.segmentId)
                warnOverspeed878()
                // Road lookup is asynchronous: if the UI speed already moved on
                // to a newer GPS fix, calculate the colour from that same fix.
                location?.takeIf{it.elapsedRealtimeNanos>fix.elapsedRealtimeNanos}
                    ?.let{updateOverspeedVisual879(it)}
                android.util.Log.d("SGRoadSpeed875","gps=${sample.point.lat},${sample.point.lon} accuracy=${sample.accuracy} heading=${sample.heading} ${roadSpeedLimitState875.diagnostics}")
            }catch(e:CancellationException){throw e}
            catch(e:Exception){
                if(generation==roadSpeedGeneration875){
                    clearRoadSpeed875("Offline road catalog unavailable; check signs")
                    roadSpeedCatalogInfo875="Catalog unavailable; previous data retained"
                }
                android.util.Log.e("SGRoadSpeed875","Road-speed lookup failed",e)
            }
        }
    }

    suspend fun refreshCameraSnapshot(){
        if(cameraRefreshBusy)return
        cameraRefreshBusy=true
        try {
            withContext(Dispatchers.IO) { cameraStore.replace(CameraSafetyRemote.download()) }
            loadCameraStore()
            voiceAudio.stopObsoleteCameraForRouteChange()
            cameraVoicePlanner.reset(); cameraRouteRecovery872.reset(); currentCameraApproaches872=emptyList()
        speedLimitTracker868.reset(); speedLimitState868=SpeedLimitState868()
            cameraRefreshMessage="Camera snapshot verified and updated."
        }catch(e:Exception){
            cameraRefreshMessage="Camera update not available; retaining previous verified offline snapshot."
        }finally{cameraRefreshBusy=false}
    }
    private fun scanCameraSafety(fix:Location,now:Long) {
        cameraMarkerBucket="${(fix.latitude*100).toInt()}:${(fix.longitude*100).toInt()}"
        if(now-lastCameraScanMs<1000) return
        lastCameraScanMs=now
        if(!cameraPreferences.master) {
            cameraVoiceDiagnostic="Master Camera Alerts is OFF"
            upcomingEnforcementCamera=null
            currentCameraApproaches872=emptyList()
            voiceAudio.stopObsoleteCameraForRouteChange()
            return
        }
        if(offRoute || route==null) {
            cameraVoiceDiagnostic="Waiting for active GPS navigation route (or rerouting)"
            upcomingEnforcementCamera=null
            currentCameraApproaches872=emptyList()
            voiceAudio.stopObsoleteCameraForRouteChange()
            return
        }
        val currentRoad=street?.takeIf{it.isNotBlank()}
            ?:cameraRoadName?.takeIf{it.isNotBlank()}
        val upcoming=route?.legs?.firstOrNull()?.steps.orEmpty()
            .drop((cameraManeuverIndex-1).coerceAtLeast(0))
            .take(12).mapNotNull{it.name}
        val reliableBearing=fix.bearing.takeIf {
            fix.hasBearing() && (!fix.hasBearingAccuracy() || fix.bearingAccuracyDegrees<=20f)
        }?.toDouble()
        val bearingAccuracy=fix.bearingAccuracyDegrees.takeIf {
            fix.hasBearingAccuracy() && it.isFinite()
        }
        val roadCorridors=CameraRoadCorridor872.from(
            route?.legs?.flatMap{it.steps}.orEmpty().map{it.name.orEmpty() to it.distance},shape)
        // Every supported database category participates. The B867 screenshot
        // catalog validates coordinates but must never act as an alert allow-list.
        val rawApproaches=cameraRouteRecovery872.scan(
            GpsPoint(fix.latitude,fix.longitude),now,shape,currentRoad,upcoming,fix.accuracy,
            fix.speed.takeIf{fix.hasSpeed()},reliableBearing,bearingAccuracy,
            enforcementCameras,roadCorridors)
        val approaches=CameraVoiceGroup867.combine(rawApproaches,enforcementCameras)
        currentCameraApproaches872=approaches
        activeCameraCue872?.let {
            if(!cameraCueRelevant872(it))voiceAudio.stop("camera-no-longer-ahead-or-matched")
        }
        upcomingEnforcementCamera=approaches.firstOrNull{it.routeMetresAhead>0 && cameraPreferences.categoryEnabled(it.camera.category)}
        cameraVoicePlanner.observeVisible(approaches,now)
        if(approaches.isEmpty()) {
            cameraVoiceDiagnostic=if(currentRoad.isNullOrBlank())
                "Waiting for matched road name from navigation"
            else if(coordinateCatalogMatchCount!=100)
                "Coordinate import validation failed: ${coordinateCatalogMatchCount}/100"
            else "No camera matched current road, direction or 2KM route distance"
            return
        }
        if(muted || !cameraPreferences.voice) {
            cameraVoiceDiagnostic="GPS voice or Camera Voice is OFF"
            approaches.forEach{logCameraApproach872(it,fix,null,"SUPPRESSED","navigation-or-camera-voice-disabled")}
            return
        }
        if(!cameraAudioAvailable) {
            cameraVoiceDiagnostic="209-clip Panda Warrior voice assets unavailable"
            approaches.forEach{logCameraApproach872(it,fix,null,"FAILED","authentic-209-audio-pack-unavailable")}
            return
        }
        var reason="Camera published nearby; direction not confirmed for voice"
        for(approach in approaches) {
            val planned=cameraVoicePlanner.due(
                approach,cameraPreferences,!muted,cameraAudioAvailable,turnMeters,
                fix.speed.takeIf{fix.hasSpeed()})
            if(planned==null) {
                reason=cameraVoicePlanner.reason(approach,cameraPreferences)
                logCameraApproach872(approach,fix,null,"OBSERVED",reason)
                continue
            }
            logCameraApproach872(approach,fix,planned,"GENERATED","threshold-or-first-observation")
            val deferred=CameraVoicePriority872.deferredReason(turnMeters,voiceAudio.activePriority,cameraManeuverType)
            if(deferred!=null) {
                reason="DEFERRED: $deferred; retry next GPS fix while still ahead"
                logCameraApproach872(approach,fix,planned,"DEFERRED",deferred)
                continue
            }
            if(planned.clipId !in voiceAudio.cameraPack.clips) {
                cameraVoiceDiagnostic="Recorded clip missing: ${planned.clipId}"
                android.util.Log.w("SGCamera865Voice",
                    "missing id=${planned.cameraId} clip=${planned.clipId}")
                continue
            }
            val candidateId=planned.cameraId
            val accepted=voiceAudio.play(planned.phrase,stillRelevant={cameraCueRelevant872(planned)}) { playback ->
                cameraVoicePlanner.playbackEvent(planned,playback)
                if(playback.state!=PandaPlaybackState866.STARTED)activeCameraCue872=null
                val status=playback.state.name
                android.util.Log.i("SGCamera866",
                    "session=$cameraAudioSession866 camera=$candidateId " +
                    "category=${planned.category} stage=${planned.stage} " +
                    "clip=${planned.clipId}.mp3 playback=$status " +
                    "reason=${playback.reason} timestamp=${playback.elapsedMs}")
                cameraVoiceDiagnostic=when(playback.state) {
                    PandaPlaybackState866.STARTED ->
                        "STARTED: ${planned.clipId}.mp3 on Android MediaPlayer"
                    PandaPlaybackState866.COMPLETED ->
                        "COMPLETED: ${planned.clipId}.mp3"
                    PandaPlaybackState866.FAILED ->
                        "FAILED: ${planned.clipId}.mp3 (${playback.reason}); will retry if still ahead"
                    PandaPlaybackState866.CANCELLED,
                    PandaPlaybackState866.INTERRUPTED ->
                        "INTERRUPTED: ${planned.clipId}.mp3 (${playback.reason}); will retry when safe"
                }
            }
            android.util.Log.d("SGCamera866",
                "session=$cameraAudioSession866 camera=${planned.cameraId} " +
                "category=${planned.category} publishedRoad=${approach.camera.road} " +
                "publishedDirection=${approach.camera.directionText} currentRoad=$currentRoad " +
                "directionConfirmed=${approach.directionConfirmed} " +
                "genericSafety=${approach.genericSafetyEligible} " +
                "distanceType=${if(approach.coordinateFallbackUsed)"RECOVERED_ROUTE" else "ROUTE"} " +
                "sourceGps=(${approach.camera.point.lat},${approach.camera.point.lon}) " +
                "vehicleGps=(${fix.latitude},${fix.longitude}) accuracy=${fix.accuracy}m " +
                "previousDistance=${planned.previousMetresAhead?.toInt()}m " +
                "cameraRouteDistance=${planned.metresAhead.toInt()}m " +
                "threshold=${planned.warningThresholdMetres?.toInt()}m " +
                "crossed=${planned.crossedThreshold} " +
                "event=GENERATED clip=${planned.clipId}.mp3 accepted=$accepted " +
                "audioPriority=${voiceAudio.activePriority} " +
                "controllerStarted=${voiceAudio.isPlaybackStarted}")
            if(accepted) {
                // Only a pending request until MediaPlayer.start() confirms real playback.
                cameraVoicePlanner.markRequested(planned)
                activeCameraCue872=planned
                reason="PENDING: ${planned.clipId}.mp3; waiting for Android playback STARTED"
            } else {
                reason="DEFERRED: audio controller rejected; rechecking next GPS fix"
                logCameraApproach872(approach,fix,planned,"DEFERRED","controller-busy-focus-or-muted")
            }
        }
        cameraVoiceDiagnostic=reason
    }

    private fun cameraCueRelevant872(cue:CameraVoiceCue865):Boolean {
        if(!active || arrived || offRoute || muted || !cameraPreferences.voice ||
            !cameraPreferences.coordinateCameraAlerts || !cameraPreferences.categoryEnabled(cue.category) ||
            SystemClock.elapsedRealtime()-lastFix>5_000L)return false
        return currentCameraApproaches872.any {
            (it.voiceGroupId?:it.camera.id)==cue.approachId &&
                (it.voiceGroupCategory?:it.camera.category)==cue.stateCategory &&
                if(cue.stage in setOf("enter","exit"))it.routeMetresAhead in -80.0..0.0
                else it.routeMetresAhead>0.0 && it.routeMetresAhead<=2_000.0 &&
                    (it.directionConfirmed || it.genericSafetyEligible)
        }
    }
    private fun logCameraApproach872(a:CameraApproach,fix:Location,cue:CameraVoiceCue865?,
        event:String,reason:String) {
        val previous=cue?.previousMetresAhead?:cameraVoicePlanner.previousDistance(a)
        android.util.Log.i("SGCamera872",
            "session=$cameraAudioSession866 id=${a.camera.id} group=${a.voiceGroupId} category=${a.camera.category} " +
            "cameraGps=(${a.camera.point.lat},${a.camera.point.lon}) gps=(${fix.latitude},${fix.longitude}) " +
            "accuracy=${fix.accuracy} road=${a.matchedRoad} direction=${a.camera.directionText} " +
            "directionConfirmed=${a.directionConfirmed} match=${a.matchReason} previous=$previous " +
            "current=${a.routeMetresAhead} threshold=${cue?.warningThresholdMetres?:2_000.0} " +
            "crossed=${cue?.crossedThreshold?:false} mp3=${cue?.clipId} event=$event reason=$reason")
    }

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
        val baseline = zoomOverride ?: cameraDisplayedZoom?.takeIf { it.isFinite() }
            ?: NavigationDisplayTuning.zoomForSpeed(
                location?.speed?.takeIf { location?.hasSpeed() == true })
        zoomOverride = (baseline + step).coerceIn(13.8, 19.5)
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
    private val gpsSanityFilter = NavigationGpsSanityFilter()
    private val main = android.os.Handler(Looper.getMainLooper())
    private var active = false
    private val voiceAudio = NavigationVoiceAudio(context)
    private val pandaGate = PandaVoiceGate()
    var voiceVolume by mutableStateOf(gpsModePreferences.voiceVolume()); private set
    val pandaAvailable get() = voiceAudio.pack.available
    val previewingVoice get() = voiceAudio.previewing
    private var gpsWasLost=false
    private var startAnnounced=false
    private var approachAnnounced=false
    private var unavailableAnnounced=false
    fun changeVoiceVolume(value:Float) {
        voiceVolume=value.coerceIn(0f,1f);gpsModePreferences.setVoiceVolume(voiceVolume)
        voiceAudio.setVolume(voiceVolume)
    }
    fun previewVoice() {
        if(!active || muted || !pandaAvailable) return
        voiceAudio.play(VoicePhrase("preview", VoicePriority.PREVIEW,
            PandaVoiceMapper.composeAtDistance("turn","right","Turn right",null,NavigationPanelCue(NavigationSign.TURN_RIGHT),3000).map { VoicePart(it,"Preview") }))
    }
    fun previewCameraVoice(clipId:String) {
        if(!active || muted || !cameraAudioAvailable || clipId !in CameraVoiceAssetIds865.ALL) return
        if(clipId !in voiceAudio.cameraPack.clips) return
        voiceAudio.play(VoicePhrase("preview-camera-$clipId",VoicePriority.PREVIEW,
            listOf(VoicePart(clipId,"Authentic Panda Warrior V2 camera recording"))))
    }
    fun stopVoicePreview()=voiceAudio.stopPreview()
    private var requestJob: Job? = null
    private var lastFix = 0L
    private var scope: CoroutineScope? = null

    init {
        CameraSafetyUpdateScheduler.schedule(context)
        RoadSpeedUpdateScheduler875.schedule(context)
        sdk.addProgressChangeListener { _, p -> main.post { if (active && !arrived && !offRoute && p.directionsRoute == route) updateProgress(p) } }
        voiceAudio.setVolume(voiceVolume)
    }

    suspend fun run(): Unit = coroutineScope {
        gpsModePreferences.voice(); voiceVolume = gpsModePreferences.voiceVolume(); voiceAudio.setVolume(voiceVolume)
        try { loadCameraStore() } catch (_:Exception) {
            cameraRefreshMessage="No validated camera snapshot available."
        }
        loadSpeedReference868()
        roadSpeedTracker875.reset();roadSpeedLimitState875=RoadSpeedState875()
        overspeedVisualMonitor879.reset()
        overspeedVisualState879=overspeedVisualMonitor879.state
        overspeedMonitor878.reset()
        overspeedState878=OverspeedState878()
        active = true; scope = this; startAnnounced=false; adaptiveEta.reset(); arrival = ArrivalDetector(); reroute.resetDrift(); instantDetector.reset(); requestGate.clear(); pandaGate.reset(); gpsSanityFilter.reset()
        try {
            launch {
                while(isActive){
                    delay(1000)
                    val expired=roadSpeedTracker875.expire(SystemClock.elapsedRealtime())
                    if(expired!=roadSpeedLimitState875) {
                        roadSpeedLimitState875=expired
                        overspeedVisualMonitor879.reset("Current speed limit expired; check signs")
                        overspeedVisualState879=overspeedVisualMonitor879.state
                        overspeedMonitor878.reset()
                        overspeedState878=OverspeedState878()
                    }
                }
            }
            if (!GpsPoint(destination.lat, destination.lon).valid()) {
                message = "Resolving destination…"
                destination = Repository(context.applicationContext as android.app.Application).resolve(destination.postal)
                require(GpsPoint(destination.lat, destination.lon).valid())
            }
            route?.takeIf { !arrived }?.let { sdk.startNavigation(it); startAnnounced=true; pandaStatus("start_navigation","Start navigation.",VoicePriority.CHARACTER) }
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
                    clearRoadSpeed875("GPS accuracy or timestamp invalid; check signs")
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
                    clearRoadSpeed875("GPS jump rejected; check signs")
                    return@collect
                }
                lastFix = now; location = fix
                updateOverspeedVisual879(fix)
                scanCameraSafety(fix,now)
                updateRoadSpeed875(fix)
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
                    pandaStatus("arrived","You have arrived at the destination.",VoicePriority.CRITICAL)
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
        finally { active = false; roadSpeedLookup875?.cancel();roadSpeedStore875.close();requestGate.clear(); requestJob?.cancel(); requestJob = null; loading = false; sdk.stopNavigation(); voiceAudio.stop(); scope = null }
    }

    private fun requestRoute(fix: RerouteFix, decision: RerouteDecision? = null) {
        val owner=scope ?: return
        val now=SystemClock.elapsedRealtime()
        if (!reroute.requestAllowed(now) || !active || arrived) return
        // Invalidate before cancelling; an old coroutine's finally must not
        // clear the newer request's loading state or apply its route/signs.
        val id=requestGate.begin(fix)
        requestJob?.cancel()
        upcomingEnforcementCamera=null
        voiceAudio.stopObsoleteCameraForRouteChange()
        cameraVoicePlanner.reset(); cameraRouteRecovery872.reset(); currentCameraApproaches872=emptyList()
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
                cameraRoadName=current.name
                cameraManeuverIndex=if(steps.size>1) 1 else 0
                cameraManeuverType=next.maneuver.type?.toString()
                followingTurnMeters=if(steps.size>2) next.distance.coerceAtLeast(0.0) else null
                turnMeters=current.distance.coerceAtLeast(0.0)
                instruction=next.maneuver.instruction?.takeIf { it.isNotBlank() }
                    ?: current.bannerInstructions?.firstOrNull()?.primary?.text
                    ?: "Continue on the route"
                panelCue=NavigationPanelSigns.cue(next.maneuver.type?.toString(),next.maneuver.modifier?.toString(),
                    instruction,routeSignMetadata.getOrNull(if(steps.size>1) 1 else 0),routeSignMetadata.firstOrNull())
                adaptiveEta.reset(); baseRemainingSeconds=newRoute.duration
                remainingMeters=newRoute.distance; remainingSeconds=newRoute.duration
                instantDetector.reset(); reroute.succeeded(); offRoute=false; message=null
                pandaGate.reset(); voiceAudio.stop()
                unavailableAnnounced=false; approachAnnounced=false
                latencyRoute=newRoute; route=newRoute
                sdk.startNavigation(newRoute)
                // Publish the first actual new maneuver immediately, not after the next GPS tick.
                if(trace!=null) {
                    trace.applied=SystemClock.elapsedRealtime(); recordLatency("route-applied",trace.applied,trace)
                    // Announce the real first maneuver once, even when it is farther than
                    // the regular 300m voice cue range. Subsequent SDK cues still apply.
                    run {
                        // The new direction always wins over wrong-turn/status audio.
                        announcePanda(if(steps.size>1) 1 else 0,next.maneuver.type?.toString(),
                            next.maneuver.modifier?.toString(),next.name,instruction,
                            turnMeters?:0.0, panelCue, force=true,
                            following=steps.getOrNull(2)?.let { PandaFollowing(it.maneuver.type?.toString(),it.maneuver.modifier?.toString(),it.maneuver.instruction) },
                            nextLength=next.distance,newRouteReady=true)
                    }
                } else if(!startAnnounced) {
                    startAnnounced=true
                    if((turnMeters?:0.0)>30)
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
        cameraManeuverIndex=nextIndex
        cameraManeuverType=next.maneuver.type?.toString()
        cameraRoadName=current.step.name
        val cameraSteps=p.directionsRoute.legs.single().steps
        followingTurnMeters=if(nextIndex+1<cameraSteps.size)
            cameraSteps.getOrNull(nextIndex)?.distance?.takeIf { it.isFinite() && it>=0.0 }
            else null
        panelCue = NavigationPanelSigns.cue(
            next.maneuver.type?.toString(), next.maneuver.modifier?.toString(), instruction,
            routeSignMetadata.getOrNull(nextIndex),
            if (current.nextStep != null) routeSignMetadata.getOrNull(p.stepIndex) else null
        )
        if (SystemClock.elapsedRealtime() - lastFix < 15_000 && !muted) {
            run {
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
            }
        }
    }
    fun toggleMute() = setSoundEnabled(muted)
    fun setSoundEnabled(enabled: Boolean) {
        if (muted == !enabled) return
        muted = !enabled
        gpsModePreferences.setSoundEnabled(enabled)
        if (muted) voiceAudio.stop() else { pandaGate.reset() }
    }
    private fun pandaStatus(id:String,fallback:String,priority:VoicePriority=VoicePriority.STATUS) {
        if(active && !muted)
            voiceAudio.play(VoicePhrase(id,priority,listOf(VoicePart(id,fallback))))
    }
    private fun announcePanda(index:Int,type:String?,modifier:String?,road:String?,text:String,distance:Double,
        cue:NavigationPanelCue,force:Boolean,following:PandaFollowing?,nextLength:Double,sdkDue:Boolean=true,
        newRouteReady:Boolean=false) {
        if(!active || muted || offRoute || !distance.isFinite()) return
        val speed=location?.speed?.takeIf{location?.hasSpeed()==true}?:0f
        voiceAudio.stopObsoleteManeuver(index)
        val previous=pandaGate.previousDistance(index,distance)
        val phrase=PandaVoiceMapper.maneuver(type,modifier,text,road,cue,distance,speed,following,nextLength,previous,force)
        if(phrase.key=="missing-audio" || (cue.roundaboutExit?:0)>10)
            android.util.Log.w("SGPanda857","missing coverage type=$type modifier=$modifier distance=$distance exit=${cue.roundaboutExit}; visual guidance; legacy voice disabled")
        if(pandaGate.eligible(index,phrase,distance)) {
            android.util.Log.d("SGPanda857","navigation step=$index type=$type modifier=$modifier distance=$distance clip=${phrase.parts.firstOrNull()?.clip}")
            if(voiceAudio.play(phrase.copy(key="step-$index-${phrase.key}"))) pandaGate.mark(index,phrase,distance)
        }
    }
    override fun close() { active=false;requestJob?.cancel();sdk.onDestroy();voiceAudio.close();cameraStore.close() }
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
