package app.opentasks.calendar

import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarInsertionTest {
    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val revision = Revision(DeviceId("calendar-test-device"), 1, 0)

    private fun task(
        title: String = "Point launch domain",
        start: ZonedMoment? = null,
        due: ZonedMoment? = null,
        projectId: ProjectId? = OpenTasksFixtures.studioProject.id,
    ): Task = Task(
        id = TaskId("task-calendar"),
        workspaceId = OpenTasksFixtures.workspaceId,
        projectId = projectId,
        statusId = OpenTasksFixtures.statusId(projectId, SemanticStatus.PLANNED),
        semanticStatus = SemanticStatus.PLANNED,
        title = title,
        start = start,
        due = due,
        revision = revision,
    )

    private fun momentAt(hour: Int): ZonedMoment =
        ZonedMoment(Instant.parse("2026-08-10T00:00:00Z").plusSeconds(hour * 3_600L), zone.id)

    @Test
    fun undatedTaskProducesNoDraft() {
        val draft = calendarEventDraft(task(), projectName = "Studio refresh")

        assertNull(draft)
    }

    @Test
    fun startAndDueProduceBeginAndEndTimes() {
        val start = momentAt(9)
        val due = momentAt(10)

        val draft = calendarEventDraft(
            task(start = start, due = due),
            projectName = "Studio refresh",
        )

        assertEquals(start.instant.toEpochMilli(), draft?.beginEpochMillis)
        assertEquals(due.instant.toEpochMilli(), draft?.endEpochMillis)
    }

    @Test
    fun dueOnlyTaskHasNoEndTime() {
        val due = momentAt(14)

        val draft = calendarEventDraft(task(due = due), projectName = "Studio refresh")

        assertEquals(due.instant.toEpochMilli(), draft?.beginEpochMillis)
        assertNull(draft?.endEpochMillis)
    }

    @Test
    fun dueBeforeStartHasNoEndTime() {
        val start = momentAt(14)
        val due = momentAt(9)

        val draft = calendarEventDraft(
            task(start = start, due = due),
            projectName = "Studio refresh",
        )

        assertEquals(start.instant.toEpochMilli(), draft?.beginEpochMillis)
        assertNull(draft?.endEpochMillis)
    }

    @Test
    fun descriptionUsesProjectName() {
        val draft = calendarEventDraft(task(due = momentAt(9)), projectName = "Studio refresh")

        assertEquals("Project: Studio refresh", draft?.description)
    }

    @Test
    fun inboxTaskHasEmptyDescription() {
        val draft = calendarEventDraft(
            task(due = momentAt(9), projectId = null),
            projectName = null,
        )

        assertEquals("", draft?.description)
    }
}
