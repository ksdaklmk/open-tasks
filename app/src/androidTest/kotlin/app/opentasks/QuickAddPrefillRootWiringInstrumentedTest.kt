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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.parseQuickAdd
import app.opentasks.core.domain.stripQuickAddToken
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Tag
import app.opentasks.core.model.ZonedMoment
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
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
 * Pins invariants two rounds of code review found broken in early cuts of
 * the share/text-selection intake:
 *  - a share/selection intent arriving while the sheet is closed must still
 *    open it with that text (the first mount under a signal value happens
 *    on the pass *after* the capturing effect has run, so it cannot read
 *    the by-then-cleared prefill parameter directly -- it needs the
 *    effect-captured fallback);
 *  - a share/selection intent arriving while the sheet is already open must
 *    replace the title with the new text, not a stale one (that remount
 *    happens synchronously, in the same pass as the new signal, ahead of
 *    its effect -- it needs the still-populated prefill parameter, not the
 *    one-share-behind fallback);
 *  - a blank share/selection intent must never overwrite an already-pending
 *    prefill (the guard around the assignment, not just the signal bump);
 *  - an explicit, no-prefill quick-add trigger (the Today widget tap or the
 *    static launcher shortcut) arriving while the sheet is open must clear
 *    a stale title left over from an earlier, already-consumed share, not
 *    resurrect it through the `?: quickAddSheetTitle` fallback -- "last
 *    explicit trigger wins".
 */
class QuickAddPrefillRootWiringInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val clock = Clock.fixed(
        Instant.parse("2026-08-10T03:00:00Z"),
        ZoneId.of("Asia/Bangkok"),
    )

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
                        onAdd = {},
                        projects = emptyList(),
                        tags = emptyList(),
                        clock = clock,
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
                        onAdd = {},
                        projects = emptyList(),
                        tags = emptyList(),
                        clock = clock,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Real then blank share").performClick()
        composeRule.onNodeWithTag("quick-add-title")
            .assertTextContains("Real share", substring = true)
    }

    @Test
    fun explicitTriggerWhileOpenClearsAStaleSharedTitle() {
        composeRule.setContent {
            var signal by remember { mutableIntStateOf(0) }
            var prefillText by remember { mutableStateOf<String?>(null) }
            OpenTasksTheme {
                Column {
                    Button(
                        onClick = {
                            prefillText = "Buy milk"
                            signal++
                        },
                    ) { Text("Share") }
                    Button(
                        onClick = {
                            // Mirrors `MainActivity.handleIntent`'s
                            // `EXTRA_OPEN_QUICK_ADD` / `QUICK_ADD_ACTION`
                            // arms: an explicit, no-prefill trigger sets the
                            // sentinel empty string -- never produced by
                            // `quickAddPrefill()` -- rather than leaving
                            // `prefillText` untouched.
                            prefillText = ""
                            signal++
                        },
                    ) { Text("Quick add trigger") }
                    QuickAddPrefillReplica(
                        quickAddSignal = signal,
                        quickAddPrefillText = prefillText,
                        onQuickAddConsumed = { prefillText = null },
                        onAdd = {},
                        projects = emptyList(),
                        tags = emptyList(),
                        clock = clock,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Share").performClick()
        composeRule.onNodeWithTag("quick-add-title")
            .assertTextContains("Buy milk", substring = true)

        // The sheet is still open, showing the share's title, when an
        // explicit no-prefill trigger arrives.
        composeRule.onNodeWithText("Quick add trigger").performClick()
        composeRule.onNodeWithTag("quick-add-title").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("")),
        )
    }

    @Test
    fun enrichedPrefillSubmitsAtomicallyThenReopensWithAnEmptyTitle() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        val projects = listOf(OpenTasksFixtures.studioProject)
        val tags = OpenTasksFixtures.tags
        val original = "Root #stu @Admin !2 every monday tomorrow ~45m"
        composeRule.setContent {
            var signal by remember { mutableIntStateOf(0) }
            var prefillText by remember { mutableStateOf<String?>(null) }
            OpenTasksTheme {
                Column {
                    Button(
                        onClick = {
                            prefillText = original
                            signal++
                        },
                    ) { Text("Open enriched prefill") }
                    QuickAddPrefillReplica(
                        quickAddSignal = signal,
                        quickAddPrefillText = prefillText,
                        onQuickAddConsumed = { prefillText = null },
                        onAdd = submitted::set,
                        projects = projects,
                        tags = tags,
                        clock = clock,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Open enriched prefill").performClick()
        var text = original
        while (true) {
            val matches = parseQuickAdd(text, clock.instant(), clock.zone, projects, tags)
            val match = matches.lastOrNull() ?: break
            composeRule.onNodeWithTag(suggestionTag(match)).performScrollTo().performClick()
            text = stripQuickAddToken(text, match)
        }
        composeRule.onNodeWithText("Add task").performScrollTo().performClick()

        assertEquals(
            DomainCommand.CreateTask(
                title = "Root",
                projectId = OpenTasksFixtures.studioProject.id,
                priority = Priority.HIGH,
                due = ZonedMoment(
                    Instant.parse("2026-08-11T10:00:00Z"),
                    "Asia/Bangkok",
                ),
                tagNames = listOf("Admin"),
                estimate = Duration.ofMinutes(45),
                recurrence = RecurrenceRule(
                    RecurrenceFrequency.WEEKLY,
                    weekdays = setOf(DayOfWeek.MONDAY),
                ),
            ),
            submitted.get(),
        )
        composeRule.onNodeWithTag("quick-add-title").assertDoesNotExist()

        composeRule.onNodeWithTag("quick-add-reopen").performClick()
        composeRule.onNodeWithTag("quick-add-title").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("")),
        )
    }
}

/**
 * Replica of the Quick Add prefill wiring in `OpenTasksApp`: a
 * `LaunchedEffect(quickAddSignal)` that captures [quickAddPrefillText] into
 * `quickAddSheetTitle` *before* consuming it, and a
 * `key(quickAddSignal)`-scoped [QuickAddSheet] whose `initialTitle` prefers
 * [quickAddPrefillText] (still populated when a second share remounts the
 * sheet synchronously, ahead of the new signal's effect) and falls back to
 * `quickAddSheetTitle` (what the *first* mount under a signal value reads,
 * once the effect has already run and cleared the parameter).
 */
@Composable
private fun QuickAddPrefillReplica(
    quickAddSignal: Int,
    quickAddPrefillText: String?,
    onQuickAddConsumed: () -> Unit,
    onAdd: (DomainCommand.CreateTask) -> Unit,
    projects: List<Project>,
    tags: List<Tag>,
    clock: Clock,
) {
    var showQuickAdd by remember { mutableStateOf(false) }
    var quickAddSheetTitle by remember { mutableStateOf("") }
    LaunchedEffect(quickAddSignal) {
        if (quickAddSignal > 0) {
            quickAddSheetTitle = quickAddPrefillText.orEmpty()
            showQuickAdd = true
            onQuickAddConsumed()
        }
    }
    Column {
        Button(
            onClick = { showQuickAdd = true },
            modifier = Modifier.testTag("quick-add-reopen"),
        ) { Text("Open quick add") }
        if (showQuickAdd) {
            key(quickAddSignal) {
                QuickAddSheet(
                    onDismiss = {
                        showQuickAdd = false
                        quickAddSheetTitle = ""
                    },
                    onAdd = { command ->
                        onAdd(command)
                        showQuickAdd = false
                        quickAddSheetTitle = ""
                    },
                    initialTitle = quickAddPrefillText ?: quickAddSheetTitle,
                    projects = projects,
                    tags = tags,
                    clock = clock,
                )
            }
        }
    }
}
