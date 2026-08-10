package app.opentasks.core.domain

import app.opentasks.core.model.Priority
import app.opentasks.core.model.Project
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.RecurrenceFrequency
import app.opentasks.core.model.RecurrenceRule
import app.opentasks.core.model.Tag
import app.opentasks.core.model.TagId
import app.opentasks.core.model.ZonedMoment
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class QuickAddTokenKind {
    PROJECT, TAG, PRIORITY, DATE, RECURRENCE, ESTIMATE,
}

sealed interface QuickAddTokenValue {
    data class ProjectValue(
        val projectId: ProjectId,
        val projectName: String,
    ) : QuickAddTokenValue

    data class TagValue(
        val name: String,
        val existingTagId: TagId?,
    ) : QuickAddTokenValue

    data class PriorityValue(val priority: Priority) : QuickAddTokenValue
    data class DueValue(val due: ZonedMoment) : QuickAddTokenValue
    data class RecurrenceValue(
        val rule: RecurrenceRule,
        val due: ZonedMoment,
    ) : QuickAddTokenValue
    data class EstimateValue(val duration: Duration) : QuickAddTokenValue
}

data class QuickAddTokenMatch(
    val startIndex: Int,
    val endIndex: Int,
    val value: QuickAddTokenValue,
) {
    val kind: QuickAddTokenKind
        get() = when (value) {
            is QuickAddTokenValue.ProjectValue -> QuickAddTokenKind.PROJECT
            is QuickAddTokenValue.TagValue -> QuickAddTokenKind.TAG
            is QuickAddTokenValue.PriorityValue -> QuickAddTokenKind.PRIORITY
            is QuickAddTokenValue.DueValue -> QuickAddTokenKind.DATE
            is QuickAddTokenValue.RecurrenceValue -> QuickAddTokenKind.RECURRENCE
            is QuickAddTokenValue.EstimateValue -> QuickAddTokenKind.ESTIMATE
        }
}

fun parseQuickAdd(
    text: String,
    now: Instant,
    zone: ZoneId,
    projects: List<Project>,
    tags: List<Tag>,
): List<QuickAddTokenMatch> {
    val sigils = SIGIL.findAll(text).mapNotNull { match ->
        resolveSigil(match.groupValues[1], match.groupValues[2], projects, tags)?.let { value ->
            QuickAddTokenMatch(match.range.first, match.range.last + 1, value)
        }
    }.toList()
    val sigilSpans = sigils.map { it.startIndex until it.endIndex }
    val recurrences = RECURRENCE.findAll(text).mapNotNull { match ->
        if (claimed(match.range.first, match.range.last + 1, sigilSpans)) return@mapNotNull null
        resolveRecurrence(match)?.let { candidate ->
            candidate.copy(startIndex = match.range.first, endIndex = match.range.last + 1)
        }
    }.toList()
    val recurrenceSpans = recurrences.map { it.startIndex until it.endIndex }
    val dates = parseNaturalDates(text, now, zone).filterNot { match ->
        claimed(match.startIndex, match.endIndex, sigilSpans) ||
            claimed(match.startIndex, match.endIndex, recurrenceSpans)
    }
    val explicitDue = dates.lastOrNull()?.due
    val recurrenceTokens = recurrences.mapNotNull { recurrence ->
        val due = explicitDue ?: try {
            implicitRecurrenceDue(recurrence.weekday, now, zone)
        } catch (_: IllegalArgumentException) {
            return@mapNotNull null
        }
        QuickAddTokenMatch(
            recurrence.startIndex,
            recurrence.endIndex,
            QuickAddTokenValue.RecurrenceValue(recurrence.rule, due),
        )
    }
    return (sigils + recurrenceTokens + dates.map { date ->
        QuickAddTokenMatch(date.startIndex, date.endIndex, QuickAddTokenValue.DueValue(date.due))
    }).sortedWith(compareBy(QuickAddTokenMatch::startIndex).thenBy { it.endIndex })
}

