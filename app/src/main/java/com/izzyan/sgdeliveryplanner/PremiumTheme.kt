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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Backwards-compatible aliases so approved delivery logic and badges are not replaced. */
val PremiumNavy = SGColors.Background
val PremiumRoyal = SGColors.SecondaryBackground
val PremiumGold = SGColors.Gold
val PremiumEmerald = SGColors.Success
val PremiumAmber = SGColors.Warning

private val UnifiedPalette = darkColorScheme(
    primary = SGColors.Gold,
    onPrimary = SGColors.Background,
    primaryContainer = SGColors.SecondaryBackground,
    onPrimaryContainer = SGColors.White,
    secondary = SGColors.Gold,
    onSecondary = SGColors.Background,
    secondaryContainer = SGColors.SolidCard,
    onSecondaryContainer = SGColors.White,
    tertiary = SGColors.SelectedGold,
    onTertiary = SGColors.Background,
    background = SGColors.Background,
    onBackground = SGColors.White,
    surface = SGColors.SolidCard,
    onSurface = SGColors.White,
    surfaceVariant = SGColors.SecondaryBackground,
    onSurfaceVariant = SGColors.Silver,
    outline = SGColors.Divider,
    outlineVariant = SGColors.Divider,
    error = SGColors.Error,
    onError = SGColors.White,
    errorContainer = SGColors.ErrorContainer,
    onErrorContainer = SGColors.White,
    surfaceTint = Color.Transparent
)

private val originalTypography = Typography()
private val UnifiedTypography = originalTypography.copy(
    headlineLarge = originalTypography.headlineLarge.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    headlineMedium = originalTypography.headlineMedium.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    headlineSmall = originalTypography.headlineSmall.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
    titleLarge = originalTypography.titleLarge.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = originalTypography.titleMedium.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = originalTypography.titleSmall.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = originalTypography.bodyLarge.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = originalTypography.bodyMedium.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = originalTypography.bodySmall.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = originalTypography.labelLarge.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = originalTypography.labelMedium.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = originalTypography.labelSmall.copy(
        fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp)
)

/** Dark-only even if a legacy caller passes false. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun IzzDeliveryTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UnifiedPalette,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(SGDimensions.RadiusSmall),
            small = RoundedCornerShape(SGDimensions.RadiusSmall),
            medium = RoundedCornerShape(SGDimensions.RadiusCard),
            large = RoundedCornerShape(SGDimensions.RadiusCard),
            extraLarge = RoundedCornerShape(SGDimensions.RadiusDialog)
        ),
        typography = UnifiedTypography,
        content = content
    )
}

/** Keep the historical component name but route all cards to one shared standard. */
@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    containerColor: Color = SGColors.Card,
    content: @Composable ColumnScope.() -> Unit
) {
    SGCard(modifier, borderColor, containerColor, content)
}
