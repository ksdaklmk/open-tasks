# Google Play Submission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish the exact qualified Open Tasks 1.5.0 Android App Bundle as a
free, ad-free, global Google Play app while preserving updates for existing
signed-sideload users and retaining local-first use without an account.

**Architecture:** Extend the existing `release` build with one external
upload-keystore selector, keep the current app-signing key as the direct-APK
default and Play delivery key, and add one fail-closed AAB verifier beside the
existing APK verifier. Publish two static GitHub Pages documents, expose the
privacy URL through the existing More callback pattern, then move one immutable
candidate through internal testing, the mandatory closed test, production
access, and the first global production release.

**Tech Stack:** Kotlin, Android Gradle Plugin, Jetpack Compose Material 3,
JUnit 4, AndroidX Compose UI tests, Bash, JDK signing tools, Android SDK build
tools, bundletool 1.18.3, GitHub Actions/Pages, Google Cloud OAuth, and Google
Play Console.

**Spec:**
`docs/superpowers/specs/2026-08-27-google-play-submission-design.md`
(approved by the owner on 2026-08-27).

## Global Constraints

- Preserve package `app.opentasks`, app name **Open Tasks**, and direct upgrade
  compatibility with the existing signed 1.4.0 installation.
- The existing `/Users/kk/Keys/opentasks-release.jks` key, alias `opentasks`,
  must become Play's app-signing/delivery key. Never accept a newly generated
  delivery key if it changes the signer seen by existing installations.
- Use a separate upload key at
  `/Users/kk/Keys/opentasks-play-upload.jks`, alias `opentasks-upload`.
- Keep keystores, passwords, recovery material, tester identities, legal
  identity evidence, and private Console screenshots outside Git. Public
  certificate fingerprints and aggregate tester counts are safe to record.
- Candidate identity begins at `versionName = "1.5.0"` and `versionCode = 7`.
  Every Play upload consumes its code. A changed artifact uses the next unused
  integer while retaining 1.5.0 unless the user-visible scope changes.
- Build Play with the existing `release` build type and
  `:app:bundleRelease`. Add no product flavor, parallel release type, Play API
  client, CI signing secret, deployment framework, or app dependency.
- Core use remains local and account-free. Google authorization remains an
  optional user action for encrypted Drive `appDataFolder` backup/recovery.
- Publish as free, with no ads, in all Play-supported countries and regions,
  in Productivity, default language English (United Kingdom), for users 13+
  and not in Families.
- Use internal testing, then the personal-account closed test with at least 12
  testers continuously opted in for 14 days, then production access, then
  production. Do not add an open-test phase.
- The first production launch uses standard publishing to every selected
  region. Managed publishing, a percentage staged rollout, and halt-to-previous
  release are unavailable for the first production release.
- No external mutation is delegated. The owner performs identity, package,
  key, OAuth, track, declaration, and publication actions with explicit
  execution-time approval.
- Never install, clear, benchmark, or run connected tests on a device that
  contains data that must survive. Use an audited disposable device or AVD and
  synthetic content only; never use the protected `Pixel_10_Pro_Fold`.
- Treat signer/key/package mismatch, failed direct upgrade, manifest or 16 KB
  incompatibility, data loss, broken Drive recovery, inaccurate declarations,
  unresolved Critical/High defects, an unmet tester requirement, or any
  Console rejection as a promotion stop.
- Use the official current rules linked below at execution time. If Console
  behavior conflicts with this 2026-08-27 plan, stop, record the changed rule,
  and update the plan/spec before acting.

## File Map

- Modify `app/build.gradle.kts`: set 1.5.0/7 and select an explicitly named
  external signing-properties file for Play AABs while preserving the current
  default direct-APK signer.
- Create `scripts/verify-release-bundle.sh`: authenticate and inspect exactly
  one AAB, including bundletool provenance, JAR signer, identity, merged
  manifest, Drive scope, ABIs, and native ELF alignment.
- Create `scripts/verify-release-bundle-script.sh`: exercise the verifier's
  success case and each fail-closed trust boundary using fake tools.
- Modify `RELEASING.md`: retain direct APK release gates, add the manual Play
  AAB path, signing-key split, bundletool pin, track progression, and first
  release incident procedure; remove stale Welcome steps.
- Modify
  `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt` and
  `feature/more/src/main/res/values/strings.xml`: add one stateless Privacy
  policy row.
- Create
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/MorePrivacyPolicyInstrumentedTest.kt`:
  prove the row is visible, accessible, and forwards one click.
- Modify `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt` and
  `app/src/main/res/values/strings.xml`: open the fixed HTTPS policy URL and
  report the no-browser fallback.
- Create `docs/google-play/store-listing.md`: version exact en-GB listing copy,
  declaration decisions/evidence, URLs, release notes, and the asset manifest.
- Create `site/privacy/index.html` and `site/support/index.html`: static,
  tracker-free public policy and support pages.
- Create `.github/workflows/pages.yml` and modify
  `scripts/verify-actions-workflow.sh`: deploy only `site/` with pinned actions
  and verify the workflow's permissions and pins.
- Create `docs/google-play/assets/`: final store icon, feature graphic, four
  phone screenshots, and four large-screen screenshots from the exact release
  UI using synthetic data.
- Create `docs/qualification/release-1.5.0-play.md`: the non-sensitive,
  append-only release ledger for hashes, public fingerprints, gates, aggregate
  testing evidence, decisions, approvals, and launch monitoring.

## Dependencies and Target Calendar

| Workstream | Depends on | Earliest target |
|---|---|---|
| Existing-key and account preflight | None | 27 August 2026 |
| Repository preparation | Approved design | 27 August–2 September |
| Developer verification/package registration | Owner identity and physical device | Complete before 30 September 2026 |
| Play signing and OAuth | Pages live; existing key accepted | Before any tester installs |
| Candidate qualification/internal track | Signing and OAuth complete | 3–5 September |
| Closed test | Complete app setup and green internal gates | Start by 6 September |
| Production-access application | 12+ continuously opted-in testers for 14 days | 20 September or later |
| First production review/publication | Production access plus final go/no-go | Allow at least one week of buffer |

The dates are targets, not permission to waive a gate. The 30 September 2026
Thailand milestone applies to developer verification/package registration;
complete that P0 work even if the production review extends later.

## Evidence Rules

- Begin each ledger entry with UTC date/time, actor (`owner` or `worker`), the
  exact commit, and the candidate version code.
- Record commands and outcomes, but never command-line passwords, OAuth
  secrets, recovery secrets, tester identities, legal documents, or private
  email addresses.
- Record SHA-256 for every AAB, APK set, APK, mapping, native-symbol archive,
  SBOM, and committed store asset used for a decision.
- Store sensitive source evidence in owner-controlled storage; put only a
  neutral reference and PASS/FAIL result in the repository ledger.
- A PASS belongs to one exact artifact hash. Rebuilding, resigning, or changing
  code/resources invalidates all artifact-dependent evidence.

## Current Official References

- [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-GB_ALL)
- [Personal-account test requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-GB)
- [Physical-device verification](https://support.google.com/googleplay/android-developer/answer/14316361?hl=en-GB)
- [Android developer verification](https://developer.android.com/developer-verification/guides)
- [Package-name registration](https://support.google.com/googleplay/android-developer/answer/16984799?hl=en)
- [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en)
- [Android signing guidance](https://developer.android.com/studio/publish/app-signing)
- [Android App Bundles](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en)
- [16 KB page-size compatibility](https://developer.android.com/guide/practices/page-sizes)
- [Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
- [Privacy policy requirements](https://support.google.com/googleplay/android-developer/answer/17190352?hl=en)
- [App Content](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en-EN)
- [Target audience and children](https://support.google.com/googleplay/android-developer/answer/9867159?hl=en)
- [Financial-features declaration](https://support.google.com/googleplay/android-developer/answer/13849271?hl=en)
- [Store listing assets](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)
- [Drive scope classification](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [OAuth publishing status](https://support.google.com/cloud/answer/15549945?hl=en)
- [First production rollout](https://support.google.com/googleplay/android-developer/answer/9859348?hl=en)
- [Managed publishing limits](https://support.google.com/googleplay/android-developer/answer/9859654?hl=en)
- [Halting a release](https://support.google.com/googleplay/android-developer/answer/16285429?hl=en)
- [Android vitals](https://support.google.com/googleplay/android-developer/answer/9844486?hl=en)

---

### Task 1: Open the release ledger and prove the existing signing baseline

**Files:**

- Create: `docs/qualification/release-1.5.0-play.md`
- Read: `docs/qualification/release-1.4.0-sideload.md`
- Read: `RELEASING.md`
- Read: `app/build.gradle.kts`

**Interfaces:**

- Consumes: tag `v1.4.0`, the existing signed APK/key, the independent owner
  certificate record, and read-only Play account status.
- Produces: a non-sensitive ledger with an authenticated signing baseline and
  explicit PENDING/PASS/FAIL gates.

- [ ] **Step 1: Create the append-only qualification ledger**

Add fixed metadata for package, 1.5.0/7, branch/commit, account type, default
language, price, ads, audience, regions, URLs, key roles, and the approved track
path. Add gate tables for:

- developer identity, device verification, and package registration;
- app-signing and upload keys;
- GitHub Pages and OAuth;
- repository/build/AAB/APK/16 KB/backup/Drive/upgrade qualification;
- listing, Data Safety, App Content, content rating, and target audience;
- internal and closed tests;
- production access, production go/no-go, and day 1–30 monitoring; and
- consumed version codes and candidate hashes.

Initial statuses must say `PENDING — evidence not yet produced`; never mark an
approved design decision as test evidence.

- [ ] **Step 2: Establish the current build/tag baseline**

Run:

```bash
git status --short --branch
git rev-parse HEAD
git show --no-patch --format='%H %cI %s' v1.4.0
sed -n '1,90p' app/build.gradle.kts
```

Expected: a deliberately chosen clean execution worktree; package
`app.opentasks`; current pre-change version 1.4.0/6; and an immutable v1.4.0
commit. Record exact hashes, not the current branch divergence narrative.

- [ ] **Step 3: Inspect the existing private key without exporting it**

Run locally; let `keytool` prompt for the password:

```bash
keytool -list -v \
  -keystore /Users/kk/Keys/opentasks-release.jks \
  -alias opentasks
```

Expected: a private-key entry using RSA 4096, validity long enough for Play,
and SHA-1/SHA-256 fingerprints matching the owner's independent record. Record
only algorithm, key size, expiry, and public fingerprints. Stop if the file,
alias, independent record, key algorithm, strength, or validity is wrong.

- [ ] **Step 4: Authenticate the signer of the shipped 1.4.0 artifact**

If a retained 1.4.0 APK is unavailable, rebuild it only from the `v1.4.0` tag
in the isolated worktree procedure in Task 9. Then run:

```bash
play_build_tools="$(find /Users/kk/Library/Android/sdk/build-tools \
  -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
