package app.opentasks.feature.tasks

import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * artifact to host it. Keep the copies identical apart from the package line.
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
                val roots = WindowInspector.getGlobalWindowViews()
                val visible = roots.filter { it.visibility == View.VISIBLE }
                visible.forEach { root -> root.visibility = View.GONE }
                if (visible.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "Hid ${visible.size} of ${roots.size} window roots: " +
                            visible.joinToString { it.javaClass.simpleName },
                    )
                }
            } catch (failure: Throwable) {
                // A last-ditch teardown guard must never make the run worse:
                // an uncaught main-looper throwable would kill the process.
                Log.w(TAG, "Could not hide the test windows", failure)
            } finally {
                hidden.countDown()
            }
        }
        try {
            // Bounded best effort: never replace the test's own failure and
            // never wait without a timeout the way waitForIdleSync does.
            if (!hidden.await(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "The main thread did not run the hide runnable within 10 seconds")
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val TAG = "HideWindowsRule"
    }
}
