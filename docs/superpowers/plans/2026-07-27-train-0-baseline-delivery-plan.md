# Train 0 — Baseline and Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the completed P1/P2 working tree as a verified checkpoint
and make local and GitHub delivery gates trustworthy before secrets exist.

**Architecture:** Treat the current working tree plus `HANDOFF.md` as the
baseline. Verify it without destructive cleanup, checkpoint only audited files,
then pin Actions and repair the emulator matrix. Keep CI read-only and
credential-free.

**Tech Stack:** Git, Gradle, Android SDK 36/37, GitHub Actions, official Android
emulators, JUnit 4, and Compose UI test.

**Backlog:** P0-R08 and P3-T00 through P3-T03.

## Global Constraints

- Follow the master plan constraints.
- Do not use `git reset`, `git clean`, emulator wipes, dependency auto-merge,
  or a blanket `git add .`.
- Do not add signing, OAuth, Play, or Drive secrets.
- Keep the official Kotlin IDE formatter; do not add ktlint or Spotless.
- Do not install an optional plugin: P3-T03 is deliberately closed as
  unnecessary for this programme.

---

### Task 0.1: Audit and freeze the existing P1/P2 baseline

**Files:**
- Modify: `HANDOFF.md`
- Verify: every path reported by `git status --short`
- Verify: `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/*.json`

**Interfaces:** Consumes the existing P1/P2 handoff and working tree. Produces
an audited file manifest and verification record; it changes no runtime API.

- [ ] Capture the exact baseline without changing it:

```bash
git status --short
git diff --stat
git diff --check
git ls-files --others --exclude-standard
rg -n --hidden --glob '!\\.git/**' \
  '(AIza[0-9A-Za-z_-]{35}|BEGIN (RSA|EC|OPENSSH) PRIVATE KEY|client_secret)'
```

Expected: `git diff --check` prints nothing; the secret scan prints no actual
credential. Classify fixtures and documentation examples manually rather than
blindly deleting matches.

- [ ] Compare every changed/untracked path with the P1/P2 completion table in
`HANDOFF.md`. Add a “Train 0 baseline manifest” containing the exact file
groups and state that unrelated user files remain unstaged.

- [ ] Confirm schemas `1.json` through `5.json` exist and that the newest
schema matches `VaultDatabase.version`.

- [ ] Run the baseline JVM/lint/debug gate:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
```

Expected: exit `0`. If it fails, invoke `superpowers:systematic-debugging`;
repair only a regression inside the completed baseline and record the failure
and fix in `HANDOFF.md`.

- [ ] Run release separately:

```bash
./gradlew :app:assembleRelease --stacktrace
```

Expected: exit `0`, with minification and resource shrinking enabled.

- [ ] Commit the verified inventory before touching source staging:

```bash
git add HANDOFF.md
git diff --cached --check
git commit -m "docs: inventory completed P1 and P2 baseline"
```

### Task 0.2: Verify affected device suites and in-place survival

**Files:**
- Modify: `HANDOFF.md`
- Test:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`
- Test: `app/src/androidTest/`
- Test: `feature/*/src/androidTest/`

**Interfaces:** Verifies Room migrations, repository persistence, process
restoration, and completed feature semantics. Produces evidence only.

- [ ] Start an API 37 emulator without `-wipe-data`. Install the new debug APK
over the existing package:

```bash
./gradlew :app:installDebug --stacktrace
adb shell am force-stop app.opentasks
adb shell monkey -p app.opentasks 1
```

Expected: the app opens with the pre-existing workspace intact.

- [ ] Run all currently affected suites:

```bash
./gradlew \
  :core:data:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest \
  --stacktrace
```

Expected: exit `0`; no test clears the installed user workspace.

- [ ] Cold-stop, relaunch, and manually verify Home, Tasks, Projects, Schedule,
More, Quick Add, a project template, and a manual time entry. Record emulator
API, test counts, and observation in `HANDOFF.md`.

- [ ] Stage only the audited baseline files listed in the new manifest:

```bash
git add -u
git add app/src/androidTest app/src/main/kotlin/app/opentasks/reminders \
  app/src/main/res/drawable/ic_notification.xml \
  app/src/main/res/xml/locales_config.xml app/src/test \
  core/data/schemas core/data/src/main/kotlin/app/opentasks/core/data/TemplatePayloadCodec.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/TemplatePayloadCodecTest.kt \
  core/domain/src/main/kotlin/app/opentasks/core/domain/ProjectTemplatePlanner.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/ProjectTemplatePlannerTest.kt \
  feature/schedule/src/androidTest HANDOFF.md
git diff --cached --check
```

