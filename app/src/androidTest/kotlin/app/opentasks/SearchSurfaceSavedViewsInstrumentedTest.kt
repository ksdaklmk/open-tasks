package app.opentasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.SavedView
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.TagId
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
        composeRule.mainClock.advanceTimeBy(150)
        composeRule.waitForIdle()

        assertEquals(focusView.query, captured.get())
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
        composeRule.onNodeWithTag("save-search").performClick()
        composeRule.onNodeWithTag("save-search-name").performTextReplacement("My saved search")
        composeRule.onNodeWithTag("save-search-confirm").performClick()

        assertEquals("My saved search", savedName.get())
        assertEquals(SearchQuery("custom text query"), savedQuery.get())
        // The 150 ms debounce never advanced: Save did not race it.
        assertNull(debouncedQuery.get())
    }
}