test -x "$play_build_tools/apksigner"
"$play_build_tools/apksigner" verify --print-certs \
  /private/tmp/open-tasks-v1.4.0.apk
```

Expected: signature verification succeeds and the APK certificate SHA-256
equals the independently recorded existing-key SHA-256. Never establish trust
by copying the fingerprint from the APK alone. If no retained APK exists, leave
this artifact gate PENDING and perform it with Task 9's isolated v1.4.0 rebuild;
the private-key baseline can still be committed now.

- [ ] **Step 5: Perform a read-only owner-account readiness check**

In Play Console, confirm the personal account was created after 13 November
2023; legal/contact/payment-profile details are accurate; two-step verification
is active; device verification and Android developer verification status are
visible; and `app.opentasks` is not already claimed by an unrelated app.
Record only statuses and the inspection date. Do not mutate Console state in
this step.

- [ ] **Step 6: Review and commit the baseline ledger**

Run:

```bash
! rg -ni "storePassword=|keyPassword=|BEGIN .*PRIVATE KEY|[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}" \
  docs/qualification/release-1.5.0-play.md
git diff --check
git diff -- docs/qualification/release-1.5.0-play.md
git add docs/qualification/release-1.5.0-play.md
git commit -m "docs: open Play qualification ledger"
```

Expected: the sensitive-data scan has no match, the diff is clean, and the
commit contains only the new ledger.

---

### Task 2: Add the upload-signing selector and establish 1.5.0/7

**Files:**

- Modify: `app/build.gradle.kts:17-45`

**Interfaces:**

- Consumes: optional Gradle property `openTasksKeystoreProperties`.
- Produces: default direct-release signing through root `keystore.properties`,
  explicit Play AAB signing through an external properties file, and a hard
  configuration failure for an explicitly missing file.

- [ ] **Step 1: Demonstrate the current fail-open behavior**

Run before editing:

```bash
./gradlew :app:tasks \
  -PopenTasksKeystoreProperties=/private/tmp/open-tasks-missing-upload.properties \
  --stacktrace
```

Expected RED: configuration currently succeeds because the property is
ignored. This proves the new check is meaningful.

- [ ] **Step 2: Add the smallest fail-closed selector**

Replace the fixed `keystorePropertiesFile` declaration with:

```kotlin
val requestedKeystoreProperties =
    providers.gradleProperty("openTasksKeystoreProperties").orNull
val keystorePropertiesFile = requestedKeystoreProperties
    ?.let(rootProject::file)
    ?: rootProject.file("keystore.properties")
require(requestedKeystoreProperties == null || keystorePropertiesFile.isFile) {
    "openTasksKeystoreProperties does not name a readable file"
}
```

Keep the existing conditional `release` signing config and required property
keys. Do not add a second signing config or embed any path/password in Git.

- [ ] **Step 3: Bump only the approved candidate identity**

Set:

```kotlin
versionCode = 7
versionName = "1.5.0"
```

Do not change application ID, SDK values, ABIs, build types, shrinking, or
dependencies.

- [ ] **Step 4: Prove default compatibility and explicit failure**

Run:

```bash
./gradlew :app:tasks --stacktrace
./gradlew :app:tasks \
  -PopenTasksKeystoreProperties=/private/tmp/open-tasks-missing-upload.properties \
  --stacktrace
```

Expected: the first command exits 0 using the existing default behavior; the
second exits non-zero with
`openTasksKeystoreProperties does not name a readable file`.

- [ ] **Step 5: Build the non-release regression gate**

Run:

```bash
./gradlew :app:assembleDebug testDebugUnitTest lintDebug --stacktrace
```

Expected: BUILD SUCCESSFUL. This step uses no upload key and creates no signed
Play artifact.

- [ ] **Step 6: Review and commit**

Run:

```bash
git diff --check
git diff -- app/build.gradle.kts
git add app/build.gradle.kts
git commit -m "build: prepare Play upload signing"
```

Expected: one build-file commit with no path, fingerprint, or credential
beyond the property name.

---

### Task 3: Add a fail-closed Android App Bundle verifier and Play release procedure

**Files:**

- Create: `scripts/verify-release-bundle.sh`
- Create: `scripts/verify-release-bundle-script.sh`
- Modify: `RELEASING.md`
- Reference: `scripts/verify-release-apk.sh`
- Reference: `scripts/verify-release-apk-script.sh`

**Interfaces:**

- Command: `bash scripts/verify-release-bundle.sh app-release.aab`
- Required environment:
  `OPEN_TASKS_UPLOAD_CERT_SHA256` and `OPEN_TASKS_BUNDLETOOL_JAR`.
- Optional environment: `OPEN_TASKS_LLVM_OBJDUMP`, defaulting to
  `/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/llvm-objdump`.
- Produces: exit 0 plus `verify-release-bundle: all checks passed`, or exit 1
  with one non-sensitive reason.

- [ ] **Step 1: Write the fake-tool contract harness first**

Model `scripts/verify-release-bundle-script.sh` on the existing APK harness:
create one private temporary directory with a cleanup trap; fake `shasum`,
`java`, `jarsigner`, `keytool`, `unzip`, and `llvm-objdump`; read version values from
`app/build.gradle.kts`; and ensure output never contains the expected or actual
certificate material.

Cover exactly these cases:

1. missing, short, and non-hex upload fingerprint;
2. missing bundletool JAR and wrong bundletool SHA-256;
3. wrong argument count and missing AAB;
4. failed bundletool validation;
5. invalid JAR signature;
6. absent, ambiguous, and mismatched AAB signer;
7. wrong package, version name, version code, min SDK, or target SDK;
8. debuggable manifest or debug qualification component;
9. missing required permission or a denied high-risk permission;
10. missing or unexpected Drive scope;
11. missing or extra native ABI;
12. missing native library or ELF `LOAD` alignment below `2**14`; and
13. one valid fixture that exits 0.

- [ ] **Step 2: Run the RED harness**

Run:

```bash
bash scripts/verify-release-bundle-script.sh
```

Expected RED: the harness fails because `verify-release-bundle.sh` does not
yet implement the contract.

- [ ] **Step 3: Implement input and tool provenance checks**

In `verify-release-bundle.sh`, use `set -euo pipefail`, `LC_ALL=C`, a private
`mktemp -d` directory, and a cleanup trap. Validate exactly one `.aab` file and
a 64-hex fingerprint. Require executable `java`, `jarsigner`, `keytool`,
`unzip`, and `llvm-objdump` tools.

Pin bundletool to:

```text
version: 1.18.3
SHA-256: a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29
```

Compute the JAR hash with `shasum -a 256`; reject every mismatch before
executing it.

- [ ] **Step 4: Authenticate structure and signer**

Run the equivalent of:

```bash
java -jar "$OPEN_TASKS_BUNDLETOOL_JAR" validate --bundle="$aab"
jarsigner -verify -verbose -certs "$aab"
keytool -printcert -jarfile "$aab"
```

Capture `jarsigner` output and require a successful exit plus `jar verified.`;
reject `jar is unsigned.` and every verification failure. Do not use
`jarsigner -strict`: Android app certificates are intentionally self-signed, so
strict PKIX-chain validation rejects a correctly signed bundle.

Normalize the single `SHA256:` value by removing separators and lowercasing
it, then compare it with `OPEN_TASKS_UPLOAD_CERT_SHA256`. Reject absent,
ambiguous, or mismatched output without echoing either fingerprint.

- [ ] **Step 5: Inspect the merged base manifest**

Use bundletool `dump manifest` with `--module=base` and XPath queries for
package, `versionCode`, `versionName`, minimum SDK, and target SDK. Require:

```text
package = app.opentasks
versionName/versionCode = values in app/build.gradle.kts
minSdk = 36
targetSdk = 37
android:debuggable = absent or false
DriveCreateOnlyQualificationActivity = absent
```

Dump the full merged manifest once for permission/component inspection. Require
the five declared permissions `INTERNET`, `POST_NOTIFICATIONS`,
`RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, and `USE_BIOMETRIC`; require
the expected `MainActivity`, non-exported FileProvider, and permission-protected
`QuickAddTileService`; require the manifest links to `data_extraction_rules`,
`backup_rules`, and `file_paths`. Deny external-storage, contacts, SMS, call
log, location, camera, microphone, account-management, and
`QUERY_ALL_PACKAGES` permissions. Record the full final permission/export list
for human audit rather than pretending the script understands policy.

- [ ] **Step 6: Inspect scopes, native ABIs, and ELF alignment**

List AAB entries with `unzip -Z1`. Require both and only
`base/lib/arm64-v8a/*.so` and `base/lib/x86_64/*.so`, plus the three linked XML
resource entries. Stream `base/dex/classes*.dex` and require
`auth/drive.appdata` as the sole `auth/drive...` string. Extract native files
only into the private temporary directory and require every ELF `LOAD` segment
reported by `llvm-objdump -p` to have alignment at least `2**14`.

- [ ] **Step 7: Make the harness pass**

Run:

```bash
chmod +x scripts/verify-release-bundle.sh \
  scripts/verify-release-bundle-script.sh
bash scripts/verify-release-bundle-script.sh
```

Expected: every negative fixture is rejected, certificate tokens are absent
from captured output, and the final line reports the harness passed.

- [ ] **Step 8: Rewrite `RELEASING.md` around two supported channels**

Keep all proven direct-APK size, signer, benchmark, SBOM, disposable-AVD,
physical-device, backup, and Drive gates. Make the top-level sections
**Direct APK release** and **Google Play AAB release**. Add:

- the app-signing/upload-key split and key-loss recovery responsibilities;
- the exact external Gradle property and AAB output path;
- bundletool 1.18.3 URL/hash and `/Users/kk/Tools` local path;
- AAB verification, derived universal APK, `zipalign -c -P 16 -v 4`, signer,
  fresh-install, and 1.4.0 upgrade commands;
- internal → closed → production-access → production progression;
- no CI signing/automated upload for the first launch;
- consumed version-code handling; and
- first-release emergency unpublish plus higher-code fix, explicitly noting
  there is no previous Play release to halt back to.

Replace both `Start without restoring`/Welcome smoke instructions with the
current automatic local-first launch and More → Backup & recovery path.

- [ ] **Step 9: Run script, documentation, and stale-copy checks**

Run:

```bash
bash -n scripts/verify-release-bundle.sh
bash -n scripts/verify-release-bundle-script.sh
bash scripts/verify-release-bundle-script.sh
! rg -n "signed sideload only|Start without restoring|choose.*Welcome" RELEASING.md
rg -n "bundleRelease|openTasksKeystoreProperties|1\.18\.3|unpublish" RELEASING.md
git diff --check
```

Expected: syntax and contract checks pass; stale copy is absent; every Play
release invariant is documented.

- [ ] **Step 10: Review and commit**

Run:

