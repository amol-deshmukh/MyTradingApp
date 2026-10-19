package com.example.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

const val TARGET_PACKAGE = "com.example.myapplication"

private const val LIST_TIMEOUT_MS = 15_000L

/** Blocks until the Markets list shows rows, i.e. data arrived from the mock server. */
fun MacrobenchmarkScope.waitForMarketsList() {
    check(device.wait(Until.hasObject(By.res("stock_row")), LIST_TIMEOUT_MS)) {
        "Markets list never populated. Is the mock server running and `adb reverse tcp:8080 tcp:8080` set? " +
            "Use perf/run-benchmarks.sh."
    }
}

/** Flings the Markets list down then back up. Fixed waits keep the scenario identical across runs. */
fun MacrobenchmarkScope.flingMarketsList(times: Int = 3) {
    val list = device.findObject(By.res("markets_list"))
    // Keep gestures away from the screen edges so they aren't treated as system back/home gestures.
    list.setGestureMargin(device.displayWidth / 5)
    repeat(times) {
        list.fling(Direction.DOWN)
        SystemClock.sleep(FLING_SETTLE_MS)
    }
    repeat(times) {
        list.fling(Direction.UP)
        SystemClock.sleep(FLING_SETTLE_MS)
    }
}

private const val FLING_SETTLE_MS = 800L

/**
 * Main-thread responsiveness metric computed from the Perfetto trace — the leading indicator for
 * ANRs (the system raises an ANR when input isn't handled within 5s).
 *
 * Considers top-level slices on the app's main thread (e.g. Choreographer#doFrame, input,
 * binder calls, activity lifecycle) during the measured block and reports:
 *  - mainThreadLongestSliceMs:          the single longest blocking slice
 *  - mainThreadSlicesOver{N}msCount:    how many slices exceeded the threshold
 *  - mainThreadBlockedOver{N}msTotalMs: total time spent in those long slices
 */
@OptIn(ExperimentalMetricApi::class)
class MainThreadBlockingMetric(private val thresholdMs: Int = 100) : TraceMetric() {
    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val thresholdNs = thresholdMs * 1_000_000L
        val row = traceSession.query(
            """
            SELECT
              COALESCE(MAX(s.dur), 0) AS longest,
              COALESCE(SUM(CASE WHEN s.dur > $thresholdNs THEN 1 ELSE 0 END), 0) AS long_count,
              COALESCE(SUM(CASE WHEN s.dur > $thresholdNs THEN s.dur ELSE 0 END), 0) AS long_total
            FROM slice s
            JOIN thread_track tt ON s.track_id = tt.id
            JOIN thread USING (utid)
            JOIN process USING (upid)
            WHERE process.name = '${captureInfo.targetPackageName}'
              AND thread.tid = process.pid
              AND s.depth = 0
            """.trimIndent(),
        ).first()
        return listOf(
            Measurement("mainThreadLongestSliceMs", row.long("longest") / 1e6),
            Measurement("mainThreadSlicesOver${thresholdMs}msCount", row.long("long_count").toDouble()),
            Measurement("mainThreadBlockedOver${thresholdMs}msTotalMs", row.long("long_total") / 1e6),
        )
    }
}

/**
 * Fails the test if the app recorded a new ANR or crash while it ran. Reads the system DropBox,
 * which is where ActivityManager stores ANR/crash reports.
 */
class NoAnrOrCrashRule(private val packageName: String = TARGET_PACKAGE) : TestRule {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val anrsBefore = count("data_app_anr")
            val crashesBefore = count("data_app_crash")
            base.evaluate()
            val newAnrs = count("data_app_anr") - anrsBefore
            val newCrashes = count("data_app_crash") - crashesBefore
            check(newAnrs <= 0 && newCrashes <= 0) {
                "${description.methodName}: $newAnrs new ANR(s), $newCrashes new crash(es) in $packageName. " +
                    "Inspect with: adb shell dumpsys dropbox --print data_app_anr"
            }
        }
    }

    private fun count(tag: String): Int =
        device.executeShellCommand("dumpsys dropbox --print $tag")
            .split("Process: $packageName").size - 1
}
