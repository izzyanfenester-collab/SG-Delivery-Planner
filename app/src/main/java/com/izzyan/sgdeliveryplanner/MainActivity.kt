package com.izzyan.sgdeliveryplanner

import android.app.TimePickerDialog
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(englishAppContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(7, 23, 45))
        )
        org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
        setContent {
            val vm: PlannerViewModel = viewModel()
            val dark = vm.theme == "Dark" || (vm.theme == "System" && isSystemInDarkTheme())
            IzzDeliveryTheme(dark) { App(vm) }
        }
    }
}

fun time(value: String): String = runCatching {
    val dateTime = LocalDateTime.parse(value)
    dateTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) +
        if (dateTime.toLocalDate() != LocalDate.now(ZoneId.of("Asia/Singapore")))
            " (${dateTime.toLocalDate()})" else ""
}.getOrDefault(value)

fun duration(seconds: Double): String {
    val minutes = kotlin.math.ceil(seconds.coerceAtLeast(0.0) / 60).toInt()
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}
fun km(value: Double) = "%.1f km".format(Locale.US, value)

@Composable
fun App(vm: PlannerViewModel) {
    ScheduleExportHost()
    ProofPickerHost(vm)
    DeliveryReportPreviewHost(vm)
    if (vm.importOrdersDialog) CustomerImportDialog(vm)
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle, vm.screen) {
        val awake = DeliveryScreenAwake(view, lifecycle, vm.screen)
        onDispose { awake.close() }
    }
    val screenStates = rememberSaveableStateHolder()
    BackHandler(enabled = vm.canGoBack) { vm.goBack() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = PremiumNavy, shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.canGoBack) {
                            TextButton(
                                onClick = vm::goBack, enabled = !vm.busy,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = PremiumGold,
                                    disabledContentColor = Color(0xFF8292AC)
                                ),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("← Back", style = MaterialTheme.typography.titleMedium) }
                        }
                        Text(
                            stringResource(R.string.app_name), Modifier.weight(1f),
                            color = Color.White, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (vm.screen in setOf("Home", "Route", "Delivery", "Map", "History")) {
                            IconButton(onClick = { vm.screen = "Settings" }, enabled = !vm.busy, modifier = Modifier.size(48.dp)) {
                                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = PremiumGold, modifier = Modifier.size(30.dp))
                            }
                            ChatGptHeaderButton()
                        }
                    }
                    Text(
                        when (vm.screen) {
                            "Home" -> "Singapore parcel delivery"
                            "Route" -> "Your optimized route"
                            "Delivery" -> "Delivery mode"
                            "Map" -> "Route map"
                            "History" -> "Saved delivery routes"
                            "Summary" -> "Route summary"
                            "LocationPicker" -> "Choose your Start & End location"
                            else -> "Preferences"
                        },
                        color = Color(0xFFB7C7E1), style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        bottomBar = {
            if (vm.screen != "LocationPicker") {
                Surface(color = PremiumNavy, shadowElevation = 6.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Home", "Route", "Delivery", "Map", "History").forEach { label ->
                            val selected = vm.screen == label
                            TextButton(
                                onClick = { vm.screen = label }, enabled = !vm.busy,
                                modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 80.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (selected) PremiumRoyal else Color.Transparent,
                                    contentColor = if (selected) PremiumGold else Color(0xFFD0DBEC),
                                    disabledContentColor = Color(0xFF8292AC)
                                )
                            ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.message.isNotBlank()) Text(vm.message, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
            if (vm.message.isNotBlank() && !vm.busy) {
                PremiumCard(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(vm.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { vm.message = "" }) { Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
            if (vm.notice.isNotBlank()) {
                PremiumCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(vm.notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.notice = "" }) { Text("Dismiss") }
                    }
                }
            }
            // Keep route form drafts and scroll positions when the driver opens another screen.
            val screenStateKey = if (vm.screen in setOf("Route", "Delivery", "Map", "Summary"))
                "${vm.screen}:${vm.route?.id.orEmpty()}" else vm.screen
            screenStates.SaveableStateProvider(screenStateKey) {
                when (vm.screen) {
                    "Home" -> Home(vm)
                    "Settings" -> Settings(vm)
                    "History" -> History(vm)
                    "LocationPicker" -> StartLocationPicker(vm)
                    else -> {
                        val p = vm.route
                        if (p == null) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                PremiumCard(Modifier.fillMaxWidth()) {
                                    Text("Your next delivery starts here", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
                                    Text("Plan a route on Home or open a saved route from History.", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp))
                                }
                                PrimaryAction("Plan a route", { vm.screen = "Home" }, enabled = !vm.busy)
                            }
                        } else when (vm.screen) {
                            "Delivery" -> Delivery(vm, p)
                            "Map" -> RouteMap(p)
                            "Summary" -> Summary(vm, p)
                            else -> Results(vm, p)
                        }
                    }
                }
            }
        }
    }
    vm.reportPreview?.let { plan ->
        DeliverySummaryReportPreview(plan, onClose = vm::closeReportPreview)
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = PremiumRoyal,
    minHeight: Dp = 64.dp
) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
    ) { Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SecondaryAction(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, minHeight: Dp = 64.dp) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight), shape = RoundedCornerShape(18.dp)
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CountTile(label: String, count: Int, modifier: Modifier = Modifier, color: Color? = null) {
    Surface(
        modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium, color = color ?: MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DeliveryDateControl(vm: PlannerViewModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    SecondaryAction(
        "Delivery Date${if (compact) "\n" else ": "}${vm.deliveryDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))}",
        { deliveryDatePicker(context, vm.deliveryDate, vm::selectDeliveryDate).show() },
        enabled = !vm.busy, modifier = modifier, minHeight = if (compact) 56.dp else 64.dp
    )
}

@Composable
fun Clock(vm: PlannerViewModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    SecondaryAction(
        "Start time${if (compact) "\n" else ": "}${LocalTime.of(vm.startMinute / 60, vm.startMinute % 60).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))}",
        {
            TimePickerDialog(context, { _, hour, minute ->
                vm.startMinute = hour * 60 + minute
                vm.persist()
            }, vm.startMinute / 60, vm.startMinute % 60, false).show()
        }, enabled = !vm.busy, modifier = modifier, minHeight = if (compact) 56.dp else 64.dp
    )
}

@Composable
fun Home(vm: PlannerViewModel) {
    val parsed = parseInput(vm.input)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PageTitle("Plan your delivery", "Plan a round trip from your selected Start & End location.")
        StartLocationControls(vm)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeliveryDateControl(vm, Modifier.weight(1f), compact = true)
            Clock(vm, Modifier.weight(1f), compact = true)
        }
        Text("Delivery time per stop: ${vm.service} minutes", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = vm.input, onValueChange = { vm.input = it; vm.persist() },
            label = { Text("Singapore postal codes") },
            placeholder = { Text("Paste Singapore postal codes here\n730120\n730301\n760270\n560211\n460079\n640208") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 224.dp),
            shape = RoundedCornerShape(20.dp), enabled = !vm.busy
        )
        SecondaryAction("Import WhatsApp Orders", { vm.importOrdersDialog = true }, enabled = !vm.busy)
        if (vm.draftOrders.isNotEmpty()) Text("Orders found: ${vm.planningOrders().size} · Duplicate postals preserved: ${vm.planningOrders().size - vm.planningOrders().map { it.postalCode }.distinct().size}")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CountTile("Valid stops", if (vm.draftOrders.isEmpty()) parsed.valid.size else vm.planningOrders().size, Modifier.weight(1f))
            CountTile(if (vm.draftOrders.isEmpty()) "Duplicates removed" else "Duplicate postals preserved", parsed.duplicates, Modifier.weight(1f))
        }
        if (parsed.invalid.isNotEmpty()) {
            PremiumCard(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text("Invalid postal codes: ${parsed.invalid.joinToString()}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryAction("Optimize route", vm::optimize, Modifier.weight(1f), enabled = !vm.busy, minHeight = 56.dp)
            SecondaryAction("Clear postal code", vm::clearPostalCodes, enabled = !vm.busy, modifier = Modifier.weight(1f), minHeight = 56.dp)
        }
        TaxDeclareButton()
        Text("Routes follow roads and support up to 50 unique delivery stops.\nTimes are planning estimates and do not include live traffic.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Settings(vm: PlannerViewModel) {
    var serviceText by remember(vm.service) { mutableStateOf(vm.service.toString()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageTitle("Settings", "Set the defaults for your next route.")
        PremiumCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Default WhatsApp App", style = MaterialTheme.typography.titleLarge)
                (listOf<WhatsAppChoice?>(null) + WhatsAppChoice.entries).forEach { choice ->
                    Row(Modifier.fillMaxWidth().heightIn(min=52.dp).selectable(vm.whatsappPreferences.default == choice, enabled=!vm.busy, role=androidx.compose.ui.semantics.Role.RadioButton,
                        onClick={vm.whatsappPreferences.updateDefault(choice)}),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(vm.whatsappPreferences.default == choice,null,enabled=!vm.busy)
                        Text(choice?.label ?: "Ask every time",Modifier.padding(start=8.dp))
                    }
                }
                HorizontalDivider()
                Text("Default Navigation", style = MaterialTheme.typography.titleLarge)
                (listOf<NavigationChoice?>(null) + NavigationChoice.entries).forEach { choice ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(vm.navigationPreferences.default == choice, enabled = !vm.busy, role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { vm.navigationPreferences.updateDefault(choice) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(vm.navigationPreferences.default == choice, onClick = null, enabled = !vm.busy)
                        Text(choice?.label ?: "Ask every time", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        PremiumCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Schedule", style = MaterialTheme.typography.titleLarge)
                DeliveryDateControl(vm)
                OutlinedTextField(
                    serviceText,
                    { text ->
                        if (text.length <= 3 && text.all(Char::isDigit)) {
                            serviceText = text
                            text.toIntOrNull()?.takeIf { it in 1..120 }?.let { vm.service = it; vm.persist() }
                        }
                    },
                    label = { Text("Delivery time per stop (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = serviceText.toIntOrNull()?.let { it in 1..120 } != true,
                    supportingText = { Text("Enter a whole number from 1 to 120 minutes.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Clock(vm)
                Text("Traffic estimate", style = MaterialTheme.typography.titleMedium)
                listOf("Normal", "Light Traffic", "Heavy Traffic").forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { vm.traffic = mode; vm.persist() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(vm.traffic == mode, { vm.traffic = mode; vm.persist() })
                        Text(trafficLabel(mode))
                    }
                }
            }
        }
        PremiumCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleLarge)
                Text("Distance unit: kilometres (km)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("System", "Light", "Dark").forEach { theme ->
                        FilterChip(
                            selected = vm.theme == theme, onClick = { vm.theme = theme; vm.persist() },
                            label = { Text(if (theme == "System") "Use device setting" else theme) }, modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                        )
                    }
                }
            }
        }
        PremiumCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Road routing", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    vm.endpoint, { vm.endpoint = it.trim(); vm.persist() }, label = { Text("Routing server address (HTTPS)") },
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Text("The default routing server is for evaluation and may be unavailable. For regular deliveries, use a reliable OSRM-compatible server with Singapore road data. Saved routes keep the settings used when they were planned.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun tryOpenMap(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

private fun mapUnavailable(context: Context) {
    Toast.makeText(context, "Google Maps could not be opened. Install Google Maps or a web browser, then try again.", Toast.LENGTH_LONG).show()
}

fun navigate(context: Context, place: Place) {
    val navigation = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${place.lat},${place.lon}&mode=d"))
    if (!tryOpenMap(context, navigation)) {
        val browser = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${place.lat},${place.lon}&travelmode=driving"))
        if (!tryOpenMap(context, browser)) mapUnavailable(context)
    }
}

/** The saved route's depot remains the origin and destination even after Home is changed. */
internal fun routeGoogleMapsIntent(plan: Plan): Intent {
    val start = plan.startLocation.asPlace()
    val waypoints = plan.stops.joinToString("|") { "${it.place.lat},${it.place.lon}" }
    return Intent(Intent.ACTION_VIEW, Uri.parse(
        "https://www.google.com/maps/dir/?api=1&origin=${start.lat},${start.lon}" +
            "&destination=${start.lat},${start.lon}&travelmode=driving&waypoints=${Uri.encode(waypoints)}"
    ))
}

private fun statusLabel(status: String): String = when (normalizedStatus(status)) {
    "DELIVERED" -> "Delivered"
    "ON_HOLD" -> "On hold"
    "SKIPPED" -> "Skipped"
    else -> "Pending"
}

private fun trafficLabel(mode: String): String = when (mode) {
    "Light Traffic" -> "Light traffic"
    "Heavy Traffic" -> "Heavy traffic"
    else -> "Normal traffic"
}
private fun statusColor(status: String): Color = when (normalizedStatus(status)) {
    "DELIVERED" -> PremiumEmerald
    "ON_HOLD" -> PremiumAmber
    "SKIPPED" -> Color(0xFF4F5E73)
    else -> PremiumRoyal
}

@Composable
private fun statusCountColor(status: String): Color {
    val light = MaterialTheme.colorScheme.onSurface == PremiumNavy
    return when (normalizedStatus(status)) {
        "DELIVERED" -> if (light) PremiumEmerald else Color(0xFF80E7BE)
        "ON_HOLD" -> if (light) PremiumAmber else Color(0xFFFFD492)
        "SKIPPED" -> if (light) Color(0xFF4F5E73) else Color(0xFFC3CBDA)
        else -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun StatusBadge(status: String) {
    Surface(color = statusColor(status), contentColor = Color.White, shape = RoundedCornerShape(50)) {
        Text(statusLabel(status), Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

fun holdDetails(stop: Stop): String? {
    if (normalizedStatus(stop.status) != "ON_HOLD") return null
    return listOfNotNull(stop.holdReason?.takeIf { it.isNotBlank() }, stop.holdNote?.takeIf { it.isNotBlank() }).joinToString(" • ").takeIf { it.isNotBlank() }
}

@Composable
fun Delivery(vm: PlannerViewModel, p: Plan) {
    val context = LocalContext.current
    var showNavigation by remember(p.id, p.current) { mutableStateOf(false) }
    if (showNavigation) NavigationChooser(
        onDismiss = { showNavigation = false },
        onChoose = { choice, saveDefault -> showNavigation = !vm.navigationPreferences.launch(context, p, choice, saveDefault) },
        enabled = !vm.busy, initialSelection = vm.navigationPreferences.default
    )
    var holdStop by remember(p.id, p.current) { mutableStateOf<Int?>(null) }
    if (holdStop != null) {
        HoldDialog(
            onDismiss = { holdStop = null },
            onConfirm = { reason, note ->
                vm.progress("ON_HOLD", reason, note)
                holdStop = null
            }, enabled = !vm.busy
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (p.current !in p.stops.indices) {
            PageTitle("All stops reviewed", "Your delivery summary is ready, including any stops to revisit.")
            val summary = deliverySummary(p)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CountTile("Delivered", summary.delivered, Modifier.weight(1f), statusCountColor("DELIVERED"))
                CountTile("To revisit", summary.onHold + summary.skipped + summary.pending, Modifier.weight(1f))
            }
            PrimaryAction("View delivery summary", { vm.screen = "Summary" }, enabled = !vm.busy)
            SecondaryAction("Return to ${p.startLocation.displayName}", { navigate(context, p.startLocation.asPlace()) }, enabled = !vm.busy)
            p.stops.forEachIndexed { index, stop ->
                if (normalizedStatus(stop.status) != "DELIVERED") StopCard(vm, p, stop, index, false, showShareReport = false) { vm.revisit(index) }
            }
        } else {
            val stop = p.stops[p.current]
            PremiumCard(Modifier.fillMaxWidth(), borderColor = PremiumGold, containerColor = PremiumNavy) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Stop ${p.current + 1} of ${p.stops.size}", color = PremiumGold, style = MaterialTheme.typography.titleLarge)
                    Text(stop.place.postal, color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Block ${stop.place.block} • ${stop.place.area}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    if (stop.order == null) Text(stop.place.address, color = Color(0xFFB7C7E1), style = MaterialTheme.typography.bodyLarge) else CompositionLocalProvider(LocalContentColor provides Color.White) { CustomerDetails(vm,stop) }
                    StatusBadge(stop.status)
                    holdDetails(stop)?.let { Text(it, color = Color(0xFFFFD492), style = MaterialTheme.typography.bodyMedium) }
                    HorizontalDivider(color = Color(0xFF31486B))
                    Text("Planned arrival: ${time(stop.arrival)}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Delivery period: ${time(stop.arrival)} – ${time(stop.leave)}", color = Color(0xFFB7C7E1))
                    stop.etaArrival?.let { Text("Updated ETA: ${time(it)} • Finish: ${time(stop.etaLeave ?: stop.leave)}", color = PremiumGold) }
                    stop.completedAt?.let { Text("Actual / Delivered at: ${time(it)}", color = PremiumGold) }
                    Text("Planning estimate", color = PremiumGold, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryAction("Navigate", {
                        val choice = vm.navigationPreferences.default
                        showNavigation = choice == null || !openDeliveryNavigation(context, p, choice)
                    }, Modifier.weight(1f), enabled = !vm.busy, minHeight = 56.dp)
                    PrimaryAction("Delivered", { vm.progress("DELIVERED") }, Modifier.weight(1f), enabled = !vm.busy && normalizedStatus(stop.status) != "DELIVERED", color = PremiumEmerald, minHeight = 56.dp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryAction("On Hold", { holdStop = p.current }, Modifier.weight(1f), enabled = !vm.busy && normalizedStatus(stop.status) != "DELIVERED", color = PremiumAmber, minHeight = 56.dp)
                    PrimaryAction("Skipped", { vm.progress("SKIP") }, Modifier.weight(1f), enabled = !vm.busy && normalizedStatus(stop.status) != "DELIVERED", color = Color(0xFF4F5E73), minHeight = 56.dp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={vm.proofRequest=ProofRequest(p.id,stop.orderId,ProofKind.PAYMENT,chooseSource=true)},enabled=!vm.busy,modifier=Modifier.weight(1f).heightIn(min=56.dp)) {Text(if(stop.order?.paymentProofFileId != null) "✓ Proof of Payment" else "Proof of Payment")}
                OutlinedButton(onClick={vm.proofRequest=ProofRequest(p.id,stop.orderId,ProofKind.DELIVERY,chooseSource=true)},enabled=!vm.busy,modifier=Modifier.weight(1f).heightIn(min=56.dp)) {Text(if(stop.order?.proofFileId != null) "✓ Proof of Delivery" else "Proof of Delivery")}
            }
            if (stop.order?.paymentProofFileId != null) OrderProofSection(vm,p,stop,ProofKind.PAYMENT)
            if (stop.order?.proofFileId != null) OrderProofSection(vm,p,stop,ProofKind.DELIVERY)
            SecondaryAction("Next stop", { vm.progress("NEXT") }, enabled = !vm.busy)
            Text("Next stop marks this stop as reviewed and keeps its current status. You can revisit deliveries that are on hold, skipped or pending from the summary or route.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HoldDialog(onDismiss: () -> Unit, onConfirm: (String?, String?) -> Unit, enabled: Boolean) {
    var reason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Place delivery on hold") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("A reason is optional. You can revisit this stop later.")
                listOf("Customer not home", "No answer", "Reschedule", "Payment issue", "Access issue", "Other").forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { reason = if (reason == option) null else option },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(reason == option, { reason = if (reason == option) null else option })
                        Text(option)
                    }
                }
                if (reason == "Other") {
                    OutlinedTextField(
                        note, { note = it.take(160) }, label = { Text("Optional note") },
                        supportingText = { Text("${note.length}/160") }, modifier = Modifier.fillMaxWidth(),
                        minLines = 2, maxLines = 3
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason, if (reason == "Other") note.trim().takeIf { it.isNotEmpty() } else null) },
                enabled = enabled, modifier = Modifier.heightIn(min = 64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumAmber, contentColor = Color.White)
            ) { Text("Confirm hold") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 64.dp)) { Text("Cancel") }
        }
    )
}

@Composable
private fun StopCard(vm: PlannerViewModel, p: Plan, stop: Stop, index: Int, current: Boolean, showShareReport: Boolean = true, onClick: () -> Unit) {
    PremiumCard(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        borderColor = if (current) PremiumGold else null
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index + 1}. ${stop.place.postal}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                StatusBadge(stop.status)
            }
            if (current) Text("Current delivery", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
            if (stop.order == null) {
                Text(stop.place.address, style = MaterialTheme.typography.bodyLarge)
                Text("Block ${stop.place.block} • ${stop.place.area}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Block ${stop.place.block} • ${stop.place.area}", style = MaterialTheme.typography.titleMedium)
                CustomerDetails(vm,stop)
            }
            OrderProofSection(vm,p,stop,ProofKind.PAYMENT)
            OrderProofSection(vm,p,stop,ProofKind.DELIVERY)
            Text("Planned arrival: ${time(stop.arrival)} • Departure: ${time(stop.leave)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            stop.etaArrival?.let { Text("Updated ETA: ${time(it)} • Finish: ${time(stop.etaLeave ?: stop.leave)}", style = MaterialTheme.typography.bodyMedium) }
            stop.completedAt?.let { Text("Actual / Delivered at: ${time(it)}", style = MaterialTheme.typography.bodyMedium) }
            holdDetails(stop)?.let { Text("On hold: $it", style = MaterialTheme.typography.bodyMedium) }
            Text("Distance from previous stop: ${km(stop.leg.km)}")
            if (showShareReport) OrderShareButton(stop,index,!vm.busy)
            Text(if (normalizedStatus(stop.status) == "DELIVERED") "Tap to view this delivery" else "Tap to revisit this delivery", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun Results(vm: PlannerViewModel, p: Plan) {
    val context = LocalContext.current
    val summary = deliverySummary(p)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageTitle("Your optimized route", "${p.startLocation.displayName} → deliveries → ${p.startLocation.displayName}")
        PremiumCard(Modifier.fillMaxWidth(), borderColor = PremiumGold.copy(alpha = .4f)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.startLocation.reportLabel, style = MaterialTheme.typography.titleLarge)
                DetailRow("Start location", p.startLocation.displayName)
                DetailRow("End location", p.startLocation.displayName)
                DetailRow("Start", time(p.start))
                DetailRow("Total parcels", p.stops.size.toString())
                DetailRow("Distance", km(p.totalKm))
                DetailRow("Estimated finish", p.stops.lastOrNull()?.leave?.let(::time) ?: "—")
                DetailRow("Return to ${p.startLocation.displayName}", time(p.returned))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CountTile("Delivered", summary.delivered, Modifier.weight(1f), statusCountColor("DELIVERED"))
            CountTile("On hold", summary.onHold, Modifier.weight(1f), statusCountColor("ON_HOLD"))
            CountTile("Pending", summary.pending, Modifier.weight(1f))
        }
        PrimaryAction("Start or resume deliveries", { vm.screen = if (p.current in p.stops.indices) "Delivery" else "Summary" }, enabled = !vm.busy)
        SecondaryAction("View delivery summary", { vm.screen = "Summary" }, enabled = !vm.busy)
        PremiumCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Planning estimate • ${trafficLabel(p.mode)}", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                DetailRow("Driving time without allowance", duration(p.baseSeconds))
                DetailRow("Traffic allowance", duration(p.bufferSeconds))
                DetailRow("Delivery time", duration(p.serviceMinutes * p.stops.size * 60.0))
                DetailRow("Estimated route time", duration(p.baseSeconds + p.bufferSeconds + p.serviceMinutes * p.stops.size * 60.0))
                DetailRow("Final delivery", p.stops.lastOrNull()?.place?.postal ?: "—")
            }
        }
        // Three waypoints is the conservative supported limit on mobile browsers.
        if (p.stops.size <= 3) {
            SecondaryAction("Open in Google Maps", {
                if (!tryOpenMap(context, routeGoogleMapsIntent(p))) mapUnavailable(context)
            }, enabled = !vm.busy)
        } else {
            Text("Google Maps can open up to 3 delivery stops together in a mobile browser. Your full ${p.stops.size}-stop route is shown here in optimized order. Tap Navigate on the Delivery screen for directions to each stop.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Complete delivery schedule", style = MaterialTheme.typography.titleLarge)
        Text("Swipe the table horizontally to see driving times, delivery periods and departures.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ScheduleExportActions(p, enabled = !vm.busy)
        ScheduleTable(p)
        Text("Optimized stop order", style = MaterialTheme.typography.titleLarge)
        PremiumCard(Modifier.fillMaxWidth()) {
            Text("START — ${p.startLocation.reportLabel}\n${time(p.start)}", Modifier.padding(18.dp), style = MaterialTheme.typography.titleMedium)
        }
        p.stops.forEachIndexed { index, stop -> StopCard(vm, p, stop, index, index == p.current) { if (!vm.busy) vm.revisit(index) } }
        PremiumCard(Modifier.fillMaxWidth()) {
            Text("END — return to ${p.startLocation.reportLabel}\nPlanned return: ${time(p.returned)}", Modifier.padding(18.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun ScheduleTable(p: Plan) {
    val startPlace = p.startLocation.asPlace()
    val headings = listOf("Stop", "Postal code", "Block", "Area", "Planned arrival", "Distance from previous stop", "Driving time", "Traffic allowance", "Planned travel time", "Delivery period", "Planned departure", "Distance so far", "Updated ETA", "Updated finish", "Actual / Delivered at")
    val start = listOf("START", startPlace.postal, startPlace.block, startPlace.area, time(p.start), "—", "—", "—", "—", "—", time(p.start), "0.0 km", "—", "—", "—")
    val rows = p.stops.mapIndexed { index, stop ->
        listOf("${index + 1}", stop.place.postal, stop.place.block, stop.place.area, time(stop.arrival), km(stop.leg.km), duration(stop.leg.baseSeconds), duration(stop.leg.bufferSeconds), duration(stop.leg.plannedSeconds), "${time(stop.arrival)} – ${time(stop.leave)}", time(stop.leave), km(stop.cumulativeKm), stop.etaArrival?.let(::time) ?: "—", stop.etaLeave?.let(::time) ?: "—", stop.completedAt?.let(::time) ?: "—")
    }
    val end = listOf("END", startPlace.postal, startPlace.block, startPlace.area, time(p.returned), km(p.returnLeg.km), duration(p.returnLeg.baseSeconds), duration(p.returnLeg.bufferSeconds), duration(p.returnLeg.plannedSeconds), "—", time(p.returned), km(p.totalKm), p.etaReturned?.let(::time) ?: "—", "—", "—")
    Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            (listOf(headings, start) + rows + listOf(end)).forEachIndexed { index, row ->
                val background = if (index == 0) PremiumNavy else if (index % 2 == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                Row(Modifier.background(background)) {
                    row.forEach {
                        Text(it, Modifier.width(160.dp).padding(14.dp), color = if (index == 0) Color.White else MaterialTheme.colorScheme.onSurface, style = if (index == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun Summary(vm: PlannerViewModel, p: Plan) {
    val context = LocalContext.current
    val summary = deliverySummary(p)
    var cash by rememberSaveable(p.id, p.cashOnHand) { mutableStateOf(p.cashOnHand) }
    var tax by rememberSaveable(p.id, p.tax) { mutableStateOf(p.tax) }
    var rate by rememberSaveable(p.id, p.exchangeRate) { mutableStateOf(p.exchangeRate) }
    var remark by rememberSaveable(p.id, p.remark) { mutableStateOf(p.remark) }
    LaunchedEffect(p.id) { vm.refreshExchangeRate() }
    val estimated = p.reviewedFinishedAt == null && p.actualCompletion == null
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageTitle("Delivery Report Summary", if (estimated) "Your route progress and estimated finish time." else "Your delivery progress and summary, saved on this device.")
        PremiumCard(Modifier.fillMaxWidth(), borderColor = PremiumGold.copy(alpha = .5f), containerColor = PremiumNavy) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Delivery success rate", color = PremiumGold, style = MaterialTheme.typography.labelLarge)
                Text("%.1f%%".format(Locale.US, summary.successRate), color = Color.White, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                Text("${summary.delivered} of ${summary.totalParcel} parcels delivered", color = Color(0xFFB7C7E1), style = MaterialTheme.typography.titleMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CountTile("Total parcels", summary.totalParcel, Modifier.weight(1f))
            CountTile("Delivered", summary.delivered, Modifier.weight(1f), statusCountColor("DELIVERED"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CountTile("On hold", summary.onHold, Modifier.weight(1f), statusCountColor("ON_HOLD"))
            CountTile("Skipped", summary.skipped, Modifier.weight(1f), statusCountColor("SKIPPED"))
            CountTile("Pending", summary.pending, Modifier.weight(1f))
        }
        PremiumCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Route details", style = MaterialTheme.typography.titleLarge)
                DetailRow("Date", p.deliveryDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)))
                DetailRow("Start location", p.startLocation.reportLabel)
                DetailRow("End location", p.startLocation.reportLabel)
                DetailRow("Start", time(summary.start))
                DetailRow(if (estimated) "Estimated finish" else "Finish", time(summary.finish))
                DetailRow(if (estimated) "Estimated route time" else "Total route time", duration(summary.totalRouteSeconds))
                DetailRow("Total distance", km(summary.totalKm))
                DetailRow("Planned return", time(summary.returned))
            }
        }
        PremiumCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Cash on hand and tax", style = MaterialTheme.typography.titleLarge)
                Text("Enter these amounts manually, then save them with this route.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                CurrencyField("Cash on hand", cash, { if (validCurrencyInput(it)) cash = it }, !vm.busy)
                CurrencyField("Tax", tax, { if (validCurrencyInput(it)) tax = it }, !vm.busy)
                Text("Cash on Hand: ${formatCurrency(cash)}")
                Text("Tax: ${formatCurrency(tax)} (RM ${taxMyr(tax, rate)})")
                OutlinedTextField(rate, { rate = it }, label = { Text("Rate (SGD → MYR)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = normalizedExchangeRate(rate) == null, singleLine = true, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
                if (vm.rateNotice.isNotBlank()) Text(vm.rateNotice, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { rate = vm.latestExchangeRate }, enabled = !vm.busy && !vm.rateBusy) {
                    Text("Use latest rate: ${vm.latestExchangeRate}")
                }
                TextButton(onClick = vm::refreshExchangeRate, enabled = !vm.rateBusy) { Text("Refresh online rate") }
                OutlinedTextField(remark, { remark = it.take(1000) }, label = { Text("Remark") }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
                PrimaryAction("Save Summary", { vm.saveSummary(cash, tax, rate, remark) }, enabled = !vm.busy && normalizedCurrency(cash) != null && normalizedCurrency(tax) != null && normalizedExchangeRate(rate) != null)
                p.summarySavedAt?.let { Text("Summary saved ${time(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        SecondaryAction("Return to ${p.startLocation.displayName}", { navigate(context, p.startLocation.asPlace()) }, enabled = !vm.busy)
        SecondaryAction("View full route and schedule", { vm.screen = "Route" }, enabled = !vm.busy)
        val remaining = p.stops.withIndex().filter { normalizedStatus(it.value.status) != "DELIVERED" }
        if (remaining.isNotEmpty()) {
            Text("Revisit a delivery", style = MaterialTheme.typography.titleLarge)
            Text("Parcels that are on hold, skipped or pending can still be delivered.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            remaining.forEach { (index, stop) -> StopCard(vm, p, stop, index, false) { if (!vm.busy) vm.revisit(index) } }
        }
    }
}

@Composable
private fun CurrencyField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, prefix = { Text("SGD ") }, placeholder = { Text("0.00") },
        supportingText = { Text(if (value.isNotBlank() && normalizedCurrency(value) == null) "Enter a non-negative amount with up to 2 decimal places, such as 12.50." else formatCurrency(value)) },
        isError = value.isNotBlank() && normalizedCurrency(value) == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true, enabled = enabled
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun History(vm: PlannerViewModel) {
    val saved by vm.history.collectAsState(initial = emptyList())
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var confirmIds by remember { mutableStateOf<Set<String>?>(null) }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    confirmIds?.let { ids ->
        AlertDialog(onDismissRequest = { confirmIds = null }, title = { Text("Delete selected history?") },
            text = { Text("Permanently delete ${ids.size} selected record(s)? This cannot be undone.") },
            confirmButton = { TextButton(onClick = {
                vm.deleteHistory(ids); confirmIds = null; selected = emptyList(); selecting = false
            }, enabled = !vm.busy) { Text("Delete Selected") } },
            dismissButton = { TextButton(onClick = { confirmIds = null }) { Text("Cancel") } })
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            PageTitle("Route history", "Tap to open details. Select records to delete them.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selecting = !selecting; selected = emptyList() }, enabled = !vm.busy) {
                    Text(if (selecting) "Cancel selection" else "Select history")
                }
                if (selecting) TextButton(onClick = { confirmIds = selected.toSet() }, enabled = selected.isNotEmpty() && !vm.busy) {
                    Text("Delete Selected (${selected.size})")
                }
            }
        }
        if (saved.isEmpty()) item {
            PremiumCard(Modifier.fillMaxWidth()) { Text("Your saved routes will appear here after you optimize your first route.", Modifier.padding(20.dp)) }
        }
        items(saved, key = { it.id }) { record ->
            val p = runCatching { vm.repo.decode(record) }.getOrNull()
            PremiumCard(Modifier.fillMaxWidth().combinedClickable(
                enabled = !vm.busy,
                onClick = { if (selecting) toggle(record.id) else vm.open(record) },
                onLongClick = { selecting = true; toggle(record.id) }
            )) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (selecting) Checkbox(record.id in selected, { toggle(record.id) }, enabled = !vm.busy)
                    if (p == null) {
                        Text("This saved route could not be read. Its stored data has been kept.")
                    } else {
                        val summary = deliverySummary(p)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.deliveryDate.toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            if (isRouteCompleted(p)) Surface(color = PremiumNavy, contentColor = PremiumGold,
                                shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, PremiumGold)) {
                                Text("COMPLETED", Modifier.padding(8.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                        DetailRow("Total Parcel", summary.totalParcel.toString())
                        DetailRow("Success Rate", "%.1f%%".format(Locale.ENGLISH, summary.successRate))
                        DetailRow("Total Distance", km(summary.totalKm))
                        DetailRow("Finish Time", time(summary.finish))
                    }
                }
            }
        }
    }
}

@Composable
fun RouteMap(p: Plan) {
    val context = LocalContext.current
    val map = remember { MapView(context).apply {
        setMultiTouchControls(true)
        setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
    } }
    DisposableEffect(map) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    Column(Modifier.fillMaxSize()) {
        Text("Red marker: Next Delivery / Current Target • © OpenStreetMap contributors", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().weight(1f), update = { view ->
            view.overlays.clear()
            val line = Polyline().apply {
                setPoints(p.geometry.map { GeoPoint(it[1], it[0]) })
                outlinePaint.color = android.graphics.Color.rgb(24, 60, 120)
                outlinePaint.strokeWidth = 7f
            }
            view.overlays.add(line)
            p.stops.getOrNull(p.current)?.let { stop ->
                view.overlays.add(Marker(view).apply {
                    position = GeoPoint(stop.place.lat, stop.place.lon)
                    title = "Next Delivery / Current Target: ${stop.place.postal}"
                    snippet = stop.place.address
                    icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_current_delivery)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            val startPlace = p.startLocation.asPlace()
            val points = p.stops.map { GeoPoint(it.place.lat, it.place.lon) } + GeoPoint(startPlace.lat, startPlace.lon)
            view.post { view.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, 80) }
            view.invalidate()
        })
    }
}
