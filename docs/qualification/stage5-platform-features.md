# Stage 5 platform features qualification

## Outcome

Stage 5 is qualified as of 6 August 2026. This record covers the Room v9
retired blob-set index and its `RETIRED_BLOB_SET` backup family, bounded
garbage-collection closure over retired sets, silent attachment-intake
auto-resume, the frozen `.otvault` v1 archive format with independent Node
fixtures, encrypted vault export and import with staged activation and
rollback, disclosed formula-safe CSV export, the Glance Today widget, app
lock with title privacy and a unified Quick Add, keyboard/mouse/accessible
shortcuts with a help dialog, one-way calendar insertion, and the final exit
gates. Encrypted Room remains the sole live structured-data authority; no
remote merge or cloud-to-Room record path was added.

No account identifier, device serial, Drive object ID, or other private
identifier is recorded here. All gate runs below completed at head
`d53a9f9`; the base of the Stage 5 range is `1cd8cf4`.

## Step 1 — six-module connected gate

### First run: 7 failures, all test-only

The first attempt (10m13s, sole disposable `Pixel_10_Pro_Fold`, API 37 /
Android 17) failed 7 tests across two modules: `:core:data` (2 failures)
and `:app` (5 failures). All seven were root-caused as test-only defects
and fixed in commits `334fcae..d53a9f9`, with no product code touched:

- `VaultDatabaseMigrationInstrumentedTest.migrate8To9PreservesRowsAndAddsEmptyRetiredBlobSets`
  — the v8 seed fixture's `tasks` INSERT supplied 28 values for 29
  columns, missing one `NULL` in the `recurrenceEndDate…deletedAtEpochMillis`
  run; the missing value was restored.
- `OtVaultImportInstrumentedTest.anImportedArchiveBecomesTheLiveVaultAndReleasesTheRollbackSlot`
  — the assertion compared a raw `String` against the `VaultId` value
  class, which can never be `equal()`; the raw side was wrapped in
  `VaultId(...)`, matching every sibling instrumented test's idiom.
- `FoldContinuityInstrumentedTest` (all three methods) — the deliberate
  fail-closed legacy-storage guard tripped on residue from device history
  outside the current suites, not from a real ordering defect (the actual
  execution order and the only two `MainActivity`-launching `:app` classes
  were independently verified). `vaultFixtureRule.after()` was restructured
  so identity/verification checks run in a `try` block and every
  destructive cleanup step runs unconditionally in the paired `finally`,
  closing a latent robustness gap without touching the guard's strictness.
- `MainActivityRecoveryRestorationInstrumentedTest.productionRecoveryRouteClearsPassphraseAfterActivityRecreation`
  — no `:app` test established a clean `AppLockSettings` baseline before
  depending on cold-start unlocked behaviour, so the `AppLockController`
  singleton inherited a stale `lock_enabled` value from prior device state;
  the recovery fixture rule now sets `lockEnabled = false` before writing
  its fixture.
- `input.ShortcutRootWiringInstrumentedTest.ctrlKOpensSearchAndFocusesTheQueryField`
  — the search field's `LaunchedEffect`-driven focus request lands one step
  behind the key-event recomposition; `composeRule.waitForIdle()` was added
  between the key input and the focus assertion.

Scoped re-review confirmed all seven addressed, no product code touched,
and no new breakage.

### Official rerun: PASS, 9m05s

The official rerun at `d53a9f9` passed. XML-authoritative per-module
counts (tests / failures / errors / skipped):

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `:app` | 27 | 0 | 0 | 2 |
| `:core:data` | 158 | 0 | 0 | 0 |
| `:feature:tasks` | 36 | 0 | 0 | 0 |
| `:feature:projects` | 16 | 0 | 0 | 0 |
| `:feature:schedule` | 2 | 0 | 0 | 0 |
| `:feature:more` | 54 | 0 | 0 | 0 |
| **Total** | **293** | **0** | **0** | **2** |

Device: sole disposable ADB target, `Pixel_10_Pro_Fold` (verified via
`adb emu avd name`), API 37 / Android 17, launched `-read-only
-no-snapshot-load -no-snapshot-save -no-window -no-boot-anim`; inherited
font scale 2.0 was set to 1.0 in the discarded overlay only. The
disposable was shut down after the run; final ADB target list and
emulator process audits were empty.

**Skip-count wording reconciliation.** The plan's Step 1 sentence "the
known `Pixel_10_Pro_Fold` cross-display harness skip remains the only
expected skip" undercounts. The two skips are exactly the Stage 4 pair:
`DriveCreateOnlyQualificationPackagingInstrumentedTest#explicitCredentialedArgumentLaunchesInternalQualificationAndRequiresBoundedPass`
(the credential-only row; the preserved one-shot harness is never rerun)
and `FoldContinuityInstrumentedTest#draftAndSelectionSurviveFoldTransition`
(the exact `Pixel_10_Pro_Fold` cross-display harness exception). The Stage
4 authoritative record of exactly these two skips governs; this Stage 5
result matches it exactly.

