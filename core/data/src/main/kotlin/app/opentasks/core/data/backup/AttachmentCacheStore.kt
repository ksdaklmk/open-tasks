package app.opentasks.core.data.backup

import app.opentasks.core.model.BlobSetId
import app.opentasks.core.sync.CloudBounds
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class AttachmentCacheStore(
    private val cacheRoot: File,
    private val availableBytes: () -> Long,
) {
    private var accessClock = 0L

    init {
        cacheRoot.mkdirs()
        accessClock = cacheFiles().maxOfOrNull(File::lastModified) ?: 0L
        sweep()
    }

    @Synchronized
    fun read(blobSetId: BlobSetId, chunkIndex: Int): ByteArray? {
        require(chunkIndex >= 0) { "Chunk index is negative" }
        val file = file(blobSetId, chunkIndex)
        if (!file.isFile || file.length() !in 1..MAX_FRAME_BYTES) {
            file.delete()
            return null
        }
        return try {
            file.readBytes().also { file.setLastModified(nextAccessTime()) }
        } catch (_: IOException) {
            file.delete()
            null
        } catch (_: SecurityException) {
            file.delete()
            null
        }
    }

    @Synchronized
    fun write(blobSetId: BlobSetId, chunkIndex: Int, frameBytes: ByteArray) {
        require(chunkIndex >= 0) { "Chunk index is negative" }
        require(frameBytes.size.toLong() in 1..MAX_FRAME_BYTES) {
            "Attachment frame is outside its bound"
        }
        val destination = file(blobSetId, chunkIndex)
        val directory = checkNotNull(destination.parentFile)
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create attachment cache directory")
        }
        val temporary = File.createTempFile("chunk-$chunkIndex-", ".tmp", directory)
        try {
            temporary.writeBytes(frameBytes)
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            destination.setLastModified(nextAccessTime())
        } finally {
            temporary.delete()
        }
        sweep()
    }

    @Synchronized
    fun evict(blobSetId: BlobSetId) {
        directory(blobSetId).deleteRecursively()
    }

    @Synchronized
    fun sweep() {
        cacheRoot.walkTopDown()
            .filter {
                it.isFile &&
                    (it.extension != FRAME_EXTENSION || it.length() !in 1..MAX_FRAME_BYTES)
            }
            .forEach(File::delete)
        val files = cacheFiles().sortedWith(compareBy(File::lastModified, ::relativePath))
        var usage = files.sumOf(File::length)
        val ceiling = minOf(MAX_CACHE_BYTES, availableBytes().coerceAtLeast(0) / 20)
        files.forEach { file ->
            if (usage > ceiling) {
                val length = file.length()
                if (file.delete()) usage -= length
            }
        }
        cacheRoot.walkBottomUp()
            .filter { it != cacheRoot && it.isDirectory && it.list()?.isEmpty() == true }
            .forEach(File::delete)
    }

    @Synchronized
    fun usageBytes(): Long = cacheFiles().sumOf(File::length)

    private fun file(blobSetId: BlobSetId, chunkIndex: Int) =
        directory(blobSetId).resolve("$chunkIndex.$FRAME_EXTENSION")

    private fun directory(blobSetId: BlobSetId) = cacheRoot.resolve(
        MessageDigest.getInstance("SHA-256")
            .digest(blobSetId.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) },
    )

    private fun cacheFiles() = cacheRoot.walkTopDown()
        .filter { it.isFile && it.extension == FRAME_EXTENSION }
        .toList()

    private fun relativePath(file: File) = file.relativeTo(cacheRoot).path

    private fun nextAccessTime(): Long =
        maxOf(System.currentTimeMillis(), accessClock + 1).also { accessClock = it }

    private companion object {
        const val FRAME_EXTENSION = "frame"
        const val MAX_CACHE_BYTES = 128L * 1024 * 1024
        const val MAX_FRAME_BYTES =
            4L + CloudBounds.MAX_HEADER_BYTES +
                CloudBounds.MAX_ATTACHMENT_CHUNK_CIPHERTEXT_BYTES_V1
    }
}
