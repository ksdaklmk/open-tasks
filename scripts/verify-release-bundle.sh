#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C
umask 077

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/verify-release-bundle.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT

fail() { echo "verify-release-bundle FAIL: $1" >&2; exit 1; }

expected_cert="${OPEN_TASKS_UPLOAD_CERT_SHA256:-}"
case "$expected_cert" in
    ""|*[!0-9A-Fa-f]*)
        fail "OPEN_TASKS_UPLOAD_CERT_SHA256 must be 64 hexadecimal characters"
        ;;
esac
[ "${#expected_cert}" -eq 64 ] \
    || fail "OPEN_TASKS_UPLOAD_CERT_SHA256 must be 64 hexadecimal characters"
expected_cert="$(printf '%s' "$expected_cert" | tr 'A-F' 'a-f')"

[ "$#" -eq 1 ] || fail "expected exactly one release AAB"
aab="$1"
case "$aab" in
    *.aab) ;;
    *) fail "release artifact must have an .aab extension" ;;
esac
[ -f "$aab" ] || fail "release AAB not found"

bundletool="${OPEN_TASKS_BUNDLETOOL_JAR:-}"
[ -f "$bundletool" ] || fail "OPEN_TASKS_BUNDLETOOL_JAR not found"

require_tool() {
    local name="$1" path
    path="$(command -v "$name" 2>/dev/null)" || fail "$name not found"
    [ -x "$path" ] || fail "$name is not executable"
    printf '%s\n' "$path"
}

shasum_tool="$(require_tool shasum)"
java_tool="$(require_tool java)"
jarsigner_tool="$(require_tool jarsigner)"
keytool_tool="$(require_tool keytool)"
unzip_tool="$(require_tool unzip)"
objdump_tool="${OPEN_TASKS_LLVM_OBJDUMP:-\
/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/llvm-objdump}"
[ -x "$objdump_tool" ] || fail "llvm-objdump not found or not executable"

bundletool_hash="$("$shasum_tool" -a 256 "$bundletool" 2>/dev/null)" \
    || fail "failed to hash bundletool JAR"
bundletool_hash="${bundletool_hash%%[[:space:]]*}"
[ "$bundletool_hash" = a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29 ] \
    || fail "bundletool JAR SHA-256 mismatch"

"$java_tool" -jar "$bundletool" validate --bundle="$aab" \
    >"$work_dir/bundletool-validate.out" 2>&1 \
    || fail "bundletool validation failed"

jarsigner_output="$work_dir/jarsigner.out"
"$jarsigner_tool" -verify -verbose -certs "$aab" \
    >"$jarsigner_output" 2>&1 \
    || fail "AAB JAR signature verification failed"
grep -Fq 'jar is unsigned.' "$jarsigner_output" \
    && fail "AAB JAR signature verification failed"
grep -Fq 'jar verified.' "$jarsigner_output" \
    || fail "AAB JAR signature verification failed"

keytool_output="$work_dir/keytool.out"
"$keytool_tool" -printcert -jarfile "$aab" >"$keytool_output" 2>&1 \
    || fail "AAB signer inspection failed"
actual_cert="$(sed -n 's/.*SHA256:[[:space:]]*//p' "$keytool_output")"
case "$actual_cert" in
    ""|*$'\n'*) fail "AAB signer fingerprint absent or ambiguous" ;;
esac
actual_cert="$(printf '%s' "$actual_cert" | tr -d ':[:space:]' | tr 'A-F' 'a-f')"
case "$actual_cert" in
    *[!0-9a-f]*) fail "AAB signer fingerprint absent or ambiguous" ;;
esac
[ "${#actual_cert}" -eq 64 ] \
    || fail "AAB signer fingerprint absent or ambiguous"
[ "$actual_cert" = "$expected_cert" ] || fail "AAB signer mismatch"

want_name="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$repo_root/app/build.gradle.kts")"
want_code="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$repo_root/app/build.gradle.kts")"
case "$want_name" in ""|*$'\n'*) fail "failed to read release versionName" ;; esac
case "$want_code" in ""|*$'\n'*) fail "failed to read release versionCode" ;; esac

