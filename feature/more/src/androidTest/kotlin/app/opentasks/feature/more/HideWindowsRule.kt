package app.opentasks.feature.more

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inspector.WindowInspector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Hides every window of the test process after the test body and before
 * `AndroidComposeTestRule`'s teardown, which calls the unbounded
 * `Instrumentation.waitForIdleSync()` in a finally block.
 *
 * On an emulator whose renderer cannot finish a full-window frame inside the
 * display's vsync budget (observed on the headless software-GPU Fold8 image,
 * 53.33 Hz), every `syncAndDrawFrame` returns `SYNC_REDRAW_REQUESTED` and
 * `ThreadedRenderer.draw` re-invalidates the window, so
 * `ViewRootImpl.scheduleTraversals` keeps a sync barrier on the main queue at
 * every instant. The main looper then never idles, ordinary posted messages
 * starve behind the barrier, and `waitForIdleSync` blocks forever — hanging
 * the whole connected suite and swallowing the test's real failure.
 *
 * A hidden window skips drawing entirely, so no redraw is requested, the
 * barrier is not re-posted, and the looper can go idle. The visibility change
 * is posted with an async handler because ordinary main-thread messages do
 * not run while a traversal barrier is pending. On a healthy device this is a
 * no-op moment before the activity is torn down anyway.
 *
 * Duplicated per androidTest source set on purpose: feature modules depend
 * only on `:core:model` and `:core:designsystem`, so there is no shared test
 * artifact to host it.
 *
 * Wire it inside the compose rule so it runs first:
 * `RuleChain.outerRule(composeRule).around(HideWindowsRule())`.
 */
class HideWindowsRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    hideAllWindows()
                }
            }
        }

    private fun hideAllWindows() {
        val hidden = CountDownLatch(1)
        Handler.createAsync(Looper.getMainLooper()).post {
            try {
                WindowInspector.getGlobalWindowViews().forEach { root ->
                    root.visibility = View.GONE
                }
            } finally {
                hidden.countDown()
            }
        }
        try {
            // Best effort with a bound: never replace the test's own failure
            // and never wait without a timeout the way waitForIdleSync does.
            hidden.await(10, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
