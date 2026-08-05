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
 * This function is phase-agnostic -- it only maps a key combination to an
 * action, and does not know or care which Compose key-event phase produced
 * it. The workspace root is what must route by phase, keyed on
 * `isCtrlPressed`, not on the resolved [ShortcutAction]: `Ctrl` combinations
 * (`Ctrl+K`, `Ctrl+N`, `Ctrl+Shift+N`) are dispatched only from
 * `Modifier.onPreviewKeyEvent`, ahead of any focused text field, so they
 * always fire. The single-key shortcuts -- [Key.Slash] alone for search,
 * `Shift+`[Key.Slash] (the `?` key) for help -- and [Key.Escape] are
 * dispatched only from bubbling `Modifier.onKeyEvent`, so a focused text
 * field gets first claim on ordinary typing; [editableFocused] is the
 * belt-and-braces guard for the rare case a keystroke reaches the root
 * anyway. [Key.Escape] is deliberately exempt from that guard: dismissing
 * the topmost surface must still work while a field is focused.
 *
 * [ShortcutAction.OPEN_SEARCH] is the case that needs this most: `Ctrl+K`
 * and bare [Key.Slash] both resolve to it, but only the former belongs in
 * the preview phase -- routing on the resolved action alone would let a
 * bare `/` hijack a focused text field's typing before the field ever sees
 * the key event.
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