```bash
git diff -- scripts/verify-release-bundle.sh \
  scripts/verify-release-bundle-script.sh RELEASING.md
git add scripts/verify-release-bundle.sh \
  scripts/verify-release-bundle-script.sh RELEASING.md
git commit -m "build: verify Play app bundles"
```

---

### Task 4: Expose the public privacy policy from More

**Files:**

- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt:105-475`
- Modify: `feature/more/src/main/res/values/strings.xml`
- Create:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/MorePrivacyPolicyInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt:245-260,870-890,1743-1930`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**

- Adds `onOpenPrivacyPolicy: () -> Unit = {}` to `MoreScreen`.
- Opens `https://ksdaklmk.github.io/open-tasks/privacy/` with
  `Intent.ACTION_VIEW`.
- Produces a snackbar saying `No browser is available.` when no activity can
  handle the URL.

- [ ] **Step 1: Write the Compose callback test first**

Create a test using `createComposeRule`, `AtomicInteger`, and the existing
`OpenTasksTheme`. Render:

```kotlin
MoreScreen(
    tasks = emptyList(),
    projects = emptyList(),
    onOpenPrivacyPolicy = { opens.incrementAndGet() },
    onRestoreProject = {},
    onRestoreTask = {},
    onPermanentlyDeleteTask = {},
)
```

Find tag `open-privacy-policy`, call `performScrollTo()`, assert it is
displayed and at least 48 dp high, click once, and assert `opens.get() == 1`.

- [ ] **Step 2: Compile the RED test**

Run:

```bash
./gradlew :feature:more:compileDebugAndroidTestKotlin --stacktrace
```

Expected RED: `MoreScreen` has no `onOpenPrivacyPolicy` parameter.

- [ ] **Step 3: Add exact strings and the stateless row**

Add to the More strings:

```xml
<string name="privacy_policy_title">Privacy policy</string>
<string name="privacy_policy_supporting">How Open Tasks handles data</string>
```

Add the defaulted callback beside the existing privacy callbacks. Directly
after **Privacy & lock** and before **Backup & recovery**, add a
`DestinationRow` using the already imported `Icons.Rounded.Description`, the
two strings, `onOpenPrivacyPolicy`, and test tag `open-privacy-policy`. Reuse
`DestinationRow`; its existing 64 dp minimum surface already supplies the
accessible target.

- [ ] **Step 4: Wire the fixed URL at the app root**

Add one private constant near the existing root constants:

```kotlin
private const val PRIVACY_POLICY_URL =
    "https://ksdaklmk.github.io/open-tasks/privacy/"
```

Add `privacy_policy_no_browser` with exact text `No browser is available.` to
the app strings. Beside `openSystemSettings()`, add:

```kotlin
fun openPrivacyPolicy() {
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri()))
    } catch (_: ActivityNotFoundException) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                activity.getString(R.string.privacy_policy_no_browser),
            )
        }
    }
}
```

Pass `onOpenPrivacyPolicy = ::openPrivacyPolicy` to the existing `MoreScreen`
call. Reuse imports already present in `OpenTasksApp.kt`.

- [ ] **Step 5: Run focused GREEN checks**

Run:

```bash
./gradlew :feature:more:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin --stacktrace
```

Expected: BUILD SUCCESSFUL.

On an explicitly disposable device only, run:

```bash
./gradlew :feature:more:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.opentasks.feature.more.MorePrivacyPolicyInstrumentedTest \
  --stacktrace
```

Expected: one test passes. At 200% font, manually confirm the row remains
readable, focusable, and clickable without clipping. If no disposable device
is approved, record the connected/manual checks as PENDING; compilation is not
a substitute.

- [ ] **Step 6: Run the module regression and commit**

Run:

```bash
./gradlew :feature:more:testDebugUnitTest :app:testDebugUnitTest --stacktrace
git diff --check
git diff -- feature/more/src app/src/main
git add \
  feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt \
  feature/more/src/main/res/values/strings.xml \
  feature/more/src/androidTest/kotlin/app/opentasks/feature/more/MorePrivacyPolicyInstrumentedTest.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt \
  app/src/main/res/values/strings.xml
git commit -m "feat: link Play privacy policy"
```

---

### Task 5: Version the store listing and declaration evidence

**Files:**

- Create: `docs/google-play/store-listing.md`
- Modify: `docs/qualification/release-1.5.0-play.md`
- Read: `app/src/main/AndroidManifest.xml`
- Read: `app/src/main/res/xml/data_extraction_rules.xml`
- Read: `app/src/main/res/xml/backup_rules.xml`
- Read: `app/src/main/kotlin/app/opentasks/backup/`
- Read: `app/src/main/kotlin/app/opentasks/backup/drive/`
- Read: `gradle/libs.versions.toml`

**Interfaces:**

- Produces the single source of truth for en-GB Play copy, app-content answers,
  the field-by-field Data Safety audit, public URLs, and asset filenames.
- Consumes final-binary evidence again in Task 9 before Console submission.

- [ ] **Step 1: Capture exact listing copy**

Create `docs/google-play/store-listing.md` with fixed identity, category,
price, ads, audience, availability, privacy/support URLs, and this copy:

```text
App name
Open Tasks

Short description
Private, local-first tasks and projects with optional encrypted backup

Full description
Plan tasks, projects and your day without creating an account.

Open Tasks keeps your workspace in an encrypted vault on your device. Capture tasks quickly, organise projects and tags, plan dates and reminders, use focus timers, and review work from phone or large-screen layouts.

Your workspace works locally by default. When you choose, you can create encrypted backups in your private Google Drive app-data storage, prepare an Android backup package, or export an encrypted Open Tasks vault. Import tools are available from More.

Open Tasks has no ads, analytics or in-app purchases.

Release notes
First Google Play release. Open Tasks starts directly in a private local workspace, with import and encrypted backup and recovery available from More.
```

Do not claim live sync, collaboration, multi-user sharing, Google affiliation,
absolute security, a desktop/web app, or broad Android compatibility.

- [ ] **Step 2: Verify Play text limits mechanically**

Copy each field literally into shell variables and run:

```bash
test "$(printf %s "$short_description" | wc -m | tr -d ' ')" -le 80
test "$(printf %s "$full_description" | wc -m | tr -d ' ')" -le 4000
test "$(printf %s "$release_notes" | wc -m | tr -d ' ')" -le 500
```

Expected: all commands exit 0. Also proofread as English (United Kingdom):
`organise`, no unsupported claims, and no account requirement.

- [ ] **Step 3: Build the Data Safety evidence matrix, not a guessed answer**

Add one row per Play data type implicated by shipped behavior: task titles and
bodies, notes, projects, tags, schedules, reminders, timers/time entries,
attachments/imported files, settings, backup metadata, optional Google account
identifier, diagnostics, and device/app identifiers. Give every row these
columns:

```text
source path | on-device processing | leaves device | recipient/path |
Play collection classification | Play sharing classification |
required or optional | purpose | encrypted in transit | deletion path |
final AAB evidence | Console answer
```

Use `PENDING — final AAB audit` where the source/dependency review is not enough.
Do not collapse the table into “no data collected.” Specifically examine:

- automatic encrypted Drive backups after a user enables them;
- encrypted Drive app-data metadata and any retained account binding;
- the explicitly allowed encrypted Android backup package;
- plaintext CSV and Markdown exports chosen by the user;
- encrypted `.otvault` import/export;
- Google Play Services authorization behavior; and
- diagnostics supplied by Google Play versus SDK code embedded in the app.

- [ ] **Step 4: Audit dependencies, networking, permissions, and scopes**

Run and inspect, without committing the generated dependency report:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath \
  > /private/tmp/open-tasks-release-runtime-dependencies.txt
rg -n "https?://|drive\.appdata|GoogleSignIn|Authorization|analytics|telemetry|crash|Firebase|AD_ID|AdvertisingId" \
  app core feature gradle/libs.versions.toml
rg -n "uses-permission|exported=|allowBackup|dataExtractionRules|fullBackupContent" \
  app/src/main/AndroidManifest.xml app/src/main/res/xml
```

Expected: no ads, analytics, billing, Firebase, advertising ID, or
developer-operated application backend; one active Drive scope,
`drive.appdata`; and an explainable permission/export/backup surface. Reconcile
every unexpected match before continuing.

- [ ] **Step 5: Record App Content answers and reviewer instructions**

Add an evidence-backed matrix covering:

- ads: no;
- app access: unrestricted core app; no login credentials required;
- target audience: 13 and older; not directed to children/Families;
- content rating: ordinary task/productivity content only, answered from the
  actual questionnaire rather than a copied rating;
- no billing/in-app purchases, financial features, government, news, health,
  dating, gambling, VPN, or social/collaboration behavior;
- no Open Tasks account and therefore no app-account deletion flow;
- optional Google authorization only for encrypted backup/recovery;
- exact-alarm, notification, boot, biometric, Internet, FileProvider, exported
  activity/service, and backup purposes; and
- reviewer path: fresh launch opens a local workspace; More contains Privacy
  policy, imports, and Backup & recovery; Drive sign-in is optional.

Mark any Console-only policy question PENDING until its current wording is
visible. Never force an answer from this document when the question differs.

- [ ] **Step 6: Add the asset manifest contract**

List these exact future filenames, dimensions, content, and alt text:

```text
icon-512.png
feature-graphic-1024x500.png
phone-01-home.png
phone-02-tasks.png
phone-03-project.png
phone-04-more-backup.png
large-01-home.png
large-02-project-board.png
large-03-schedule.png
large-04-more-backup.png
```

Mark each `PENDING — captured from final release UI` until Task 10. State that
all workspace content is synthetic and all account names, identifiers,
notification previews, attachment names, and recovery secrets are excluded.

- [ ] **Step 7: Review for consistency and commit**

Run:

```bash
rg -n "Open Tasks|app\.opentasks|Productivity|English \(United Kingdom\)|13|No ads|drive\.appdata|privacy/|support/" \
  docs/google-play/store-listing.md
! rg -ni "real-time sync|collaborate with|100% secure|unbreakable|Google Tasks integration" \
  docs/google-play/store-listing.md
git diff --check
git diff -- docs/google-play/store-listing.md \
  docs/qualification/release-1.5.0-play.md
git add docs/google-play/store-listing.md \
  docs/qualification/release-1.5.0-play.md
git commit -m "docs: prepare Play listing and declarations"
```

Expected: the first scan finds the fixed facts, the prohibited-claim scan has
no match, and the committed declaration document clearly separates decided
answers from pending final-binary/Console evidence.

---

### Task 6: Publish static privacy and support pages through GitHub Pages

**Files:**

- Create: `site/privacy/index.html`
- Create: `site/support/index.html`
- Create: `.github/workflows/pages.yml`
- Modify: `scripts/verify-actions-workflow.sh`
- Modify: `docs/qualification/release-1.5.0-play.md`

**Interfaces:**

- Publishes only `site/` at:
  `https://ksdaklmk.github.io/open-tasks/privacy/` and
  `https://ksdaklmk.github.io/open-tasks/support/`.
