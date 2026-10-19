#!/usr/bin/env python3
"""
Compare two macrobenchmark runs (e.g. last release vs. this release).

  perf/compare.py perf/results/v1.0 perf/results/v1.1          # compare
  perf/compare.py perf/results/v1.1                             # summarize one run
  perf/compare.py old.json new.json --threshold 10 --min-abs 2

Each argument can be a results directory or a *benchmarkData.json file. Prints a Markdown table
and exits with code 1 if any metric regressed by more than --threshold percent AND more than
--min-abs in absolute terms (so CI can block the release).

Single-value metrics (e.g. startup) are compared by median. Per-frame metrics (frameDurationCpuMs,
frameOverrunMs) are compared at P50/P90/P99; the high percentiles are where jank shows up.
"""
import argparse
import glob
import json
import os
import sys

# Metrics that describe the workload rather than performance; reported, never flagged.
INFORMATIONAL = {"tickApplyCount", "frameCount"}


def load(path):
    if os.path.isdir(path):
        files = glob.glob(os.path.join(path, "*benchmarkData.json"))
        if not files:
            sys.exit(f"No *benchmarkData.json in {path}")
        path = files[0]
    with open(path) as f:
        data = json.load(f)
    results = {}
    for b in data["benchmarks"]:
        name = b["name"]
        for metric, v in b.get("metrics", {}).items():
            results[(name, metric, "median")] = v["median"]
        for metric, v in b.get("sampledMetrics", {}).items():
            for p in ("P50", "P90", "P99"):
                if p in v:
                    results[(name, metric, p)] = v[p]
    return results, data.get("context", {})


def fmt(v):
    return "–" if v is None else f"{v:.2f}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("baseline")
    ap.add_argument("candidate", nargs="?")
    ap.add_argument("--threshold", type=float, default=10.0, help="regression threshold in percent")
    ap.add_argument("--min-abs", type=float, default=1.0, help="ignore changes smaller than this (ms/count)")
    args = ap.parse_args()

    base, base_ctx = load(args.baseline)
    if not args.candidate:
        print(f"Device: {base_ctx.get('build', {}).get('model', '?')}\n")
        print("| Test | Metric | Stat | Value |\n|---|---|---|---:|")
        for (test, metric, stat), v in sorted(base.items()):
            print(f"| {test} | {metric} | {stat} | {fmt(v)} |")
        return 0

    cand, cand_ctx = load(args.candidate)
    bm = base_ctx.get("build", {}).get("model")
    cm = cand_ctx.get("build", {}).get("model")
    if bm != cm:
        print(f"WARNING: different devices ({bm} vs {cm}); results are not comparable.\n")

    regressions = 0
    print("| Test | Metric | Stat | Baseline | Candidate | Δ | Δ% | |")
    print("|---|---|---|---:|---:|---:|---:|---|")
    for key in sorted(set(base) | set(cand)):
        test, metric, stat = key
        b, c = base.get(key), cand.get(key)
        if b is None or c is None:
            print(f"| {test} | {metric} | {stat} | {fmt(b)} | {fmt(c)} | | | new/removed |")
            continue
        delta = c - b
        pct = (delta / b * 100) if b else (0.0 if delta == 0 else float("inf"))
        status = ""
        if metric not in INFORMATIONAL and abs(delta) >= args.min_abs:
            # Every non-informational metric here is "lower is better".
            if pct > args.threshold:
                status, regressions = "🔴 REGRESSION", regressions + 1
            elif pct < -args.threshold:
                status = "🟢 improved"
        print(f"| {test} | {metric} | {stat} | {fmt(b)} | {fmt(c)} | {delta:+.2f} | {pct:+.1f}% | {status} |")

    print(f"\n{regressions} regression(s) over {args.threshold:.0f}% (and ≥ {args.min_abs} absolute).")
    return 1 if regressions else 0


if __name__ == "__main__":
    sys.exit(main())
