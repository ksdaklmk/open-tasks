# Open Tasks Production Programme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete every remaining P0–P3 backlog item and publish the
privacy-first Open Tasks Android application globally through a production
release, with staged percentage rollouts for subsequent updates.

**Architecture:** Preserve the existing local-first repository boundary and
five-destination Navigation 3 shell. Add a second, independently wrapped
vault-content key for encrypted Drive objects and attachments; derive Insights
from immutable workspace snapshots; introduce cloud, recovery, attachment,
archive, widget, and qualification services behind focused interfaces. Execute
the programme as seven dependency-ordered trains, with a releasable and
reviewable checkpoint after each train.

**Tech Stack:** Kotlin 2.3.21, Android Gradle Plugin 9.3.1, Java 17 on JDK 21,
Compose BOM 2026.06.00, Navigation 3, Material 3 Adaptive, Hilt, coroutines,
Room 2.8.4, SQLCipher 4.15.0, WorkManager 2.11.2, Tink 1.23.0, Bouncy Castle
1.84, Google Drive REST v3, Glance, AndroidX Biometric, Baseline Profiles,
Macrobenchmark, JUnit 4, Compose UI test v2, and GitHub Actions.

## Global Constraints

- Work directly on `main`; do not create a branch, worktree, or pull request.
- Preserve the existing user-owned P1/P2 working tree. Never reset, clean, or
  stage beyond the file list named by the active task.
- Keep `minSdk 36`, `compileSdk 37`, `targetSdk 37`, Java 17, JDK 21, and AGP
  built-in Kotlin. Do not apply `org.jetbrains.kotlin.android`.
- Keep top-level navigation to Home, Tasks, Projects, Schedule, and More.
- Feature composables stay stateless and depend only on `core:model` and
  `core:designsystem`; Hilt and platform launchers stay in `app`.
- Route every durable product mutation through a typed `DomainCommand` and
  `VaultRepository.execute`. Commit record changes and their outbox operation
  atomically, and return repository-produced Undo for reversible changes.
- Keep `InMemoryVaultRepository` behaviour aligned with encrypted Room by
  running the shared contract suite against both.
- Apply downloaded remote changes through an internal merge transaction that
  cannot recursively append the same operation to the upload outbox.
- Keep the SQLCipher key and cloud vault-content key independent. Use
  `CharArray` for passphrases, zero temporary key arrays, and never save keys,
  passphrases, attachment bytes, or vault payloads in instance state.
- Do not log task content, filenames, account data, Drive IDs, ciphertext,
  recovery metadata, or keys. Add no analytics, advertising, purchase, or
  crash-reporting SDK.
- Every Room version bump includes a non-destructive migration, an exported
  schema, prior-version fixtures, and restart verification.
- Every cloud/crypto format change includes bounded decoder tests, golden
  vectors, tamper tests, wrong-key tests, old-reader tests, and a documented
  minimum-reader rule.
- Use UK English in resources, day–month dates, 24-hour time, logical
  start/end layout, 48 dp actions, keyboard access, and non-colour status cues.
- Update `README.md`, `PRODUCT.md`, `DESIGN.md`, `HANDOFF.md`,
  `docs/architecture.md`, and `docs/threat-model.md` whenever a train changes
  their contracts.
- Run release assembly separately from lint because the repository documents
  an AGP/KSP release-lint race.

---

## Programme map

| Order | Train | Plan | Exit decision |
|---|---|---|---|
| 0 | Baseline and delivery | [Train 0 plan](2026-07-27-train-0-baseline-delivery-plan.md) | Existing P1/P2 work is checkpointed; CI and hooks are trustworthy |
| 1 | Insights and cloud format | [Train 1 plan](2026-07-27-train-1-insights-cloud-format-plan.md) | Insights ships locally; the encrypted object contract is frozen |
| 2 | Drive identity and sync | [Train 2 plan](2026-07-27-train-2-drive-sync-plan.md) | Authenticated, offline-safe multi-device sync is operational |
| 3 | Migration and recovery | [Train 3 plan](2026-07-27-train-3-migration-recovery-plan.md) | Authority migration, rollback, disconnect, and recovery are proven |
| 4 | Notes, attachments, search | [Train 4 plan](2026-07-27-train-4-notes-attachments-search-plan.md) | Final local schema and resumable attachment contract are complete |
| 5 | Platform features | [Train 5 plan](2026-07-27-train-5-platform-features-plan.md) | Import/export, widget, lock, input, and calendar features are complete |
| 6 | Qualification and rollout | [Train 6 plan](2026-07-27-train-6-production-qualification-rollout-plan.md) | Global first production release is published and update staging is ready |