- Support routes to `https://github.com/ksdaklmk/open-tasks/issues`.
- Uses no JavaScript, cookies, analytics, form, third-party font, or remote
  stylesheet.

- [ ] **Step 1: Resolve the public developer identity before writing HTML**

The owner reads the exact public developer display name from the verified Play
account. Put that literal value in the privacy page and record its verification
date in the private owner evidence. If the account does not yet expose a final
display name, complete that owner setting first; do not commit a token, blank,
repository username assumption, legal identity, or private email address.

- [ ] **Step 2: Make the workflow policy check fail first**

Extend `scripts/verify-actions-workflow.sh` to require a Pages workflow, four
exact full-length action SHAs, top-level least-privilege permissions, the
`github-pages` environment, `site` upload path, and no secret/token printing.
Then run:

```bash
bash scripts/verify-actions-workflow.sh
```

Expected RED: `.github/workflows/pages.yml` does not exist.

- [ ] **Step 3: Write the privacy page as one self-contained static file**

Use semantic HTML (`header`, `main`, headings, paragraphs, lists, and links),
responsive inline CSS, visible keyboard focus, readable contrast, and
`lang="en-GB"`. The title is **Open Tasks privacy policy** and the effective
date is **27 August 2026**. Identify Open Tasks, package `app.opentasks`, and
the exact verified public developer display name.

Cover, in plain language:

- local encrypted vault data: tasks/descriptions, notes, projects, tags,
  schedules, timers, reminders, attachments, and settings;
- local-first use with no account and no developer-operated backend;
- optional Google authorization, local account binding, backup metadata,
  encrypted Drive `appDataFolder` objects, and automatic backups after enable;
- Android backup's explicitly allowed encrypted portable package;
- encrypted `.otvault` transfer and plaintext CSV/Markdown export disclosure;
- permission purposes for Internet, notifications, boot, exact alarms, and
  biometric unlock;
- Google Play, Google Drive, and Android backup providers;
- no ads, sale of data, embedded analytics, or in-app purchases;
- retention/deletion: local data until in-app deletion, clear-data, or
  uninstall; Bin retention of 30 days; Drive delete-history; disconnect stops
  new requests but may leave backups; provider/export deletion remains under
  user control;
- not directed to children under 13;
- reasonable security limits and responsibility for recovery secrets;
- dated future policy changes; and
- the support URL for privacy/support requests.

- [ ] **Step 4: Write the support page**

State that Open Tasks is free/ad-free and core use requires no account. Give
short routes for app help, import/backup recovery, and public bug reports. Link
to GitHub Issues and warn users not to post task contents, notes, attachment
contents/names, exported files, recovery phrases/passphrases, credentials,
Google account details, device identifiers, or other personal data. Do not add
a form or promise private/email support that has not been configured.

- [ ] **Step 5: Add the pinned Pages workflow**

Create exactly this minimal workflow:

```yaml
name: Pages

on:
  push:
    branches: [main]
    paths:
      - "site/**"
      - ".github/workflows/pages.yml"
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: pages
  cancel-in-progress: false

jobs:
  deploy:
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
      - uses: actions/configure-pages@983d7736d9b0ae728b81ab479565c72886d7745b # v5
      - uses: actions/upload-pages-artifact@7b1f4a764d45c48632c6b24a0339c27f5614fb0b # v4
        with:
          path: site
      - name: Deploy
        id: deployment
        uses: actions/deploy-pages@d6db90164ac5ed86f2b6aed7e0febac5b3c0c03e # v4
```

Do not add a build framework: the two HTML files are already the deployable
artifact.

- [ ] **Step 6: Run local policy/content checks**

Run:

```bash
bash scripts/verify-actions-workflow.sh
test "$(find site -type f | sort | wc -l | tr -d ' ')" -eq 2
! rg -ni "<script|<form|googletag|google-analytics|fonts\.google|http:" site
rg -n "27 August 2026|app\.opentasks|Google Drive|appDataFolder|30 days|under 13|GitHub|analytics|cookies" site
git diff --check
```

Expected: workflow verification passes, only the two HTML files exist, no
forbidden web behavior is present, and required disclosures are found.

- [ ] **Step 7: Review and commit the deployable site**

Run:

```bash
git diff -- site .github/workflows/pages.yml scripts/verify-actions-workflow.sh
git add site/privacy/index.html site/support/index.html \
  .github/workflows/pages.yml scripts/verify-actions-workflow.sh
git commit -m "docs: publish privacy and support pages"
```

- [ ] **Step 8: Owner enables and verifies GitHub Pages after integration**

After the commit reaches `main`, the owner selects **GitHub Actions** as the
Pages source if it is not already selected, dispatches or observes the pinned
Pages workflow, and waits for a successful deployment. Then run with network
approval:

```bash
curl --fail --location --silent --show-error \
  https://ksdaklmk.github.io/open-tasks/privacy/ \
  > /private/tmp/open-tasks-privacy.html
curl --fail --location --silent --show-error \
  https://ksdaklmk.github.io/open-tasks/support/ \
  > /private/tmp/open-tasks-support.html
rg -n "Open Tasks privacy policy|app\.opentasks" \
  /private/tmp/open-tasks-privacy.html
rg -n "github\.com/ksdaklmk/open-tasks/issues|do not" \
  /private/tmp/open-tasks-support.html
```

Open both URLs in a normal and private browser window, at narrow and wide
widths, and keyboard-tab through links. Expected: public HTTP 200 responses,
no authentication, correct content, no mixed content, and usable focus/order.

- [ ] **Step 9: Record deployment evidence separately**

Add the workflow run URL/commit, deployment time, HTTP/content/browser results,
and exact public developer-name match to the ledger. Record no private contact
details. Then run:

```bash
git diff --check
git add docs/qualification/release-1.5.0-play.md
git commit -m "docs: record Play policy-site deployment"
```

---

### Task 7: Complete owner verification, register the package, and configure Play signing

**Files:**

- Modify: `docs/qualification/release-1.5.0-play.md`
- Read: `docs/google-play/store-listing.md`
- External owner storage: existing key, new upload key, key backups, identity
  evidence, and Play Console evidence

**Interfaces:**

- Consumes: the authenticated existing app-signing certificate, verified
  personal developer account, deployed policy URLs, and package
  `app.opentasks`.
- Produces: registered package/app, existing certificate as Play delivery
  identity, separate upload certificate, and a private owner recovery record.

- [ ] **Step 1: Recheck current account and signing rules**

Open the official developer-verification, package-registration, and Play App
Signing references from this plan. Record the access date and whether current
Console wording still supports providing an existing custom app-signing key.
Stop and revise the design if Play now forces an incompatible signer.

- [ ] **Step 2: Finish personal-account identity and device verification**

With execution-time owner approval, complete every requested legal-name,
address, phone, email, payment-profile, and two-step verification field. If
device verification is required, use the Play Console mobile app on an audited,
non-rooted physical Android 10+ device. Keep documents, values, and screenshots
in private owner storage; record only completion state and date in Git.

- [ ] **Step 3: Register `app.opentasks` for Android developer verification**

Claim the exact package before 30 September 2026. When Play recognizes the
existing public certificate, select it. If Play asks for proof of ownership:

1. obtain the current one-time challenge from Console;
2. place it only in the proof APK location described by Play;
3. build the smallest proof APK outside the production source/history;
4. sign it with `/Users/kk/Keys/opentasks-release.jks`, alias `opentasks`;
5. authenticate that proof APK's signer before upload; and
6. delete the temporary challenge/proof workspace after successful evidence is
   retained privately.

Never add the challenge to the production app or Git. If Play says the
certificate is ineligible or the package is owned elsewhere, stop and use the
documented package-name request/appeal path with existing signed-release
evidence; do not rename the package silently.

- [ ] **Step 4: Create the Play app with fixed product settings**

Create one app using:

```text
Name: Open Tasks
Default language: English (United Kingdom)
App or game: App
Free or paid: Free
Category: Productivity
Ads: No
Target audience: 13 and older; not designed for children
Countries/regions: every Play-supported country and region
```

Accept only declarations that are factually true. Saving the app is not
permission to publish a release.

- [ ] **Step 5: Create or authenticate the separate upload key**

First check the exact path. If it already exists, stop creation and authenticate
it against the independent owner record; never overwrite it.

For a genuinely absent path, run and let `keytool` prompt securely:

```bash
keytool -genkeypair -v \
  -keystore /Users/kk/Keys/opentasks-play-upload.jks \
  -alias opentasks-upload \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=Open Tasks Upload"
```

Create `/Users/kk/Keys/opentasks-play-upload.properties` with mode 600 using a
secure local editor. It must contain actual values for exactly `storeFile`,
`storePassword`, `keyAlias`, and `keyPassword`; `storeFile` is the absolute
upload-JKS path and `keyAlias` is `opentasks-upload`. Do not display the file.

Run:

```bash
chmod 600 /Users/kk/Keys/opentasks-play-upload.properties
stat -f '%Sp %N' /Users/kk/Keys/opentasks-play-upload.properties
keytool -list -v \
  -keystore /Users/kk/Keys/opentasks-play-upload.jks \
  -alias opentasks-upload
```

Expected: mode `-rw-------`, RSA 4096, and a new SHA-1/SHA-256 different from
the app-signing certificate. Back up the JKS, passwords, public certificate,
and recovery instructions in owner-controlled primary and offline storage.

- [ ] **Step 6: Enrol the existing key in Play App Signing before testers install**

In the first-release App integrity flow, choose **Change app signing key** and
the option to provide the owner's own key. Do not accept a Google-generated or
new quantum-ready delivery key for this first release. Use Play's currently
supplied PEPK/encryption-public-key command to export an encrypted copy of
`opentasks-release.jks`; do not put passwords in arguments or Git.

If Console exposes this choice only after an AAB is uploaded, do not create a
throwaway bundle. Leave the enrolment gate PENDING, build the exact candidate
in Task 9, and complete this selection as the first action inside Task 11's
exact-candidate upload—still before making the release available to testers.

Upload only the encrypted PEPK result. Register the public upload certificate
from `opentasks-play-upload.jks`. Before any tester installs, compare:

```text
Play app-signing SHA-1/SHA-256 = existing release-key SHA-1/SHA-256
Play upload SHA-1/SHA-256 = new upload-key SHA-1/SHA-256
app-signing fingerprint != upload fingerprint
```

If any equality is wrong, do not roll out. Retain both keystores and delete
only disposable encrypted-transfer files after owner evidence is safe.

- [ ] **Step 7: Handle version-code consumption explicitly**

Record every code shown in App Bundle Explorer, including rejected/draft
uploads. If Play consumes 7 and the artifact must change, set the next unused
integer in `app/build.gradle.kts`, keep version name 1.5.0, commit that one-line
change, and repeat Tasks 2–6 where their artifact/copy evidence is affected.
Never overwrite or relabel an uploaded binary.

