package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Search only after the runner opens this panel; never change their delivery destination. */
@Composable
internal fun FuelStationDialog(session: NavigationSession, onDismiss: () -> Unit) {
    val origin = remember { session.location?.let { GpsPoint(it.latitude, it.longitude) } }
    var result by remember { mutableStateOf<FuelSnapshot?>(null) }
    LaunchedEffect(origin) {
        result = origin?.takeIf { it.valid() }?.let { session.fuelStations.nearby(it) }
            ?: FuelSnapshot(emptyList(), unavailable = true)
    }
    AlertDialog(onDismissRequest = onDismiss, containerColor = SGColors.SolidCard,
        titleContentColor = PremiumGold,
        title = { Text("Petrol Stations Nearby") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Within 5 km · approximate straight-line distance",
                    color = SGColors.Silver, style = MaterialTheme.typography.bodySmall)
                if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (result?.unavailable == true)
                    Text("Live petrol search is temporarily unavailable. Try again later. Your delivery route is unchanged.",
                        color = SGColors.Warning)
                if (result?.unavailable == false && result?.stations?.isEmpty() == true)
                    Text("No mapped petrol stations found nearby.", color = Color.White)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 365.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(result?.stations.orEmpty(), key = { it.id }) { station ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(station.name, color = Color.White, fontWeight = FontWeight.Bold)
                                Text("${metricDistance(station.meters)} away", color = PremiumGold,
                                    style = MaterialTheme.typography.bodyMedium)
                                station.address?.let { Text(it, color = SGColors.Silver,
                                    style = MaterialTheme.typography.bodySmall) }
                            }
                            FilledTonalButton(onClick = { session.previewFuelStation(station); onDismiss() },
                                contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Show", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    }
                }
                Text("OpenStreetMap data · Searching sends your current approximate GPS position. 'Show' previews a map pin, not a route change. Tap Follow to resume.",
                    color = SGColors.Silver, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
