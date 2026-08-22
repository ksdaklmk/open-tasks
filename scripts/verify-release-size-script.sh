#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
check="$repo_root/scripts/check-release-size.sh"
output_root="$repo_root/app/build/outputs/apk"
mkdir -p "$output_root"
test_dir="$(mktemp -d "$output_root/release-size-test.XXXXXX")"
outside_dir="$(mktemp -d "${TMPDIR:-/tmp}/release-size-outside.XXXXXX")"
trap 'rm -rf -- "$test_dir" "$outside_dir"' EXIT

baseline="$test_dir/baseline.properties"
arm64="$test_dir/app-arm64-v8a-release.apk"
universal="$test_dir/app-universal-release.apk"

write_baseline() {
    printf 'arm64Bytes=%s\nuniversalBytes=%s\n' "$1" "$2" > "$baseline"
}

size_files() {
    truncate -s "$1" "$arm64"
    truncate -s "$2" "$universal"
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
}

run_check() {
    RELEASE_SIZE_BASELINE_FILE="$baseline" "$check" "$@"
}

write_baseline 10000000 15000000
size_files 10000000 15000000
expect_status 2 "missing path" run_check "$test_dir/missing.apk" "$universal"

wrong_extension="$test_dir/not-an-apk.zip"
truncate -s 1 "$wrong_extension"
expect_status 2 "wrong extension" run_check "$wrong_extension" "$universal"

outside_apk="$outside_dir/outside.apk"
truncate -s 1 "$outside_apk"
expect_status 2 "outside output tree" run_check "$outside_apk" "$universal"

write_baseline 10485760 15728640
size_files 10485760 15728640
expect_status 0 "exact hard caps" run_check "$arm64" "$universal"

truncate -s 10485761 "$arm64"
expect_status 3 "one byte over hard cap" run_check "$arm64" "$universal"

write_baseline 10000000 15000000
size_files 10200000 15000000
expect_status 0 "exact two-percent drift" run_check "$arm64" "$universal"

truncate -s 10200001 "$arm64"
expect_status 4 "one byte over two-percent drift" run_check "$arm64" "$universal"

write_baseline 10000000 14000000
size_files 10000000 14256000
expect_status 0 "exact 256000-byte drift" run_check "$arm64" "$universal"

truncate -s 14256001 "$universal"
expect_status 4 "one byte over 256000-byte drift" run_check "$arm64" "$universal"

echo "verify-release-size-script: all checks passed"