- [ ] **Step 8: Record non-sensitive outcomes and commit**

Add verification/package/app creation dates, public certificate fingerprints,
key algorithms/expiry, the signing configuration PASS, private evidence
references, and consumed-code list to the ledger. Run:

```bash
! rg -ni "storePassword=|keyPassword=|BEGIN .*PRIVATE KEY|[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}" \
  docs/qualification/release-1.5.0-play.md
git diff --check
git add docs/qualification/release-1.5.0-play.md
git commit -m "docs: record Play identity and signing setup"
```

Expected: the sensitive-data scan has no match. The signing gate is PASS only
if Play exposes and confirms the existing release fingerprint; otherwise its
explicit PENDING state carries into Task 11 and blocks tester availability.

---

### Task 8: Put Google authorization into production with Play delivery certificates

**Files:**

- Modify: `docs/google-play/store-listing.md`
- Modify: `docs/qualification/release-1.5.0-play.md`
- Read:
  `app/src/main/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManager.kt`
- Read:
  `app/src/androidTest/kotlin/app/opentasks/RemoteBackupBoundaryInstrumentedTest.kt`

**Interfaces:**

- Consumes: deployed privacy/support URLs and every Play app-signing SHA-1 that
  can sign an APK delivered to a supported device.
- Produces: External OAuth consent in production and an Android client for
  `app.opentasks` plus delivery SHA-1 fingerprints.

- [ ] **Step 1: Reconfirm the source-level authorization boundary**

Run:

```bash
rg -n "AuthorizationRequest|DRIVE_APPDATA_SCOPE|drive\.appdata|requestedScopes" \
  app/src/main app/src/debug
rg -n "default_web_client_id|client_secret|server_client_id" \
  app/src/main app/src/androidTest
```

Expected: production uses Google Play Services `AuthorizationClient`, requests
only `https://www.googleapis.com/auth/drive.appdata`, and embeds no client
secret/server client ID. Debug-only qualification code remains absent from the
release bundle gate.

- [ ] **Step 2: Audit the Google Auth Platform project**

With owner approval, open the existing Google Cloud project used by this app.
Set or verify:

```text
Audience: External
Publishing status: In production
Application name: Open Tasks
Privacy URL: https://ksdaklmk.github.io/open-tasks/privacy/
Support/home URL: https://ksdaklmk.github.io/open-tasks/support/
Requested scope: https://www.googleapis.com/auth/drive.appdata only
Owner contact: the verified private contact required by Google
```

Remove stale scopes only after proving no active client requires them. Do not
delete debug/direct-sideload Android clients that are still needed.

- [ ] **Step 3: Register delivery identities, never the upload identity**

For the Android OAuth client, set package `app.opentasks` and add the SHA-1 of
every certificate listed by Play as capable of signing delivery APKs for the
supported devices. The primary SHA-1 must equal the existing release key. Do
not register `opentasks-upload` as a delivery client.

If Play lists multiple delivery certificates, document their device targeting,
register every applicable SHA-1, and plan an internal install on each applicable
delivery path. A certificate that breaks direct-update compatibility is a stop
gate even if OAuth accepts it.

- [ ] **Step 4: Complete any brand/identity review without broadening scope**

`drive.appdata` is non-sensitive under the current Drive classification, so no
sensitive/restricted-scope verification is planned. If Google requests brand
or identity verification, satisfy it with the deployed public pages, exact app
name/logo, verified owner contact, and scope evidence. Do not add broader Drive
scopes to work around a review.

- [ ] **Step 5: Verify production status and record evidence**

Confirm an ordinary Google account outside any Testing-user allowlist can reach
the authorization consent flow without the Testing mode's 100-user/seven-day
limits. Full credentialed backup/restore is executed on the final installed
candidate in Tasks 9 and 11; this step proves configuration only.

Update the listing/declaration rationale and ledger with publishing status,
URLs, exact scope, package, registered delivery-certificate count, and private
evidence reference. Record no account, token, OAuth client secret, or private
contact value.

- [ ] **Step 6: Review and commit**

Run:

```bash
rg -n "In production|drive\.appdata|app\.opentasks|upload" \
  docs/google-play/store-listing.md \
  docs/qualification/release-1.5.0-play.md
git diff --check
git add docs/google-play/store-listing.md \
  docs/qualification/release-1.5.0-play.md
git commit -m "docs: record production OAuth setup"
```

Expected: documentation distinguishes delivery and upload certificates and
contains no credential material.

---

### Task 9: Build once and qualify the exact candidate off-Play

**Files:**

- Build from: the clean current source commit
- Produce, do not commit:
  `app/build/outputs/bundle/release/app-release.aab`
- Produce, do not commit:
  `app/build/outputs/bundle/release/app-release.apks`
- Produce, do not commit:
  `app/build/outputs/bundle/release/play-universal.apk`
- Modify: `docs/google-play/store-listing.md`
- Modify: `docs/qualification/release-1.5.0-play.md`
- Reference: `RELEASING.md`

**Interfaces:**

- Consumes: existing direct-release properties, external upload properties,
  independently stored public fingerprints, pinned bundletool, and disposable
  Android 16+ test hardware.
- Produces: one immutable upload-signed AAB, one app-signing-key APK set for
  offline simulation, hashes, and complete pre-Play qualification evidence.

- [ ] **Step 1: Download and authenticate standalone bundletool once**

With explicit network/filesystem approval, create `/Users/kk/Tools` if needed
and download only the official release asset:

```bash
curl --fail --location \
  --output /Users/kk/Tools/bundletool-all-1.18.3.jar \
  https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar
printf '%s  %s\n' \
  a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29 \
  /Users/kk/Tools/bundletool-all-1.18.3.jar \
  | shasum -a 256 -c -
```

Expected: `OK`. If the file already exists, run the hash check and skip the
download when it matches; never execute a mismatched JAR.

- [ ] **Step 2: Freeze source identity before building**

Run:

```bash
test -z "$(git status --porcelain)"
play_build_commit="$(git rev-parse HEAD)"
test "${#play_build_commit}" -eq 40
printf 'Build commit: `%s`\n' "$play_build_commit"
sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts
sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts
```

Expected: clean worktree, version name 1.5.0, and the next unused version code
(initially 7). Copy the command's complete `Build commit:` line into the ledger
in Step 13; all later artifact evidence binds to it.

- [ ] **Step 3: Run the repository and supply-chain gates**

Run in separate invocations so failures remain attributable:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
scripts/verify-actions-workflow.sh
./gradlew help cyclonedxBom --stacktrace
bash scripts/verify-release-size-script.sh
bash scripts/verify-release-bundle-script.sh
```

Review both CycloneDX outputs for all app modules, Room, SQLCipher, Tink, and
Bouncy Castle; verify no local configuration or credential appears. Confirm
pushed Android and Security workflows for `build_commit` are green before
promotion.

- [ ] **Step 4: Build and authenticate the direct APK set**

Provision the existing signer fingerprint from the independent owner record in
the environment without echoing it. Run:

```bash
./gradlew :app:assembleRelease --stacktrace
scripts/check-release-size.sh \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk \
  app/build/outputs/apk/release/app-universal-release.apk
bash scripts/verify-release-apk.sh \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk \
  app/build/outputs/apk/release/app-x86_64-release.apk \
  app/build/outputs/apk/release/app-universal-release.apk
unset OPEN_TASKS_RELEASE_CERT_SHA256
```

Expected: all three are signed by the existing app-signing key, 1.5.0 with the
current code, non-debuggable, correctly split, and `drive.appdata`-only.

- [ ] **Step 5: Build the Play AAB once with the upload key**

Run:

```bash
./gradlew :app:bundleRelease \
  -PopenTasksKeystoreProperties=/Users/kk/Keys/opentasks-play-upload.properties \
  --stacktrace
```

Expected: exactly
`app/build/outputs/bundle/release/app-release.aab`; no new source diff. From
this point, do not rebuild or modify it. A changed binary becomes a new
candidate with a new version code.

- [ ] **Step 6: Authenticate the AAB with independent upload-key evidence**

Set the public upload fingerprint from the owner record, then run:

```bash
export OPEN_TASKS_BUNDLETOOL_JAR=/Users/kk/Tools/bundletool-all-1.18.3.jar
export OPEN_TASKS_LLVM_OBJDUMP=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/llvm-objdump
bash scripts/verify-release-bundle.sh \
  app/build/outputs/bundle/release/app-release.aab
unset OPEN_TASKS_UPLOAD_CERT_SHA256
```

Expected: the verifier passes package, version, SDK, upload signer, manifest,
permission, Drive scope, ABI, resource-linkage, and 16 KB ELF gates. Preserve
its output without certificate values.

- [ ] **Step 7: Generate an offline Play-like universal APK with the delivery key**

Let bundletool prompt for keystore passwords; never add `--ks-pass` or
`--key-pass` values to the command line:

```bash
java -jar /Users/kk/Tools/bundletool-all-1.18.3.jar build-apks \
  --bundle=app/build/outputs/bundle/release/app-release.aab \
  --output=app/build/outputs/bundle/release/app-release.apks \
  --mode=universal \
  --ks=/Users/kk/Keys/opentasks-release.jks \
  --ks-key-alias=opentasks \
  --overwrite
unzip -p app/build/outputs/bundle/release/app-release.apks universal.apk \
  > app/build/outputs/bundle/release/play-universal.apk
```

Locate the newest installed Android build-tools directory and run its
`zipalign` and `apksigner`:

```bash
play_build_tools="$(find /Users/kk/Library/Android/sdk/build-tools \
  -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
test -x "$play_build_tools/zipalign"
test -x "$play_build_tools/apksigner"
"$play_build_tools/zipalign" \
  -c -P 16 -v 4 app/build/outputs/bundle/release/play-universal.apk
"$play_build_tools/apksigner" \
  verify --print-certs app/build/outputs/bundle/release/play-universal.apk
```

Expected: 4/16 KB alignment passes and signer SHA-256 equals the existing app
signing key, not the upload key.

- [ ] **Step 8: Reproduce the exact 1.4.0 upgrade source safely**

Use only the explicit temporary path below; abort if it already exists:

```bash
test ! -e /private/tmp/open-tasks-v1.4.0
git worktree add --detach /private/tmp/open-tasks-v1.4.0 v1.4.0
ln -s /Users/kk/projects/open-tasks/keystore.properties \
  /private/tmp/open-tasks-v1.4.0/keystore.properties
cd /private/tmp/open-tasks-v1.4.0
./gradlew :app:assembleRelease --stacktrace
cp app/build/outputs/apk/release/app-universal-release.apk \
  /private/tmp/open-tasks-v1.4.0.apk
