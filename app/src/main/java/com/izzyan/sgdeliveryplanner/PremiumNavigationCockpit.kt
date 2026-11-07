package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val CockpitNavy = Color(0xFF081B33)
private val CockpitElevated = Color(0xFF152F50)
private val CockpitGold = Color(0xFFD4AF37)
private val CockpitLight = Color(0xFFF1D57A)
private val CockpitMuted = Color(0xFFD6DEE8)
private val PanelShape = RoundedCornerShape(16.dp)

/**
 * Entirely new map-dominant native Compose cockpit. The map itself remains a real
 * MapLibre view, and all UI actions delegate to the existing NavigationSession.
 * The cockpit never writes delivery/payment records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PremiumNavigationCockpit(
    stop: Place,
    session: NavigationSession,
    permitted: Boolean,
    requestedPermission: Boolean,
    onRequestLocation: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRetryRoute: () -> Unit,
    onPetrol: () -> Unit,
    onCameras: () -> Unit,
    onTrafficRequest: () -> Unit,
    onDelivery: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showZoom by remember { mutableStateOf(false) }
    var cancelPrompt by remember { mutableStateOf(false) }
    val remaining = session.remainingSeconds
    val arrival = remaining?.let {
        Instant.ofEpochMilli(navigationEta(System.currentTimeMillis(), it))
            .atZone(ZoneId.of("Asia/Singapore"))
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    } ?: "--:--"
    LaunchedEffect(showZoom) {
        if (showZoom) {
            delay(8_000)
            showZoom = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(CockpitNavy)) {
        val landscape = maxWidth > maxHeight
        val narrow = maxWidth < 365.dp
        // The native map is genuinely full screen. All guidance sits on top.
        NavigationMap(session, Modifier.matchParentSize(), permitted)

        Box(Modifier.matchParentSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val bannerWidth = if (landscape) 0.70f else 1f
            Column(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth(bannerWidth)
                    .padding(horizontal = if (landscape) 8.dp else 9.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    color = CockpitNavy.copy(alpha = 0.97f),
                    shape = PanelShape,
                    shadowElevation = 5.dp,
                    border = BorderStroke(1.dp, CockpitGold.copy(alpha = .52f))
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = if (landscape) 73.dp else 82.dp)
                            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            if (session.arrived) "✓" else
                                NavigationDisplayTuning.maneuverArrow(session.instruction),
                            color = CockpitLight,
                            fontSize = if (narrow) 38.sp else 44.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(47.dp)
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                if (session.arrived) "Arrived" else
                                    session.turnMeters?.let { metricDistance(it) } ?: "GPS",
                                color = Color.White,
                                fontSize = if (narrow) 25.sp else 28.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                if (session.arrived) "Destination reached" else
                                    NavigationDisplayTuning.nextRoadLabel(
                                        session.instruction, session.street),
                                color = CockpitMuted,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            NavigationDisplayTuning.roundaboutExit(session.instruction)
                                ?.let { exit ->
                                    Text(exit, color = CockpitLight, fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold)
                                }
                        }
                        IconButton(
                            onClick = { session.toggleMute() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                if (session.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                contentDescription = if (session.muted) "Unmute directions" else "Mute directions",
                                tint = if (session.muted) CockpitMuted else CockpitLight,
                                modifier = Modifier.size(23.dp)
                            )
                        }
                    }
                }
                if (session.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp),
                        color = CockpitGold)
                }
                session.message?.let { text ->
                    Surface(color = CockpitNavy.copy(alpha = .93f), shape = RoundedCornerShape(9.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(text, color = CockpitLight, fontSize = 11.sp,
                                modifier = Modifier.weight(1f), maxLines = 2)
                            if (!session.loading && !session.arrived)
                                TextButton(onClick = onRetryRoute) { Text("Retry", color = CockpitLight) }
                        }
                    }
                }
            }

            // Small 48dp controls instead of 5 large vertically-stacked labeled cards.
            Column(
                Modifier.align(Alignment.CenterEnd)
                    .padding(end = 9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CockpitControl(Icons.Filled.MyLocation, "Recenter",
                    active = session.cameraMode == "follow") { session.recenter() }
                CockpitControl(Icons.Filled.Map, "Route overview",
                    active = session.cameraMode == "overview") {
                    session.cameraMode = "overview"
                    session.cameraRequest++
                }
                CockpitControl(
                    icon = Icons.Filled.Explore,
                    description = if (session.threeDimensional) "Switch to 2D" else "Switch to 3D",
                    active = session.threeDimensional
                ) { session.toggleDimension() }
                if (showZoom) {
                    CockpitControl(Icons.Filled.Add, "Zoom in") { session.adjustZoom(.5) }
                    CockpitControl(Icons.Filled.Remove, "Zoom out") { session.adjustZoom(-.5) }
                }
                CockpitControl(Icons.Filled.MoreVert, "Open navigation menu",
                    active = menuOpen) { menuOpen = true }
            }

            // GPS speed is intentionally small. Do not invent an unknown speed limit.
            session.location?.takeIf { it.hasSpeed() }?.let { fix ->
                Surface(
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = 11.dp, bottom = if (landscape) 94.dp else 105.dp)
                        .widthIn(min = 57.dp),
                    color = CockpitNavy.copy(alpha = .94f),
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 3.dp,
                    border = BorderStroke(.8.dp, CockpitGold.copy(alpha = .42f))
                ) {
                    Column(
                        Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text((fix.speed.coerceAtLeast(0f) * 3.6f).roundToInt().toString(),
                            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("km/h", color = CockpitMuted, fontSize = 10.sp)
                    }
                }
            }

            // Existing Delivery callback is retained: this returns to the existing
            // Delivery workflow and does not change an order's status in navigation.
            Surface(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth(if (landscape) .76f else 1f)
                    .padding(horizontal = 9.dp, vertical = 8.dp),
                color = CockpitNavy.copy(alpha = .97f),
                shape = PanelShape,
                shadowElevation = 5.dp,
                border = BorderStroke(1.dp, CockpitGold.copy(alpha = .56f))
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = if (landscape) 75.dp else 84.dp)
                        .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("ETA", color = CockpitMuted, fontSize = 10.sp,
                            fontWeight = FontWeight.Medium)
                        Text(arrival, color = Color.White, fontSize = if (narrow) 23.sp else 27.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1)
                        val distance = session.remainingMeters?.let { metricDistance(it) } ?: "-- km"
                        val time = remaining?.let { duration(it) } ?: "-- min"
                        Text(time + " · " + distance,
                            color = CockpitMuted, fontSize = 12.sp, maxLines = 1)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(stop.postal, color = CockpitLight,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (narrow) 23.sp else 26.sp,
                            letterSpacing = .4.sp, maxLines = 1, softWrap = false)
                        Button(
                            onClick = onDelivery,
                            modifier = Modifier.height(39.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CockpitGold,
                                contentColor = CockpitNavy)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Delivery", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (!permitted) {
                Surface(
                    Modifier.align(Alignment.Center).padding(18.dp),
                    shape = RoundedCornerShape(17.dp),
                    color = CockpitElevated
                ) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (requestedPermission) "Precise GPS permission is required. Check app permissions."
                            else "Enable precise GPS for built-in navigation.",
                            color = Color.White
                        )
                        Button(onClick = onRequestLocation) { Text("Allow precise location") }
                        if (requestedPermission)
                            TextButton(onClick = onOpenAppSettings) {
                                Text("Open Android app settings", color = CockpitLight)
                            }
                    }
                }
            }
        }
    }

    if (menuOpen) {
        ModalBottomSheet(
            onDismissRequest = { menuOpen = false },
            containerColor = CockpitNavy,
            contentColor = Color.White,
            dragHandle = {
                Box(Modifier.padding(top = 10.dp, bottom = 7.dp)
                    .size(width = 42.dp, height = 4.dp)
                    .background(CockpitGold, CircleShape))
            }
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text("NAVIGATION OPTIONS", color = CockpitLight, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                MenuOption(Icons.Filled.LocalGasStation, "Nearby petrol stations",
                    enabled = permitted && session.location != null) {
                    menuOpen = false; onPetrol()
                }
                MenuOption(Icons.Filled.Map, "Route overview") {
                    menuOpen = false
                    session.cameraMode = "overview"; session.cameraRequest++
                }
                MenuOption(Icons.Filled.Add, "Show zoom controls") {
                    showZoom = true; menuOpen = false
                }
                MenuOption(Icons.Filled.Traffic,
                    if (session.localTrafficVisible) "Turn off runner traffic" else "Runner traffic information") {
                    menuOpen = false
                    if (session.localTrafficVisible) session.toggleLocalTraffic()
                    else onTrafficRequest()
                }
                MenuOption(if (session.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    if (session.muted) "Enable voice guidance" else "Mute voice guidance") {
                    menuOpen = false; session.toggleMute()
                }
                MenuOption(Icons.Filled.PhotoCamera, "Traffic cameras") {
                    menuOpen = false; onCameras()
                }
                MenuOption(Icons.Filled.LocalParking, "Parking · not available", enabled = false) {}
                MenuOption(Icons.Filled.OfflinePin, "Offline maps · not installed", enabled = false) {}
                MenuOption(Icons.Filled.Settings, "Advanced settings · not available", enabled = false) {}
                HorizontalDivider(color = CockpitGold.copy(alpha = .25f))
                MenuOption(Icons.Filled.Close, "End navigation", destructive = true) {
                    menuOpen = false; cancelPrompt = true
                }
            }
        }
    }
    if (cancelPrompt) {
        AlertDialog(
            onDismissRequest = { cancelPrompt = false },
            containerColor = CockpitNavy,
            title = { Text("End GPS navigation?", color = Color.White) },
            text = {
                Text("Your delivery route, orders and payment information will remain unchanged.",
                    color = CockpitMuted)
            },
            confirmButton = {
                TextButton(onClick = { cancelPrompt = false; onDelivery() }) {
                    Text("End navigation", color = CockpitLight)
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelPrompt = false }) {
                    Text("Continue driving", color = Color.White)
                }
            }
        )
    }
}

@Composable
private fun CockpitControl(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(15.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) CockpitGold else CockpitNavy.copy(alpha = .93f),
            contentColor = if (active) CockpitNavy else Color.White
        )
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun MenuOption(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 47.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) Color(0xFFFFAAA5) else Color.White
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(13.dp))
        Text(label, modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start, fontSize = 14.sp,
            color = if (enabled) (if (destructive) Color(0xFFFFAAA5) else Color.White)
                else CockpitMuted.copy(alpha = .45f))
    }
}
