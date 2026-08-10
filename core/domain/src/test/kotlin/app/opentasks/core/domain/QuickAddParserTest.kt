package app.opentasks.core.domain

import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAddParserTest {

    private val zone = ZoneId.of("Asia/Bangkok")
    private val now = ZonedDateTime.of(2026, 8, 10, 10, 0, 0, 0, zone).toInstant()
    private val projects = listOf(
        OpenTasksFixtures.studioProject.copy(id = ProjectId("short"), name = "Studio"),
        OpenTasksFixtures.studioProject.copy(id = ProjectId("long"), name = "Studio refresh"),
        OpenTasksFixtures.taxProject.copy(archivedAt = now),
    )
    private val tags = listOf(Tag(TagId("focus"), OpenTasksFixtures.workspaceId, "Focus"))

    private fun tokens(text: String, at: Instant = now) =
        parseQuickAdd(text, at, zone, projects, tags)

    @Test
    fun sigilsRequireAWordStartAndResolveDeterministically() {
        val parsed = tokens("Plan: #stu @FOCUS @new !1 ~1h30m")
        assertEquals(
            listOf(
                QuickAddTokenKind.PROJECT,
                QuickAddTokenKind.TAG,
                QuickAddTokenKind.TAG,
                QuickAddTokenKind.PRIORITY,
                QuickAddTokenKind.ESTIMATE,
            ),
            parsed.map(QuickAddTokenMatch::kind),
        )
        assertEquals("short", (parsed[0].value as QuickAddTokenValue.ProjectValue).projectId.value)
        assertEquals(TagId("focus"), (parsed[1].value as QuickAddTokenValue.TagValue).existingTagId)
        assertNull((parsed[2].value as QuickAddTokenValue.TagValue).existingTagId)
        assertEquals(Priority.URGENT, (parsed[3].value as QuickAddTokenValue.PriorityValue).priority)
        assertEquals(Duration.ofMinutes(90), (parsed[4].value as QuickAddTokenValue.EstimateValue).duration)
        assertTrue(tokens("mail@host abc#studio wow!high").isEmpty())
    }

    @Test
    fun priorityAndEstimateAliasesRespectBounds() {
        mapOf(
            "!low" to Priority.LOW,
            "!med" to Priority.MEDIUM,
            "!medium" to Priority.MEDIUM,
            "!high" to Priority.HIGH,
            "!urgent" to Priority.URGENT,
            "!1" to Priority.URGENT,
            "!2" to Priority.HIGH,
            "!3" to Priority.MEDIUM,
            "!4" to Priority.LOW,
        ).forEach { (text, expected) ->
            assertEquals(expected, (tokens(text).single().value as QuickAddTokenValue.PriorityValue).priority)
        }
        listOf("!0", "!5", "!unknown", "~0", "~-1m", "~24h1m", "~999999999999h")
            .forEach { assertTrue(it, tokens(it).isEmpty()) }
        mapOf(
            "~30m" to Duration.ofMinutes(30),
            "~2h" to Duration.ofHours(2),
            "~45" to Duration.ofMinutes(45),
            "~24h" to Duration.ofHours(24),
        ).forEach { (text, expected) ->
            assertEquals(expected, (tokens(text).single().value as QuickAddTokenValue.EstimateValue).duration)
        }
    }

    @Test
    fun recurrenceClaimsBeforeDatesAndUsesRightmostExplicitDate() {
        val parsed = tokens("@friday every monday tomorrow then friday")
        assertEquals(
            listOf(
                QuickAddTokenKind.TAG,
                QuickAddTokenKind.RECURRENCE,
                QuickAddTokenKind.DATE,
                QuickAddTokenKind.DATE,
            ),
            parsed.map(QuickAddTokenMatch::kind),
        )
        val recurrence = parsed.first { it.kind == QuickAddTokenKind.RECURRENCE }
            .value as QuickAddTokenValue.RecurrenceValue
        val rightmost = parsed.last { it.kind == QuickAddTokenKind.DATE }
            .value as QuickAddTokenValue.DueValue
        assertEquals(rightmost.due, recurrence.due)
        assertEquals(setOf(DayOfWeek.MONDAY), recurrence.rule.weekdays)
        assertEquals(
            parsed.sortedWith(compareBy(QuickAddTokenMatch::startIndex).thenBy { it.endIndex }),
            parsed,
        )
    }

    @Test
    fun recurrenceAnchorsAtTheNextMatchingFivePm() {
        val morning = (tokens("every monday").single().value as QuickAddTokenValue.RecurrenceValue).due
        val eveningNow = ZonedDateTime.of(2026, 8, 10, 20, 0, 0, 0, zone).toInstant()
        val evening = (tokens("every monday", eveningNow).single().value as QuickAddTokenValue.RecurrenceValue).due
        assertEquals(LocalDate.of(2026, 8, 10), morning.instant.atZone(zone).toLocalDate())
        assertEquals(LocalDate.of(2026, 8, 17), evening.instant.atZone(zone).toLocalDate())
        assertEquals(LocalTime.of(17, 0), morning.instant.atZone(zone).toLocalTime())
    }

    @Test
    fun recurrenceUnitsAndIntervalsResolveExactly() {
        mapOf(
            "every day" to RecurrenceFrequency.DAILY,
            "every week" to RecurrenceFrequency.WEEKLY,
            "every month" to RecurrenceFrequency.MONTHLY,
            "every year" to RecurrenceFrequency.YEARLY,
        ).forEach { (text, expected) ->
            assertEquals(expected, recurrence(text).frequency)
            assertEquals(1, recurrence(text).interval)
        }
        mapOf(
            "every 2 days" to RecurrenceFrequency.DAILY,
            "every 2 weeks" to RecurrenceFrequency.WEEKLY,
            "every 2 months" to RecurrenceFrequency.MONTHLY,
            "every 2 years" to RecurrenceFrequency.YEARLY,
        ).forEach { (text, expected) ->
            assertEquals(expected, recurrence(text).frequency)
            assertEquals(2, recurrence(text).interval)
        }
        mapOf(
            "every monday" to DayOfWeek.MONDAY,
            "every mon" to DayOfWeek.MONDAY,
            "every tuesday" to DayOfWeek.TUESDAY,
            "every tue" to DayOfWeek.TUESDAY,
            "every wednesday" to DayOfWeek.WEDNESDAY,
            "every wed" to DayOfWeek.WEDNESDAY,
            "every thursday" to DayOfWeek.THURSDAY,
            "every thu" to DayOfWeek.THURSDAY,
            "every friday" to DayOfWeek.FRIDAY,
            "every fri" to DayOfWeek.FRIDAY,
            "every saturday" to DayOfWeek.SATURDAY,
            "every sat" to DayOfWeek.SATURDAY,
            "every sunday" to DayOfWeek.SUNDAY,
            "every sun" to DayOfWeek.SUNDAY,
        ).forEach { (text, expected) ->
            assertEquals(setOf(expected), recurrence(text).weekdays)
            assertEquals(RecurrenceFrequency.WEEKLY, recurrence(text).frequency)
            assertEquals(1, recurrence(text).interval)
        }
        assertEquals(999, recurrence("every 999 days").interval)
        assertTrue(tokens("every 1000 days").isEmpty())
    }

    @Test
    fun grammarUsesRootLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(Priority.HIGH, (tokens("!HIGH").single().value as QuickAddTokenValue.PriorityValue).priority)
            assertEquals(
                setOf(DayOfWeek.FRIDAY),
                recurrence("EVERY FRIDAY").weekdays,
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun stripRemovesOnlyTheClaimedHalfOpenSpan() {
        val text = "  Plan   !high   tomorrow  "
        assertEquals("Plan tomorrow", stripQuickAddToken(text, tokens(text).first()))
    }

    private fun recurrence(text: String) =
        (tokens(text).single().value as QuickAddTokenValue.RecurrenceValue).rule
}
