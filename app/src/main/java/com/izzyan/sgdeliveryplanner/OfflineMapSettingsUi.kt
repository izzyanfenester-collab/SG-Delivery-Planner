package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun offlineDate(value: Long): String =
    if (value <= 0L) "Never" else Instant.ofEpochMilli(value)
        .atZone(ZoneId.of("Asia/Singapore"))
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a"))

/** Read-only map installation metadata + explicit manual package update action. */
@Composable
internal fun OfflineMapSettingsCard(vm: PlannerViewModel) {
    val status = vm.offlineMapStatus
    PremiumCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Singapore Offline Roads · 50 Stops", style = MaterialTheme.typography.titleLarge,
                color = SGColors.Gold)
            Text(
                if (status.installed) "Installed · ready for offline route optimization"
                else "Not installed · offline route optimization unavailable",
                color = if (status.installed) SGColors.SuccessText else SGColors.Warning,
                fontWeight = FontWeight.SemiBold
            )
            Text("Map version: " + status.version)
            Text("Package size: " +
                if (status.packageBytes > 0L) "%.1f MB".format(
                    java.util.Locale.ENGLISH, status.packageBytes / 1_000_000.0
                ) else "Not available")
            Text("Road vertices: " + if (status.roadVertices > 0)
                "%,d".format(java.util.Locale.ENGLISH, status.roadVertices) else "Not available")
            Text("Last successful installation: " + offlineDate(status.installedAt))
            Text("Last checked: " + offlineDate(status.checkedAt))
            if (status.lastError.isNotBlank()) Text("Last error: " + status.lastError,
                color = SGColors.Warning, style = MaterialTheme.typography.bodySmall)
            if (vm.offlineMapProgress.isNotBlank()) Text(vm.offlineMapProgress,
                color = SGColors.Silver, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = vm::downloadOrUpdateOfflineMaps,
                enabled = !vm.offlineMapBusy && !vm.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                if (vm.offlineMapBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = SGColors.Gold)
                } else Text(if (status.installed) "Check / Update Offline Maps"
                    else "Download Singapore Offline Maps")
            }
            Text(
                "Downloads require internet once. After a verified installation, route optimization " +
                    "can use the stored driving graph without mobile data or Wi-Fi. " +
                    "Weekly update checks run when connected. Existing GPS navigation is unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = SGColors.Silver
            )
        }
    }
}
