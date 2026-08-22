package app.opentasks

import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkspaceSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSearchStateTest {
    @Test
    fun latestQueryWinsWhenTheCancelledSearchReturnsLast() = runBlocking {
        val releases = mapOf(
            "A" to CompletableDeferred<Unit>(),
            "B" to CompletableDeferred(),
        )
        val started = Channel<Pair<String, String>>(Channel.UNLIMITED)
        val finished = Channel<String>(Channel.UNLIMITED)
        val repository = FakeSearchRepository { query ->
            started.send(query.text to Thread.currentThread().name)
            withContext(NonCancellable) {
                releases.getValue(query.text).await()
                finished.send(query.text)
            }
            result(query.text)
        }
        val scope = testScope()
        val state = WorkspaceSearchState(repository, scope)

        state.search(SearchQuery("A"))
        val firstThread = started.receive().second
        state.search(SearchQuery("B"))
        val secondThread = started.receive().second
        releases.getValue("B").complete(Unit)
        withTimeout(2_000) {
            state.results.first { it.singleOrNull()?.title == "B" }
        }
        assertEquals("B", withTimeout(2_000) { finished.receive() })
        val stalePublication = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(500) {
                state.results.drop(1).first { it.singleOrNull()?.title == "A" }
            }
        }
        releases.getValue("A").complete(Unit)
        assertEquals("A", withTimeout(2_000) { finished.receive() })

        assertNull(stalePublication.await())
        assertEquals("B", state.results.value.single().title)
        assertTrue(firstThread.contains("DefaultDispatcher"))
        assertTrue(secondThread.contains("DefaultDispatcher"))
        scope.cancel()
    }

    @Test
    fun aNewSearchPropagatesCancellationToThePreviousRepositoryCall() = runBlocking {
        val started = Channel<String>(Channel.UNLIMITED)
        val cancelled = Channel<String>(Channel.UNLIMITED)
        val repository = FakeSearchRepository { query ->
            if (query.text == "A") {
                started.send(query.text)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.trySend(query.text).getOrThrow()
                }
            }
            result(query.text)
        }
        val scope = testScope()
        val state = WorkspaceSearchState(repository, scope)

        state.search(SearchQuery("A"))
        started.receive()
        state.search(SearchQuery("B"))

        assertEquals("A", withTimeout(2_000) { cancelled.receive() })
        withTimeout(2_000) { state.results.first { it.singleOrNull()?.title == "B" } }
        scope.cancel()
    }

    @Test
    fun clearCancelsRunningSearchAndKeepsResultsEmpty() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val repository = FakeSearchRepository {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val scope = testScope()
        val state = WorkspaceSearchState(repository, scope)

        state.search(SearchQuery("running"))
        started.await()
        state.clear()

        withTimeout(2_000) { cancelled.await() }
        assertTrue(state.results.value.isEmpty())
        scope.cancel()
    }

    private fun testScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun result(title: String): List<SearchResult> = listOf(
        SearchResult.TaskResult(
            task = OpenTasksFixtures.tasks.first().copy(title = title),
            context = "",
        ),
    )

    private class FakeSearchRepository(
        private val search: suspend (SearchQuery) -> List<SearchResult>,
    ) : VaultRepository {
        override fun observeHome(): Flow<HomeSnapshot> = error("unused")

        override fun observeWorkspace(): StateFlow<WorkspaceSnapshot> = error("unused")

        override fun observeTask(id: TaskId): Flow<Task?> = error("unused")

        override suspend fun currentWorkspace(): WorkspaceSnapshot = error("unused")

        override suspend fun execute(command: DomainCommand): CommandResult = error("unused")

        override suspend fun search(query: SearchQuery): List<SearchResult> = search.invoke(query)
    }
}
