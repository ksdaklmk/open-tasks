package app.opentasks.core.domain

import app.opentasks.core.model.AutomationRule
import app.opentasks.core.model.AutomationRuleType
import app.opentasks.core.model.PRIMARY_WORKSPACE_ID
import app.opentasks.core.model.Reminder
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.ZonedMoment
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything one status entry is evaluated against, read post-transition by
 * the calling engine inside its own write transaction.
 *
 * The entered status is [task]'s own `statusId` — the transition has already
 * been applied when a trigger is built — and My Day's occupancy is
 * [myDayMemberIds] rather than a separate count, so the two can never
 * disagree.
 */
data class StatusTransitionTrigger(
    val task: Task,
    /** Preserved verbatim by ON_ENTER_SET_DUE outputs. */
    val reminder: Reminder?,
    val myDayMemberIds: Set<TaskId>,
    val today: LocalDate,
    val zoneId: String,
)

/**
 * The tasks a just-applied [command] actually moved into a new status, in the
 * order the engine applied them, derived from the repository's own undo.
 *
 * Two layers gate this deliberately. The command whitelist is the authority on
 * what counts as a status entry, so a command that merely produces per-task
 * inverses — a project-move remap, an undo replay — never registers even when
 * its undo carries a [DomainCommand.RestoreTaskStatus]. Within the whitelist
 * the undo shape is the evidence: only the real transition paths construct
 * that inverse, and a no-op move returns no undo at all, so a task that did not
 * change column is never reported. [DomainCommand.CompleteTasks] stores its
 * inverses in reverse application order, which is undone here.
 *
 * Pure and engine-free, so both vault repository engines share exactly one
 * copy of the rule.
 */
fun automationTransitionedTaskIds(
    command: DomainCommand,
    result: CommandResult.Success,
): List<TaskId> = when (command) {
    is DomainCommand.ChangeTaskStatus,
    is DomainCommand.CompleteTask,
    ->
        (result.undo as? DomainCommand.RestoreTaskStatus)
            ?.let { listOf(it.taskId) }
            .orEmpty()
    is DomainCommand.CompleteTasks ->
        (result.undo as? DomainCommand.UndoBatch)
            ?.commands
            ?.filterIsInstance<DomainCommand.RestoreTaskStatus>()
            ?.map(DomainCommand.RestoreTaskStatus::taskId)
            ?.asReversed() // stored reversed; recover application order
            .orEmpty()
    else -> emptyList()
}

/**
 * Deterministic: matching rules apply in ascending rule-id order. Idempotent
 * verbs skip silently, and so does any verb whose command the repository
 * would reject, so a rule never turns a user's move into a failure. Outputs
 * never re-enter evaluation because callers apply them via internal dispatch.
 *
 * A rule matches only when it is enabled, its status is the entered one, and
 * its project scope either is absent or agrees with the task's project. The
 * project clause is a no-op for product-producible rules — a status belongs
 * to exactly one project — and exists to fail closed on a crafted or imported
 * rule whose `projectId` and `statusId` disagree.
 */
