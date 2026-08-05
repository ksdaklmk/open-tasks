package app.opentasks.input

import androidx.compose.ui.input.key.Key

/**
 * Keyboard shortcuts recognised at the workspace root. Each value is one
 * user-visible action; [shortcutActionFor] is the single source of truth
 * for the key-combination-to-action mapping, kept independent of Compose's
 * key-event dispatch so it is exercised by plain JVM unit tests.
 */
enum class ShortcutAction {
    OPEN_SEARCH,
    QUICK_ADD,
    NEW_PROJECT,
    SHOW_HELP,
    DISMISS_TOP,
}

/**
 * Resolves a key press to the [ShortcutAction] it triggers, or `null` when
 * the combination has no shortcut meaning.
 *
 * `Ctrl` combinations (`Ctrl+K`, `Ctrl+N`, `Ctrl+Shift+N`) are wired at the
 * workspace root on `Modifier.onPreviewKeyEvent`, ahead of any focused text
 * field, so they always fire. The single-key shortcuts -- [Key.Slash] alone
 * for search, `Shift+`[Key.Slash] (the `?` key) for help -- and
 * [Key.Escape] are wired on bubbling `Modifier.onKeyEvent`, so a focused
 * text field gets first claim on ordinary typing; [editableFocused] is the
 * belt-and-braces guard for the rare case a keystroke reaches the root
 * anyway. [Key.Escape] is deliberately exempt from that guard: dismissing
 * the topmost surface must still work while a field is focused.
 */
fun shortcutActionFor(
    key: Key,
    isCtrlPressed: Boolean,
    isShiftPressed: Boolean,
    inProjectsRoute: Boolean,
    editableFocused: Boolean,
): ShortcutAction? = when {
    isCtrlPressed && isShiftPressed && key == Key.N ->
        ShortcutAction.NEW_PROJECT.takeIf { inProjectsRoute }
    isCtrlPressed && key == Key.N -> ShortcutAction.QUICK_ADD
    isCtrlPressed && key == Key.K -> ShortcutAction.OPEN_SEARCH
    key == Key.Escape -> ShortcutAction.DISMISS_TOP
    editableFocused -> null
    key == Key.Slash && isShiftPressed -> ShortcutAction.SHOW_HELP
    key == Key.Slash -> ShortcutAction.OPEN_SEARCH
    else -> null
}
