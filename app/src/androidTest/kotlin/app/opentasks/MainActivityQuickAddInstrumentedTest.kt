package app.opentasks

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            scenario.onActivity(first::set)
            waitForWorkspace()

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
            assertEquals(1, created.get())
            assertSame(first.get(), resumed.get())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                resumed.get()?.finish()
            }
            application.unregisterActivityLifecycleCallbacks(callbacks)
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
