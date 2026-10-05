package com.izzyan.sgdeliveryplanner

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class NavigationChoice(val label: String, val packageName: String? = null) {
    BUILT_IN("Built-in GPS"), GOOGLE_MAPS("Google Maps", "com.google.android.apps.maps"), WAZE("Waze", "com.waze")
}

@Composable
internal fun NavigationChooser(onDismiss: () -> Unit, onChoose: (NavigationChoice) -> Unit, enabled: Boolean) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = PremiumNavy, titleContentColor = PremiumGold,
        title = { Text("Choose Navigation") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NavigationChoice.entries.forEach { choice ->
                    OutlinedButton(onClick = { onChoose(choice) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PremiumGold)) { Text(choice.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } }
    )
}

/** Omitted origin means the external app uses current GPS. Exactly one destination, no waypoints. */
internal fun externalNavigationIntent(stop: Place, choice: NavigationChoice): Intent {
    require(choice != NavigationChoice.BUILT_IN)
    val destination = if (GpsPoint(stop.lat, stop.lon).valid()) "${stop.lat},${stop.lon}"
        else listOf(stop.address.trim(), "Singapore", stop.postal.trim()).filter { it.isNotBlank() }.joinToString(" ")
    val uri = when (choice) {
        NavigationChoice.GOOGLE_MAPS -> Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d")
        NavigationChoice.WAZE -> Uri.Builder().scheme("waze").authority("")
            .appendQueryParameter(if (GpsPoint(stop.lat, stop.lon).valid()) "ll" else "q", destination)
            .appendQueryParameter("navigate", "yes").build()
        else -> error("Internal navigation uses the existing activity")
    }
    return Intent(Intent.ACTION_VIEW, uri).setPackage(choice.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Handoff only: no access to ViewModel, persistence, delivery statuses or ETA writes. */
internal fun openDeliveryNavigation(context: Context, plan: Plan, choice: NavigationChoice): Boolean {
    val stop = navigationDestination(plan) ?: return false
    try {
        val intent = if (choice == NavigationChoice.BUILT_IN) builtInNavigationIntent(context, plan) ?: return false
            else externalNavigationIntent(stop, choice)
        if (choice != NavigationChoice.BUILT_IN && intent.resolveActivity(context.packageManager) == null) {
            Toast.makeText(context, "${choice.label} is not installed.", Toast.LENGTH_LONG).show()
            return false
        }
        context.startActivity(intent)
        return true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, if (choice == NavigationChoice.BUILT_IN) "Navigation could not be opened." else "${choice.label} is not installed.", Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "${choice.label} could not be opened.", Toast.LENGTH_LONG).show()
    }
    return false
}
