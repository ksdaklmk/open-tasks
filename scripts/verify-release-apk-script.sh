#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
verify="$repo_root/scripts/verify-release-apk.sh"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/verify-release-apk.XXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT

sdk_root="$test_root/android-sdk"
build_tools="$sdk_root/build-tools/1.0.0"
fake_bin="$test_root/bin"
fixtures="$test_root/fixtures"
mkdir -p "$build_tools" "$fake_bin" "$fixtures"

matching_actual="$(printf 'a%.0s' {1..64})"
matching_input="$(printf 'A%.0s' {1..64})"
mismatching_actual="$(printf 'b%.0s' {1..64})"
short_input="$(printf 'A%.0s' {1..63})"
non_hex_input="${short_input}G"
version_code="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$repo_root/app/build.gradle.kts")"
version_name="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$repo_root/app/build.gradle.kts")"

cat > "$build_tools/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$#" -eq 3 ] && [ "$1" = verify ] && [ "$2" = --print-certs ] || exit 2
apk="$3"
[ "$(sed -n 's/^signature=//p' "$apk")" = valid ] || exit 1
digest="$(sed -n 's/^digest=//p' "$apk")"
case "$(sed -n 's/^digest_mode=//p' "$apk")" in
    valid)
        printf 'Signer #1 certificate SHA-256 digest: %s\n' "$digest"
        ;;
    ambiguous)
        printf 'Signer #1 certificate SHA-256 digest: %s\n' "$digest"
        printf 'Signer #2 certificate SHA-256 digest: %s\n' "$FAKE_OTHER_DIGEST"
        ;;
    absent)
        printf 'Signer #1 certificate DN: CN=fixture\n'
        ;;
esac
EOF

cat > "$build_tools/aapt2" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
apk="$3"
case "$2" in
    badging)
        if [ "$(sed -n 's/^metadata=//p' "$apk")" = valid ]; then
            printf "package: name='app.opentasks' versionCode='%s' versionName='%s'\n" \
                "$FAKE_VERSION_CODE" "$FAKE_VERSION_NAME"
        else
            printf "package: name='app.opentasks' versionCode='0' versionName='0.0.0'\n"
        fi
        [ "$(sed -n 's/^debuggable=//p' "$apk")" = no ] \
            || printf 'application-debuggable\n'
        ;;
    xmltree)
        if [ "$(sed -n 's/^debug_activity=//p' "$apk")" = absent ]; then
            printf 'E: manifest\n'
        else
            printf 'DriveCreateOnlyQualificationActivity\n'
        fi
        ;;
    *)
        exit 2
        ;;
esac
EOF

cat > "$fake_bin/unzip" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$1" = -p ] || exit 2
case "$(sed -n 's/^scope=//p' "$2")" in
    valid) printf 'https://www.googleapis.com/auth/drive.appdata\n' ;;
    unexpected) printf 'https://www.googleapis.com/auth/drive.file\n' ;;
    absent) printf 'classes fixture\n' ;;
esac
EOF

chmod +x "$build_tools/apksigner" "$build_tools/aapt2" "$fake_bin/unzip"

arm64="$fixtures/app-arm64-v8a-release.apk"
x86="$fixtures/app-x86_64-release.apk"
universal="$fixtures/app-universal-release.apk"
unexpected="$fixtures/app-armeabi-v7a-release.apk"

write_fixture() {
    printf '%s\n' \
        "signature=$2" \
        "digest_mode=$3" \
        "digest=$4" \
        "metadata=$5" \
        "debug_activity=$6" \
        "scope=$7" \
        "debuggable=$8" > "$1"
}

reset_fixtures() {
    write_fixture "$arm64" valid valid "$matching_actual" valid absent valid no
    write_fixture "$x86" valid valid "$matching_actual" valid absent valid no
    write_fixture "$universal" valid valid "$matching_actual" valid absent valid no
    write_fixture "$unexpected" valid valid "$matching_actual" valid absent valid no
}

run_without_cert() (
    unset OPEN_TASKS_RELEASE_CERT_SHA256
    export OPEN_TASKS_ANDROID_SDK_ROOT="$sdk_root"
    export PATH="$fake_bin:$PATH"
    export FAKE_OTHER_DIGEST="$mismatching_actual"
    export FAKE_VERSION_CODE="$version_code"
    export FAKE_VERSION_NAME="$version_name"
    cd "$repo_root"
    bash "$verify" "$@"
)

