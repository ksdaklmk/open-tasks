#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
output_root="$repo_root/app/build/outputs/apk"
baseline_file="${RELEASE_SIZE_BASELINE_FILE:-$repo_root/gradle/release-size-baseline.properties}"

invalid() {
    echo "check-release-size FAIL: $1" >&2
    exit 2
}

if [ "$#" -ne 2 ]; then
    invalid "usage: check-release-size.sh ARM64_APK UNIVERSAL_APK"
fi
[ -d "$output_root" ] || invalid "APK output tree does not exist"
[ -f "$baseline_file" ] || invalid "accepted baseline does not exist"

validate_apk() {
    local input="$1"
    local resolved
    case "$input" in
        *.apk) ;;
        *) invalid "APK path must end in .apk: $input" ;;
    esac
    [ -f "$input" ] || invalid "APK is not a regular file: $input"
    resolved="$(realpath "$input")" || invalid "cannot resolve APK path: $input"
    case "$resolved" in
        "$output_root"/*) ;;
        *) invalid "APK is outside app/build/outputs/apk/: $input" ;;
    esac
    printf '%s' "$resolved"
}

baseline_value() {
    local key="$1"
    local value
    value="$(sed -n "s/^${key}=\([0-9][0-9]*\)$/\1/p" "$baseline_file")"
    case "$value" in
        ''|*[!0-9]*) invalid "baseline property $key must be one positive integer" ;;
    esac
    [ "$value" -gt 0 ] || invalid "baseline property $key must be positive"
    printf '%s' "$value"
}

byte_count() {
    wc -c < "$1" | tr -d '[:space:]'
}

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

absolute() {
    if [ "$1" -lt 0 ]; then
        printf '%s' "$((-1 * $1))"
    else
        printf '%s' "$1"
    fi
}

arm64="$(validate_apk "$1")"
universal="$(validate_apk "$2")"
arm64_baseline="$(baseline_value arm64Bytes)"
universal_baseline="$(baseline_value universalBytes)"
arm64_bytes="$(byte_count "$arm64")"
universal_bytes="$(byte_count "$universal")"
arm64_delta=$((arm64_bytes - arm64_baseline))
universal_delta=$((universal_bytes - universal_baseline))

printf 'arm64 bytes=%s sha256=%s delta=%+d baseline=%s\n' \
    "$arm64_bytes" "$(sha256 "$arm64")" "$arm64_delta" "$arm64_baseline"
printf 'universal bytes=%s sha256=%s delta=%+d baseline=%s\n' \
    "$universal_bytes" "$(sha256 "$universal")" "$universal_delta" "$universal_baseline"

if [ "$arm64_bytes" -gt 10485760 ] || [ "$universal_bytes" -gt 15728640 ]; then
    echo "check-release-size FAIL: release APK exceeds a hard size cap" >&2
    exit 3
fi

arm64_drift="$(absolute "$arm64_delta")"
universal_drift="$(absolute "$universal_delta")"
if [ "$arm64_drift" -gt 256000 ] ||
    [ $((arm64_drift * 100)) -gt $((arm64_baseline * 2)) ] ||
    [ "$universal_drift" -gt 256000 ] ||
    [ $((universal_drift * 100)) -gt $((universal_baseline * 2)) ]; then
    echo "check-release-size REVIEW: size drift exceeds the accepted baseline" >&2
    exit 4
fi

echo "check-release-size: all checks passed"
