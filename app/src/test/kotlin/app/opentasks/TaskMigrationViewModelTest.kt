package app.opentasks

import app.opentasks.core.data.export.TaskCsvTarget
import app.opentasks.core.data.export.emptyTaskCsvTarget
import app.opentasks.core.model.TaskCsvBlockingIssue
import app.opentasks.core.model.TaskCsvDateOrder
import app.opentasks.core.model.TaskCsvEstimateUnit
import app.opentasks.core.model.TaskCsvField
import app.opentasks.core.model.TaskCsvPriorityChoice
import app.opentasks.core.model.TaskCsvStatusChoice
import app.opentasks.core.model.TaskCsvTagMode
import app.opentasks.feature.more.TaskMigrationLoadFailure
import app.opentasks.feature.more.TaskMigrationUiState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMigrationViewModelTest {
    @Test
    fun beginRequestsOnePickerAndCancellationIsQuiet() {
        val subject = viewModel()

        subject.begin(emptyTaskCsvTarget())

        assertNotNull(subject.openDocumentRequests.tryReceive().getOrNull())
        subject.onDocumentSelected(null)
        assertNull(subject.state.value)
    }

    @Test
    fun cancellingAReplacementPickerKeepsTheCurrentReview() {
        val subject = viewModel()
        load(subject, "Title\r\nOne\r\n")
        val before = subject.state.value

        subject.chooseAnother()
        assertNotNull(subject.openDocumentRequests.tryReceive().getOrNull())
        subject.onDocumentSelected(null)

        assertSame(before, subject.state.value)
    }

    @Test
    fun acceptedBytesAreClearedAfterParsing() {
        val subject = viewModel()
        val bytes = "Title\r\nOne\r\n".toByteArray()
        subject.begin(emptyTaskCsvTarget())
        subject.openDocumentRequests.tryReceive()

        subject.acceptDocument("tasks.csv", bytes)

        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(subject.state.value is TaskMigrationUiState.Review)
    }

    @Test
    fun parseFailurePublishesTheExactLoadFailureAndRow() {
        val subject = viewModel()

        load(subject, "Title\r\n\"unterminated")

        val failure = subject.state.value as TaskMigrationUiState.LoadFailure
        assertEquals(TaskMigrationLoadFailure.MALFORMED, failure.reason)
        assertEquals(1, failure.rowNumber)
    }

    @Test
    fun remappingAColumnRemovesItFromThePreviousField() {
        val subject = viewModel()
        load(subject, "Title,Project\r\nOne,Launch\r\n")

        subject.mapField(TaskCsvField.PROJECT, 0)

        val mapping = review(subject).mapping
        assertNull(mapping.columns[TaskCsvField.TITLE])
        assertEquals(0, mapping.columns[TaskCsvField.PROJECT])
        assertTrue(
            TaskCsvBlockingIssue.TITLE_MAPPING_REQUIRED in
                review(subject).blockingIssues,
        )
    }

    @Test
    fun statusPriorityDateEstimateAndTagEditsRecomputeReview() {
        val subject = viewModel()
        load(
            subject,
            "Title,Status,Priority,Due,Estimate,Tags\r\n" +
                "One,3,2,03/04/2026,2,a;b|c\r\n",
        )
        assertTrue(review(subject).blockingIssues.containsAll(
            setOf(
                TaskCsvBlockingIssue.STATUS_CHOICES_REQUIRED,
                TaskCsvBlockingIssue.PRIORITY_CHOICES_REQUIRED,
                TaskCsvBlockingIssue.DATE_ORDER_REQUIRED,
                TaskCsvBlockingIssue.ESTIMATE_UNIT_REQUIRED,
                TaskCsvBlockingIssue.TAG_MODE_REQUIRED,
            ),
        ))

        subject.chooseStatus("3", TaskCsvStatusChoice.IN_PROGRESS)
        subject.choosePriority("2", TaskCsvPriorityChoice.HIGH)
        subject.chooseDateOrder(TaskCsvDateOrder.DAY_MONTH_YEAR)
        subject.chooseEstimateUnit(TaskCsvEstimateUnit.HOURS)
        subject.chooseTagMode(TaskCsvTagMode.SINGLE)

        assertTrue(review(subject).blockingIssues.isEmpty())
        assertEquals(120L, subject.confirm(emptyTaskCsvTarget())!!.single().estimateMinutes)
    }

    @Test
    fun confirmRevalidatesAgainstTheLatestTarget() {
        val subject = viewModel()
        load(subject, "Title\r\nOne\r\n")

        val rows = subject.confirm(
            TaskCsvTarget(
                projects = emptyList(),
                workflowStatuses = emptyList(),
                tags = emptyList(),
            ),
        )

        assertNull(rows)
        assertTrue(TaskCsvBlockingIssue.TARGET_REJECTED in review(subject).blockingIssues)
    }

    @Test
    fun confirmUsesTheFreshConfirmationInstantForInferredDoneRows() {
        var instant = Instant.parse("2026-08-24T12:00:00Z")
        val subject = viewModel(now = { instant }, zone = { ZoneId.of("UTC") })
        load(subject, "Title,Status\r\nOne,Done\r\n")
        instant = Instant.parse("2026-08-24T12:05:00Z")

        val row = subject.confirm(emptyTaskCsvTarget())!!.single()

        assertEquals(instant, row.completedAt)
    }

    @Test
    fun aSecondConfirmWhileCommittingReturnsNoRows() {
        val subject = viewModel()
        load(subject, "Title\r\nOne\r\n")

        assertNotNull(subject.confirm(emptyTaskCsvTarget()))
        assertNull(subject.confirm(emptyTaskCsvTarget()))
    }

    @Test
    fun mappingEditDuringCommitDoesNotAllowASecondConfirm() {
        val subject = viewModel()
        load(subject, "Title\r\nOne\r\n")

        assertNotNull(subject.confirm(emptyTaskCsvTarget()))
        subject.mapField(TaskCsvField.TITLE, 0)

        assertTrue(review(subject).isCommitting)
        assertNull(subject.confirm(emptyTaskCsvTarget()))
    }

    @Test
    fun queryCancellationIsRethrownDuringDocumentRead() {
        try {
            taskMigrationDisplayName {
                throw CancellationException("test cancellation")
            }
        } catch (_: CancellationException) {
            return
        }

        org.junit.Assert.fail("query cancellation was swallowed")
    }

    @Test
    fun aRejectedCommitKeepsTheReviewAndASuccessClearsIt() {
        val subject = viewModel()
        load(subject, "Title\r\nOne\r\n")
        subject.confirm(emptyTaskCsvTarget())

        subject.onCommitFinished(success = false)
        assertFalse(review(subject).isCommitting)
        assertNotNull(subject.confirm(emptyTaskCsvTarget()))
        subject.onCommitFinished(success = true)
        assertNull(subject.state.value)
    }

    @Test
    fun cancelDropsTheDocumentAndReview() {
        val subject = viewModel()
        load(subject, "Title\r\nOne\r\n")

        subject.cancel()

        assertNull(subject.state.value)
        assertNull(subject.confirm(emptyTaskCsvTarget()))
    }

    private fun viewModel(
        now: () -> Instant = { Instant.parse("2026-08-24T12:00:00Z") },
        zone: () -> ZoneId = { ZoneId.of("Asia/Bangkok") },
    ) = TaskMigrationViewModel(
        readDocument = { null },
        now = now,
        zoneProvider = zone,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun load(subject: TaskMigrationViewModel, source: String) {
        subject.begin(emptyTaskCsvTarget())
        assertNotNull(subject.openDocumentRequests.tryReceive().getOrNull())
        subject.acceptDocument("tasks.csv", source.toByteArray())
    }

    private fun review(subject: TaskMigrationViewModel) =
        subject.state.value as TaskMigrationUiState.Review
}