run_with_cert() (
    export OPEN_TASKS_RELEASE_CERT_SHA256="$1"
    shift
    export OPEN_TASKS_ANDROID_SDK_ROOT="$sdk_root"
    export PATH="$fake_bin:$PATH"
    export FAKE_OTHER_DIGEST="$mismatching_actual"
    export FAKE_VERSION_CODE="$version_code"
    export FAKE_VERSION_NAME="$version_name"
    cd "$repo_root"
    bash "$verify" "$@"
)

expect_status_without_leak() {
    local expected="$1"
    local label="$2"
    shift 2
    local sensitive_tokens=()
    while [ "$1" != "--" ]; do
        sensitive_tokens+=("$1")
        shift
    done
    shift
    local output normalized_output sensitive normalized_sensitive status
    set +e
    output="$("$@" 2>&1)"
    status=$?
    set -e
    if [ "$status" -ne "$expected" ]; then
        printf 'FAIL: %s expected status %s, got %s\n' \
            "$label" "$expected" "$status" >&2
        exit 1
    fi
    normalized_output="$(printf '%s' "$output" | tr '[:upper:]' '[:lower:]')"
    for sensitive in "${sensitive_tokens[@]}"; do
        normalized_sensitive="$(printf '%s' "$sensitive" \
            | tr '[:upper:]' '[:lower:]')"
        case "$normalized_output" in
            *"$normalized_sensitive"*)
                printf 'FAIL: %s disclosed certificate material\n' "$label" >&2
                exit 1
                ;;
        esac
    done
}

reset_fixtures
expect_status_without_leak 1 "missing owner certificate" \
    "$matching_actual" -- \
    run_without_cert "$arm64" "$x86" "$universal"
expect_status_without_leak 1 "short owner certificate" \
    "$short_input" "$matching_actual" -- \
    run_with_cert "$short_input" "$arm64" "$x86" "$universal"
expect_status_without_leak 1 "non-hexadecimal owner certificate" \
    "$non_hex_input" "$matching_actual" -- \
    run_with_cert "$non_hex_input" "$arm64" "$x86" "$universal"

reset_fixtures
write_fixture "$universal" valid valid "$mismatching_actual" valid absent valid no
expect_status_without_leak 1 "mismatched signer" \
    "$matching_input" "$matching_actual" "$mismatching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$x86" "$universal"

reset_fixtures
expect_status_without_leak 1 "missing variant" \
    "$matching_input" "$matching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$universal"
expect_status_without_leak 1 "duplicate variant" \
    "$matching_input" "$matching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$x86" "$x86"
expect_status_without_leak 1 "unexpected variant" \
    "$matching_input" "$matching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$x86" "$unexpected"

expect_status_without_leak 0 "three authenticated variants" \
    "$matching_input" "$matching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$x86" "$universal"

write_fixture "$universal" invalid valid "$matching_actual" valid absent valid no
expect_status_without_leak 1 "invalid signature on universal" \
    "$matching_input" "$matching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$x86" "$universal"

reset_fixtures
write_fixture "$universal" valid absent "$matching_actual" valid absent valid no
expect_status_without_leak 1 "absent signer digest on universal" \
    "$matching_input" "$matching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$x86" "$universal"
write_fixture "$universal" valid ambiguous "$matching_actual" valid absent valid no
expect_status_without_leak 1 "ambiguous signer digest on universal" \
    "$matching_input" "$matching_actual" "$mismatching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$x86" "$universal"

reset_fixtures
write_fixture "$universal" valid valid "$matching_actual" invalid absent valid no
expect_status_without_leak 1 "version failure on universal" \
    "$matching_input" "$matching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$x86" "$universal"
write_fixture "$universal" valid valid "$matching_actual" valid present valid no
expect_status_without_leak 1 "debug activity on universal" \
    "$matching_input" "$matching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$x86" "$universal"
write_fixture "$universal" valid valid "$matching_actual" valid absent unexpected no
expect_status_without_leak 1 "unexpected Drive scope on universal" \
    "$matching_input" "$matching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$x86" "$universal"
write_fixture "$universal" valid valid "$matching_actual" valid absent valid yes
expect_status_without_leak 1 "debuggable universal" \
    "$matching_input" "$matching_actual" -- \
    run_with_cert "$matching_input" "$arm64" "$x86" "$universal"

echo "verify-release-apk-script: all checks passed"
