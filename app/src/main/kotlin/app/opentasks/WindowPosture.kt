package app.opentasks

enum class FoldOrientation {
    VERTICAL,
    HORIZONTAL,
}

data class FoldLine(
    val orientation: FoldOrientation,
    val isSeparating: Boolean,
    val positionDp: Int,
    val occludedWidthDp: Int,
) {
    init {
        require(occludedWidthDp >= 0) { "Occluded width must not be negative" }
    }
}

data class WindowPosture(
    val widthDp: Int,
    val heightDp: Int,
    val foldLines: List<FoldLine>,
) {
    init {
        require(widthDp > 0) { "Window width must be positive" }
        require(heightDp > 0) { "Window height must be positive" }
    }
}
