package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A single palette for all Compose screens, including History and built-in GPS chrome. */
object SGColors {
    val Background = Color(0xFF081B33)
    val SecondaryBackground = Color(0xFF102A4C)
    val Card = Color(0xFF152F50)
    val Gold = Color(0xFFD4AF37)
    val SelectedGold = Color(0xFFF1D57A)
    val White = Color(0xFFFFFFFF)
    val Silver = Color(0xFFD6DEE8)
    val Divider = Color(0xFF29415D)
    val Disabled = Color(0xFF728096)
    val Success = Color(0xFF219E74)
    val Warning = Color(0xFFE7B94B)
    val Error = Color(0xFFF16B72)
    val ErrorContainer = Color(0xFF5C2636)
    val NavigationBlue = Color(0xFF1685FF)
    val InactiveRoute = Color(0xFF808080)
}

/** All padding and control dimensions derive from a 4dp grid. */
object SGDimensions {
    val Tight = 4.dp
    val Compact = 8.dp
    val Small = 12.dp
    val Normal = 16.dp
    val Large = 24.dp
    val RadiusSmall = 12.dp
    val RadiusCard = 16.dp
    val RadiusDialog = 20.dp
    val Touch = 48.dp
    val Icon = 24.dp
    val ScreenHorizontal = 16.dp
}

@Composable
fun SGPrimaryButton(
    label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    minHeight: Dp = SGDimensions.Touch
) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = minHeight),
        shape = RoundedCornerShape(SGDimensions.RadiusSmall),
        colors = ButtonDefaults.buttonColors(
            containerColor = SGColors.Gold, contentColor = SGColors.Background,
            disabledContainerColor = SGColors.Disabled, disabledContentColor = SGColors.Background),
        contentPadding = PaddingValues(horizontal = SGDimensions.Normal, vertical = SGDimensions.Compact)
    ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2) }
}

@Composable
fun SGSecondaryButton(
    label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    minHeight: Dp = SGDimensions.Touch
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = minHeight),
        shape = RoundedCornerShape(SGDimensions.RadiusSmall),
        border = BorderStroke(1.dp, if (enabled) SGColors.Gold else SGColors.Disabled),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = SGColors.SecondaryBackground, contentColor = SGColors.White,
            disabledContainerColor = SGColors.SecondaryBackground,
            disabledContentColor = SGColors.Disabled),
        contentPadding = PaddingValues(horizontal = SGDimensions.Normal, vertical = SGDimensions.Compact)
    ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2) }
}

@Composable
fun SGIconButton(
    onClick: () -> Unit, icon: @Composable () -> Unit,
    modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true
) {
    Surface(
        modifier = modifier.size(SGDimensions.Touch),
        shape = RoundedCornerShape(SGDimensions.RadiusSmall),
        border = BorderStroke(1.dp, if (selected) SGColors.Gold else SGColors.Divider),
        color = SGColors.SecondaryBackground
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) { icon() }
    }
}

@Composable
fun SGCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    containerColor: Color = SGColors.Card,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(SGDimensions.RadiusCard),
        border = BorderStroke(1.dp, borderColor ?: SGColors.Divider),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        content = content
    )
}

@Composable
fun SGDeliveryCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    SGCard(modifier, content = content)

@Composable
fun SGPaymentCard(
    modifier: Modifier = Modifier, compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(SGDimensions.RadiusCard)
    Column(
        modifier = modifier.fillMaxWidth()
            .border(BorderStroke(1.dp, SGColors.Divider), shape)
            .background(SGColors.Card.copy(alpha = 0.94f), shape)
            .padding(if (compact) SGDimensions.Small else SGDimensions.Normal),
        verticalArrangement = Arrangement.spacedBy(if (compact) SGDimensions.Compact else SGDimensions.Small),
        content = content
    )
}

@Composable
fun SGPageHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(SGDimensions.Compact)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = SGColors.White)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = SGColors.Silver)
        }
    }
}

@Composable
fun SGSectionHeader(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleLarge,
        color = SGColors.Gold)

@Composable
fun SGTextField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, singleLine: Boolean = true,
    placeholder: String? = null, minLines: Int = 1, maxLines: Int = Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) }, enabled = enabled, singleLine = singleLine,
        placeholder = { if (!placeholder.isNullOrBlank()) Text(placeholder) },
        minLines = minLines, maxLines = maxLines,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        modifier = modifier,
        shape = RoundedCornerShape(SGDimensions.RadiusSmall),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SGColors.Gold, unfocusedBorderColor = SGColors.Divider,
            focusedLabelColor = SGColors.SelectedGold, unfocusedLabelColor = SGColors.Silver,
            cursorColor = SGColors.Gold,
            focusedTextColor = SGColors.White, unfocusedTextColor = SGColors.White,
            focusedContainerColor = SGColors.SecondaryBackground,
            unfocusedContainerColor = SGColors.SecondaryBackground
        )
    )
}

@Composable
fun SGStatusBadge(status: String) {
    val normalized = normalizedStatus(status)
    val (label, fill) = when (normalized) {
        "DELIVERED" -> "Delivered" to SGColors.Success
        "ON_HOLD" -> "On Hold" to SGColors.Warning
        "SKIPPED" -> "Skipped" to SGColors.Disabled
        else -> "Pending" to SGColors.SecondaryBackground
    }
    val text = if (normalized == "ON_HOLD") SGColors.Background else SGColors.White
    Surface(color = fill, contentColor = text, shape = RoundedCornerShape(40.dp)) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SGDialog(
    title: String, onDismiss: () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = text, confirmButton = confirmButton, dismissButton = dismissButton,
        shape = RoundedCornerShape(SGDimensions.RadiusDialog),
        containerColor = SGColors.Card,
        titleContentColor = SGColors.White, textContentColor = SGColors.Silver
    )
}

/** Fixed tab order; touch targets and selection styling shared on all main screens. */
@Composable
fun SGBottomNavigation(
    selected: String, enabled: Boolean = true, onSelect: (String) -> Unit
) {
    val tabs = listOf(
        Triple("Home", Icons.Outlined.Home, "Home"),
        Triple("Route", Icons.Outlined.Route, "Route"),
        Triple("Delivery", Icons.Outlined.LocalShipping, "Delivery"),
        Triple("Map", Icons.Outlined.Map, "Map"),
        Triple("History", Icons.Outlined.History, "History"),
        Triple("Payment", Icons.Outlined.Payments, "Payment")
    )
    Surface(color = SGColors.SecondaryBackground, shadowElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = SGDimensions.Tight, vertical = SGDimensions.Tight),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            tabs.forEach { (name, icon, description) ->
                val active = selected == name
                TextButton(
                    onClick = { onSelect(name) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                    shape = RoundedCornerShape(SGDimensions.RadiusSmall),
                    contentPadding = PaddingValues(horizontal = 1.dp, vertical = SGDimensions.Tight),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (active) SGColors.Gold.copy(alpha = 0.18f) else Color.Transparent,
                        contentColor = if (active) SGColors.SelectedGold else SGColors.Silver,
                        disabledContentColor = SGColors.Disabled
                    )
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(icon, contentDescription = description,
                            modifier = Modifier.size(SGDimensions.Icon))
                        Text(name, fontSize = 10.sp, lineHeight = 13.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Clip)
                    }
                }
            }
        }
    }
}
