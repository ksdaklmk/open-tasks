package app.opentasks

import kotlin.math.roundToInt

data class RawFold(
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val isSeparating: Boolean,
)

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
