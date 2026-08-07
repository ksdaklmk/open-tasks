# Release Readiness (Signed Sideload) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a signed, R8-minified, runtime-verified
`app-release.apk` at version 1.0.0 with a repeatable documented release
process, per `docs/superpowers/specs/2026-08-08-release-readiness-design.md`.

**Architecture:** A guarded signing config in `app/build.gradle.kts`
signs the release build only when an untracked `keystore.properties`
exists (CI stays unsigned and unchanged). A verification script
automates the release-inspection gate; a human-executed smoke checklist
on a disposable AVD proves the R8 build at runtime; `RELEASING.md`
documents the process.

**Tech Stack:** AGP 9 Kotlin DSL, bash, Android SDK build-tools
(`apksigner`, `aapt2`), `keytool`, adb/emulator at
`~/Library/Android/sdk` (not on PATH).

## Global Constraints

- Never combine `lintDebug` and `assembleRelease` in one Gradle
  invocation.
- Configuration cache is on; all build logic must be config-cache
  compatible.
- No secrets in the repository, GitHub, logs, or command output. Never
  echo keystore passwords.
- `keystore.properties`, `*.jks`, `*.keystore` are already gitignored;
  never `git add` them.
- Markdown docs hard-wrapped near 78 columns.
- Commit straight to `main`.
- The protected `Pixel_10_Pro_Fold` AVD persistent state must never be
  modified: any emulator use is a sole ADB target started with
  `-read-only -no-snapshot-load -no-snapshot-save` (writes land in a
  discarded overlay).
- Do not touch the untracked `artifacts/`, `.kotlin/`, or the modified
  Stage 3 plan doc in the working tree.

---

### Task 1: Guarded signing config and version 1.0.0

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: nothing.
- Produces: `:app:assembleRelease` emits
  `app/build/outputs/apk/release/app-release.apk`, signed iff
  `keystore.properties` exists at the repo root with keys `storeFile`,
  `storePassword`, `keyAlias`, `keyPassword`. Tasks 2, 4, and 5 rely on
  this path and behaviour. `versionName` is `1.0.0`, `versionCode` 1.

- [ ] **Step 1: Edit `app/build.gradle.kts`**

Add the import as the first line of the file, before `plugins {`:

```kotlin
import java.util.Properties
```

Change `versionName = "0.1.0"` to:

```kotlin
        versionName = "1.0.0"
```

Inside the `android { }` block, add before `buildTypes { }`:

```kotlin
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                val props = Properties()
                keystorePropertiesFile.inputStream().use(props::load)
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
```

In `buildTypes { release { } }`, add as the first line of the release
block:

```kotlin
            signingConfig = signingConfigs.findByName("release")
```

`findByName` returns null when the properties file is absent, which
leaves the release build unsigned — exactly today's CI behaviour.

- [ ] **Step 2: Verify the unsigned path (CI parity)**

Ensure no `keystore.properties` exists at the repo root, then:

```bash
./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL;
`app/build/outputs/apk/release/app-release.apk` exists. Then confirm it
is unsigned:

```bash
BT=$(ls ~/Library/Android/sdk/build-tools | sort -V | tail -1)
~/Library/Android/sdk/build-tools/$BT/apksigner verify \
  app/build/outputs/apk/release/app-release.apk
```

Expected: nonzero exit (`DOES NOT VERIFY` or missing-certificate
error) — the APK is unsigned.

- [ ] **Step 3: Verify the signed path with a throwaway keystore**

Create a throwaway keystore in the session scratchpad (NOT the repo):

```bash
SCRATCH=$(mktemp -d)
keytool -genkeypair -keystore "$SCRATCH/throwaway.jks" \
  -alias opentasks -keyalg RSA -keysize 4096 -validity 30 \
  -storepass throwaway-only -keypass throwaway-only \
  -dname "CN=Throwaway"
cat > keystore.properties <<EOF
storeFile=$SCRATCH/throwaway.jks
storePassword=throwaway-only
keyAlias=opentasks
keyPassword=throwaway-only
EOF
./gradlew :app:assembleRelease
~/Library/Android/sdk/build-tools/$BT/apksigner verify \
  app/build/outputs/apk/release/app-release.apk && echo SIGNED-OK
```

Expected: BUILD SUCCESSFUL, then `SIGNED-OK`.

- [ ] **Step 4: Remove the throwaway material and confirm clean tree**

```bash
rm keystore.properties && rm -rf "$SCRATCH"
git status --short
```

Expected: only `app/build.gradle.kts` modified; no keystore files
listed.

- [ ] **Step 5: Run the unit gate**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
```

