package app.opentasks.core.data.backup

import app.opentasks.core.data.backup.drive.DriveTransportException
import app.opentasks.core.domain.CreateOnlyBackupObjectStore
import app.opentasks.core.domain.OwnedRemoteBytes
import app.opentasks.core.domain.ReadSmallResult
import app.opentasks.core.domain.RemoteListRequest
import app.opentasks.core.domain.RemoteListedObject
import app.opentasks.core.model.ProviderObjectId
import app.opentasks.core.model.RemoteBackupFailureCategory
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Shared bounded access to [CreateOnlyBackupObjectStore].
 *
 * `list` has no failure result family and the provider-backed store raises
 * instead, so every list-driven caller would otherwise repeat the same
 * catch-and-translate loop. These helpers keep exactly one copy of it, so a
 * provider failure, a local failure, an over-bound namespace, and an
 * over-long page chain all reduce to one redacted
 * [RemoteBackupFailureCategory] and never escape as control flow.
 */

/** A provider or decoding failure already reduced to a bounded category. */
internal class BoundedRemoteFailure(
    val reason: RemoteBackupFailureCategory,
) : RuntimeException("Bounded remote failure")

internal const val REMOTE_FILE_LENGTH_PREFIX_BYTES = 4L

/** The most index pages any one bounded discovery will follow. */
internal const val MAX_LIST_PAGES = 64

/**
 * Pages one bounded provider index.
 *
 * Accumulating more than [maximum] objects, or following more than
 * [MAX_LIST_PAGES] pages, is a corrupt or unsupported remote namespace rather
 * than a reason to truncate silently. Failures are raised as
 * [BoundedRemoteFailure] for the calling component to turn into its own
 * bounded blocked result.
 */
internal suspend fun listAllBounded(
    objectStore: CreateOnlyBackupObjectStore,
    request: RemoteListRequest,
    maximum: Int,
): List<RemoteListedObject> {
    val accumulated = mutableListOf<RemoteListedObject>()
    var pageToken: String? = request.pageToken
    var pages = 0
    while (true) {
        val page = try {
            objectStore.list(request.copy(pageToken = pageToken))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw BoundedRemoteFailure(failure.toBoundedReason())
        }
        accumulated += page.objects
        if (accumulated.size > maximum) {
            throw BoundedRemoteFailure(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        }
        pages += 1
        pageToken = page.nextPageToken ?: return accumulated
        if (pages >= MAX_LIST_PAGES) {
            throw BoundedRemoteFailure(RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE)
        }
    }
}

/** Reads one bounded small object, degrading every failure to a category. */
internal suspend fun readSmallBounded(
    objectStore: CreateOnlyBackupObjectStore,
    providerObjectId: ProviderObjectId,
    maximumBytes: Long,
): ReadSmallResult = try {
    objectStore.readSmall(providerObjectId, maximumBytes)
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    ReadSmallResult.Failed(failure.toBoundedReason())
}

/**
 * Runs a codec or validation step whose only failure mode is rejected input.
 * Returns null instead of propagating, so an ambiguity never escapes as
 * control flow and no message from the failure ever reaches a caller.
 * Coroutine cancellation is never swallowed.
 */
internal inline fun <T> runBounded(block: () -> T): T? = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
}

/** Consumes owned remote bytes exactly once, clearing them afterwards. */
internal inline fun <T> OwnedRemoteBytes.useOwned(block: (ByteArray) -> T): T {
    val source = take()
    return try {
        block(source)
    } finally {
        source.fill(0)
        close()
    }
}

internal fun ownedCopyOf(bytes: ByteArray): OwnedRemoteBytes = object : OwnedRemoteBytes {
    private var owned: ByteArray? = bytes.copyOf()
    override val size: Int = bytes.size

    override fun take(): ByteArray {
        val current = checkNotNull(owned) { "Remote bytes were already taken or closed" }
        owned = null
        return current
    }

    override fun close() {
        owned?.fill(0)
        owned = null
    }
}

/**
 * Reduces an unexpected provider or local failure to a bounded category. The
 * throwable itself is never logged or rethrown, so no provider identifier or
 * message escapes. Coroutine cancellation is not a bounded failure and must be
 * rethrown by every caller before this runs.
 */
internal fun Exception.toBoundedReason(): RemoteBackupFailureCategory = when (this) {
    is BoundedRemoteFailure -> reason
    is DriveTransportException -> category.toRemoteFailure()
    is IllegalArgumentException -> RemoteBackupFailureCategory.CORRUPT_OR_INCOMPATIBLE
    is IOException -> RemoteBackupFailureCategory.RETRYABLE_PROVIDER
    else -> RemoteBackupFailureCategory.RETRYABLE_PROVIDER
}