cd /Users/kk/projects/open-tasks
unlink /private/tmp/open-tasks-v1.4.0/keystore.properties
git worktree remove /private/tmp/open-tasks-v1.4.0
```

Authenticate `/private/tmp/open-tasks-v1.4.0.apk` against the independent
existing-key fingerprint and confirm 1.4.0/6 before installing it. If the
temporary build fails, remove only the explicit keystore-properties symlink,
retain diagnostics as needed, and remove this exact worktree before retrying.

- [ ] **Step 9: Prove offline fresh install and 1.4.0 upgrade**

On one explicitly approved disposable Play-capable Android 16+ device:

1. install the authenticated 1.4.0 APK;
2. create a synthetic project, task, checklist, tag, reminder, attachment,
   widget, focus session, backup configuration, and app-lock setting;
3. record their expected synthetic values;
4. run `adb install -r` with `play-universal.apk`, never uninstalling first;
5. verify every record, attachment, alarm/reminder, widget, setting, vault
   unlock, and local-first startup survives; and
6. separately clear only the disposable package, install the derived APK
   fresh, and confirm automatic local workspace startup and optional Drive UI.

Expected: `Success` for the update, no signature incompatibility, no data loss,
no Welcome/forced Google sign-in, and no authorization request during core use.

- [ ] **Step 10: Run release qualification from `RELEASING.md`**

On approved disposable targets, execute the full connected suite, physical
API 36 arm64 benchmark, Android backup package restore, browser/print/share,
notifications/reminders/widget/quick-tile/app-lock, accessibility/font-scale,
and owner-present credentialed Drive backup/list/restore/takeover/delete-history
gates. Use the current accepted benchmark JSON and exact commands in
`RELEASING.md`; emulator timing is never threshold evidence.

For Drive, prove an ordinary production-audience Google account can authorize,
create/read the encrypted app-data backup, restore through the existing safe
path, disconnect, and delete remote history as documented. Record no account,
token, vault content, attachment content, or recovery secret.

- [ ] **Step 11: Hash and inventory the immutable candidate**

Run:

```bash
shasum -a 256 \
  app/build/outputs/bundle/release/app-release.aab \
  app/build/outputs/bundle/release/app-release.apks \
  app/build/outputs/bundle/release/play-universal.apk \
  app/build/outputs/mapping/release/mapping.txt \
  build/reports/cyclonedx/bom.json \
  build/reports/cyclonedx/bom.xml
find app/build/outputs -path '*native-debug-symbols*' -type f -print
```

Record every hash and whether a native debug-symbol archive exists. Preserve
the exact AAB, APK set, derived APK, mapping, symbols when generated, and SBOMs
without committing binaries. Re-run the AAB hash immediately before upload.

- [ ] **Step 12: Reconcile declarations against the final binary**

Use the verifier's full manifest permission/export output, final dependency
graph, DEX scope output, and observed network/backup behavior to finish every
Data Safety row and Console answer in `store-listing.md`. Any mismatch with the
deployed privacy page must be corrected and redeployed before upload; any
unexpected runtime behavior is a release blocker.

- [ ] **Step 13: Record qualification and commit evidence**

Add build commit, candidate code/hash, tool versions, all PASS/FAIL/PENDING
outcomes, disposable-device identifiers at non-personal model/API/ABI granularity,
and paths to private/raw evidence. Then run:

```bash
play_build_commit="$(sed -n \
  's/^Build commit: `\([0-9a-f][0-9a-f]*\)`$/\1/p' \
  docs/qualification/release-1.5.0-play.md)"
test "${#play_build_commit}" -eq 40
test "$play_build_commit" = "$(git rev-parse HEAD)"
test -z "$(git status --porcelain --untracked-files=no | \
  grep -v 'docs/google-play/store-listing.md' | \
  grep -v 'docs/qualification/release-1.5.0-play.md')"
git diff --check
git add docs/google-play/store-listing.md \
  docs/qualification/release-1.5.0-play.md
git commit -m "docs: qualify Play release candidate"
```

Expected: no Critical/High issue or unresolved artifact gate; the commit
changes evidence only and records the earlier `build_commit` precisely.

---

### Task 10: Produce the final Play listing assets from the qualified UI

**Files:**

- Create: `docs/google-play/assets/icon-512.png`
- Create: `docs/google-play/assets/feature-graphic-1024x500.png`
- Create: `docs/google-play/assets/phone-01-home.png`
- Create: `docs/google-play/assets/phone-02-tasks.png`
- Create: `docs/google-play/assets/phone-03-project.png`
- Create: `docs/google-play/assets/phone-04-more-backup.png`
- Create: `docs/google-play/assets/large-01-home.png`
- Create: `docs/google-play/assets/large-02-project-board.png`
- Create: `docs/google-play/assets/large-03-schedule.png`
- Create: `docs/google-play/assets/large-04-more-backup.png`
- Modify: `docs/google-play/store-listing.md`
- Modify: `docs/qualification/release-1.5.0-play.md`
- Source:
  `/Users/kk/Downloads/deliverables-1a-ember/playstore/ic_launcher-playstore-512.png`

**Interfaces:**

- Produces a Play-valid 512 px icon, opaque 1024×500 feature graphic, four
  1080×1920 phone screenshots, and four 1920×1080 large-screen screenshots.
- Every screenshot shows the exact Task 9 candidate UI with synthetic data and
  no account/provider/private information.

- [ ] **Step 1: Verify and copy the approved store icon**

Run:

```bash
sips -g format -g pixelWidth -g pixelHeight -g bitsPerSample \
  -g samplesPerPixel \
  /Users/kk/Downloads/deliverables-1a-ember/playstore/ic_launcher-playstore-512.png
stat -f '%z' \
  /Users/kk/Downloads/deliverables-1a-ember/playstore/ic_launcher-playstore-512.png
mkdir -p docs/google-play/assets
cp /Users/kk/Downloads/deliverables-1a-ember/playstore/ic_launcher-playstore-512.png \
  docs/google-play/assets/icon-512.png
```

Expected: PNG, 512×512, 8 bits per sample/four samples (32-bit), and at most
1,024 KiB. Compare it visually with the installed Ember launcher icon; do not
redraw or mask it.

- [ ] **Step 2: Compose the minimal feature graphic**

Create one opaque 1024×500 PNG using only the approved Ember palette and glyph:
full-bleed `#C64E2B`, the white-card/charcoal task glyph, and the words
**Open Tasks**. Keep meaningful content inside the central safe area, use no
device frame, provider logo, review badge, price/discount, ranking claim, or
unsupported product claim. Export as 24-bit RGB PNG with no alpha and at most
15 MiB.

- [ ] **Step 3: Prepare a privacy-safe synthetic workspace**

Install `play-universal.apk` from Task 9 on an approved disposable phone-size
target. Through the release UI, create neutral examples such as planning a
week, preparing a report, reviewing a design, and booking a venue. Include
projects, tags, dates, a checklist, and a reminder so real features are visible.

Before capture:

- disconnect Google Drive and ensure no account chooser/name/avatar is shown;
- use invented attachment names and no imported personal file;
- hide notifications and system UI that exposes accounts/device identifiers;
- leave app lock unlocked and title privacy off only on the disposable data;
- use the light theme and default 100% font for listing consistency; and
- verify the candidate hash still equals the Task 9 ledger.

- [ ] **Step 4: Capture four 9:16 phone images**

Use a 1080×1920 app viewport and capture the actual release UI, without a
promotional frame:

1. Home with today's synthetic plan and search action;
2. Tasks showing useful grouping/checklist/reminder state;
3. one project planning/detail surface; and
4. More showing Privacy policy plus Backup & recovery without implying Drive
   is required.

Capture with the audited device ID selected explicitly:

```bash
device_serials="$(/Users/kk/Library/Android/sdk/platform-tools/adb devices \
  | awk 'NR > 1 && $2 == "device" { print $1 }')"
test "$(printf '%s\n' "$device_serials" | sed '/^$/d' | wc -l | tr -d ' ')" -eq 1
device_serial="$device_serials"
/Users/kk/Library/Android/sdk/platform-tools/adb -s "$device_serial" \
  exec-out screencap -p > docs/google-play/assets/phone-01-home.png
```

Repeat after navigating for the remaining exact filenames. Never use a shell
variable until `adb devices -l` proves it names the sole approved target.

- [ ] **Step 5: Capture four 16:9 large-screen images**

Install the same retained candidate APK on a disposable large-screen/fold/tablet
configuration with a 1920×1080 app viewport. Use synthetic data and capture:

1. Home in its actual expanded/adaptive layout;
2. project board/planning layout;
3. Schedule; and
4. More/Backup & recovery.

Do not stretch a phone image or fabricate panes. Confirm the app itself
rendered each large-screen arrangement before capture.

- [ ] **Step 6: Validate every asset mechanically and visually**

Run:

```bash
for image in docs/google-play/assets/*.png; do
  sips -g format -g pixelWidth -g pixelHeight -g hasAlpha "$image"
done
test "$(find docs/google-play/assets -name 'phone-*.png' | wc -l | tr -d ' ')" -eq 4
test "$(find docs/google-play/assets -name 'large-*.png' | wc -l | tr -d ' ')" -eq 4
test "$(stat -f '%z' docs/google-play/assets/icon-512.png)" -le 1048576
test "$(stat -f '%z' docs/google-play/assets/feature-graphic-1024x500.png)" -le 15728640
shasum -a 256 docs/google-play/assets/*.png
```

Expected dimensions:

```text
icon-512.png: 512 × 512
feature-graphic-1024x500.png: 1024 × 500, hasAlpha no
phone-*.png: 1080 × 1920
large-*.png: 1920 × 1080
```

Inspect all images at 100% and thumbnail size. Reject clipping, illegible text,
real/private content, account identity, debug UI, broken adaptive layout,
misleading claims, or status-bar surprises.

Show one contact sheet of the ten final assets to the owner and obtain explicit
visual/content approval before uploading or committing them. A screenshot that
needs explanatory copy to avoid misleading users should be replaced, not
defended in the manifest.

- [ ] **Step 7: Finish the asset manifest and alt text**

Replace every pending asset status in `store-listing.md` with dimensions,
SHA-256, source/capture target, candidate version/hash, privacy review result,
and one concise descriptive alt text. Confirm the four phone and four
large-screen narratives match shipped behavior and do not imply Google Drive
is mandatory.

- [ ] **Step 8: Review and commit**

Run:

```bash
git diff --check
git diff --stat -- docs/google-play/assets docs/google-play/store-listing.md \
  docs/qualification/release-1.5.0-play.md
git add docs/google-play/assets docs/google-play/store-listing.md \
  docs/qualification/release-1.5.0-play.md
git commit -m "docs: add Play listing assets"
```

Expected: ten PNGs plus documentation/evidence only. If any application source
changed after Task 9, discard the candidate and return to the next version code
instead of preserving stale screenshots/evidence.

---

### Task 11: Upload the immutable candidate and pass internal testing

