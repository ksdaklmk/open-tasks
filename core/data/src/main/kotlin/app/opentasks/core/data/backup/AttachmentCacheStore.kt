package app.opentasks.core.data.backup

import app.opentasks.core.model.BlobSetId
import app.opentasks.core.sync.CloudBounds
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Comparator

class AttachmentCacheStore(
    private val cacheRoot: File,
    private val availableBytes: () -> Long,
) {
    private val root = cacheRoot.toPath().toAbsolutePath().normalize()
    private var accessClock = 0L

    init {
        if (Files.isSymbolicLink(root)) throw IOException("Attachment cache root is a symlink")
        Files.createDirectories(root)
        requireRoot()
        accessClock = cacheFiles().maxOfOrNull(::lastModified) ?: 0L
        sweep()
    }

    @Synchronized
    fun read(blobSetId: BlobSetId, chunkIndex: Int): ByteArray? {
        require(chunkIndex >= 0) { "Chunk index is negative" }
        val directory = blobDirectory(blobSetId)
        val file = framePath(directory, chunkIndex)
        return try {
            if (!isSafeDirectory(directory)) {
                null
            } else if (
                !Files.isRegularFile(file, NOFOLLOW_LINKS) ||
                Files.size(file) !in 1..MAX_FRAME_BYTES
            ) {
                deleteFrameIfSafe(directory, file)
                null
            } else {
                Files.readAllBytes(file).also {
                    Files.setLastModifiedTime(file, FileTime.fromMillis(nextAccessTime()))
                }
            }
        } catch (_: IOException) {
            deleteFrameIfSafe(directory, file)
            null
        } catch (_: SecurityException) {
            deleteFrameIfSafe(directory, file)
            null
        }
    }

    @Synchronized
    fun write(blobSetId: BlobSetId, chunkIndex: Int, frameBytes: ByteArray) {
        require(chunkIndex >= 0) { "Chunk index is negative" }
        require(frameBytes.size.toLong() in 1..MAX_FRAME_BYTES) {
            "Attachment frame is outside its bound"
        }
        requireRoot()
        val directory = blobDirectory(blobSetId)
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            if (!isSafeDirectory(directory)) {
                throw IOException("Attachment cache blob directory is not a regular directory")
            }
        } else {
            Files.createDirectory(directory)
        }
        val destination = framePath(directory, chunkIndex)
        val temporary = Files.createTempFile(directory, "chunk-$chunkIndex-", ".tmp")
        try {
            Files.write(temporary, frameBytes)
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (failure: AtomicMoveNotSupportedException) {
                throw IOException("Atomic attachment cache writes are unsupported", failure)
            }
            Files.setLastModifiedTime(destination, FileTime.fromMillis(nextAccessTime()))
        } finally {
            Files.deleteIfExists(temporary)
        }
        sweep()
    }

    @Synchronized
    fun evict(blobSetId: BlobSetId) {
        requireRoot()
        val directory = blobDirectory(blobSetId)
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            Files.deleteIfExists(directory)
            return
        }
        deleteTree(directory)
    }

    @Synchronized
    fun sweep() {
        requireRoot()
        walk(root).sortedByDescending(Path::getNameCount).forEach { path ->
            val keep = isCanonicalDirectory(path) ||
                (isCanonicalFrame(path) && Files.size(path) in 1..MAX_FRAME_BYTES)
            if (!keep) Files.deleteIfExists(path)
        }
        val files = cacheFiles().sortedWith(compareBy(::lastModified, ::relativePath))
        var usage = files.sumOf(Files::size)
        val ceiling = minOf(MAX_CACHE_BYTES, availableBytes().coerceAtLeast(0) / 20)
        files.forEach { file ->
            if (usage > ceiling) {
                val length = Files.size(file)
                if (Files.deleteIfExists(file)) usage -= length
            }
        }
        cacheDirectories().filter(::isEmpty).forEach(Files::deleteIfExists)
    }

    @Synchronized
    fun usageBytes(): Long {
        requireRoot()
        return cacheFiles().sumOf(Files::size)
    }

    private fun blobDirectory(blobSetId: BlobSetId) = root.resolve(
        MessageDigest.getInstance("SHA-256")
            .digest(blobSetId.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) },
    )

    private fun framePath(directory: Path, chunkIndex: Int) =
        directory.resolve("$chunkIndex.$FRAME_EXTENSION")

    private fun cacheFiles(): List<Path> = walk(root, 2).filter(::isCanonicalFrame)

    private fun cacheDirectories(): List<Path> = walk(root, 1).filter(::isCanonicalDirectory)

    private fun isCanonicalFrame(path: Path): Boolean {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return false
        val parent = path.parent ?: return false
        val name = path.fileName.toString()
        val index = name.removeSuffix(".$FRAME_EXTENSION")
        val parsedIndex = index.toIntOrNull()
        return isCanonicalDirectory(parent) &&
            name.endsWith(".$FRAME_EXTENSION") &&
            parsedIndex != null && parsedIndex >= 0 && parsedIndex.toString() == index
    }

    private fun isCanonicalDirectory(path: Path): Boolean =
        path.parent == root &&
            BLOB_DIRECTORY.matches(path.fileName.toString()) &&
            Files.isDirectory(path, NOFOLLOW_LINKS)

    private fun isSafeDirectory(path: Path): Boolean {
        requireRoot()
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return false
        }
        return Files.isDirectory(path, NOFOLLOW_LINKS)
    }

    private fun requireRoot() {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW_LINKS)) {
            throw IOException("Attachment cache root is not a regular directory")
        }
    }

    private fun deleteFrameIfSafe(directory: Path, file: Path) {
        try {
            requireRoot()
            if (!Files.isSymbolicLink(directory) && Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                Files.deleteIfExists(file)
            }
        } catch (_: IOException) {
            // A corrupt cache entry is a miss even when best-effort cleanup fails.
        } catch (_: SecurityException) {
            // A corrupt cache entry is a miss even when best-effort cleanup fails.
        }
    }

    private fun deleteTree(directory: Path) {
        walk(directory).sortedWith(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }

    private fun walk(directory: Path, depth: Int = Int.MAX_VALUE): List<Path> {
        val paths = mutableListOf<Path>()
        Files.walk(directory, depth).use { stream ->
            stream.filter { it != directory }.forEach(paths::add)
        }
        return paths
    }

    private fun isEmpty(directory: Path): Boolean =
        Files.list(directory).use { stream -> !stream.findAny().isPresent }

    private fun lastModified(file: Path) =
        Files.getLastModifiedTime(file, NOFOLLOW_LINKS).toMillis()

    private fun relativePath(file: Path) = root.relativize(file).toString()

    private fun nextAccessTime(): Long =
        maxOf(System.currentTimeMillis(), accessClock + 1).also { accessClock = it }

    private companion object {
        const val FRAME_EXTENSION = "frame"
        const val MAX_CACHE_BYTES = 128L * 1024 * 1024
        const val MAX_FRAME_BYTES =
            4L + CloudBounds.MAX_HEADER_BYTES +
                CloudBounds.MAX_ATTACHMENT_CHUNK_CIPHERTEXT_BYTES_V1
        val BLOB_DIRECTORY = Regex("[0-9a-f]{64}")
    }
}
