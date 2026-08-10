package app.opentasks.core.domain

import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.ZonedMoment
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

fun classifyDueBucket(due: ZonedMoment?, clock: Clock): DueBucket {
    val dueAt = due?.instant ?: return DueBucket.NO_DATE
    val now = clock.instant()
    if (dueAt.isBefore(now)) return DueBucket.OVERDUE
    val today = LocalDate.now(clock)
    val tomorrow = today.plusDays(1).atStartOfDay(clock.zone).toInstant()
    if (dueAt.isBefore(tomorrow)) return DueBucket.TODAY
    val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        .atStartOfDay(clock.zone)
        .toInstant()
    return if (dueAt.isBefore(nextMonday)) DueBucket.THIS_WEEK else DueBucket.LATER
}