**Files:**

- Modify: `docs/qualification/release-1.5.0-play.md`
- Read: `docs/google-play/store-listing.md`
- Upload, do not modify:
  `app/build/outputs/bundle/release/app-release.aab`
- Upload when available: R8 mapping and native debug-symbol archive

**Interfaces:**

- Consumes: exact Task 9 AAB/hash, final listing/assets/declarations, Play
  app-signing setup, production OAuth, and approved test accounts/devices.
- Produces: an internal Play release with verified delivery signer, green
  Play checks/pre-launch review, fresh-install proof, and direct-1.4.0-to-Play
  update proof.

- [ ] **Step 1: Run the pre-upload immutability gate**

Parse the exact source commit from the ledger, re-provision the expected upload
fingerprint from its independent owner record, and run:

```bash
play_build_commit="$(sed -n \
  's/^Build commit: `\([0-9a-f][0-9a-f]*\)`$/\1/p' \
  docs/qualification/release-1.5.0-play.md)"
test "${#play_build_commit}" -eq 40
git cat-file -e "$play_build_commit^{commit}"
shasum -a 256 app/build/outputs/bundle/release/app-release.aab
export OPEN_TASKS_BUNDLETOOL_JAR=/Users/kk/Tools/bundletool-all-1.18.3.jar
export OPEN_TASKS_LLVM_OBJDUMP=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/llvm-objdump
bash scripts/verify-release-bundle.sh \
  app/build/outputs/bundle/release/app-release.aab
unset OPEN_TASKS_UPLOAD_CERT_SHA256
```

Expected: AAB hash equals the ledger exactly, verifier passes, source build
commit is recorded, and the version code is unused in Play. Do not rebuild to
make a new timestamped equivalent.

- [ ] **Step 2: Complete the Console draft before rollout**

Enter the exact en-GB listing copy, upload the ten committed assets, set
Productivity/free/no-ads/all-regions/13+, and complete privacy policy, support,
Data Safety, App Content, content rating, app access, and every dashboard task
from the final evidence. Use the verified developer support email from the
account; do not commit it merely to satisfy Console.

Review each saved Console answer against `store-listing.md` and the deployed
privacy page. A disagreement is a stop, not a paperwork exception.

- [ ] **Step 3: Create the internal release with the exact artifact**

With explicit owner approval, upload only the retained AAB to the internal
track and use the committed release notes. Upload the matching R8 mapping and
native debug symbols when present. Record the upload time, version code, Play
artifact ID, and Console-displayed SHA-256; compare it with the local hash.

If Task 7 left app-signing enrolment PENDING because Console required this first
AAB, immediately choose **Change app signing key**, complete Play's current PEPK
flow with the existing release key, and register the separate upload
certificate before saving or rolling out. Do not allow an automatically
generated delivery key to become the tester signer.

Do not make the release available until App integrity shows the existing
release certificate as the app-signing key and the separate upload certificate
as the upload key. Reconcile every resulting delivery SHA-1 with the production
OAuth Android client before a tester installs.

- [ ] **Step 4: Inspect App Bundle Explorer and generated delivery**

In App Bundle Explorer, verify package, version, target SDK, supported devices,
ABIs, permissions, download sizes, signing certificates, and 16 KB page-size
status. Download a generated device APK set when Console allows it, hash it,
and authenticate its base APK signer. The delivered signer must equal the
existing 1.4.0 signer on every supported delivery path.

- [ ] **Step 5: Review automated Play findings before human testing**

Wait for app-bundle validation, policy checks, and the pre-launch report. Triage
every crash, ANR, security warning, accessibility warning, unsupported-device
finding, permission warning, and startup screenshot. Reproduce any material
finding locally. Critical/High, startup, data-loss, signer, backup, or Drive
findings block rollout; document evidence before classifying a benign warning.

- [ ] **Step 6: Prove the Play-delivered 1.4.0 update path**

On an audited, non-rooted, Play-capable disposable Android 16+ physical device
with an approved test Google account:

1. install authenticated direct-sideload 1.4.0/6;
2. create the same synthetic preservation dataset from Task 9;
3. opt the account into the internal test;
4. wait for Play Store to offer Open Tasks 1.5.0/current code;
5. update through Play Store without uninstalling or clearing data;
6. verify all vault records, attachments, settings, reminders/alarms, widget,
   focus state, backup configuration, and app lock; and
7. verify installer source and pull the installed base APK for signer proof.

Use:

```bash
device_serials="$(/Users/kk/Library/Android/sdk/platform-tools/adb devices \
  | awk 'NR > 1 && $2 == "device" { print $1 }')"
test "$(printf '%s\n' "$device_serials" | sed '/^$/d' | wc -l | tr -d ' ')" -eq 1
device_serial="$device_serials"
/Users/kk/Library/Android/sdk/platform-tools/adb -s "$device_serial" \
  shell pm list packages -i app.opentasks
/Users/kk/Library/Android/sdk/platform-tools/adb -s "$device_serial" \
  shell dumpsys package app.opentasks
```

Resolve the exact `pm path app.opentasks`, pull its base APK, and run
`apksigner verify --print-certs`. Expected: Play is the installer, version is
the internal candidate, signer equals the existing release key, and every
synthetic item survives. Any uninstall/reinstall prompt or lost state blocks
closed testing.

- [ ] **Step 7: Prove a fresh Play install independently**

On a second clean disposable device/profile, or only after the upgrade dataset
is no longer needed and the owner approves clearing it, opt in and install from
Play. Verify automatic local workspace creation, core task/project/schedule/
focus/import/widget/reminder/app-lock flows, no Welcome page, no forced Google
authorization, the More privacy link, and optional backup/recovery.

Then authorize a production-audience Google account deliberately and repeat
the encrypted Drive create/list/restore/delete-history check on the
Play-delivered build. Record only anonymized outcomes.

- [ ] **Step 8: Complete the internal critical-journey matrix**

Record PASS/FAIL for startup, task/project CRUD, checklist/dependencies,
schedule/reminders, Today widget, quick tile/shortcut/share/process-text,
focus timing, notes/attachments/search, generic and strict CSV import,
CSV/Markdown export disclosures, `.otvault` transfer, Android backup package,
Drive backup/recovery, app lock/title privacy/screenshot blocking, rotation,
large-screen layout, 200% font, keyboard navigation, and offline core use.

Run the test for at least one supported arm64 device. x86_64 remains an
emulator/package qualification path unless Play reports a compatible physical
device; do not invent hardware evidence.

- [ ] **Step 9: Decide internal promotion or rebuild**

Promote only if:

- local and Play hashes map to the same candidate code;
- upload and delivery signers have the correct distinct roles;
- fresh install and direct upgrade both pass;
- Play checks/pre-launch findings are resolved or evidence-backed benign;
- Drive authorization/restore works from the Play-delivered signer;
- declarations/listing/pages match behavior; and
- no Critical/High issue remains.

A code/resource fix returns to Task 2 with the next unused version code and
repeats all artifact-dependent gates. A Console-only copy correction is
versioned in `store-listing.md` and redeployed/re-entered without rebuilding.

- [ ] **Step 10: Record internal evidence and commit**

Add the Play artifact ID/hash, delivered fingerprint(s), device-class coverage,
pre-launch disposition, upgrade/fresh results, Drive result, internal tester
count, defects, and promotion decision. Include no tester identity or device
serial. Run:

```bash
git diff --check
git add docs/qualification/release-1.5.0-play.md
git commit -m "docs: record internal Play qualification"
```

---

### Task 12: Complete the mandatory closed test with continuous evidence

**Files:**

- Modify: `docs/qualification/release-1.5.0-play.md`
- Read: `docs/google-play/store-listing.md`
- External owner storage: tester invitation list, private communications, and
  daily Console snapshots

**Interfaces:**

- Consumes: fully configured Play app and internally qualified candidate.
- Produces: at least 12 compatible testers continuously opted in for 14 full
  days, meaningful use/feedback evidence, resolved defects, and an enabled
  production-access application.

- [ ] **Step 1: Reconfirm the current personal-account rule in Console**

Open the official personal-account test requirement and the app's dashboard.
Verify that Play currently requires at least 12 testers continuously opted into
a closed test for 14 days and that the account is subject to it. Record exact
Console wording/date privately and the non-sensitive rule result in the ledger.
If the requirement changes, update this plan before starting the clock.

- [ ] **Step 2: Complete every prerequisite before recruiting testers**

Confirm:

- app setup/dashboard has no blocking item;
- listing, assets, privacy/support, Data Safety, App Content, content rating,
  audience, app access, ads, and region availability are complete;
- the internal artifact and signer/upgrade/Drive gates are green;
- the closed release uses the exact internally qualified candidate hash/code;
- the opt-in page works for an eligible non-owner test account; and
- no Critical/High defect or material pre-launch finding remains.

Do not start the 14-day period around an artifact known to require replacement.

- [ ] **Step 3: Recruit margin without committing personal data**

Create a private email list or Google Group containing 15–20 willing testers,
not merely 12. Confirm each has:

- a Google account accepted by the test mechanism;
- a compatible Play-capable Android 16+ device for the app's `minSdk = 36`;
- the official opt-in and Play listing links;
- the test period and request to remain opted in for the full 14 days;
- the critical-journey checklist; and
- the GitHub Issues support URL plus the warning against posting private data.

Never put names, addresses, account identifiers, serials, or one-to-one
communications in Git. Record only invitation count and aggregate compatible
device/API/ABI categories.

- [ ] **Step 4: Start the closed release and establish day zero**

With explicit owner approval, create the closed track, attach the exact
qualified release, add the private tester list/group, verify country
availability, and roll out to the closed track. Wait until at least 12 people
have used the official link and Console counts them as opted in.

Day zero begins only at that verified time; invitations do not count. Record
UTC/local time, candidate code/hash, opted-in count, invited count, and private
evidence reference, then commit the start without identities:

```bash
git diff --check
git add docs/qualification/release-1.5.0-play.md
git commit -m "docs: record closed Play test start"
```

- [ ] **Step 5: Keep a daily aggregate continuity log outside Git**

At roughly the same time each day through day 14, record privately:

```text
date/time | opted-in count | Play eligibility status | artifact code |
new feedback count | open Critical/High count | action
```

Maintain at least 12 continuously opted-in testers. An opt-out breaks that
person's continuity; a replacement starts at zero and does not inherit elapsed
time. Keep 15–20 opted in when possible so ordinary attrition does not drop the
qualifying cohort below 12. Treat Play Console, not a spreadsheet estimate, as
the authority on qualification.

- [ ] **Step 6: Drive meaningful use and feedback during the window**

Ask testers to exercise, over the period:

- local-first first launch and offline task/project use;
- dates, reminders, recurring work, Today widget, quick add, and focus timing;
- notes, attachments, search, tags, planning/board/schedule layouts;
- generic CSV import plus Open Tasks import/export disclosures;
- app lock/title privacy and large-font/rotation behavior;
- Backup & recovery, including Android and optional Drive paths for testers who
  knowingly choose them; and
