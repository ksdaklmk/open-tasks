#!/usr/bin/env bash
set -euo pipefail

apk="${1:-app/build/outputs/apk/release/app-release.apk}"
sdk="$HOME/Library/Android/sdk"

fail() { echo "verify-release-apk FAIL: $1" >&2; exit 1; }

bt="$sdk/build-tools/$(ls "$sdk/build-tools" | sort -V | tail -1)" || fail "failed to locate build-tools"

[ -f "$apk" ] || fail "APK not found at $apk"

# 1. Signed with a modern scheme.
[ -x "$bt/apksigner" ] || fail "apksigner not found under $bt"
"$bt/apksigner" verify "$apk" >/dev/null 2>&1 \
  || fail "apksigner verify failed (unsigned or bad signature)"

# 2. Version matches app/build.gradle.kts.
badging="$("$bt/aapt2" dump badging "$apk")" || fail "aapt2 badging dump failed"
want_name="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)" || fail "failed to extract versionName"
want_code="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)" || fail "failed to extract versionCode"
echo "$badging" | grep -q "versionCode='$want_code' versionName='$want_name'" \
  || fail "version mismatch (expected $want_name/$want_code)"

# 3. Debug qualification activity absent from the manifest.
manifest="$("$bt/aapt2" dump xmltree "$apk" --file AndroidManifest.xml)" || fail "aapt2 manifest dump failed"
echo "$manifest" | grep -q "DriveCreateOnlyQualificationActivity" \
  && fail "debug qualification activity present in release manifest"

# 4. drive.appdata is the sole Drive scope string in the dex.
unzip -p "$apk" "classes*.dex" >/dev/null || fail "unzip dex extraction failed"
# Second extraction feeds grep directly so no NUL bytes pass through a
# shell variable; an empty result still fails closed on the check below.
drive_scopes="$(unzip -p "$apk" "classes*.dex" | grep -ao "auth/drive[.a-z]*" | sort -u)" || true
if echo "$drive_scopes" | grep -v "^auth/drive\.appdata$" | grep -q .; then
  fail "unexpected Drive scope string in release dex"
fi
echo "$drive_scopes" | grep -q "^auth/drive\.appdata$" \
  || fail "auth/drive.appdata scope not found in release dex"

# 5. Not debuggable.
echo "$badging" | grep -q "application-debuggable" \
  && fail "release APK is debuggable"

echo "verify-release-apk: all checks passed"
