package app.opentasks

import kotlin.math.roundToInt

data class RawFold(
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val isSeparating: Boolean,
)

internal data class MigrationPane(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
)

private data class PixelSpan(val start: Int, val endExclusive: Int) {
    val length: Int get() = endExclusive - start
}

internal fun largestMigrationPane(
    widthPx: Int,
    heightPx: Int,
    folds: List<RawFold>,
): MigrationPane {
    require(widthPx > 0 && heightPx > 0)
    val separating = folds.filter(RawFold::isSeparating)
    val horizontal = largestSpan(
        widthPx,
        separating.filter { it.heightPx >= it.widthPx }
            .map { PixelSpan(it.leftPx, it.leftPx + it.widthPx) },
    )
    val vertical = largestSpan(
        heightPx,
        separating.filter { it.heightPx < it.widthPx }
            .map { PixelSpan(it.topPx, it.topPx + it.heightPx) },
    )
    return MigrationPane(
        leftPx = horizontal.start,
        topPx = vertical.start,
        rightPx = horizontal.endExclusive,
        bottomPx = vertical.endExclusive,
    )
}

private fun largestSpan(length: Int, blocked: List<PixelSpan>): PixelSpan {
    var cursor = 0
    val available = mutableListOf<PixelSpan>()
    blocked.sortedBy(PixelSpan::start).forEach { raw ->
        val start = raw.start.coerceIn(0, length)
        val end = raw.endExclusive.coerceIn(start, length)
        if (start > cursor) available += PixelSpan(cursor, start)
        cursor = maxOf(cursor, end)
    }
    if (cursor < length) available += PixelSpan(cursor, length)
    return available.maxWithOrNull(
        compareBy<PixelSpan>(PixelSpan::length).thenBy { -it.start },
    ) ?: PixelSpan(0, length)
}

object WindowPostureMapper {
    fun map(
        widthDp: Int,
        heightDp: Int,
        density: Float,
        folds: List<RawFold>,
    ): WindowPosture {
        require(density > 0f) { "Density must be positive" }
        val foldLines = folds.map { fold ->
            val vertical = fold.heightPx >= fold.widthPx
            if (vertical) {
                FoldLine(
                    orientation = FoldOrientation.VERTICAL,
                    isSeparating = fold.isSeparating,
                    positionDp = (fold.leftPx / density).roundToInt(),
                    occludedWidthDp = (fold.widthPx / density).roundToInt(),
                )
            } else {
                FoldLine(
                    orientation = FoldOrientation.HORIZONTAL,
                    isSeparating = fold.isSeparating,
                    positionDp = (fold.topPx / density).roundToInt(),
                    occludedWidthDp = (fold.heightPx / density).roundToInt(),
                )
            }
        }
        return WindowPosture(
            widthDp = widthDp,
            heightDp = heightDp,
            foldLines = foldLines,
        )
    }
}