### Structural FoldContinuity residue caveat

The `FoldContinuity` suite is guaranteed residue-independent only against
residue this repository's own suites can create. Residue from device
history outside those suites — on the never-wiped, protected
`Pixel_10_Pro_Fold` AVD — can still trip the deliberate fail-closed guard.
No code-only fix exists without either weakening the guard or wiping the
protected AVD, and both are forbidden by this repository's constraints.
This caveat is recorded for the qualification record and does not weaken
the guard itself.

## Step 2 — repository, release, schema, fixture, hygiene gates (PASS)

- Forced-fresh `testDebugUnitTest lintDebug :app:assembleDebug`: 547/547
  executed tasks, BUILD SUCCESSFUL in 1m34s. JVM unit totals (tests /
  failures): `:app` 350/0, `:core:data` 572/0, `:core:domain` 44/0,
  `:core:sync` 52/0, `:core:crypto` 27/0 — **1,045 total, zero failures**.
  No other module carries JVM unit suites.
- Forced-fresh `:app:assembleRelease` (separate invocation): 441/441
  executed tasks, BUILD SUCCESSFUL in 1m33s.
- `scripts/check-schema-drift.sh`: clean — the checked-in v9 schema
  matches the live entities.
- All three fixture generators regenerate byte-identically
  (`generate-stage2-backup-v1-fixtures.mjs`,
  `generate-stage4-attachment-v1-fixtures.mjs`,
  `generate-stage5-otvault-v1-fixtures.mjs`; `git diff --exit-code
  core/data/src/test/resources` clean after each).
- `git diff --check` clean.

**Release inspection** (unsigned release APK, 15,850,599 bytes):

- Manifest carries no debug qualification activity; `MainActivity` is the
  only exported application activity.
