package app.opentasks

import androidx.lifecycle.SavedStateHandle
import app.opentasks.core.domain.DefaultInsightsEngine
import app.opentasks.core.domain.InsightsEngine
import app.opentasks.core.model.InsightsRange
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import app.opentasks.feature.more.InsightsPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class WorkspaceInsightsStateTest {
    @Test
    fun insightsSelectionRoundTripsUsingOnlySupportedSavedStateTypes() {
        val handle = SavedStateHandle()
        val state = InsightsSelectionSavedState(handle)

        state.setRange(InsightsRange.NINETY_DAYS)
        state.setProjectFilter(ProjectId("project-z"), true)
        state.setProjectFilter(ProjectId("project-a"), true)
        state.setTagFilter(TagId("tag-z"), true)
        state.setTagFilter(TagId("tag-a"), true)
        state.setIncludeConflictedTime(true)
        state.setPresentation(InsightsPresentation.TABLE)

        assertEquals("NINETY_DAYS", handle.get<Any?>(InsightsSelectionSavedState.INSIGHTS_RANGE))
        assertEquals(
            arrayListOf("project-a", "project-z"),
            handle.get<Any?>(InsightsSelectionSavedState.INSIGHTS_PROJECT_IDS),
        )
        assertEquals(
            arrayListOf("tag-a", "tag-z"),
            handle.get<Any?>(InsightsSelectionSavedState.INSIGHTS_TAG_IDS),
        )
        assertEquals(
            true,
            handle.get<Any?>(InsightsSelectionSavedState.INSIGHTS_INCLUDE_CONFLICTED),
        )
        assertEquals(
            "TABLE",
            handle.get<Any?>(InsightsSelectionSavedState.INSIGHTS_PRESENTATION),
        )

        val restored = InsightsSelectionSavedState(
            SavedStateHandle(
                mapOf(
                    InsightsSelectionSavedState.INSIGHTS_RANGE to
                        handle.get<Any?>(InsightsSelectionSavedState.INSIGHTS_RANGE),
                    InsightsSelectionSavedState.INSIGHTS_PROJECT_IDS to
                        handle.get<Any?>(InsightsSelectionSavedState.INSIGHTS_PROJECT_IDS),
                    InsightsSelectionSavedState.INSIGHTS_TAG_IDS to
                        handle.get<Any?>(InsightsSelectionSavedState.INSIGHTS_TAG_IDS),
                    InsightsSelectionSavedState.INSIGHTS_INCLUDE_CONFLICTED to
                        handle.get<Any?>(
                            InsightsSelectionSavedState.INSIGHTS_INCLUDE_CONFLICTED,
                        ),
                    InsightsSelectionSavedState.INSIGHTS_PRESENTATION to
                        handle.get<Any?>(InsightsSelectionSavedState.INSIGHTS_PRESENTATION),
                ),
            ),
        )

        assertEquals(InsightsRange.NINETY_DAYS, restored.selection.value.range)
        assertEquals(
            setOf(ProjectId("project-a"), ProjectId("project-z")),
            restored.selection.value.projectIds,
        )
        assertEquals(
            setOf(TagId("tag-a"), TagId("tag-z")),
            restored.selection.value.tagIds,
        )
        assertTrue(restored.selection.value.includeConflictedTime)
        assertEquals(InsightsPresentation.TABLE, restored.presentation.value)
    }

    @Test
    fun malformedSavedStateFallsBackWithoutThrowing() {
        val state = InsightsSelectionSavedState(
            SavedStateHandle(
                mapOf(
                    InsightsSelectionSavedState.INSIGHTS_RANGE to 90,
                    InsightsSelectionSavedState.INSIGHTS_PROJECT_IDS to
                        listOf("project-a", 7, "", " ", "project-a"),
                    InsightsSelectionSavedState.INSIGHTS_TAG_IDS to
                        setOf("tag-a"),
                    InsightsSelectionSavedState.INSIGHTS_INCLUDE_CONFLICTED to "true",
                    InsightsSelectionSavedState.INSIGHTS_PRESENTATION to false,
                ),
            ),
        )

        assertEquals(InsightsRange.SEVEN_DAYS, state.selection.value.range)
        assertEquals(setOf(ProjectId("project-a")), state.selection.value.projectIds)
        assertTrue(state.selection.value.tagIds.isEmpty())
        assertFalse(state.selection.value.includeConflictedTime)
        assertEquals(InsightsPresentation.CHART, state.presentation.value)
    }

    @Test
    fun unknownEnumsAndBlankIdentifiersAreIgnored() {
        val state = InsightsSelectionSavedState(
            SavedStateHandle(
                mapOf(
                    InsightsSelectionSavedState.INSIGHTS_RANGE to "FORTNIGHT",
                    InsightsSelectionSavedState.INSIGHTS_PROJECT_IDS to
                        listOf("", "  ", "project-a"),
                    InsightsSelectionSavedState.INSIGHTS_TAG_IDS to
                        listOf("tag-a", "", "tag-a"),
                    InsightsSelectionSavedState.INSIGHTS_INCLUDE_CONFLICTED to true,
                    InsightsSelectionSavedState.INSIGHTS_PRESENTATION to "CARDS",
                ),
            ),
        )

        assertEquals(InsightsRange.SEVEN_DAYS, state.selection.value.range)
        assertEquals(setOf(ProjectId("project-a")), state.selection.value.projectIds)
        assertEquals(setOf(TagId("tag-a")), state.selection.value.tagIds)
        assertTrue(state.selection.value.includeConflictedTime)
        assertEquals(InsightsPresentation.CHART, state.presentation.value)
    }

    @Test
    fun oneTimeContextIsSharedBySummaryAndDetailForEachRefresh() {
        val provider = ManualInsightsTimeProvider(
            InsightsTimeContext(
                now = Instant.parse("2026-07-27T10:00:00Z"),
                zoneId = ZoneId.of("Asia/Bangkok"),
            ),
        )
        val engine = RecordingInsightsEngine()
        val scope = testScope()
        val state = workspaceInsightsState(
            workspace = OpenTasksFixtures.snapshot,
            provider = provider,
            engine = engine,
            scope = scope,
        )

        assertEquals(1, provider.captureCount)
        assertEquals(2, engine.contexts.size)
        assertEquals(engine.contexts[0], engine.contexts[1])

        scope.cancel()
    }

    @Test
    fun dueBoundaryRefreshesInsightsWithoutAWorkspaceMutation() = runBlocking {
        val now = Instant.parse("2026-07-27T10:00:00Z")
        val due = now.plusSeconds(30)
        val task = OpenTasksFixtures.tasks.first().copy(
            id = TaskId("boundary-task"),
            semanticStatus = SemanticStatus.PLANNED,
            completedAt = null,
            deletedAt = null,
            due = ZonedMoment(due, "UTC"),
        )
        val workspace = OpenTasksFixtures.snapshot.copy(tasks = listOf(task))
        val provider = ManualInsightsTimeProvider(InsightsTimeContext(now, ZoneId.of("UTC")))
        val scope = testScope()
        val state = workspaceInsightsState(workspace, provider, scope = scope)
        state.setForegrounded(true)

        assertTrue(state.summary.value.overdue.isEmpty())
        assertEquals(due.plusNanos(1), provider.nextScheduledRefresh())

        provider.advanceTo(InsightsTimeContext(due.plusNanos(1), ZoneId.of("UTC")))

        assertEquals(listOf(task.id), state.summary.value.overdue.map { it.taskId })
        scope.cancel()
    }

    @Test
    fun localMidnightRefreshesTheSelectedCalendarWindow() = runBlocking {
        val zone = ZoneId.of("Asia/Bangkok")
        val beforeMidnight = Instant.parse("2026-07-27T16:59:50Z")
        val midnight = Instant.parse("2026-07-27T17:00:00Z")
        val provider = ManualInsightsTimeProvider(InsightsTimeContext(beforeMidnight, zone))
        val scope = testScope()
        val state = workspaceInsightsState(OpenTasksFixtures.snapshot, provider, scope = scope)
        state.setForegrounded(true)
        val originalStart = state.summary.value.interval.startInclusive

        assertEquals(midnight, provider.nextScheduledRefresh())
        provider.advanceTo(InsightsTimeContext(midnight, zone))

        assertEquals(originalStart.plus(Duration.ofDays(1)), state.summary.value.interval.startInclusive)
        scope.cancel()
    }

    @Test
    fun boundedRecheckDetectsAClockOrZoneChangeWithoutAWorkspaceMutation() = runBlocking {
        val now = Instant.parse("2026-07-27T10:00:00Z")
        val provider = ManualInsightsTimeProvider(InsightsTimeContext(now, ZoneId.of("UTC")))
        val scope = testScope()
        val state = workspaceInsightsState(
            OpenTasksFixtures.snapshot.copy(tasks = emptyList()),
            provider,
            scope = scope,
        )
        state.setForegrounded(true)
        val utcStart = state.summary.value.interval.startInclusive
        val recheck = now.plus(Duration.ofMinutes(15))

        assertEquals(recheck, provider.nextScheduledRefresh())
        provider.advanceTo(InsightsTimeContext(recheck, ZoneId.of("Pacific/Kiritimati")))

        assertTrue(state.summary.value.interval.startInclusive != utcStart)
        scope.cancel()
    }

    @Test
    fun foregroundRefreshDetectsAZoneChangeImmediately() {
        val now = Instant.parse("2026-07-27T10:00:00Z")
        val provider = ManualInsightsTimeProvider(InsightsTimeContext(now, ZoneId.of("UTC")))
        val scope = testScope()
        val state = workspaceInsightsState(OpenTasksFixtures.snapshot, provider, scope = scope)
        val utcStart = state.summary.value.interval.startInclusive

        provider.context = InsightsTimeContext(now, ZoneId.of("Pacific/Kiritimati"))
        state.setForegrounded(true)

        assertTrue(state.summary.value.interval.startInclusive != utcStart)
        scope.cancel()
    }

    @Test
    fun schedulerIsInactiveUntilForegroundedAndCancelsWhenBackgrounded() {
        val now = Instant.parse("2026-07-27T10:00:00Z")
        val provider = ManualInsightsTimeProvider(InsightsTimeContext(now, ZoneId.of("UTC")))
        val scope = testScope()
        val state = workspaceInsightsState(
            OpenTasksFixtures.snapshot.copy(tasks = emptyList()),
            provider,
            scope = scope,
        )

        assertEquals(1, provider.captureCount)
        assertEquals(0, provider.scheduleCount)
        assertEquals(0, provider.activeWaitCount)

        state.setForegrounded(true)

        assertEquals(2, provider.captureCount)
        assertEquals(1, provider.scheduleCount)
        assertEquals(1, provider.activeWaitCount)

        state.setForegrounded(false)

        assertEquals(2, provider.captureCount)
        assertEquals(1, provider.scheduleCount)
        assertEquals(0, provider.activeWaitCount)
        assertEquals(1, provider.cancelledWaitCount)
        scope.cancel()
    }

    @Test
    fun repeatedForegroundSignalsCaptureOnceAndKeepOneScheduler() {
        val now = Instant.parse("2026-07-27T10:00:00Z")
        val provider = ManualInsightsTimeProvider(InsightsTimeContext(now, ZoneId.of("UTC")))
        val scope = testScope()
        val state = workspaceInsightsState(
            OpenTasksFixtures.snapshot.copy(tasks = emptyList()),
            provider,
            scope = scope,
        )
        val initialStart = state.summary.value.interval.startInclusive
        provider.context = InsightsTimeContext(
            now.plus(Duration.ofDays(1)),
            ZoneId.of("Pacific/Kiritimati"),
        )

        state.setForegrounded(false)
        state.setForegrounded(true)
        state.setForegrounded(true)

        assertEquals(2, provider.captureCount)
        assertEquals(1, provider.scheduleCount)
        assertEquals(1, provider.activeWaitCount)
        assertTrue(state.summary.value.interval.startInclusive != initialStart)

        state.setForegrounded(false)
        state.setForegrounded(false)

        assertEquals(1, provider.cancelledWaitCount)
        assertEquals(0, provider.activeWaitCount)
        scope.cancel()
    }

    @Test
    fun restoredUnknownIdsAreIgnoredAgainstTheCurrentWorkspace() {
        val handle = SavedStateHandle(
            mapOf(
                InsightsSelectionSavedState.INSIGHTS_PROJECT_IDS to
                    listOf("project-studio", "missing-project"),
                InsightsSelectionSavedState.INSIGHTS_TAG_IDS to
                    listOf("tag-admin", "missing-tag"),
            ),
        )
        val provider = ManualInsightsTimeProvider(
            InsightsTimeContext(
                Instant.parse("2026-07-27T10:00:00Z"),
                ZoneId.of("Asia/Bangkok"),
            ),
        )
        val scope = testScope()
        val state = WorkspaceInsightsState(
            workspace = MutableStateFlow(OpenTasksFixtures.snapshot),
            savedStateHandle = handle,
            insightsEngine = DefaultInsightsEngine(),
            timeProvider = provider,
            scope = scope,
        )

        assertEquals(
            setOf(ProjectId("project-studio")),
            state.uiState.value.selection.projectIds,
        )
        assertEquals(
            setOf(TagId("tag-admin")),
            state.uiState.value.selection.tagIds,
        )
        scope.cancel()
    }

    private fun workspaceInsightsState(
        workspace: WorkspaceSnapshot,
        provider: ManualInsightsTimeProvider,
        engine: InsightsEngine = DefaultInsightsEngine(),
        scope: CoroutineScope,
    ): WorkspaceInsightsState = WorkspaceInsightsState(
        workspace = MutableStateFlow(workspace),
        savedStateHandle = SavedStateHandle(),
        insightsEngine = engine,
        timeProvider = provider,
        scope = scope,
    )

    private fun testScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class RecordingInsightsEngine : InsightsEngine {
        val contexts = mutableListOf<InsightsTimeContext>()
        private val delegate = DefaultInsightsEngine()

        override fun calculate(
            workspace: WorkspaceSnapshot,
            selection: InsightsSelection,
            now: Instant,
            zoneId: ZoneId,
        ): InsightsSnapshot {
            contexts += InsightsTimeContext(now, zoneId)
            return delegate.calculate(workspace, selection, now, zoneId)
        }
    }

    private class ManualInsightsTimeProvider(
        initialContext: InsightsTimeContext,
    ) : InsightsTimeProvider {
        var context: InsightsTimeContext = initialContext
        var captureCount: Int = 0
            private set
        var scheduleCount: Int = 0
            private set
        var activeWaitCount: Int = 0
            private set
        var cancelledWaitCount: Int = 0
            private set

        private val scheduled = Channel<Instant>(Channel.UNLIMITED)
        private val releases = Channel<Unit>(Channel.UNLIMITED)

        override fun capture(): InsightsTimeContext {
            captureCount++
            return context
        }

        override suspend fun awaitUntil(instant: Instant) {
            scheduleCount++
            activeWaitCount++
            try {
                scheduled.send(instant)
                releases.receive()
            } finally {
                activeWaitCount--
                if (!kotlin.coroutines.coroutineContext.isActive) {
                    cancelledWaitCount++
                }
            }
        }

        suspend fun nextScheduledRefresh(): Instant = scheduled.receive()

        fun advanceTo(value: InsightsTimeContext) {
            context = value
            releases.trySend(Unit).getOrThrow()
        }
    }
}
