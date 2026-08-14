package app.opentasks.core.model

import java.time.LocalDate
import java.time.YearMonth

data class ScheduleMonthProjection(
    val month: YearMonth,
    val days: List<ScheduleMonthDay>,
)

data class ScheduleMonthDay(
    val date: LocalDate,
    val inSelectedMonth: Boolean,
    val tasks: List<Task>,
    val totalCount: Int,
    val completedCount: Int,
    val overdueCount: Int,
    val densityDotCount: Int,
    val hasDensityOverflow: Boolean,
)