The dependency chain is strictly:

```text
Train 0 → Train 1 → Train 2 → Train 3 → Train 4 → Train 5 → Train 6
```

Do not begin a later train until the preceding train's automated gates pass and
its manual/external blockers are either satisfied or explicitly recorded as
owner actions. Insights UI tasks inside Train 1 may run before its cloud-format
tasks, but Train 2 may not start until the format and golden vectors are
frozen.

## Shared public contracts

The train plans use these ownership rules to avoid incompatible parallel
implementations:

- `core:model` owns immutable records, identifiers, snapshot types, sync
  status, Insights result types, attachment states, and archive summaries.
- `core:domain` owns `DomainCommand`, `VaultRepository`, pure rules,
  `InsightsEngine`, cloud-store abstractions, administration interfaces, and
  repository contract tests.
- `core:sync` owns canonical cloud headers, manifest/snapshot/segment payload
  codecs, merge ordering, bounds, retry policy, and failure classification.
- `core:crypto` owns vault-content key creation, Android Keystore local
  wrapping, passphrase recovery envelopes, AEAD, and authenticated stream
  chunking.
- `core:data` owns Room, SQLCipher, app-private encrypted files, outbox and
  remote-merge transactions, Drive REST transport, WorkManager workers,
  archive staging, and cache eviction.
- Feature modules own stateless UI and UI-only state models. `app` owns Hilt,
  ViewModels/coordinators, activity-result APIs, permissions, authentication
  prompts, intents, navigation binding, and process-lifecycle locking.

The authority modes are:

```kotlin
enum class StorageMode {
    LOCAL,
    DRIVE_PRIMARY,
}
```

`LOCAL` means SQLCipher and encrypted app-private files are durable authority.
`DRIVE_PRIMARY` means those stores remain the immediate offline cache and
atomic outbox while encrypted Drive `appDataFolder` objects are durable
authority. There is exactly one active vault.

## Shared verification gates

After every implementation task, run its focused tests. At every train exit,
run:

```bash
git diff --check
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
./gradlew :app:assembleRelease --stacktrace
```

Expected: all commands exit `0`; `git diff --check` prints nothing. Run the
affected device suites named by the train without clearing emulator data. A
Room/authority train must include an in-place upgrade and cold restart.

Before claiming a train complete:

- [ ] Invoke `superpowers:verification-before-completion`.
- [ ] Review `git status --short` and stage only train-owned files.
- [ ] Scan changed production source for `TODO`, `FIXME`, `println`,
  `Log.`, raw secrets, and placeholder implementations.
- [ ] Check interface/type names against this master plan and all later train
  plans; update plans first if an approved contract changes.
- [ ] Record exact commands, device/API, test counts, and manual evidence in
  `HANDOFF.md`.
- [ ] Commit the train checkpoint with the exact message named in its plan.

## Release strategy

The first Play production publication cannot use a percentage staged rollout.
Contain first-release risk through internal testing, the required closed test,
production-access approval where applicable, and a global open test. Then
publish the approved binary globally using managed publication.

For every later production update:

```text
5% for ≥48 h → 20% for ≥48 h → 50% for ≥48 h → 100%
```

At every stage review Play crash/ANR vitals, pre-launch warnings, reviews,
support reports, and reported sync/recovery failures. Halt for any new
critical/high data-loss, security, recovery, crash, ANR, or blocking
accessibility issue. A fix uses a higher `versionCode`, the same signing
identity, the complete release gate, and a new staged rollout.

## Programme completion checklist

- [ ] P0-R02 through P0-R10 are implemented or their external acceptance is
  recorded.
- [ ] P1-D01 through P1-D08, P1-L05, and P1-L08 are complete.
- [ ] P2-F02 and P2-F04 through P2-F07 are complete.
- [ ] P3-T00 through P3-T03 are closed according to the approved decisions.
- [ ] Unit, lint, debug, release, device, migration, crypto, cloud,
  accessibility, screenshot, and performance gates pass.
- [ ] No critical/high security, data-loss, recovery, accessibility, or
  compatibility issue remains.
- [ ] Privacy policy, Data Safety, OAuth, signing, store listing, and developer
  verification match the shipped binary.
- [ ] Internal, required closed, and open testing are complete.
- [ ] The first production release is globally published.
- [ ] The staged-update halt and recovery procedure is rehearsed.
