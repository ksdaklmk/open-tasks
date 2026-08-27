#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
verify="$repo_root/scripts/verify-release-bundle.sh"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/verify-release-bundle.XXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT

fake_bin="$test_root/bin"
fixtures="$test_root/fixtures"
mkdir -p "$fake_bin" "$fixtures"

bundletool="$fixtures/bundletool-all-1.18.3.jar"
aab="$fixtures/app-release.aab"
missing_aab="$fixtures/missing.aab"
missing_bundletool="$fixtures/missing-bundletool.jar"
matching_actual="$(printf 'a%.0s' {1..64})"
matching_input="$(printf 'A%.0s' {1..64})"
matching_keytool="$(printf '%s' "$matching_input" | sed 's/../&:/g; s/:$//')"
mismatching_actual="$(printf 'b%.0s' {1..64})"
short_input="$(printf 'A%.0s' {1..63})"
non_hex_input="${short_input}G"
version_code=7
version_name=1.5.0

cat > "$fake_bin/shasum" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$#" -eq 3 ] && [ "$1" = -a ] && [ "$2" = 256 ] || exit 2
if grep -q '^hash=valid$' "$3"; then
    printf 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29  %s\n' "$3"
else
    printf '0000000000000000000000000000000000000000000000000000000000000000  %s\n' "$3"
fi
EOF

cat > "$fake_bin/java" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$#" -ge 4 ] && [ "$1" = -jar ] || exit 2
[ -z "${FAKE_JAVA_MARKER:-}" ] || : > "$FAKE_JAVA_MARKER"
shift 2
command="$1"
shift
get_arg() {
    local prefix="$1" arg
    shift
    for arg in "$@"; do
        case "$arg" in
            "$prefix"*) printf '%s\n' "${arg#"$prefix"}"; return ;;
        esac
    done
    return 1
}
bundle="$(get_arg --bundle= "$@")"
field() { sed -n "s/^$1=//p" "$bundle"; }
case "$command" in
    validate)
        [ "$(field validation)" = valid ]
        ;;
    dump)
        [ "$1" = manifest ] || exit 2
        shift
        xpath="$(get_arg --xpath= "$@" 2>/dev/null || true)"
        case "$xpath" in
            /manifest/@package) field package ;;
            /manifest/@android:versionName) field version_name ;;
            /manifest/@android:versionCode) field version_code ;;
            /manifest/uses-sdk/@android:minSdkVersion) field min_sdk ;;
            /manifest/uses-sdk/@android:targetSdkVersion) field target_sdk ;;
            /manifest/application/@android:debuggable)
                [ "$(field debuggable)" = absent ] || field debuggable
                ;;
            /manifest/application/@android:dataExtractionRules)
                case "$(field manifest_resources)" in
                    data_missing|data_misplaced) ;;
                    *) printf '@xml/data_extraction_rules\n' ;;
                esac
                ;;
            /manifest/application/@android:fullBackupContent)
                case "$(field manifest_resources)" in
                    backup_missing|backup_misplaced) ;;
                    *) printf '@xml/backup_rules\n' ;;
                esac
                ;;
            "/manifest/application/provider[@android:name='androidx.core.content.FileProvider']/meta-data[@android:name='android.support.FILE_PROVIDER_PATHS']/@android:resource")
                case "$(field file_provider)" in
                    paths_missing|paths_misplaced) ;;
                    *) printf '@xml/file_paths\n' ;;
                esac
                ;;
            "")
                printf '<manifest package="%s">\n' "$(field package)"
                case "$(field permissions)" in
                    missing|misplaced) permissions='INTERNET POST_NOTIFICATIONS RECEIVE_BOOT_COMPLETED SCHEDULE_EXACT_ALARM' ;;
                    *) permissions='INTERNET POST_NOTIFICATIONS RECEIVE_BOOT_COMPLETED SCHEDULE_EXACT_ALARM USE_BIOMETRIC' ;;
                esac
                for permission in $permissions; do
                    printf '<uses-permission android:name="android.permission.%s" />\n' "$permission"
                done
                printf '<uses-permission android:name="app.opentasks.permission.FIXTURE" />\n'
                [ "$(field permissions)" != misplaced ] \
                    || printf '<meta-data android:name="android.permission.USE_BIOMETRIC" />\n'
                case "$(field permissions)" in
                    risky|risky_large)
                        printf '<uses-permission android:name="android.permission.CAMERA" />\n'
                        ;;
                esac
                if [ "$(field permissions)" = risky_large ]; then
                    filler=0
                    while [ "$filler" -lt 5000 ]; do
                        printf '<uses-permission android:name="android.permission.FILLER_%s" />\n' "$filler"
                        filler=$((filler + 1))
                    done
                fi
                printf '<application'
                case "$(field manifest_resources)" in data_missing|data_misplaced) true ;; *) false ;; esac \
                    || printf ' android:dataExtractionRules="@xml/data_extraction_rules"'
                case "$(field manifest_resources)" in backup_missing|backup_misplaced) true ;; *) false ;; esac \
                    || printf ' android:fullBackupContent="@xml/backup_rules"'
                printf '>\n'
                [ "$(field manifest_resources)" != data_misplaced ] \
                    || printf '<meta-data android:resource="@xml/data_extraction_rules" />\n'
                [ "$(field manifest_resources)" != backup_misplaced ] \
                    || printf '<meta-data android:resource="@xml/backup_rules" />\n'
                case "$(field main_activity)" in
                    missing) ;;
                    unexported) printf '<activity android:name="app.opentasks.MainActivity" android:exported="false" />\n' ;;
                    *) printf '<activity android:name="app.opentasks.MainActivity" android:exported="true" />\n' ;;
                esac
                case "$(field file_provider)" in
                    missing) ;;
                    exported) printf '<provider android:name="androidx.core.content.FileProvider" android:exported="true"><meta-data android:resource="@xml/file_paths" /></provider>\n' ;;
                    paths_missing) printf '<provider android:name="androidx.core.content.FileProvider" android:exported="false" />\n' ;;
                    paths_misplaced) printf '<provider android:name="androidx.core.content.FileProvider" android:exported="false" /><receiver android:name="app.opentasks.Other"><meta-data android:resource="@xml/file_paths" /></receiver>\n' ;;
                    *) printf '<provider android:name="androidx.core.content.FileProvider" android:exported="false"><meta-data android:resource="@xml/file_paths" /></provider>\n' ;;
                esac
                case "$(field tile_service)" in
                    missing) ;;
                    unexported) printf '<service android:name="app.opentasks.tile.QuickAddTileService" android:exported="false" android:permission="android.permission.BIND_QUICK_SETTINGS_TILE" />\n' ;;
                    unprotected) printf '<service android:name="app.opentasks.tile.QuickAddTileService" android:exported="true" />\n' ;;
                    *) printf '<service android:name="app.opentasks.tile.QuickAddTileService" android:exported="true" android:permission="android.permission.BIND_QUICK_SETTINGS_TILE" />\n' ;;
                esac
                [ "$(field debug_activity)" = absent ] \
                    || printf '<activity android:name="app.opentasks.backup.drive.DriveCreateOnlyQualificationActivity" android:exported="false" />\n'
                printf '</application></manifest>\n'
                ;;
            *) exit 2 ;;
        esac
        ;;
    *) exit 2 ;;
