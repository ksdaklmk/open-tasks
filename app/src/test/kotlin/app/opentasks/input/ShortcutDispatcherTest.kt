package app.opentasks.input

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutDispatcherTest {
    @Test
    fun ctrlKOpensSearch() {
        assertEquals(
            ShortcutAction.OPEN_SEARCH,
            shortcutActionFor(
                key = Key.K,
                isCtrlPressed = true,
                isShiftPressed = false,
                inProjectsRoute = false,
                editableFocused = false,
            ),
        )
    }

    @Test
    fun ctrlKOpensSearchEvenWhenEditableFocused() {
        assertEquals(
            ShortcutAction.OPEN_SEARCH,
            shortcutActionFor(
                key = Key.K,
                isCtrlPressed = true,
                isShiftPressed = false,
                inProjectsRoute = false,
                editableFocused = true,
            ),
        )
    }

    @Test
    fun slashOpensSearchWhenNotEditableFocused() {
        assertEquals(
            ShortcutAction.OPEN_SEARCH,
            shortcutActionFor(
                key = Key.Slash,
                isCtrlPressed = false,
                isShiftPressed = false,
                inProjectsRoute = false,
                editableFocused = false,
            ),
        )
    }

    @Test
    fun slashReturnsNullWhenEditableFocused() {
        assertNull(
            shortcutActionFor(
                key = Key.Slash,
                isCtrlPressed = false,
                isShiftPressed = false,
                inProjectsRoute = false,
                editableFocused = true,
            ),
        )
    }

    @Test
    fun shiftSlashShowsHelpWhenNotEditableFocused() {
        assertEquals(
            ShortcutAction.SHOW_HELP,
            shortcutActionFor(
                key = Key.Slash,
                isCtrlPressed = false,
                isShiftPressed = true,
                inProjectsRoute = false,
                editableFocused = false,
            ),
        )
    }

    @Test
    fun shiftSlashReturnsNullWhenEditableFocused() {
        assertNull(
            shortcutActionFor(
                key = Key.Slash,
                isCtrlPressed = false,
                isShiftPressed = true,
                inProjectsRoute = false,
                editableFocused = true,
            ),
        )
    }

    @Test
    fun ctrlNOpensQuickAdd() {
        assertEquals(
            ShortcutAction.QUICK_ADD,
            shortcutActionFor(
                key = Key.N,
                isCtrlPressed = true,
                isShiftPressed = false,
                inProjectsRoute = false,
                editableFocused = false,
            ),
        )
    }

    @Test
    fun ctrlNOpensQuickAddEvenWhenEditableFocused() {
        assertEquals(
            ShortcutAction.QUICK_ADD,
            shortcutActionFor(
                key = Key.N,
                isCtrlPressed = true,
                isShiftPressed = false,
                inProjectsRoute = false,
                editableFocused = true,
            ),
        )
    }

    @Test
    fun ctrlShiftNCreatesProjectInProjectsRoute() {
        assertEquals(
            ShortcutAction.NEW_PROJECT,
            shortcutActionFor(
                key = Key.N,
                isCtrlPressed = true,
                isShiftPressed = true,
                inProjectsRoute = true,
                editableFocused = false,
            ),
        )
    }

    @Test
    fun ctrlShiftNReturnsNullOutsideProjectsRoute() {
        assertNull(
            shortcutActionFor(
                key = Key.N,
                isCtrlPressed = true,
                isShiftPressed = true,
                inProjectsRoute = false,
                editableFocused = false,
            ),
        )
    }

    @Test
    fun ctrlShiftNReturnsNullOutsideProjectsRouteEvenWhenEditableFocused() {
        assertNull(
            shortcutActionFor(
                key = Key.N,
                isCtrlPressed = true,
                isShiftPressed = true,
                inProjectsRoute = false,
                editableFocused = true,
            ),
        )
    }

    @Test
    fun escapeDismissesTopWhenNotEditableFocused() {
        assertEquals(
            ShortcutAction.DISMISS_TOP,
            shortcutActionFor(
                key = Key.Escape,
                isCtrlPressed = false,
                isShiftPressed = false,
                inProjectsRoute = false,
                editableFocused = false,
            ),
        )
    }

    @Test
    fun escapeDismissesTopEvenWhenEditableFocused() {
        assertEquals(
            ShortcutAction.DISMISS_TOP,
            shortcutActionFor(
                key = Key.Escape,
                isCtrlPressed = false,
                isShiftPressed = false,
                inProjectsRoute = false,
                editableFocused = true,
            ),
        )
    }

    @Test
    fun unmappedKeyReturnsNull() {
        assertNull(
            shortcutActionFor(
                key = Key.A,
                isCtrlPressed = false,
                isShiftPressed = false,
                inProjectsRoute = false,
                editableFocused = false,
            ),
        )
    }

    @Test
    fun unmodifiedNReturnsNull() {
        assertNull(
            shortcutActionFor(
                key = Key.N,
                isCtrlPressed = false,
                isShiftPressed = false,
                inProjectsRoute = true,
                editableFocused = false,
            ),
        )
    }
}
