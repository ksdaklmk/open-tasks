package app.opentasks.core.data.backup

import app.opentasks.core.model.BlobSetId
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentCacheStoreTest {
    @Test
    fun writeAndReadRoundTripTracksUsage() = withCacheRoot { root ->
        val store = AttachmentCacheStore(root) { 20_000 }
        val frame = byteArrayOf(1, 2, 3)

        store.write(BLOB_A, 0, frame)

        assertArrayEquals(frame, store.read(BLOB_A, 0))
        assertEquals(3, store.usageBytes())
    }

    @Test
    fun writeEvictsLeastRecentlyAccessedFrameFirst() = withCacheRoot { root ->
        val store = AttachmentCacheStore(root) { 80 }
        store.write(BLOB_A, 0, byteArrayOf(1, 1))
        store.write(BLOB_B, 0, byteArrayOf(2, 2))
        val files = root.walkTopDown().filter(File::isFile).toList()
        files.forEach { file ->
            file.setLastModified(if (file.readBytes()[0] == 1.toByte()) 10 else 20)
        }
        assertArrayEquals(byteArrayOf(1, 1), store.read(BLOB_A, 0))

        store.write(BLOB_C, 0, byteArrayOf(3, 3))

        assertArrayEquals(byteArrayOf(1, 1), store.read(BLOB_A, 0))
        assertNull(store.read(BLOB_B, 0))
        assertArrayEquals(byteArrayOf(3, 3), store.read(BLOB_C, 0))
        assertEquals(4, store.usageBytes())
    }

    @Test
    fun repeatedSweepIsIdempotent() = withCacheRoot { root ->
        val store = AttachmentCacheStore(root) { 40 }
        store.write(BLOB_A, 0, byteArrayOf(1, 2, 3))

        store.sweep()
        store.sweep()

        assertNull(store.read(BLOB_A, 0))
        assertEquals(0, store.usageBytes())
    }

    private fun withCacheRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("attachment-cache-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        val BLOB_A = BlobSetId("blob-a")
        val BLOB_B = BlobSetId("blob-b")
        val BLOB_C = BlobSetId("blob-c")
    }
}
