package app.opentasks.core.data.backup

import app.opentasks.core.model.BlobSetId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun blobDirectorySymlinkCannotRedirectWriteOutsideRoot() = withSymlinkFixture { root, outside ->
        val store = AttachmentCacheStore(root) { 20_000 }
        val link = root.resolve(blobHash(BLOB_A))
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertThrows(IOException::class.java) {
            store.write(BLOB_A, 0, byteArrayOf(1, 2, 3))
        }

        assertEquals(listOf("sentinel"), outside.list()?.sorted())
    }

    @Test
    fun evictionDeletesBlobSymlinkWithoutFollowingIt() =
        withSymlinkFixture { root, outside ->
            val store = AttachmentCacheStore(root) { 20_000 }
            val blobLink = root.resolve(blobHash(BLOB_A)).toPath()
            Files.createSymbolicLink(blobLink, outside.toPath())

            store.evict(BLOB_A)

            assertTrue(outside.resolve("sentinel").isFile)
            assertFalse(Files.exists(blobLink, LinkOption.NOFOLLOW_LINKS))
        }

    @Test
    fun sweepDeletesUnknownSymlinkWithoutFollowingIt() =
        withSymlinkFixture { root, outside ->
            val store = AttachmentCacheStore(root) { 20_000 }

            val sweepLink = root.resolve("sweep-link").toPath()
            Files.createSymbolicLink(sweepLink, outside.toPath())
            store.sweep()

            assertTrue(outside.resolve("sentinel").isFile)
            assertFalse(Files.exists(sweepLink, LinkOption.NOFOLLOW_LINKS))
        }

    @Test
    fun onlyCanonicalFramePathsCountAndSurviveSweep() = withCacheRoot { root ->
        val store = AttachmentCacheStore(root) { 20_000 }
        store.write(BLOB_A, 0, byteArrayOf(1, 2, 3))
        val blobDirectory = root.resolve(blobHash(BLOB_A))
        root.resolve("stray.frame").writeBytes(ByteArray(4))
        root.resolve("not-a-blob-hash").mkdirs()
        root.resolve("not-a-blob-hash/0.frame").writeBytes(ByteArray(5))
        blobDirectory.resolve("-1.frame").writeBytes(ByteArray(6))

        assertEquals(3, store.usageBytes())
        store.sweep()

        assertEquals(3, store.usageBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), store.read(BLOB_A, 0))
        assertFalse(root.resolve("stray.frame").exists())
        assertFalse(root.resolve("not-a-blob-hash").exists())
        assertFalse(blobDirectory.resolve("-1.frame").exists())
    }

    @Test
    fun nonEmptyDirectoryAtFramePathIsCacheMiss() = withCacheRoot { root ->
        val store = AttachmentCacheStore(root) { 20_000 }
        val invalidFrame = root.resolve(blobHash(BLOB_A)).resolve("0.frame")
        invalidFrame.mkdirs()
        invalidFrame.resolve("child").writeText("invalid")

        assertNull(store.read(BLOB_A, 0))
    }

    private fun withCacheRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("attachment-cache-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withSymlinkFixture(block: (File, File) -> Unit) {
        val parent = Files.createTempDirectory("attachment-cache-symlink-test").toFile()
        val root = parent.resolve("cache").also(File::mkdirs)
        val outside = parent.resolve("outside").also(File::mkdirs)
        outside.resolve("sentinel").writeText("keep")
        try {
            block(root, outside)
        } finally {
            root.listFiles()?.filter { Files.isSymbolicLink(it.toPath()) }?.forEach(File::delete)
            parent.deleteRecursively()
        }
    }

    private fun blobHash(blobSetId: BlobSetId): String =
        MessageDigest.getInstance("SHA-256")
            .digest(blobSetId.value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val BLOB_A = BlobSetId("blob-a")
        val BLOB_B = BlobSetId("blob-b")
        val BLOB_C = BlobSetId("blob-c")
    }
}
