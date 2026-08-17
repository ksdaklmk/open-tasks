package app.opentasks.core.data.backup

import app.opentasks.core.data.InMemoryVaultRepository
import app.opentasks.core.domain.BackupMutationKind
import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RejectionReason
import app.opentasks.core.model.AutomationRule
import app.opentasks.core.model.AutomationRuleId
import app.opentasks.core.model.AutomationRuleType
import app.opentasks.core.model.OpenTasksFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuleFamilyTest {

    private val journal = InMemoryBackupJournal()
    private val repository = InMemoryVaultRepository(backupJournal = journal)

    @Test
    fun automationRuleMutationsJournalThroughTheBackupFamily() = runBlocking {
        withTimeout(5_000) {
            val snapshot = repository.currentWorkspace()
            val rule = AutomationRule(
                id = AutomationRuleId.new(),
                workspaceId = OpenTasksFixtures.workspaceId,
                type = AutomationRuleType.ON_ENTER_ADD_TAG,
                enabled = true,
                statusId = snapshot.workflowStatuses.first().id,
                tagId = snapshot.tags.first().id,
            )

            val created = repository.execute(DomainCommand.CreateAutomationRule(rule))
            assertTrue(created is CommandResult.Success)
            val createUpserts = journal.entries.filter {
                it.objectType == "AUTOMATION_RULE" && it.mutationKind == BackupMutationKind.UPSERT
            }
            assertEquals(listOf(rule.id.value), createUpserts.map { it.objectId })

            // The update flips only `enabled`; AutomationRule carries no
            // revision field, so a second upsert for the same identity
            // proves the journal diffs on full record content, not on a
            // revision counter.
            val disabled = rule.copy(enabled = false)
            val updated = repository.execute(DomainCommand.UpdateAutomationRule(disabled))
            assertTrue(updated is CommandResult.Success)
            val updateUpserts = journal.entries.filter {
                it.objectType == "AUTOMATION_RULE" && it.mutationKind == BackupMutationKind.UPSERT
            }
            assertEquals(
                listOf(rule.id.value, rule.id.value),
                updateUpserts.map { it.objectId },
            )
            assertTrue(updateUpserts[1].payload.contentEquals(createUpserts[0].payload).not())

            val deleted = repository.execute(DomainCommand.DeleteAutomationRule(rule.id))
            assertTrue(deleted is CommandResult.Success)
            val deletes = journal.entries.filter {
                it.objectType == "AUTOMATION_RULE" && it.mutationKind == BackupMutationKind.DELETE
            }
            assertEquals(listOf(rule.id.value), deletes.map { it.objectId })

            // A rejected create journals nothing.
            val entriesBeforeRejection = journal.entries.size
            val rejected = repository.execute(
                DomainCommand.CreateAutomationRule(
                    rule.copy(id = AutomationRuleId.new(), tagId = null),
                ),
            )
            assertTrue(rejected is CommandResult.Rejected)
            assertEquals(
                RejectionReason.AUTOMATION_RULE_INVALID,
                (rejected as CommandResult.Rejected).reason,
            )
            assertEquals(entriesBeforeRejection, journal.entries.size)
        }
    }
}
