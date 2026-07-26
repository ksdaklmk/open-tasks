package app.opentasks

enum class WorkspaceWindowClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
    EXTRA_WIDE,
}

data class WorkspaceLayout(
    val windowClass: WorkspaceWindowClass,
    val showNavigationRail: Boolean,
    val showDetailPane: Boolean,
    val showSupportingPane: Boolean,
    val useExtendedQuickAdd: Boolean,
)

object WorkspaceLayoutPolicy {
    fun calculate(
        widthDp: Int,
        hasSeparatingFold: Boolean,
    ): WorkspaceLayout {
        require(widthDp > 0) { "Window width must be positive" }
        val windowClass = when {
            widthDp < 600 -> WorkspaceWindowClass.COMPACT
            widthDp < 840 -> WorkspaceWindowClass.MEDIUM
            widthDp < 1_200 -> WorkspaceWindowClass.EXPANDED
            else -> WorkspaceWindowClass.EXTRA_WIDE
        }
        return WorkspaceLayout(
            windowClass = windowClass,
            showNavigationRail = windowClass != WorkspaceWindowClass.COMPACT,
            showDetailPane =
                windowClass != WorkspaceWindowClass.COMPACT || hasSeparatingFold,
            showSupportingPane = windowClass == WorkspaceWindowClass.EXTRA_WIDE,
            useExtendedQuickAdd =
                windowClass == WorkspaceWindowClass.EXPANDED ||
                    windowClass == WorkspaceWindowClass.EXTRA_WIDE,
        )
    }
}
