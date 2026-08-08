package app.opentasks.core.model

import java.nio.charset.StandardCharsets
import java.util.UUID

@JvmInline
value class VaultId(val value: String) {
    companion object {
        fun new(): VaultId = VaultId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class WorkspaceId(val value: String) {
    companion object {
        fun new(): WorkspaceId = WorkspaceId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class MemberId(val value: String) {
    companion object {
        fun new(): MemberId = MemberId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class ProjectId(val value: String) {
    companion object {
        fun new(): ProjectId = ProjectId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class WorkflowStatusId(val value: String) {
    companion object {
        fun new(): WorkflowStatusId = WorkflowStatusId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class MilestoneId(val value: String) {
    companion object {
        fun new(): MilestoneId = MilestoneId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class TaskId(val value: String) {
    companion object {
        fun new(): TaskId = TaskId(UUID.randomUUID().toString())

        fun deterministicOccurrence(seriesId: TaskId, occurrenceKey: String): TaskId {
            val source = "${seriesId.value}:$occurrenceKey".toByteArray(StandardCharsets.UTF_8)
            return TaskId(UUID.nameUUIDFromBytes(source).toString())
        }
    }
}

@JvmInline
value class NoteId(val value: String) {
    companion object {
        fun new(): NoteId = NoteId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class TagId(val value: String)

@JvmInline
value class AttachmentId(val value: String) {
    companion object {
        fun new(): AttachmentId = AttachmentId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class BlobSetId(val value: String) {
    companion object {
        fun new(): BlobSetId = BlobSetId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class TimeEntryId(val value: String) {
    companion object {
        fun new(): TimeEntryId = TimeEntryId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class TemplateId(val value: String) {
    companion object {
        fun new(): TemplateId = TemplateId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class SavedViewId(val value: String) {
    companion object {
        fun new(): SavedViewId = SavedViewId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class DeviceId(val value: String)

@JvmInline
value class CloudObjectId(val value: String)