esac
EOF

cat > "$fake_bin/jarsigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$#" -eq 4 ] && [ "$1" = -verify ] && [ "$2" = -verbose ] \
    && [ "$3" = -certs ] || exit 2
case "$(sed -n 's/^signature=//p' "$4")" in
    valid) printf 'jar verified.\n' ;;
    unsigned) printf 'jar is unsigned.\n' ;;
    invalid) printf 'jar verified.\n'; exit 1 ;;
    *) exit 1 ;;
esac
EOF

cat > "$fake_bin/keytool" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$#" -eq 3 ] && [ "$1" = -printcert ] && [ "$2" = -jarfile ] || exit 2
case "$(sed -n 's/^signer=//p' "$3")" in
    valid) printf 'SHA256: %s\n' "$FAKE_MATCHING_DIGEST" ;;
    mismatch) printf 'SHA256: %s\n' "$FAKE_OTHER_DIGEST" ;;
    absent) printf 'Owner: CN=fixture\n' ;;
    ambiguous)
        printf 'SHA256: %s\n' "$FAKE_MATCHING_DIGEST"
        printf 'SHA256: %s\n' "$FAKE_OTHER_DIGEST"
        ;;
    *) exit 1 ;;
esac
EOF

cat > "$fake_bin/unzip" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
field() { sed -n "s/^$1=//p" "$aab"; }
case "$1" in
    -Z1)
        [ "$#" -eq 2 ] || exit 2
        aab="$2"
        printf 'base/dex/classes.dex\n'
        case "$(field abis)" in
            valid)
                printf 'base/lib/arm64-v8a/libsqlcipher.so\n'
                printf 'base/lib/x86_64/libsqlcipher.so\n'
                ;;
            missing) printf 'base/lib/arm64-v8a/libsqlcipher.so\n' ;;
            extra)
                printf 'base/lib/arm64-v8a/libsqlcipher.so\n'
                printf 'base/lib/x86_64/libsqlcipher.so\n'
                printf 'base/lib/armeabi-v7a/libsqlcipher.so\n'
                ;;
            extra_large)
                printf 'base/lib/armeabi-v7a/libunexpected.so\n'
                filler=0
                while [ "$filler" -lt 2000 ]; do
                    printf 'base/lib/arm64-v8a/libfiller_%s.so\n' "$filler"
                    printf 'base/lib/x86_64/libfiller_%s.so\n' "$filler"
                    filler=$((filler + 1))
                done
                ;;
            additional_matching)
                printf 'base/lib/arm64-v8a/libextra.so\n'
                printf 'base/lib/arm64-v8a/libsqlcipher.so\n'
                printf 'base/lib/x86_64/libextra.so\n'
                printf 'base/lib/x86_64/libsqlcipher.so\n'
                ;;
            mismatched_set)
                printf 'base/lib/arm64-v8a/libextra.so\n'
                printf 'base/lib/arm64-v8a/libsqlcipher.so\n'
                printf 'base/lib/x86_64/libsqlcipher.so\n'
                ;;
            duplicate)
                printf 'base/lib/arm64-v8a/libsqlcipher.so\n'
                printf 'base/lib/arm64-v8a/libsqlcipher.so\n'
                printf 'base/lib/x86_64/libsqlcipher.so\n'
                printf 'base/lib/x86_64/libsqlcipher.so\n'
                ;;
            metacharacter)
                printf 'base/lib/arm64-v8a/lib[fixture].so\n'
                printf 'base/lib/x86_64/lib[fixture].so\n'
                ;;
            library_missing)
                printf 'base/lib/arm64-v8a/libsqlcipher.so\n'
                printf 'base/lib/x86_64/README\n'
                ;;
        esac
        printf 'base/res/xml/data_extraction_rules.xml\n'
        printf 'base/res/xml/backup_rules.xml\n'
        [ "$(field resources)" = missing ] || printf 'base/res/xml/file_paths.xml\n'
        ;;
    -p)
        [ "$#" -eq 3 ] || exit 2
        aab="$2"
        case "$3" in
            base/dex/classes\*.dex)
                case "$(field scope)" in
                    valid) printf 'https://www.googleapis.com/auth/drive.appdata\n' ;;
                    absent) printf 'classes fixture\n' ;;
                    unexpected)
                        printf 'https://www.googleapis.com/auth/drive.appdata\n'
                        printf 'https://www.googleapis.com/auth/drive.file\n'
                        ;;
                esac
                ;;
            base/lib/arm64-v8a/*.so) printf 'alignment=%s\n' "$(field alignment_arm64)" ;;
            base/lib/x86_64/*.so) printf 'alignment=%s\n' "$(field alignment_x86)" ;;
            base/lib/armeabi-v7a/*.so) printf 'alignment=14\n' ;;
            *) exit 1 ;;
        esac
        ;;
    *) exit 2 ;;
esac
EOF

cat > "$fake_bin/llvm-objdump" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$#" -eq 2 ] && [ "$1" = -p ] || exit 2
alignment="$(sed -n 's/^alignment=//p' "$2")"
first_alignment="${alignment%%,*}"
second_alignment="${alignment#*,}"
[ "$second_alignment" != "$alignment" ] || second_alignment="$first_alignment"
printf '    LOAD off 0x000000 align 2**%s\n' "$first_alignment"
printf '    LOAD off 0x010000 align 2**%s\n' "$second_alignment"
EOF

chmod +x "$fake_bin"/*

write_bundletool() { printf 'hash=%s\n' "$1" > "$bundletool"; }

write_fixture() {
    printf '%s\n' \
        "validation=valid" \
        "signature=valid" \
        "signer=valid" \
        "package=app.opentasks" \
        "version_name=$version_name" \
        "version_code=$version_code" \
        "min_sdk=36" \
        "target_sdk=37" \
        "debuggable=absent" \
        "debug_activity=absent" \
        "permissions=valid" \
        "main_activity=valid" \
        "file_provider=valid" \
        "tile_service=valid" \
        "manifest_resources=valid" \
        "resources=valid" \
        "scope=valid" \
        "abis=valid" \
        "alignment_arm64=14" \
        "alignment_x86=14" > "$aab"
}

set_field() {
    local name="$1" value="$2"
    sed -i.bak "s/^${name}=.*/${name}=${value}/" "$aab"
    rm "$aab.bak"
}

