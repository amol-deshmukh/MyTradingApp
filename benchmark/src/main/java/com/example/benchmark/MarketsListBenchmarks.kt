package com.example.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Performance suite for the Markets (stock list) screen. Run for every release and compare the
 * JSON output with perf/compare.py. Every iteration also saves a Perfetto trace for deep dives
 * (open in https://ui.perfetto.dev).
 *
 * All tests use the same compilation mode (baseline profile if present) so results are comparable
 * between releases.
 */
@RunWith(AndroidJUnit4::class)
class MarketsListBenchmarks {
    private val benchmarkRule = MacrobenchmarkRule()

    // ANR/crash check wraps the whole benchmark, so any ANR during any iteration fails the test.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(NoAnrOrCrashRule()).around(benchmarkRule)

    private val compilation = CompilationMode.DEFAULT

    /** Cold start → list visible. timeToInitialDisplayMs = first frame; timeToFullDisplayMs = rows loaded. */
    @Test
    fun startupCold() = startup(StartupMode.COLD)

    /** Warm start (process alive, activity recreated). */
    @Test
    fun startupWarm() = startup(StartupMode.WARM)

    private fun startup(mode: StartupMode) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), MainThreadBlockingMetric()),
        compilationMode = compilation,
        startupMode = mode,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitForMarketsList()
    }

    /** Scroll jank: frame durations/overruns while flinging the list with live prices streaming in. */
    @Test
    fun scrollJank() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), MainThreadBlockingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForMarketsList()
        },
    ) {
        flingMarketsList()
    }

    /**
     * Idle-with-live-updates: the list sits still while the WebSocket pushes ticks every second.
     * Catches regressions in recomposition cost per tick, JSON decode cost, and per-frame work
     * from animations (price flash, LIVE indicator).
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun liveUpdatesIdle() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MainThreadBlockingMetric(),
            TraceSectionMetric("PriceStream.decode", TraceSectionMetric.Mode.Average, label = "tickDecodeAvg"),
            TraceSectionMetric("PriceStream.decode", TraceSectionMetric.Mode.Max, label = "tickDecodeMax"),
            TraceSectionMetric("Repository.applyTicks", TraceSectionMetric.Mode.Average, label = "tickApplyAvg"),
            TraceSectionMetric("Repository.applyTicks", TraceSectionMetric.Mode.Count, label = "tickApplyCount"),
        ),
        compilationMode = compilation,
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForMarketsList()
        },
    ) {
        SystemClock.sleep(10_000)
    }
}
