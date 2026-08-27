package app.opentasks.feature.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.TaskCsvBlockingIssue
import app.opentasks.core.model.TaskCsvDateOrder
import app.opentasks.core.model.TaskCsvEstimateUnit
import app.opentasks.core.model.TaskCsvField
import app.opentasks.core.model.TaskCsvMapping
import app.opentasks.core.model.TaskCsvPriorityChoice
import app.opentasks.core.model.TaskCsvStatusChoice
import app.opentasks.core.model.TaskCsvTagMode
import app.opentasks.core.model.TaskCsvWarning
import app.opentasks.core.model.TaskCsvWarningReason
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskMigrationScreenInstrumentedTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun loadFailureOffersChooseAnotherAndCancel() {
        val chooseAnother = AtomicInteger()
        val cancel = AtomicInteger()
        setScreen(
            TaskMigrationUiState.LoadFailure(
                fileName = "broken.csv",
                reason = TaskMigrationLoadFailure.MALFORMED,
                rowNumber = 4,
            ),
            onChooseAnother = { chooseAnother.incrementAndGet() },
            onCancel = { cancel.incrementAndGet() },
        )

        composeRule.onNodeWithText("broken.csv").assertIsDisplayed()
        composeRule.onNodeWithText("The CSV structure is invalid at row 4.")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        composeRule.onNodeWithTag("migration-choose-another").performClick()
        composeRule.onNodeWithTag("migration-cancel").performClick()
        assertEquals(1, chooseAnother.get())
        assertEquals(1, cancel.get())
    }

    @Test
    fun reviewShowsSourceSamplesIgnoredColumnsAndCreateOnlyDisclosure() {
        setScreen(reviewState())

        composeRule.onNodeWithText("tasks.csv • 2 rows • 2 columns").assertIsDisplayed()
        composeRule.onNodeWithText("Samples: One").assertIsDisplayed()
        composeRule.onNodeWithText("Ignored columns").assertIsDisplayed()
        composeRule.onNodeWithTag("migration-ignored-columns").assertIsDisplayed()
        composeRule.onNodeWithText(
            "This creates new tasks. Importing the same file again creates duplicates.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Dates without an offset use Asia/Bangkok.")
            .assertIsDisplayed()
    }

    @Test
    fun reviewSectionHeadingsExposeHeadingSemantics() {
        setScreen(
            reviewState(
                statusValues = listOf("3"),
                priorityValues = listOf("2"),
                ambiguousDatesPresent = true,
                estimateValuesPresent = true,
                tagValuesPresent = true,
                warnings = listOf(
                    TaskCsvWarning(2, TaskCsvField.DUE, TaskCsvWarningReason.DUE_OMITTED),
                ),
                blockers = setOf(TaskCsvBlockingIssue.DATE_ORDER_REQUIRED),
            ),
        )

        listOf(
            "Import from another app",
            "Map columns",
            "Ignored columns",
            "Status values",
            "Priority values",
            "Date order",
            "Estimate unit",
            "Tag mode",
            "Preview",
            "Resolve these issues",
            "Warnings",
        ).forEach { heading ->
            composeRule.onNodeWithTag("task-migration-screen")
                .performScrollToNode(hasText(heading))
            composeRule.onNodeWithText(heading)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
    }

    @Test
    fun destinationSelectorForwardsItsSelectedColumn() {
        val selected = AtomicReference<Pair<TaskCsvField, Int?>?>()
        setScreen(reviewState(), onMapField = { field, index -> selected.set(field to index) })

        composeRule.onNodeWithTag("migration-field-title").performClick()
        composeRule.onNodeWithTag("migration-field-title-option-1").performClick()

        assertEquals(TaskCsvField.TITLE to 1, selected.get())
    }

    @Test
    fun sourceSamplesSkipBlankValuesBeforeTakingThree() {
        setScreen(
            reviewState(
                columns = listOf(
                    TaskMigrationColumnUi(0, "Title", listOf("", "One", "", "Two", "Three", "Four")),
                    TaskMigrationColumnUi(1, "Project", listOf("Launch")),
                ),
            ),
        )

        composeRule.onNodeWithText("Samples: One, Two, Three").assertIsDisplayed()
    }

    @Test
    fun rowWiderFailureWithRowNumberUsesSpecificCopy() {
        setScreen(
            TaskMigrationUiState.LoadFailure(
                fileName = "wide.csv",
                reason = TaskMigrationLoadFailure.ROW_WIDER_THAN_HEADER,
                rowNumber = 7,
            ),
        )
        composeRule.onNodeWithText("Row 7 has more values than the header.").assertIsDisplayed()
    }

    @Test
    fun rowWiderFailureWithoutRowNumberUsesGenericCopy() {
        setScreen(
            TaskMigrationUiState.LoadFailure(
                fileName = "wide.csv",
                reason = TaskMigrationLoadFailure.ROW_WIDER_THAN_HEADER,
                rowNumber = null,
            ),
        )
        composeRule.onNodeWithText("The CSV structure is invalid.").assertIsDisplayed()
    }

    @Test
    fun unresolvedStatusAndPriorityValuesExposeExplicitChoices() {
        val status = AtomicReference<Pair<String, TaskCsvStatusChoice>?>()
        val priority = AtomicReference<Pair<String, TaskCsvPriorityChoice>?>()
        setScreen(
            reviewState(
                statusValues = listOf("3"),
                priorityValues = listOf("2"),
                blockers = setOf(
                    TaskCsvBlockingIssue.STATUS_CHOICES_REQUIRED,
                    TaskCsvBlockingIssue.PRIORITY_CHOICES_REQUIRED,
                ),
            ),
            onStatusChoice = { value, choice -> status.set(value to choice) },
            onPriorityChoice = { value, choice -> priority.set(value to choice) },
        )

        composeRule.onNodeWithText("Ignore (use Backlog)").assertIsDisplayed()
        composeRule.onNodeWithText("Ignore (use None)").assertIsDisplayed()
        composeRule.onNodeWithTag("migration-status-0-done").performClick()
        composeRule.onNodeWithTag("migration-priority-0-urgent").performClick()
        assertEquals("3" to TaskCsvStatusChoice.DONE, status.get())
        assertEquals("2" to TaskCsvPriorityChoice.URGENT, priority.get())
    }

    @Test
    fun conditionalDateEstimateAndTagControlsAppearOnlyWhenNeeded() {
        var state by mutableStateOf(reviewState())
        val dateOrder = AtomicReference<TaskCsvDateOrder?>()
        val estimateUnit = AtomicReference<TaskCsvEstimateUnit?>()
        val tagMode = AtomicReference<TaskCsvTagMode?>()
        composeRule.setContent {
            OpenTasksTheme {
                MigrationTestScreen(
                    state = state,
                    onDateOrder = dateOrder::set,
                    onEstimateUnit = estimateUnit::set,
                    onTagMode = tagMode::set,
                )
            }
        }
        composeRule.onNodeWithTag("migration-date-order").assertDoesNotExist()
        composeRule.onNodeWithTag("migration-estimate-unit").assertDoesNotExist()
        composeRule.onNodeWithTag("migration-tag-mode").assertDoesNotExist()

        composeRule.runOnIdle {
            state = reviewState(
                ambiguousDatesPresent = true,
                estimateValuesPresent = true,
                tagValuesPresent = true,
                tagSamples = listOf("work", "urgent"),
            )
        }
        composeRule.onNodeWithTag("migration-date-order").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("migration-estimate-unit").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("migration-tag-mode").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Resulting tags: work, urgent")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("migration-date-order-dmy").performClick()
        composeRule.onNodeWithTag("migration-estimate-hours").performClick()
        composeRule.onNodeWithTag("migration-tag-pipe").performClick()
        assertEquals(TaskCsvDateOrder.DAY_MONTH_YEAR, dateOrder.get())
        assertEquals(TaskCsvEstimateUnit.HOURS, estimateUnit.get())
        assertEquals(TaskCsvTagMode.PIPE, tagMode.get())
    }

    @Test
    fun warningsShowRowFieldAndReasonAndChangeTheImportLabel() {
        setScreen(
            reviewState(
                warnings = listOf(
                    TaskCsvWarning(
                        rowNumber = 2,
                        field = TaskCsvField.DUE,
                        reason = TaskCsvWarningReason.DUE_OMITTED,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag("migration-warning-0")
            .performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        composeRule.onNodeWithText("Row 2 • Due • Invalid value omitted")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Import 2 tasks anyway")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun completionWarningsDistinguishDateOnlyTimeFromConfirmationTime() {
        setScreen(
            reviewState(
                warnings = listOf(
                    TaskCsvWarning(
                        rowNumber = 2,
                        field = TaskCsvField.COMPLETION,
                        reason = TaskCsvWarningReason.COMPLETION_TIME_INFERRED,
                    ),
                    TaskCsvWarning(
                        rowNumber = 3,
                        field = TaskCsvField.COMPLETION,
                        reason = TaskCsvWarningReason.COMPLETION_INFERRED,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Row 2 • Completion • No time supplied; 17:00 will be used")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Row 3 • Completion • No completion time supplied; confirmation time will be used",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun blockedOrCommittingReviewDisablesActions() {
        var state by mutableStateOf(
            reviewState(
                blockers = setOf(TaskCsvBlockingIssue.DATE_ORDER_REQUIRED),
            ),
        )
        val mapped = AtomicInteger()
        val status = AtomicInteger()
        val priority = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                MigrationTestScreen(
                    state,
                    onMapField = { _, _ -> mapped.incrementAndGet() },
                    onStatusChoice = { _, _ -> status.incrementAndGet() },
                    onPriorityChoice = { _, _ -> priority.incrementAndGet() },
                )
            }
        }
        composeRule.onNodeWithTag("migration-import").performScrollTo().assertIsNotEnabled()

        composeRule.runOnIdle {
            state = reviewState(
                statusValues = listOf("3"),
                priorityValues = listOf("2"),
                isCommitting = true,
            )
        }
        composeRule.onNodeWithTag("migration-import").performScrollTo().assertIsNotEnabled()
        listOf(
            "migration-field-title",
            "migration-status-0-done",
            "migration-priority-0-urgent",
        ).forEach { tag ->
            scrollScreenTo(tag)
            composeRule.onNodeWithTag(tag).assertIsNotEnabled()
        }
        composeRule.onNodeWithTag("migration-choose-another")
            .performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("migration-cancel").performScrollTo().assertIsNotEnabled()
        assertEquals(0, mapped.get())
        assertEquals(0, status.get())
        assertEquals(0, priority.get())
    }

    @Test
    fun systemBackCancelsUnlessReviewIsCommitting() {
        lateinit var dispatcher: androidx.activity.OnBackPressedDispatcher
        var state by mutableStateOf(reviewState())
        val cancel = AtomicInteger()
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            OpenTasksTheme {
                MigrationTestScreen(state = state, onCancel = { cancel.incrementAndGet() })
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnUiThread(dispatcher::onBackPressed)
        assertEquals(1, cancel.get())

        composeRule.runOnIdle { state = reviewState(isCommitting = true) }
        composeRule.runOnUiThread(dispatcher::onBackPressed)
        assertEquals(1, cancel.get())
        composeRule.onNodeWithTag("task-migration-screen").assertIsDisplayed()
    }

    @Test
    fun dropdownAndRadioOptionsMeetTheMinimumTargetSize() {
        setScreen(
            reviewState(
                statusValues = listOf("3"),
                priorityValues = listOf("2"),
                ambiguousDatesPresent = true,
                estimateValuesPresent = true,
                tagValuesPresent = true,
            ),
        )

        composeRule.onNodeWithTag("migration-field-title").performClick()
        composeRule.onNodeWithTag("migration-field-title-option-0")
            .assertHeightIsAtLeast(48.dp)
        listOf(
            "migration-status-0-done",
            "migration-priority-0-urgent",
            "migration-date-order-dmy",
            "migration-estimate-hours",
            "migration-tag-pipe",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun rtlLayoutKeepsFinalActionsReachable() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                OpenTasksTheme {
                    Box(Modifier.width(320.dp).height(520.dp)) {
                        MigrationTestScreen(reviewState())
                    }
                }
            }
        }

        listOf("migration-field-title", "migration-import", "migration-choose-another", "migration-cancel")
            .forEach { tag ->
                composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            }
    }

    @Test
    fun compactAndExpandedWidthsKeepOneReachableScrollablePage() {
        var size by mutableStateOf(320.dp to 520.dp)
        composeRule.setContent {
            OpenTasksTheme {
                Box(Modifier.width(size.first).height(size.second)) {
                    MigrationTestScreen(reviewState())
                }
            }
        }
        listOf(320.dp to 520.dp, 1_000.dp to 700.dp).forEach { next ->
            composeRule.runOnIdle { size = next }
            composeRule.onNodeWithTag("task-migration-screen").assertIsDisplayed()
            composeRule.onNodeWithTag("migration-import")
                .performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyControlRemainsReachableAtTwoHundredPercentFont() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                OpenTasksTheme {
                    Box(Modifier.width(320.dp).height(520.dp)) {
                        MigrationTestScreen(
                            reviewState(
                                ambiguousDatesPresent = true,
                                estimateValuesPresent = true,
                                tagValuesPresent = true,
                            ),
                        )
                    }
                }
            }
        }

        listOf(
            "migration-field-title",
            "migration-field-project",
            "migration-field-status",
            "migration-field-priority",
            "migration-field-start",
            "migration-field-due",
            "migration-field-completion",
            "migration-field-estimate",
            "migration-field-tags",
            "migration-field-description",
            "migration-date-order",
            "migration-estimate-unit",
            "migration-tag-mode",
            "migration-import",
            "migration-choose-another",
            "migration-cancel",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    private fun reviewState(
        statusValues: List<String> = emptyList(),
        priorityValues: List<String> = emptyList(),
        ambiguousDatesPresent: Boolean = false,
        estimateValuesPresent: Boolean = false,
        tagValuesPresent: Boolean = false,
        tagSamples: List<String> = emptyList(),
        columns: List<TaskMigrationColumnUi> = listOf(
            TaskMigrationColumnUi(0, "Title", listOf("One")),
            TaskMigrationColumnUi(1, "Project", listOf("Launch")),
        ),
        warnings: List<TaskCsvWarning> = emptyList(),
        blockers: Set<TaskCsvBlockingIssue> = emptySet(),
        isCommitting: Boolean = false,
    ) = TaskMigrationUiState.Review(
        fileName = "tasks.csv",
        sourceRowCount = 2,
        sourceColumnCount = 2,
        columns = columns,
        mapping = TaskCsvMapping(columns = mapOf(TaskCsvField.TITLE to 0)),
        statusValues = statusValues,
        priorityValues = priorityValues,
        ambiguousDatesPresent = ambiguousDatesPresent,
        estimateValuesPresent = estimateValuesPresent,
        tagValuesPresent = tagValuesPresent,
        tagSamples = tagSamples,
        capturedZoneId = "Asia/Bangkok",
        summary = TaskMigrationSummaryUi(2, 0, 0, 1, 1),
        warnings = warnings,
        blockingIssues = blockers,
        blockingMessage = null,
        ignoredHeaders = listOf("Project"),
        isCommitting = isCommitting,
    )

    private fun setScreen(
        state: TaskMigrationUiState,
        onMapField: (TaskCsvField, Int?) -> Unit = { _, _ -> },
        onStatusChoice: (String, TaskCsvStatusChoice) -> Unit = { _, _ -> },
        onPriorityChoice: (String, TaskCsvPriorityChoice) -> Unit = { _, _ -> },
        onDateOrder: (TaskCsvDateOrder) -> Unit = {},
        onEstimateUnit: (TaskCsvEstimateUnit) -> Unit = {},
        onTagMode: (TaskCsvTagMode) -> Unit = {},
        onChooseAnother: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            OpenTasksTheme {
                MigrationTestScreen(
                    state = state,
                    onMapField = onMapField,
                    onStatusChoice = onStatusChoice,
                    onPriorityChoice = onPriorityChoice,
                    onDateOrder = onDateOrder,
                    onEstimateUnit = onEstimateUnit,
                    onTagMode = onTagMode,
                    onChooseAnother = onChooseAnother,
                    onCancel = onCancel,
                )
            }
        }
    }

    private fun scrollScreenTo(tag: String) {
        composeRule.onNodeWithTag("task-migration-screen")
            .performScrollToNode(hasTestTag(tag))
    }

    @androidx.compose.runtime.Composable
    private fun MigrationTestScreen(
        state: TaskMigrationUiState,
        onMapField: (TaskCsvField, Int?) -> Unit = { _, _ -> },
        onStatusChoice: (String, TaskCsvStatusChoice) -> Unit = { _, _ -> },
        onPriorityChoice: (String, TaskCsvPriorityChoice) -> Unit = { _, _ -> },
        onDateOrder: (TaskCsvDateOrder) -> Unit = {},
        onEstimateUnit: (TaskCsvEstimateUnit) -> Unit = {},
        onTagMode: (TaskCsvTagMode) -> Unit = {},
        onChooseAnother: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) = TaskMigrationScreen(
        state = state,
        onMapField = onMapField,
        onStatusChoice = onStatusChoice,
        onPriorityChoice = onPriorityChoice,
        onDateOrder = onDateOrder,
        onEstimateUnit = onEstimateUnit,
        onTagMode = onTagMode,
        onImport = {},
        onChooseAnother = onChooseAnother,
        onCancel = onCancel,
    )
}
