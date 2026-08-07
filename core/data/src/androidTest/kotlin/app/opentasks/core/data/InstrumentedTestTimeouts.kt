package app.opentasks.core.data

/**
 * Bound for `withTimeout` around suspend calls in `:core:data` instrumented tests.
 *
 * The 5 s bound used by the repo's JVM unit-test convention
 * (`runBlocking` + `withTimeout(5_000)`) was written for JVM tests; it is too tight for
 * real Room transactions on a loaded CI emulator (software-rendered, 2 cores). This
 * constant only widens the wall-clock budget for device tests — it does not change the
 * JVM unit-test convention, which keeps its own 5,000 ms bound.
 */
internal const val DEVICE_TEST_TIMEOUT_MILLIS = 30_000L