Expected: no path outside the manifest is staged.

- [ ] Commit the recoverable programme baseline:

```bash
git commit -m "chore: checkpoint completed P1 and P2 workspace"
```

### Task 0.3: Pin the CI supply chain

**Files:**
- Modify: `.github/workflows/android.yml`
- Verify: `.github/dependabot.yml`

**Interfaces:** Produces immutable GitHub Action references; no application
runtime contract changes.

- [ ] Add a failing repository test script check by running:

```bash
rg -n 'uses: [^#\\n]+@(v[0-9]+|main|master)$' .github/workflows
```

Expected before the edit: matches for the four unpinned actions.

- [ ] Replace action tags with the reviewed full commits and retain a version
comment:

```yaml
- uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
- uses: actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9 # v4
- uses: android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407 # v3
- uses: ReactiveCircus/android-emulator-runner@4c44018e59b437e86cdfc41da381398f93ed8808 # v2
```

- [ ] Confirm Dependabot retains `package-ecosystem: github-actions` with a
weekly cadence.

- [ ] Re-run the pin check.

Expected: no output.

- [ ] Commit:

```bash
git add .github/workflows/android.yml .github/dependabot.yml
git commit -m "ci: pin reviewed GitHub Actions revisions"
```

### Task 0.4: Repair API 36/37 compact and expanded CI

**Files:**
- Modify: `.github/workflows/android.yml`
- Create: `scripts/verify-actions-workflow.sh`
- Modify: `HANDOFF.md`

**Interfaces:** Produces a deterministic CI matrix and a local structural
validator. Does not change app behaviour.

- [ ] Create `scripts/verify-actions-workflow.sh` with these enforced checks:

```bash
#!/usr/bin/env bash
set -euo pipefail
workflow=".github/workflows/android.yml"
grep -q 'api-level: 36' "$workflow"
grep -q 'api-level: 37' "$workflow"
grep -q 'profile: pixel_6' "$workflow"
grep -q 'profile: pixel_tablet' "$workflow"
grep -q 'channel: canary' "$workflow"
! grep -Eq 'uses: [^#[:space:]]+@(v[0-9]+|main|master)([[:space:]]|$)' "$workflow"
```

- [ ] Run it before the matrix edit:

```bash
bash scripts/verify-actions-workflow.sh
```

Expected: non-zero because no expanded profile or API 37 channel exists.

- [ ] Change the instrumented matrix to explicit includes:

```yaml
matrix:
  include:
    - api-level: 36
      channel: stable
      profile: pixel_6
      form-factor: compact
    - api-level: 37
      channel: canary
      profile: pixel_tablet
      form-factor: expanded
```

Pass `${{ matrix.channel }}` and `${{ matrix.profile }}` to the emulator
runner. Run Data, App, Tasks, Projects, Schedule, and More device suites. Add a
separate `release` job with
`needs: verify` and `./gradlew :app:assembleRelease --stacktrace`.

- [ ] Keep `permissions: contents: read`, `fail-fast: false`, and explicit
timeouts. Upload only JUnit/lint reports with:

```yaml
- uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4
```

Never upload emulator or workspace data.

- [ ] Validate:

```bash
bash scripts/verify-actions-workflow.sh
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/android.yml", aliases: true)'
```

Expected: both exit `0`.

- [ ] Commit:

```bash
git add .github/workflows/android.yml scripts/verify-actions-workflow.sh HANDOFF.md
git commit -m "ci: repair Android device and release gates"
```

### Task 0.5: Verify the colour hook and close tooling decisions

**Files:**
- Verify: `.claude/settings.json`
- Modify: `HANDOFF.md`
- Modify: `README.md`

**Interfaces:** Verifies the existing developer guard. Produces explicit
decisions for P3-T01 through P3-T03.

- [ ] Through the supported Claude/Codex hook harness, present a temporary
sample containing `Color(0xFF112233)` outside existing grandfathered code.

Expected: the raw-colour hook rejects the sample.

- [ ] Present an OKLCH token sample through the same harness.

Expected: the hook permits it.

- [ ] Remove the temporary samples and verify:

```bash
git status --short
```

Expected: no temporary hook-test file remains.

- [ ] Record in `HANDOFF.md`: P3-T01 passed; P3-T02 retains the Kotlin IDE
formatter; P3-T03 is closed without plugin installation. Add the formatter
decision to `README.md`.

- [ ] Run the train exit gates from the master plan and commit:

```bash
git add HANDOFF.md README.md
git commit -m "docs: close baseline developer tooling decisions"
```
