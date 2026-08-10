package app.opentasks.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.ceil

internal data class DotRunLayout(
    val total: Int,
    val filled: Int,
)

internal fun dotRunLayout(
    progress: Float,
    unitCount: Long?,
    maxDots: Int,
): DotRunLayout {
    require(maxDots > 0)
    if (unitCount != null && unitCount in 0..maxDots.toLong()) {
        val count = unitCount.toInt()
        return DotRunLayout(total = count, filled = count)
    }
    val bounded = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val filled = if (bounded == 0f) 0 else {
        ceil(bounded * maxDots).toInt().coerceIn(1, maxDots)
    }
    return DotRunLayout(total = maxDots, filled = filled)
}

internal fun dottedColumnHeights(values: List<Float>, maxRows: Int): List<Int> {
    require(maxRows > 0)
    val finiteMaximum = values.filter(Float::isFinite)
        .filter { it >= 0f }
        .maxOrNull()
        ?.takeIf { it > 0f }
        ?: return values.map { 0 }
    return values.map { value ->
        val bounded = when {
            !value.isFinite() || value < 0f -> 0f
            else -> value.coerceAtMost(finiteMaximum)
        }
        if (bounded == 0f) 0 else {
            ceil((bounded / finiteMaximum) * maxRows).toInt().coerceIn(1, maxRows)
        }
    }
}

@Composable
fun DotRunBar(
    progress: Float,
    modifier: Modifier = Modifier,
    unitCount: Long? = null,
    maxDots: Int = 24,
) {
    val layout = dotRunLayout(progress, unitCount, maxDots)
    val filledColor = MaterialTheme.colorScheme.secondary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.testTag("dot-run-bar")) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            if (layout.total == 0) return@Canvas
            val diameter = minOf(
                size.height,
                size.width / (layout.total + (layout.total - 1) * 0.5f),
            )
            val step = diameter * 1.5f
            val contentWidth = diameter + step * (layout.total - 1)
            val firstX = (size.width - contentWidth) / 2f + diameter / 2f
            repeat(layout.total) { index ->
                drawCircle(
                    color = if (index < layout.filled) filledColor else trackColor,
                    radius = diameter / 2f,
                    center = Offset(firstX + index * step, size.height / 2f),
                )
            }
        }
    }
}

@Composable
fun DottedAreaChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    maxRows: Int = 12,
) {
    val heights = dottedColumnHeights(values, maxRows)
    val filledColor = MaterialTheme.colorScheme.secondary
    Box(modifier.testTag("dotted-area-chart")) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            if (heights.isEmpty() || heights.all { it == 0 }) return@Canvas
            val columnWidth = size.width / heights.size
            val diameter = minOf(
                columnWidth / 1.5f,
                size.height / (maxRows + (maxRows - 1) * 0.5f),
            )
            val step = diameter * 1.5f
            heights.forEachIndexed { column, height ->
                repeat(height) { row ->
                    drawCircle(
                        color = filledColor,
                        radius = diameter / 2f,
                        center = Offset(
                            x = columnWidth * (column + 0.5f),
                            y = size.height - diameter / 2f - row * step,
                        ),
                    )
                }
            }
        }
    }
}
