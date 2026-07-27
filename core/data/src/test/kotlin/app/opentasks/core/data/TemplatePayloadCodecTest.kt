package app.opentasks.core.data

import app.opentasks.core.data.db.TemplateEntity
import app.opentasks.core.domain.ProjectTemplatePlanner
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Template
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.TemplateTask
import app.opentasks.core.model.TemplateWorkflowStatus
import app.opentasks.core.model.WorkspaceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TemplatePayloadCodecTest {
    @Test
    fun roundTripPreservesVersionedTemplatePayload() {
        val template = template()
        val encoded = TemplatePayloadCodec.encode(template)
        val decoded = TemplatePayloadCodec.decode(template.toEntity(encoded))

        assertEquals(template, decoded)
    }

    @Test
    fun decodeRejectsPayloadBeforeOversizedAllocation() {
        val entity = template().toEntity(
            ByteArray(TemplatePayloadCodec.MAX_PAYLOAD_BYTES + 1),
        )

        assertThrows(IllegalArgumentException::class.java) {
            TemplatePayloadCodec.decode(entity)
        }
    }

    @Test
    fun encodeRejectsUnboundedTaskCollections() {
        val template = template().copy(
            tasks = List(ProjectTemplatePlanner.MAX_TEMPLATE_TASKS + 1) {
                TemplateTask(
                    key = "task-$it",
                    parentKey = null,
                    statusKey = SemanticStatus.BACKLOG.name,
                    title = "Task",
                    description = "",
                    priority = Priority.NONE,
                    start = null,
                    due = null,
                    recurrence = null,
                    estimateSeconds = null,
                    milestoneKey = null,
                    tagNames = emptySet(),
                    checklist = emptyList(),
                    dependencyKeys = emptySet(),
                )
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            TemplatePayloadCodec.encode(template)
        }
    }

    @Test
    fun encodeRejectsCyclicTaskRelationships() {
        val template = template().copy(
            tasks = listOf(
                task("first", setOf("second")),
                task("second", setOf("first")),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            TemplatePayloadCodec.encode(template)
        }
    }

    @Test
    fun decodeRejectsMetadataThatDoesNotMatchTheAuthenticatedPayload() {
        val template = template()
        val entity = template.toEntity(TemplatePayloadCodec.encode(template))
            .copy(name = "Substituted name")

        assertThrows(IllegalArgumentException::class.java) {
            TemplatePayloadCodec.decode(entity)
        }
    }

    private fun template(): Template = Template(
        id = TemplateId("template"),
        workspaceId = WorkspaceId("workspace"),
        name = "Delivery",
        projectName = "Client delivery",
        projectSummary = "A repeatable project",
        projectDueOffsetDays = 14,
        workflowStatuses = SemanticStatus.entries.mapIndexed { index, semantic ->
            TemplateWorkflowStatus(
                key = semantic.name,
                name = semantic.name,
                semanticStatus = semantic,
                rank = "a$index",
            )
        },
        milestones = emptyList(),
        tasks = emptyList(),
        revision = Revision(DeviceId("device"), 42, 1),
    )

    private fun task(key: String, dependencies: Set<String>): TemplateTask =
        TemplateTask(
            key = key,
            parentKey = null,
            statusKey = SemanticStatus.BACKLOG.name,
            title = "Task $key",
            description = "",
            priority = Priority.NONE,
            start = null,
            due = null,
            recurrence = null,
            estimateSeconds = null,
            milestoneKey = null,
            tagNames = emptySet(),
            checklist = emptyList(),
            dependencyKeys = dependencies,
        )

    private fun Template.toEntity(payload: ByteArray): TemplateEntity = TemplateEntity(
        id = id.value,
        workspaceId = workspaceId.value,
        name = name,
        encryptedPayload = payload,
        revisionWallMillis = revision.wallTimeMillis,
        revisionLogical = revision.logicalCounter,
        revisionDeviceId = revision.deviceId.value,
    )
}
