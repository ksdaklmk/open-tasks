package app.opentasks.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object OpenTasksColors {
    val LightBackground = oklch(0.975, 0.000, 0.0)
    val LightSurface = oklch(1.000, 0.000, 0.0)
    val LightSurfaceDim = oklch(0.945, 0.004, 29.0)
    val LightInk = oklch(0.220, 0.008, 29.0)
    val LightMutedInk = oklch(0.470, 0.010, 29.0)
    val LightOutline = oklch(0.720, 0.008, 29.0)
    val LightEmber = oklch(0.620, 0.170, 32.0)
    val LightPaleEmber = oklch(0.930, 0.035, 32.0)
    val LightError = oklch(0.520, 0.190, 25.0)
    val LightSuccess = oklch(0.480, 0.100, 150.0)

}

private val LightColorScheme = lightColorScheme(
    primary = OpenTasksColors.LightInk,
    onPrimary = OpenTasksColors.LightSurface,
    primaryContainer = OpenTasksColors.LightSurfaceDim,
    onPrimaryContainer = OpenTasksColors.LightInk,
    secondary = OpenTasksColors.LightEmber,
    onSecondary = Color.White,
    secondaryContainer = OpenTasksColors.LightPaleEmber,
    onSecondaryContainer = OpenTasksColors.LightInk,
    tertiary = OpenTasksColors.LightSuccess,
    onTertiary = Color.White,
    background = OpenTasksColors.LightBackground,
    onBackground = OpenTasksColors.LightInk,
    surface = OpenTasksColors.LightSurface,
    onSurface = OpenTasksColors.LightInk,
    surfaceVariant = OpenTasksColors.LightSurfaceDim,
    onSurfaceVariant = OpenTasksColors.LightMutedInk,
    outline = OpenTasksColors.LightOutline,
    error = OpenTasksColors.LightError,
    onError = Color.White,
)

private val OpenTasksTypography = Typography().run {
    copy(
        displayLarge = displayLarge.withProductFont(FontWeight.Bold),
        displayMedium = displayMedium.withProductFont(FontWeight.Bold),
        displaySmall = displaySmall.withProductFont(FontWeight.Bold),
        headlineLarge = headlineLarge.withProductFont(FontWeight.Bold),
        headlineMedium = headlineMedium.withProductFont(FontWeight.Bold),
        headlineSmall = headlineSmall.withProductFont(FontWeight.SemiBold),
        titleLarge = titleLarge.withProductFont(FontWeight.SemiBold),
        titleMedium = titleMedium.withProductFont(FontWeight.SemiBold),
        titleSmall = titleSmall.withProductFont(FontWeight.SemiBold),
        bodyLarge = bodyLarge.withProductFont(FontWeight.Normal),
        bodyMedium = bodyMedium.withProductFont(FontWeight.Normal),
        bodySmall = bodySmall.withProductFont(FontWeight.Normal),
        labelLarge = labelLarge.withProductFont(FontWeight.SemiBold),
        labelMedium = labelMedium.withProductFont(FontWeight.Medium),
        labelSmall = labelSmall.withProductFont(FontWeight.Medium),
    )
}

private fun TextStyle.withProductFont(weight: FontWeight): TextStyle = copy(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
)

private val OpenTasksShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun OpenTasksTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = OpenTasksTypography,
        shapes = OpenTasksShapes,
        content = content,
    )
}
