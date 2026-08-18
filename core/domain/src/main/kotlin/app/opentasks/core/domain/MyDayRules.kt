package app.opentasks.core.domain

import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import java.time.LocalDate
import java.time.ZoneId

const val MAX_MY_DAY_RANK_LENGTH = 200

/**
 * How many tasks My Day holds. Shared by both vault repository engines and by
 * the automation engine's ON_ENTER_ADD_TO_MY_DAY verb, so the bound cannot
 * drift between the handler that rejects at it and the rule that skips below
 * it.
 */
const val MAX_MY_DAY_ENTRIES = 200

private const val RANK_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

fun myDayRankForIndex(index: Int): String =
    "r" + index.toString(36).padStart(6, '0')

fun myDayRankBetween(previous: String?, next: String?): String? {
    val candidate: String? = when {
        next == null -> (previous ?: "") + "m"
        previous == null -> midpointBelow(next)
        else -> midpointBetween(previous, next)
    }
    // The final guard makes correctness unconditional: any candidate
    // that is not strictly between its neighbours (or is over the
    // bound) becomes null, and the caller re-ranks the whole list.
    return candidate?.takeIf {
        it.length <= MAX_MY_DAY_RANK_LENGTH &&
            (previous == null || it > previous) &&
            (next == null || it < next)
    }
}

private fun midpointBelow(next: String): String? {
    // Any string strictly below `next`: step its first digit down and
    // re-open the space with a trailing "m". A floor first digit (or a
    // character outside the alphabet, possible in recovered foreign
    // ranks) yields null and the caller re-ranks.
    val first = RANK_ALPHABET.indexOf(next.first())
    return if (first > 0) "${RANK_ALPHABET[first - 1]}m" else null
}

private fun midpointBetween(previous: String, next: String): String? {
    val prefix = previous.commonPrefixWith(next)
    val lowDigit = previous.getOrNull(prefix.length)
        ?.let(RANK_ALPHABET::indexOf) ?: -1
    val highDigit = next.getOrNull(prefix.length)
        ?.let(RANK_ALPHABET::indexOf) ?: return null
    return if (highDigit - lowDigit > 1) {
        prefix + RANK_ALPHABET[(lowDigit + highDigit) / 2]
    } else {
        // Adjacent digits: extend the lower bound instead. The outer
        // guard rejects the candidate when it does not fit below next.
        previous + "m"
    }
}

fun myDaySuggestions(
    tasks: List<Task>,
    memberIds: Set<TaskId>,
    today: LocalDate,
    zoneId: ZoneId,
): List<Task> {
    val open = tasks.filter {
        it.deletedAt == null && !it.isCompleted && it.id !in memberIds
    }
    fun localDate(moment: app.opentasks.core.model.ZonedMoment): LocalDate =
        moment.instant.atZone(moment.zone()).toLocalDate()
    val overdue = open.filter { task ->
        task.due?.let { localDate(it) < today } == true
    }
    val todays = open.filter { task ->
        task !in overdue &&
            (task.due?.let { localDate(it) == today } == true ||
                task.start?.let { localDate(it) == today } == true)
    }
    val byDueThenTitle = compareBy<Task>(
        { it.due?.instant ?: it.start?.instant },
        { it.title.lowercase() },
        { it.id.value },
    )
    return (overdue.sortedWith(byDueThenTitle) + todays.sortedWith(byDueThenTitle))
        .take(10)
}
