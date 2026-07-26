package app.opentasks.core.model

import java.time.DayOfWeek
import java.time.LocalDate

enum class RecurrenceFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
}

data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val count: Int? = null,
    val endDate: LocalDate? = null,
) {
    init {
        require(interval > 0) { "Recurrence interval must be positive" }
        require(count == null || count > 0) { "Recurrence count must be positive" }
    }
}
