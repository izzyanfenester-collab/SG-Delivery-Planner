package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun DualThemeSettingsCard() {
    val context = LocalContext.current
    CompactSettingsSection(
        title = "Premium Theme",
        summary = if (SGThemePreferences.isBurgundy) "Burgundy Sport & Silver" else "Original Navy & Gold",
        icon = Icons.Outlined.Palette,
        essential = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "Navy & Gold", true to "Burgundy & Silver").forEach { (burgundy,label) ->
                    FilterChip(
                        selected = SGThemePreferences.isBurgundy == burgundy,
                        onClick = { SGThemePreferences.select(context, burgundy) },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    ) {
        CompactSettingsDetail("Changes all standard screens, cards, controls, GPS panels and generated reports. Safety and route-line colors remain unchanged. The original theme is preserved.")
    }
}
