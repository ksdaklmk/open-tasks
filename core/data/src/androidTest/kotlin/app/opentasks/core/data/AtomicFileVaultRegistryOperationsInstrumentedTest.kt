package app.opentasks.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtomicFileVaultRegistryOperationsInstrumentedTest {
    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var operations: AtomicFileVaultRegistryOperations

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        directory = File(context.filesDir, "vault_registry_operations_test")
        directory.deleteRecursively()
        file = File(directory, "active_slot.json")
        operations = AtomicFileVaultRegistryOperations()
    }

    @After
    fun tearDown() {
        operations.discardWrite(file)
        directory.deleteRecursively()
    }

    @Test
    fun anAbsentRegistryFileReadsAsAbsent() {
        operations.ensureDirectory(directory)

        assertNull(operations.readBytes(file))
    }

    @Test
    fun ensureDirectoryCreatesEveryMissingParent() {
        val nested = File(directory, "nested/deeper")

        operations.ensureDirectory(nested)

        assertTrue(nested.isDirectory)
    }

    @Test
    fun stagedBytesStayInvisibleUntilTheCommitPublishesThem() {
        operations.ensureDirectory(directory)
        operations.stageWrite(file, FIRST)
        operations.commitWrite(file)

        operations.stageWrite(file, SECOND)

        // Reading through the boundary while a write is staged must keep
        // returning the committed bytes and must not destroy the synchronised
        // temporary that AtomicFile.openRead would delete.
        assertArrayEquals(FIRST, operations.readBytes(file))
        assertArrayEquals(FIRST, file.readBytes())
        assertTrue(File(directory, "${file.name}.new").isFile)

        operations.commitWrite(file)

        assertArrayEquals(SECOND, operations.readBytes(file))
        assertArrayEquals(SECOND, file.readBytes())
        assertArrayEquals(SECOND, AtomicFileVaultRegistryOperations().readBytes(file))
        assertFalse(File(directory, "${file.name}.new").exists())
    }

    @Test
    fun aDiscardedWriteLeavesTheCommittedBytesAndRemovesTheTemporary() {
        operations.ensureDirectory(directory)
        operations.stageWrite(file, FIRST)
        operations.commitWrite(file)

        operations.stageWrite(file, SECOND)
        operations.discardWrite(file)

        assertArrayEquals(FIRST, operations.readBytes(file))
        assertFalse(File(directory, "${file.name}.new").exists())
    }

    @Test
    fun aRestagedWriteReplacesTheAbandonedTemporary() {
        operations.ensureDirectory(directory)
        operations.stageWrite(file, FIRST)

        operations.stageWrite(file, SECOND)
        operations.commitWrite(file)

        assertArrayEquals(SECOND, operations.readBytes(file))
    }

    @Test
    fun deleteRemovesTheRegistryFileAndItsTemporary() {
        operations.ensureDirectory(directory)
        operations.stageWrite(file, FIRST)
        operations.commitWrite(file)

        operations.delete(file)

        assertNull(operations.readBytes(file))
        assertFalse(file.exists())
        assertFalse(File(directory, "${file.name}.new").exists())
    }

    @Test
    fun aCommittedMarkerSurvivesAFreshRegistryInstance() {
        val registry = VaultSlotRegistry(directory, operations)
        val slot = VaultSlot.new()

        registry.replace(slot)

        val reopened = VaultSlotRegistry(directory, AtomicFileVaultRegistryOperations())
        assertTrue(reopened.read() == slot)
    }

    private companion object {
        val FIRST = "first".toByteArray(Charsets.UTF_8)
        val SECOND = "second-and-longer".toByteArray(Charsets.UTF_8)
    }
}
