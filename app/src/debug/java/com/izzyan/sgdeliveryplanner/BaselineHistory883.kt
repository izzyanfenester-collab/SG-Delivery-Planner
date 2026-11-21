package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

// DEBUG ONLY: exact #883 History UI, retained for same-device visual comparison.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BaselineHistory883(vm: PlannerViewModel) {
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
            BaselinePageTitle883("Route history", "Tap an unfinished route to continue delivery. Completed routes remain view-only.")
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
                        BaselineDetailRow883("Total Parcel", summary.totalParcel.toString())
                        BaselineDetailRow883("Delivered / Remaining", "${summary.delivered} / ${summary.totalParcel - summary.delivered}")
                        BaselineDetailRow883("Success Rate", "%.1f%%".format(Locale.ENGLISH, summary.successRate))
                        BaselineDetailRow883("Total Distance", km(summary.totalKm))
                        BaselineDetailRow883("Finish Time", time(summary.finish))
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

@Composable
private fun BaselinePageTitle883(title: String, subtitle: String? = null) {
    SGPageHeader(title, subtitle)
}

@Composable
private fun BaselineDetailRow883(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
