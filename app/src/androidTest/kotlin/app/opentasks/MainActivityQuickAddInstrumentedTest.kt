package app.opentasks

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityQuickAddInstrumentedTest {
    private val composeRule = createEmptyComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun warmQuickAddActionReusesMainActivityAndDeliversIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val created = AtomicInteger()
        val resumed = AtomicReference<MainActivity>()
        val resumedAction = AtomicReference<String?>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is MainActivity) created.incrementAndGet()
            }

            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    resumed.set(activity)
                    resumedAction.set(activity.intent.action)
                }
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
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag("recovery-shell")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("recovery-shell").assertIsDisplayed()
            composeRule.onNodeWithTag("quick-add-title").assertDoesNotExist()

            scenario.onActivity { activity ->
                activity.startActivity(
                    Intent(activity, MainActivity::class.java)
                        .setAction(MainActivity.QUICK_ADD_ACTION),
                )
            }

            composeRule.waitUntil(timeoutMillis = 10_000) {
                resumed.get() === first.get() &&
                    resumedAction.get() == MainActivity.QUICK_ADD_ACTION
            }
            scenario.onActivity { activity ->
                assertSame(first.get(), activity)
                assertEquals(MainActivity.QUICK_ADD_ACTION, activity.intent.action)
            }
            assertEquals(1, created.get())
            assertSame(first.get(), resumed.get())
            composeRule.onNodeWithTag("recovery-shell").assertIsDisplayed()
            composeRule.onNodeWithTag("quick-add-title").assertDoesNotExist()
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
}
