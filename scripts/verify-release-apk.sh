#!/usr/bin/env bash
set -euo pipefail

apk="${1:-app/build/outputs/apk/release/app-release.apk}"
sdk="$HOME/Library/Android/sdk"
bt="$sdk/build-tools/$(ls "$sdk/build-tools" | sort -V | tail -1)"

fail() { echo "verify-release-apk FAIL: $1" >&2; exit 1; }

[ -f "$apk" ] || fail "APK not found at $apk"

# 1. Signed with a modern scheme.
"$bt/apksigner" verify "$apk" >/dev/null 2>&1 \
  || fail "apksigner verify (unsigned or bad signature)"

# 2. Version matches app/build.gradle.kts.
badging="$("$bt/aapt2" dump badging "$apk")"
want_name="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
want_code="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)"
echo "$badging" | grep -q "versionCode='$want_code' versionName='$want_name'" \
  || fail "version mismatch (expected $want_name/$want_code)"

# 3. Debug qualification activity absent from the manifest.
"$bt/aapt2" dump xmltree "$apk" --file AndroidManifest.xml \
  | grep -q "DriveCreateOnlyQualificationActivity" \
  && fail "debug qualification activity present in release manifest"

# 4. drive.appdata is the sole Drive scope string in the dex.
if unzip -p "$apk" "classes*.dex" \
  | grep -ao "auth/drive[.a-z]*" | sort -u \
  | grep -v "^auth/drive\.appdata$" | grep -q .; then
  fail "unexpected Drive scope string in release dex"
fi

# 5. Not debuggable.
echo "$badging" | grep -q "application-debuggable" \
  && fail "release APK is debuggable"

echo "verify-release-apk: all checks passed"
