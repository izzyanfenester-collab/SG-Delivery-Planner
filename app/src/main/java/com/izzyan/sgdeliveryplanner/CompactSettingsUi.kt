package com.izzyan.sgdeliveryplanner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun settingsPostalColor(status: String?, checking: Boolean): Color = when {
    checking -> SGColors.Warning
    status == "Update failed" -> SGColors.Error
    status in setOf("Ready offline","Up to date","Updated") -> SGColors.SuccessText
    else -> SGColors.Warning
}
internal fun settingsAppColor(state: AppUpdateState, checking: Boolean): Color = when {
    checking -> SGColors.Warning
    state.error.isNotBlank() -> SGColors.Error
    state.updateAvailable -> SGColors.Warning
    state.lastChecked > 0 && state.latestBuild != null -> SGColors.SuccessText
    else -> SGColors.Silver
}
internal fun compactSettingsDate(value: Long): String = if (value <= 0) "Not yet" else
    Instant.ofEpochMilli(value).atZone(ZoneId.of("Asia/Singapore"))
        .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))

@Composable
internal fun CompactSettingsSection(title: String, summary: String, icon: ImageVector,
    statusColor: Color = SGColors.Silver,
    essential: (@Composable ColumnScope.() -> Unit)? = null,
    details: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    PremiumCard(Modifier.fillMaxWidth(), borderColor = SGColors.Gold.copy(alpha = .35f)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse $title" else "Expand $title") { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = SGColors.Gold, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = SGColors.White)
                    Text(summary, fontSize = 13.sp, lineHeight = 16.sp, color = statusColor)
                }
                Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    null, tint = SGColors.Gold, modifier = Modifier.size(24.dp))
            }
            essential?.invoke(this)
            AnimatedVisibility(expanded, enter = expandVertically(tween(220), expandFrom = Alignment.Top),
                exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider(color = SGColors.Divider)
                    details()
                }
            }
        }
    }
}
@Composable
internal fun CompactSettingsMetrics(values: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        values.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label,value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = SGColors.Silver, fontSize = 12.sp, lineHeight = 15.sp)
                        Text(value, color = SGColors.White, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
@Composable
internal fun CompactSettingsMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), color = SGColors.Silver, fontSize = 13.sp)
        Text(value, Modifier.weight(1f), color = SGColors.White, fontSize = 14.sp)
    }
}
@Composable
internal fun CompactSettingsDetail(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 17.sp, color = SGColors.Silver)
}
@Composable
internal fun CompactGpsPreferences() {
    val context = LocalContext.current
    val store = remember(context) { GpsNavigationModePreferences(context) }
    var mode by remember(store) { mutableStateOf(store.is3dEnabled()) }
    var sound by remember(store) { mutableStateOf(store.isSoundEnabled()) }
    CompactSettingsSection("GPS Preferences", "${if (mode) "3D" else "2D"} · Sound ${if (sound) "ON" else "OFF"}", Icons.Outlined.Tune) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("3D map display", Modifier.weight(1f), fontSize = 15.sp)
            Switch(mode, { store.set3dEnabled(it); mode = it })
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Navigation sound", Modifier.weight(1f), fontSize = 15.sp)
            Switch(sound, { store.setSoundEnabled(it); sound = it })
        }
    }
}
@Composable
internal fun CompactSettingsBackup(vm: PlannerViewModel) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        if (it != null) vm.exportHistory(it)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) vm.restoreHistory(it)
    }
    CompactSettingsSection("Backup & Restore", "Routes · Photos · Payments", Icons.Outlined.Backup, essential = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { create.launch("SG-Delivery-History-"+java.time.LocalDate.now()+".zip") },
                enabled = !vm.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(4.dp)) { Text("Backup", fontSize = 14.sp) }
            OutlinedButton(onClick = { confirm = true }, enabled = !vm.busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp)) { Text("Restore", fontSize = 14.sp) }
        }
        if (vm.backupNotice.isNotBlank()) CompactSettingsDetail(vm.backupNotice)
    }) {
        CompactSettingsDetail("Your backup ZIP includes route history, customer details, proof photos and Payment records. Keep it private; it is not encrypted.")
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Restore SG Delivery Backup?") },
        text = { Text("Restore routes, proof photos and Payment records from your backup ZIP. Existing routes and payments will be preserved without overwriting them. Older backup ZIPs are also supported.") },
        confirmButton = { Button(onClick = { confirm = false; open.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream")) }) { Text("Choose Backup") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