run_verify_script() (
    verifier="$1"
    shift
    export OPEN_TASKS_UPLOAD_CERT_SHA256="$1"
    export OPEN_TASKS_BUNDLETOOL_JAR="$2"
    export OPEN_TASKS_LLVM_OBJDUMP="$fake_bin/llvm-objdump"
    export FAKE_MATCHING_DIGEST="$matching_keytool"
    export FAKE_OTHER_DIGEST="$mismatching_actual"
    export FAKE_JAVA_MARKER="$test_root/java-called"
    export PATH="$fake_bin:$PATH"
    shift 2
    cd "$repo_root"
    bash "$verifier" "$@"
)

run_verify() { run_verify_script "$verify" "$@"; }

run_without_cert() (
    unset OPEN_TASKS_UPLOAD_CERT_SHA256
    export OPEN_TASKS_BUNDLETOOL_JAR="$bundletool"
    export OPEN_TASKS_LLVM_OBJDUMP="$fake_bin/llvm-objdump"
    export FAKE_MATCHING_DIGEST="$matching_keytool"
    export FAKE_OTHER_DIGEST="$mismatching_actual"
    export FAKE_JAVA_MARKER="$test_root/java-called"
    export PATH="$fake_bin:$PATH"
    cd "$repo_root"
    bash "$verify" "$@"
)

