#!/usr/bin/env bash
set -euo pipefail

invalid() {
    echo "check-benchmark-thresholds FAIL: $1" >&2
    exit 2
}

if [ "$#" -ne 2 ]; then
    invalid "usage: check-benchmark-thresholds.sh CURRENT_JSON ACCEPTED_BASELINE_JSON"
fi
[ -f "$1" ] || invalid "current benchmark JSON does not exist"
[ -f "$2" ] || invalid "accepted baseline JSON does not exist"
command -v python3 >/dev/null 2>&1 || invalid "python3 is required"

python3 - "$1" "$2" <<'PY'
import json
import math
import sys


class Invalid(Exception):
    pass


def load(path):
    try:
        with open(path, encoding="utf-8") as source:
            value = json.load(source)
    except (OSError, json.JSONDecodeError) as failure:
        raise Invalid(f"cannot read {path}: {failure}") from failure
    if not isinstance(value, dict) or not isinstance(value.get("benchmarks"), list):
        raise Invalid(f"{path} is not AndroidX benchmark JSON")
    return value


def profile(data, label):
    build = data.get("context", {}).get("build", {})
    version = build.get("version", {})
    if version.get("sdk") != 36:
        raise Invalid(f"{label} must be captured on API 36")
    model = str(build.get("model", ""))
    device = str(build.get("device", ""))
    fingerprint = str(build.get("fingerprint", ""))
    identity = " ".join((model, device, fingerprint)).lower()
    if not model or not device or any(word in identity for word in ("emulator", "sdk_gphone", "generic")):
        raise Invalid(f"{label} must be captured on a physical device")
    return model, device


def benchmark(data, name):
    matches = [value for value in data["benchmarks"] if value.get("name") == name]
    if len(matches) != 1:
        raise Invalid(f"expected one benchmark named {name}, found {len(matches)}")
    value = matches[0]
    if value.get("repeatIterations", 0) < 10:
        raise Invalid(f"{name} must contain at least 10 repeat iterations")
    return value


def flatten(value):
    if isinstance(value, list):
        result = []
        for child in value:
            result.extend(flatten(child))
        return result
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise Invalid("metric runs must contain only numbers")
    value = float(value)
    if not math.isfinite(value) or value < 0:
        raise Invalid("metric runs must contain finite non-negative numbers")
    return [value]


def runs(value, metric_name, sampled=False):
    collection = value.get("sampledMetrics" if sampled else "metrics", {})
    metric = collection.get(metric_name)
    if not isinstance(metric, dict) or "runs" not in metric:
        raise Invalid(f"{value.get('name')} is missing {metric_name}")
    result = flatten(metric["runs"])
    if not result:
        raise Invalid(f"{value.get('name')} has no {metric_name} samples")
    return result


def trace_runs(value, section):
    suffix = f"{section}FirstMs"
    metrics = value.get("metrics", {})
    keys = [key for key in metrics if key == suffix or key.endswith(f".{suffix}")]
    if len(keys) != 1:
        raise Invalid(f"{value.get('name')} is missing one {suffix} metric")
    return runs(value, keys[0])


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def summary(name, metric, values, baseline_values=None):
    p50 = percentile(values, 0.50)
    p95 = percentile(values, 0.95)
    line = f"{name} {metric} p50={p50:.3f} p95={p95:.3f}"
    if baseline_values is not None:
        line += f" baseline_p95={percentile(baseline_values, 0.95):.3f}"
    print(line)
    return p95


try:
    current = load(sys.argv[1])
    baseline = load(sys.argv[2])
    current_profile = profile(current, "current result")
    baseline_profile = profile(baseline, "accepted baseline")
    if current_profile != baseline_profile:
        raise Invalid("current result and accepted baseline use different device profiles")

    hard_failures = []
    regressions = []
    startup_limits = {
        "welcomeColdFullyDrawn": 1500.0,
        "emptyColdFullyDrawn": 1500.0,
        "tasks500ColdFullyDrawn": 1500.0,
        "tasks5000ColdFullyDrawn": 1500.0,
        "welcomeWarmFullyDrawn": 500.0,
        "emptyWarmFullyDrawn": 500.0,
        "tasks500WarmFullyDrawn": 500.0,
        "tasks5000WarmFullyDrawn": 500.0,
    }
    for name, limit in startup_limits.items():
        current_runs = runs(benchmark(current, name), "timeToFullDisplayMs")
        baseline_runs = runs(benchmark(baseline, name), "timeToFullDisplayMs")
        current_p95 = summary(name, "timeToFullDisplayMs", current_runs, baseline_runs)
        baseline_p95 = percentile(baseline_runs, 0.95)
        if current_p95 > limit:
            hard_failures.append(f"{name} p95 {current_p95:.3f} ms > {limit:.1f} ms")
        if current_p95 > baseline_p95 * 1.10:
            regressions.append(
                f"{name} p95 {current_p95:.3f} ms > 110% of baseline {baseline_p95:.3f} ms"
            )

    operation_limits = (
        ("latestSearchAt5000", "Search", 150.0),
        ("insightsFilterAt5000", "Insights", 300.0),
        ("aggregateDashboardAt5000", "Dashboard", 2000.0),
        ("detailDashboardAt5000", "Dashboard", 5000.0),
    )
    for name, section, limit in operation_limits:
        values = trace_runs(benchmark(current, name), section)
        p95 = summary(name, f"OpenTasks.{section}FirstMs", values)
        if p95 > limit:
            hard_failures.append(f"{name} p95 {p95:.3f} ms > {limit:.1f} ms")

    frame = benchmark(current, "homeTasksInsightsFrameTiming")
    frame_values = runs(frame, "frameDurationCpuMs", sampled=True)
    frame_p95 = summary(
        "homeTasksInsightsFrameTiming",
        "frameDurationCpuMs",
        frame_values,
    )
    if frame_p95 > 16.7:
        hard_failures.append(f"frame CPU p95 {frame_p95:.3f} ms > 16.7 ms")
    if max(frame_values) >= 700.0:
        hard_failures.append(f"frame CPU maximum {max(frame_values):.3f} ms >= 700 ms")

    if hard_failures:
        for failure in hard_failures:
            print(f"check-benchmark-thresholds FAIL: {failure}", file=sys.stderr)
        sys.exit(3)
    if regressions:
        for failure in regressions:
            print(f"check-benchmark-thresholds REGRESSION: {failure}", file=sys.stderr)
        sys.exit(4)
    print("check-benchmark-thresholds: all checks passed")
except Invalid as failure:
    print(f"check-benchmark-thresholds FAIL: {failure}", file=sys.stderr)
    sys.exit(2)
PY