Expected: BUILD SUCCESSFUL, zero test failures. (Separate invocation
from Step 2's `assembleRelease`, per the global constraint.)

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: sign release from local keystore.properties when present

Release stays unsigned when the file is absent, preserving CI behaviour.
Version bumped to 1.0.0 for the first signed sideload release."
```

---

### Task 2: `scripts/verify-release-apk.sh`

**Files:**
- Create: `scripts/verify-release-apk.sh`

**Interfaces:**
- Consumes: Task 1's APK at
  `app/build/outputs/apk/release/app-release.apk` (default; `$1`
  overrides) and the `versionName`/`versionCode` literals in
  `app/build.gradle.kts`.
- Produces: `bash scripts/verify-release-apk.sh [apk]` — exit 0 with
  `verify-release-apk: all checks passed`, or nonzero naming the first
  failed check. Tasks 4 and 5 run it verbatim.

- [ ] **Step 1: Write the script**

Create `scripts/verify-release-apk.sh`:

```bash
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
```

Note the two `&& fail` lines (checks 3 and 5) invert grep: grep
succeeding means the bad thing is present. Under `set -e` a failing
grep at the end of a `&&` chain does not abort the script.

- [ ] **Step 2: RED — run against the unsigned APK**

Ensure no `keystore.properties` exists, then:

```bash
./gradlew :app:assembleRelease
bash scripts/verify-release-apk.sh
```

Expected: exit nonzero with
`verify-release-apk FAIL: apksigner verify (unsigned or bad signature)`.

- [ ] **Step 3: GREEN — run against a throwaway-signed APK**

Recreate the throwaway keystore exactly as Task 1 Step 3 (scratchpad
`throwaway.jks`, `keystore.properties` at the root), then:

```bash
./gradlew :app:assembleRelease
bash scripts/verify-release-apk.sh
```

Expected: `verify-release-apk: all checks passed`, exit 0.

- [ ] **Step 4: Clean up throwaway material**

```bash
rm keystore.properties && rm -rf "$SCRATCH"
git status --short
```

Expected: only `scripts/verify-release-apk.sh` new; no keystore files.

- [ ] **Step 5: Commit**

```bash
git add scripts/verify-release-apk.sh
git commit -m "build: add release apk verification script"
```

---

### Task 3: `RELEASING.md`

**Files:**
- Create: `RELEASING.md`

**Interfaces:**
- Consumes: Task 1's signing contract, Task 2's script.
- Produces: the documented one-time setup and per-release process that
  Task 4 and Task 5 execute literally. Later sessions follow this file.

- [ ] **Step 1: Write `RELEASING.md`** (hard-wrapped near 78 columns)

```markdown
# Releasing Open Tasks (signed sideload)

Distribution is signed sideload only. There is no Play Console, no AAB,
and no CI signing; CI builds the release unsigned.

## One-time setup

1. Generate the release keystore OUTSIDE the repository:

       keytool -genkeypair \
         -keystore ~/Keys/opentasks-release.jks \
         -alias opentasks -keyalg RSA -keysize 4096 -validity 10000 \
         -dname "CN=Open Tasks"

   keytool prompts for the store password; use a strong unique one.

2. Write `keystore.properties` at the repository root (gitignored):

       storeFile=/Users/<you>/Keys/opentasks-release.jks
       storePassword=<store password>
       keyAlias=opentasks
       keyPassword=<store password>

3. Back up BOTH files durably (password manager plus offline copy).
   Losing the keystore means existing installs can never update: a
   signature mismatch forces uninstall and on-device data loss.
4. Never commit either file. `.gitignore` already covers them; do not
   override it.

## Per-release process

1. Bump `versionCode` by exactly 1 and set `versionName` by semver
   judgment in `app/build.gradle.kts`.
2. Run the CI gate (separate invocation from the release build):

       ./gradlew testDebugUnitTest lintDebug :app:assembleDebug

3. Build the signed release:

       ./gradlew :app:assembleRelease

4. Verify the APK:

       bash scripts/verify-release-apk.sh

5. Run the smoke checklist below on a disposable AVD. Record the
   results in `docs/qualification/release-<versionName>-sideload.md`.
6. Commit the version bump and qualification record, then tag:

       git tag -a v<versionName> -m "Release <versionName>" && git push origin main v<versionName>

7. Install on the real device:

       adb install app/build/outputs/apk/release/app-release.apk

## Smoke checklist (per release, disposable AVD only)

Boot the emulator as the sole ADB target with `-read-only
-no-snapshot-load -no-snapshot-save`. Never install a release build on
the protected AVD's persistent state; the read-only overlay is
discarded at shutdown, which is what makes this safe. Inside the
session, uninstall the inherited debug build first (overlay-only):

    adb uninstall app.opentasks
    adb install app/build/outputs/apk/release/app-release.apk

Then, by hand:

1. Fresh launch → create a vault with a passphrase.
2. Add a project, a task with a checklist item, and a tag.
3. Force-stop and relaunch → everything from step 2 persists.
4. Export `.otvault`, then import it back → counts match.
5. Enable app lock, background past the delay, unlock.
6. Place the Today widget on the launcher → counts render.
7. Open the app via the Quick Add launcher shortcut.

Every step must pass before tagging. A failure stops the release; triage
the defect before any tag is created.
```

- [ ] **Step 2: Check the wrap and tree**

```bash
awk 'length > 80 {print FILENAME": "FNR" ("length")"}' RELEASING.md
git status --short
```

Expected: no lines over 80 columns (the two indented command lines that
must stay on one line are exempt if unavoidable); only `RELEASING.md`
new.

- [ ] **Step 3: Commit**

```bash
git add RELEASING.md
git commit -m "docs: add sideload release process"
```

---

### Task 4: One-time keystore setup and the signed 1.0.0 build

This task creates real key material on the user's machine. It requires
the user's participation for password entry and backup confirmation —
pause and hand the keytool step to the user rather than generating or
printing passwords.

**Files:**
- None in the repository (keystore and properties are untracked by
  design).

**Interfaces:**
- Consumes: Task 1's signing contract, Task 2's script, Task 3's
  one-time setup instructions.
- Produces: `~/Keys/opentasks-release.jks`, `keystore.properties`, and
  a verified signed `app-release.apk` that Task 5 smoke-tests.

- [ ] **Step 1: User performs one-time setup**

Ask the user to run the One-time setup section of `RELEASING.md`
(steps 1–3) themselves — the keystore password is theirs and must not
appear in session output. Suggest they run the keytool command via the
`!` prefix or a separate terminal. Wait for confirmation that
`keystore.properties` exists and both files are backed up.

- [ ] **Step 2: Build the signed release**

```bash
./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify**

```bash
bash scripts/verify-release-apk.sh
```

Expected: `verify-release-apk: all checks passed`.

- [ ] **Step 4: Confirm the tree is clean**

```bash
git status --short
```

Expected: no keystore files; nothing to commit (this task changes no
tracked file).

---

### Task 5: Smoke checklist, qualification record, tag

**Files:**
- Create: `docs/qualification/release-1.0.0-sideload.md`
- Modify: `HANDOFF.md` (closure record)

**Interfaces:**
- Consumes: Task 4's verified signed APK; Task 3's smoke checklist.
- Produces: the executed qualification record and the annotated
  `v1.0.0` tag on the released commit.

- [ ] **Step 1: Boot the disposable AVD**

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_10_Pro_Fold \
  -read-only -no-snapshot-load -no-snapshot-save &
~/Library/Android/sdk/platform-tools/adb wait-for-device
~/Library/Android/sdk/platform-tools/adb devices
```

Expected: exactly one device listed. Abort if any other ADB target is
present.

- [ ] **Step 2: Install the release build into the overlay**

```bash
~/Library/Android/sdk/platform-tools/adb uninstall app.opentasks
~/Library/Android/sdk/platform-tools/adb install \
  app/build/outputs/apk/release/app-release.apk
```

Expected: both commands report success. (The uninstall touches only the
discarded overlay, never the protected persistent state.)

- [ ] **Step 3: Execute the smoke checklist**

The user (or the controller driving interactively with the user's
knowledge) performs the seven checklist steps from `RELEASING.md` in
order, noting pass/fail for each. Any failure stops the release for
triage.

- [ ] **Step 4: Shut the emulator down**

```bash
~/Library/Android/sdk/platform-tools/adb emu kill
~/Library/Android/sdk/platform-tools/adb devices
```

Expected: no devices remain; the overlay is discarded.

- [ ] **Step 5: Write the qualification record**

Create `docs/qualification/release-1.0.0-sideload.md` (hard-wrapped
near 78 columns) recording: the released commit hash, APK
size, the full verify-script output, the seven smoke-step outcomes with
pass/fail, the emulator flags used, and the date. No task text, account
details, or Drive IDs.

- [ ] **Step 6: Update `HANDOFF.md`**

Record in the top matter: release 1.0.0 signed and qualified for
sideload, keystore held locally by the user, CI unchanged (unsigned
release), and the qualification record path.

- [ ] **Step 7: Commit and tag**

```bash
git add docs/qualification/release-1.0.0-sideload.md HANDOFF.md
git commit -m "docs: qualify release 1.0.0 for signed sideload"
git tag -a v1.0.0 -m "Release 1.0.0 (signed sideload)"
git push origin main v1.0.0
```

- [ ] **Step 8: Install on the real device**

Hand the user the command (their device, their cable/Wi-Fi debugging):

```bash
adb install app/build/outputs/apk/release/app-release.apk
```