fun stripQuickAddToken(text: String, match: QuickAddTokenMatch): String {
    require(match.startIndex in 0..text.length)
    require(match.endIndex in match.startIndex..text.length)
    return (text.substring(0, match.startIndex) + text.substring(match.endIndex))
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private data class RecurrenceCandidate(
    val startIndex: Int = 0,
    val endIndex: Int = 0,
    val rule: RecurrenceRule,
    val weekday: DayOfWeek?,
)

private val SIGIL = Regex("""(?<![\p{L}\p{Nd}])([#@!~])(\S+)""")
private val RECURRENCE = Regex(
    """(?i)(?<![\p{L}\p{Nd}])every\s+(?:(\d+)\s+)?""" +
        """(day|days|week|weeks|month|months|year|years|""" +
        """monday|mon|tuesday|tue|wednesday|wed|thursday|thu|""" +
        """friday|fri|saturday|sat|sunday|sun)(?![\p{L}\p{Nd}])""",
)
private val ESTIMATE = Regex("""(?:(\d+)h)?(?:(\d+)m)?""")
private val PRIORITIES = mapOf(
    "low" to Priority.LOW,
    "med" to Priority.MEDIUM,
    "medium" to Priority.MEDIUM,
    "high" to Priority.HIGH,
    "urgent" to Priority.URGENT,
    "1" to Priority.URGENT,
    "2" to Priority.HIGH,
    "3" to Priority.MEDIUM,
    "4" to Priority.LOW,
)
private val WEEKDAYS = mapOf(
    "monday" to DayOfWeek.MONDAY,
    "mon" to DayOfWeek.MONDAY,
    "tuesday" to DayOfWeek.TUESDAY,
    "tue" to DayOfWeek.TUESDAY,
    "wednesday" to DayOfWeek.WEDNESDAY,
    "wed" to DayOfWeek.WEDNESDAY,
    "thursday" to DayOfWeek.THURSDAY,
    "thu" to DayOfWeek.THURSDAY,
    "friday" to DayOfWeek.FRIDAY,
    "fri" to DayOfWeek.FRIDAY,
    "saturday" to DayOfWeek.SATURDAY,
    "sat" to DayOfWeek.SATURDAY,
    "sunday" to DayOfWeek.SUNDAY,
    "sun" to DayOfWeek.SUNDAY,
)

private fun IntRange.overlaps(start: Int, end: Int): Boolean =
    first < end && start <= last

private fun claimed(start: Int, end: Int, spans: List<IntRange>): Boolean =
    spans.any { it.overlaps(start, end) }

private fun resolveProject(needle: String, projects: List<Project>): Project? {
    val normalisedNeedle = SearchNormalizer.normalize(needle)
    return projects.asSequence()
        .filter { it.archivedAt == null }
        .mapNotNull { project ->
            val name = SearchNormalizer.normalize(project.name)
            val tier = when {
                name.startsWith(normalisedNeedle) -> 0
                normalisedNeedle in name -> 1
                else -> return@mapNotNull null
            }
            Triple(project, tier, name)
        }
        .sortedWith(
            compareBy<Triple<Project, Int, String>> { it.second }
                .thenBy { it.first.name.length }
                .thenBy { it.third }
                .thenBy { it.first.id.value },
        )
        .firstOrNull()?.first
}

private fun resolveTag(needle: String, tags: List<Tag>): QuickAddTokenValue.TagValue {
    val existing = tags.firstOrNull {
        SearchNormalizer.normalize(it.name) == SearchNormalizer.normalize(needle)
    }
    return QuickAddTokenValue.TagValue(existing?.name ?: needle, existing?.id)
}

private fun resolveSigil(
    sigil: String,
    needle: String,
    projects: List<Project>,
    tags: List<Tag>,
): QuickAddTokenValue? = when (sigil) {
    "#" -> resolveProject(needle, projects)?.let {
        QuickAddTokenValue.ProjectValue(it.id, it.name)
    }
    "@" -> resolveTag(needle, tags)
    "!" -> PRIORITIES[needle.lowercase(Locale.ROOT)]?.let(QuickAddTokenValue::PriorityValue)
    "~" -> resolveEstimate(needle)?.let(QuickAddTokenValue::EstimateValue)
    else -> null
}

private fun resolveEstimate(needle: String): Duration? = try {
    val total = needle.toLongOrNull() ?: run {
        val match = ESTIMATE.matchEntire(needle) ?: return null
        val hours = match.groupValues[1].takeIf(String::isNotEmpty)?.toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull() ?: 0L
        Math.addExact(Math.multiplyExact(hours, 60), minutes)
    }
    if (total in 1..1_440) Duration.ofMinutes(total) else null
} catch (_: ArithmeticException) {
    null
}

private fun resolveRecurrence(match: MatchResult): RecurrenceCandidate? = try {
    val unit = match.groupValues[2].lowercase(Locale.ROOT)
    WEEKDAYS[unit]?.let { weekday ->
        return RecurrenceCandidate(
            rule = RecurrenceRule(RecurrenceFrequency.WEEKLY, weekdays = setOf(weekday)),
            weekday = weekday,
        )
    }
    val interval = match.groupValues[1].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 1
    if (interval !in 1..999) return null
    val frequency = when (unit) {
        "day", "days" -> RecurrenceFrequency.DAILY
        "week", "weeks" -> RecurrenceFrequency.WEEKLY
        "month", "months" -> RecurrenceFrequency.MONTHLY
        "year", "years" -> RecurrenceFrequency.YEARLY
        else -> return null
    }
    RecurrenceCandidate(rule = RecurrenceRule(frequency, interval), weekday = null)
} catch (_: IllegalArgumentException) {
    null
}

private fun implicitRecurrenceDue(
    weekday: DayOfWeek?,
    now: Instant,
    zone: ZoneId,
): ZonedMoment = try {
    val current = now.atZone(zone)
    var date = if (weekday == null) {
        current.toLocalDate()
    } else {
        current.toLocalDate().with(TemporalAdjusters.nextOrSame(weekday))
    }
    var candidate = date.atTime(17, 0).atZone(zone)
    if (!candidate.toInstant().isAfter(now)) {
        date = if (weekday == null) date.plusDays(1) else date.plusWeeks(1)
        candidate = date.atTime(17, 0).atZone(zone)
    }
    ZonedMoment(candidate.toInstant(), zone.id)
} catch (failure: DateTimeException) {
    throw IllegalArgumentException("Recurrence anchor is out of range", failure)
}
