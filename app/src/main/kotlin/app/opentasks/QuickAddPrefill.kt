package app.opentasks

/**
 * Derives a Quick Add title prefill from shared or selected text: the first
 * non-blank line, trimmed, bounded to the Quick Add title length limit
 * (240 characters, matching `MAX_QUICK_ADD_TITLE_LENGTH` in
 * `QuickAddSheet.kt`). Returns `null` when there is nothing usable, so
 * callers never open Quick Add with a blank title from an empty share.
 *
 * Pure JVM logic (no `android.*` imports) so it is unit-testable without a
 * device or Robolectric.
 */
fun quickAddPrefill(raw: CharSequence?): String? =
    raw?.take(240)
        ?.toString()
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
