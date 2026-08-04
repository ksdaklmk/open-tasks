package app.opentasks.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the property [TodayWidgetPublisher] depends on [StopGatedWriter]
 * for: once `stop`'s action has run, no `write` -- whether it was already
 * in flight, or requested afterwards by an arbitrary caller such as a
 * widget-host broadcast -- can ever run its own action later.
 *
 * These run entirely inside a single [runBlocking] event loop, so the
 * ordering each test forces (via [CompletableDeferred] and explicit
 * [yield] points) is deterministic, not timing-dependent.
 */
class StopGatedWriterTest {
    @Test
    fun writesBeforeStopAllRunAndStopRunsAfterThem() = runBlocking {
        withTimeout(5_000) {
            val writer = StopGatedWriter()
            val events = mutableListOf<String>()

            writer.write { events += "write-1" }
            writer.write { events += "write-2" }
            writer.stop { events += "stop" }

            assertEquals(listOf("write-1", "write-2", "stop"), events)
        }
    }

    @Test
    fun writeRequestedAfterStopIsSkipped() = runBlocking {
        withTimeout(5_000) {
            val writer = StopGatedWriter()
            val events = mutableListOf<String>()

            writer.stop { events += "stop" }
            writer.write { events += "write" }

            assertEquals(listOf("stop"), events)
        }
    }

    @Test
    fun stopOnlyRunsItsActionOnce() = runBlocking {
        withTimeout(5_000) {
            val writer = StopGatedWriter()
            val events = mutableListOf<String>()

            writer.stop { events += "stop-1" }
            writer.stop { events += "stop-2" }

            assertEquals(listOf("stop-1"), events)
        }
    }

    /**
     * The exact race the review found: a `republish`-style write already
     * holding the gate when `stop` is requested (e.g. a widget-host
     * broadcast landing in the instant before a slot closes) must finish
     * -- in full, including its own action -- before `stop`'s clearing
     * action can run. `writeEntered` and `releaseWrite` pin the write mid
     * flight so `stop` is guaranteed to be requested while it still holds
     * the gate, rather than merely hoping a race lands that way.
     */
    @Test
    fun writeInFlightWhenStopIsRequestedFinishesBeforeStopsAction() = runBlocking {
        withTimeout(5_000) {
            val writer = StopGatedWriter()
            val events = mutableListOf<String>()
            val writeEntered = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()

            val writeJob = launch {
                writer.write {
                    events += "write-start"
                    writeEntered.complete(Unit)
                    releaseWrite.await()
                    events += "write-end"
                }
            }

            // The write is now inside the gate, suspended on `releaseWrite`.
            writeEntered.await()

            val stopJob = launch { writer.stop { events += "stop" } }
            // Let `stop` actually attempt (and block on) the held gate.
            yield()

            releaseWrite.complete(Unit)
            writeJob.join()
            stopJob.join()

            assertEquals(listOf("write-start", "write-end", "stop"), events)
        }
    }
}
