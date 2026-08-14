package app.opentasks

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.opentasks.backup.AndroidBackupFiles
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.QuickAddTokenKind
import app.opentasks.core.domain.computeProjectTimelineProjection
import app.opentasks.core.domain.parseQuickAdd
import app.opentasks.core.domain.stripQuickAddToken
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.ProjectPresentation
import app.opentasks.core.model.ProjectTimelineWindow
import app.opentasks.core.model.Priority
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.SavedView
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TaskSortKey
import app.opentasks.core.model.ZonedMoment
import app.opentasks.feature.projects.ProjectsScreen
import app.opentasks.lock.AppLockSettings
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProcessRestorationInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Asia/Bangkok")
    private val clock = Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"), zone)
    private val projects = listOf(OpenTasksFixtures.studioProject)
    private val tags = OpenTasksFixtures.tags

    private fun parsed(text: String) =
        parseQuickAdd(text, clock.instant(), clock.zone, projects, tags)

    private fun confirm(text: String, kind: QuickAddTokenKind): String {
        val match = parsed(text).single { it.kind == kind }
        composeRule.onNodeWithTag(suggestionTag(match))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        return stripQuickAddToken(text, match)
    }

    @Test
    fun workspaceRouteRestoresAfterSavedInstanceStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            val backStack = rememberWorkspaceBackStack()
            OpenTasksTheme {
                Column {
                    Text(
                        when (backStack.lastOrNull()) {
                            TasksRoute -> "Current route: Tasks"
                            else -> "Current route: Home"
                        },
                    )
                    Button(
                        onClick = {
                            backStack.clear()
                            backStack.add(TasksRoute)
                        },
                    ) {
                        Text("Open Tasks")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Open Tasks").performClick()
        composeRule.onNodeWithText("Current route: Tasks").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Current route: Tasks").assertIsDisplayed()
    }

    @Test
    fun enrichedQuickAddDraftAndExplicitDueRestoreBeforeSubmission() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        val original = "Restore #stu @Admin !2 tomorrow ~45m every 2 weeks"
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            OpenTasksTheme {
                QuickAddSheet(
                    onDismiss = {},
                    onAdd = submitted::set,
                    projects = projects,
                    tags = tags,
                    clock = clock,
                )
            }
        }

        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(original)
        var text = confirm(original, QuickAddTokenKind.RECURRENCE)
        text = confirm(text, QuickAddTokenKind.DATE)
        text = confirm(text, QuickAddTokenKind.ESTIMATE)
        text = confirm(text, QuickAddTokenKind.PRIORITY)
        text = confirm(text, QuickAddTokenKind.TAG)
        confirm(text, QuickAddTokenKind.PROJECT)

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("quick-add-title").performImeAction()

        val expectedDue = ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), zone.id)
        assertEquals(
            DomainCommand.CreateTask(
                title = "Restore",
                projectId = OpenTasksFixtures.studioProject.id,
                priority = Priority.HIGH,
                due = expectedDue,
                tagNames = listOf("Admin"),
                estimate = Duration.ofMinutes(45),
                recurrence = RecurrenceRule(RecurrenceFrequency.WEEKLY, interval = 2),
            ),
            submitted.get(),
        )

        val mondayText = "Restore every monday"
        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(mondayText)
        confirm(mondayText, QuickAddTokenKind.RECURRENCE)
        composeRule.onNodeWithTag("quick-add-title").performImeAction()
        assertEquals(expectedDue, submitted.get()?.due)
        assertEquals(
            RecurrenceRule(
                RecurrenceFrequency.WEEKLY,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            submitted.get()?.recurrence,
        )
    }

    @Test
    fun implicitWeekdayDueRemainsImplicitAcrossRestoration() {
        val observed = AtomicReference<QuickAddDraft>()
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            OpenTasksTheme {
                var draft by rememberSaveable(stateSaver = QuickAddDraftSaver) {
                    mutableStateOf(QuickAddDraft("Weekday every monday"))
                }
                SideEffect { observed.set(draft) }
                Column {
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { draft = draft.editTitle(it) },
                        modifier = Modifier.testTag("restored-weekday-title"),
                    )
                    Button(
                        onClick = {
                            val matches = parseQuickAdd(
                                draft.title,
                                clock.instant(),
                                clock.zone,
                                projects,
                                tags,
                            )
                            val recurrence = matches.single {
                                it.kind == QuickAddTokenKind.RECURRENCE
                            }
                            draft = draft.confirm(recurrence, matches)
                        },
                        modifier = Modifier.testTag("restored-weekday-confirm"),
                    ) { Text("Confirm recurrence") }
                }
            }
        }

        val expectedMonday = DomainCommand.CreateTask(
            title = "Weekday",
            due = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), zone.id),
            recurrence = RecurrenceRule(
                RecurrenceFrequency.WEEKLY,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
        )
        composeRule.onNodeWithTag("restored-weekday-confirm").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) {
            observed.get()?.toCommand() == expectedMonday
        }
        assertEquals(expectedMonday, observed.get().toCommand())
        assertEquals(false, observed.get().dueIsExplicit)

        observed.set(QuickAddDraft(""))
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitUntil(timeoutMillis = 2_000) {
            observed.get()?.toCommand() == expectedMonday
        }
        assertEquals(expectedMonday, observed.get().toCommand())
        assertEquals(false, observed.get().dueIsExplicit)

        composeRule.onNodeWithTag("restored-weekday-title")
            .performTextReplacement("Weekday every tuesday")
        composeRule.onNodeWithTag("restored-weekday-confirm").performClick()
        val expectedTuesday = DomainCommand.CreateTask(
            title = "Weekday",
            due = ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), zone.id),
            recurrence = RecurrenceRule(
                RecurrenceFrequency.WEEKLY,
                weekdays = setOf(DayOfWeek.TUESDAY),
            ),
        )
        composeRule.waitUntil(timeoutMillis = 2_000) {
            observed.get()?.toCommand() == expectedTuesday
        }
        assertEquals(expectedTuesday, observed.get().toCommand())
        assertEquals(false, observed.get().dueIsExplicit)
    }

    @Test
    fun dismissedSuggestionRemainsSuppressedAcrossRestoration() {
        val submitted = AtomicReference<DomainCommand.CreateTask?>()
        val text = "Dismiss @Admin"
        val match = parsed(text).single()
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            OpenTasksTheme {
                QuickAddSheet(
                    onDismiss = {},
                    onAdd = submitted::set,
                    projects = projects,
                    tags = tags,
                    clock = clock,
                )
            }
        }

        composeRule.onNodeWithTag("quick-add-title").performTextReplacement(text)
        composeRule.onNodeWithTag(dismissTag(match))
            .performSemanticsAction(SemanticsActions.OnClick)
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(suggestionTag(match)).assertDoesNotExist()
        composeRule.onNodeWithTag("quick-add-title").performImeAction()
        assertEquals(DomainCommand.CreateTask(text), submitted.get())
    }

    @Test
    fun searchQueryRestoresAndReissuesTheQueryAfterSavedInstanceStateRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        val latestQuery = AtomicReference(SearchQuery(""))
        val restored = SearchQuery(
            text = "restored",
            dueBuckets = setOf(DueBucket.LATER),
            priorities = setOf(Priority.HIGH),
            statuses = setOf(SemanticStatus.BLOCKED),
            sort = TaskSortKey.TITLE,
        )
        val view = SavedView(
            id = SavedViewId("restored-filter-view"),
            workspaceId = OpenTasksFixtures.workspaceId,
            name = "Restored filters",
            query = restored,
        )
        restorationTester.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = latestQuery::set,
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    savedViews = listOf(view),
                )
            }
        }

        composeRule.onNodeWithTag("saved-view-chip-${view.id.value}").performClick()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitUntil(timeoutMillis = 2_000) {
            latestQuery.get() == restored
        }

        composeRule.onNodeWithTag("workspace-search-query")
            .assertTextContains("restored", substring = true)
        composeRule.onNodeWithTag("active-saved-view-${view.id.value}").assertIsDisplayed()
    }

    @Test
    fun schedulePresentationMonthAndSelectedDateRestore() {
        val restorationTester = StateRestorationTester(composeRule)
        val projectionClock = Clock.fixed(
            Instant.parse("2026-08-31T09:00:00Z"),
            ZoneId.of("UTC"),
        )
        val snapshot = OpenTasksFixtures.snapshot

        restorationTester.setContent {
            OpenTasksTheme {
                ScheduleContent(
                    snapshot = snapshot,
                    projectNames = snapshot.projects.associate { it.id to it.name },
                    expanded = false,
                    projectionClock = projectionClock,
                    onOpenTask = {},
                )
            }
        }

        composeRule.onNodeWithTag("schedule-day-2026-09-01").performClick()
        composeRule.onNodeWithTag("schedule-presentation-month").performClick()
        composeRule.onNodeWithText("September 2026").assertExists()
        composeRule.onNodeWithTag("schedule-previous").performClick()
        composeRule.onNodeWithTag("schedule-month-day-2026-08-01").assertIsSelected()
        composeRule.onNodeWithTag("schedule-today").performClick()
        composeRule.onNodeWithText("August 2026").assertExists()
        composeRule.onNodeWithTag("schedule-month-day-2026-08-31").assertIsSelected()
        composeRule.onNodeWithTag("schedule-next").performClick()
        composeRule.onNodeWithText("September 2026").assertExists()
        composeRule.onNodeWithTag("schedule-month-day-2026-09-30").assertIsSelected()
        composeRule.onNodeWithTag("schedule-today").performClick()
        composeRule.onNodeWithTag("schedule-month-day-2026-07-27")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag("schedule-presentation-month").assertIsSelected()
        composeRule.onNodeWithText("August 2026").assertExists()
        composeRule.onNodeWithTag("schedule-month-day-2026-07-27").assertIsSelected()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("schedule-presentation-month").assertIsSelected()
        composeRule.onNodeWithText("August 2026").assertExists()
        composeRule.onNodeWithTag("schedule-month-day-2026-07-27").assertIsSelected()
    }

    @Test
    fun replacementZoneProviderChangesProjectionZoneWithoutChangingNow() {
        val instant = Instant.parse("2026-08-10T03:00:00Z")
        val instantClock = Clock.fixed(instant, ZoneId.of("UTC"))
        val zoneProvider = AtomicReference(ZoneId.of("Asia/Bangkok"))
        val readZone: () -> ZoneId = zoneProvider::get
        val timeVersion = mutableStateOf(0L)
        val observedClock = AtomicReference<Clock>()
        val observedTimelineToday = AtomicReference<LocalDate>()
        val snapshot = OpenTasksFixtures.snapshot

        composeRule.setContent {
            val projectionClock = rememberProjectionClock(
                clock = instantClock,
                zoneProvider = readZone,
                timeVersion = timeVersion.value,
            )
            SideEffect { observedClock.set(projectionClock) }
            OpenTasksTheme {
                Column {
                    ScheduleContent(
                        snapshot = snapshot,
                        projectNames = snapshot.projects.associate { it.id to it.name },
                        expanded = false,
                        projectionClock = projectionClock,
                        onOpenTask = {},
                    )
                    // A minimal probe for Task 11's constraint: Timeline's
                    // Today anchor must resample `zoneProvider` fresh at the
                    // action point (`currentDeviceClock`), never the
                    // memoized `projectionClock.zone` above -- so this reads
                    // `instantClock`/`readZone` directly, exactly as
                    // `OpenTasksApp`'s `onTimelineToday` does.
                    Button(
                        onClick = {
                            observedTimelineToday.set(
                                LocalDate.now(currentDeviceClock(instantClock, readZone))
                                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                            )
                        },
                        modifier = Modifier.testTag("timeline-today-probe"),
                    ) { Text("Timeline today probe") }
                }
            }
        }

        composeRule.onNodeWithTag("schedule-day-2026-08-10").assertIsSelected()
        assertEquals(instant, observedClock.get().instant())
        assertEquals(ZoneId.of("Asia/Bangkok"), observedClock.get().zone)

        composeRule.onNodeWithTag("timeline-today-probe").performClick()
        // 10 Aug 2026 is itself a Monday in Bangkok, so the current Monday
        // is that same day.
        assertEquals(LocalDate.of(2026, 8, 10), observedTimelineToday.get())

        composeRule.runOnIdle {
            zoneProvider.set(ZoneId.of("America/Los_Angeles"))
            timeVersion.value += 1L
        }
        composeRule.waitUntil(timeoutMillis = 2_000) {
            observedClock.get()?.zone == ZoneId.of("America/Los_Angeles")
        }
        composeRule.onNodeWithTag("schedule-today").performClick()

        assertEquals(instant, observedClock.get().instant())
        assertEquals(ZoneId.of("America/Los_Angeles"), observedClock.get().zone)
        composeRule.onNodeWithTag("schedule-day-2026-08-09").assertIsSelected()

        composeRule.onNodeWithTag("timeline-today-probe").performClick()
        // The injected instant never changes; only the zone did. Local time
        // in Los Angeles is now 9 Aug 2026 (a Sunday), so the current Monday
        // moves back to 3 Aug 2026.
        assertEquals(instant, instantClock.instant())
        assertEquals(LocalDate.of(2026, 8, 3), observedTimelineToday.get())
    }

    @Test
    fun timelinePresentationAnchorAndSelectionIsolateBetweenProjectsAfterRootRecreation() {
        val projectA = OpenTasksFixtures.researchProject
        val projectB = OpenTasksFixtures.studioProject
        val snapshot = OpenTasksFixtures.snapshot
        val chainTask = snapshot.tasks.first { it.projectId == projectA.id && it.dependencyIds.isNotEmpty() }
        val defaultFirstDate = LocalDate.of(2026, 8, 3)

        fun renderWith(state: WorkspaceProjectViewState, initialProjectId: ProjectId) {
            composeRule.setContent {
                val viewState by state.state.collectAsState()
                var selectedProjectId by remember { mutableStateOf(initialProjectId) }
                val presentation = viewState.presentationByProject[selectedProjectId]
                    ?: ProjectPresentation.LIST
                val firstDate = viewState.timelineFirstDateByProject[selectedProjectId]
                    ?: defaultFirstDate
                val selectedTaskId = viewState.selectedTimelineTaskByProject[selectedProjectId]
                val timelineProjection = if (presentation == ProjectPresentation.TIMELINE) {
                    computeProjectTimelineProjection(
                        snapshot = snapshot,
                        projectId = selectedProjectId,
                        window = ProjectTimelineWindow(firstDate),
                        selectedTaskId = selectedTaskId,
                    )
                } else {
                    null
                }
                OpenTasksTheme {
                    ProjectsScreen(
                        projects = snapshot.projects,
                        tasks = snapshot.tasks,
                        milestones = snapshot.milestones,
                        workflowStatuses = snapshot.workflowStatuses,
                        selectedProjectId = selectedProjectId,
                        showDetailPane = false,
                        presentation = presentation,
                        timelineProjection = timelineProjection,
                        onPresentationChange = { state.setProjectPresentation(selectedProjectId, it) },
                        onTimelinePrevious = {
                            state.setProjectTimelineFirstDate(selectedProjectId, firstDate.minusWeeks(4))
                        },
                        onTimelineToday = {
                            state.setProjectTimelineFirstDate(selectedProjectId, defaultFirstDate)
                        },
                        onTimelineNext = {
                            state.setProjectTimelineFirstDate(selectedProjectId, firstDate.plusWeeks(4))
                        },
                        onTimelineTaskSelectionChange = { taskId ->
                            state.setProjectTimelineSelection(selectedProjectId, taskId)
                        },
                        onSelectProject = { selectedProjectId = it },
                        onCloseDetail = {},
                        onUpdateProject = { _, _ -> },
                        onArchiveProject = {},
                        onOpenTask = {},
                    )
                }
            }
        }

        val original = WorkspaceProjectViewState(SavedStateHandle())
        renderWith(original, projectA.id)

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("workbench-view-timeline"))
        composeRule.onNodeWithTag("workbench-view-timeline").performClick()

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("timeline-next"))
        composeRule.onNodeWithTag("timeline-next").performClick()

        composeRule.onNodeWithTag("project-workbench-list")
            .performScrollToNode(hasTestTag("timeline-task-row-${chainTask.id.value}"))
        composeRule.onNodeWithTag("timeline-task-row-${chainTask.id.value}").performClick()

        assertEquals(
            ProjectPresentation.TIMELINE,
            original.state.value.presentationByProject[projectA.id],
        )
        assertEquals(
            defaultFirstDate.plusWeeks(4),
            original.state.value.timelineFirstDateByProject[projectA.id],
        )
        assertEquals(chainTask.id, original.state.value.selectedTimelineTaskByProject[projectA.id])

        // "Recreate": a fresh `WorkspaceProjectViewState` restored purely
        // from the exported `SavedStateHandle` keys, exactly as
        // `WorkspaceProjectViewStateTest` restores it -- this is the real
        // persistence boundary Task 10 established (a plain `rememberSaveable`
        // round trip would not exercise it, since this state lives in the
        // view model's `SavedStateHandle`, not local composable state).
        val restored = WorkspaceProjectViewState(
            SavedStateHandle(
                mapOf(
                    WorkspaceProjectViewState.PROJECT_BOARD_MODE_IDS to
                        original.state.value.presentationByProject
                            .filterValues { it == ProjectPresentation.BOARD }
                            .keys.map { it.value },
                    WorkspaceProjectViewState.PROJECT_TIMELINE_MODE_IDS to
                        original.state.value.presentationByProject
                            .filterValues { it == ProjectPresentation.TIMELINE }
                            .keys.map { it.value },
                    WorkspaceProjectViewState.PROJECT_TIMELINE_ANCHORS to
                        original.state.value.timelineFirstDateByProject
                            .flatMap { (id, date) -> listOf(id.value, date.toEpochDay().toString()) },
                    WorkspaceProjectViewState.PROJECT_TIMELINE_SELECTIONS to
                        original.state.value.selectedTimelineTaskByProject
                            .flatMap { (id, taskId) -> listOf(id.value, taskId.value) },
                ),
            ),
        )

        renderWith(restored, projectB.id)

        // B was never touched: it restores to the LIST default, independent
        // of A's Timeline selection/anchor.
        composeRule.onNodeWithTag("workbench-view-list").assertIsSelected()
        assertNull(restored.state.value.presentationByProject[projectB.id])
        assertNull(restored.state.value.timelineFirstDateByProject[projectB.id])
        assertNull(restored.state.value.selectedTimelineTaskByProject[projectB.id])

        // A's restored values are exactly what it left with.
        assertEquals(
            ProjectPresentation.TIMELINE,
            restored.state.value.presentationByProject[projectA.id],
        )
        assertEquals(
            defaultFirstDate.plusWeeks(4),
            restored.state.value.timelineFirstDateByProject[projectA.id],
        )
        assertEquals(chainTask.id, restored.state.value.selectedTimelineTaskByProject[projectA.id])
    }

    @Test
    fun dateOnlyDueSamplesReplacementProviderAtActionTime() {
        val zoneProvider = AtomicReference(ZoneId.of("Asia/Bangkok"))
        val readZone: () -> ZoneId = zoneProvider::get
        val date = LocalDate.parse("2026-08-10")

        assertEquals(
            ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), "Asia/Bangkok"),
            dateOnlyDue(date, readZone),
        )

        zoneProvider.set(ZoneId.of("America/Los_Angeles"))

        assertEquals(
            ZonedMoment(Instant.parse("2026-08-11T00:00:00Z"), "America/Los_Angeles"),
            dateOnlyDue(date, readZone),
        )
    }
}

