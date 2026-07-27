# Train 6 — Production Qualification and Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Qualify the complete product, satisfy Play/OAuth/privacy/signing
gates, publish the first production release globally, and prepare unrestricted
5/20/50/100 staged rollouts for subsequent updates.

**Architecture:** Add test-only fixtures, official screenshot testing,
Baseline Profile, and Macrobenchmark modules without changing production data
flows. Generate one signed, minified release candidate and promote that exact
artifact through internal, closed, open, and production tracks. Observe with
Play-provided vitals and direct support, not an app telemetry SDK.

**Tech Stack:** AndroidX Compose Preview Screenshot Testing 0.0.1-alpha15,
AndroidX Benchmark Macrobenchmark 1.4.1, Baseline Profile plugin, Profile
Installer 1.4.1, Gradle Managed/physical devices, bundletool, R8, Google Play
Console, Google Cloud OAuth, JUnit 4, Compose Accessibility Test Framework.

**Backlog:** P1-D08, P0-R02, P0-R04 through P0-R07, and P0-R10.

## Global Constraints

- Follow the master plan constraints.
- Qualification fixtures are synthetic and unavailable in release builds.
- Do not bulk-accept screenshot changes.
- Do not put keystores, credentials, service-account JSON, or passwords in Git,
Gradle files, logs, Actions artifacts, or command history.
- Promote the same AAB digest between testing tracks; rebuild only for a new
  `versionCode`.
- First production publication is global because new apps cannot use
  percentage staged rollout. Percentage staging applies to every later update.
- No country/region limitation: all Play-supported regions selected by the
  owner are included.

---

### Task 6.1: Complete the final cloud and data-loss matrix

