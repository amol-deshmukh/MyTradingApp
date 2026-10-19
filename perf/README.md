# Performance tests: Markets list

Macrobenchmark suite (`:benchmark` module) for the stock list screen. Run it for every release on
the same physical device, then compare the results with the previous release.

```bash
perf/run-benchmarks.sh v1.1                          # run the suite, save results to perf/results/v1.1
perf/compare.py perf/results/v1.0 perf/results/v1.1  # Markdown diff; exits 1 on regression
```

The run script starts the mock server with no latency, no injected failures and 300 extra stocks,
sets up `adb reverse`, and runs `:benchmark:connectedBenchmarkAndroidTest` against the minified,
non-debuggable `benchmark` build type.

## What is measured

| Test | Metrics | Catches |
|---|---|---|
| `startupCold` / `startupWarm` | `timeToInitialDisplayMs`, `timeToFullDisplayMs` (list populated, via `ReportDrawnWhen`) | Slow startup, slow first data |
| `scrollJank` | `frameDurationCpuMs`, `frameOverrunMs` (P50/P90/P99), `frameCount` | Scroll jank. Overrun > 0 means a frame missed its deadline |
| `liveUpdatesIdle` | Frame metrics plus `tickDecodeAvg/Max` and `tickApplyAvg/Count` trace sections | Per-tick cost of WebSocket JSON decoding and recomposition |
| All | `mainThreadLongestSliceMs`, `mainThreadSlicesOver100msCount`, `mainThreadBlockedOver100msTotalMs` | Main-thread blocking, the leading indicator for ANRs |
| All | `NoAnrOrCrashRule` | Fails the test if any ANR or crash is recorded in the system DropBox |

Each iteration saves a `.perfetto-trace` file in the results folder. To investigate a regression,
open the trace from both releases in https://ui.perfetto.dev. The custom sections
`PriceStream.decode` and `Repository.applyTicks` appear on the app's threads.

## Getting comparable numbers
- Use the same physical device, OS build and charge state every time. `device.txt` records the
  device, and `compare.py` warns when two runs came from different devices.
- Emulator runs only check that the suite works. The numbers are not meaningful.
- Run at least twice when a change looks borderline. Single runs vary by about 5–10%.
  Tune `--threshold` and `--min-abs` for your device.
- Keep `perf/results/<release>/*.json` under version control (or as CI artifacts) to keep a history.