@RunWith(AndroidJUnit4::class)
class MainActivityRecoveryRestorationInstrumentedTest {
    private val recoveryInbox by lazy {
        AndroidBackupFiles(InstrumentationRegistry.getInstrumentation().targetContext).recoveryInbox
    }
    private val recoveryFixtureRule = object : ExternalResource() {
        override fun before() {
            // MainActivity checks `locked` ahead of `activeRecovery` (Task 10),
            // and AppLockController's cold-start value is a Hilt @Singleton
            // read once from this real preferences file -- whichever test
            // first triggers its construction in this instrumentation
            // session latches it in. A stale `lock_enabled=true` left by an
            // earlier session would show the lock overlay instead of the
            // recovery shell this test depends on, so this establishes the
            // clean baseline this test needs before `composeRule` (the inner
            // rule) launches the production activity.
            AppLockSettings(
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getSharedPreferences("app_lock", Context.MODE_PRIVATE),
            ).lockEnabled = false
            check(!recoveryInbox.exists()) { "The disposable recovery inbox is not empty" }
            check(
                recoveryInbox.parentFile?.mkdirs() != false ||
                    recoveryInbox.parentFile?.isDirectory == true,
            )
            recoveryInbox.writeBytes(byteArrayOf(0))
        }

        override fun after() {
            check(!recoveryInbox.exists() || recoveryInbox.delete()) {
                "The disposable recovery fixture was not deleted"
            }
        }
    }
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(recoveryFixtureRule).around(composeRule)

    @Test
    fun productionRecoveryRouteClearsPassphraseAfterActivityRecreation() {
        composeRule.onNodeWithTag("recovery-shell").assertIsDisplayed()
        composeRule.onNodeWithTag("recovery-portable").performClick()
        val passphrase = composeRule.onNodeWithTag("recovery-passphrase")
            .assertIsDisplayed()
        passphrase.performTextReplacement("process private passphrase")
        passphrase.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.InputText,
                AnnotatedString("process private passphrase"),
            ),
        )

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("recovery-passphrase").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.InputText,
                AnnotatedString(""),
            ),
        )
    }
}
