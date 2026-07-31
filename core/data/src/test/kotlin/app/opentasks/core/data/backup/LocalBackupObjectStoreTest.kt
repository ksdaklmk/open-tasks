package app.opentasks.core.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalBackupObjectStoreTest {
    @Test
    fun candidateIsInvisibleUntilItsSnapshotIsCommitted() {
        val root = Files.createTempDirectory("local-backup-store-test").toFile()
        try {
            val store = DefaultLocalBackupObjectStore(root)
            val frame = "verified-frame".toByteArray()

            val candidate = store.writeCandidate("snapshot:7", frame)

            assertTrue(candidate.file.isFile)
            assertFalse(root.resolve("current/snapshot-7.otf").exists())
            assertFalse(root.resolve("previous/snapshot-7.otf").exists())
            assertFalse(root.resolve("segments/segment-7-7.otf").exists())
            store.commitSnapshot(candidate, previousObjectId = null)

            store.open("snapshot:7").use { input ->
                assertArrayEquals(frame, input.readBytes())
            }
            frame.fill(0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun snapshotCommitRetainsThePriorVerifiedBaseAsPrevious() {
        val root = Files.createTempDirectory("local-backup-store-test").toFile()
        try {
            val store = DefaultLocalBackupObjectStore(root)
            val first = store.writeCandidate("snapshot:1", "first".toByteArray())
            store.commitSnapshot(first, previousObjectId = null)
            val second = store.writeCandidate("snapshot:2", "second".toByteArray())

            store.commitSnapshot(second, previousObjectId = "snapshot:1")

            store.open("snapshot:2").use { input ->
                assertArrayEquals("second".toByteArray(), input.readBytes())
            }
            assertTrue(root.resolve("previous/snapshot-1.otf").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun segmentNamesAreConfinedToTheInjectedRoot() {
        val root = Files.createTempDirectory("local-backup-store-test").toFile()
        try {
            val store = DefaultLocalBackupObjectStore(root)
            val candidate = store.writeCandidate("segment:8:9", "segment".toByteArray())

            store.commitSegment(candidate)

            assertTrue(root.resolve("segments/segment-8-9.otf").isFile)
            assertThrows(IllegalArgumentException::class.java) {
                store.writeCandidate("snapshot:../escape", byteArrayOf(1))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.open("segment:8:../9")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aRetriedWiderSegmentReplacesAnUncheckpointedOverlap() {
        val root = Files.createTempDirectory("local-backup-store-test").toFile()
        try {
            val store = DefaultLocalBackupObjectStore(root)
            store.commitSegment(store.writeCandidate("segment:54:54", "first".toByteArray()))
            store.commitSegment(store.writeCandidate("segment:54:55", "retry".toByteArray()))

            assertFalse(root.resolve("segments/segment-54-54.otf").exists())
            assertEquals("retry", store.open("segment:54:55").readBytes().decodeToString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedRetriedSegmentPromotionLeavesThePriorOverlapReadable() {
        val root = Files.createTempDirectory("local-backup-store-test").toFile()
        try {
            val setup = DefaultLocalBackupObjectStore(root)
            setup.commitSegment(setup.writeCandidate("segment:54:54", "first".toByteArray()))
            val store = DefaultLocalBackupObjectStore(root, FailingLocalBackupFileOperations { destination ->
                destination.parentFile?.name == "segments"
            })
            val retry = store.writeCandidate("segment:54:55", "retry".toByteArray())

            assertThrows(LocalBackupFileException::class.java) { store.commitSegment(retry) }

            assertEquals("first", store.open("segment:54:54").readBytes().decodeToString())
            assertFalse(root.resolve("segments/segment-54-55.otf").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameIdRetryRetainsItsPromotedSegmentForCheckpointRetry() {
        val root = Files.createTempDirectory("local-backup-store-test").toFile()
        try {
            val store = DefaultLocalBackupObjectStore(root)
            store.commitSegment(store.writeCandidate("segment:54:55", "first".toByteArray()))

            store.commitSegment(store.writeCandidate("segment:54:55", "retry".toByteArray()))

            assertEquals("retry", store.open("segment:54:55").readBytes().decodeToString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedCurrentPromotionLeavesReferencedCurrentAndPreviousBytesUnchanged() {
        val root = Files.createTempDirectory("local-backup-store-test").toFile()
        try {
            val operations = FailingLocalBackupFileOperations { destination ->
                destination.parentFile?.name == "current"
            }
            val setup = DefaultLocalBackupObjectStore(root)
            setup.commitSnapshot(setup.writeCandidate("snapshot:1", "one".toByteArray()), null)
            setup.commitSnapshot(
                setup.writeCandidate("snapshot:2", "two".toByteArray()),
                "snapshot:1",
            )
            val store = DefaultLocalBackupObjectStore(root, operations)
            val candidate = store.writeCandidate("snapshot:3", "three".toByteArray())

            assertThrows(LocalBackupFileException::class.java) {
                store.commitSnapshot(candidate, "snapshot:2")
            }

            assertArrayEquals("two".toByteArray(), store.open("snapshot:2").readBytes())
            assertArrayEquals("one".toByteArray(), store.open("snapshot:1").readBytes())
            assertTrue(root.resolve("previous/snapshot-2.otf").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pruneRetainsBothBasesAndSegmentsAfterTheOlderBaseAcrossReopen() {
        val root = Files.createTempDirectory("local-backup-store-test").toFile()
        try {
            val store = DefaultLocalBackupObjectStore(root)
            store.commitSnapshot(store.writeCandidate("snapshot:1", byteArrayOf(1)), null)
            store.commitSnapshot(store.writeCandidate("snapshot:2", byteArrayOf(2)), "snapshot:1")
            store.commitSegment(store.writeCandidate("segment:0:0", byteArrayOf(0)))
            store.commitSegment(store.writeCandidate("segment:2:2", byteArrayOf(2)))
            store.commitSegment(store.writeCandidate("segment:3:3", byteArrayOf(3)))
            root.resolve("staging/interrupted.otf").writeBytes(byteArrayOf(9))

            store.prune(setOf("snapshot:1", "snapshot:2", "segment:3:3"))
            val reopened = DefaultLocalBackupObjectStore(root)

            assertArrayEquals(byteArrayOf(1), reopened.open("snapshot:1").readBytes())
            assertArrayEquals(byteArrayOf(2), reopened.open("snapshot:2").readBytes())
            assertTrue(root.resolve("segments/segment-3-3.otf").isFile)
            assertTrue(root.resolve("segments/segment-2-2.otf").isFile)
            assertFalse(root.resolve("segments/segment-0-0.otf").exists())
            assertFalse(root.resolve("current/interrupted.otf").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedCandidateWriteLeavesCurrentBytesUnchanged() {
        val root = Files.createTempDirectory("local-backup-store-test").toFile()
        try {
            val setup = DefaultLocalBackupObjectStore(root)
            setup.commitSnapshot(setup.writeCandidate("snapshot:1", "one".toByteArray()), null)
            val store = DefaultLocalBackupObjectStore(root, object : LocalBackupFileOperations {
                override fun atomicMove(source: File, destination: File) =
                    AtomicLocalBackupFileOperations.atomicMove(source, destination)

                override fun writeAndSync(file: File, frame: ByteArray) {
                    throw IllegalStateException("injected write failure")
                }
            })

            assertThrows(LocalBackupFileException::class.java) {
                store.writeCandidate("snapshot:2", "two".toByteArray())
            }

            assertArrayEquals("one".toByteArray(), store.open("snapshot:1").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun committedObjectIdsAreEnumeratedInADeterministicOrder() {
        val root = Files.createTempDirectory("local-backup-store-test").toFile()
        try {
            val store = DefaultLocalBackupObjectStore(root)
            store.commitSnapshot(store.writeCandidate("snapshot:1", byteArrayOf(1)), null)
            store.commitSnapshot(store.writeCandidate("snapshot:12", byteArrayOf(2)), "snapshot:1")
            store.commitSegment(store.writeCandidate("segment:2:3", byteArrayOf(3)))
            store.commitSegment(store.writeCandidate("segment:4:4", byteArrayOf(4)))
            root.resolve("staging/interrupted.otf").writeBytes(byteArrayOf(9))
            root.resolve("segments/unrelated.txt").writeBytes(byteArrayOf(9))

            assertEquals(
                listOf("segment:2:3", "segment:4:4", "snapshot:1", "snapshot:12"),
                store.objectIds(),
            )
            assertEquals(store.objectIds(), DefaultLocalBackupObjectStore(root).objectIds())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anOverBoundLocalNamespaceFailsClosedRatherThanTruncating() {
        val root = Files.createTempDirectory("local-backup-store-test").toFile()
        try {
            val store = DefaultLocalBackupObjectStore(root)
            val segments = root.resolve("segments")
            repeat(DefaultLocalBackupObjectStore.MAX_LOCAL_OBJECTS + 1) { index ->
                segments.resolve("segment-$index-$index.otf").writeBytes(byteArrayOf(1))
            }

            assertThrows(LocalBackupFileException::class.java) { store.objectIds() }
        } finally {
            root.deleteRecursively()
        }
    }
}

private class FailingLocalBackupFileOperations(
    private val shouldFailMove: (File) -> Boolean,
) : LocalBackupFileOperations {
    override fun atomicMove(source: File, destination: File) {
        if (shouldFailMove(destination)) throw IllegalStateException("injected move failure")
        source.toPath().let { from ->
            Files.move(
                from,
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
