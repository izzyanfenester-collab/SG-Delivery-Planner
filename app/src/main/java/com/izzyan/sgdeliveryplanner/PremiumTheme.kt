package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val PremiumNavy = Color(0xFF07172D)
val PremiumRoyal = Color(0xFF183C78)
val PremiumGold = Color(0xFFE4BC69)
val PremiumEmerald = Color(0xFF126B50)
val PremiumAmber = Color(0xFF9B5807)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF9BBEFA),
    onPrimary = PremiumNavy,
    primaryContainer = PremiumRoyal,
    onPrimaryContainer = Color(0xFFF3F6FF),
    secondary = PremiumGold,
    onSecondary = PremiumNavy,
    secondaryContainer = Color(0xFF3B2E16),
    onSecondaryContainer = Color(0xFFFFE7B2),
    background = PremiumNavy,
    onBackground = Color(0xFFF3F6FC),
    surface = Color(0xFF10233E),
    onSurface = Color(0xFFF3F6FC),
    surfaceVariant = Color(0xFF1B3050),
    onSurfaceVariant = Color(0xFFBBC9DD),
    outline = Color(0xFF8193AD),
    outlineVariant = Color(0xFF304563),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5D1A1A),
    onErrorContainer = Color(0xFFFFDAD6)
)
private val LightPalette = lightColorScheme(
    primary = PremiumRoyal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE8FC),
    onPrimaryContainer = PremiumNavy,
    secondary = Color(0xFF775720),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFECC9),
    onSecondaryContainer = Color(0xFF36280F),
    background = Color(0xFFF2F5FA),
    onBackground = PremiumNavy,
    surface = Color.White,
    onSurface = PremiumNavy,
    surfaceVariant = Color(0xFFE7EDF7),
    onSurfaceVariant = Color(0xFF4C5C72),
    outline = Color(0xFF6E7E95),
    outlineVariant = Color(0xFFCFD8E6),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

@Composable
fun IzzDeliveryTheme(dark: Boolean, content: @Composable () -> Unit) {
    val typography = Typography()
    MaterialTheme(
        colorScheme = if (dark) DarkPalette else LightPalette,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        typography = typography.copy(
            headlineLarge = typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            headlineMedium = typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            headlineSmall = typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            titleLarge = typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        ),
        content = content
    )
}

@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        border = borderColor?.let { BorderStroke(1.dp, it) },
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        content = content
    )
}
