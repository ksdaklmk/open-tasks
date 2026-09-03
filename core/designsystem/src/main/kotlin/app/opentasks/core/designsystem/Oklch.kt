package app.opentasks.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// ponytail: kept out of OpenTasksTheme.kt on purpose. OpenTasksColors calls
// oklch() while it initialises; if oklch() sat in OpenTasksThemeKt, that call
// ran the facade's static initialiser, which built LightColorScheme from
// OpenTasksColors fields that were still zero, and every themed colour stayed
// transparent for the rest of the process (blank UI after a locked cold start).
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
