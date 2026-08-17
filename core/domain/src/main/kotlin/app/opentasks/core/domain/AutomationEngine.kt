package app.opentasks.core.domain

import app.opentasks.core.model.AutomationRule
import app.opentasks.core.model.AutomationRuleType
import app.opentasks.core.model.OpenTasksFixtures

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

/** Every automation rule must belong to the single fixture workspace. */
fun automationRuleWorkspaceRejection(rule: AutomationRule): CommandResult.Rejected? =
    if (rule.workspaceId != OpenTasksFixtures.workspaceId) {
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
