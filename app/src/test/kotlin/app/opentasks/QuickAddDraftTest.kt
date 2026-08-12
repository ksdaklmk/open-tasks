package app.opentasks

import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.QuickAddTokenKind
import app.opentasks.core.domain.QuickAddTokenMatch
import app.opentasks.core.domain.QuickAddTokenValue
import app.opentasks.core.domain.parseQuickAdd
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.ZonedMoment
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAddDraftTest {
    private val zone = ZoneId.of("Asia/Bangkok")
    private val now = Instant.parse("2026-08-10T03:00:00Z")
    private val projects = listOf(OpenTasksFixtures.studioProject)
    private val tags = OpenTasksFixtures.tags

    private fun parsed(text: String) = parseQuickAdd(text, now, zone, projects, tags)

    private fun match(
        text: String,
        kind: QuickAddTokenKind,
        occurrence: Int = 0,
    ) = parsed(text).filter { it.kind == kind }[occurrence]

    private fun QuickAddDraft.confirmCurrent(
        kind: QuickAddTokenKind,
        occurrence: Int = 0,
    ): QuickAddDraft {
        val matches = parsed(title)
        return confirm(matches.filter { it.kind == kind }[occurrence], matches)
    }

    private fun QuickAddDraft.confirmValue(value: QuickAddTokenValue): QuickAddDraft {
        val match = QuickAddTokenMatch(0, 0, value)
        return confirm(match, listOf(match))
    }

    @Test
    fun rightToLeftConfirmationProducesTheExactGrammarFreeCommand() {
        val original = "Plan #stu @Admin !1 every monday ~45m tomorrow"
        val expectedDue = ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), zone.id)
        var draft = QuickAddDraft(title = original)

        assertEquals(DomainCommand.CreateTask(original), draft.toCommand())
        // Recurrence is confirmed while its explicit date token is still present;
        // every later match is obtained from the current, stripped title.
        draft = draft.confirmCurrent(QuickAddTokenKind.RECURRENCE)
        draft = draft.confirmCurrent(QuickAddTokenKind.DATE)
        draft = draft.confirmCurrent(QuickAddTokenKind.ESTIMATE)
        draft = draft.confirmCurrent(QuickAddTokenKind.PRIORITY)
        draft = draft.confirmCurrent(QuickAddTokenKind.TAG)
        draft = draft.confirmCurrent(QuickAddTokenKind.PROJECT)

        assertEquals(
            DomainCommand.CreateTask(
                title = "Plan",
                projectId = OpenTasksFixtures.studioProject.id,
                priority = Priority.URGENT,
                due = expectedDue,
                tagNames = listOf("Admin"),
                estimate = Duration.ofMinutes(45),
                recurrence = RecurrenceRule(
                    frequency = RecurrenceFrequency.WEEKLY,
                    weekdays = setOf(DayOfWeek.MONDAY),
                ),
            ),
            draft.toCommand(),
        )
        val dateCleared = draft.clear(QuickAddTokenKind.DATE)
        assertNull(dateCleared.toCommand().recurrence)
        assertFalse(dateCleared.dueIsExplicit)
        val recurrenceCleared = draft.clear(QuickAddTokenKind.RECURRENCE)
        assertEquals(expectedDue, recurrenceCleared.toCommand().due)
        assertTrue(recurrenceCleared.dueIsExplicit)
    }

    @Test
    fun recurrenceConfirmedAfterDateKeepsTheExplicitAppliedDue() {
        var draft = QuickAddDraft("Plan tomorrow every monday")
        draft = draft.confirmCurrent(QuickAddTokenKind.DATE)
        val explicitDue = draft.toCommand().due
        val reparsedRecurrence = match(draft.title, QuickAddTokenKind.RECURRENCE)
        val implicitDue = (reparsedRecurrence.value as QuickAddTokenValue.RecurrenceValue).due
        assertEquals(ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), zone.id), explicitDue)
        assertNotEquals(explicitDue, implicitDue)

        draft = draft.confirm(reparsedRecurrence, parsed(draft.title))

        assertEquals("Plan", draft.toCommand().title)
        assertEquals(explicitDue, draft.toCommand().due)
        assertTrue(draft.dueIsExplicit)
        assertEquals(
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            draft.toCommand().recurrence,
        )
    }

    @Test
    fun laterRecurrenceReplacesRuleAndImplicitAnchorInEitherOrder() {
        val original = "Plan every monday every tuesday"
        val mondayDue = ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), zone.id)
        val tuesdayDue = ZonedMoment(Instant.parse("2026-08-11T10:00:00Z"), zone.id)

        var mondayThenTuesday = QuickAddDraft(original)
            .confirmCurrent(QuickAddTokenKind.RECURRENCE, occurrence = 0)
        assertEquals(mondayDue, mondayThenTuesday.toCommand().due)
        assertFalse(mondayThenTuesday.dueIsExplicit)
        mondayThenTuesday = mondayThenTuesday
            .confirmCurrent(QuickAddTokenKind.RECURRENCE, occurrence = 0)
        assertEquals("Plan", mondayThenTuesday.toCommand().title)
        assertEquals(tuesdayDue, mondayThenTuesday.toCommand().due)
        assertEquals(
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                weekdays = setOf(DayOfWeek.TUESDAY),
            ),
            mondayThenTuesday.toCommand().recurrence,
        )
        assertFalse(mondayThenTuesday.dueIsExplicit)

        var tuesdayThenMonday = QuickAddDraft(original)
            .confirmCurrent(QuickAddTokenKind.RECURRENCE, occurrence = 1)
        assertEquals(tuesdayDue, tuesdayThenMonday.toCommand().due)
        tuesdayThenMonday = tuesdayThenMonday
            .confirmCurrent(QuickAddTokenKind.RECURRENCE, occurrence = 0)
        assertEquals("Plan", tuesdayThenMonday.toCommand().title)
        assertEquals(mondayDue, tuesdayThenMonday.toCommand().due)
        assertEquals(
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                weekdays = setOf(DayOfWeek.MONDAY),
            ),
            tuesdayThenMonday.toCommand().recurrence,
        )
        assertFalse(tuesdayThenMonday.dueIsExplicit)
    }

    @Test
    fun clearingRecurrenceMakesItsRetainedDueExplicit() {
        var draft = QuickAddDraft("Plan every monday")
            .confirmCurrent(QuickAddTokenKind.RECURRENCE)
            .clear(QuickAddTokenKind.RECURRENCE)
            .editTitle("Plan every tuesday")
        assertTrue(draft.dueIsExplicit)

        draft = draft.confirmCurrent(QuickAddTokenKind.RECURRENCE)

        assertEquals(ZonedMoment(Instant.parse("2026-08-10T10:00:00Z"), zone.id), draft.toCommand().due)
        assertEquals(
            RecurrenceRule(
                frequency = RecurrenceFrequency.WEEKLY,
                weekdays = setOf(DayOfWeek.TUESDAY),
            ),
            draft.toCommand().recurrence,
        )
        assertTrue(draft.dueIsExplicit)
    }

    @Test
    fun dismissedTokenSurvivesEarlierConfirmationAndReparse() {
        val original = "Plan #stu @Admin"
        val matches = parsed(original)
        val project = matches.single { it.kind == QuickAddTokenKind.PROJECT }
        val tag = matches.single { it.kind == QuickAddTokenKind.TAG }
        var draft = QuickAddDraft(original).dismiss(tag, matches)
        assertEquals(setOf("tag:@admin:0"), draft.dismissedTokenKeys)

        draft = draft.confirm(project, matches)
        val reparsed = parsed(draft.title)
        val reparsedTag = reparsed.single { it.kind == QuickAddTokenKind.TAG }

        assertEquals("Plan @Admin", draft.title)
        assertTrue(reparsedTag.tokenKey(draft.title, reparsed) in draft.dismissedTokenKeys)
        assertTrue(draft.editTitle(draft.title + " now").dismissedTokenKeys.isEmpty())
    }

    @Test
    fun identicalTokensRemainIndividuallyDismissible() {
        val title = "@Admin @Admin"
        val matches = parsed(title)
        val draft = QuickAddDraft(title).dismiss(matches.last(), matches)
        val visible = matches.filterNot {
            it.tokenKey(title, matches) in draft.dismissedTokenKeys
        }

        assertEquals(setOf("tag:@admin:1"), draft.dismissedTokenKeys)
        assertEquals(listOf(matches.first()), visible)
    }

    @Test
    fun identicalDismissalOrdinalRebasesWhenEitherSideIsConfirmed() {
        listOf(
            1 to 0, // dismiss right, confirm left
            0 to 1, // dismiss left, confirm right
        ).forEach { (dismissedIndex, confirmedIndex) ->
            val title = "@Admin @Admin"
            val matches = parsed(title)
            val draft = QuickAddDraft(title)
                .dismiss(matches[dismissedIndex], matches)
                .confirm(matches[confirmedIndex], matches)
            val reparsed = parsed(draft.title)

            assertEquals("@Admin", draft.title)
            assertEquals(setOf("tag:@admin:0"), draft.dismissedTokenKeys)
            assertTrue(
                reparsed.single().tokenKey(draft.title, reparsed) in
                    draft.dismissedTokenKeys,
            )
        }
    }

    @Test
    fun tagsAccumulateCaseInsensitivelyAndSingleValuesReplace() {
        val first = QuickAddDraft("Task")
            .confirmValue(QuickAddTokenValue.TagValue("Focus", null))
            .confirmValue(QuickAddTokenValue.TagValue("FOCUS", null))
            .confirmValue(QuickAddTokenValue.PriorityValue(Priority.LOW))
            .confirmValue(QuickAddTokenValue.PriorityValue(Priority.URGENT))
        assertEquals(listOf("Focus"), first.toCommand().tagNames)
        assertEquals(Priority.URGENT, first.toCommand().priority)
    }

    @Test
    fun confirmingDistinctFiftyFirstTagPreservesTheDraft() {
        val appliedTags = (1..50).map { "tag-$it" }
        val draft = QuickAddDraft(
            title = "Task @overflow",
            tagNames = appliedTags,
        ).confirmCurrent(QuickAddTokenKind.TAG)

        assertEquals("Task @overflow", draft.title)
        assertEquals(appliedTags, draft.toCommand().tagNames)
    }

    @Test
    fun confirmingDuplicateTagAtLimitStillStripsItsToken() {
        val appliedTags = listOf("Admin") + (1..49).map { "tag-$it" }
        val draft = QuickAddDraft(
            title = "Task @admin",
            tagNames = appliedTags,
        ).confirmCurrent(QuickAddTokenKind.TAG)

        assertEquals("Task", draft.title)
        assertEquals(appliedTags, draft.toCommand().tagNames)
    }

    @Test
    fun malformedSaverValuesRestoreFailClosed() {
        val restored = requireNotNull(
            QuickAddDraftSaver.restore(
                arrayListOf<Any?>(
                    "title", "Malformed",
                    "priority", "INVALID",
                    "frequency", "INVALID",
                    "interval", 0,
                    "weekdays", arrayListOf("FUNDAY"),
                    "dueEpoch", 1_234L,
                    "dueZone", "Not/AZone",
                    "dueExplicit", true,
                ),
            ),
        )
        assertEquals(Priority.NONE.name, restored.priority)
        assertNull(restored.recurrenceFrequency)
        assertEquals(1, restored.recurrenceInterval)
        assertTrue(restored.recurrenceWeekdays.isEmpty())
        assertNull(restored.dueEpochMillis)
        assertNull(restored.dueZoneId)
        assertFalse(restored.dueIsExplicit)
        assertEquals(DomainCommand.CreateTask("Malformed"), restored.toCommand())
    }
}
