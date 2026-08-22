#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C

sdk="${OPEN_TASKS_ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"

fail() { echo "verify-release-apk FAIL: $1" >&2; exit 1; }

expected_cert="${OPEN_TASKS_RELEASE_CERT_SHA256:-}"
case "$expected_cert" in
  ""|*[!0-9A-Fa-f]*) fail "OPEN_TASKS_RELEASE_CERT_SHA256 must be 64 hexadecimal characters" ;;
esac
[ "${#expected_cert}" -eq 64 ] \
  || fail "OPEN_TASKS_RELEASE_CERT_SHA256 must be 64 hexadecimal characters"
expected_cert="$(printf '%s' "$expected_cert" | tr 'A-F' 'a-f')"

[ "$#" -eq 3 ] || fail "expected exactly three release APKs"
seen=""
for apk in "$@"; do
  case "${apk##*/}" in
    app-arm64-v8a-release.apk) variant="arm64-v8a" ;;
    app-x86_64-release.apk) variant="x86_64" ;;
    app-universal-release.apk) variant="universal" ;;
    *) fail "unexpected release APK name" ;;
  esac
  case "$seen" in
    *"|$variant|"*) fail "duplicate release APK variant" ;;
  esac
  seen="$seen|$variant|"
  [ -f "$apk" ] || fail "release APK not found"
done

bt="$sdk/build-tools/$(ls "$sdk/build-tools" | sort -V | tail -1)" || fail "failed to locate build-tools"
[ -x "$bt/apksigner" ] || fail "apksigner not found under $bt"
want_name="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)" || fail "failed to extract versionName"
want_code="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)" || fail "failed to extract versionCode"

for apk in "$@"; do
  # 1. Signed with a modern scheme by the owner certificate.
  signing="$("$bt/apksigner" verify --print-certs "$apk" 2>/dev/null)" \
    || fail "apksigner verify failed (unsigned or bad signature)"
  actual_cert="$(printf '%s\n' "$signing" \
    | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p')"
  case "$actual_cert" in
    ""|*$'\n'*|*[!0-9A-Fa-f]*) fail "signer certificate digest absent or ambiguous" ;;
  esac
  [ "${#actual_cert}" -eq 64 ] \
    || fail "signer certificate digest absent or ambiguous"
  actual_cert="$(printf '%s' "$actual_cert" | tr 'A-F' 'a-f')"
  [ "$actual_cert" = "$expected_cert" ] || fail "release APK signer mismatch"

  # 2. Version matches app/build.gradle.kts.
  badging="$("$bt/aapt2" dump badging "$apk")" || fail "aapt2 badging dump failed"
  echo "$badging" | grep -q "versionCode='$want_code' versionName='$want_name'" \
    || fail "version mismatch (expected $want_name/$want_code)"

  # 3. Debug qualification activity absent from the manifest.
  manifest="$("$bt/aapt2" dump xmltree "$apk" --file AndroidManifest.xml)" \
    || fail "aapt2 manifest dump failed"
  echo "$manifest" | grep -q "DriveCreateOnlyQualificationActivity" \
    && fail "debug qualification activity present in release manifest"

  # 4. drive.appdata is the sole Drive scope string in the dex.
  unzip -p "$apk" "classes*.dex" >/dev/null || fail "unzip dex extraction failed"
  # Second extraction feeds grep directly so no NUL bytes pass through a
  # shell variable; an empty result still fails closed on the check below.
  drive_scopes="$(unzip -p "$apk" "classes*.dex" \
    | grep -ao "auth/drive[.a-z]*" | sort -u)" || true
  if echo "$drive_scopes" | grep -v "^auth/drive\.appdata$" | grep -q .; then
    fail "unexpected Drive scope string in release dex"
  fi
  echo "$drive_scopes" | grep -q "^auth/drive\.appdata$" \
    || fail "auth/drive.appdata scope not found in release dex"

  # 5. Not debuggable.
  echo "$badging" | grep -q "application-debuggable" \
    && fail "release APK is debuggable"
done

echo "verify-release-apk: all checks passed"
