package app.opentasks.feature.more

import android.view.View
import android.widget.TimePicker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.model.OpenTasksFixtures
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DailyDigestSettingsInstrumentedTest {
    private val composeRule = createComposeRule()

    // HideWindowsRule runs inside the compose rule so a renderer-driven
    // redraw loop cannot starve the rule's final waitForIdleSync.
    @get:Rule
    val testRules: RuleChain = RuleChain.outerRule(composeRule).around(HideWindowsRule())

    @Test
    fun switchInvokesOptInAndShows0800() {
        val optIn = AtomicReference<Boolean?>()

        composeRule.setContent {
            var enabled by remember { mutableStateOf(false) }
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = OpenTasksFixtures.snapshot.projects,
                    onRestoreProject = {},
                    onRestoreTask = {},
                    onPermanentlyDeleteTask = {},
                    dailyDigestEnabled = enabled,
                    onDailyDigestEnabledChange = {
                        optIn.set(it)
                        enabled = it
                    },
                )
            }
        }

        // Off is the default: nothing is scheduled until the switch is used.
        scrollOverviewTo(hasTestTag("daily-digest-enable"))
        composeRule.onNodeWithTag("daily-digest-time").assertDoesNotExist()
        composeRule.onNodeWithTag("daily-digest-enable")
            .assertHeightIsAtLeast(48.dp)
            .assertIsOff()
            .performClick()

        assertEquals(true, optIn.get())
        composeRule.onNodeWithTag("daily-digest-enable").assertIsOn()
        scrollOverviewTo(hasTestTag("daily-digest-time"))
        composeRule.onNodeWithTag("daily-digest-time")
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains("08:00", substring = true)
    }

    @Test
    fun timeButtonUses24HourPickerAndReturnsMinuteOfDay() {
        val minuteOfDay = AtomicInteger(-1)
        val twentyFourHourPicker = AtomicBoolean(false)

        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = OpenTasksFixtures.snapshot.projects,
                    onRestoreProject = {},
                    onRestoreTask = {},
                    onPermanentlyDeleteTask = {},
                    dailyDigestEnabled = true,
                    onDailyDigestMinuteOfDayChange = minuteOfDay::set,
                )
            }
        }

        scrollOverviewTo(hasTestTag("daily-digest-time"))
        composeRule.onNodeWithTag("daily-digest-time")
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains("08:00", substring = true)
            .performClick()

        onView(isAssignableFrom(TimePicker::class.java))
            .inRoot(isDialog())
            .perform(setPickerTime(hour = 21, minute = 45, is24Hour = twentyFourHourPicker))

        assertTrue(twentyFourHourPicker.get())
        assertEquals(21 * 60 + 45, minuteOfDay.get())
    }

    @Test
    fun notificationGuidanceAppearsOnlyWhenEnabledAndUnavailable() {
        val enableRequests = AtomicInteger(0)
        var notificationsEnabled by mutableStateOf(false)

        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = OpenTasksFixtures.snapshot.projects,
                    onRestoreProject = {},
                    onRestoreTask = {},
                    onPermanentlyDeleteTask = {},
                    dailyDigestEnabled = true,
                    dailyDigestNotificationsEnabled = notificationsEnabled,
                    onEnableNotifications = { enableRequests.incrementAndGet() },
                )
            }
        }

        scrollOverviewTo(
            hasText("Notifications are switched off, so the digest cannot be delivered."),
        )
        composeRule.onNodeWithText(
            "Notifications are switched off, so the digest cannot be delivered.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("daily-digest-enable-notifications")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, enableRequests.get())

        // A refused or revoked notification never turns the digest off, and
        // never hides its time.
        composeRule.onNodeWithTag("daily-digest-enable").assertIsOn()
        composeRule.onNodeWithTag("daily-digest-time").assertExists()

        composeRule.runOnIdle { notificationsEnabled = true }

        composeRule.onNodeWithTag("daily-digest-enable-notifications").assertDoesNotExist()
        composeRule.onNodeWithTag("daily-digest-enable").assertIsOn()
        composeRule.onNodeWithTag("daily-digest-time").assertExists()
    }

    @Test
    fun disabledDigestHidesTimeAndGuidance() {
        composeRule.setContent {
            OpenTasksTheme {
                MoreScreen(
                    tasks = emptyList(),
                    projects = OpenTasksFixtures.snapshot.projects,
                    onRestoreProject = {},
                    onRestoreTask = {},
                    onPermanentlyDeleteTask = {},
                    dailyDigestEnabled = false,
                    dailyDigestNotificationsEnabled = false,
                )
            }
        }

        scrollOverviewTo(hasTestTag("daily-digest-enable"))
        composeRule.onNodeWithTag("daily-digest-enable")
            .assertIsOff()
        composeRule.onNodeWithTag("daily-digest-time").assertDoesNotExist()
        composeRule.onNodeWithTag("daily-digest-enable-notifications").assertDoesNotExist()
    }

    private fun scrollOverviewTo(matcher: SemanticsMatcher) {
        composeRule.onNodeWithTag("more-overview").performScrollToNode(matcher)
    }

    private fun setPickerTime(hour: Int, minute: Int, is24Hour: AtomicBoolean): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> =
                isAssignableFrom(TimePicker::class.java)

            override fun getDescription() = "set the time picker to $hour:$minute"

            override fun perform(uiController: UiController, view: View) {
                val picker = view as TimePicker
                is24Hour.set(picker.is24HourView)
                picker.hour = hour
                picker.minute = minute
                val positiveButton = checkNotNull(
                    picker.rootView.findViewById<View>(android.R.id.button1),
                )
                check(positiveButton.performClick())
                uiController.loopMainThreadUntilIdle()
            }
        }
}