manifest_value() {
    local xpath="$1" output
    output="$("$java_tool" -jar "$bundletool" dump manifest \
        --bundle="$aab" --module=base --xpath="$xpath" 2>/dev/null)" \
        || fail "bundletool manifest query failed"
    printf '%s' "$output"
}

[ "$(manifest_value '/manifest/@package')" = app.opentasks ] \
    || fail "release package mismatch"
[ "$(manifest_value '/manifest/@android:versionName')" = "$want_name" ] \
    || fail "release versionName mismatch"
[ "$(manifest_value '/manifest/@android:versionCode')" = "$want_code" ] \
    || fail "release versionCode mismatch"
[ "$(manifest_value '/manifest/uses-sdk/@android:minSdkVersion')" = 36 ] \
    || fail "release minimum SDK mismatch"
[ "$(manifest_value '/manifest/uses-sdk/@android:targetSdkVersion')" = 37 ] \
    || fail "release target SDK mismatch"
debuggable="$(manifest_value '/manifest/application/@android:debuggable')"
[ -z "$debuggable" ] || [ "$debuggable" = false ] \
    || fail "release AAB is debuggable"

manifest="$work_dir/manifest.xml"
"$java_tool" -jar "$bundletool" dump manifest \
    --bundle="$aab" --module=base >"$manifest" 2>/dev/null \
    || fail "bundletool merged manifest dump failed"
grep -Fq 'DriveCreateOnlyQualificationActivity' "$manifest" \
    && fail "debug qualification component present"

permissions="$(grep -o 'android:name="android.permission.[A-Z_]*"' "$manifest" \
    | sed 's/.*android.permission\.//; s/"$//' | sort -u)" || true
for permission in INTERNET POST_NOTIFICATIONS RECEIVE_BOOT_COMPLETED \
    SCHEDULE_EXACT_ALARM USE_BIOMETRIC; do
    printf '%s\n' "$permissions" | grep -Fqx "$permission" \
        || fail "required release permission missing"
done
for permission in READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE MANAGE_EXTERNAL_STORAGE \
    READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO ACCESS_MEDIA_LOCATION MANAGE_MEDIA \
    READ_CONTACTS WRITE_CONTACTS GET_ACCOUNTS SEND_SMS READ_SMS RECEIVE_SMS WRITE_SMS \
    RECEIVE_MMS RECEIVE_WAP_PUSH READ_CALL_LOG WRITE_CALL_LOG PROCESS_OUTGOING_CALLS \
    READ_PHONE_STATE READ_PHONE_NUMBERS CALL_PHONE ANSWER_PHONE_CALLS ADD_VOICEMAIL USE_SIP \
    ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION CAMERA \
    RECORD_AUDIO AUTHENTICATE_ACCOUNTS MANAGE_ACCOUNTS USE_CREDENTIALS \
    READ_SYNC_SETTINGS WRITE_SYNC_SETTINGS QUERY_ALL_PACKAGES; do
    printf '%s\n' "$permissions" | grep -Fqx "$permission" \
        && fail "denied high-risk permission present"
done

manifest_flat="$(tr '\n' ' ' < "$manifest" | tr -s '[:space:]' ' ')"
find_component() {
    local tag="$1" name="$2"
    printf '%s\n' "$manifest_flat" \
        | grep -o "<$tag[^>]*>" \
        | grep -F "android:name=\"$name\"" || true
}

main_activity="$(find_component activity app.opentasks.MainActivity)"
case "$main_activity" in ""|*$'\n'*) fail "MainActivity absent or ambiguous" ;; esac
printf '%s\n' "$main_activity" | grep -Fq 'android:exported="true"' \
    || fail "MainActivity export configuration mismatch"

file_provider="$(find_component provider androidx.core.content.FileProvider)"
case "$file_provider" in ""|*$'\n'*) fail "FileProvider absent or ambiguous" ;; esac
printf '%s\n' "$file_provider" | grep -Fq 'android:exported="false"' \
    || fail "FileProvider export configuration mismatch"