**Files:**
- Create:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/ProductionCloudMatrixInstrumentedTest.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/ProductionCloudMatrixTest.kt`
- Create: `core/data/src/test/resources/cloud-production-matrix/*`
- Modify: `HANDOFF.md`

**Interfaces:** Test-only. Uses the production repository, codecs,
administration, attachment transfer, and fake store interfaces.

- [ ] Encode table-driven scenarios for two/three devices; offline outboxes;
10,000 operations; duplicate/reordered/paginated changes; auth expiry; quota;
rate limit; provider outage; snapshot/segment/manifest/chunk corruption;
resume/missing chunks; reinstall/new device/Keystore loss; rollback;
disconnect/delete; concurrent notes; attachment deletion; and overlapping
timers.

- [ ] For every scenario assert:

```text
canonical record equality
+ expected explicit conflicts
+ identical live/tombstone ID inventory
+ identical attachment metadata/content hashes
+ no unacknowledged outbox operation
+ no quarantined object silently skipped
```

- [ ] Run each deterministic scenario with at least two delivery orders and
one process restart:

```bash
./gradlew :core:data:testDebugUnitTest \
  :core:data:connectedDebugAndroidTest --stacktrace
```

Expected: exit `0`.

- [ ] Run the credentialed Drive smoke matrix with test-only vault prefixes.
Record object counts and pass/fail only; no IDs or payloads.

- [ ] Commit:

```bash
git add core/data/src/test core/data/src/androidTest HANDOFF.md
git commit -m "test: complete production cloud matrix"
```

### Task 6.2: Run current-surface and final accessibility acceptance

**Files:**
- Create: `docs/release/accessibility-audit.md`
- Modify: every `feature/*/src/androidTest/` suite as findings require
- Modify: `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/Components.kt`
- Modify: `HANDOFF.md`

**Interfaces:** No new API unless a verified finding requires a reusable
design-system semantic.

- [ ] Record P0-R02 against the current baseline before fixes: TalkBack,
Switch Access, high contrast, reduced motion, RTL, keyboard, compact, and
expanded. Each finding has route, reproduction, expected/actual, severity, and
owner.

- [ ] Enable the Compose Accessibility Test Framework in every feature device
suite and add an assertion for each critical screen. Run at 100%, 130%, and
200% font scale, light/dark, and selected RTL pseudo-locale.

- [ ] Manually test TalkBack reading order/names/roles/state/custom actions/live
regions; Switch Access; keyboard/mouse; high contrast; animation scale 0; all
dialogs/sheets/snackbars/charts/tables; notifications; widget configuration;
and recovery.

- [ ] Fix each blocking finding test-first in its owning module. Re-run the
complete audit as P0-R04. Production blocks on every critical-journey issue and
every high-impact secondary issue.

- [ ] Record lower issues with severity, owner, and post-release target; do not
use this exception for security, data loss, recovery, or critical journeys.

- [ ] Run all device suites and commit:

```bash
git add docs/release/accessibility-audit.md core/designsystem feature HANDOFF.md
git commit -m "test: complete full app accessibility audit"
```

### Task 6.3: Add reviewed screenshot and responsive regression

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `screenshot-tests/build.gradle.kts`
- Create: `screenshot-tests/src/screenshotTest/kotlin/app/opentasks/screenshots/*.kt`
- Create: `screenshot-tests/src/screenshotTest/reference/*`
- Modify: `.github/workflows/android.yml`
- Create: `docs/release/responsive-matrix.md`

**Interfaces:** Test-only screenshot host depends on feature modules and
synthetic immutable state; it does not open the production repository.

- [ ] Add the official screenshot plugin version 0.0.1-alpha15 and a dedicated
test module. Configure representative compact portrait/landscape, medium split,
expanded tablet, fold cover/unfolded, light/dark, 100/130/200% text, and RTL
configurations.

- [ ] Add named screenshot tests for Home, Tasks list/editor/activity-files,
Projects/workbench, Schedule, More/Insights/Recovery/Export/Settings, Search,
Quick Add, and App Lock. Include empty, populated, error, conflict, and
progress states where visually distinct.

- [ ] Generate references:

```bash
./gradlew :screenshot-tests:updateDebugScreenshotTest --stacktrace
```

Expected initially: reviewed reference PNGs are created. Inspect every image
at full resolution; discard/fix any clipped, overlapping, misleading, or
private fixture content.

- [ ] Verify:

```bash
./gradlew :screenshot-tests:validateDebugScreenshotTest --stacktrace
```

Expected: exit `0`.

- [ ] Add validation to CI and upload only diff/report artifacts. Manually
complete rotation, split-screen resizing, live resizing, and separating-fold
matrix on API 36/37; record screenshots/device IDs locally, not user data.

- [ ] Commit:

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml \
  screenshot-tests .github/workflows/android.yml docs/release/responsive-matrix.md
git commit -m "test: add responsive screenshot regression"
```

### Task 6.4: Add deterministic large-data fixtures

**Files:**
- Create:
  `core/data/src/debug/kotlin/app/opentasks/core/data/ProductionScaleFixture.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/ProductionScaleFixtureTest.kt`
- Create: `app/src/debug/kotlin/app/opentasks/BenchmarkFixtureReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/proguard-rules.pro`

**Interfaces:**

```kotlin
data class ProductionScale(
    val tasks: Int = 10_000,
    val projects: Int = 250,
    val milestones: Int = 1_000,
    val tags: Int = 1_000,
    val timeEntries: Int = 100_000,
    val activityEntries: Int = 5_000,
    val attachments: Int = 1_000,
    val syncOperations: Int = 10_000,
)
```

- [ ] Add deterministic-count, referential-integrity, bounded-text,
controlled-overlap, reproducible-seed, and synthetic-content tests.

- [ ] Generate through repository transactions or a test-only bulk loader that
enforces the same domain invariants. Synthetic encrypted attachment content is
bounded and contains no real data.

- [ ] Register `BenchmarkFixtureReceiver` only in the debug/benchmark manifest
and protect it with an app-signature permission. Assert it is absent from the
release merged manifest.

- [ ] Time fixture generation separately from product benchmarks and record
the seed.

- [ ] Commit:

```bash
git add core/data/src/debug core/data/src/test app/src/debug \
  app/src/main/AndroidManifest.xml app/proguard-rules.pro
git commit -m "test: add deterministic production scale fixture"
```

### Task 6.5: Add Baseline Profiles and Macrobenchmarks

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `baselineprofile/build.gradle.kts`
- Create:
  `baselineprofile/src/main/kotlin/app/opentasks/baselineprofile/BaselineProfileGenerator.kt`
- Create: `macrobenchmark/build.gradle.kts`
- Create:
  `macrobenchmark/src/main/kotlin/app/opentasks/macrobenchmark/CriticalJourneysBenchmark.kt`
- Create: `docs/release/performance-budget.md`

**Interfaces:** Test-only modules drive the production app by stable semantics;
no private content appears in selectors or output.

- [ ] Add AndroidX Benchmark Macrobenchmark 1.4.1, Baseline Profile plugin, and
Profile Installer 1.4.1. Configure the benchmark build type as non-debuggable,
minified, profileable, and signed with a local benchmark key outside Git.

- [ ] Generate profiles for cold Home, Tasks scroll, Search/open result,
Project Workbench, Insights range switch, and Activity & files.

- [ ] Add ten-iteration cold startup and frame-timing benchmarks on a physical
reference device with the production-scale fixture; compare `None` and
`Partial(BaselineProfileMode.Require)`.

- [ ] Run:

```bash
./gradlew :baselineprofile:connectedNonMinifiedReleaseAndroidTest --stacktrace
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest --stacktrace
```

Expected: exit `0`, with profile rules copied into `app/src/main/baseline-prof.txt`.

- [ ] Enforce budgets in the result evaluator:
median initial display ≤1,000 ms; median full display ≤1,500 ms; P95 frame
duration <32 ms; search ≤300 ms; local merge of 10,000 valid operations ≤10 s;
and no critical journey >15% slower than approved baseline.

- [ ] If an absolute budget fails, invoke systematic debugging, profile and
optimise the measured bottleneck, then record the device-specific residual.
No ANR or >15% regression may be waived.

- [ ] Commit:

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml \
  app/build.gradle.kts app/src/main/baseline-prof.txt \
  baselineprofile macrobenchmark docs/release/performance-budget.md
git commit -m "perf: add baseline profiles and release budgets"
```

### Task 6.6: Harden and inspect the production AAB

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `scripts/verify-release-bundle.sh`
- Create: `docs/release/release-checklist.md`
- Modify: `.github/workflows/android.yml`

**Interfaces:** Release configuration reads upload signing values only from
environment/CI secret providers. Initial production coordinates are
`versionCode = 10000`, `versionName = "1.0.0"`.

- [ ] Add a release-signing guard that requires:
`OPEN_TASKS_UPLOAD_STORE_FILE`, `OPEN_TASKS_UPLOAD_STORE_PASSWORD`,
`OPEN_TASKS_UPLOAD_KEY_ALIAS`, and `OPEN_TASKS_UPLOAD_KEY_PASSWORD`. Never
print values. Local unsigned release assembly remains available as
`assembleRelease`; `bundleProductionRelease` requires signing.

- [ ] Implement `scripts/verify-release-bundle.sh` to fail on:
debuggable/profileable production app; test fixture receiver; unexpected
exported components; forbidden permissions; analytics/crash/ads/billing SDKs;
missing baseline profile; missing privacy link; backup enabled; or non-1.0.0
coordinates.

- [ ] Run the complete gate:

```bash
git diff --check
./gradlew testDebugUnitTest lintDebug \
  :screenshot-tests:validateDebugScreenshotTest :app:assembleDebug --stacktrace
./gradlew :app:assembleRelease --stacktrace
./gradlew :app:bundleProductionRelease --stacktrace
bash scripts/verify-release-bundle.sh \
  app/build/outputs/bundle/productionRelease/app-production-release.aab
```

Expected: all exit `0`.

- [ ] Inspect bundletool manifest/resources/dex, R8 mapping, licences, native
symbols if present, Baseline Profile, FileProvider paths, widget receiver,
deep links, notification visibility, OAuth redirect/package identity, and
`versionCode`. Calculate and record SHA-256 in the release ledger.

- [ ] Run dependency vulnerability and secret scans. Reconcile every finding;
do not suppress without owner, rationale, and expiry.

- [ ] Commit:

```bash
git add app .github/workflows/android.yml scripts/verify-release-bundle.sh \
  docs/release/release-checklist.md
git commit -m "build: harden production app bundle"
```

### Task 6.7: Complete privacy, OAuth, signing, and store declarations

**Files:**
- Create: `docs/privacy-policy.md`
- Create: `docs/release/data-safety.md`
- Create: `docs/release/oauth-and-signing.md`
- Create: `docs/release/play-listing-en-GB.md`
- Create: `docs/release/release-ledger.md`
- Modify: `app/src/main/res/values/strings.xml`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/PrivacySettingsScreen.kt`
- Modify: `README.md`
- Modify: `HANDOFF.md`

**Interfaces:** The app links to one stable HTTPS privacy-policy URL and shows
the same version/date in its in-app privacy screen.

- [ ] Write the policy from actual flows: local SQLCipher data; encrypted
Drive app data; Google processing; no developer-readable vault content; no
analytics/ads/IAP/crash SDK; optional plaintext exports; calendar provider;
support/contact; deletion/disconnect; retention; child/target audience; and
policy change date.

- [ ] Publish it at a stable public HTTPS URL, insert that exact URL into app
resources and Play Console, then verify it without authentication on mobile.

- [ ] In Play Console, complete verified developer identity, account type,
two-step verification, least-privilege roles, global availability, `en-GB`
default listing, support email, app access, content rating, target audience,
ads=no, government declaration, export-law answers, and Data Safety exactly as
`docs/release/data-safety.md`.

- [ ] Enrol in Play App Signing with a Google-generated app-signing key. Create
and protect a separate upload key. Record certificate SHA-256 fingerprints,
custodian, backup, and rotation/recovery procedure; never record passwords or
private key bytes.

- [ ] Configure OAuth production branding and Android clients for:
local debug certificate + package; and Play app-signing certificate + package.
Do not register the upload certificate. Request only `drive.appdata`.

- [ ] Produce reviewed phone, 7-inch/10-inch tablet, foldable, feature graphic,
icon, short description, full description, and privacy-safe screenshots from
the release candidate. Store source assets outside user vault data and record
Play asset checks in the release ledger.

- [ ] Commit documentation/app link changes:

```bash
git add docs app/src/main/res/values/strings.xml feature/more README.md HANDOFF.md
git commit -m "docs: complete production privacy and store contracts"
```

### Task 6.8: Promote the exact release candidate through test tracks

**Files:**
- Modify: `docs/release/release-ledger.md`
- Create: `docs/release/test-track-results.md`
- Modify: `HANDOFF.md`

**Interfaces:** External Play operations only; application source remains
unchanged unless a test finds a defect.

- [ ] Upload the signed AAB with recorded SHA-256 to Internal testing. Enable
Play App Signing delivery, install from Play on API 36/37 phone/fold/tablet,
test clean install and upgrade, review the pre-launch report, and execute every
critical journey.

- [ ] Promote the same artifact to a closed track. Recruit representative
phone/fold/tablet testers. If the Play account is subject to the newer personal
account rule, retain at least 12 opted-in testers continuously for at least
14 days. Record dates, opt-in count, device coverage, feedback, and fixes.

- [ ] If required, submit production-access answers with the closed-test
evidence and do not advance until approved.

- [ ] Promote the same artifact to a globally available open test for at least
14 days. Review Play vitals, pre-launch warnings, reviews/support, and reported
sync/recovery issues daily. Exit requires no unresolved critical/high issue.

- [ ] A defect fix creates a higher `versionCode`, reruns Tasks 6.1–6.7,
updates the AAB hash, and restarts the affected test hold. Never silently swap
the ledger artifact.

- [ ] Record exit evidence in the test-track results and commit:

```bash
git add docs/release/test-track-results.md docs/release/release-ledger.md HANDOFF.md
git commit -m "docs: record production test track evidence"
```

### Task 6.9: Publish the first release globally

**Files:**
- Modify: `docs/release/release-ledger.md`
- Modify: `HANDOFF.md`

**Interfaces:** External Play operation. No code change.

- [ ] Reconfirm immediately before submission: target API acceptance, policy
status, OAuth production status, global country/region selection, managed
publishing, store listing, AAB SHA-256, version 1.0.0/10000, test-track exit,
and zero blocking Play warning.

- [ ] Promote the exact approved AAB to Production with 100% global
availability under managed publishing. Do not configure a percentage staged
rollout: Play does not support staging a new app's first production
publication.

- [ ] Submit for review, wait for approval, then use managed publishing to make
the approved release live. Verify the public listing and clean Play install in
at least two supported regions and on phone/tablet form factors.

- [ ] For the first 72 hours, review Play crash/ANR vitals, policy status,
reviews, support, and reported sync/recovery failures at least twice daily.
Any critical/high issue follows the halt/fix procedure in Task 6.10.

- [ ] Record approval/publication timestamps, countries=global, artifact hash,
Play release ID, and observations without user identifiers. Commit:

```bash
git add docs/release/release-ledger.md HANDOFF.md
git commit -m "docs: record global production publication"
```

### Task 6.10: Rehearse and use staged update rollout

**Files:**
- Create: `docs/release/staged-rollout-runbook.md`
- Create: `docs/release/incident-runbook.md`
- Modify: `docs/release/release-ledger.md`
- Modify: `HANDOFF.md`

**Interfaces:** External Play operations for updates after 1.0.0.

- [ ] Prepare a harmless testing-track update with `versionCode = 10001` and
rehearse start, increase, halt, resume, and replace operations without exposing
it to production users. Record Play role permissions and screenshots.

- [ ] For every real post-launch update, pass Tasks 6.1–6.7, promote the exact
AAB through internal validation, then start production at 5%.

- [ ] Use this unrestricted global progression:

```text
5%  — hold at least 48 hours
20% — hold at least 48 hours
50% — hold at least 48 hours
100%
```

At every gate compare Play crash/ANR vitals, pre-launch report, reviews/support,
sync/recovery reports, policy warnings, and known-issue count with the prior
stage.

- [ ] Halt immediately for any new critical/high data-loss, security,
recovery, crash, ANR, or blocking accessibility issue. Preserve offline app
access. Triage with `superpowers:systematic-debugging`; ship a higher
`versionCode` through the full release gate and a new staged rollout.

- [ ] Define owners and alternates for Play operations, incident lead,
security/privacy, Drive/recovery, accessibility, communications, and upload-key
custody. Include severity definitions, response targets, evidence template,
and user-communication criteria.

- [ ] Run the final master-plan completion checklist and invoke
`superpowers:verification-before-completion`. Update the handoff with exact
final evidence.

- [ ] Commit:

```bash
git add docs/release/staged-rollout-runbook.md \
  docs/release/incident-runbook.md docs/release/release-ledger.md HANDOFF.md
git commit -m "docs: establish staged update rollout operations"
```
