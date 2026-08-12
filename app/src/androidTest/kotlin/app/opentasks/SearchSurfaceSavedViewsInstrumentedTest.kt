package app.opentasks

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.DueBucket
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.SavedView
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.TagId
import app.opentasks.core.model.TaskSortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the saved-search chips, save affordance, and the debounce
 * contract added on top of the plain-text search surface: [SearchSurface]
 * still dispatches the whole [SearchQuery] -- filters included -- on its
 * existing 150 ms debounce, and Save bypasses that debounce entirely.
 */
@RunWith(AndroidJUnit4::class)
class SearchSurfaceSavedViewsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val focusView = SavedView(
        id = SavedViewId("saved-view-focus"),
        workspaceId = OpenTasksFixtures.workspaceId,
        name = "Focus queue",
        query = SearchQuery(
            text = "deep work",
            projectIds = setOf(OpenTasksFixtures.studioProject.id),
            tagIds = setOf(TagId("tag-deep-work")),
            includeCompleted = false,
            includeTrash = true,
        ),
    )

    private val overdueView = SavedView(
        id = SavedViewId("saved-view-overdue"),
        workspaceId = OpenTasksFixtures.workspaceId,
        name = "Overdue",
        query = SearchQuery(text = "overdue"),
    )

    @Test
    fun chipsRenderOnlyWhileTheQueryIsBlank() {
        composeRule.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = {},
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    savedViews = listOf(focusView, overdueView),
                )
            }
        }

        composeRule.onNodeWithTag("saved-view-chip-${focusView.id.value}").assertIsDisplayed()
        composeRule.onNodeWithTag("saved-view-chip-${overdueView.id.value}").assertIsDisplayed()

        composeRule.onNodeWithTag("workspace-search-query").performTextReplacement("anything")

        composeRule.onNodeWithTag("saved-view-chip-${focusView.id.value}").assertDoesNotExist()
        composeRule.onNodeWithTag("saved-view-chip-${overdueView.id.value}").assertDoesNotExist()
    }

    @Test
    fun tappingAChipFillsTheFieldWithItsQueryText() {
        composeRule.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = {},
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    savedViews = listOf(focusView, overdueView),
                )
            }
        }

        composeRule.onNodeWithTag("saved-view-chip-${focusView.id.value}").performClick()

        composeRule.onNodeWithTag("workspace-search-query")
            .assertTextContains(focusView.query.text, substring = true)
    }

    @Test
    fun tappingAChipDispatchesTheWholeSavedQueryAfterTheDebounce() {
        val captured = AtomicReference<SearchQuery?>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = captured::set,
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    savedViews = listOf(focusView, overdueView),
                )
            }
        }

        composeRule.onNodeWithTag("saved-view-chip-${focusView.id.value}").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(151)
        composeRule.waitForIdle()

        assertEquals(focusView.query, captured.get())
    }

    @Test
    fun relativeSavedQueryRedispatchesWhenOnlyTimeChanges() {
        val saved = focusView.copy(
            query = SearchQuery("", dueBuckets = setOf(DueBucket.TODAY)),
        )
        val captured = mutableListOf<SearchQuery>()
        val timeVersion = mutableStateOf(0L)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = captured::add,
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    savedViews = listOf(saved),
                    timeVersion = timeVersion.value,
                )
            }
        }

        composeRule.onNodeWithTag("saved-view-chip-${saved.id.value}").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(151)
        composeRule.waitForIdle()
        assertEquals(listOf(saved.query), captured)

        composeRule.runOnUiThread { timeVersion.value++ }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(151)
        composeRule.waitForIdle()

        assertEquals(listOf(saved.query, saved.query), captured)
    }

    @Test
    fun dueSortAloneDoesNotRedispatchWhenTimeChanges() {
        val saved = focusView.copy(
            query = SearchQuery("", sort = TaskSortKey.DUE),
        )
        val captured = mutableListOf<SearchQuery>()
        val timeVersion = mutableStateOf(0L)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = captured::add,
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    savedViews = listOf(saved),
                    timeVersion = timeVersion.value,
                )
            }
        }

        composeRule.onNodeWithTag("saved-view-chip-${saved.id.value}").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(151)
        composeRule.waitForIdle()
        assertEquals(listOf(saved.query), captured)

        composeRule.runOnUiThread { timeVersion.value++ }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(151)
        composeRule.waitForIdle()

        assertEquals(listOf(saved.query), captured)
    }

    @Test
    fun textRefinementKeepsFiltersUntilExplicitClear() {
        val saved = focusView.copy(
            query = SearchQuery(
                text = "deep",
                dueBuckets = setOf(DueBucket.TODAY),
                priorities = setOf(Priority.URGENT),
                statuses = setOf(SemanticStatus.STARTED),
                sort = TaskSortKey.UPDATED,
            ),
        )
        val emitted = AtomicReference<SearchQuery?>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            OpenTasksTheme {
                SearchSurface(emptyList(), emitted::set, {}, {}, {}, listOf(saved))
            }
        }

        composeRule.onNodeWithTag("saved-view-chip-${saved.id.value}").performClick()
        composeRule.onNodeWithTag("workspace-search-query").performTextReplacement("refined")
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(151)
        composeRule.waitForIdle()

        assertEquals(saved.query.copy(text = "refined"), emitted.get())
        composeRule.onNodeWithTag("active-saved-view-${saved.id.value}").assertIsDisplayed()
        composeRule.onNodeWithTag("clear-active-saved-view").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(151)
        composeRule.waitForIdle()
        assertEquals(SearchQuery("refined"), emitted.get())
    }

    @Test
    fun renamingAnActiveBlankViewKeepsItsIdentity() {
        val saved = focusView.copy(
            query = SearchQuery("", dueBuckets = setOf(DueBucket.TODAY)),
        )
        composeRule.setContent {
            OpenTasksTheme {
                var currentViews by remember { mutableStateOf(listOf(saved)) }
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = {},
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    savedViews = currentViews,
                    onRenameView = { id, name ->
                        currentViews = currentViews.map { view ->
                            if (view.id == id) view.copy(name = name) else view
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag("saved-view-chip-${saved.id.value}").performClick()
        composeRule.onNodeWithTag("saved-view-menu-${saved.id.value}").performClick()
        composeRule.onNodeWithTag("saved-view-rename-${saved.id.value}").performClick()
        composeRule.onNodeWithTag("rename-saved-view-name-${saved.id.value}")
            .performTextReplacement("Renamed filters")
        composeRule.onNodeWithTag("rename-saved-view-confirm-${saved.id.value}").performClick()

        composeRule.onNodeWithTag("active-saved-view-${saved.id.value}")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Active view: Renamed filters").assertIsDisplayed()
    }

    @Test
    fun filterControlsToggleSetsAndSortUsesRelevanceForNull() {
        val emitted = AtomicReference<SearchQuery?>()
        composeRule.setContent {
            OpenTasksTheme {
                SearchSurface(emptyList(), emitted::set, {}, {}, {})
            }
        }

        composeRule.onNodeWithTag("search-filter-due")
            .assertContentDescriptionEquals(
                composeRule.runOnIdle {
                    androidx.test.platform.app.InstrumentationRegistry
                        .getInstrumentation().targetContext
                        .getString(R.string.saved_search_filter_due)
                },
            )
            .performClick()
        composeRule.onNodeWithTag("search-due-today").assertIsNotSelected().performClick()
        composeRule.onNodeWithTag("search-filter-due").performClick()
        composeRule.onNodeWithTag("search-due-today").assertIsSelected()
        composeRule.onNodeWithTag("search-due-later").performClick()

        composeRule.onNodeWithTag("search-filter-priority").performClick()
        composeRule.onNodeWithTag("search-priority-high").performClick()
        composeRule.onNodeWithTag("search-filter-priority").performClick()
        composeRule.onNodeWithTag("search-priority-urgent").performClick()

        composeRule.onNodeWithTag("search-filter-status").performClick()
        composeRule.onNodeWithTag("search-status-started").performClick()
        composeRule.onNodeWithTag("search-filter-status").performClick()
        composeRule.onNodeWithTag("search-status-blocked").performClick()

        composeRule.onNodeWithTag("search-sort").performClick()
        composeRule.onNodeWithTag("search-sort-updated").performClick()
        composeRule.onNodeWithTag("search-sort").performClick()
        composeRule.onNodeWithTag("search-sort-updated").assertIsSelected()
        composeRule.onNodeWithTag("search-sort-relevance").assertIsNotSelected().performClick()
        composeRule.onNodeWithTag("search-sort").performClick()
        composeRule.onNodeWithTag("search-sort-relevance").assertIsSelected()

        composeRule.mainClock.advanceTimeBy(151)
        composeRule.waitForIdle()
        assertEquals(
            SearchQuery(
                text = "",
                dueBuckets = setOf(DueBucket.TODAY, DueBucket.LATER),
                priorities = setOf(Priority.HIGH, Priority.URGENT),
                statuses = setOf(SemanticStatus.STARTED, SemanticStatus.BLOCKED),
            ),
            emitted.get(),
        )
    }

    @Test
    fun blankFilterQueryShowsResultsStateAndSavesTheExactQuery() {
        val savedQuery = AtomicReference<SearchQuery?>()
        composeRule.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = {},
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    onSaveView = { _, query -> savedQuery.set(query) },
                )
            }
        }

        composeRule.onNodeWithTag("search-filter-due").performClick()
        composeRule.onNodeWithTag("search-due-today").performClick()

        composeRule.onNodeWithText("Search the whole workspace").assertDoesNotExist()
        composeRule.onNodeWithText("No matching work").assertIsDisplayed()
        composeRule.onNodeWithTag("save-search").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("save-search-name").performTextReplacement("Today")
        composeRule.onNodeWithTag("save-search-confirm").performClick()

        assertEquals(
            SearchQuery("", dueBuckets = setOf(DueBucket.TODAY)),
            savedQuery.get(),
        )
    }

    @Test
    fun saveAffordanceIsAbsentWithoutAnOnSaveViewCallback() {
        composeRule.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = {},
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    savedViews = emptyList(),
                    onSaveView = null,
                )
            }
        }

        composeRule.onNodeWithTag("workspace-search-query").performTextReplacement("shallow work")

        composeRule.onNodeWithTag("save-search").assertDoesNotExist()
    }

    @Test
    fun saveAffordanceAppearsOnlyOnceTheQueryIsNonBlank() {
        composeRule.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = {},
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    savedViews = listOf(focusView, overdueView),
                    onSaveView = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("save-search").assertDoesNotExist()

        composeRule.onNodeWithTag("workspace-search-query").performTextReplacement("shallow work")

        composeRule.onNodeWithTag("save-search").assertIsDisplayed()
    }

    @Test
    fun savingImmediatelyAfterTypingUsesTheUndebouncedQueryText() {
        val savedName = AtomicReference<String?>()
        val savedQuery = AtomicReference<SearchQuery?>()
        val debouncedQuery = AtomicReference<SearchQuery?>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            OpenTasksTheme {
                SearchSurface(
                    results = emptyList(),
                    onQueryChange = debouncedQuery::set,
                    onDismiss = {},
                    onOpenTask = {},
                    onOpenProject = {},
                    savedViews = emptyList(),
                    onSaveView = { name, query ->
                        savedName.set(name)
                        savedQuery.set(query)
                    },
                )
            }
        }

        composeRule.onNodeWithTag("workspace-search-query")
            .performTextReplacement("custom text query")
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("save-search").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("save-search-name").performTextReplacement("My saved search")
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("save-search-confirm").performClick()

        assertEquals("My saved search", savedName.get())
        assertEquals(SearchQuery("custom text query"), savedQuery.get())
        // The 150 ms debounce never advanced: Save did not race it.
        assertNull(debouncedQuery.get())
    }

    /**
     * Pins the un-keyed-`remember` regression: with `focusView` first and
     * its overflow menu left open, an earlier chip disappearing must not
     * leave that open-menu flag attached to whichever chip now occupies
     * its old slot. The drop is driven by a plain test-only button rather
     * than `focusView`'s own delete item, because routing it through that
     * item's own click handler would also close its own menu as part of
     * the same click -- masking exactly the leak this test exists to
     * catch. `savedViews` reordering "underneath" a composed chip this
     * way is the same shape of update `onDeleteView` drives in
     * `OpenTasksApp` (a delete anywhere shifts every later chip's slot).
     */
    @Test
    fun droppingAnEarlierChipDoesNotLeakItsOpenMenuOntoItsNeighbour() {
        composeRule.setContent {
            OpenTasksTheme {
                var currentViews by remember {
                    mutableStateOf(listOf(focusView, overdueView))
                }
                Column {
                    SearchSurface(
                        results = emptyList(),
                        onQueryChange = {},
                        onDismiss = {},
                        onOpenTask = {},
                        onOpenProject = {},
                        savedViews = currentViews,
                    )
                    Button(
                        onClick = { currentViews = currentViews.drop(1) },
                        modifier = Modifier.testTag("test-drop-first-saved-view"),
                    ) {
                        Text("Drop first saved view")
                    }
                }
            }
        }

        composeRule.onNodeWithTag("saved-view-menu-${focusView.id.value}").performClick()
        composeRule.onNodeWithTag("saved-view-rename-${focusView.id.value}").assertIsDisplayed()

        composeRule.onNodeWithTag("test-drop-first-saved-view").performClick()

        composeRule.onNodeWithTag("saved-view-chip-${overdueView.id.value}").assertIsDisplayed()
        composeRule.onNodeWithTag("saved-view-rename-${overdueView.id.value}").assertDoesNotExist()
    }
}