tile_service="$(find_component service app.opentasks.tile.QuickAddTileService)"
case "$tile_service" in ""|*$'\n'*) fail "QuickAddTileService absent or ambiguous" ;; esac
printf '%s\n' "$tile_service" | grep -Fq 'android:exported="true"' \
    || fail "QuickAddTileService export configuration mismatch"
printf '%s\n' "$tile_service" \
    | grep -Fq 'android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"' \
    || fail "QuickAddTileService permission protection missing"

grep -Fq 'android:dataExtractionRules="@xml/data_extraction_rules"' "$manifest" \
    || fail "data extraction rules manifest link missing"
grep -Fq 'android:fullBackupContent="@xml/backup_rules"' "$manifest" \
    || fail "backup rules manifest link missing"
grep -Fq 'android:resource="@xml/file_paths"' "$manifest" \
    || fail "FileProvider paths manifest link missing"

entries="$work_dir/entries.txt"
"$unzip_tool" -Z1 "$aab" >"$entries" 2>/dev/null \
    || fail "failed to list AAB entries"
for resource in data_extraction_rules backup_rules file_paths; do
    grep -Fqx "base/res/xml/$resource.xml" "$entries" \
        || fail "required XML resource missing from AAB"
done

native_entries="$(sed -n '/^base\/lib\//p' "$entries")"
[ -n "$native_entries" ] || fail "native libraries missing from AAB"
printf '%s\n' "$native_entries" \
    | grep -Ev '^base/lib/(arm64-v8a|x86_64)/[^/]+\.so$' \
    | grep -q . && fail "unexpected native ABI or library entry"
for abi in arm64-v8a x86_64; do
    printf '%s\n' "$native_entries" \
        | grep -Eq "^base/lib/$abi/[^/]+\.so$" \
        || fail "required native ABI or library missing"
done

"$unzip_tool" -p "$aab" 'base/dex/classes*.dex' >/dev/null 2>&1 \
    || fail "failed to stream release dex"
drive_scopes="$("$unzip_tool" -p "$aab" 'base/dex/classes*.dex' 2>/dev/null \
    | grep -ao 'auth/drive[.a-z]*' | sort -u)" || true
[ "$drive_scopes" = auth/drive.appdata ] \
    || fail "release dex must contain only auth/drive.appdata"

native_index=0
while IFS= read -r entry; do
    native_index=$((native_index + 1))
    native_file="$work_dir/native-$native_index.so"
    "$unzip_tool" -p "$aab" "$entry" >"$native_file" 2>/dev/null \
        || fail "failed to extract native library"
    objdump_output="$("$objdump_tool" -p "$native_file" 2>/dev/null)" \
        || fail "failed to inspect native library"
    load_count="$(printf '%s\n' "$objdump_output" | grep -c 'LOAD' || true)"
    alignments="$(printf '%s\n' "$objdump_output" \
        | sed -n '/LOAD/ s/.*align 2\*\*\([0-9][0-9]*\).*/\1/p')"
    alignment_count="$(printf '%s\n' "$alignments" | grep -c . || true)"
    [ "$load_count" -gt 0 ] && [ "$alignment_count" -eq "$load_count" ] \
        || fail "native ELF LOAD alignment absent or malformed"
    while IFS= read -r alignment; do
        [ "$alignment" -ge 14 ] || fail "native ELF LOAD alignment below 2**14"
    done <<< "$alignments"
done <<< "$native_entries"

printf 'verify-release-bundle audit: permissions=%s\n' \
    "$(printf '%s' "$permissions" | tr '\n' ',')"
exported_components="$(printf '%s\n' "$manifest_flat" \
    | grep -Eo '<(activity|activity-alias|service|receiver|provider)[^>]*android:exported="(true|false)"[^>]*>')" \
    || fail "failed to record exported component audit"
printf '%s\n' "$exported_components" \
    | sed 's/^/verify-release-bundle audit: component=/'
echo "verify-release-bundle: all checks passed"
