#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
check="$repo_root/scripts/check-benchmark-thresholds.sh"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/benchmark-threshold-test.XXXXXX")"
trap 'rm -rf -- "$test_dir"' EXIT

make_result() {
    python3 - "$1" "$2" "$3" "$4" <<'PY'
import json
import sys

path, mode, startup, frame_max = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4])

def metric(name, key, values, sampled=False):
    return {
        "name": name,
        "params": {},
        "className": "app.opentasks.benchmark.OpenTasksMacrobenchmark",
        "metrics": {} if sampled else {key: {"runs": values}},
        "sampledMetrics": {key: {"runs": [values]}} if sampled else {},
        "repeatIterations": 10,
    }

benchmarks = []
for name in (
    "welcomeColdFullyDrawn",
    "welcomeWarmFullyDrawn",
    "emptyColdFullyDrawn",
    "emptyWarmFullyDrawn",
    "tasks500ColdFullyDrawn",
    "tasks500WarmFullyDrawn",
    "tasks5000ColdFullyDrawn",
    "tasks5000WarmFullyDrawn",
):
    benchmarks.append(metric(name, "timeToFullDisplayMs", [startup] * 10))

frame_values = (
    [float(value) for value in range(1, 20)] + [frame_max]
    if mode == "percentile"
    else [float(min(value, 16)) for value in range(1, 20)] + [frame_max]
)

benchmarks.extend((
    metric("latestSearchAt5000", "OpenTasks.SearchFirstMs", [100.0] * 10),
    metric("insightsFilterAt5000", "OpenTasks.InsightsFirstMs", [200.0] * 10),
    metric("aggregateDashboardAt5000", "OpenTasks.DashboardFirstMs", [1000.0] * 10),
    metric("detailDashboardAt5000", "OpenTasks.DashboardFirstMs", [4000.0] * 10),
    metric(
        "homeTasksInsightsFrameTiming",
        "frameDurationCpuMs",
        frame_values,
        sampled=True,
    ),
))

if mode == "missing":
    benchmarks[8]["metrics"] = {}
elif mode == "hard":
    benchmarks[8]["metrics"]["OpenTasks.SearchFirstMs"]["runs"][-1] = 151.0

data = {
    "context": {
        "build": {
            "model": "Pixel 6",
            "device": "oriole",
            "fingerprint": "google/oriole/release-keys",
            "version": {"sdk": 36},
        }
    },
    "benchmarks": benchmarks,
}
with open(path, "w", encoding="utf-8") as output:
    json.dump(data, output)
PY
}

expect_status() {
    local expected="$1"
    local label="$2"
    shift 2
    local output status
    set +e
    output="$("$@" 2>&1)"
    status=$?
    set -e
    if [ "$status" -ne "$expected" ]; then
        printf 'FAIL: %s (expected %s, got %s)\n%s\n' "$label" "$expected" "$status" "$output" >&2
        exit 1
    fi
    printf '%s' "$output"
}

current="$test_dir/current.json"
baseline="$test_dir/baseline.json"
make_result "$baseline" pass 100 16
make_result "$current" pass 100 16
output="$(expect_status 0 "passing metrics" "$check" "$current" "$baseline")"

make_result "$current" percentile 100 20
output="$(expect_status 3 "nearest-rank percentiles" "$check" "$current" "$baseline")"
grep -q 'p50=10.000 p95=19.000' <<<"$output"

make_result "$current" missing 100 16
expect_status 2 "missing metric" "$check" "$current" "$baseline" >/dev/null

make_result "$current" pass 110 16
expect_status 0 "exact ten-percent regression" "$check" "$current" "$baseline" >/dev/null

make_result "$current" pass 110.001 16
expect_status 4 "regression above ten percent" "$check" "$current" "$baseline" >/dev/null

make_result "$current" pass 100 700
expect_status 3 "frame at 700 ms" "$check" "$current" "$baseline" >/dev/null

make_result "$current" hard 100 16
expect_status 3 "hard operation threshold" "$check" "$current" "$baseline" >/dev/null

echo "verify-benchmark-threshold-script: all checks passed"
