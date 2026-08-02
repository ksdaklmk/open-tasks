package app.opentasks.feature.tasks

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.BlobSetId
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.Revision
import app.opentasks.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TaskAttachmentsSectionInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun eachRowStateIsReadableAsText() {
        composeRule.setContent {
            OpenTasksTheme {
                TaskAttachmentsSection(
                    attachments = ALL_ATTACHMENTS,
                    states = ALL_STATES,
                    onAddFromPhotos = {},
                    onAddFromFiles = {},
                    onOpen = {},
                    onShare = {},
                    onDelete = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("In cloud backup", substring = true).assertExists()
        composeRule.onNodeWithText("Downloading", substring = true).assertExists()
        composeRule.onNodeWithText("Temporarily unavailable", substring = true).assertExists()
        composeRule.onNodeWithText("Removed", substring = true).assertExists()
        composeRule.onNodeWithText("Could not be downloaded", substring = true).assertExists()
    }

    @Test
    fun tombstonedRowOffersNoOpenOrShare() {
        composeRule.setContent {
            OpenTasksTheme {
                TaskAttachmentsSection(
                    attachments = ALL_ATTACHMENTS,
                    states = ALL_STATES,
                    onAddFromPhotos = {},
                    onAddFromFiles = {},
                    onOpen = {},
                    onShare = {},
                    onDelete = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("attachment-open-tombstoned").assertDoesNotExist()
        composeRule.onNodeWithTag("attachment-share-tombstoned").assertDoesNotExist()
        composeRule.onNodeWithTag("attachment-open-remote").assertExists()
    }

    @Test
    fun failedRowRetriesOnlyItsOwnAttachment() {
        val retried = AtomicReference<AttachmentId?>()
        composeRule.setContent {
            OpenTasksTheme {
                TaskAttachmentsSection(
                    attachments = ALL_ATTACHMENTS,
                    states = ALL_STATES,
                    onAddFromPhotos = {},
                    onAddFromFiles = {},
                    onOpen = {},
                    onShare = {},
                    onDelete = {},
                    onRetry = retried::set,
                )
            }
        }

        composeRule.onNodeWithTag("attachment-retry-failed")
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(AttachmentId("failed"), retried.get())
    }

    @Test
    fun pickerActionsDispatchTheirOwnSource() {
        val photos = AtomicInteger()
        val files = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                TaskAttachmentsSection(
                    attachments = emptyList(),
                    states = emptyMap(),
                    onAddFromPhotos = { photos.incrementAndGet() },
                    onAddFromFiles = { files.incrementAndGet() },
                    onOpen = {},
                    onShare = {},
                    onDelete = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("add-attachment-photos")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("add-attachment-files")
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, photos.get())
        assertEquals(1, files.get())
    }

    @Test
    fun setupRequiredExplainsBackupInsteadOfOpeningAPicker() {
        val opened = AtomicInteger()
        val photos = AtomicInteger()
        composeRule.setContent {
            OpenTasksTheme {
                TaskAttachmentsSection(
                    attachments = emptyList(),
                    states = emptyMap(),
                    onAddFromPhotos = { photos.incrementAndGet() },
                    onAddFromFiles = {},
                    onOpen = {},
                    onShare = {},
                    onDelete = {},
                    onRetry = {},
                    setupRequired = true,
                    onOpenBackupSetup = { opened.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithTag("add-attachment-photos").assertDoesNotExist()
        composeRule.onNodeWithTag("add-attachment-files").assertDoesNotExist()
        composeRule.onNodeWithTag("attachment-setup-backup")
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, opened.get())
        assertEquals(0, photos.get())
    }

    private companion object {
        val REVISION = Revision(DeviceId("fixture-device"), 1_722_000_000_000, 0)

        fun attachment(id: String, deletedAt: Instant? = null) = Attachment(
            id = AttachmentId(id),
            taskId = TaskId("task-proposal"),
            displayName = "$id-scope.pdf",
            mimeType = "application/pdf",
            byteCount = 2_048,
            contentHash = "hash-$id",
            blobSetId = BlobSetId("blob-$id"),
            chunkCount = 1,
            deletedAt = deletedAt,
            revision = REVISION,
        )

        val ALL_ATTACHMENTS = listOf(
            attachment("remote"),
            attachment("downloading"),
            attachment("unavailable"),
            attachment("tombstoned", deletedAt = Instant.parse("2026-07-25T08:00:00Z")),
            attachment("failed"),
        )

        val ALL_STATES = mapOf(
            AttachmentId("remote") to AttachmentRowState.REMOTE,
            AttachmentId("downloading") to AttachmentRowState.DOWNLOADING,
            AttachmentId("unavailable") to AttachmentRowState.UNAVAILABLE,
            AttachmentId("tombstoned") to AttachmentRowState.TOMBSTONED,
            AttachmentId("failed") to AttachmentRowState.FAILED,
        )
    }
}