- `app.opentasks.widget.TodayWidgetReceiver` is present with
  `android:exported="false"` — no new exported component exists at all
  (stricter than the plan's "the widget receiver is the only new exported
  component" phrasing).
- No calendar permission (Task 12's pinned no-permission contract);
  `USE_BIOMETRIC` is the expected Task 10 addition; the only
  `exported=true` library component is the pre-existing GMS
  `RevocationBoundService` from Stage 3's approved release scope.
- DEX scope scan: `auth/drive.appdata` is the only Drive scope string
  present (`auth/games`/`games_lite` are inert constants inside the
  bundled GMS auth library, unchanged since Stage 3).

## Step 3 — privacy scans (PASS, over `1cd8cf4..d53a9f9`)

- Zero added `Log.`/`println`/`Timber` lines in the entire range.
- Shortcut and widget intents carry only the boolean `open_quick_add`
  extra (`MainActivity.getBooleanExtra` plus Glance
  `ActionParameters.Key<Boolean>`).
- The calendar `ACTION_INSERT` intent carries title/times/description by
  disclosed design: user-initiated, after a preview dialog showing
  exactly what will be inserted — the feature itself, not a leak.
- Passphrase handling: `String` → `CharArray` conversion happens at the
  ViewModel boundary, matching pre-Stage-5 precedent
  (`BackupViewModel.prepare`,
  `AttachmentIntakeViewModel.deleteRemoteContent`); `pendingPassphrase` is
  held as `CharArray` and NUL-wiped; every engine API takes `CharArray`.
- Glance titles cleared after lock engages, and CSV/`.otvault` partial-output
  deletion on failure, are asserted by the suites exercised in the Step 1
  and Step 2 gates above.

## Dedicated schema-fix (pre-Task-13, complete)

Before Task 13's gates could run, a pre-existing defect required its own
fix: `RECOVERED_SCHEMA_VERSION` was a hand-tracked literal `7`, which
rejected the schema marker `8` that `MIGRATION_7_8` writes on every
migrated device — breaking Drive recovery and `.otvault` import on
migrated devices. Fresh vaults wrote marker `7`, so existing suites could
not catch the defect.

Commit `d8c89e3` ties the recovery gate to the Room database version: a
shared `internal const val VAULT_DATABASE_VERSION = 9` now feeds the
`@Database` annotation, `RECOVERED_SCHEMA_VERSION`, and the fresh-vault
seed. Markers 1..9 are accepted and normalized to 9; migrated devices keep
row marker 8 and recover cleanly. No migration was edited, no version was
bumped, and no frozen fixture changed. A new deterministic
`BackupRecordImporterTest` ran RED against the pre-fix constant and GREEN
after the fix. Review approved with zero Critical or Important findings.

**Disclosed compatibility note.** Post-fix captures carry schema marker 9,
which pre-fix builds would reject as unreadable. No released builds exist,
so this compatibility boundary has no live consequence.

## Final whole-branch review verdict

The final whole-branch review (`1cd8cf4..d53a9f9`, most capable review
tier) returned: **Ready to merge WITH FIXES. 0 Critical.**

Two Important findings were raised and both are resolved by this fix
wave:

1. Task 13 Steps 4–5 contract documents were not yet in range at review
   time (planned-but-unfinished, not a defect). `CLAUDE.md` and
   `docs/architecture.md` were independently verified already updated by
   the review; the threat-model addendum, `DESIGN.md`, `PRODUCT.md`, this
   qualification document, and the `HANDOFF.md` closure entry are the
   remaining Step 4 deliverables, authored in this closing pass.
2. `VaultTransferViewModel.kt:129-137` did not wipe the export passphrase
   on the null-`openOutputStream`/throw branch. This was a Task 6
   deferred minor, triaged as a **must-fix**: the import twin at
   `VaultTransferViewModel.kt:183-188` was ruled Important and fixed in
   Task 7, and the asymmetry between the two paths was judged
   unjustifiable for a one-line change. The fix landed as **Part 1 of
   this closing wave**: the export branch's `finally` now wipes the
   passphrase with the repository's NUL convention, mirroring the import
   twin exactly. No new test was added — the `:app` module's JVM unit
   tests cannot exercise this path (the stub `android.jar` throws on
   `ContentResolver` calls, and this repository does not use Robolectric
   by policy) — matching the established, re-reviewer-upheld
   justification for the twin's own coverage boundary.

Everything else in the deferred-minors backlog **stays deferred**:
inherent or already-documented failure classes, hostile-input hardening,
test-coverage gaps, and style nits. These are bundled into a recommended
post-merge hardening task (see below) rather than blocking this
qualification.

Minor rulings of note from the review:

- App-lock-enable does not flip `locked` until a qualifying foreground
  transition occurs. This matches the pinned `titlesPermitted =
  !(titlePrivacy || locked)` predicate exactly; the spec's "immediately"
  wording is resolved by the threat-model addendum authored in this
  closing pass.
- `WorkspaceCsvWriter`'s `FORMULA_PREFIXES` set lacks a leading tab
  character among its neutralised prefixes (post-merge hardening item,
  not a release blocker).
- `takePendingTransfer`'s unlock-without-ownership pattern is fragile but
  safe.

Cross-task seams — export↔import, retired-sets↔journal↔GC↔recovery,
widget↔lock, and shortcuts↔lock — were all verified to close correctly
across task boundaries.

## Recommended post-merge hardening task

The following deferred items are bundled for a dedicated post-merge
hardening task; none block this qualification:

- The deferred test-coverage minors recorded throughout the Stage 5
  ledger (notably: no `AppLockSettings` persistence-key tests, the
  exactly-at-delay lock boundary case, held-`Esc` key-repeat cascade
  behaviour, the untested ViewModel CSV batch state machine, and related
  items recorded per task in `HANDOFF.md`).
- Adding a leading-tab prefix to `WorkspaceCsvWriter`'s `FORMULA_PREFIXES`
  formula-neutralisation set.

## Recorded Stage 5 boundary

Stage 5 delivers no bidirectional sync path, no in-row attachment
transfer progress, snapshot-only `.otvault` exports (no operation-segment
frames), and one-way calendar insertion with no stored event ID and no
result handling. These are recorded product boundaries, not defects; see
`PRODUCT.md` for the complete delivery-boundary statement.

Samsung Remote Test Lab RTL remains externally blocked pending Samsung
developer-account approval and is unchanged by this qualification. Play
Console work remains externally pending.

## Post-release hosted recovery-test closure — 20 August 2026

Adding `:feature:home` to the hosted connected matrix changed compact-lane
timing and later exposed a second test-only race in the already qualified
`MainActivityRecoveryRestorationInstrumentedTest.productionRecoveryRouteClearsPassphraseAfterActivityRecreation`.
The original clean app-lock baseline remains necessary and correct. The new
failure occurred after the recovery shell was visible: the test clicked
portable discovery, which runs on `Dispatchers.Default`, then immediately
asserted the candidate passphrase field before discovery could publish the
candidate presentation.

Commit `3e1a5a7` adds only the repository's existing Compose condition wait for
the passphrase node before preserving all original input and recreation
assertions. `:app:compileDebugAndroidTestKotlin` passed locally. Hosted run
`32382258182` then completed the app module with 94 tests, 2 established skips,
and 0 failures; the complete compact API 36 seven-module lane was green. No
production code or Stage 5 behavior changed.