fun evaluateAutomationRules(
    rules: List<AutomationRule>,
    trigger: StatusTransitionTrigger,
): List<DomainCommand> = rules
    .asSequence()
    .filter {
        it.enabled && it.statusId == trigger.task.statusId &&
            (it.projectId == null || it.projectId == trigger.task.projectId)
    }
    .sortedBy { it.id.value }
    .mapNotNull { rule ->
        // A fourth verb must emit a command whose success undo is a shape both
        // engines' `rejectUndoCommand` already preflights: an output undo that
        // falls through to that function's fail-closed branch would make every
        // composed undo containing it permanently unreplayable.
        when (rule.type) {
            AutomationRuleType.ON_ENTER_ADD_TAG -> rule.tagId
                ?.takeIf { it !in trigger.task.tagIds }
                ?.let { DomainCommand.SetTaskTag(trigger.task.id, it, present = true) }
            AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY ->
                DomainCommand.AddTaskToMyDay(trigger.task.id)
                    .takeIf {
                        trigger.task.id !in trigger.myDayMemberIds &&
                            trigger.myDayMemberIds.size < MAX_MY_DAY_ENTRIES
                    }
            AutomationRuleType.ON_ENTER_SET_DUE -> rule.dueInDays?.let { days ->
                val zone = ZoneId.of(trigger.zoneId)
                DomainCommand.SetTaskSchedule(
                    taskId = trigger.task.id,
                    start = trigger.task.start,
                    due = ZonedMoment(
                        instant = trigger.today.plusDays(days.toLong())
                            .atTime(17, 0).atZone(zone).toInstant(),
                        zoneId = trigger.zoneId,
                    ),
                    reminder = trigger.reminder,
                    // Repository-produced, like an undo replay: the reminder is
                    // carried across unchanged, so a reminder that has already
                    // fired must not reject the rule into a permanent no-op.
                    restorePastReminder = true,
                )
            }
            // Neither type is a status-entry verb, and the config validator
            // forces a null `statusId` on both, so the filter above has
            // already excluded them.
            AutomationRuleType.MY_DAY_AUTO_REMOVE,
            AutomationRuleType.STALE_BADGE,
            -> null
        }
    }
    .toList()

/**
 * Whether the idempotent My Day rollover sweep is switched on. Extracted from
 * the app-side sweeper so the gate itself is unit-testable without an engine.
 */
fun myDaySweepEnabled(rules: List<AutomationRule>): Boolean =
    rules.any { it.enabled && it.type == AutomationRuleType.MY_DAY_AUTO_REMOVE }

/**
 * Pure per-type config check shared by both vault repository engines'
 * automation-rule commands, so the field matrix stays in exactly one place
 * instead of drifting between engines. Must remain in lockstep with
 * `BackupMutationCodec`'s AUTOMATION_RULE branch — that codec is the sole
 * per-type rule-config authority; change one only with the other.
 */
fun automationRuleConfigRejection(rule: AutomationRule): CommandResult.Rejected? {
    fun invalid(detail: String) = CommandResult.Rejected(
        RejectionReason.AUTOMATION_RULE_INVALID,
        "This rule is not valid: $detail.",
    )
    rule.dueInDays?.let { if (it !in 0..365) return invalid("days must be 0–365") }
    rule.thresholdDays?.let { if (it !in 1..365) return invalid("days must be 1–365") }
    val requirement = when (rule.type) {
        AutomationRuleType.ON_ENTER_ADD_TAG ->
            rule.statusId != null && rule.tagId != null &&
                rule.dueInDays == null && rule.thresholdDays == null
        AutomationRuleType.ON_ENTER_ADD_TO_MY_DAY ->
            rule.statusId != null && rule.tagId == null &&
                rule.dueInDays == null && rule.thresholdDays == null
        AutomationRuleType.ON_ENTER_SET_DUE ->
            rule.statusId != null && rule.dueInDays != null &&
                rule.tagId == null && rule.thresholdDays == null
        AutomationRuleType.MY_DAY_AUTO_REMOVE ->
            rule.projectId == null && rule.statusId == null && rule.tagId == null &&
                rule.dueInDays == null && rule.thresholdDays == null
        AutomationRuleType.STALE_BADGE ->
            rule.thresholdDays != null && rule.statusId == null &&
                rule.tagId == null && rule.dueInDays == null
    }
    return if (requirement) null else invalid("its settings do not match its type")
}

/** Every automation rule must belong to the primary workspace. */
fun automationRuleWorkspaceRejection(rule: AutomationRule): CommandResult.Rejected? =
    if (rule.workspaceId != PRIMARY_WORKSPACE_ID) {
        CommandResult.Rejected(
            RejectionReason.AUTOMATION_RULE_INVALID,
            "This rule is not valid: it belongs to a different workspace.",
        )
    } else {
        null
    }

/** Shared rejection for a rule whose `statusId`/`tagId`/`projectId` no longer resolves. */
fun automationRuleNotFound(): CommandResult.Rejected = CommandResult.Rejected(
    RejectionReason.NOT_FOUND,
    "That rule refers to something that no longer exists.",
)
