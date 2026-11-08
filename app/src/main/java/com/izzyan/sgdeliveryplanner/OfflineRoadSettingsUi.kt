package com.izzyan.sgdeliveryplanner

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** An accurate status panel: a road graph is not the same thing as offline visual tiles. */
@Composable
internal fun OfflineRoadSettingsPanel() {
    val context = LocalContext.current
    val packageStore = remember(context) { OfflineRoadPackageStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<OfflineRoadStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && !busy) scope.launch {
            busy = true; feedback = "Validating road graph and preserving current installation…"
            try {
                status = packageStore.installPickedArchive(uri)
                feedback = "Offline driving road graph installed."
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { feedback = e.message ?: "Package installation failed. Existing roads were retained." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { status = packageStore.status() }
    PremiumCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Offline Singapore Driving Roads",
                style = MaterialTheme.typography.titleLarge, color = SGColors.Gold)
            Text(
                if (status?.ready == true) "ROAD GRAPH READY" else "OFFLINE ROAD GRAPH NOT READY",
                style = MaterialTheme.typography.titleMedium,
                color = if (status?.ready == true) SGColors.SuccessText else SGColors.Warning
            )
            Text("Road graph: ${status?.version ?: "Checking…"}", style = MaterialTheme.typography.bodyMedium)
            status?.let {
                Text("Nodes: ${it.nodeCount}  ·  Archive: ${it.archiveBytes / 1_048_576} MB",
                    style = MaterialTheme.typography.bodySmall, color = SGColors.Silver)
                Text(it.detail, style = MaterialTheme.typography.bodySmall, color = SGColors.Silver)
                if (it.lastUpdated > 0) Text(
                    "Installed: ${DateFormat.getDateTimeInstance().format(Date(it.lastUpdated))}",
                    style = MaterialTheme.typography.bodySmall, color = SGColors.Silver)
            }
            Text("Requires a real road graph ZIP. Online maps and live traffic are separate; road data does not supply offline visual tiles.",
                style = MaterialTheme.typography.bodySmall, color = SGColors.Silver)
            SGSecondaryButton(
                label = "Import Singapore Road Graph ZIP",
                onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                modifier = Modifier.fillMaxWidth(), enabled = !busy
            )
            SGPrimaryButton(
                label = if (busy) "Checking / Installing…" else "Check Road Graph Updates",
                onClick = {
                    scope.launch {
                        busy = true; feedback = ""
                        try {
                            status = packageStore.checkForUpdates()
                            feedback = "Road graph is installed. Data version: ${status?.version}"
                        } catch (e: CancellationException) { throw e }
                          catch (e: Exception) {
                              feedback = e.message ?: "No verified update found. The previous graph remains installed."
                          }
                        finally { busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !busy
            )
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SGColors.Gold)
            if (feedback.isNotBlank()) Text(feedback,
                style = MaterialTheme.typography.bodySmall, color = SGColors.Silver)
            Text("Automatic release checks: weekly on Wi-Fi. Failed checks never delete the installed graph.",
                style = MaterialTheme.typography.bodySmall, color = SGColors.Silver)
        }
    }
}
