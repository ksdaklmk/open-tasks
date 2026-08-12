package app.opentasks

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityQuickAddInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun warmQuickAddActionReusesMainActivityAndOpensSheet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val created = AtomicInteger()
        val resumed = AtomicReference<MainActivity>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is MainActivity) created.incrementAndGet()
            }

            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) resumed.set(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        var scenario: ActivityScenario<MainActivity>? = null
        val first = AtomicReference<MainActivity>()
        val scenarioIntent = Intent(context, MainActivity::class.java)
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            scenario = ActivityScenario.launch(scenarioIntent)
            scenario.onActivity(first::set)
            waitForWorkspace()
            composeRule.onNodeWithTag("quick-add-title").assertDoesNotExist()

            scenario.onActivity { activity ->
                activity.startActivity(
                    Intent(activity, MainActivity::class.java)
                        .setAction(MainActivity.QUICK_ADD_ACTION),
                )
            }

            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag("quick-add-title")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("quick-add-title").assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.InputText,
                    AnnotatedString(""),
                ),
            )
            assertEquals(1, created.get())
            assertSame(first.get(), resumed.get())
        } finally {
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    resumed.get()?.takeUnless { it === first.get() }?.finish()
                    // onNewIntent replaces the activity's intent; restore the
                    // launch token so ActivityScenario observes final teardown.
                    first.get()?.intent = scenarioIntent
                }
                scenario?.close()
            } finally {
                application.unregisterActivityLifecycleCallbacks(callbacks)
            }
        }
    }

    private fun waitForWorkspace() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("recovery-shell").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Quick add task")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        if (
            composeRule.onAllNodesWithTag("recovery-shell").fetchSemanticsNodes().isNotEmpty()
        ) {
            composeRule.onNodeWithText("Start without restoring").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithContentDescription("Quick add task")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
