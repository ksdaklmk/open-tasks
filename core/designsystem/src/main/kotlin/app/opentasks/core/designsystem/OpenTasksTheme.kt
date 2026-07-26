package app.opentasks.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

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

    val DarkBackground = oklch(0.150, 0.006, 29.0)
    val DarkSurface = oklch(0.190, 0.007, 29.0)
    val DarkSurfaceBright = oklch(0.250, 0.009, 29.0)
    val DarkInk = oklch(0.940, 0.004, 29.0)
    val DarkMutedInk = oklch(0.720, 0.008, 29.0)
    val DarkOutline = oklch(0.420, 0.010, 29.0)
    val DarkEmber = oklch(0.720, 0.150, 32.0)
    val DarkDeepEmber = oklch(0.300, 0.065, 32.0)
    val DarkError = oklch(0.720, 0.150, 25.0)
    val DarkSuccess = oklch(0.720, 0.100, 150.0)
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

private val DarkColorScheme = darkColorScheme(
    primary = OpenTasksColors.DarkInk,
    onPrimary = OpenTasksColors.DarkBackground,
    primaryContainer = OpenTasksColors.DarkSurfaceBright,
    onPrimaryContainer = OpenTasksColors.DarkInk,
    secondary = OpenTasksColors.DarkEmber,
    onSecondary = OpenTasksColors.DarkBackground,
    secondaryContainer = OpenTasksColors.DarkDeepEmber,
    onSecondaryContainer = OpenTasksColors.DarkInk,
    tertiary = OpenTasksColors.DarkSuccess,
    onTertiary = OpenTasksColors.DarkBackground,
    background = OpenTasksColors.DarkBackground,
    onBackground = OpenTasksColors.DarkInk,
    surface = OpenTasksColors.DarkSurface,
    onSurface = OpenTasksColors.DarkInk,
    surfaceVariant = OpenTasksColors.DarkSurfaceBright,
    onSurfaceVariant = OpenTasksColors.DarkMutedInk,
    outline = OpenTasksColors.DarkOutline,
    error = OpenTasksColors.DarkError,
    onError = OpenTasksColors.DarkBackground,
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
fun OpenTasksTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OpenTasksTypography,
        shapes = OpenTasksShapes,
        content = content,
    )
}

fun oklch(lightness: Double, chroma: Double, hueDegrees: Double): Color {
    val hue = Math.toRadians(hueDegrees)
    val a = chroma * cos(hue)
    val b = chroma * sin(hue)

    val lPrime = lightness + 0.3963377774 * a + 0.2158037573 * b
    val mPrime = lightness - 0.1055613458 * a - 0.0638541728 * b
    val sPrime = lightness - 0.0894841775 * a - 1.2914855480 * b

    val l = lPrime * lPrime * lPrime
    val m = mPrime * mPrime * mPrime
    val s = sPrime * sPrime * sPrime

    val linearRed = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    val linearGreen = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    val linearBlue = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s

    return Color(
        red = linearToSrgb(linearRed).toFloat(),
        green = linearToSrgb(linearGreen).toFloat(),
        blue = linearToSrgb(linearBlue).toFloat(),
        alpha = 1f,
    )
}

private fun linearToSrgb(component: Double): Double {
    val encoded = if (component <= 0.0031308) {
        12.92 * component
    } else {
        1.055 * component.pow(1.0 / 2.4) - 0.055
    }
    return encoded.coerceIn(0.0, 1.0)
}
