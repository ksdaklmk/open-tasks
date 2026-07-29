package app.opentasks.core.data.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

data class LocalBackupCandidate(
    val objectId: String,
    val file: File,
    val byteCount: Long,
)

interface LocalBackupObjectStore {
    fun writeCandidate(objectId: String, frame: ByteArray): LocalBackupCandidate
    fun commitSnapshot(candidate: LocalBackupCandidate, previousObjectId: String?)
    fun commitSegment(candidate: LocalBackupCandidate)
    fun open(objectId: String): InputStream
    fun length(objectId: String): Long
    fun prune(retainedObjectIds: Set<String>)
}

class LocalBackupFileException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

interface LocalBackupFileOperations {
    fun atomicMove(source: File, destination: File)

    fun writeAndSync(file: File, frame: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(frame)
            output.fd.sync()
        }
    }
}

internal object AtomicLocalBackupFileOperations : LocalBackupFileOperations {
    override fun atomicMove(source: File, destination: File) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            ATOMIC_MOVE,
            REPLACE_EXISTING,
        )
    }
}

/**
 * Stores frames below an already-injected `noBackupFilesDir/backup/v1` root.
 * Android path discovery deliberately lives outside this class.
 */
class DefaultLocalBackupObjectStore(
    root: File,
    private val fileOperations: LocalBackupFileOperations = AtomicLocalBackupFileOperations,
) : LocalBackupObjectStore {
    private val rootPath = root.absoluteFile.toPath().normalize()
    private val current = child("current")
    private val previous = child("previous")
    private val segments = child("segments")
    private val staging = child("staging")

    init {
        listOf(rootPath, current, previous, segments, staging).forEach { directory ->
            try {
                Files.createDirectories(directory)
            } catch (failure: Throwable) {
                throw LocalBackupFileException("Unable to create local backup directory", failure)
            }
        }
    }

    override fun writeCandidate(objectId: String, frame: ByteArray): LocalBackupCandidate {
        validateId(objectId)
        val candidate = try {
            Files.createTempFile(staging, "candidate-", ".otf").toFile()
        } catch (failure: Throwable) {
            throw LocalBackupFileException("Unable to create local backup candidate", failure)
        }
        try {
            fileOperations.writeAndSync(candidate, frame)
            return LocalBackupCandidate(objectId, candidate, candidate.length())
        } catch (failure: Throwable) {
            candidate.delete()
            throw LocalBackupFileException("Unable to write local backup candidate", failure)
        }
    }

    override fun commitSnapshot(candidate: LocalBackupCandidate, previousObjectId: String?) {
        require(snapshotId.matches(candidate.objectId)) { "Snapshot candidate required" }
        validateCandidate(candidate)
        previousObjectId?.let {
            require(snapshotId.matches(it)) { "Previous snapshot ID required" }
        }
        val target = current.resolve(fileName(candidate.objectId))
        val retainedPrevious = previousObjectId?.let { previous.resolve(fileName(it)) }
        val oldCurrent = previousObjectId?.let { current.resolve(fileName(it)).toFile() }
            ?.takeIf(File::isFile)
        val copy = if (oldCurrent != null && retainedPrevious != null) {
            try {
                Files.createTempFile(staging, "previous-", ".otf").also { temporary ->
                    Files.copy(oldCurrent.toPath(), temporary, REPLACE_EXISTING)
                    FileOutputStream(temporary.toFile(), true).use { it.fd.sync() }
                }
            } catch (failure: Throwable) {
                throw LocalBackupFileException("Unable to stage previous local backup", failure)
            }
        } else {
            null
        }
        try {
            // The old base is made durable before current is replaced. A later
            // current-promotion failure therefore leaves current intact and at
            // most creates an additional, safe previous copy.
            if (copy != null && retainedPrevious != null) {
                atomicReplace(copy, retainedPrevious)
            }
            atomicReplace(candidate.file.toPath(), target)
            if (oldCurrent != null && oldCurrent.toPath() != target && !oldCurrent.delete()) {
                throw LocalBackupFileException("Unable to clear superseded local backup object")
            }
        } catch (failure: Throwable) {
            copy?.toFile()?.delete()
            throw failure
        }
    }

    override fun commitSegment(candidate: LocalBackupCandidate) {
        require(segmentId.matches(candidate.objectId)) { "Segment candidate required" }
        validateCandidate(candidate)
        val (first, last) = segmentRange(candidate.objectId)
        val target = segments.resolve(fileName(candidate.objectId)).toFile()
        val overlapping = segments.toFile().listFiles()?.filter { existing ->
            idForFile(existing.name)?.takeIf(segmentId::matches)?.let { id ->
                val (existingFirst, existingLast) = segmentRange(id)
                existing.absoluteFile != target.absoluteFile &&
                    existingFirst <= last && first <= existingLast
            } ?: false
        }.orEmpty()
        atomicReplace(candidate.file.toPath(), target.toPath())
        // Preserve an existing range until the replacement is durable. A
        // failed move therefore leaves all prior bytes available for retry.
        overlapping.forEach { existing ->
            if (existing.exists() && !existing.delete()) {
                throw LocalBackupFileException("Unable to discard overlapping local backup segment")
            }
        }
    }

    override fun open(objectId: String): InputStream = FileInputStream(resolveVisible(objectId).toFile())

    override fun length(objectId: String): Long = resolveVisible(objectId).toFile().length()

    override fun prune(retainedObjectIds: Set<String>) {
        retainedObjectIds.forEach(::validateId)
        val oldestRetainedBase = retainedObjectIds
            .asSequence()
            .filter(snapshotId::matches)
            .map { it.substringAfter(':').toLong() }
            .minOrNull()
        listOf(current, previous, segments).forEach { directory ->
            directory.toFile().listFiles()?.forEach { file ->
                val id = idForFile(file.name)
                val requiredSegment = id?.takeIf(segmentId::matches)?.let {
                    oldestRetainedBase != null && it.substringAfterLast(':').toLong() > oldestRetainedBase
                } == true
                if (id == null || (id !in retainedObjectIds && !requiredSegment)) {
                    if (!file.delete()) {
                        throw LocalBackupFileException("Unable to prune local backup object")
                    }
                }
            }
        }
    }

    private fun resolveVisible(objectId: String) = when {
        snapshotId.matches(objectId) -> {
            val name = fileName(objectId)
            listOf(current.resolve(name), previous.resolve(name)).firstOrNull(Files::isRegularFile)
                ?: throw LocalBackupFileException("Local backup object is unavailable")
        }
        segmentId.matches(objectId) -> segments.resolve(fileName(objectId)).also {
            if (!Files.isRegularFile(it)) throw LocalBackupFileException("Local backup object is unavailable")
        }
        else -> throw IllegalArgumentException("Invalid local backup object ID")
    }

    private fun validateCandidate(candidate: LocalBackupCandidate) {
        validateId(candidate.objectId)
        val file = candidate.file.absoluteFile.toPath().normalize()
        require(file.parent == staging && Files.isRegularFile(file)) {
            "Local backup candidate is outside staging"
        }
        require(candidate.byteCount == Files.size(file)) { "Local backup candidate length changed" }
    }

    private fun atomicReplace(source: java.nio.file.Path, destination: java.nio.file.Path) {
        try {
            fileOperations.atomicMove(source.toFile(), destination.toFile())
        } catch (failure: AtomicMoveNotSupportedException) {
            throw LocalBackupFileException("Atomic local backup replacement is unavailable", failure)
        } catch (failure: Throwable) {
            throw LocalBackupFileException("Unable to atomically replace local backup object", failure)
        }
    }

    private fun child(name: String) = rootPath.resolve(name).normalize().also {
        require(it.parent == rootPath) { "Invalid local backup directory" }
    }

    private fun validateId(objectId: String) {
        require(snapshotId.matches(objectId) || segmentId.matches(objectId)) {
            "Invalid local backup object ID"
        }
    }

    private fun fileName(objectId: String): String = when {
        snapshotId.matches(objectId) -> "snapshot-${objectId.substringAfter(':')}.otf"
        segmentId.matches(objectId) -> "segment-${objectId.substringAfter(':').replace(':', '-')}.otf"
        else -> throw IllegalArgumentException("Invalid local backup object ID")
    }

    private fun idForFile(name: String): String? = when {
        snapshotFile.matches(name) -> "snapshot:${name.removePrefix("snapshot-").removeSuffix(".otf")}"
        segmentFile.matches(name) -> "segment:${name.removePrefix("segment-").removeSuffix(".otf").replace('-', ':')}"
        else -> null
    }

    private fun segmentRange(objectId: String): Pair<Long, Long> = objectId
        .removePrefix("segment:")
        .split(':')
        .let { (first, last) -> first.toLong() to last.toLong() }

    private companion object {
        val snapshotId = Regex("snapshot:[0-9]+")
        val segmentId = Regex("segment:[0-9]+:[0-9]+")
        val snapshotFile = Regex("snapshot-[0-9]+\\.otf")
        val segmentFile = Regex("segment-[0-9]+-[0-9]+\\.otf")
    }
}
