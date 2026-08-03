package app.opentasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The staging root holds decrypted attachment bytes for the length of one
 * FileProvider handoff. A process that dies mid-handoff leaves that plaintext
 * behind, so the sweep the ViewModel runs at construction has to remove the
 * whole root — not just the entry a later open happens to restage.
 */
class AttachmentStagingSweepTest {

    @Test
    fun sweepRemovesEveryStagedPlaintextCopyAndTheRootItself() {
        val root = Files.createTempDirectory("share-attachments").toFile()
        val abandoned = stage(root, "attachment-1", "receipt.pdf")
        val nested = stage(root, "attachment-2/inner", "photo.jpg")

        sweepStagedPlaintext(root)

        assertFalse(abandoned.exists())
        assertFalse(nested.exists())
        assertFalse(root.exists())
    }

    @Test
    fun sweepOfAnAbsentRootIsASilentNoOp() {
        val root = File(Files.createTempDirectory("share-attachments").toFile(), "never-created")

        sweepStagedPlaintext(root)

        assertFalse(root.exists())
    }

    @Test
    fun sweptRootLeavesNoStagedBytesToReport() {
        val root = Files.createTempDirectory("share-attachments").toFile()
        stage(root, "attachment-1", "receipt.pdf")
        assertTrue(stagedPlaintextBytes(root) > 0)

        sweepStagedPlaintext(root)

        assertEquals(0L, stagedPlaintextBytes(root))
    }

    private fun stage(root: File, directory: String, name: String): File {
        val parent = File(root, directory)
        parent.mkdirs()
        return File(parent, name).apply { writeText("decrypted bytes") }
    }
}