- the public privacy/support links.

Collect reproducible steps, expected/actual behavior, impact, app version, and
non-identifying device/API class. Summarize themes and actions in the ledger;
link public issues only when they contain no personal data.

- [ ] **Step 7: Triage defects without sacrificing the clock or artifact trust**

For every report, reproduce and classify severity. A documentation/listing fix
can be corrected without rebuilding when behavior is unchanged. A code or
resource fix requires the next unused version code, Tasks 2/9/11 rerun, and a
new closed release. Tester opt-in continuity may continue only if Play says it
does; never claim elapsed time based on assumption.

Any data loss/corruption, signer/update failure, startup block, security issue,
or broken backup/restore is Critical/High and blocks the production-access
application even when the 14-day counter completes.

- [ ] **Step 8: Close the 14-day evidence gate**

After 14 full continuous days, verify in Console that:

- at least 12 qualifying testers remain opted in;
- Play shows the test requirement satisfied/production access available;
- the qualifying artifact code/hash is known;
- aggregate device/API/ABI coverage and critical journeys are recorded;
- feedback themes, defects, and resulting changes/dispositions are factual;
- no Critical/High issue remains; and
- at least 12 testers stay opted in while the production-access application is
  submitted and reviewed.

- [ ] **Step 9: Commit the anonymized closed-test summary**

Add start/end times, lowest and ending opted-in counts, candidate identity,
aggregate coverage, engagement summary, feedback themes, defects/dispositions,
Play requirement status, and go/no-go. Run:

```bash
! rg -ni "BEGIN .*PRIVATE KEY|[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}" \
  docs/qualification/release-1.5.0-play.md
git diff --check
git add docs/qualification/release-1.5.0-play.md
git commit -m "docs: record completed closed Play test"
```

Expected: no personal tester data; the production-access gate is PASS only
when Console itself recognizes the continuous requirement.

---

### Task 13: Apply for and obtain production access

**Files:**

- Modify: `docs/qualification/release-1.5.0-play.md`
- Read: `docs/google-play/store-listing.md`
- Read: closed-test evidence from Task 12

**Interfaces:**

- Consumes: Play-recognized 14-day closed test, retained qualifying testers,
  final product/declaration evidence, and owner-authored business expectations.
- Produces: a factual production-access application and Play's decision.

- [ ] **Step 1: Run the application preflight**

Confirm the dashboard enables **Apply for production**, at least 12 qualifying
testers remain opted in, app setup is complete, policy status is clear, and the
closed candidate still has no Critical/High defect. Re-open the deployed
privacy/support URLs and compare Console declarations one final time.

- [ ] **Step 2: Draft answers only from recorded evidence**

Prepare concise answers covering the actual Console prompts:

- recruitment: where the private 15–20-person compatible cohort came from and
  how they opted in;
- engagement: which critical journeys/device classes they exercised during the
  14+ days;
- production resemblance: how their local task/project/backup use represents
  expected use;
- feedback channel: private communication and privacy-safe GitHub Issues;
- feedback and changes: real aggregate themes, defects, fixes, or evidence-backed
  reasons no code change was needed;
- audience/value: people 13+ who want a private, local-first task/project app
  with optional encrypted backup;
- expected first-year install range: the owner's honest forecast selected from
  the current Console choices, not an invented growth claim; and
- readiness: exact internal/closed, fresh/update, signer, backup, Drive,
  performance, policy, and pre-launch evidence.

Do not paste generic marketing copy, claim tester activity that was not
observed, or overstate device/region coverage.

- [ ] **Step 3: Owner reviews and submits the application**

The owner compares every answer with the private daily log and public ledger,
then explicitly approves the external submission. Record submission date/time,
candidate code, retained opted-in count, and a private copy of the exact
answers. Keep testers opted in and the closed release available during review.

- [ ] **Step 4: Respond to the production-access decision**

If approved, record the approval time and any conditions. If Google asks for
more testing or rejects access, do not start an open test reflexively: preserve
the closed cohort, address the stated weakness, extend meaningful testing,
update evidence, and reapply only when the issue is genuinely resolved. Appeal
only when the recorded facts show the decision is factually wrong.

- [ ] **Step 5: Commit the non-sensitive decision record**

Run:

```bash
git diff --check
git add docs/qualification/release-1.5.0-play.md
git commit -m "docs: record Play production access decision"
```

Expected: production access is PASS before Task 14 begins; exact private form
answers and tester identities remain outside Git.

---

### Task 14: Publish globally, tag the build, and monitor the first release

**Files:**

- Modify: `docs/qualification/release-1.5.0-play.md`
- Modify, only if post-review copy must be reconciled:
  `docs/google-play/store-listing.md`
- Reference: `RELEASING.md`

**Interfaces:**

- Consumes: production access and the exact closed/internal candidate already
  qualified by hash.
- Produces: standard production availability in every selected Play region,
  tag `v1.5.0` on the recorded build commit, and a day 1–30 monitoring record.

- [ ] **Step 1: Run the final immutable go/no-go checklist**

Require all of these at one owner review:

- local AAB SHA-256, App Bundle Explorer artifact, version code, and closed
  release all identify the same candidate;
- the tag target candidate source commit is recorded and reachable;
- Play app-signing fingerprint equals the existing 1.4.0 signer and the upload
  fingerprint is distinct;
- fresh Play install and 1.4.0 direct-to-Play update passed with data intact;
- production OAuth contains every applicable delivery SHA-1 and Drive restore
  passed from the Play build;
- pre-launch, policy, vitals baseline, listing, content rating, Data Safety,
  App Content, audience, app access, pricing, ads, and all-region availability
  are green/consistent;
- both public pages return HTTP 200 without authentication;
- closed-test requirement and production access are approved;
- no Critical/High defect or unresolved reviewer concern remains; and
- the owner signs and dates the ledger's production go decision.

Any failed item stops production. A binary fix uses a higher code and repeats
affected gates; a factual copy correction is versioned and resubmitted before
rollout.

- [ ] **Step 2: Create the first production release from the existing artifact**

With explicit owner approval, promote the exact closed candidate to production
where Console supports promotion; otherwise select the already uploaded exact
artifact by version code. Use the committed release notes. Do not upload a
rebuild, request a signing-key upgrade, add a new artifact, or enable an open
test.

Confirm all Play-supported countries/regions are selected. Submit for standard
review with at least a one-week scheduling buffer. Managed publishing and a
percentage staged rollout are not available for the first publication, so do
not present them as safety controls. Upload the four large-screen images only
to the tablet/large-screen slot their captured device actually represents; do
not label untested images as Chromebook evidence.

- [ ] **Step 3: Recheck the approved release before pressing rollout**

After Play review approves the release, inspect the final change summary,
artifact code, signing identity, listing, declarations, country count, and
release notes again. Starting rollout makes the first release available to all
selected regions; obtain a fresh explicit owner go decision immediately before
pressing the production rollout action.

- [ ] **Step 4: Verify live availability and installation**

When Play reports production available:

- open the public store listing signed out and from an eligible test account;
- confirm app name, developer identity, privacy/support, copy, icon, feature
  graphic, screenshots, free price, no ads claim, and device compatibility;
- install fresh from production on a clean compatible device;
- update one retained compatible 1.4.0 direct install when Play offers it;
- authenticate the installed base APK signer and verify preserved synthetic
  data; and
- run one short core/offline and optional Drive backup/restore smoke.

Record first availability time and country/region observations without
claiming instant visibility everywhere while Play propagation is ongoing.

- [ ] **Step 5: Commit launch evidence and tag the exact build commit**

Update the ledger with review/rollout/live times, final artifact/hash/code,
public listing URL, final signer proof, install/update smokes, owner approval,
and monitoring schedule. Commit evidence:

```bash
git diff --check
git add docs/qualification/release-1.5.0-play.md \
  docs/google-play/store-listing.md
git commit -m "docs: record Play production launch"
```

Resolve the previously recorded build commit, verify it contains the final
version values, and create the release tag on that commit rather than the later
evidence commit:

```bash
play_build_commit="$(sed -n \
  's/^Build commit: `\([0-9a-f][0-9a-f]*\)`$/\1/p' \
  docs/qualification/release-1.5.0-play.md)"
test "${#play_build_commit}" -eq 40
git show "$play_build_commit:app/build.gradle.kts" | \
  rg 'versionCode|versionName'
test -z "$(git tag --list v1.5.0)"
git tag -a v1.5.0 "$play_build_commit" -m "Open Tasks 1.5.0"
git show --no-patch --decorate v1.5.0
```

Expected: tag points to the source that produced the live AAB. Push the chosen
integration branch and annotated tag only with explicit owner approval and only
after reconciling the repository's actual remote state; never force-push to
paper over divergence.

- [ ] **Step 6: Monitor daily on days 1–7**

At a consistent time each day, inspect and record:

- Play policy/app status and any enforcement message;
- Android vitals: user-perceived crash and ANR clusters;
- install, startup, update, and device-compatibility failures;
- ratings/reviews and privacy-safe GitHub support issues;
- pre-launch/device-catalog changes; and
- Drive authorization, backup, restore, or deletion reports.

No analytics/crash SDK is added. Low volume may hide aggregate metrics; a
single reproducible vault-loss/signing/security/restore report remains
actionable.

- [ ] **Step 7: Monitor weekly through day 30 and close the launch window**

Repeat the same review on approximately days 14, 21, and 30. Record counts,
trends, issue links without personal data, dispositions, and whether any release
action was needed. At day 30, summarize stability, unresolved lower-severity
work, and readiness to use staged rollout/managed publishing for later updates.

- [ ] **Step 8: Use the first-release incident path when a stop condition appears**

For reproducible vault loss/corruption, signer/update incompatibility, security
regression, inaccessible backup, widespread startup/authorization failure, or
a Critical policy issue:

1. with owner approval, unpublish to prevent new installs;
2. keep privacy and support pages online;
3. communicate only confirmed facts and a safe workaround when one exists;
4. diagnose and fix the root cause;
5. use the next unused version code;
6. rerun every affected repository, artifact, signer, install/update, backup,
   Drive, policy, and track gate; and
7. submit the corrective release for existing users before republishing.

Do not use Play's halt feature: there is no previous production release to
restore. Unpublishing does not remove the app from existing users, and Android
does not support a signed downgrade as rollback.

- [ ] **Step 9: Commit the day-30 closeout**

Run:

```bash
git diff --check
git add docs/qualification/release-1.5.0-play.md
git commit -m "docs: close Play 1.5.0 launch monitoring"
```

The submission is complete only when production is live, the signed update
path is verified, no launch-stop incident remains open, and the day-30 summary
is recorded. Future Play automation and staged update controls remain separate
work justified only after this manual path is proven.
