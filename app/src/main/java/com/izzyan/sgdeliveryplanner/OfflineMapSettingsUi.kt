package com.izzyan.sgdeliveryplanner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun offlineDate(value: Long): String = if (value <= 0L) "Never" else
    Instant.ofEpochMilli(value).atZone(ZoneId.of("Asia/Singapore")).format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a"))

@Composable
internal fun OfflineMapSettingsCard(vm: PlannerViewModel) {
    val status = vm.offlineMapStatus
    val summary = when {
        vm.offlineMapBusy -> "Checking / updating…"
        status.installed && status.lastError.isNotBlank() -> "Installed · last check failed"
        status.lastError.isNotBlank() -> "Update failed · not installed"
        status.installed -> "Installed · Ready"
        else -> "Not installed"
    }
    val color = when {
        vm.offlineMapBusy -> SGColors.Warning
        status.lastError.isNotBlank() -> SGColors.Error
        status.installed -> SGColors.SuccessText
        else -> SGColors.Warning
    }
    CompactSettingsSection("Singapore Offline Maps · 50 Stops", summary, Icons.Outlined.CloudDownload, color, essential = {
        CompactSettingsMetrics(listOf(
            "Version" to status.version,
            "Size" to if (status.packageBytes > 0) "%.1f MB".format(Locale.ENGLISH,status.packageBytes / 1_000_000.0) else "Not available",
            "Road vertices" to if (status.roadVertices > 0) "%,d".format(Locale.ENGLISH,status.roadVertices) else "Not available",
            "Last updated" to compactSettingsDate(status.installedAt),
            "Last checked" to compactSettingsDate(status.checkedAt)))
        if (status.lastError.isNotBlank()) Text(status.lastError, color = SGColors.Error, fontSize = 12.sp)
        if (vm.offlineMapProgress.isNotBlank()) CompactSettingsDetail(vm.offlineMapProgress)
        OutlinedButton(onClick = vm::downloadOrUpdateOfflineMaps, enabled = !vm.offlineMapBusy && !vm.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            if (vm.offlineMapBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(if (status.installed) "Check / Update Maps" else "Download Singapore Offline Maps", fontSize = 14.sp)
        }
    }) {
        CompactSettingsMetric("Installed at", offlineDate(status.installedAt))
        CompactSettingsMetric("Checked at", offlineDate(status.checkedAt))
        CompactSettingsDetail("Downloads require internet once. After verified installation, route optimization can use the stored driving graph without mobile data or Wi-Fi. Weekly update checks run when connected. Existing GPS navigation is unchanged.")
    }
}
@Composable
internal fun NavigationAppearanceSettingsCard() {
    val context = LocalContext.current
    val store = remember(context) { NavigationMapAppearanceStore(context) }
    var selected by remember(store) { mutableStateOf(store.read()) }
    CompactSettingsSection("Built-in GPS · Map Appearance",
        if (selected == NavigationMapAppearance.AUTO) "Auto Day/Night" else selected.name.lowercase().replaceFirstChar { it.uppercase() },
        Icons.Outlined.Map, essential = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NavigationMapAppearance.entries.forEach { mode ->
                val active = mode == selected
                OutlinedButton(onClick = { store.save(mode); selected = mode },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, SGColors.Gold),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (active) SGColors.Gold else Color.Transparent,
                        contentColor = if (active) SGColors.Background else SGColors.White)) {
                    Icon(when(mode) {
                        NavigationMapAppearance.DAY -> Icons.Outlined.WbSunny
                        NavigationMapAppearance.NIGHT -> Icons.Outlined.DarkMode
                        NavigationMapAppearance.AUTO -> Icons.Outlined.BrightnessAuto
                    }, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }) {
        CompactSettingsDetail("Auto uses local sunrise and sunset when GPS is available, with the existing local-time fallback. The choice is saved and also available inside GPS navigation.")
    }
}
