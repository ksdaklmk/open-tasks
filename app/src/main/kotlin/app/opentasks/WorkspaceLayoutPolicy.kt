package app.opentasks

enum class WorkspaceWindowClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
    EXTRA_WIDE,
}

data class PaneSplit(
    val listFraction: Float,
    val snapToFoldPositionDp: Int?,
)

data class WorkspaceLayout(
    val windowClass: WorkspaceWindowClass,
    val showNavigationRail: Boolean,
    val showDetailPane: Boolean,
    val showSupportingPane: Boolean,
    val useExtendedQuickAdd: Boolean,
    val paneSplit: PaneSplit?,
    val hingeExclusionBandDp: IntRange?,
)

object WorkspaceLayoutPolicy {
    private const val MAX_FOLD_LINES = 4
    private const val WIDE_FRACTION_MIN_WIDTH_DP = 960
    private const val MEDIUM_LIST_FRACTION = 0.42f
    private const val WIDE_LIST_FRACTION = 0.38f

    fun calculate(posture: WindowPosture): WorkspaceLayout {
        val widthDp = posture.widthDp
        val windowClass = when {
            widthDp < 600 -> WorkspaceWindowClass.COMPACT
            widthDp < 840 -> WorkspaceWindowClass.MEDIUM
            widthDp < 1_200 -> WorkspaceWindowClass.EXPANDED
            else -> WorkspaceWindowClass.EXTRA_WIDE
        }
        val considered = posture.foldLines.take(MAX_FOLD_LINES)
        val verticalSeparating = considered.filter {
            it.orientation == FoldOrientation.VERTICAL &&
                it.isSeparating &&
                it.positionDp in 1 until widthDp
        }
        val horizontalSeparating = considered.firstOrNull {
            it.orientation == FoldOrientation.HORIZONTAL &&
                it.isSeparating &&
                it.positionDp in 1 until posture.heightDp
        }
        val snapFold = verticalSeparating.minWithOrNull(
            compareBy(
                { kotlin.math.abs(it.positionDp - widthDp / 2) },
                { it.positionDp },
            ),
        )
        val showDetailPane =
            windowClass != WorkspaceWindowClass.COMPACT || snapFold != null
        val paneSplit = if (showDetailPane && (snapFold != null ||
                windowClass != WorkspaceWindowClass.COMPACT)
        ) {
            if (snapFold != null) {
                PaneSplit(
                    listFraction = snapFold.positionDp.toFloat() / widthDp,
                    snapToFoldPositionDp = snapFold.positionDp,
                )
            } else {
                PaneSplit(
                    listFraction = if (widthDp >= WIDE_FRACTION_MIN_WIDTH_DP) {
                        WIDE_LIST_FRACTION
                    } else {
                        MEDIUM_LIST_FRACTION
                    },
                    snapToFoldPositionDp = null,
                )
            }
        } else {
            null
        }
        return WorkspaceLayout(
            windowClass = windowClass,
            showNavigationRail = windowClass != WorkspaceWindowClass.COMPACT,
            showDetailPane = showDetailPane,
            showSupportingPane = windowClass == WorkspaceWindowClass.EXTRA_WIDE,
            useExtendedQuickAdd =
                windowClass == WorkspaceWindowClass.EXPANDED ||
                    windowClass == WorkspaceWindowClass.EXTRA_WIDE,
            paneSplit = paneSplit,
            hingeExclusionBandDp = horizontalSeparating?.let {
                it.positionDp..(it.positionDp + it.occludedWidthDp)
            },
        )
    }
}
