@file:OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)

package app.opentasks.benchmark

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.EditText
import android.widget.TextView
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class OpenTasksMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun welcomeColdFullyDrawn() = startup(null, StartupMode.COLD)
    @Test fun welcomeWarmFullyDrawn() = startup(null, StartupMode.WARM)
    @Test fun emptyColdFullyDrawn() = startup(0, StartupMode.COLD)
    @Test fun emptyWarmFullyDrawn() = startup(0, StartupMode.WARM)
    @Test fun tasks500ColdFullyDrawn() = startup(500, StartupMode.COLD)
    @Test fun tasks500WarmFullyDrawn() = startup(500, StartupMode.WARM)
    @Test fun tasks5000ColdFullyDrawn() = startup(5_000, StartupMode.COLD)
    @Test fun tasks5000WarmFullyDrawn() = startup(5_000, StartupMode.WARM)

    @Test
    fun homeTasksInsightsFrameTiming() {
        prepareDataset(5_000)
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = ITERATIONS,
            setupBlock = { restartPreparedApp() },
        ) {
            tapText("Tasks")
            waitForText("Benchmark task 00000")
            tapText("More")
            waitForText("Open Insights")
            tapText("Insights")
            waitForDescription("Back")
        }
    }

    @Test
    fun latestSearchAt5000() {
        prepareDataset(5_000)
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(TraceSectionMetric(SEARCH_TRACE, TraceSectionMetric.Mode.First)),
            compilationMode = CompilationMode.Partial(),
            iterations = ITERATIONS,
            setupBlock = {
                restartPreparedApp()
                tapDescription("Search workspace")
                waitForEditText()
            },
        ) {
            val query = "Benchmark task 04999"
            waitForEditText().setText(query)
            requireNotNull(
                device.wait(
                    Until.findObject(By.clazz(TextView::class.java).text(query)),
                    TIMEOUT_MILLIS,
                ),
            ) { "Timed out waiting for search result '$query'" }
        }
    }

    @Test
    fun insightsFilterAt5000() {
        prepareDataset(5_000)
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(TraceSectionMetric(INSIGHTS_TRACE, TraceSectionMetric.Mode.First)),
            compilationMode = CompilationMode.Partial(),
            iterations = ITERATIONS,
            setupBlock = {
                restartPreparedApp()
                openInsights()
            },
        ) {
            val label = "Benchmark project 0"
            ensureCheckedLabel(label)
        }
    }

    @Test fun aggregateDashboardAt5000() = dashboard(includeDetails = false)
    @Test fun detailDashboardAt5000() = dashboard(includeDetails = true)

    @Test
    fun fixtureIsSignatureProtectedAndRejectsInvalidRequests() {
        val packageManager = context.packageManager
        val receiver = packageManager.getReceiverInfo(
            ComponentName(PACKAGE_NAME, RECEIVER_CLASS),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertEquals(FIXTURE_PERMISSION, receiver.permission)
        val permission = packageManager.getPermissionInfo(
            FIXTURE_PERMISSION,
            0,
        )
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            permission.protection and PermissionInfo.PROTECTION_MASK_BASE,
        )
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            packageManager.checkPermission(FIXTURE_PERMISSION, context.packageName),
        )
        assertEquals(RESULT_REJECTED, sendFixture("wrong.action", 500))
        assertEquals(RESULT_REJECTED, sendFixture(FIXTURE_ACTION, 499))
        assertEquals(RESULT_REJECTED, sendFixture(FIXTURE_ACTION, 5_001))
    }

    private fun startup(datasetSize: Int?, startupMode: StartupMode) {
        if (datasetSize == null) clearTarget() else prepareDataset(datasetSize)
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = startupMode,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }
    }

    private fun dashboard(includeDetails: Boolean) {
        prepareDataset(5_000)
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(TraceSectionMetric(DASHBOARD_TRACE, TraceSectionMetric.Mode.First)),
            compilationMode = CompilationMode.Partial(),
            iterations = ITERATIONS,
            setupBlock = {
                restartPreparedApp()
                openInsights()
                ensureCheckedLabel("Generate executive dashboard")
                waitForText("Include task details")
                if (includeDetails) {
                    ensureCheckedLabel("Include task details")
                }
                scrollToText("Share HTML")
            },
        ) {
            device.findObject(By.text("Share HTML")).click()
            waitUntil { device.currentPackageName != PACKAGE_NAME }
        }
    }

    private fun prepareDataset(size: Int) {
        clearTarget()
        launchTarget()
        waitForText("Welcome to Open Tasks")
        tapText("Continue offline")
        waitForDescription("Search workspace")
        if (size != 0) assertEquals(RESULT_SEEDED, sendFixture(FIXTURE_ACTION, size))
        device.executeShellCommand("am force-stop $PACKAGE_NAME")
        device.pressHome()
    }

    private fun MacrobenchmarkScope.restartPreparedApp() {
        pressHome()
        killProcess()
        startActivityAndWait()
        waitForDescription("Search workspace")
    }

    private fun MacrobenchmarkScope.openInsights() {
        tapText("More")
        waitForText("Open Insights")
        tapText("Insights")
        waitForDescription("Back")
        waitForText("Projects")
        waitUntil { !device.hasObject(By.text("No projects available")) }
    }

    private fun clearTarget() {
        device.executeShellCommand("pm clear $PACKAGE_NAME")
        device.pressHome()
    }

    private fun launchTarget() {
        device.executeShellCommand("am start -W $PACKAGE_NAME/.MainActivity")
    }

    private fun sendFixture(action: String, size: Int): Int {
        val result = AtomicInteger(Int.MIN_VALUE)
        val completed = CountDownLatch(1)
        context.sendOrderedBroadcast(
            Intent(action)
                .setComponent(ComponentName(PACKAGE_NAME, RECEIVER_CLASS))
                .putExtra(FIXTURE_SIZE_EXTRA, size),
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    result.set(resultCode)
                    completed.countDown()
                }
            },
            Handler(Looper.getMainLooper()),
            0,
            null,
            null,
        )
        assertTrue("Fixture receiver timed out", completed.await(2, TimeUnit.MINUTES))
        return result.get()
    }

    private fun tapText(text: String) = waitForText(text).click()
    private fun tapDescription(description: String) = waitForDescription(description).click()

    private fun waitForText(text: String) =
        requireNotNull(device.wait(Until.findObject(By.text(text)), TIMEOUT_MILLIS)) {
            "Timed out waiting for text '$text'"
        }

    private fun waitForDescription(description: String) =
        requireNotNull(device.wait(Until.findObject(By.desc(description)), TIMEOUT_MILLIS)) {
            "Timed out waiting for description '$description'"
        }

    private fun waitForEditText() =
        requireNotNull(
            device.wait(Until.findObject(By.clazz(EditText::class.java)), TIMEOUT_MILLIS),
        ) { "Timed out waiting for editable text field" }

    private fun ensureCheckedLabel(label: String) {
        scrollToText(label)
        val checked = By.checked(true).hasDescendant(By.text(label))
        repeat(3) {
            if (device.hasObject(checked)) return
            device.waitForIdle()
            SystemClock.sleep(100)
            requireNotNull(
                device.findObject(By.checkable(true).hasDescendant(By.text(label))),
            ) { "No checkable control contains '$label'" }.click()
            if (device.wait(Until.hasObject(checked), 2_000)) return
        }
        error("Timed out waiting for checked '$label'")
    }

    private fun scrollToText(text: String): androidx.test.uiautomator.UiObject2 {
        repeat(20) {
            val width = device.displayWidth
            val height = device.displayHeight
            device.findObject(By.text(text))?.let { target ->
                if (target.visibleBounds.centerY() in height / 10..height * 5 / 6) {
                    return target
                }
            }
            device.swipe(width / 2, height * 4 / 5, width / 2, height / 5, 12)
        }
        error("Timed out scrolling to text '$text'")
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (!predicate() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
        }
        check(predicate()) { "Timed out waiting for benchmark action" }
    }

    private companion object {
        const val PACKAGE_NAME = "app.opentasks"
        const val RECEIVER_CLASS = "app.opentasks.benchmark.BenchmarkFixtureReceiver"
        const val FIXTURE_ACTION = "app.opentasks.action.BENCHMARK_FIXTURE"
        const val FIXTURE_PERMISSION = "app.opentasks.permission.BENCHMARK_FIXTURE"
        const val FIXTURE_SIZE_EXTRA = "size"
        const val RESULT_SEEDED = 1
        const val RESULT_REJECTED = 2
        const val SEARCH_TRACE = "OpenTasks.Search"
        const val INSIGHTS_TRACE = "OpenTasks.Insights"
        const val DASHBOARD_TRACE = "OpenTasks.Dashboard"
        const val ITERATIONS = 10
        const val TIMEOUT_MILLIS = 60_000L
    }
}