expect_status_without_leak() {
    local expected="$1" label="$2"
    shift 2
    local output normalized_output status token normalized_token
    set +e
    output="$("$@" 2>&1)"
    status=$?
    set -e
    if [ "$status" -ne "$expected" ]; then
        printf 'FAIL: %s expected status %s, got %s\n%s\n' \
            "$label" "$expected" "$status" "$output" >&2
        exit 1
    fi
    if [ -n "${EXPECT_OUTPUT:-}" ]; then
        case "$output" in
            *"$EXPECT_OUTPUT"*) ;;
            *) printf 'FAIL: %s missing expected audit output\n' "$label" >&2; exit 1 ;;
        esac
    fi
    normalized_output="$(printf '%s' "$output" \
        | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
    for token in "$matching_input" "$matching_actual" "$matching_keytool" \
        "$mismatching_actual"; do
        normalized_token="$(printf '%s' "$token" \
            | tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
        case "$normalized_output" in
            *"$normalized_token"*)
                printf 'FAIL: %s disclosed certificate material\n' "$label" >&2
                exit 1
                ;;
        esac
    done
}

expect_rejected() {
    local label="$1"
    shift
    expect_status_without_leak 1 "$label" run_verify \
        "$matching_input" "$bundletool" "$@"
}

write_bundletool valid
write_fixture
expect_status_without_leak 1 "missing upload fingerprint" \
    run_without_cert "$aab"
expect_status_without_leak 1 "short upload fingerprint" \
    run_verify "$short_input" "$bundletool" "$aab"
expect_status_without_leak 1 "non-hex upload fingerprint" \
    run_verify "$non_hex_input" "$bundletool" "$aab"
expect_status_without_leak 1 "missing bundletool JAR" \
    run_verify "$matching_input" "$missing_bundletool" "$aab"
write_bundletool wrong
rm -f "$test_root/java-called"
expect_rejected "wrong bundletool SHA-256" "$aab"
[ ! -e "$test_root/java-called" ] \
    || { echo 'FAIL: wrong bundletool SHA-256 was executed' >&2; exit 1; }
write_bundletool valid
expect_rejected "wrong argument count" "$aab" "$aab"
expect_rejected "missing AAB" "$missing_aab"

parser_repo="$test_root/parser-repo"
parser_verify="$parser_repo/scripts/verify-release-bundle.sh"
mkdir -p "$parser_repo/scripts" "$parser_repo/app"
cp "$verify" "$parser_verify"
cat > "$parser_repo/app/build.gradle.kts" <<'EOF'
android {
    defaultConfig {
        versionCode = 7 + 0
        versionName = providers.gradleProperty("versionName").get()
    }
}
// versionName = "1.5.0"
EOF
expect_status_without_leak 1 "non-literal defaultConfig version" \
    run_verify_script "$parser_verify" "$matching_input" "$bundletool" "$aab"
cat > "$parser_repo/app/build.gradle.kts" <<'EOF'
android {
    defaultConfig {
        applicationId = "app.opentasks"
    }
    releaseMetadata {
        versionCode = 7
        versionName = "1.5.0"
    }
}
EOF
expect_status_without_leak 1 "version assignments outside defaultConfig" \
    run_verify_script "$parser_verify" "$matching_input" "$bundletool" "$aab"

