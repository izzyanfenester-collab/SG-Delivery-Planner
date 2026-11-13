package com.izzyan.sgdeliveryplanner

import android.app.TimePickerDialog
import android.Manifest
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(8, 27, 51))
        )
        org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName
        runCatching { PostalDatasetScheduler.schedule(applicationContext) }
        runCatching { AppUpdateScheduler.schedule(applicationContext) }
        runCatching { OfflineMapUpdateScheduler.schedule(applicationContext) }
        setContent {
            val vm: PlannerViewModel = viewModel()
            // Dark mode is permanent, regardless of Android's system theme or old saved preferences.
            IzzDeliveryTheme(dark = true) { App(vm) }
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
    RoutePostalReviewDialog(vm)
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && SmartShareSession.active)
                SmartShareSession.finish("Returned to SG Delivery. Send status is unknown; no automatic resend.")
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
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
                // Keep the safe-area inset and 48dp touch targets, but reclaim 20dp vertically on every page.
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.canGoBack) {
                            TextButton(
                                onClick = vm::goBack, enabled = !vm.busy,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = PremiumGold,
                                    disabledContentColor = SGColors.Disabled
                                ),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("← Back", style = MaterialTheme.typography.titleMedium) }
                        }
                        Spacer(Modifier.weight(1f))
                        if (vm.screen in setOf("Home", "Route", "Delivery", "Map", "History", "Payment")) {
                            SGIconButton(onClick = { vm.screen = "Settings" }, enabled = !vm.busy) {
                                Icon(androidx.compose.material.icons.Icons.Outlined.Settings,
                                    contentDescription = "Settings", tint = SGColors.White,
                                    modifier = Modifier.size(SGDimensions.Icon))
                            }
                            ChatGptHeaderButton()
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (vm.screen != "LocationPicker") {
                SGBottomNavigation(selected = vm.screen, enabled = !vm.busy) { destination ->
                    vm.screen = destination
                }
            }
        }
    ) { padding ->
        MarinaBayBackdrop(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
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
                    if (vm.failedRoutePostalChecks.isNotEmpty() && vm.screen == "Home") {
                        OutlinedButton(
                            onClick = { vm.reviewFailedRoutePostals = true },
                            modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp)
                        ) { Text("REVIEW FAILED ORDERS (${vm.failedRoutePostalChecks.size})") }
                    }
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
            val pageRoute = if (vm.screen == "Route" || vm.screen == "Delivery") vm.activeRoute else vm.route
            val screenStateKey = if (vm.screen in setOf("Route", "Delivery", "Map", "Summary"))
                "${vm.screen}:${pageRoute?.id.orEmpty()}" else vm.screen
            screenStates.SaveableStateProvider(screenStateKey) {
                when (vm.screen) {
                    "Home" -> Home(vm)
                    "Settings" -> Settings(vm)
                    "History" -> History(vm)
                    "Payment" -> PaymentScreen(vm)
                    "LocationPicker" -> StartLocationPicker(vm)
                    "Route", "Delivery" -> {
                        val p = vm.activeRoute
                        if (p == null) ActiveTripSelection(vm, vm.screen)
                        else if (vm.screen == "Delivery") Delivery(vm, p)
                        else Results(vm, p)
                    }
                    else -> {
                        val p = pageRoute
                        if (p == null) {
                            Column(Modifier.padding(SGDimensions.Normal), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                PremiumCard(Modifier.fillMaxWidth()) {
                                    Text("Your next delivery starts here", Modifier.padding(SGDimensions.Normal), style = MaterialTheme.typography.titleLarge)
                                    Text("Plan a route on Home or open a saved route from History.", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp))
                                }
                                PrimaryAction("Plan a route", { vm.screen = "Home" }, enabled = !vm.busy)
                            }
                        } else when (vm.screen) {
                            "Map" -> RouteMap(p)
                            "Summary" -> Summary(vm, p)
                            else -> Results(vm, p)
                        }
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

/** Only unfinished persisted trips are offered; choosing one is explicit. */
@Composable
private fun ActiveTripSelection(vm: PlannerViewModel, destination: String) {
    val saved by vm.history.collectAsState(initial = emptyList())
    val plans = remember(saved) { unfinishedSavedPlans(saved) }
    val delivery = destination == "Delivery"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(SGDimensions.Normal),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            if (plans.isEmpty()) {
                PageTitle(if (delivery) "No Active Deliveries" else "No Active Routes",
                    if (delivery) "All deliveries are completed. Create a new route or select an unfinished trip from History."
                    else "Plan a new route on Home or select an unfinished trip from History.")
                PrimaryAction("Go to Home", { vm.screen = "Home" }, enabled = !vm.busy)
            } else {
                PageTitle(if (delivery) "Select an Active Delivery" else "Select an Active Route",
                    "Choose an unfinished trip to continue. Completed trips remain in History.")
            }
        }
        items(plans, key = { it.id }) { plan ->
            val status = savedDeliveryStatus(plan)
            PremiumCard(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) {
                vm.continueSavedRoute(plan.id, destination)
            }, borderColor = historyStatusAccent(status).copy(alpha = .38f)) {
                Column(Modifier.padding(SGDimensions.Normal), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(plan.deliveryDate.toString(), style = MaterialTheme.typography.titleLarge)
                    Text(status.label, color = historyStatusAccent(status), fontWeight = FontWeight.Bold)
                    DetailRow("Route ID", plan.id.take(8))
                    DetailRow("Delivered / Total", "${deliverySummary(plan).delivered} / ${plan.stops.size}")
                    Text("Tap to continue", color = PremiumGold, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (plans.isNotEmpty()) item {
            SecondaryAction("Go to Home", { vm.screen = "Home" }, enabled = !vm.busy)
        }
    }
}

/** Read-only schedule, accessible without reactivating a completed trip. */
@Composable
internal fun CompletedRouteSchedule(plan: Plan, onClose: () -> Unit) {
    var expanded by rememberSaveable(plan.id) { mutableStateOf(true) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        MarinaBayBackdrop(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(SGDimensions.Normal),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved route & schedule", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, color = PremiumGold)
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${plan.startLocation.displayName} → ${plan.endDisplayName}",
                        style = MaterialTheme.typography.titleMedium)
                    ScheduleExportActions(plan, enabled = true, expanded = expanded,
                        onToggleExpanded = { expanded = !expanded })
                    if (expanded) ScheduleTable(plan)
                }
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String? = null) {
    SGPageHeader(title, subtitle)
}

@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = SGColors.Gold,
    minHeight: Dp = 52.dp
) {
    if (color == SGColors.Gold || color == PremiumRoyal) {
        SGPrimaryButton(label, onClick, modifier.fillMaxWidth(), enabled, minHeight)
    } else {
        Button(onClick = onClick, enabled = enabled,
            modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
            colors = ButtonDefaults.buttonColors(containerColor = color,
                contentColor = Color.White),
            shape = RoundedCornerShape(SGDimensions.RadiusSmall)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SecondaryAction(
    label: String, onClick: () -> Unit, enabled: Boolean = true,
    modifier: Modifier = Modifier, minHeight: Dp = 52.dp
) {
    SGSecondaryButton(label, onClick, modifier.fillMaxWidth(), enabled, minHeight)
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CountTile(label: String, count: Int, modifier: Modifier = Modifier, color: Color? = null, compact: Boolean = false) {
    Surface(
        modifier = modifier, color = SGColors.Card,
        shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
    ) {
        Column(Modifier.padding(if (compact) 9.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)) {
            Text(count.toString(),
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                color = color ?: MaterialTheme.colorScheme.onSurface)
            Text(label, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeDateTimeAction(
    label: String, value: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = SGDimensions.Touch),
        shape = RoundedCornerShape(SGDimensions.RadiusSmall),
        border = BorderStroke(1.dp, SGColors.Gold),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = SGColors.SecondaryBackground, contentColor = SGColors.White),
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, fontSize = 12.sp, lineHeight = 14.sp)
            Text(value, fontSize = 14.sp, lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun HomeSmallAction(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, filled: Boolean = false
) {
    if (filled) {
        SGPrimaryButton(label, onClick, modifier.fillMaxWidth(), enabled)
    } else {
        SGSecondaryButton(label, onClick, modifier.fillMaxWidth(), enabled)
    }
}

@Composable
fun DeliveryDateControl(vm: PlannerViewModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val value = vm.deliveryDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    val pickDate = { deliveryDatePicker(context, vm.deliveryDate, vm::selectDeliveryDate).show(); Unit }
    if (compact) {
        HomeDateTimeAction("Delivery Date", value, pickDate, modifier, enabled = !vm.busy)
    } else {
        SecondaryAction("Delivery Date: " + value, pickDate,
            enabled = !vm.busy, modifier = modifier)
    }
}

@Composable
fun Clock(vm: PlannerViewModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val value = LocalTime.of(vm.startMinute / 60, vm.startMinute % 60)
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    val pickTime = {
        TimePickerDialog(context, { _, hour, minute ->
            vm.startMinute = hour * 60 + minute
            vm.persist()
        }, vm.startMinute / 60, vm.startMinute % 60, false).show()
        Unit
    }
    if (compact) {
        HomeDateTimeAction("Start time", value, pickTime, modifier, enabled = !vm.busy)
    } else {
        SecondaryAction("Start time: " + value, pickTime,
            enabled = !vm.busy, modifier = modifier)
    }
}

@Composable
fun Home(vm: PlannerViewModel) {
    val parsed = parseInput(vm.input)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PageTitle("Plan your delivery", "Choose where to start and finish your delivery route.")
        StartLocationControls(vm)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeliveryDateControl(vm, Modifier.weight(1f), compact = true)
            Clock(vm, Modifier.weight(1f), compact = true)
        }

        Text("Delivery time per stop: " + vm.service + " minutes",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)

        SGTextField(
            value = vm.input, onValueChange = vm::updatePostalInput,
            label = "Singapore postal codes",
            placeholder = "Paste Singapore postal codes here\n730120\n730301\n760270\n560211\n460079\n640208",
            modifier = Modifier.fillMaxWidth().heightIn(min = 168.dp),
            minLines = 5, maxLines = 5, singleLine = false, enabled = !vm.busy
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeSmallAction("Import WhatsApp Orders",
                { vm.importOrdersDialog = true }, Modifier.weight(1f), enabled = !vm.busy)
            HomeSmallAction("Optimize route",
                vm::optimize, Modifier.weight(1f), enabled = !vm.busy, filled = true)
        }

        if (vm.draftOrders.isNotEmpty()) {
            Text("Orders found: " + vm.planningOrders().size +
                " · Duplicate postals preserved: " +
                (vm.planningOrders().size - vm.planningOrders().map { it.postalCode }.distinct().size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountTile("Valid stops",
                if (vm.draftOrders.isEmpty()) parsed.valid.size else vm.planningOrders().size,
                Modifier.weight(1f), compact = true)
            CountTile(if (vm.draftOrders.isEmpty()) "Duplicates removed" else "Duplicate postals preserved",
                parsed.duplicates, Modifier.weight(1f), compact = true)
        }

        if (parsed.invalid.isNotEmpty()) {
            PremiumCard(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text("Invalid postal codes: " + parsed.invalid.joinToString(),
                    Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        HomeTaxActionRow(vm)

        Text("Routes follow roads and support up to 50 unique delivery stops.\nTimes are planning estimates and do not include live traffic.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Settings(vm: PlannerViewModel) {
    var serviceText by remember(vm.service) { mutableStateOf(vm.service.toString()) }
    val postalContext = LocalContext.current
    val postalScope = rememberCoroutineScope()
    var postalStatus by remember { mutableStateOf<PostalUpdateState?>(null) }
    var checkingPostal by remember { mutableStateOf(false) }
    var appUpdateStatus by remember { mutableStateOf(AppUpdateState.load(postalContext)) }
    var checkingAppUpdate by remember { mutableStateOf(false) }
    var appNotificationsAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 ||
            postalContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val appNotificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        appNotificationsAllowed = it
    }
    LaunchedEffect(postalContext) {
        postalStatus = PostalUpdateState.load(postalContext)
    }
    LaunchedEffect(postalContext) {
        if (System.currentTimeMillis() - appUpdateStatus.lastChecked >= APP_UPDATE_CHECK_INTERVAL_MS) {
            checkingAppUpdate = true
            try {
                appUpdateStatus = withContext(Dispatchers.IO) { AppReleaseChecker(postalContext).checkNow() }
            } finally { checkingAppUpdate = false }
        }
    }
    fun readablePostalDate(millis: Long): String = if (millis == 0L) "Not yet"
        else java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Singapore"))
            .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH))
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = SGDimensions.ScreenHorizontal, vertical = SGDimensions.Small),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SGColors.White)
        Text("Navigation, maps & delivery preferences", fontSize = 13.sp, color = SGColors.Silver)
        NavigationAppearanceSettingsCard()
        OfflineMapSettingsCard(vm)
        val postal = postalStatus
        CompactSettingsSection("Singapore Postal Database", if (checkingPostal) "Checking…" else postal?.status ?: "Loading…",
            Icons.Outlined.Storage, settingsPostalColor(postal?.status, checkingPostal), essential = {
                val state = postalStatus
                if (state == null) {
                    Text("Loading offline database status…")
                } else {
                    CompactSettingsMetrics(listOf("Version" to state.version.toString(),
                        "Postal codes" to "%,d".format(Locale.ENGLISH, state.total),
                        "Sources" to state.sources.toString(),
                        "Last checked" to compactSettingsDate(state.lastChecked),
                        "Last updated" to compactSettingsDate(state.lastUpdated)))
                    if (state.status == "Update failed") Text(state.detail, fontSize = 12.sp, color = SGColors.Error)
                }
                OutlinedButton(onClick = {
                    postalScope.launch {
                        checkingPostal = true
                        try { postalStatus = PostalSnapshotUpdater(postalContext).checkAndRecord() }
                        finally { checkingPostal = false }
                    }
                }, enabled = !checkingPostal, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    if (checkingPostal) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Check / Update Database", fontSize = 14.sp)
                }
            }) {
                postal?.let { state ->
                    CompactSettingsMetric("Checked at", readablePostalDate(state.lastChecked))
                    CompactSettingsMetric("Successful update", readablePostalDate(state.lastUpdated))
                    if (state.newCodes > 0) CompactSettingsMetric("New postal codes", state.newCodes.toString())
                    CompactSettingsDetail(state.detail)
                }
                CompactSettingsDetail("Checks automatically every 7 days when internet is available. Failed updates retain the last installed database.")
        }
        val appState = appUpdateStatus
        val appStatus = when {
            checkingAppUpdate -> "Checking…"
            appState.error.isNotEmpty() -> "Unable to check"
            appState.updateAvailable -> "New version available"
            appState.lastChecked > 0 && appState.latestBuild != null -> "Up to date"
            else -> "Not checked yet"
        }
        CompactSettingsSection("App Updates", "Build #${appState.currentBuild} · $appStatus", Icons.Outlined.SystemUpdate,
            settingsAppColor(appState, checkingAppUpdate), essential = {
                val state = appUpdateStatus
                val statusText = when {
                    checkingAppUpdate -> "Checking…"
                    state.updateAvailable -> "New version available"
                    state.error.isNotEmpty() -> "Unable to check"
                    state.lastChecked > 0 -> "Up to date"
                    else -> "Not checked yet"
                }
                // Status and build are shown in the compact header.
                if (state.error.isNotEmpty()) Text(state.error,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = {
                    postalScope.launch {
                        checkingAppUpdate = true
                        try {
                            appUpdateStatus = withContext(Dispatchers.IO) { AppReleaseChecker(postalContext).checkNow() }
                        } finally { checkingAppUpdate = false }
                    }
                }, enabled = !checkingAppUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    if (checkingAppUpdate) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Check for App Updates")
                }
                if (state.updateAvailable) {
                    Button(onClick = {
                        try {
                            postalContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.downloadUrl)))
                        } catch (_: Exception) {
                            Toast.makeText(postalContext, "A browser is required to download the APK.", Toast.LENGTH_LONG).show()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Download & Install") }
                }
            }) {
                val state = appUpdateStatus
                CompactSettingsMetric("Current version", "Build #${state.currentBuild}")
                CompactSettingsMetric("Latest version", state.latestBuild?.let { "Build #$it" } ?: "Not available")
                CompactSettingsMetric("Last checked", readablePostalDate(state.lastChecked))
                if (state.updateAvailable) CompactSettingsDetail("Downloads the official APK in your browser. Android will ask you to confirm installation.")
                if (!appNotificationsAllowed && Build.VERSION.SDK_INT >= 33) {
                    OutlinedButton(onClick = { appNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.fillMaxWidth()) { Text("Enable update notifications") }
                }
                CompactSettingsDetail("Checks automatically every day when internet is available. No account or API token required.")
        }
        CompactSettingsBackup(vm)
        CompactSettingsSection("Default WhatsApp App", vm.whatsappPreferences.default?.label ?: "Ask every time", Icons.Outlined.Chat) {
                (listOf<WhatsAppChoice?>(null) + WhatsAppChoice.entries).forEach { choice ->
                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(vm.whatsappPreferences.default == choice, enabled=!vm.busy, role=androidx.compose.ui.semantics.Role.RadioButton,
                        onClick={vm.whatsappPreferences.updateDefault(choice)}),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(vm.whatsappPreferences.default == choice,null,enabled=!vm.busy)
                        Text(choice?.label ?: "Ask every time",Modifier.padding(start=5.dp))
                    }
                }
        }
        SmartWhatsAppSettings(vm)
        CompactSettingsSection("Default Navigation", vm.navigationPreferences.default?.label ?: "Ask every time", Icons.Outlined.Navigation) {
                (listOf<NavigationChoice?>(null) + NavigationChoice.entries).forEach { choice ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(vm.navigationPreferences.default == choice, enabled = !vm.busy, role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { vm.navigationPreferences.updateDefault(choice) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(vm.navigationPreferences.default == choice, onClick = null, enabled = !vm.busy)
                        Text(choice?.label ?: "Ask every time", Modifier.padding(start = 5.dp))
                    }
                }
        }
        CompactGpsPreferences()
        CompactSettingsSection("Schedule", "${vm.service} min/stop · ${vm.traffic}", Icons.Outlined.Schedule) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeliveryDateControl(vm, Modifier.weight(1f), compact = true)
                    Clock(vm, Modifier.weight(1f), compact = true)
                }
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
                Text("Traffic estimate", fontSize = 15.sp, color = SGColors.Gold)
                listOf("Normal", "Light Traffic", "Heavy Traffic").forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { vm.traffic = mode; vm.persist() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(vm.traffic == mode, { vm.traffic = mode; vm.persist() })
                        Text(trafficLabel(mode))
                    }
                }
        }
        CompactSettingsSection("Road Routing", "OSRM-compatible server", Icons.Outlined.Route) {
                OutlinedTextField(
                    vm.endpoint, { vm.endpoint = it.trim(); vm.persist() }, label = { Text("Routing server address (HTTPS)") },
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                CompactSettingsDetail("The default routing server is for evaluation and may be unavailable. For regular deliveries, use a reliable OSRM-compatible server with Singapore road data. Saved routes keep the settings used when they were planned.")
        }
        CompactSettingsSection("Language", "English", Icons.Outlined.Language) {
            CompactSettingsDetail("SG Delivery uses English for app and framework captions. Your device language is unchanged.")
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

/** Each route keeps independent Start and End snapshots even after location preferences change. */
internal fun routeGoogleMapsIntent(plan: Plan): Intent {
    val start = plan.startLocation.asPlace()
    val end = plan.endPlace
    val intermediateStops = if (plan.endAtLastDelivery) plan.stops.dropLast(1) else plan.stops
    val waypoints = intermediateStops.joinToString("|") { "${it.place.lat},${it.place.lon}" }
    val waypointParameter = if (waypoints.isBlank()) "" else "&waypoints=${Uri.encode(waypoints)}"
    return Intent(Intent.ACTION_VIEW, Uri.parse(
        "https://www.google.com/maps/dir/?api=1&origin=${start.lat},${start.lon}" +
            "&destination=${end.lat},${end.lon}&travelmode=driving$waypointParameter"
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
    "SKIPPED" -> SGColors.Disabled
    else -> PremiumRoyal
}

@Composable
private fun statusCountColor(status: String): Color {
    val light = MaterialTheme.colorScheme.onSurface == PremiumNavy
    return when (normalizedStatus(status)) {
        "DELIVERED" -> if (light) PremiumEmerald else SGColors.SuccessText
        "ON_HOLD" -> if (light) PremiumAmber else SGColors.SelectedGold
        "SKIPPED" -> if (light) SGColors.Disabled else SGColors.Silver
        else -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun StatusBadge(status: String) {
    SGStatusBadge(status)
}

fun holdDetails(stop: Stop): String? {
    if (normalizedStatus(stop.status) != "ON_HOLD") return null
    return listOfNotNull(stop.holdReason?.takeIf { it.isNotBlank() }, stop.holdNote?.takeIf { it.isNotBlank() }).joinToString(" • ").takeIf { it.isNotBlank() }
}

// Compact, icon-led delivery controls. Only the active stop's action panel uses these;
// other app buttons retain their existing size and behavior.
@Composable
private fun CompactDeliveryAction(
    label: String,
    iconRes: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color? = null,
    compactText: Boolean = false,
    completed: Boolean = false
) {
    val shape = RoundedCornerShape(SGDimensions.RadiusCard)
    val contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
    if (color == null) {
        OutlinedButton(
            onClick = onClick, enabled = enabled,
            modifier = modifier.height(52.dp),
            shape = shape, contentPadding = contentPadding,
            border = BorderStroke(1.dp, SGColors.Gold),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = SGColors.SecondaryBackground, contentColor = SGColors.White)
        ) {
            CompactDeliveryActionContent(label, iconRes, compactText, completed)
        }
    } else {
        Button(
            onClick = onClick, enabled = enabled,
            modifier = modifier.height(52.dp),
            shape = shape, contentPadding = contentPadding,
            colors = ButtonDefaults.buttonColors(containerColor = color,
                contentColor = if (color == SGColors.Warning) SGColors.Background else SGColors.White),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
        ) {
            CompactDeliveryActionContent(label, iconRes, compactText, completed)
        }
    }
}

@Composable
private fun CompactDeliveryActionContent(
    label: String,
    iconRes: ImageVector,
    compactText: Boolean,
    completed: Boolean
) {
    val iconSize = SGDimensions.Icon
    val currentColor = LocalContentColor.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = iconRes,
                contentDescription = if (completed) "$label attached" else null,
                modifier = Modifier.fillMaxSize()
            )
            if (completed) {
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .size(12.dp)
                        .background(PremiumEmerald, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", fontSize = 9.sp, lineHeight = 10.sp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.width(if (compactText) 4.dp else 7.dp))
        Box(Modifier.width(1.dp).height(23.dp).background(currentColor.copy(alpha = 0.45f)))
        Spacer(Modifier.width(if (compactText) 5.dp else 8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontSize = if (compactText) 11.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = SGDimensions.ScreenHorizontal, vertical = SGDimensions.Small),
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
            if (!p.endAtLastDelivery) SecondaryAction("Navigate to End: ${p.endDisplayName}", { navigate(context, p.endPlace) }, enabled = !vm.busy)
            p.stops.forEachIndexed { index, stop ->
                if (normalizedStatus(stop.status) != "DELIVERED") StopCard(vm, p, stop, index, false, showShareReport = false) { vm.revisit(index) }
            }
        } else {
            val stop = p.stops[p.current]
            PremiumCard(Modifier.fillMaxWidth(), borderColor = PremiumGold, containerColor = SGColors.Card) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Stop ${p.current + 1} of ${p.stops.size}", Modifier.weight(1f), color = PremiumGold, style = MaterialTheme.typography.titleLarge)
                        stop.order?.takeIf { it.phoneNumber != null }?.let { SendOrderMessageButton(vm, stop) }
                    }
                    Text(stop.place.postal, color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Block ${stop.place.block} • ${stop.place.area}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    if (stop.order == null) Text(stop.place.address, color = SGColors.Silver, style = MaterialTheme.typography.bodyLarge) else CompositionLocalProvider(LocalContentColor provides Color.White) { CustomerDetails(vm,stop) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(stop.status)
                        Spacer(Modifier.weight(1f))
                        PaymentModeStamp(stop.order, size = 78.dp)
                    }
                    holdDetails(stop)?.let { Text(it, color = SGColors.SelectedGold, style = MaterialTheme.typography.bodyMedium) }
                    HorizontalDivider(color = SGColors.Divider)
                    Text("Planned arrival: ${time(stop.arrival)}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Delivery period: ${time(stop.arrival)} – ${time(stop.leave)}", color = SGColors.Silver)
                    stop.etaArrival?.let { Text("Updated ETA: ${time(it)} • Finish: ${time(stop.etaLeave ?: stop.leave)}", color = PremiumGold) }
                    stop.completedAt?.let { Text("Actual / Delivered at: ${time(it)}", color = PremiumGold) }
                    Text("Planning estimate", color = PremiumGold, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactDeliveryAction(
                        label = "Proof of Delivery", iconRes = Icons.Outlined.CameraAlt,
                        onClick = { vm.proofRequest = ProofRequest(p.id, stop.orderId, ProofKind.DELIVERY, chooseSource = true) },
                        modifier = Modifier.weight(1f), enabled = !vm.busy,
                        compactText = true, completed = stop.order?.proofFileId != null
                    )
                    CompactDeliveryAction(
                        label = "Proof of Payment", iconRes = Icons.Outlined.ReceiptLong,
                        onClick = { vm.proofRequest = ProofRequest(p.id, stop.orderId, ProofKind.PAYMENT, chooseSource = true) },
                        modifier = Modifier.weight(1f), enabled = !vm.busy,
                        compactText = true, completed = stop.order?.paymentProofFileId != null
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactDeliveryAction(
                        label = "Navigate", iconRes = Icons.Outlined.Navigation,
                        onClick = {
                            val choice = vm.navigationPreferences.default
                            showNavigation = choice == null || !openDeliveryNavigation(context, p, choice)
                        },
                        modifier = Modifier.weight(1f), enabled = !vm.busy, color = PremiumRoyal
                    )
                    CompactDeliveryAction(
                        label = "Delivered", iconRes = Icons.Outlined.CheckCircle,
                        onClick = { vm.progress("DELIVERED") },
                        modifier = Modifier.weight(1f),
                        enabled = !vm.busy && normalizedStatus(stop.status) != "DELIVERED",
                        color = PremiumEmerald
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactDeliveryAction(
                        label = "On Hold", iconRes = Icons.Outlined.PauseCircle,
                        onClick = { holdStop = p.current },
                        modifier = Modifier.weight(1f),
                        enabled = !vm.busy && normalizedStatus(stop.status) != "DELIVERED",
                        color = PremiumAmber
                    )
                    CompactDeliveryAction(
                        label = "Skipped", iconRes = Icons.Outlined.SkipNext,
                        onClick = { vm.progress("SKIP") },
                        modifier = Modifier.weight(1f),
                        enabled = !vm.busy && normalizedStatus(stop.status) != "DELIVERED",
                        color = SGColors.Disabled
                    )
                }
            }
            if (stop.order?.proofFileId != null) OrderProofSection(vm,p,stop,ProofKind.DELIVERY)
            if (stop.order?.paymentProofFileId != null) OrderProofSection(vm,p,stop,ProofKind.PAYMENT)
            CompactDeliveryAction(
                label = "Next stop", iconRes = Icons.Outlined.ArrowForward,
                onClick = { vm.progress("NEXT") },
                modifier = Modifier.fillMaxWidth(), enabled = !vm.busy
            )
            Text("Next stop marks this stop as reviewed and keeps its current status. You can revisit deliveries that are on hold, skipped or pending from the summary or route.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HoldDialog(onDismiss: () -> Unit, onConfirm: (String?, String?) -> Unit, enabled: Boolean) {
    val options = listOf("Customer not home", "No answer", "Reschedule", "Payment issue", "Access issue", "Joy buyer", "Other")
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var note by remember { mutableStateOf("") }
    val requiresManualReason = "Other" in selected
    val canConfirmHold = enabled && (!requiresManualReason || note.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Place delivery on hold") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Select all reasons that apply (optional). You can revisit this stop later.")
                options.forEach { option ->
                    val checked = option in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            selected = if (checked) selected - option else selected + option
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { isChecked ->
                                selected = if (isChecked) selected + option else selected - option
                            }
                        )
                        Text(option)
                    }
                }
                if ("Other" in selected) {
                    OutlinedTextField(
                        note, { note = it.take(160) }, label = { Text("Reason (required)") },
                        isError = note.isBlank(),
                        supportingText = { Text(if (note.isBlank()) "Please enter a reason to confirm hold." else "${note.length}/160") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2, maxLines = 3
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val reasons = options.filter { it in selected }.joinToString(" | ").takeIf { it.isNotBlank() }
                    onConfirm(reasons, if ("Other" in selected) note.trim().takeIf { it.isNotEmpty() } else null)
                },
                enabled = canConfirmHold, modifier = Modifier.heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumAmber, contentColor = Color.White)
            ) { Text("Confirm hold") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
        }
    )
}
@Composable
private fun StopCard(vm: PlannerViewModel, p: Plan, stop: Stop, index: Int, current: Boolean, showShareReport: Boolean = true, onClick: () -> Unit) {
    SGDeliveryCard(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        borderColor = if (current) SGColors.Gold else null
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index + 1}. ${stop.place.postal}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                StatusBadge(stop.status)
            }
            if (current || stop.order?.phoneNumber != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (current) Text("Current delivery", Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                    else Spacer(Modifier.weight(1f))
                    stop.order?.takeIf { it.phoneNumber != null }?.let { SendOrderMessageButton(vm, stop) }
                }
            }
            if (stop.order == null) {
                Text(stop.place.address, style = MaterialTheme.typography.bodyLarge)
                Text("Block ${stop.place.block} • ${stop.place.area}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Block ${stop.place.block} • ${stop.place.area}", style = MaterialTheme.typography.titleMedium)
                CustomerDetails(vm,stop)
            }
            val hasProofPhoto = stop.order?.paymentProofFileId != null ||
                stop.order?.proofFileId != null
            if (hasProofPhoto) {
                // Keep uploaded proof photos at full card width.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PaymentModeStamp(stop.order, size = 78.dp)
                }
                OrderProofSection(vm,p,stop,ProofKind.PAYMENT)
                OrderProofSection(vm,p,stop,ProofKind.DELIVERY)
            } else {
                // Use the right-hand blank space for the stamp when no proof exists.
                Box(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        OrderProofSection(vm,p,stop,ProofKind.PAYMENT)
                        OrderProofSection(vm,p,stop,ProofKind.DELIVERY)
                    }
                    PaymentModeStamp(stop.order,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                        size = 82.dp)
                }
            }
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
    // A newly optimized Plan has a unique ID: its schedule always starts collapsed.
    // Each saved route retains its own expansion state while the driver navigates.
    var scheduleExpanded by rememberSaveable(p.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SGDimensions.Normal),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            onClick = { scheduleExpanded = !scheduleExpanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = !vm.busy,
            shape = RoundedCornerShape(SGDimensions.RadiusSmall),
            border = BorderStroke(1.dp, PremiumGold.copy(alpha = .55f)),
            color = SGColors.Card
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Complete Delivery Schedule · ${p.stops.size} stops",
                    Modifier.weight(1f), color = SGColors.Gold,
                    style = MaterialTheme.typography.titleMedium)
                Icon(
                    if (scheduleExpanded) Icons.Outlined.KeyboardArrowUp
                        else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (scheduleExpanded) "Collapse delivery schedule"
                        else "Expand delivery schedule",
                    tint = SGColors.Gold
                )
            }
        }
        ScheduleExportActions(p, enabled = !vm.busy, expanded = scheduleExpanded, useDefaultWhatsApp = true,
            onToggleExpanded = { scheduleExpanded = !scheduleExpanded })
        if (scheduleExpanded) {
            Text("Swipe the table horizontally to see driving times, delivery periods and departures.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ScheduleTable(p)
        }
        Text("Optimized stop order", style = MaterialTheme.typography.titleLarge)
        PremiumCard(Modifier.fillMaxWidth()) {
            Text("START — ${p.startLocation.reportLabel}\n${time(p.start)}", Modifier.padding(18.dp), style = MaterialTheme.typography.titleMedium)
        }
        p.stops.forEachIndexed { index, stop -> StopCard(vm, p, stop, index, index == p.current) { if (!vm.busy) vm.revisit(index) } }
        PremiumCard(Modifier.fillMaxWidth()) {
            Text("END — ${p.endReportLabel}\nPlanned arrival: ${time(p.returned)}", Modifier.padding(18.dp), style = MaterialTheme.typography.titleMedium)
        }
        PageTitle("Your optimized route", "${p.startLocation.displayName} → deliveries → ${p.endDisplayName}")
        PremiumCard(Modifier.fillMaxWidth(), borderColor = PremiumGold.copy(alpha = .4f)) {
            Column(Modifier.padding(SGDimensions.Normal), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.startLocation.reportLabel, style = MaterialTheme.typography.titleLarge)
                DetailRow("Start location", p.startLocation.displayName)
                DetailRow("End location", p.endReportLabel)
                DetailRow("Start", time(p.start))
                DetailRow("Total parcels", p.stops.size.toString())
                if (p.routingSource == "Offline") DetailRow("Route calculation source", "Offline Singapore roads")
                DetailRow("Distance", km(p.totalKm))
                DetailRow("Estimated finish", p.stops.lastOrNull()?.leave?.let(::time) ?: "—")
                DetailRow("End arrival", time(p.returned))
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
            Column(Modifier.padding(SGDimensions.Normal), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

    }
}

@Composable
fun ScheduleTable(p: Plan) {
    val startPlace = p.startLocation.asPlace()
    val endPlace = p.endPlace
    val headings = listOf("Stop", "Postal code", "Block", "Area", "Planned arrival", "Distance from previous stop", "Driving time", "Traffic allowance", "Planned travel time", "Delivery period", "Planned departure", "Distance so far", "Updated ETA", "Updated finish", "Actual / Delivered at")
    val start = listOf("START", startPlace.postal, startPlace.block, startPlace.area, time(p.start), "—", "—", "—", "—", "—", time(p.start), "0.0 km", "—", "—", "—")
    val rows = p.stops.mapIndexed { index, stop ->
        listOf("${index + 1}", stop.place.postal, stop.place.block, stop.place.area, time(stop.arrival), km(stop.leg.km), duration(stop.leg.baseSeconds), duration(stop.leg.bufferSeconds), duration(stop.leg.plannedSeconds), "${time(stop.arrival)} – ${time(stop.leave)}", time(stop.leave), km(stop.cumulativeKm), stop.etaArrival?.let(::time) ?: "—", stop.etaLeave?.let(::time) ?: "—", stop.completedAt?.let(::time) ?: "—")
    }
    val end = listOf("END", endPlace.postal, endPlace.block, endPlace.area, time(p.returned), km(p.returnLeg.km), duration(p.returnLeg.baseSeconds), duration(p.returnLeg.bufferSeconds), duration(p.returnLeg.plannedSeconds), "—", time(p.returned), km(p.totalKm), p.etaReturned?.let(::time) ?: "—", "—", "—")
    Surface(color = Color.Transparent, shape = RoundedCornerShape(SGDimensions.RadiusCard), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            (listOf(headings, start) + rows + listOf(end)).forEachIndexed { index, row ->
                val background = SGColors.Card // One consistent 30%-opaque Navy fill per schedule row.
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
    var showCompletedSchedule by remember(p.id) { mutableStateOf(false) }
    if (showCompletedSchedule) CompletedRouteSchedule(p) { showCompletedSchedule = false }
    var showEndNavigation by remember(p.id) { mutableStateOf(false) }
    var failedEndChoice by remember(p.id) { mutableStateOf<NavigationChoice?>(null) }
    if (showEndNavigation) NavigationChooser(
        onDismiss = { showEndNavigation = false },
        onChoose = { choice, saveDefault ->
            val launched = vm.navigationPreferences.launchEnd(context, p, choice, saveDefault)
            showEndNavigation = !launched
            failedEndChoice = if (launched) null else choice
        },
        enabled = !vm.busy,
        initialSelection = if (failedEndChoice != null) NavigationChoice.BUILT_IN else vm.navigationPreferences.default
    )
    val summary = deliverySummary(p)
    var cash by rememberSaveable(p.id, p.cashOnHand) { mutableStateOf(p.cashOnHand) }
    var tax by rememberSaveable(p.id, p.tax) { mutableStateOf(p.tax) }
    var rate by rememberSaveable(p.id, p.exchangeRate) { mutableStateOf(p.exchangeRate) }
    var remark by rememberSaveable(p.id, p.remark) { mutableStateOf(p.remark) }
    LaunchedEffect(p.id) { vm.refreshExchangeRate() }
    val estimated = p.reviewedFinishedAt == null && p.actualCompletion == null
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(SGDimensions.Normal),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageTitle("Delivery Report Summary", if (estimated) "Your route progress and estimated finish time." else "Your delivery progress and summary, saved on this device.")
        PremiumCard(Modifier.fillMaxWidth(), borderColor = PremiumGold.copy(alpha = .5f), containerColor = SGColors.Card) {
            Column(Modifier.padding(SGDimensions.Normal), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Delivery success rate", color = PremiumGold, style = MaterialTheme.typography.labelLarge)
                Text("%.1f%%".format(Locale.US, summary.successRate), color = Color.White, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                Text("${summary.delivered} of ${summary.totalParcel} parcels delivered", color = SGColors.Silver, style = MaterialTheme.typography.titleMedium)
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
            Column(Modifier.padding(SGDimensions.Normal), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Route details", style = MaterialTheme.typography.titleLarge)
                DetailRow("Date", p.deliveryDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)))
                DetailRow("Start location", p.startLocation.reportLabel)
                DetailRow("End location", p.endReportLabel)
                DetailRow("Start", time(summary.start))
                DetailRow(if (estimated) "Estimated finish" else "Finish", time(summary.finish))
                DetailRow(if (estimated) "Estimated route time" else "Total route time", duration(summary.totalRouteSeconds))
                DetailRow("Total distance", km(summary.totalKm))
                DetailRow("Planned End arrival", time(summary.returned))
            }
        }
        PremiumCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(SGDimensions.Normal), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
        SecondaryAction("Navigate to End: ${p.endDisplayName}", {
            val choice = vm.navigationPreferences.default
            if (choice == null) {
                failedEndChoice = null
                showEndNavigation = true
            } else {
                val launched = vm.navigationPreferences.launchEnd(context, p, choice)
                failedEndChoice = if (launched) null else choice
                showEndNavigation = !launched
            }
        }, enabled = !vm.busy)
        SecondaryAction("View full route and schedule", {
            if (activeDeliveryPlan(p) == null) showCompletedSchedule = true else vm.screen = "Route"
        }, enabled = !vm.busy)
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), shape = RoundedCornerShape(SGDimensions.RadiusCard),
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
    // Viewing a saved route NEVER updates vm.route, active ID or status.
    var opened by remember { mutableStateOf<SavedRoute?>(null) }
    opened?.let { record ->
        runCatching { vm.repo.decode(record) }.getOrNull()?.let { savedPlan ->
            HistoryViewOnly(savedPlan) { opened = null }
        }
    }
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
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(SGDimensions.Normal),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            PageTitle("Route history", "Tap an unfinished route to continue delivery. Completed routes remain view-only.")
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
            PremiumCard(Modifier.fillMaxWidth()) { Text("Your saved routes will appear here after you optimize your first route.", Modifier.padding(SGDimensions.Normal)) }
        }
        items(saved, key = { it.id }) { record ->
            val p = runCatching { vm.repo.decode(record) }.getOrNull()
            PremiumCard(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    enabled = !vm.busy,
                    onClick = {
                        if (selecting) toggle(record.id)
                        else if (p != null && savedDeliveryStatus(p) != SavedDeliveryStatus.COMPLETED)
                            vm.continueSavedRoute(record.id)
                        else opened = record
                    },
                    onLongClick = { selecting = true; toggle(record.id) }
                ),
                borderColor = p?.let { historyStatusAccent(savedDeliveryStatus(it)).copy(alpha = .38f) }
                    ?: PremiumGold.copy(alpha = .25f)
            ) {
                Column(Modifier.padding(SGDimensions.Normal), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (selecting) Checkbox(record.id in selected, { toggle(record.id) }, enabled = !vm.busy)
                    if (p == null) {
                        Text("This saved route could not be read. Its stored data has been kept.")
                    } else {
                        val summary = deliverySummary(p)
                        val deliveryStatus = savedDeliveryStatus(p)
                        // Chop stays within a fixed, compact right-side slot.
                        // No overlay on date, progress values, action buttons or proof controls.
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 76.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(p.deliveryDate.toString(),
                                    style = MaterialTheme.typography.titleLarge)
                                Text(deliveryStatus.label,
                                    color = historyStatusAccent(deliveryStatus),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold)
                                // Route ID—not delivery date or postal code—identifies the snapshot.
                                Text("Route ID: ${p.id.take(8)}", color = SGColors.Silver,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            HistoryStatusChop(deliveryStatus)
                        }
                        DetailRow("Total Parcel", summary.totalParcel.toString())
                        DetailRow("Delivered / Remaining", "${summary.delivered} / ${summary.totalParcel - summary.delivered}")
                        DetailRow("Success Rate", "%.1f%%".format(Locale.ENGLISH, summary.successRate))
                        DetailRow("Total Distance", km(summary.totalKm))
                        DetailRow("Finish Time", time(summary.finish))
                        if (deliveryStatus != SavedDeliveryStatus.COMPLETED) {
                            Text("Tap to Continue Delivery", color = PremiumGold,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { opened = record }, enabled = !vm.busy) {
                                Text("View Details & Proofs", color = SGColors.White)
                            }
                        } else {
                            Text("Tap to View History", color = SGColors.Silver,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Small numbered red stop markers matching the route map reference: red fill, dark rim, white stop number. */
internal fun numberedMapStopIcon(resources: android.content.res.Resources, stopNumber: Int): android.graphics.drawable.BitmapDrawable {
    val density = resources.displayMetrics.density
    val diameter = (16f * density + 0.5f).toInt().coerceAtLeast(16)
    val bitmap = android.graphics.Bitmap.createBitmap(diameter, diameter, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val center = diameter / 2f
    val outerRadius = center - 0.5f
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = android.graphics.Color.BLACK
    canvas.drawCircle(center, center, outerRadius, paint)
    paint.color = android.graphics.Color.rgb(238, 6, 12)
    canvas.drawCircle(center, center, (outerRadius - 1.35f * density).coerceAtLeast(1f), paint)
    paint.color = android.graphics.Color.WHITE
    paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.textSize = (when {
        stopNumber >= 100 -> 7.2f
        stopNumber >= 10 -> 9f
        else -> 10.7f
    }) * density
    val centeredBaseline = center - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    canvas.drawText(stopNumber.toString(), center, centeredBaseline, paint)
    return android.graphics.drawable.BitmapDrawable(resources, bitmap)
}

/**
 * Route Map screen selects a permitted online vector map when connected, and the
 * local OSM-derived MBTiles (never tile.openstreetmap.org) in airplane mode.
 * This does not change the separate built-in GPS navigation Activity.
 */
@Composable
fun RouteMap(p: Plan) {
    val context = LocalContext.current
    val online = hasValidatedNetwork(context)
    val localTiles = if (online) null else OfflineMapPackageManager(context).tilesFile()
    // The map now occupies all available height below the existing app header.
    // MapLibre provides its own online attribution; the offline branch shows it locally.
    if (online) {
        OnlineRouteMap(p, Modifier.fillMaxSize())
    } else {
        OfflinePlannedRouteMap(p, localTiles, Modifier.fillMaxSize())
    }
}

/** Visualize saved route and 50 distinct delivery markers without network calls. */
@Composable
private fun OfflinePlannedRouteMap(p: Plan, archive: java.io.File?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val numberedStopIcons = remember(context, p.stops.size) {
        (1..p.stops.size).map { numberedMapStopIcon(context.resources, it) }
    }
    val map = remember(archive) {
        MapView(context).apply {
            setMultiTouchControls(true)
            setUseDataConnection(false)
            setBackgroundColor(android.graphics.Color.rgb(175, 211, 226))
            // Never select MAPNIK: cached raster tiles may be poisoned with 403
            // images and the OSM Foundation server is not an offline map source.
            setTileSource(org.osmdroid.tileprovider.tilesource.XYTileSource(
                "SG_DAYLIGHT_BASEMAP", 9, 16, 256, ".png", arrayOf("")
            ))
            if (archive != null) {
                runCatching {
                    setTileProvider(org.osmdroid.tileprovider.modules.OfflineTileProvider(
                        org.osmdroid.tileprovider.util.SimpleRegisterReceiver(context),
                        arrayOf(archive)
                    ))
                }
            }
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    Box(modifier) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize(), update = { view ->
            view.overlays.clear()
            val line = Polyline().apply {
                setPoints(p.geometry.filter { it.size >= 2 }.map { GeoPoint(it[1], it[0]) })
                outlinePaint.color = android.graphics.Color.rgb(24, 104, 218)
                outlinePaint.strokeWidth = 7f
            }
            view.overlays.add(line)
            p.stops.forEachIndexed { index, stop ->
                view.overlays.add(Marker(view).apply {
                    position = GeoPoint(stop.place.lat, stop.place.lon)
                    title = "Stop " + (index + 1) + ": " + stop.place.postal
                    snippet = stop.place.address
                    icon = numberedStopIcons[index]
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
            }
            if (!p.endAtLastDelivery) view.overlays.add(Marker(view).apply {
                position = GeoPoint(p.endPlace.lat, p.endPlace.lon)
                title = "END — " + p.endDisplayName
                snippet = p.endPlace.address
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
            val start = p.startLocation.asPlace()
            val end = p.endPlace
            val pins = p.stops.map { GeoPoint(it.place.lat, it.place.lon) } +
                GeoPoint(start.lat, start.lon) + GeoPoint(end.lat, end.lon)
            view.post { view.zoomToBoundingBox(BoundingBox.fromGeoPoints(pins), false, 80) }
            view.invalidate()
        })
        if (archive != null) {
            Text(
                "© OpenStreetMap contributors · Natural Earth",
                Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 6.dp)
                    .background(Color.White.copy(alpha = 0.88f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.DarkGray
            )
        }
        if (archive == null) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                color = SGColors.SolidCard
            ) {
                Text(
                    "Singapore Offline Maps are not installed. Connect to the Internet, open Settings and download the offline package. Route markers remain visible.",
                    Modifier.padding(12.dp),
                    color = SGColors.White
                )
            }
        }
    }
}
