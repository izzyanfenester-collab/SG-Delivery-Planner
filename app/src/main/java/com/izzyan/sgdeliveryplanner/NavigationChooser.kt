package com.izzyan.sgdeliveryplanner

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class NavigationChoice(val label: String, val packageName: String? = null) {
    BUILT_IN("Built-in GPS"), GOOGLE_MAPS("Google Maps", "com.google.android.apps.maps"), WAZE("Waze", "com.waze")
}

/** Stored independently of route/history data; unknown legacy values safely mean Ask every time. */
internal class NavigationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var default by mutableStateOf(NavigationChoice.entries.firstOrNull { it.name == prefs.getString("defaultNavigation", null) })
        private set
    fun updateDefault(choice: NavigationChoice?) {
        default = choice
        prefs.edit().apply { if (choice == null) remove("defaultNavigation") else putString("defaultNavigation", choice.name) }.apply()
    }
    fun launch(context: Context, plan: Plan, choice: NavigationChoice, saveDefault: Boolean): Boolean {
        if (saveDefault) updateDefault(choice)
        return openDeliveryNavigation(context, plan, choice)
    }
}

@Composable
internal fun NavigationChooser(onDismiss: () -> Unit, onChoose: (NavigationChoice, Boolean) -> Unit, enabled: Boolean, initialSelection: NavigationChoice?) {
    var selected by remember { mutableStateOf(initialSelection) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = PremiumNavy, titleContentColor = PremiumGold,
        title = { Text("Choose Navigation") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NavigationChoice.entries.forEach { choice ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected == choice, enabled = enabled, role = Role.RadioButton, onClick = { selected = choice }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == choice, onClick = null, enabled = enabled,
                            colors = RadioButtonDefaults.colors(selectedColor = PremiumGold, unselectedColor = PremiumGold))
                        Text(choice.label, color = PremiumGold, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { selected?.let { onChoose(it, true) } }, enabled = enabled && selected != null,
                modifier = Modifier.heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumGold, contentColor = PremiumNavy)) { Text("Set Default") }
        },
        dismissButton = {
            OutlinedButton(onClick = { selected?.let { onChoose(it, false) } }, enabled = enabled && selected != null,
                modifier = Modifier.heightIn(min = 52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PremiumGold)) { Text("Just Once") }
        }
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