write_fixture; set_field validation failed
expect_rejected "failed bundletool validation" "$aab"
write_fixture; set_field signature invalid
expect_rejected "invalid JAR signature" "$aab"
write_fixture; set_field signature unsigned
expect_rejected "unsigned JAR with successful verifier status" "$aab"
write_fixture; set_field signer absent
expect_rejected "absent AAB signer" "$aab"
write_fixture; set_field signer ambiguous
expect_rejected "ambiguous AAB signer" "$aab"
write_fixture; set_field signer mismatch
expect_rejected "mismatched AAB signer" "$aab"

for field_value in \
    'package=wrong.package' \
    'version_name=0.0.0' \
    'version_code=0' \
    'min_sdk=35' \
    'target_sdk=36'; do
    write_fixture
    set_field "${field_value%%=*}" "${field_value#*=}"
    expect_rejected "wrong ${field_value%%=*}" "$aab"
done
write_fixture; set_field debuggable true
expect_rejected "debuggable manifest" "$aab"
write_fixture; set_field debug_activity present
expect_rejected "debug qualification component" "$aab"
write_fixture; set_field permissions missing
expect_rejected "missing required permission" "$aab"
write_fixture; set_field permissions misplaced
expect_rejected "permission-looking metadata is not a declaration" "$aab"
write_fixture; set_field permissions risky
expect_rejected "denied high-risk permission" "$aab"
write_fixture; set_field permissions risky_large
expect_rejected "denied permission in large manifest" "$aab"
write_fixture; set_field main_activity missing
expect_rejected "missing MainActivity" "$aab"
write_fixture; set_field main_activity unexported
expect_rejected "unexported MainActivity" "$aab"
write_fixture; set_field file_provider missing
expect_rejected "missing FileProvider" "$aab"
write_fixture; set_field file_provider exported
expect_rejected "exported FileProvider" "$aab"
write_fixture; set_field tile_service missing
expect_rejected "missing QuickAddTileService" "$aab"
write_fixture; set_field tile_service unexported
expect_rejected "unexported QuickAddTileService" "$aab"
write_fixture; set_field tile_service unprotected
expect_rejected "unprotected QuickAddTileService" "$aab"
write_fixture; set_field manifest_resources data_missing
expect_rejected "missing data extraction rules link" "$aab"
write_fixture; set_field manifest_resources data_misplaced
expect_rejected "misplaced data extraction rules link" "$aab"
write_fixture; set_field manifest_resources backup_missing
expect_rejected "missing backup rules link" "$aab"
write_fixture; set_field manifest_resources backup_misplaced
expect_rejected "misplaced backup rules link" "$aab"
write_fixture; set_field file_provider paths_missing
expect_rejected "missing FileProvider paths link" "$aab"
write_fixture; set_field file_provider paths_misplaced
expect_rejected "FileProvider paths link on unrelated component" "$aab"
write_fixture; set_field resources missing
expect_rejected "missing linked XML resource" "$aab"

write_fixture; set_field scope absent
expect_rejected "missing Drive scope" "$aab"
write_fixture; set_field scope unexpected
expect_rejected "unexpected Drive scope" "$aab"
write_fixture; set_field abis missing
expect_rejected "missing native ABI" "$aab"
write_fixture; set_field abis extra
expect_rejected "extra native ABI" "$aab"
write_fixture; set_field abis extra_large
expect_rejected "extra native ABI in large inventory" "$aab"
write_fixture; set_field abis additional_matching
expect_status_without_leak 0 "matching additional native library" \
    run_verify "$matching_input" "$bundletool" "$aab"
write_fixture; set_field abis mismatched_set
expect_rejected "mismatched native library sets" "$aab"
write_fixture; set_field abis duplicate
expect_rejected "duplicate native archive entries" "$aab"
write_fixture; set_field abis metacharacter
expect_rejected "native archive glob metacharacter" "$aab"
write_fixture; set_field abis library_missing
expect_rejected "missing native library" "$aab"
write_fixture; set_field alignment_x86 13
expect_rejected "ELF LOAD alignment below 2**14" "$aab"
write_fixture; set_field alignment_arm64 14,13
expect_rejected "second ELF LOAD alignment below 2**14" "$aab"

write_fixture
EXPECT_OUTPUT='app.opentasks.permission.FIXTURE' expect_status_without_leak 0 "valid bundle" \
    run_verify "$matching_input" "$bundletool" "$aab"

echo "verify-release-bundle-script: all checks passed"
