package app.opentasks

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.opentasks.core.designsystem.OpenTasksTheme
import org.junit.Rule
import org.junit.Test

/**
 * Exercises the same `quickAddSignal` / `quickAddPrefillText` wiring
 * `MainActivity` and `OpenTasksApp` put at the root -- a data state plus a
 * signal counter driving a `key`-scoped `QuickAddSheet` -- against the real
 * [QuickAddSheet] surface, the way [app.opentasks.input
 * .ShortcutRootWiringInstrumentedTest] replicates the key-event wiring
 * rather than calling `OpenTasksApp` itself. It needs a device, so per the
 * task brief it is only compiled now (`:app:compileDebugAndroidTestKotlin`)
 * and runs starting at Task 13.
 *
 * Pins two invariants a code review found broken in the first cut of the
 * share/text-selection intake:
 *  - a share/selection intent arriving while the sheet is already open must
 *    replace the sheet's title with the new text, not the stale one (the
 *    `key(quickAddSignal)` remount must see the *current* prefill, not one
 *    captured through an effect-updated intermediate that lags behind);
 *  - a blank share/selection intent must never overwrite an already-pending
 *    prefill (the guard around the assignment, not just the signal bump).
 */
class QuickAddPrefillRootWiringInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun secondShareWhileTheSheetIsOpenReplacesTheStaleTitle() {
        composeRule.setContent {
            var signal by remember { mutableIntStateOf(0) }
            var prefillText by remember { mutableStateOf<String?>(null) }
            OpenTasksTheme {
                Column {
                    Button(
                        onClick = {
                            prefillText = "First share"
                            signal++
                        },
                    ) { Text("Share 1") }
                    Button(
                        onClick = {
                            prefillText = "Second share"
                            signal++
                        },
                    ) { Text("Share 2") }
                    QuickAddPrefillReplica(
                        quickAddSignal = signal,
                        quickAddPrefillText = prefillText,
                        onQuickAddConsumed = { prefillText = null },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Share 1").performClick()
        composeRule.onNodeWithTag("quick-add-title")
            .assertTextContains("First share", substring = true)

        // The sheet is still open; a second, distinct share arrives.
        composeRule.onNodeWithText("Share 2").performClick()
        composeRule.onNodeWithTag("quick-add-title")
            .assertTextContains("Second share", substring = true)
    }

    @Test
    fun blankShareArrivingRightAfterARealShareDoesNotDiscardIt() {
        composeRule.setContent {
            var signal by remember { mutableIntStateOf(0) }
            var prefillText by remember { mutableStateOf<String?>(null) }
            OpenTasksTheme {
                Column {
                    Button(
                        onClick = {
                            // Two intents landing back to back, before
                            // recomposition observes the first one -- the
                            // same shape as two `MainActivity.handleIntent`
                            // calls in a row. The second, blank share must
                            // leave the first share's pending text alone.
                            val realShare = quickAddPrefill("Real share")
                            if (realShare != null) {
                                prefillText = realShare
                                signal++
                            }
                            val blankShare = quickAddPrefill("   \n  ")
                            if (blankShare != null) {
                                prefillText = blankShare
                                signal++
                            }
                        },
                    ) { Text("Real then blank share") }
                    QuickAddPrefillReplica(
                        quickAddSignal = signal,
                        quickAddPrefillText = prefillText,
                        onQuickAddConsumed = { prefillText = null },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Real then blank share").performClick()
        composeRule.onNodeWithTag("quick-add-title")
            .assertTextContains("Real share", substring = true)
    }
}

/**
 * Replica of the Quick Add prefill wiring in `OpenTasksApp`: a
 * `LaunchedEffect(quickAddSignal)` that only opens the sheet and consumes
 * the pending prefill, and a `key(quickAddSignal)`-scoped [QuickAddSheet]
 * whose `initialTitle` reads [quickAddPrefillText] directly at mount time,
 * ahead of the effect that clears it.
 */
@Composable
private fun QuickAddPrefillReplica(
    quickAddSignal: Int,
    quickAddPrefillText: String?,
    onQuickAddConsumed: () -> Unit,
) {
    var showQuickAdd by remember { mutableStateOf(false) }
    LaunchedEffect(quickAddSignal) {
        if (quickAddSignal > 0) {
            showQuickAdd = true
            onQuickAddConsumed()
        }
    }
    if (showQuickAdd) {
        key(quickAddSignal) {
            QuickAddSheet(
                onDismiss = { showQuickAdd = false },
                onAdd = { _, _ -> showQuickAdd = false },
                initialTitle = quickAddPrefillText.orEmpty(),
            )
        }
    }
}
