package app.opentasks.core.domain

private val WIP_LIMIT_RANGE = 1..200

/**
 * Pure range check shared by both vault repository engines'
 * `SetWorkflowStatusWipLimit` command, so the bound stays in exactly one
 * place instead of drifting between engines. `null` clears the limit and
 * is always valid.
 */
fun wipLimitRejection(wipLimit: Int?): CommandResult.Rejected? =
    if (wipLimit != null && wipLimit !in WIP_LIMIT_RANGE) {
        CommandResult.Rejected(
            RejectionReason.WIP_LIMIT_INVALID,
            "Limits run from 1 to 200.",
        )
    } else {
        null
    }
