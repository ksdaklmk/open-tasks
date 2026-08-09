package app.opentasks.core.domain

import app.opentasks.core.model.ZonedMoment
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

data class NaturalDateMatch(
    val startIndex: Int,
    val endIndex: Int,
    val due: ZonedMoment,
)

/**
 * Parses a Quick Add title for a trailing natural-language date/time
 * suggestion. Returns the last (rightmost) valid match in [text], or null
 * when nothing recognisable is present. Pure `java.time` arithmetic; no
 * library dependency.
 */
fun parseNaturalDate(text: String, now: Instant, zone: ZoneId): NaturalDateMatch? {
    var best: NaturalDateMatch? = null
    for (matchResult in NATURAL_DATE_REGEX.findAll(text)) {
        val due = resolve(matchResult, now, zone) ?: continue
        best = NaturalDateMatch(
            startIndex = matchResult.range.first,
            endIndex = matchResult.range.last + 1,
            due = due,
        )
    }
    return best
}

private val WEEKDAY_ALIASES: Map<String, DayOfWeek> = mapOf(
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

private val WEEKDAY_PATTERN =
    WEEKDAY_ALIASES.keys.sortedByDescending { it.length }.joinToString("|")
private val TIME_PATTERN = """\d{1,2}:\d{2}|\d{1,2}(?:am|pm)"""
private val DATE_PATTERN =
    """today|tomorrow|next\s+(?:$WEEKDAY_PATTERN)|(?:$WEEKDAY_PATTERN)|in\s+\d+\s+(?:days|weeks)"""
private const val BOUNDARY_CLASS = """[\p{L}\p{Nd}_-]"""

private val NATURAL_DATE_REGEX = Regex(
    """(?<!$BOUNDARY_CLASS)(?:(?<date>$DATE_PATTERN)(?:\s+(?<time>$TIME_PATTERN))?""" +
        """|(?:at\s+)?(?<time2>$TIME_PATTERN))(?!$BOUNDARY_CLASS)""",
    RegexOption.IGNORE_CASE,
)

private val NEXT_WEEKDAY_REGEX = Regex("""next\s+(.+)""")
private val RELATIVE_OFFSET_REGEX = Regex("""in\s+(\d+)\s+(days|weeks)""")
private val TIME_AMPM_REGEX = Regex("""(\d{1,2})(am|pm)""")
private val TIME_HHMM_REGEX = Regex("""(\d{1,2}):(\d{2})""")

private val DEFAULT_DUE_TIME: LocalTime = LocalTime.of(17, 0)

private operator fun MatchResult.get(name: String): String? =
    (groups as? MatchNamedGroupCollection)?.get(name)?.value

private fun resolve(matchResult: MatchResult, now: Instant, zone: ZoneId): ZonedMoment? = try {
    val nowZoned = ZonedDateTime.ofInstant(now, zone)
    val dateToken = matchResult["date"]?.lowercase(Locale.ROOT)
    val timeToken = (matchResult["time"] ?: matchResult["time2"])?.lowercase(Locale.ROOT)

    when {
        dateToken != null -> {
            val date = resolveDate(dateToken, nowZoned.toLocalDate()) ?: return null
            val time = if (timeToken != null) {
                parseTime(timeToken) ?: return null
            } else {
                DEFAULT_DUE_TIME
            }
            ZonedMoment(date.atTime(time).atZone(zone).toInstant(), zone.id)
        }
        timeToken != null -> {
            val time = parseTime(timeToken) ?: return null
            var candidate = nowZoned.toLocalDate().atTime(time).atZone(zone)
            if (!candidate.toInstant().isAfter(now)) {
                candidate = candidate.plusDays(1)
            }
            ZonedMoment(candidate.toInstant(), zone.id)
        }
        else -> null
    }
} catch (invalid: DateTimeException) {
    null
} catch (invalid: ArithmeticException) {
    null
}

private fun resolveDate(token: String, today: LocalDate): LocalDate? {
    if (token == "today") return today
    if (token == "tomorrow") return today.plusDays(1)
    NEXT_WEEKDAY_REGEX.matchEntire(token)?.let { match ->
        val target = WEEKDAY_ALIASES[match.groupValues[1]] ?: return null
        val offset = soonestFutureOffset(target, today.dayOfWeek)
        return today.plusDays(offset + 7)
    }
    WEEKDAY_ALIASES[token]?.let { target ->
        val offset = soonestFutureOffset(target, today.dayOfWeek)
        return today.plusDays(offset)
    }
    RELATIVE_OFFSET_REGEX.matchEntire(token)?.let { match ->
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        return when (match.groupValues[2]) {
            "days" -> today.plusDays(amount)
            "weeks" -> today.plusWeeks(amount)
            else -> null
        }
    }
    return null
}

private fun soonestFutureOffset(target: DayOfWeek, from: DayOfWeek): Long {
    val diff = (target.value - from.value + 7) % 7
    return if (diff == 0) 7L else diff.toLong()
}

private fun parseTime(text: String): LocalTime? {
    TIME_AMPM_REGEX.matchEntire(text)?.let { match ->
        val hour = match.groupValues[1].toLongOrNull()?.toInt() ?: return null
        if (hour !in 1..12) return null
        val isPm = match.groupValues[2] == "pm"
        val adjustedHour = when {
            !isPm && hour == 12 -> 0
            isPm && hour != 12 -> hour + 12
            else -> hour
        }
        return LocalTime.of(adjustedHour, 0)
    }
    TIME_HHMM_REGEX.matchEntire(text)?.let { match ->
        val hour = match.groupValues[1].toLongOrNull()?.toInt() ?: return null
        val minute = match.groupValues[2].toLongOrNull()?.toInt() ?: return null
        return LocalTime.of(hour, minute)
    }
    return null
}
