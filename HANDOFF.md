# Open Tasks Handoff

- Last updated: 6 August 2026
- Branch: `main`
- Session status: **Stage 5 is complete and qualified: all 13 tasks of
  the plan (Room v9 retired blob-set index; RETIRED_BLOB_SET backup
  family and collection command; retired-set GC closure; silent
  attachment intake auto-resume; frozen `.otvault` v1 archive format
  with independent Node fixtures; encrypted vault export with SAF
  product surface; encrypted vault import with staged activation and
  rollback; disclosed formula-safe CSV export; Glance Today widget; app
  lock with title privacy and unified Quick Add; keyboard shortcuts
  with help dialog and accessible-action audit; one-way calendar
  insertion; and Task 13 qualification and exit gates), plus the
  dedicated pre-Task-13 `RECOVERED_SCHEMA_VERSION` fix, are complete and
  independently reviewed. Stage 5 closes at the commit containing the
  Step 4 contract documents, on top of the Part 1 passphrase-wipe fix
  `6bfafa8`; see the closure checkpoint below. The authoritative
  qualification record is
  `docs/qualification/stage5-platform-features.md`. The final
  whole-branch review returned Ready to merge with fixes, zero
  Critical; both Important findings — the then-missing contract
  documents and the export passphrase wipe — are discharged by this
  closure. The Task 6
  snapshot-only export ruling was upheld by the user
  before Task 7 and is discharged: import passes an empty segments
  list. The pre-existing recovery defect recorded at the Tasks 7–9
  checkpoint (`RECOVERED_SCHEMA_VERSION = 7` rejecting the schema
  marker 8 that `MIGRATION_7_8` writes, breaking Drive recovery and
  `.otvault` import on migrated devices) is discharged by that fix.
  Both recorded Stage 4 limits are
  discharged:
  the retired-set index by Tasks 1–3 (Room v9 + GC closure) and the
  `AttachmentBlobCoordinator.resume()` product caller by Task 4's silent
  auto-resume. Stage 4 itself is complete and qualified. Task 14's
  one-shot
  credentialed live attachment gate passed in 606.947 s; its preserved harness
  is `a813c41` and must not be rerun. The final disposable six-module gate was
  282 tests, 0 failures/errors, and exactly two expected skips: the
  credential-only row and the exact `Pixel_10_Pro_Fold` cross-display harness
  exception. It makes no native fold-continuity claim; route that work
  post-Stage-4. Forced-fresh debug/unit/lint/APK, release, schema-drift,
  deterministic-fixture, diff, release-scope, and production-logging gates
  passed. The authoritative qualification is
  `docs/qualification/stage4-notes-activity-attachments-search.md`.
  Samsung Remote Test Lab RTL remains External-blocked pending the user's
  developer-account approval. The Fold 8 adaptive slice, Stage 3, Stage 2,
  Train 1 Tasks 1.1–1.5, and Stage 1 remain complete and independently
  reviewed.**
- Current product source implementation point: `6bfafa8` (`fix: wipe
  export passphrase when the output stream cannot be opened`), Task
  13's Part 1 fix, on top of the Task 13 six-module connected-gate fix
  range `f0a8550..d53a9f9` (`334fcae` seed-fixture and `VaultId`
  assertion fixes, `31dd9fc` FoldContinuity teardown hardening,
  `5df2917` app-lock recovery baseline, `d53a9f9` search-focus
  `waitForIdle`) and its checkpoint commit `f0a8550`, on top of
  `d8c89e3` (`fix: accept
  migrated schema markers in recovery import`), the dedicated
  schema-fix commit on top of the checkpoint commit `1767514`, the
  Stage 5 Task 12 range `c0ad0ac..bd8f650` and its
  checkpoint commit `7693fb7`, the Task 11 range `11e4bcb..2ec80aa` and
  its checkpoint commit `aeb013e`, the user-ruled `glance-material3`
  drop `4652a2b`, the Task 10 range `a6c52eb..f76f2a6` and its
  checkpoint commit `36b98b5`, the
  Task 7–9 range `5feb1e8..99db7dc` on top of the
  checkpoint commit `afcfe07`, the Task 3–6 range `1cb768e..b4b1ec4`,
  the Task 1–2 range `33ea364..eb343cb`, and its checkpoint commit
  `f2835b7`. The prior Stage 4 implementation
  point is `b3da5d2` (`test: skip Pixel
  fold harness transition`), the tip of the qualified Stage 4 Task 1–13,
  whole-branch-review, and Task 14 gate-fix range `6538dca..b3da5d2`. The
  prior adaptive-slice closure point
  is `ddbe52a` (`test: guard all continuity database sidecars`) on top of
  `1194536` (`fix: close fold 8 review gaps`), `74d3064` (`fix: align hinge
  split with safe insets`) and the accepted
  visual corrections through `0368dcf`. The full adaptive implementation range
  is `7276f90..ddbe52a`. Accepted visual evidence spans
  `f46ce8c..0368dcf`, with affected rows recaptured after each visual fix;
  final review tests and repository gates ran at `ddbe52a`. The authoritative
  acceptance record is
  `docs/qualification/fold8-adaptive-acceptance.md`; ignored PNG and
  UIAutomator evidence is under
  `.superpowers/sdd/2026-07-31-galaxy-fold8-trifold-adaptive-plan/task-5-evidence/`.
  The prior Stage 3 closure point is `216de3e` (`docs: verify create-only Stage
  3 backup`). The prior Task 12
  closure point is `3109108`; the prior Stage 2 correction point is
  `f9e091b` (`fix: harden stage 2 backup state transitions`); it closes content-key authority, complete
  Inbox capture, same-generation state ownership, initial crash
  reconciliation, active-loop Retry, durable inbox truth, transient intake
  I/O, bounded threshold selection, and stale Ready presentation. Detailed
  RED/GREEN traces remain in
  `.superpowers/sdd/2026-07-29-stage-2-local-backup-android-auto-backup-plan/final-review-fix-brief.md`
  and its adjacent ignored `final-review-fix-report.md`. The completed Stage 2
  design remains
  `docs/superpowers/specs/2026-07-28-stage-2-local-backup-android-auto-backup-design.md`
  with its closed plan at
  `docs/superpowers/plans/2026-07-29-stage-2-local-backup-android-auto-backup-plan.md`.
  The closed Stage 3 execution authority is
  `docs/superpowers/plans/2026-07-30-stage-3-drive-create-only-backup-recovery-plan.md`.
  The controller verified Task 14 on the sole audited API 37 disposable and
  shut it down cleanly. The protected workspace received only a read-only
  metadata and visible-state comparison, then was stopped without snapshot
  save. The user-owned untracked `artifacts/` directory remains untouched.

This is the only live project handoff and ordered backlog. Update it whenever
work changes scope, priority, dependencies, architecture, security assumptions
or verification status.

## Stage 5 Tasks 1–2 checkpoint — 3 August 2026

Stage 5 execution follows the approved design
`docs/superpowers/specs/2026-08-03-stage-5-platform-features-design.md` and
plan `docs/superpowers/plans/2026-08-03-stage-5-platform-features-plan.md`
(committed `981d8c1`, `1cd8cf4`), subagent-driven directly on `main` from
base `1cd8cf4` with an independent review per task. The execution ledger is
`.superpowers/sdd/2026-08-03-stage-5-platform-features-plan/progress.md`.

- Task 1 (`33ea364`, fix `32a8a6f`) added the Room v9 `retired_blob_sets`
  table with exported `9.json`, a non-destructive additive migration and
  preservation test, `RetiredBlobSet` in `WorkspaceSnapshot` (retirement
  time then id ordering), and same-transaction retirement of every
  blob-bearing attachment row a purge removes. Review closed after one fix
  round, which added trash-purge and tombstoned-with-blob coverage and
  extended retirement to a third, review-found path: `restoreTaskStatus`'s
  undo-of-generated-occurrence branch, whose in-memory arm previously
  failed to remove the occurrence's attachments at all.
- Task 2 (`35d54c3`, fix `eb343cb`) added the `RETIRED_BLOB_SET` backup
  family end-to-end after `NOTE` (the reviewer verified no family ordinal
  is persisted anywhere): strict mutation-codec validation (0..25 chunks),
  journal emission in both engines via the snapshot diff, recovery import,
  capture-DAO attribution, staged-vault verification, deterministic Stage 2
  fixture regeneration, and the idempotent `MarkRetiredBlobSetCollected`
  command (absent row → Success, no journal write, no Undo). The verifier's
  settle-purge accounting was extracted as a pure, unit-tested rule that
  accepts exactly the purge's own blobSetId-matched retired rows as drift
  and fails closed on anything else — load-bearing for Task 7's import
  reuse. The retired-row `revisionLogical = 0` decision was examined and
  upheld. Review approved with zero Critical or Important findings.
- No device suite ran; all new instrumented tests are compile-verified and
  execute at the Task 13 connected gate. Notable deferred minors in the
  ledger: the verifier's zero-slack `retiredAt <= now` check (a backwards
  clock step between settle and verification would reject a legitimate
  recovery), no Room-side execution of the collect arm before Task 13, and
  the pre-existing in-memory `restoreTaskStatus` gap that still leaves a
  generated occurrence's activity and time entries uncleaned.

This checkpoint's resume instruction is superseded: Tasks 3–6 were
executed on 3–4 August and are recorded in the checkpoint below.

## Stage 5 Tasks 3–6 checkpoint — 4 August 2026

Execution resumed from `f2835b7` with the same plan, ledger, and
independent-review-per-task discipline. All four task boundaries closed
with zero open Critical or Important findings. No device suite ran; all
new instrumented tests remain compile-verified and execute at the Task 13
connected gate.

- Task 3 (`1cb768e`, fix `7abe74e`) joined `retired_blob_sets` rows into
  the attachment GC candidate stream: `deletedAt = retiredAt`, tombstone
  generation from the RETIRED_BLOB_SET journal family, base coverage via
  the existing helpers, live-`blobSetId` replacement rows excluded, and
  post-batch release through `MarkRetiredBlobSetCollected`. The review's
  one Important finding — `recordAllContentCollected()` left retired rows
  permanently unsatisfiable after the destructive attachment wipe,
  costing a full authorize/resolve/list round trip forever — was fixed by
  releasing `workspace.retiredBlobSets` there too, with a covering test.
- Task 4 (`4a216bc`, fix `a6617d8`) gave
  `AttachmentBlobCoordinator.resume()` its product caller: silent
  auto-resume ordered strictly after session expiry on runtime start
  (same coroutine) and re-armed after each completed publication run, wired
  through a new `resumeAttachmentSessions` lambda in AppModule. The
  review's Important finding — resume authorized against Drive even with
  nothing pending — was fixed with the sibling
  `hasUnfinishedSessions()` guard. There is still no in-row transfer
  progress; that boundary is unchanged.
- Task 5 (`cf68f2e`) froze the `.otvault` v1 archive format:
  `OtVaultCodec` with a bounded authenticated header whose envelope is a
  real recovery envelope (export passphrase = recovery passphrase,
  Argon2id 64 MiB/3/1/16-byte salt), archive-scoped object IDs with
  bidirectional replay resistance, streamed Stage 1 frames, an
  inventory-last integrity check, and an independent Node fixture
  generator whose byte-identical regeneration the controller verified.
  Review approved with zero Critical or Important findings; the
  manifest-codec `internal` extraction was ruled a sound pure move. The
  practical archive bound is the inventory frame's byte limit (~6,000
  objects ≈ 240 attachments at 25 chunks), not the entry-count constant.
- Task 6 (`4eb112f`, fix `b4b1ec4`) built encrypted vault export:
  capture → recovery-envelope wrap → complete attachment pre-flight
  (returns `MissingAttachmentBytes` naming every unfetchable attachment
  before a single byte is written) → streamed archive → `Completed`,
  with a `VaultTransferViewModel` owning the SAF `CreateDocument` flow,
  passphrase sheet with confirmation, partial-document deletion on
  failure and — after the fix round — on cancellation via a
  `NonCancellable` finally. Archive manifests carry `ZERO_SHA256`-style
  sentinels instead of plausible-but-wrong digests; passphrase wipes now
  use the repo-wide NUL convention.

**Controller ruling requiring user confirmation before Task 7:** the
Task 6 review flagged that no operation-segment frames are written even
though the brief's export-order sentence lists them. The controller ruled
snapshot-only export correct: `RoomBackupCaptureSource.capture()` is a
complete point-in-time baseline captured in one transaction, the
authority spec never mentions segments, Task 7 imports into fresh-only
operational state (empty remote tables — segments would have no
consumer), and the brief's own Consumes list names no segment source.
Task 7 would pass an empty segments list to `RecoveryImportRequest`. The
user upheld the ruling on 4 August before Task 7 was dispatched; it is
discharged.

Carry-forwards for Task 7, recorded in the ledger: archive manifests
carry sentinel `ciphertextSha256`/`providerObjectId` values — the
importer must never hand them to the live open path expecting frame
digests (per-frame integrity is the inventory plus AEAD);
`readChunksForExport` and its AppModule wiring are compile-verified only,
with Task 7's `OtVaultImportInstrumentedTest` as the end-to-end proof at
the Task 13 connected gate. Notable deferred minors in the ledger: the
unwiped passphrase array on the rare null-`openOutputStream` branch
(memory-hygiene only; final review must triage), the unbounded read-side
inventory accumulator in `OtVaultCodec.readAll` (hostile archive could
OOM instead of failing closed), and the export row remaining enabled
during an in-flight export.

This checkpoint's resume instruction is superseded: Tasks 7–9 were
executed on 4–5 August and are recorded in the checkpoint below.

## Stage 5 Tasks 7–9 checkpoint — 5 August 2026

Execution resumed from `afcfe07` with the same plan, ledger, and
independent-review-per-task discipline. The user upheld the Task 6
segments ruling before dispatch, so `.otvault` stays snapshot-only and
import passes an empty segments list. All three task boundaries closed
with zero open Critical or Important findings. No device suite ran; new
instrumented tests are compile-verified and execute at the Task 13
connected gate.

- Task 7 (`5feb1e8`, fix `e8962e1`) built encrypted vault import and
  activation: `OtVaultImporter` stages into an isolated slot with full
  import-policy verification (single snapshot, no segments, contiguous
  manifest-authenticated chunks, digest reproduction, every live
  attachment's blob set present), previews exact counts with
  beyond-cache names, and activates through the proven recovery
  slot-replacement path, retaining the previous slot as rollback until
  first unlock. The archive envelope becomes the imported vault's
  stored recovery envelope. The fix round moved retained chunks into an
  import-scoped `vaultImportStagingRoot` that never mutates the live
  attachment cache (budgeted ceiling-minus-usage, promoted only after
  activation), joined `staging.activate` to `reconstruct` inside the
  guarded try so any failure abandons the staged vault and key, and
  moved the import passphrase wipe to a `finally` covering the
  null-stream branch. Sentinel archive manifest digests never reach a
  live open path; per-frame integrity is the inventory plus AEAD.
- Task 8 (`b9ecd9b`) added disclosed formula-safe CSV export: a pure
  `WorkspaceCsvWriter` with the four fixed tables, RFC 4180 quoting,
  UK-display and ISO columns in the moment's stored zone,
  `=`/`+`/`-`/`@` neutralisation, Bin exclusion, and the exact spec
  disclosure copy with no "do not ask again"; one
  `CreateDocument("text/csv")` per selected table, streamed with
  partial-output deletion on failure. Review approved with no fix
  round; two judgment calls upheld (ISO_LOCAL_DATE for bare project
  due dates; a generic failure outcome on a partial batch).
- Task 9 (`0f13b7c`, fixes `9a0353d`, `99db7dc`) added the Glance
  Today widget: pure `computeTodayProjection` (today/overdue counts,
  three focus titles, `titlesPermitted` seam for Task 10), a publisher
  bound to the active-slot lifecycle, receiver republish on placement,
  and Material typography role values. Two user rulings: overdue
  follows the prose via a new `now: Instant` parameter (a task due
  earlier today is overdue now, not at midnight), and
  `glance-material3` was approved as a second catalogue entry — it
  proved colour-only with no typography API, so it sits unreferenced
  while role values come from `material3.Typography()`; keep-or-drop
  is an open user decision. Fix round 2 extracted `StopGatedWriter`, a
  mutex gate proven by deterministic tests, so no Glance write on any
  path can land after the stop-time title clear.

**Pre-existing defect requiring its own task (controller-verified in
code):** `RECOVERED_SCHEMA_VERSION = 7` (`BackupRecordImporter.kt:443`,
`:760`) rejects any captured vault whose schema marker exceeds 7, while
`MIGRATION_7_8` sets marker 8 on every migrated vault
(`VaultDatabase.kt:1125`). This already breaks Drive recovery — and now
`.otvault` import — on migrated devices. Fresh vaults are written with
marker 7 (`RoomVaultRepository.kt:3115`), so the existing deterministic
and instrumented suites cannot catch it. Schedule a dedicated fix task
before the Task 13 gates.

Carry-forwards recorded in the ledger: Task 10 must wire the widget's
`titlesPermitted` seam to lock/privacy state; the Task 13 device
checklist gained the SAF `application/octet-stream` picker visibility
check for `.otvault` and an on-device tap test that Glance's
parameter-to-extra mapping reaches `MainActivity` as
`"open_quick_add"`. Deferred minors are in the ledger (Task 7
staging-promotion edges, Task 8 partial-batch messaging and the
untested ViewModel batch machine, Task 9 zone capture at construction
and midnight rollover).

This checkpoint's resume instruction is superseded: Task 10 was
executed on 5 August and is recorded in the checkpoint below.

## Stage 5 Task 10 checkpoint — 5 August 2026

Execution resumed from `36b98b5` with the same plan, ledger, and
independent-review-per-task discipline. The task boundary closed with
zero open Critical or Important findings after one fix round. No device
suite ran; new instrumented tests are compile-verified and execute at
the Task 13 connected gate.

- Task 10 (`a6c52eb`, fix `f76f2a6`) added the app lock, title privacy,
  and unified Quick Add: `AppLockSettings` over SharedPreferences,
  a clock-injected pure `AppLockController` (cold start locked when
  enabled; foreground after a background span >= the chosen delay
  locks; IMMEDIATE/1/5/15-minute options), and an `AppLockScreen`
  overlay that replaces all content with no workspace data composed
  behind it. Unlock is one platform `BiometricPrompt` with
  device-credential fallback and changes no key material.
  `titlePrivacy || locked` drives `titlesPermitted = false` into the
  Task 9 widget publisher strictly through the existing
  `StopGatedWriter` gate with an immediate republish on engage, plus
  the generic notification content path and generic external Quick Add
  labels. `setRecentsScreenshotEnabled(false)` applies whenever
  `lockEnabled || titlePrivacy`; `FLAG_SECURE` only under
  `screenshotBlocking`. A static launcher shortcut and the widget
  extra both route through `MainActivity.handleIntent` to the one
  shared `QuickAddSheet` after unlock; exported intents carry only the
  boolean extra. The fix round hoisted the `locked` check above the
  `activeRecovery` branch (a user-opened recovery shell with
  destructive restore/takeover actions can no longer render unlocked
  after a background span; `NoVault`/`Unreadable`/`Recovering` stay
  ungated), added the plan-named `AppLockOverlayInstrumentedTest`
  (compile-verified, runs at Task 13), and closed the silent unlock
  no-op at both ends: the overlay shows an unavailable message when
  the prompt cannot be shown, and the More toggle is gated on
  `canAuthenticate` success while an already-enabled lock can still be
  turned off.
- The open `glance-material3` decision was resolved by user ruling:
  dropped. `4652a2b` removes the catalogue entry and the unused `:app`
  dependency; Material role values continue to come from a plain
  `material3.Typography()` baseline, and the rationale comment in
  `TodayWidget.kt` stays.

Carry-forwards unchanged from the Tasks 7–9 checkpoint: the dedicated
`RECOVERED_SCHEMA_VERSION` fix task must land before the Task 13
gates, and the Task 13 device checklist retains the SAF
`application/octet-stream` picker visibility check for `.otvault` and
the Glance parameter-to-extra tap test reaching `MainActivity` as
`"open_quick_add"`; it now also covers running
`AppLockOverlayInstrumentedTest` and the runtime widget/notification
concealment checks. Deferred minors are in the ledger (notably: no
`AppLockSettings` persistence-key tests, the exactly-at-delay boundary
case, and the untested `setTitlesPermitted`/concealed-notification
branches until the device suite).

This checkpoint's resume instruction is superseded: Task 11 was
executed on 5 August and is recorded in the checkpoint below.

## Stage 5 Task 11 checkpoint — 5 August 2026

Execution resumed from `aeb013e` with the same plan, ledger, and
independent-review-per-task discipline. The task boundary closed with
zero open Critical or Important findings after one fix round. No device
suite ran; the new instrumented test is compile-verified and executes
at the Task 13 connected gate.

- Task 11 (`11e4bcb`, fix `2ec80aa`) added keyboard, mouse, and
  accessible actions: a pure `shortcutActionFor` dispatcher with the
  pinned mapping (`Ctrl+K` and `/` → search; `Ctrl+N` → Quick Add;
  `Ctrl+Shift+N` → new project only in the Projects route; `?` → help;
  `Esc` → dismiss top), single-key suppression while an editable is
  focused, a `ShortcutHelpDialog` listing every shortcut via
  `stringResource`, and root wiring in `OpenTasksApp` whose
  `DISMISS_TOP` closes help dialog → open sheet → expanded search and
  never finishes the Activity. Quick Add routes through the Task 10
  shared sheet. The accessible-action audit found no drag-only action
  on the five feature screens (workflow reordering is explicit up/down
  IconButtons). The fix round enforced the pinned phase split
  structurally: the preview handler early-returns unless Ctrl is
  pressed and dispatches only Ctrl combos, the bubbling handler
  early-returns when Ctrl is pressed and is the sole path for bare
  single keys including `/`, making the two phases mutually exclusive
  by construction, with the dispatcher KDoc and root comments corrected
  to match the real dispatch paths.
- Evidence: 15/15 `ShortcutDispatcherTest` unit tests (RED/GREEN); the
  CI gate passed; `ShortcutRootWiringInstrumentedTest` (Ctrl+K asserts
  search focus, Esc asserts sheet dismissal) compiles and runs at
  Task 13.

Carry-forwards unchanged: the dedicated `RECOVERED_SCHEMA_VERSION` fix
task must land before the Task 13 gates, and the Task 13 device
checklist retains the SAF `application/octet-stream` picker visibility
check for `.otvault`, the Glance parameter-to-extra tap test, running
`AppLockOverlayInstrumentedTest`, and the runtime widget/notification
concealment checks; it now also covers running
`ShortcutRootWiringInstrumentedTest`. Deferred minors are in the ledger
(notably: held-`Esc` key repeat can cascade the dismiss order through
several surfaces; `Ctrl+Escape` dismissed pre-fix and is now dropped by
both phase guards; the instrumented test exercises a replica of the
root wiring rather than `OpenTasksApp` itself).

This checkpoint's resume instruction is superseded: Task 12 was
executed on 6 August and is recorded in the checkpoint below.

## Stage 5 Task 12 checkpoint — 6 August 2026

Execution resumed from `7693fb7` with the same plan, ledger, and
independent-review-per-task discipline. The task boundary closed with
zero open Critical or Important findings after one fix round. No device
suite ran.

- Task 12 (`c0ad0ac`, fix `bd8f650`) added one-way calendar insertion:
  a pure `calendarEventDraft` in `:app` with the pinned rules (undated
  → null; start present → begin at `start.instant` with end at
  `due?.instant` only when strictly after; due-only → begin at
  `due.instant` with null end; description `Project: <name>` or empty),
  and an intent wrapper carrying exactly `ACTION_INSERT`,
  `Events.CONTENT_URI`, begin, conditional end, title, and description
  — no permission, no stored event id, no result handling. "Add to
  calendar" appears in the task editor and both Schedule layout modes
  only when the draft is non-null (the reviewer upheld the
  both-modes judgment call), via nullable `onAddToCalendar` lambdas
  that keep feature modules platform-free. A preview dialog shows
  title, times formatted in each moment's stored zone (UK format), and
  description with Insert/Cancel. The fix round moved the
  `feature:schedule` content-description copy into that module's first
  `res/values/strings.xml` (read via `stringResource`) and replaced the
  silent `ActivityNotFoundException` catch with the
  `calendar_no_provider` snackbar on the scaffold host, matching the
  attachment-delivery precedent.
- Evidence: 6/6 `CalendarInsertionTest` pinned cases (RED/GREEN); CI
  gate passed; `:app`, `:feature:tasks`, and `:feature:schedule`
  instrumented-test compilation green.

Carry-forwards: all 12 implementation tasks are complete; only Task 13
(qualification) remains. Before its gates, the dedicated
`RECOVERED_SCHEMA_VERSION` fix task must land — it is not in the plan;
author its brief from the Tasks 7–9 checkpoint defect record
(`RECOVERED_SCHEMA_VERSION = 7` at `BackupRecordImporter.kt:443`,
`:760` rejects the schema marker 8 that `MIGRATION_7_8` writes at
`VaultDatabase.kt:1125`; fresh vaults write marker 7 at
`RoomVaultRepository.kt:3115`, so existing suites cannot catch it).
The Task 13 device checklist retains the SAF
`application/octet-stream` picker visibility check for `.otvault`, the
Glance parameter-to-extra tap test, `AppLockOverlayInstrumentedTest`,
the runtime widget/notification concealment checks, and
`ShortcutRootWiringInstrumentedTest`. Deferred minors are in the
ledger.

This checkpoint's resume instruction is superseded: the dedicated
schema-fix task was executed on 6 August and is recorded in the
checkpoint below.

## Stage 5 schema-fix checkpoint — 6 August 2026

Execution resumed from `1767514` with the same plan, ledger, and
independent-review-per-task discipline. The dedicated pre-Task-13
`RECOVERED_SCHEMA_VERSION` fix task (not a numbered plan task; its
brief was authored from the Tasks 7–9 checkpoint defect record) closed
with zero Critical or Important findings and no fix round. No device
suite ran; the changed instrumented tests are compile-verified and
execute at the Task 13 connected gate.

- The fix (`d8c89e3`) ties the recovery gate to the Room database
  version: a shared `internal const val VAULT_DATABASE_VERSION = 9` in
  `VaultDatabase.kt` now feeds the `@Database` annotation,
  `RECOVERED_SCHEMA_VERSION` (previously a hand-tracked literal 7), and
  the fresh-vault seed (previously 7). Captured vaults with markers
  1..9 are accepted and normalized to 9; migrated devices keep row
  marker 8 and recover cleanly; a future migration can no longer reopen
  this defect class because migrations write markers at most the
  database version by construction. No migration was edited, no version
  bumped, and no exported schema or frozen fixture changed — the frozen
  `.otvault` v1 fixture's marker-9 vault record, previously rejected by
  the gate, now imports without a byte of it changing.
- Evidence: the new deterministic `BackupRecordImporterTest` ran RED
  against the pre-fix constant (3/4 cases failing) and GREEN after; the
  CI gate passed (all unit suites zero failures, lint clean, debug
  APK); `:core:data:assembleDebugAndroidTest` compiles;
  `scripts/check-schema-drift.sh` clean. `futureSchemaVersionIsRejected`
  now rejects `VAULT_DATABASE_VERSION + 1` instead of enshrining the
  defect; a marker-8 import-acceptance case and a migration-chain
  marker-bound assertion were added and run at Task 13.
- Reviewer minors are deferred in the ledger, notably: the
  migration-test guard comment overstates its reach (the deterministic
  JVM test is the real guard); two weak assertions (a bare `assertTrue`
  and an unpinned exception message); and one disclosed compatibility
  note — post-fix captures carry marker 9, which pre-fix builds would
  reject as unreadable (brief-mandated; no released builds exist).

Carry-forwards: only Task 13 (qualification) remains. Its device
checklist is unchanged from the Task 12 checkpoint (the SAF
`application/octet-stream` picker visibility check for `.otvault`, the
Glance parameter-to-extra tap test, `AppLockOverlayInstrumentedTest`,
the runtime widget/notification concealment checks, and
`ShortcutRootWiringInstrumentedTest`) and now also confirms the new
marker-8 import acceptance and migration-chain assertions execute green
on device.

This checkpoint's resume instruction is superseded: Task 13 was executed
on 6 August and is recorded in the checkpoint below.

## Stage 5 closure checkpoint — 6 August 2026

Stage 5 closes at `4df0af5` (`docs: fix stage 5 review round-2
findings`), which amends the Step 4 contract documents committed in
`8c7fcd0` (a broken threat-model table row and four architecture.md
sections the brief required), on top of the Part 1 passphrase-wipe fix
`6bfafa8`. The round-2 scoped re-review verified both amendments clean
against source with no new breakage. Execution resumed from `f0a8550`
with the same plan, ledger, and independent-review discipline; Task 13
(qualification and exit gates) is the plan's final task.

- The six-module connected gate's first run (10m13s) failed 7 tests
  across `:core:data` and `:app`. All seven were root-caused as
  test-only defects and fixed with no product code touched: a v8 seed
  fixture missing one `NULL` value, a raw-`String`-vs-`VaultId`
  assertion mismatch, `FoldContinuityInstrumentedTest` teardown that
  interleaved verification with destructive cleanup, a stale
  `lock_enabled` device baseline ahead of a recovery-restoration test,
  and a search-focus assertion racing its own `LaunchedEffect`. The
  official rerun at `d53a9f9` (9m05s) passed **293 tests, 0 failures, 0
  errors, 2 expected skips** — the credential-only qualification row and
  the exact `Pixel_10_Pro_Fold` cross-display exception, exactly the
  Stage 4 pair. This reconciles the plan's "only expected skip" wording,
  which undercounts by one. The `FoldContinuity` suite remains
  residue-independent only against residue this repository's own suites
  can create; residue from device history outside those suites, on the
  never-wiped protected AVD, can still trip the deliberate guard, and no
  code-only fix exists without weakening the guard or wiping the AVD,
  both forbidden.
- Forced-fresh `testDebugUnitTest lintDebug :app:assembleDebug` passed
  547/547 tasks with 1,045 JVM unit tests and zero failures; forced-fresh
  `:app:assembleRelease` passed 441/441 tasks. Schema drift, all three
  fixture generators, and `git diff --check` were clean. Release
  inspection found no new exported component (the widget receiver is
  `exported="false"`), no debug qualification activity, the expected
  `USE_BIOMETRIC` addition, and `auth/drive.appdata` as the sole Drive
  scope string.
- Privacy scans over the whole Stage 5 range (`1cd8cf4..d53a9f9`) found
  zero added `Log.`/`println`/`Timber` calls, shortcut and widget
  intents carrying only the boolean `open_quick_add` extra, disclosed
  calendar-intent content, and `CharArray`-only passphrase handling with
  the pending passphrase NUL-wiped.
- The final whole-branch review (`1cd8cf4..d53a9f9`, most capable tier)
  returned **Ready to merge with fixes; 0 Critical**. Its two Important
  findings are both closed by this wave: the Step 4 contract documents
  (this commit) and the export-path passphrase wipe (`6bfafa8`), which
  mirrors the import twin's existing `finally` shape exactly and needs
  no new test because the `:app` module's JVM unit tests cannot exercise
  a `ContentResolver` failure path (stub `android.jar`, no Robolectric
  by policy). Everything else in the deferred-minors backlog stays
  deferred and is bundled into the post-merge hardening task below.
- The authoritative qualification record is
  `docs/qualification/stage5-platform-features.md`.

Carry-forwards: Samsung Remote Test Lab RTL remains External-blocked
pending the user's developer-account approval; Play Console work remains
externally pending. A recommended post-merge hardening task bundles the
deferred test-coverage minors recorded through the Stage 5 checkpoints
(notably: no `AppLockSettings` persistence-key tests, the exactly-at-delay
lock boundary, held-`Esc` key-repeat cascade behaviour, and the untested
CSV batch ViewModel state machine) plus adding a leading-tab prefix to
`WorkspaceCsvWriter`'s `FORMULA_PREFIXES` formula-neutralisation set.

## Stage 4 closure checkpoint — 3 August 2026

Stage 4 closes at the commit containing the contract documents and
`docs/qualification/stage4-notes-activity-attachments-search.md`.

- The credentialed attachment qualification passed once in 606.947 s with the
  preserved `a813c41` harness. It proves only its exact chunk-create,
  readback, manifest-lookup, and cleanup properties; do not rerun it or infer
  wider provider coverage.
- Connected-gate history is retained in the qualification record: 282/10/0/1,
  then 282/1/0/1 after `1ba5d0e`, `b5e6a1f`, `3648595`, `a328695`, and
  `bf2f95a`; successive fold-guard rounds; the invalidated cascade run; and
  the final `b3da5d2` exact-AVD exception. Final result: 282/0/0/2.
- The final API 37 / Android 17 `Pixel_10_Pro_Fold` target was read-only,
  snapshot-disabled, headless, and the sole ADB device; only overlay-local
  `font_scale=1.0` changed. The preflight app was cleanly uninstalled and
  final package/ADB/host audits were empty.
- Forced-fresh debug/unit/lint/APK was 547 executed tasks and 935 JVM tests in
  80 suites with zero failures; release was 441 executed tasks. Schema drift,
  both generators, and diff-check were clean. Release exposed only
  `drive.appdata`, no debug activity or client secret, and logging scans were
  empty.
- Stage 5 must address the conservative retained encrypted bytes for purged
  attachment blob sets with a schema-backed retired-set index, and decide a
  product path for `AttachmentBlobCoordinator.resume()`; retain its tests.
  Samsung RTL is still externally blocked.

## Executive status

Open Tasks is a working local-authority Android foundation, not yet a
production release. Encrypted Room is the sole live structured-data authority.
The encrypted local workspace, adaptive shell, task editor, recurring
tasks, custom per-project workflows, editable milestones, Bin, project
workbench, search, timers and due-relative reminders persist across process
restarts. Reminder permission timing, exact-alarm fallback, lock-screen
redaction, task deep links, Snooze and Complete actions are implemented.
Workflow add, rename, reorder, archive and restore preserve immutable reporting
categories and assigned tasks.
Milestone create/edit/complete/reopen/delete and project-scoped task membership
are implemented with atomic backup-journal writes and exact Undo. Task dependency
editing, cycle rejection, dynamic blocking and named completion warnings are
also implemented. Schedule now derives real selected-day and Monday–Sunday
views from task dates and reminders, with an open-only unscheduled tray.
Routes, selected records, filters, meaningful scroll positions and bounded
drafts restore across process recreation without the initial repository
emission replacing unsaved text. Running timers resume from their encrypted
time entry and original start instant. Completed time can now be added, edited
and deleted from a task, with optional bounded notes, exact repository-produced
Undo and encrypted restart persistence. Overlapping records are deliberately
preserved and surfaced for review so double-counted work is never hidden. The
P0 hardening slice and P1-L01 through P1-L04 plus P1-L06 and P1-L07 product
slices are complete and verified. P2-F01 adds versioned reusable project
templates with relative zoned dates, deterministic relation remapping,
encrypted Room persistence and atomic backup-journal writes. Qualified workspace
Insights now cover completion, overdue work, estimate/actual time, project/tag
time and milestone health with accessible table/text alternatives. The
application is fixed to UK English with UK spelling, day–month dates, 24-hour
time and Bin terminology.

The private GitHub authority is
[ksdaklmk/open-tasks](https://github.com/ksdaklmk/open-tasks); local `main`
tracks `origin/main`. Secret scanning, push protection, Dependabot alerts and
security updates are enabled. The Android workflow now uses explicit compact
API 36/stable/Pixel 6 and expanded API 37/canary/Pixel Tablet instrumented
matrix entries, passes the matching channel and profile to the emulator runner,
and runs a separate release-assembly job after verification. Its local
structural verifier prevents mutable Action references. The 30 July maintenance
audit found no open pull requests or issues; it applied the queued dependency
updates directly to `main` after local verification. A blocking `PreToolUse` guard rejects raw
Kotlin `Color(0x...)` writes outside `core/designsystem`; its deterministic
protocol verifier covers both Write and Edit payloads. Non-provider secret
patterns and validity checks are unavailable in the current repository plan
and remain disabled. Stage 2 local backup and the supplementary Android
package are implemented and user-visible in More. Stage 3 create-only Tasks
1–14 are committed and verified: qualified transport, frozen formats, Room
v7 remote state, crash-safe vault slot gating, explicit authorization with
HMAC account binding, byte-bounded create-only object storage,
crash-resumable epoch-one ownership resolution, immutable successor
publication with namespace-safe cleanup, unique background scheduling,
verified staging reconstruction, recovery with writer takeover and activation,
passphrase rotation, disconnect, permanent remote-history deletion, and
separate-lineage preservation, product UI, complete recovery/takeover
qualification, and final release/privacy/protected-workspace gates. Stage 4
now qualifies attachment metadata, blob transport, and attachment product
surfaces; Play Console work remains externally pending.

Train 1 Tasks 1.1–1.5 and Stage 1 are complete. Vault-content keys are
independent of SQLCipher database keys and have separate recovery and per-vault
Android Keystore wrapping. Canonical bounded cloud frames exist for manifests,
snapshots, operation segments and attachment chunks. The authenticated
provider-independent codec binds their complete identity as AEAD associated
data and has independent deterministic vectors for all four families. Stage
2's committed Task 1–10 baseline and final-review correction add the local
backup journal, strict snapshot/segment payloads, consistent capture, verified
local recovery objects, recovery-envelope preparation, the portable package,
runtime activation, exact Android eligibility, inert restored input, and
status UI. The final review's correctness defects are closed in `f9e091b`.
The recovery, takeover, passphrase-rotation, remote-lifecycle, and product
surface work is committed through Task 14. Stage 4's attachment transport and
product flows are qualified; the committed Stage 3 Drive transport work is
recorded above. The historical
Train 1 Task 1.6 is superseded.

## Stage 3 provider and replacement-design checkpoint — 30 July 2026

The original Stage 3 design and plan were committed as `8e0cfdd`, `afb59e3`,
and `a39f117`. Task 1 then built an uncommitted debug-only credentialed
qualification path, bounded Drive HTTP transport, app-data authorization, and
provider evidence. None of that source is qualified or committed.

The credentialed investigation established:

- Google authorization, exact `drive.appdata` scope, account selection, and
  Drive API access reach the provider on the sole disposable API 37 emulator;
- pre-generated IDs are accepted for `appDataFolder` file creation;
- successful create responses provide no strong HTTP ETag;
- one approved bounded `files.get(...?fields=id)` metadata fallback also
  provides no strong HTTP ETag; and
- the final bounded result is
  `TRANSPORT_CREATE_CONTROL_CONDITIONAL_UNAVAILABLE`.

The approved hard stop was honored: there was no retry, JSON-version fallback,
staging, source commit, or Task 2 work.

The replacement design preserves the Drive-only boundary and eliminates
mutable provider control. A short immutable ownership chain uses one
pre-generated successor ID per takeover. Normal backups are immutable
publications within the active epoch. Every new epoch uploads and verifies two
independent complete bases before ownership activation. A stale old device may
finish non-authoritative old-epoch bytes, but cannot overwrite, delete, or
become authority over the new epoch.

Before any Stage 3 source commit, the revised first task must repeatedly prove
that two different authenticated claims racing one pre-generated successor ID
produce exactly one unchanged winner and bounded duplicate rejection. A
provider-property failure stops Stage 3 for another design; an authorization
setup failure blocks the qualification until diagnosed without establishing
any provider result. The untracked `artifacts/` directory remains user-owned
and untouched.

The executable replacement plan is
`docs/superpowers/plans/2026-07-30-stage-3-drive-create-only-backup-recovery-plan.md`.
Its fourteen review boundaries preserve the runtime-slot prerequisite, add a
local Room state version instead of any provider revision, give the two
same-generation bases independent remote identities, and finish with
credentialed two-installation, terminal-tombstone, protected-workspace,
privacy, schema, release, and connected gates. The older Stage 3 plan remains
historical and is not the execution authority for create-only work.

## Stage 3 revised Task 1 stopping checkpoint — 30 July 2026

Subagent-driven execution started from documentation commit `303d1ae` in the
user-approved `main` checkout. The first implementer replaced the superseded
revision-bearing boundary with uncommitted `CreateOnlyDriveTransport` and
`HttpCreateOnlyDriveTransport` work, plus the debug-only create-only
qualification harness.

Fresh local evidence recorded in
`docs/qualification/stage3-drive-create-only.md`:

- 14 focused create-only transport tests passed;
- 4 deterministic qualification tests passed;
- ten fake exact-successor races each selected one winner and one occupied
  loser;
- all thirty loser retries stayed occupied and all thirty winner readbacks
  stayed byte-identical and authenticated;
- deliberately discarded create success resolved only through exact-ID
  authenticated readback;
- deterministic cleanup-on-success and cleanup-on-failure passed;
- injected HTTP tests distinguished missing, authorization, quota, retryable,
  corrupt, provider-rejected, occupied, definite pre-request, and
  post-transmission ambiguous outcomes; and
- debug and release assemblies passed with the internal qualification
  activity present only in debug.

The exact credentialed command ran nine instrumentation tests on the sole
audited API 37 `Pixel_10_Pro_Fold` disposable. Eight passed. The explicit
credentialed test returned
`AUTH_START_ApiException_INTERNAL_ERROR_8` before the account UI completed.
No Drive provider request ran and no disposable provider object was created.
Therefore the ten live races, thirty live loser retries, unchanged live
winner readbacks, discarded-response resolution, and provider cleanup remain
unqualified. The controller re-audited that sole API 37 target, shut down only
the read-only/no-snapshot disposable, and confirmed the final ADB target list
was empty.

The hard stop was honoured: no Task 1 path is staged, no Stage 3 source commit
exists, and Task 2 did not start. The detailed ignored execution report is
`.superpowers/sdd/2026-07-30-stage-3-drive-create-only-backup-recovery-plan/task-1-report.md`.
Resume by diagnosing the authorization start failure on the same intended
Android authorization stack. Do not treat the deterministic harness as
provider evidence, and do not stage Task 1 or begin Task 2 unless a later
exact gate returns `PASS`. The historical uncommitted Stage 3 plan modification
and user-owned `artifacts/` directory remain outside scope and untouched.

Resolution, later on 30 July 2026: read-only device audit showed the freshly
booted disposable inherited zero Google accounts, while Play services,
checkin, network, and the device clock were healthy; the superseded gate had
only ever completed authorization in a session with existing signed-in
account state, which the read-only overlay discarded at shutdown. The Google
Identity Authorization API fails with bounded internal error 8 when the
device has no Google account. The user signed a Google account into the
AVD's persistent device state; it survived restart and appeared on the
freshly booted sole audited read-only API 37 disposable. The exact
credentialed gate then ran once and returned bounded `PASS` (nine tests,
zero failures; the credentialed test completed consent and the full live
provider sequence in 364.7 seconds). The CI gate passed, the Step 9
forbidden-concept scan found no matches, and Task 1 was committed as
`b6191fd`. The disposable was shut down after the run and the final ADB
target list was confirmed empty.

## Stage 3 create-only Tasks 2–6 checkpoint — 31 July 2026

Subagent-driven execution continued from the Task 1 qualification commit
`b6191fd` directly on `main`, with an independent task review after each
task and scoped re-reviews after every fix round. All five task boundaries
closed with zero open Critical or Important findings.

- Task 2 (`7ce27b1`) froze the ownership-claim and publication formats:
  opaque redacted IDs, strict authenticated codecs binding every public
  identity through the Stage 1 manifest family, publication-pair authority
  validation, and independent Node fixtures with byte-identical
  regeneration.
- Task 3 (`b0284bb`, `184786f`) added additive Room v7 remote state with a
  byte-preserving v6 fixture migration test. A user ruling added the
  `previousPublicationGeneration` column before the schema shipped, and the
  schema-drift script gained a restore-on-tool-failure path.
- Task 4 (`5fc11ae`, `839efc5`, `4f57781`) gated startup behind crash-safe
  vault slots. Fix rounds restarted the orphaned Stage 2 backup pipeline on
  session open, closed four activation-safety windows, added a real
  `AtomicFile` crash-boundary suite, and extended the staged content-key
  verification rung to the crash-restart completion path. A live disposable
  launch confirmed unrenamed legacy adoption on real migrated data.
- Task 5 (`ca70ca8`, `2bb76dc`) added explicit authorization and HMAC
  account binding behind a non-exportable Keystore key, with no token or
  identifier persistence. A user ruling enriched
  `DriveAuthorizationResult.Unavailable` with a bounded reason enum, and a
  missing Google `Account` handle now degrades revoke instead of failing
  authorization.
- Task 6 (`051ce53`, `05a1d44`, `75f94a8`) implemented create-only Drive
  object storage: exact-ID occupied/ambiguous resolution, take-once owned
  bytes/files, provider-confirmed resumable transfer with bounded restart
  and stall guards, compare-and-set persistence that fails closed, per-role
  Stage 1/2 byte ceilings, and lineage-visible small creates proven by a
  create-to-list round trip.

Deferred minors, controller rulings, and the full fix-round history are in
the ignored SDD ledger. The former Task 12 codec-tightening ruling and Task 14
live-confirmation carry-forwards are recorded there and discharged by the
later closures below. The protected workspace and user-owned `artifacts/`
were untouched; all connected and credentialed evidence came from sole
audited read-only disposables.

## Stage 3 create-only Task 7 checkpoint — 31 July 2026

Subagent-driven execution resumed from `a46f1f5` on `main`. Task 7
(`85e2f57`, `bf9bcb2`) implemented epoch-one ownership resolution and
publication and closed its independent review with zero open Critical or
Important findings after one fix round.

- `OwnershipChainStore` resolves ownership by exact successor IDs only:
  public headers navigate, the root and every successor authenticate after
  content-key unlock, a missing successor is the tip, authenticated and
  public tips must agree, and every duplicate, fork, decoy, epoch overflow,
  or bound violation (64 roots, 1,024 claims, 128 candidates) fails closed
  with no alternate slot.
- `PublicationCatalog` authenticates bounded publication candidates and
  rejects duplicate sequences, forks, gaps, generation regressions,
  competing tips, and claim/device mismatches.
- `RemoteObjectCodec` re-authenticates local Stage 2 objects and
  re-encrypts them under explicit fresh remote logical IDs, so the two
  epoch-one base copies never share identity; staged files are synced,
  cleared, and deleted on failure.
- `DefaultRemoteBackupConfigurator` runs crash-resumable epoch-one setup
  over the plan's exact ten phases: identities are generated once and
  persisted, two independent complete bases are uploaded and verified, the
  sequence-zero baseline binds the planned root and the root binds the
  baseline back, and remote backup activates only after root readback and
  full chain re-resolution. The fix round made every create-and-crash
  window resumable: intended bytes are recorded durably before the first
  network mutation, and an occupied slot is adopted only when its occupant
  authenticates at this connection's persisted identity; foreign occupants
  stay ambiguous or lost, and no alternate slot is ever generated.
- The review upheld the recorded interface deviations (sealed
  Blocked-capable discovery results, `PublicationCandidate`, a `contentKey`
  parameter on `createClaim`, nullable list lineage) as forced by the
  Task 6 list()-failure carry-forward and the frozen Task 2 formats.
- Evidence: focused RED compilation failure first; GREEN
  `:core:data:testDebugUnitTest` 317/317 (60 Task 7 tests plus 5 fix-round
  crash-window and fail-closed guard tests, mutation-verified); repository
  gate 547 tasks and separate release assembly 441 tasks passed at
  `bf9bcb2`; `git diff --check` clean. No emulator, ADB, or connected
  command ran; the protected workspace and user-owned `artifacts/` were
  untouched.

Carry-forwards for the resume session are in the ledger: Task 8 must clear
stale resumable transfer state before re-encoding a planned base and close
the `storeIdentities` orphaned-configuration-row crash window; a user
ruling is requested on whether a malformed listed ownership root may block
root discovery permanently; the deferred-minor list gained the Task 7
review and re-review items.

## Stage 3 create-only Tasks 8–10 checkpoint — 31 July 2026

Subagent-driven execution resumed from `bf9bcb2`/`26d2029` on `main`.

- Task 8 (`5495def`, `1b8feef`, `357ca98`) published immutable successor
  generations: the eleven-phase durable `DefaultRemoteBackupCoordinator`
  (checkpoint only after publication readback and a second ownership
  recheck), bounded `NamespaceSafeRemoteCleanup` (≤32 deletes, tip
  authentication before every batch, blockers retained), both Task 7
  carry-forwards closed, and the review's two Important findings fixed:
  the seven-day hold now applies only to abandoned candidates and
  old-epoch residue, and an unfulfillable frozen plan is discarded only
  when its local source is gone and its slot is provably empty. The
  reviewer disproved the "format forbids bridging" base-pair rationale;
  the same-generation pair stands as recorded policy.
- Task 9 (`6684478`, `eb26cfb`, `4ff32cc`) scheduled unique background
  work: WorkManager runtime with the exact One UI-independent plan
  cadence (15-minute debounce, 24-hour periodic, no unmetered
  requirement), non-interactive authorization that persists
  action-required state, a split `Unavailable` reason enum, a stopped
  flag making stale runners refuse after slot teardown, and completion
  re-arm only for strictly newer generations. Connected evidence:
  `:core:data` 100/100 (first device run of the Task 8 Room
  `adoptConnecting` cases, 20/20) and `:app` 13 tests on the sole
  disposable.
- Task 10 (`41d0a07`) reconstructs verified staging vaults: exhaustive
  per-family import with strict typed record access, fresh-only local
  operational state (schema marker normalised to 7), close/reopen
  canonical-capture verification, and 29 new instrumented tests
  (connected `:core:data` 129/129). Its interrupted independent review
  was re-dispatched and closed in the 1 August session recorded in the
  closure checkpoint below.

The separately approved Galaxy Z Fold 8 trifold-ready adaptive layout
slice was designed and planned in parallel (spec `1de991d`, plan
`c364a60`, user-approved) and is scheduled immediately after the Stage 3
exit gates in the execution order below.

## Stage 3 create-only Tasks 10–11 closure checkpoint — 1 August 2026

Subagent-driven execution resumed from `0cf354c` on `main`.

- The interrupted Task 10 review was re-dispatched over
  `4ff32cc..41d0a07` and returned Needs fixes (0 Critical, 3 Important,
  all in `StagedVaultVerifier`). One fix round (`a7e8ce6`) closed it:
  an unscoped structured-record count makes the verifier blind to no
  row outside the recovered vault, the replayed state is proven a valid
  Stage 2 vault through the full snapshot-codec rule set, the
  post-smoke-check settle step proves any drift is exactly
  retention-eligible trash purge (surfacing `activationGeneration` and
  `retentionPurge` on `VerifiedStagedVault`), and VAULT delete replay
  coverage was restored with a delete-and-reupsert segment — the
  apparent "DELETE for every family" plan conflict dissolved without a
  ruling. Concern verdicts: the recovered-vault capture defect was
  confirmed real and mandated into Task 11; `recoveryEnvelopeReady =
  true` was confirmed correct; the single-vault invariant was upheld.
  Connected `:core:data` closed at 134/134.
- Task 11 (`46e59ee`, `412fafa`, `b2eb2f6`) implemented
  `DefaultRecoveryCoordinator`: bounded discovery with opaque handles,
  terminal-tombstone refusal, wrong-account rejection before lineage
  access, KDF probing before derivation, full chain authentication with
  an `ACTIVE` tip match, unique publication-pair resolution, staging
  reconstruction and verification via Task 10, and a fourteen-phase
  self-contained takeover whose claim verification demands seven
  independent agreements including this device, the exact baseline, and
  no successor publication. Its review closed after two fix rounds:
  failed preparations now abandon staging and release the runtime
  in-process; a takeover always uploads two fresh independently
  identified complete bases, cross-compares the downloaded source pair
  (same-generation disagreement fails closed as ambiguity), declares
  the fresh bases at the generation they cover with retained segments
  bridging, and proves round-trip recoverability through the
  coordinator's own download path; occupied-slot claim branches and the
  dropped-`CLAIM_CREATED` resume window are covered; the mandated
  portable-decode tests exist. Stage 2 capture now works on recovered
  vaults via sole-vault attribution, and a foreign-vault-id payload
  fails closed at `prepare`. Connected `:core:data` closed at 141/141.
- Deferred minors, the amended base-declaration ruling, and the
  Task 12/13/14 carry-forwards are recorded in the ignored SDD ledger.
  Notable Task 12 items: the approved equal-generation codec
  tightening, the recovery-envelope derivation divergence after
  rotation, the settled-state capture-legitimacy decision for
  purge-orphaned parent links, and normalising the raw NUL character
  literal that makes grep treat `DefaultRecoveryCoordinator.kt` as
  binary.

## Stage 3 create-only Task 12 checkpoint — 1 August 2026

Subagent-driven execution resumed from `a14b818` on `main`. Task 12 closed in
four scoped commits: `d42ed14` (lifecycle implementation), `1c5b8cf` and
`ab9e9ce` (two review fix rounds), and `3109108` (test-only projection wait
found by the final device gate).

- `DefaultRecoveryPassphraseChanger` uses the exact seven durable phases. It
  verifies the current passphrase, publishes and reads back the portable and
  immutable replacements, reauthenticates the exact ownership tip immediately
  before promotion, and atomically promotes the SQLCipher envelope last. It
  retains the same content key, writer epoch, local generation, claim, active
  device, and inventory; only publication sequence and recovery-credential
  generation advance. Ordinary publication, rotation, and history deletion
  share one active-vault publication gate.
- Disconnect persists `DORMANT` before cancelling work and performing bounded
  non-interactive token cleanup. It makes zero Drive file list/read/create/
  delete calls, and revocation failure cannot reactivate the lineage.
- Permanent deletion creates and authenticates one exact `TERMINATED`
  successor and never allocates an alternate or deletes the terminal marker.
  The encrypted operation state durably records exact authenticated inventory,
  locally known objects, first-observed residue ages, bounded role/page cursors,
  and deletion progress. Publications, snapshots, and segments are exhausted
  before claims; a 64-page-per-role cap and final empty full rescan fail closed;
  one invocation shares a 32-delete budget; the exact terminal reauthenticates
  every later batch; young unauthenticated residue waits seven days; root is
  deleted last; resume after root loss reads the exact terminal directly.
- Divergent-work preservation is available only from locally recorded
  `OWNERSHIP_LOST`, requires the bound account non-interactively, and calls the
  existing configurator with an explicitly separate lineage. It imports,
  merges, or reactivates no lost-lineage record.
- Task 11 carry-forwards closed at shared roots: equal-generation retained
  publications require a strictly newer recovery credential; recovery stages
  the same envelope selected from the authenticated current publication and
  fails on divergence; permanent and retention purges detach surviving direct
  children in the same generation and journal their after-images; obsolete
  unreleased takeover state remains strictly rejected; the raw NUL literal is
  written as escaped `\u0000`.

The initial independent review found one Critical and four Important issues:
remote-only history could survive terminal deletion, rotation could race the
ordinary publisher, promotion could resume without fresh ownership, payload
and claim cleanup did not share the 32-delete budget, and deleting root first
made terminal resume fail. Fix round 1 closed four and most of the first; the
fresh re-review required durable page caps, direct inventory candidates, and a
final empty rescan. Fix round 2 closed that remainder. The final fresh verdict
is APPROVED with no open Critical, Important, or Minor finding.

Final evidence:

- The first connected `:core:data` run completed 141/142; its sole failure was
  the new test reading an asynchronous workspace projection before its second
  created task appeared, so the purge path had not run. `3109108` changed only
  that fixture to use the file's established bounded observation wait. Its
  focused rerun passed 1/1, then the complete sole-disposable API 37 run passed
  142/142 with zero failures or skips.
- The emulator was `Pixel_10_Pro_Fold`, API 37 / Android 17, 390 dpi, launched
  `-read-only`, `-no-snapshot-load`, `-no-snapshot-save`, `-no-window`, and
  `-no-boot-anim`. Its inherited 2.0 font scale changed only in the disposable
  overlay to 1.0. Snapshot save was ignored at shutdown; final ADB and qemu
  process audits were empty.
- Forced fresh `testDebugUnitTest lintDebug :app:assembleDebug` passed 547/547
  executed tasks. Forced fresh `:app:assembleRelease` passed 441/441 executed
  tasks including R8, resource shrinking, and release packaging. The Room
  schema-drift script passed with database version 7 unchanged. Scoped and
  working `git diff --check` and the added-code hygiene scan were clean.

Task 13 owns the user-facing backup/recovery surfaces and keeps the recorded
manual "back up now" retry affordance distinct. The then-deferred Task 14 live
Account derivation/revoke and R8 reachability evidence is discharged below.
The pre-existing historical Google Drive plan amendment and user-owned
`.kotlin/` and `artifacts/` remain untouched and unstaged.

## Stage 3 create-only Task 13 closure checkpoint — 1 August 2026

Task 13 is complete. Fix round 3 added a distinct `RETRYABLE_PROVIDER`
presentation category shared by discovery, prepare, and confirmation, with
bounded temporary-unavailability copy and no false Sign in guidance. The
restoration check now seeds the real recovery inbox before `MainActivity`
launch, enters the production recovery route, recreates the Activity, and
proves the private passphrase is not restored. No production test hook or new
runtime abstraction was added.

Closure evidence:

- the focused host gate passed 530 tasks and the scoped review found no
  remaining Critical or Important issue;
- the sole read-only API 37 disposable passed app 14 tests with one expected
  credentialed-only skip and feature:more 52/52, including the new transient
  failure and genuine Activity recreation cases;
- forced fresh debug/unit/lint passed 547/547 executed tasks and forced fresh
  release/R8 passed 441/441 executed tasks;
- Room schema drift, working/scoped `git diff --check`, and added-code hygiene
  checks passed; and
- the disposable emulator shut down with snapshots disabled and final ADB and
  emulator-session audits empty.

Task 14 supplied the final evidence below and closes Stage 3.

## Stage 3 create-only Task 14 closure checkpoint — 1 August 2026

Task 14 and Stage 3 are complete in the change with subject
`docs: verify create-only Stage 3 backup`, started from `a325017`. The
authoritative evidence is
`docs/qualification/stage3-google-drive-create-only-backup-recovery.md`.

The deterministic production-protocol end-to-end test uses isolated encrypted
Room and create-only provider contexts to prove epoch-one setup, incremental
publication, resumable process death beyond 5 MiB, staged recovery, two-base
takeover, exact-successor contention, stale-owner exclusion, fallback,
same-generation passphrase rotation, divergent-lineage preservation,
disconnect/reconnect, wrong-account rejection before provider access,
terminal cleanup resumption, tombstone-only final state, and an independent
inert Android package. Canonical workspace bytes match before backup and after
recovery.

That coverage exposed and closed root causes in the logical schema seed,
post-takeover passphrase ownership binding, durable ownership-loss state,
dormant reconnect ordering, and forced complete-baseline capture for recovered
and separate lineages. The debug credentialed harness also now cleans only its
exact marker objects after interruption and has a twenty-minute outer bound;
individual provider calls retain their fifteen-second bound.

Final evidence:

- the credentialed live provider gate passed in approximately six minutes,
  proving ten exact-ID races, thirty rejected loser retries, unchanged winner
  readbacks, exact-ID ambiguity resolution, and cleanup;
- the full sole-disposable API 37 connected gate passed 275 tests with zero
  failures or errors and one intentional credential-only skip;
- the final repository debug/unit/lint gate passed 547 Gradle tasks (20
  executed, 527 up-to-date) and 790 JVM tests in 66 suites; the release gate
  passed 441 tasks including R8, shrinking, optimization, and packaging;
- schema drift and create-only fixture regeneration passed without a diff;
- release inspection found only `drive.appdata`, excluded the debug activity,
  and found no application mutable-authority or client-secret string;
- privacy scans contained only redacted declarations, tests, negative or
  historical evidence, public endpoints, and runtime-library symbols; and
- the protected named snapshot matched package, database/WAL/SHM, visible
  record/project, and active-timer state without install, instrumentation,
  uninstall, clear, restore, backup-manager, or snapshot-save mutation.

The provider gate validates the live create-only coordination primitive. The
full lifecycle is deterministic production-protocol evidence; no second live
account or destructive second physical installation was used, and no broader
live claim is made. No private identifier, credential, or workspace content is
recorded.

That next approved adaptive action is now complete and recorded in the
checkpoint below.

## Galaxy Fold 8 trifold-ready adaptive slice closure — 2 August 2026

The approved adaptive slice is complete in the implementation range
`7276f90..ddbe52a`, including the visual-acceptance corrections `38a84f8`,
`da75a9e`, `9cc6057` and `0368dcf` and the final-review corrections `74d3064`
through `ddbe52a`. Its authoritative record is
`docs/qualification/fold8-adaptive-acceptance.md`.

The slice maps AndroidX window layout information to a model-independent
`WindowPosture`, applies One UI-aligned 42/58 and 38/62 pane fractions, snaps
vertical separating folds to the hinge, excludes editor content from a
horizontal tabletop hinge, and preserves bounded drafts and selection across
fold-driven Activity recreation. Fixture ownership and continuity acceptance
are isolated from the protected workspace.

Task 5 passed a 38/38 primary matrix across the API 37
`Fold8_Acceptance` cover/main displays and `Fold8_Ultra_Acceptance` main
display at 100% and 200% text. The selected `Client research` workbench was
verified rather than an empty Projects state. Acceptance found and closed the
medium-width Schedule routing, large-text project progress and large-text
cover status defects in `38a84f8`, `da75a9e` and `9cc6057` respectively. Review
then found clipped Quick Add actions above the IME; `0368dcf` made the shared
sheet scrollable and added a focused device regression. A further 8/8 focused
matrix passed on the physical cover at a verified 332×532 dp at both text
scales, covering compact navigation, Tasks single-pane behaviour, editor
scrolling and Quick Add with Gboard visible. Both disposable AVDs ran
sequentially with read-only, snapshot-disabled flags, were shut down, and left
empty ADB and emulator-process audits. The protected `Pixel_10_Pro_Fold` was
not started or mutated.

Final review corrected pane snapping for trailing safe insets and odd-width
trifold centres, made continuity-fixture database deletion targets
(WAL/SHM/journal/wipecheck/master journals) and active-slot sidecar ownership
fail closed, strengthened recreation/configuration/scroll checks,
asserted both pane widths within a 1 dp rounding tolerance, and made the task
editor's accessibility state use the current localized draft title. Focused
device reruns passed the continuity fixture's two executable rows (the native
transition row skipped on the DEFAULT-only AVD), Tasks pane tests 2/2 and
Projects pane tests 4/4.

The AVD exposed physical main and cover displays but no native AndroidX
`FoldingFeature`, separating fold or hinge. A single bounded fold/unfold probe
left `cmd device_state` at identifier `0` (`DEFAULT`). Physical-display
evidence therefore validates adaptive size-class surfaces only; Task 3's
instrumentation is the independent evidence for synthetic 50/50 hinge snap,
and no native emulator hinge claim is made.

Samsung Remote Test Lab remains **External-blocked** because the user's
Samsung developer account approval is pending. Real-device cover-to-main draft
continuity, One UI taskbar overlap, Samsung keyboard on both displays,
split-screen one-half and one-third widths, pop-up view, and physical hinge
alignment remain outstanding. No sign-in page was opened and no credential was
requested or handled. This recorded external gap does not reopen the completed
emulator slice, but it must be cleared before real-device or One UI integration
claims.

Pause after this checkpoint. Stage 4 was subsequently requested, designed,
planned, and started; its in-progress state is recorded in the checkpoint
below.

## Stage 4 Tasks 1–3 in-progress checkpoint — 2 August 2026

The user explicitly requested Stage 4. Brainstorming produced the approved
combined design (`7b5af15`) covering first-class notes, immutable activity
history, the create-only cloud attachment blob lifecycle, and search
extension; its one deliberate supersession replaces the 2026-07-28 design's
conditional control-manifest wording with the Stage 3 create-only lineage
model. The fourteen-task execution plan (`6538dca`) was then approved and
subagent-driven execution began directly on `main`. Each task closed with an
independent review and, where needed, scoped re-reviewed fix rounds.

- Task 1 (`1e30b3b`) added `NoteId`, the `Note` record,
  `WorkspaceSnapshot.notes`, the `AddNote`/`UpdateNote`/`DeleteNote`/
  `RestoreNote` commands with exact repository-produced Undo, bounds
  (10,000-char body, 500 per owner), three new rejection reasons, complete
  `InMemoryVaultRepository` behaviour, and fixture notes. Review: approved,
  no fix round. `RoomVaultRepository` carries compile-only stub arms until
  Task 4.
- Task 2 (`bfc51e4`, fix `f4d4451`) shipped Room v8: the `notes` table, the
  attachments rebuild dropping `keepOffline` and adding
  `blobSetId`/`chunkCount`/`deletedAtEpochMillis`/revision columns with
  existing rows preserved, the `attachment_transfer` session table, exported
  `8.json` (drift script clean), the finalised `Attachment` model with
  `BlobSetId`, snapshot `attachments`/`activityEntries` fields, mappers, and
  the v7→v8 preservation migration test. The fix round reshaped two
  instrumented-test attachment seeds that still used the dropped column.
- Task 3 (`a887732`, fix `ab5c3d0`) added the `NOTE` backup family
  end-to-end across the mutation codec, journal session
  (revision-based note and attachment snapshots), payload referential
  validation (exactly-one-owner, owner-present), recovery import with
  ciphertext zeroing, capture DAO, staged-vault verification and
  retention-purge rules, in-memory journal parity, and regenerated
  independent fixtures; it also finalised the ATTACHMENT record semantics.
  The reviewer independently confirmed the pre-Stage 4 attachments table had
  no write path, so the in-place finalisation of the frozen record shape is
  safe: no encoded old-shape instance can exist. The fix round added
  mutation-proven journal-wiring assertion tests. A record carrying
  `keepOffline` now fails strict decode by test.

Deferred minors and rulings live in the ignored execution ledger
`.superpowers/sdd/2026-08-02-stage-4-notes-activity-cloud-attachments-search-plan/progress.md`;
notable entries: Task 4 must replace the Room note stub arms' `INVALID_STATE`
rejection with real persistence, and the plan-mandated `nonNegativeLong`
NOTE-timestamp strictness was adjudicated defensible. No device suite ran;
all new instrumented tests are compile-verified only and run at the Task 14
connected gate.

The pause was honoured; Task 4 subsequently closed in the checkpoint below.

## Stage 4 Task 4 closure checkpoint — 2 August 2026

Subagent-driven execution resumed from `ab5c3d0`/`18fb577` on `main`.
Task 4 (`74563d1`, `bc4a0ce`) persisted notes through Room commands and
closed its independent review as Approved with zero Critical, Important, or
spec findings and no fix round.

- `WorkspaceDao` gained `observeNotes()` (parameterless, matching the
  sibling snapshot observers), `upsertNote`, `deleteNote(id): Int`,
  `deleteNotesForTask`, `deleteNotesForProject`, and the validation helpers
  `getNoteById`/`countNotesForOwner`; `purgeTask` deletes the task's notes
  before the task row.
- The four Room dispatch arms replace the recorded `INVALID_STATE` stubs
  and are line-for-line behaviour-equivalent to the Task 1 in-memory
  handlers — identical branch order, rejection reasons, user messages,
  revision math, and Undo shapes — wrapped in `database.withTransaction { }`
  so the surrounding `execute` journal diff emits NOTE rows without
  hand-written journal code. Write validation reads the DAO directly rather
  than the plan's literal `currentWorkspace()` wording; the reviewer
  confirmed the literal wording would have read stale pre-command state and
  upheld the deviation as the file's universal handler convention.
- A pre-review parity fix (`bc4a0ce`) made the in-memory
  `permanentlyDeleteTask` and the independently divergent
  `purgeExpiredTrash` strip purged tasks' notes with journalled NOTE
  deletes, with two new mutation-asserting unit tests; the reviewer
  verified the Room and in-memory purge paths now match.
- Evidence: RED compile failure first; GREEN
  `:core:data:compileDebugAndroidTestKotlin` plus
  `:core:data:testDebugUnitTest` 432/432. The new
  `RoomNoteCommandInstrumentedTest` (round trip, exactly-one-owner, journal
  evidence, purge) is compile-verified and executes at the Task 14
  connected gate. No emulator, ADB, or connected command ran; the protected
  workspace and user-owned files were untouched.

Deferred minors are in the ignored execution ledger; notable: Room orders
`WorkspaceSnapshot.notes` by creation time and id while in-memory appends
unsorted — sort before Task 6's UI reads it. Tasks 5–14 (activity
generation, search, attachment metadata commands, blob store, intake,
open/share/cache, GC and destructive deletion, runtime/recovery wiring,
product surfaces, and the qualification/exit gates) have not started.

Pause here at the user's request. Resume by re-entering
superpowers:subagent-driven-development with the plan and ledger above,
starting at Task 5 (activity history generation) from base `bc4a0ce`.

## Stage 4 Task 5 closure checkpoint — 2 August 2026

Task 5 (`45335ae`) generates immutable activity history in both repositories
and closed its independent review as Approved with zero Critical, Important,
Minor, or spec findings and no fix round.

- `ActivityKind` replaces the former free-form kind string and the unused
  `immutable` flag. Snapshot mapping skips unknown stored kinds instead of
  crashing, while both repositories now populate
  `WorkspaceSnapshot.activityEntries`.
- The specified task and project command handlers emit activity through one
  repository-local helper. Bodies truncate at 500 characters; each task or
  project retains the newest 500 entries with deterministic
  creation-time-then-ID eviction. Note commands emit no activity, and the
  attachment call sites remain assigned to Task 7.
- Room inserts and prunes activity inside the command transaction, so the
  existing before/after journal diff records both upserts and pruning deletes
  at the command generation. In-memory backup snapshots now include activity
  and permanently purged tasks lose their activity in parity with Room.
- Evidence: the focused in-memory suite failed RED on the missing generation
  behaviour, then passed GREEN. The final
  `:core:data:testDebugUnitTest :core:data:compileDebugAndroidTestKotlin` gate
  passed. `RoomActivityGenerationInstrumentedTest` is compile-verified and
  executes at the Task 14 connected gate; no emulator, ADB, or connected
  command ran.

The Task 4 deferred snapshot-note ordering minor remains assigned to Task 6.
Tasks 6–14 have not started. Resume Task 6 from `45335ae` with the approved
plan and execution ledger.

## Stage 4 Task 6 closure checkpoint — 2 August 2026

Task 6 (`ee28a16`) extends search to note bodies and active attachment display
names and closed its independent review as Approved with zero Critical,
Important, Minor, or spec findings and no fix round.

- Both repositories group notes by task or project and non-tombstoned
  attachment names by task, then append those fields to the existing
  `SearchNormalizer` text bundles. Activity bodies remain excluded; result
  types, filters, ordering, and the 50-result cap are unchanged.
- The Task 4 note-ordering minor was load-bearing for queries that span joined
  note boundaries. Both search implementations now order owner notes by
  creation time and ID before joining, matching Room without changing snapshot
  or UI ordering.
- `SearchExtensionTest` covers task and project notes, live and tombstoned
  attachment names, activity exclusion, diacritic normalisation, and the
  cross-note ordering case. The focused test failed RED on the missing search
  inputs and ordering, then passed GREEN with the full `core:data` unit suite.
  The final `testDebugUnitTest lintDebug :app:assembleDebug` repository gate
  also passed.

Tasks 7–14 have not started. Resume Task 7 from `ee28a16` with the approved
plan and execution ledger.

## Stage 4 Task 7 closure checkpoint — 2 August 2026

Task 7 (`24315cb`, corrected by `cdea044`) adds attachment metadata commands
in both repositories and closed its independent review after one fix round.

- Register validates the active owner task, sanitised bounded display name,
  bounded MIME type, byte and chunk limits, exact 4 MiB chunk arithmetic,
  lowercase SHA-256 text, and the 100-active-attachment task cap. Room now
  observes and seeds attachment rows through the existing v8 DAO and mapper.
- Delete retains the tombstoned row, emits `ATTACHMENT_REMOVED`, and returns
  exact `RestoreAttachment` Undo. Restore clears the tombstone without new
  activity; register emits `ATTACHMENT_ADDED` and deliberately has no Undo.
  Both accepted Room mutations remain inside the existing atomic journal
  transaction, with in-memory parity.
- Review found that reusing an active attachment ID could bypass a full target
  task's cap. The fix treats an ID as a replacement only for the same owner;
  the regression fills the destination to 100 and verifies rejection and
  unchanged ownership.
- The focused attachment suite passed after its recorded RED/GREEN cycles,
  `:core:data:compileDebugAndroidTestKotlin` passed, and the final
  `testDebugUnitTest lintDebug :app:assembleDebug` gate passed with 547
  actionable tasks and no failures. No emulator, ADB, or connected command
  ran.

Tasks 8–14 have not started. Resume Task 8 from `cdea044` with the approved
plan and execution ledger.

## Stage 4 Task 8 closure checkpoint — 2 August 2026

Task 8 (`da5488d`, corrected by `131f6b6`) adds the provider-neutral
`AttachmentBlobStore`, strict authenticated blob-set manifest codec, and
create-only Drive adapter. Its independent review closed after one fix round.

- The manifest codec binds the exact lineage and blob-set identity, uses
  canonical strict JSON, and enforces the 25-chunk, 4 MiB-per-chunk, and
  100 MiB aggregate bounds with exact indexes and byte-count sums.
- The Drive adapter uses only immutable create-by-ID app-data operations,
  exact attachment role/property tags, pre-transport family ceilings,
  bounded reads, opaque pagination, and fail-closed duplicate or malformed
  manifest discovery. Frozen Stage 3 formats and roles remain unchanged.
- A Node `crypto` generator independently produces the committed manifest
  frame fixture; `AttachmentGoldenTest` verifies byte and digest identity.
  Review fix round 1 added malformed-row and long opaque-token regressions.
- The generated fixture was reproduced deterministically, focused attachment
  codec/store/golden tests passed, and the final
  `testDebugUnitTest lintDebug :app:assembleDebug` gate passed with 547
  actionable tasks and no failures. No emulator, ADB, or connected command
  ran.

Tasks 9–14 remain. Continue Task 9 from `131f6b6` with the approved plan and
execution ledger.

## Stage 4 Task 9 closure checkpoint — 2 August 2026

Task 9 (`3bf8ce9`, corrected by `00d7786`) adds the bounded durable
`AttachmentBlobCoordinator`, transfer DAO, intake/resume/expiry state machine,
and hostile-input matrix. Its independent review closed after one fix round.

- Intake persists exact generated object IDs before any create, streams one
  4 MiB plaintext chunk and one ciphertext frame, verifies exact-ID readback,
  creates and verifies the manifest last, then registers metadata only through
  `VaultRepository.execute`.
- Resume adopts only exact authenticated occupied chunks and reconstructs a
  missing aggregate hash one bounded chunk at a time. Registration replay is
  semantically idempotent in both repositories, preventing duplicate records
  or `ATTACHMENT_ADDED` activity across the repository-success/DAO-crash gap.
- Persisted phases and their state are strictly validated. Ownership is
  checked before initial and manifest creates; stale expiry authenticates and
  deletes only a session's exact IDs. Hostile source open/read/close failures,
  lying sizes, readback mismatches, and unsafe names fail inside the sealed
  result contract.
- The focused intake and attachment-command tests plus
  `:core:data:compileDebugAndroidTestKotlin` passed. The final
  `testDebugUnitTest lintDebug :app:assembleDebug` gate passed with 547
  actionable tasks and no failures. No emulator, ADB, or connected command
  ran.

Tasks 10–14 remain. Continue Task 10 from `00d7786` with the approved plan and
execution ledger.

## Stage 4 Task 10 closure checkpoint — 2 August 2026

Task 10 (`90643ff`, corrected by `e627925` and `5d9fc19`) adds the
authenticated attachment open path, bounded ciphertext-frame LRU cache, and
FileProvider share directory. Its independent review closed after two scoped
fix rounds.

- Open authenticates the manifest and each chunk, streams one cleared
  plaintext chunk at a time, and succeeds only after exact byte-count and
  aggregate-hash checks. Missing bytes are unavailable; corrupt bytes fail
  closed.
- The cache stores only verified ciphertext at canonical hashed paths,
  enforces `min(128 MiB, availableBytes / 20)` by oldest access, and sweeps on
  construction. Traversal never follows symlinks, writes require atomic
  replacement, and malformed or unreadable entries are confined cache misses.
- Non-successful streaming may leave partial caller-owned output, so callers
  must discard it; Task 13 owns the share-file lifecycle. The focused 14-test
  cache/open suite and resource processing passed.
- The final `testDebugUnitTest lintDebug :app:assembleDebug` gate passed with
  547 actionable tasks and no failures. No emulator, ADB, or connected command
  ran.

Tasks 11–14 remain. Continue Task 11 from `5d9fc19` with the approved plan and
execution ledger.

## Stage 4 Task 11 closure checkpoint — 2 August 2026

Task 11 (`4639f02`, corrected by `c9f3ff5`, `80c6529`, `c2b3fe2`, and
`73b8922`) adds attachment garbage collection, attachment-only destructive
deletion, and attachment cleanup during terminal vault deletion. Its
independent review closed after four scoped fix rounds.

- Garbage collection uses exact current/previous-generation and 30-day
  eligibility, streams arbitrary finite pages, retains at most 32 candidates,
  deletes chunks before manifests, and reauthenticates ownership. Unknown or
  hostile state blocks deletion.
- Attachment-only deletion requires the passphrase and active remote tip,
  persists lineage-scoped role/cursor progress, preserves metadata and backup
  history, and uses an exact-role chunk probe before manifest deletion.
- Terminal deletion shares the 32-object budget, removes attachment roles
  before claims, preserves opaque pagination tokens, and reauthenticates
  authority after the final chunk probe and immediately before a manifest
  delete.
- Focused attachment and lifecycle tests, both Android-test compile gates, and
  the final `testDebugUnitTest lintDebug :app:assembleDebug` gate passed with
  547 actionable tasks and no failures. No emulator, ADB, or connected command
  ran.

Stage 4 was paused after Task 11 and resumed in the 2–3 August session
recorded in the checkpoint below.

## Historical Stage 4 Tasks 12–14 pre-qualification checkpoint — 3 August 2026

Subagent-driven execution resumed from `2d3ca4f` on `main`.

- Task 12 (`f323457`, fix `2db7e26`) wired the attachment runtime:
  per-vault-slot construction of the store, coordinators, cache, and
  collector in `AppModule` beside the `publicationGate` collaborators;
  lifecycle-gated `AttachmentRuntime` (non-ACTIVE lineages make zero store
  calls, tip mismatch refuses intake/GC); GC only after `Verified`
  publication; session expiry on start; teardown with the slot. Review
  closed after one fix round adding the shared GC eligibility pre-filter
  and the journalled `MarkAttachmentContentCollected` command (both
  repositories, no schema bump) so collected blob sets stop being
  candidates; the destructive-deletion path releases markers via one
  callback. The extended E2E proves the 9 MiB intake → publish → recover →
  open-on-B → stale-A-refused → delete → GC → terminal-tombstone scenario
  and asserts records persist with `blobSetId` cleared.
- Task 13 (`5967c67`, fix `b2604c0`) added the product surfaces: model-free
  `TimelineList`/`NotesTimelineSection` in designsystem; thin
  `NotesActivitySection` mappers in tasks and projects; attachment rows
  with state strings, 48 dp targets, and TalkBack descriptions; the Cloud
  attachments block in Backup & recovery (connection line, combined
  encrypted-frames-plus-staging cache figure, passphrase-guarded content
  deletion); `AttachmentIntakeViewModel` owning pickers, sanitised intake,
  FileProvider share staging, and per-attachment row states. `5967c67` is
  an amend of `c95a825` purging a raw-NUL binary blob from history. Review
  closed after two fix rounds (record-keyed note drafts, staging-cache
  wipe + usage accounting, dedup extraction, neutral unavailable copy,
  record-derived UNAVAILABLE after content deletion, atomic row-state
  updates). Accepted deviations: `onRetry` retries the open (an unfinished
  intake has no record); no in-row intake progress.
- The whole-branch review (`6538dca..b2604c0`, most capable model) found
  one Critical — note-edit Undo was a silent no-op in both repositories —
  plus in-memory purge keeping attachments and a missing startup sweep of
  plaintext share staging. The single fix wave (`f98d1c3`) closed all
  three with executed-round-trip tests; the scoped re-review confirmed no
  new breakage. Two recorded rulings stand as known limitations with
  Stage 5 backlog entries: purged attachments' blob sets are never GC
  candidates (conservative leak; destructive/terminal deletion still
  clears bytes; durable fix needs a schema-backed retired-set index), and
  `AttachmentBlobCoordinator.resume` has no product caller (interrupted
  intakes expire after 24 h; `resume()` and its tests are kept).
- Task 14 is in progress. The extended credentialed live gate PASSED once
  on the sole audited read-only API 37 disposable (bounded PASS in
  606.9 s; live exact-ID chunk create/occupied rejection, byte-identical
  readbacks, single-manifest lookup, exact-ID cleanup; harness committed
  as `a813c41` — the credentialed gate must NOT be re-run). The first
  full six-module connected gate ran 282 tests with 10 failures and the
  one expected credential-only skip. Fix commits so far: `1ba5d0e` (E2E
  task selection), `b5e6a1f` (stale post-Task-5 activity expectations),
  `3648595` (notes-section scroll), `a328695` (collected-attachment
  projection wait), `bf2f95a` (fold-wait retry). The second full
  six-module gate then ran 282 tests with ONE failure and the expected
  credential-only skip (core:data 7→0, feature:projects 1→0, app 2→1).
  The suspected Room activity-pruning parity divergence was disproven:
  Room's `ORDER BY id` snapshot made the test alternate a task into its
  existing status, short-circuiting all 501 commands; Room's 500-cap
  eviction is now genuinely proven. The sole remaining failure is the
  pre-existing, out-of-scope fold-continuity test described in the status
  block. The emulator procedure was followed exactly (read-only, no
  snapshots, audits empty; protected workspace and account untouched).
- Next session, in order: get the user's ruling on the fold-continuity
  failure (second recorded expected skip vs separate fix); then Task 14
  Steps 3–5 with the recorded controller rulings (scoped add only; the
  qualification doc and this file must record the first gate's 10
  failures, every fix commit, the second gate's counts, and the
  throwaway-first-`:app`-run behaviour on a fresh overlay). Then the
  Task 14 task review, ledger closure, and workspace deletion.

## Historical Stage 3 create-only Task 13 in-progress checkpoint — 1 August 2026

Task 13 began from `3109108` and is paused at `bc1c283`, not complete. Its four
commits are `cc2959a` (product surfaces), `6f0cb57` (active replacement route),
`d87e1de` (account binding and runtime-state fixes), and `bc1c283` (failure and
restoration hardening).

Implemented source at this checkpoint:

- More renders stateless Encrypted app backup and Android backup package cards.
  The active card exposes explicit connect, Back up now, passphrase change,
  disconnect, permanent deletion, and ownership-loss recovery actions according
  to lifecycle capability.
- `MainActivity` creates active-only services only for an active vault and uses
  the inert recovery shell for NoVault, Unreadable, Activating, Recovering, and
  active replacement. NoVault retains Start without restoring; active
  replacement does not.
- Recovery authorization carries the known account-binding digest before
  lineage discovery, and every authorized discovery, prepare, and confirmation
  session closes at the bounded operation boundary. Resolution-based
  reauthorisation resumes the shared `RemoteBackupRuntime.requestNow` path.
- Runner in-flight state drives Backing up with the local generation. Recovery
  failures expose bounded localized reasons, Error semantics, Drive retry, and
  the independent Android-package recovery action. Passphrases remain
  `CharArray` at service boundaries, non-saveable, and cleared in `finally`.

Review state:

- The first review found one Critical and six Important issues. Fix round 1
  closed account binding, takeover confirmation, requestNow resumption, token
  lifetime, failure actions, Backing up state, lifecycle capabilities, and most
  interaction coverage.
- Fix round 2 added truthful provider-storage/corrupt categories,
  exception/cancellation session-close tests, production digest/status-flow
  tests, and a restoration fixture. Its fresh re-review approved four of six
  focused findings but left two Important issues: transient `RETRYABLE`
  authorization failures still produce false Sign in guidance, and the
  restoration fixture never launches or recreates `MainActivity` or enters the
  production recovery route.
- Resume fix round 3 with the original implementer. Add a truthful bounded
  transient-provider recovery category/copy aligned across the active spec,
  model, mapping, UI, and tests. Replace the fixture-driven restoration case
  with a genuine Activity/production-route recreation test. Delete redundant
  fixture coverage if the real test subsumes it.

Evidence at the pause:

- Focused Task 13 tests and the app/feature host gate passed; the latest host
  gate completed 530 tasks.
- The final sole-disposable API 37 connected run at `bc1c283` passed 15 app
  tests total (14 pass, one expected credentialed skip) and feature:more 51/51.
  The AVD ran read-only with snapshot load/save disabled, opened posture,
  390 dpi, and a disposable 2.0→1.0 font overlay. Final ADB/qemu audits were
  empty.
- Final repository-wide forced rerun, release/R8, and pause schema gates were
  deliberately not claimed because Task 13 still has two open Important review
  findings. Run them only after fix round 3 is independently approved.
- The historical Google Drive plan amendment and user-owned `.kotlin/` and
  `artifacts/` remain untouched and unstaged.

## Stage 2 final-review correction checkpoint — 30 July 2026

The broad final review of `cc816ab..ccffefb` initially returned **Not Ready**
with three Critical and six Important findings. The single correction wave,
whose source diff began at `ccffefb` and crossed the documentation-only pause
commit `a02242a`, closes all nine:

- routine snapshot and segment work uses only `openExisting()`; guarded
  bootstrap is the sole key-creation authority and fails closed on any durable
  encrypted-backup evidence;
- complete capture includes the full unscoped Inbox workflow only when one
  workspace owns it unambiguously, and snapshot validation requires every
  Inbox semantic status;
- schema-v6 `backup_state` writers mutate the latest row inside a serialized
  Room transaction, merge only owned fields, and preserve package, envelope,
  restored-status, failure, object, and checkpoint truth;
- authenticated local crash residue is reconciled before generic restored
  intake for both durable `PREPARING` and legacy `NOT_PREPARED` boundaries;
- Retry feeds the active single-owner runtime loop without concurrent owners;
- the exact recovery inbox is durable startup truth and blocks publication
  even when status persistence previously crashed;
- transient intake read/open I/O retains eligible bytes as retryable blocking
  work, while deterministic invalid established packages remain withdrawable;
- the 5,000-operation snapshot threshold is decided by a capture-bounded
  count before journal payload rows are materialised; and
- generation advancement atomically changes stale `READY` to
  `UPDATE_PENDING`, with defensive runtime presentation for legacy residue.

Correction commit and verification:

- `f9e091b` — `fix: harden stage 2 backup state transitions` (20 Kotlin
  production/test files; Room remains schema version 6).
- Focused core-data correction tests passed 48/48; focused app backup tests
  passed 56/56. Affected app/data Android-test source compilation passed
  195/195 tasks.
- `./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
  --rerun-tasks` passed with 547/547 tasks executed and 390/390 JVM tests:
  App 102, Crypto 27, Data 165, Domain 44, and Sync 52.
- `./gradlew :app:assembleRelease --stacktrace --rerun-tasks` passed with
  441/441 tasks executed, including R8 and resource shrinking.
- Fixture regeneration produced no diff. Deterministic scans found only the
  intentional v5 `DRIVE_PRIMARY` migration fixtures and the negative
  `Backed up` UI assertion. `git diff --check` passed.
- The first connected attempt exposed test-only dispatcher and JUnit-signature
  defects in the new instrumentation scaffolding. Their focused rerun passed
  15/15 after correction.
- The complete four-module connected gate passed 387/387 executed tasks and
  128/128 tests: Crypto 28, Data 52, App 5, and More 43. XML evidence records
  zero failures, errors, and skips.
- The sole target was the API 37 / Android 17 `Pixel_10_Pro_Fold` AVD launched
  read-only with snapshot load/save disabled, no window, and no boot
  animation. Its inherited disposable font scale was changed from 2.0 to the
  suite baseline 1.0. The shutdown did not save a snapshot; final ADB and
  emulator-process audits were empty.
- A scoped line-by-line review against the correction brief, strict codec and
  package bounds, buffer ownership, migration rules, Android eligibility, and
  forbidden Stage 3 scope found no open Critical or Important issue.

Encrypted Google-account Android transport upload/restore remains external
qualification. No provider, WorkManager, recovery activation, writer
takeover, remote merge, or attachment transport was added. The protected
workspace and user-owned `artifacts/` remained untouched.

## Stage 2 Task 1–10 historical verification checkpoint

This checkpoint records the committed baseline before the final review. It is
historical evidence; the correction checkpoint above is authoritative.

The approved subagent-driven execution ran directly on `main`:

| Task | Result | Commit(s) |
|---:|---|---|
| 1 | Replaced product sync-facing contracts with local backup models, policy, and coordinator boundaries | `b579e9e` |
| 2 | Added additive Room v6 backup journal/state/envelope schema and preserved deterministic v5 legacy rows | `ebab71f`, `66e535f` |
| 3 | Journalled accepted local mutations under one atomic generation; removed active legacy outbox writes | `3a8520c`, `313047e` |
| 4 | Froze strict canonical snapshot/segment payload v1, golden fixtures, and vault-scoped consistent capture | `8f74219`, `f2b3a92`, `6811f3c` |
| 5 | Added crash-safe local recovery-object lifecycle, authenticated readback coordinator, checkpoint, retention, threshold, failure, cancellation, and coalescing behavior | `2a72670` |
| 6 | Prepared, verified, persisted, and failure-hardened the recovery envelope without replacing the existing content key | `99c9905`, `492a84b` |
| 7 | Added the bounded authenticated portable-package codec and crash-safe atomic publisher | `d89c0c1`, `4dea896`, `c9ca105` |
| 8 | Activated runtime coordination, exact Android allow-list, and inert restored-package intake | `92fc531`, `970bcde`, `0c8baf9` |
| 9 | Added adaptive More status/setup UI with masked non-saveable passphrase handling and bounded retry states | `79da8c2`, `26fc840`, `21bf211`, `a4069c7` |
| 10 | Ran the complete exit, Android transport, UI, protected-workspace, and release gates and reconciled active contracts | This checkpoint |

Fresh Stage 2 exit evidence:

- Deterministic source scans found only intentional v5 `DRIVE_PRIMARY`
  migration fixtures and a negative Compose assertion that **Backed up** does
  not appear. The Stage 2 v1 fixture generator produced no diff.
- `./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
  --rerun-tasks` passed with 547/547 tasks executed and 371/371 JVM tests:
  App 87, Crypto 27, Data 161, Domain 44, and Sync 52.
- The sole connected target was the disposable `Pixel_10_Pro_Fold`, API 37 /
  Android 17, opened, 390 dpi, started with `-read-only`,
  `-no-snapshot-save`, `-no-snapshot-load`, and `-no-window`. Its inherited
  2.0 font scale was changed only in the disposable overlay to the suite's
  1.0 baseline.
- The forced connected command passed 387/387 Gradle tasks and 122/122 tests:
  Crypto 28, Data 46, App 5, and More 43. This covers content-key failure
  modes, v5→v6 migration and encrypted restart, packaged eligibility,
  restored input, process recreation, and the complete More suite.
- Normal disposable UI setup produced and verified a 30,479-byte generation-0
  package with file evidence
  `573729:30479:files/android_backup/open_tasks_portable_v1.otb`. The
  passphrase fields were masked. Ready copy reported only local generation,
  bytes, and production time and made no upload claim. Visual acceptance
  passed at 100%, 130%, and 200% text in expanded, half-opened, and
  compact/closed postures.
- Packaged extraction rules and device tests prove that the package path is the
  sole eligible include. A separate ordinary `files/profileInstalled` file
  existed but was not eligible. Room, WAL/SHM, preferences, keys, credentials,
  cache, local staging, and attachment bytes remain excluded.
- Backup Manager was enabled on disposable state. The Google transport
  destination reported **Add a backup account now**. The selected debug-only
  local transport lacked the encryption capability required by the
  cloud-backup rule and rejected the package with full-data error `-1002`; no
  valid app dataset token existed. The uninstall/restore sequence therefore
  did not run. Actual encrypted Google transport upload/restore remains
  external qualification, and no upload or restore success is inferred.
- `./gradlew :app:assembleRelease --stacktrace --rerun-tasks` passed with
  441/441 tasks executed, including R8, resource shrinking, release packaging,
  and `:app:assembleRelease`.
- The named protected snapshot loads correctly only in its recorded hidden-Qt
  graphics posture with `-snapshot`, `-no-snapshot-save`, and
  `-qt-hide-window`. Two earlier renderer-mismatched starts fell back to cold
  state and were stopped before any install or data operation. The accepted
  load completed in 1.193 seconds.
- The protected snapshot's saved baseline is font scale 1.0, not the unsaved
  later 2.0 runtime value previously recorded. Before installation its exact
  identity was UID `10232`, first install `2026-07-28 05:53:59`, CE inode
  `549494`, database/WAL/SHM inodes `567204`/`567205`/`567234`, and sizes
  `4096`/`379072`/`32768`.
- The debug APK installed in place with `adb install -r`; no uninstall, clear
  data, instrumentation, or `bmgr` touched protected state. SQLCipher opened
  without a migration/runtime failure. `Reconcile July invoices`,
  `Finish launch proposal`, `Transcribe research interviews`, their projects,
  and the active timer remained visible across force-stop/cold relaunch.
  Package identity and all inodes stayed exact; the migrated WAL grew to
  `436752`. More showed generation-zero v6 initial backup state. Deterministic
  migration coverage plus this protected continuity prove legacy-row
  preservation without unsafe direct SQL inspection of the encrypted database.
- Reloading the named snapshot restored the exact original `TEST_ONLY`,
  no-`ALLOW_BACKUP` package flags, 379,072-byte WAL, package identity, and
  CE/database/WAL/SHM inodes. Both shutdowns ignored snapshot save. Final ADB
  and emulator process state were empty.
- Independent Task 1–9 reviews have no open Critical or Important findings.
  Deferred Minors remain in the ignored SDD ledger and do not reopen these
  completed boundaries.

## Train 0 baseline checkpoint verification

Train 0 Task 0.2 completed on 27 July 2026 against the API 37
`Pixel_10_Pro_Fold` AVD (Android 17, emulator 36.6.11). The debug APK installed
in place and the original workspace survived the cold process restart. The
first combined connected-test attempt exposed an AGP cleanup collision:
`:app:connectedDebugAndroidTest` targets and uninstalls `app.opentasks`.
The pre-run `default_boot` snapshot restored the exact original package and
data identity (UID `10331`, first install `10:08:16`, CE inode `573462`,
database/WAL/SHM inodes `573474`/`573476`/`573478`). That verified recovery was
saved as `train0_task02_recovered`.

The checked-in delivery plan now isolates the application suite on a sole
ADB-connected read-only emulator started with `-read-only`,
`-no-snapshot-save`, and `-no-snapshot-load`. It passed 3/3 there. The normal
emulator was then restored from `train0_task02_recovered`; packaged manifests
confirmed that Data, Tasks, Projects, Schedule, and More target isolated module
packages rather than `app.opentasks`. Their rerun passed 54/54:
Data 22, Tasks 18, Projects 9, Schedule 2, and More 3.

The first 54-test run found one transient recurrence-test failure: the task
table emission could arrive before the copied tag/checklist relation emission,
although the relations were written in the same Room transaction. The failure
did not reproduce in two isolated reruns. The test now waits for the complete
relation state before asserting; its isolated rerun and the full 54-test suite
both pass.

Post-suite force-stop/relaunch and ADB UI inspection passed Home, Tasks,
Projects, Schedule, More, Quick Add, the project `Save as template` journey,
and the manual `Add time entry` journey. Both sheets were cancelled without
creating records. The existing projects, overdue tasks, and running
`Finish launch proposal` timer remained visible, and the package/database
identity above was unchanged after the module suites.

## Status vocabulary

| Status | Meaning |
|---|---|
| Done | Implemented and verified for its current scope |
| Ready | Can be started without another project task |
| Paused | Active work deliberately stopped at the user's request; resume from the recorded checkpoint |
| Blocked | Cannot be completed until the named dependency or external input exists |
| Deferred | Deliberately ordered after higher-priority work |
| External | Requires an account, policy decision, physical-device session or store operation |

## Completed product foundation

### Application and adaptive UI

- Single-activity Kotlin/Compose app with Navigation 3, Hilt and Material 3
  Adaptive.
- Five destinations: Home, Tasks, Projects, Schedule and More.
- Compact navigation bar and medium/expanded navigation rail.
- One-pane and list/detail task and project workbenches selected by
  `WorkspaceLayoutPolicy`, including separating-fold handling.
- Responsive task editor now uses the available detail-pane width rather than
  the whole device width. Narrow panes stack Planning content, and option
  groups wrap instead of being clipped.
- Navigation labels stay readable at 100% and 130% text and deliberately
  collapse before wrapping at 150% and 200%.
- Final Galaxy Fold 8 visual acceptance passed a 38/38 primary matrix on API
  37 Fold 8 cover/main and Fold 8 Ultra main displays plus an 8/8 focused
  332×532 dp cover matrix, all at 100% and 200% text. Physical-display evidence
  validates the adaptive surfaces; Task 3 synthetic instrumentation validates
  50/50 hinge snapping because the AVD reported no native folding feature. The
  strengthened native transition row skipped because the AVD exposed only
  DEFAULT, so no native recreation/continuity claim is made.
  Samsung RTL is External-blocked pending account approval, so no real-device
  or One UI integration claim is made.

### Local workspace

- Encrypted task CRUD, core-field editing, debounced auto-save and exact Undo.
- Granular checklist and reusable tag editing with relation-safe Undo.
- Independent first-class workflows for every project and Inbox, including
  add, rename, explicit reorder, archive and restore; semantic completion
  behaviour and blocked-completion acknowledgement remain repository rules.
- Thirty-day Bin, restore, startup expiry, permanent delete and sync
  tombstones.
- Adaptive Project Workbench with create, edit, archive, restore, progress,
  workflow counts, milestone lifecycle editing and deep links.
- Project milestones support create, rename, optional due dates, complete,
  reopen and confirmed delete. Deleting clears assigned task memberships in
  the same transaction; Undo restores the milestone and captured memberships.
- Task milestone membership is limited to the selected project's milestones.
  Project moves clear membership; exact Undo restores the prior project,
  workflow status and milestone ID.
- Task prerequisites can be searched, added and removed in a bounded editor.
  Links survive prerequisite completion, but only unfinished linked tasks
  contribute to `blockedBy`; reopening a prerequisite blocks dependants again.
  Self-links, transitive cycles and more than 100 prerequisites are rejected.
- Active projects can be saved as reusable templates containing their active
  workflow, open milestones and open task structure. Using a template shifts
  local/zoned dates from a chosen anchor, resets progress, remaps parent,
  milestone, tag, checklist and dependency relationships, and creates all
  records plus backup-journal entries atomically.
- Blocked completion is enforced by the repository and confirmed consistently
  from every implemented completion path. The confirmation names unfinished
  prerequisites, while reminder notifications omit unsafe Complete actions.
- Universal search across the implemented local records.
- Compact Schedule shows a navigable selected-day agenda; expanded Schedule
  groups the containing Monday–Sunday week by actual local task dates and
  exposes an open-only unscheduled tray. Both views retain project, blocking,
  completion and reminder context and open the existing task editor.
- First-class timer and manual time history. Completed entries support add,
  edit and delete with exact Undo, a 500-character note bound and a 10,000-row
  per-task cap. Running entries remain timer-owned and read-only. A deterministic
  linear interval sweep exposes overlaps without deleting, truncating or
  silently merging recorded work.
- Process recreation preserves the current destination, selected task/project,
  task filter, list/editor scroll, search and quick-add input, and task/project
  drafts. Restored drafts win over the first repository emission; timer
  continuity remains a durable Room concern.
- One-time sample workspace seed followed by Room as the sole local authority.

### Recurring tasks

- Daily, weekly, monthly and yearly frequencies.
- Intervals, multiple weekly weekdays, count limits and end dates.
- Stable series ID, original wall-clock anchor and occurrence index.
- DST-safe wall-clock scheduling, non-drifting month-end scheduling and
  deterministic occurrence IDs.
- Completion and next-occurrence creation are one Room transaction with
  separate backup-journal entries.
- Repeated completion or redelivery creates exactly one next occurrence.
- Completion Undo reopens the original and removes only the generated
  occurrence.
- Editing a generated occurrence and undoing that edit restores its exact
  recurrence rule, due time, series ID, anchor and occurrence index.
- Room v1→v2, v2→v3, v3→v4, v4→v5 and v5→v6 migrations are non-destructive and
  preserve encrypted data; v3 creates project/Inbox workflows and remaps
  existing task statuses, v4 adds milestone revisions and v5 adds template
  revisions; v6 adds local backup journal/state/envelope tables and preserves
  every legacy outbox row.
- On-device Compose coverage exercises every cadence, interval editing,
  multiple weekdays, count ending, 200% text, 48 dp targets and keyboard
  activation.

### Reminders and notifications

- One persisted due-relative reminder per task with deterministic identity.
- Task and reminder editor changes, Undo and independent backup-journal entries
  are committed atomically.
- Flexible delivery uses an idle-safe inexact alarm. Precise delivery uses an
  exact alarm only while Android special access is granted and falls back
  safely if access is absent or revoked.
- Notification permission is requested only when the user selects a reminder;
  disabled app/channel state and precise-timing fallback are explained inline.
- Alarm payloads contain opaque IDs. The notification channel is private on
  the lock screen and supplies generic public content.
- Notification taps open the task. Snooze schedules 15 minutes later and
  Complete executes through the repository; blocked tasks omit Complete.
- Reboot, package replacement, time/time-zone changes and exact-access changes
  reconcile future alarms.
- Recurring occurrences inherit reminder lead time and precision. Completion
  Undo and permanent task purge queue reminder deletions with their task
  operations.

### Data, cryptography and sync foundations

- Every write is a typed `DomainCommand` executed by `VaultRepository`.
- Room writes and ordered backup-journal entries are atomic; the legacy outbox
  is read-only.
- SQLCipher database with a random 256-bit key wrapped by a non-exportable,
  unlocked-device-required Android Keystore AES-GCM key.
- Tink AES-256-GCM vault-content keys are independently generated from the
  SQLCipher key. The same content key is wrapped separately by an Argon2id
  recovery envelope and a per-vault Android Keystore key for local use.
- Existing database and vault-content envelopes fail closed if their Keystore
  key is lost, replaced or invalidated; a new key is never silently
  substituted. Failed preference or alias operations restore the prior
  envelope where possible and preserve the original failure.
- Canonical v1 cloud frames use a fixed-order strict UTF-8 JSON header and raw
  ciphertext. Decoding validates the 16 KiB header cap, per-family ciphertext
  bounds, exact frame length, versions, attachment chunk tuple and SHA-256
  checksum before exposing a one-shot owned ciphertext buffer.
- The ciphertext limits are 1 MiB for manifests, 64 MiB for snapshots, 16 MiB
  for operation segments and 4 MiB plaintext plus the fixed crypto-v1 overhead
  for each of at most 26 attachment chunks. Payload-model caps are 10,000
  manifest inventory entries, 100,000 snapshot records and 10,000 operations
  per segment.
- Golden vectors cover Argon2id output and associated-data encoding.
- Tests cover wrong passphrase, weakened KDF metadata, ciphertext/envelope
  tamper, associated-data swapping, passphrase change, key zeroisation and
  second-device decrypt.
- Hybrid logical clock and deterministic scalar, set and tombstone merge
  primitives cover clock rollback, redelivery, idempotence and arrival order.
- Repository shutdown now cancels and joins the Room observation job before
  its owner closes SQLCipher. This fixes a real connection-pool race found by
  the restart suite.
- Template payload v1 is self-contained and bounded to 2 MiB. Its strict
  decoder validates metadata binding, sizes, counts, workflow semantics, zones,
  relative dates and acyclic relationship graphs before use. SQLCipher protects
  the local rows and outbox; damaged template rows can still be deleted safely.
- Time-entry add, update, delete and restore use the same typed-command and
  exact-Undo contract in both repositories. Room commits each record change and
  its ordered backup-journal entry atomically; the legacy outbox remains
  read-only migration input.
  Workspace snapshots derive the running timer, full history and representative
  overlap conflicts from the same persisted stream.

### Security and maintenance

- Threat model, asset inventory, trust boundaries, dependency review,
  residual risks and release gates are in
  [docs/threat-model.md](docs/threat-model.md).
- Architecture lifecycle and security references are aligned in
  [docs/architecture.md](docs/architecture.md).
- Android backup and device-transfer rules exclude vault data and keys.
- Current source scan found no application logging calls or committed secrets.
- Dependabot is configured weekly for Gradle and GitHub Actions.
- Tracked-history audit found no credential, private-key, OAuth-client or
  provider-token patterns in the tracked tree or its existing history.
- `.gitignore` excludes local properties, environment files, signing keys,
  OAuth/service-account files, local vault databases/exports and generated
  release artefacts.
- 30 July 2026 maintenance resolved the eight queued Dependabot updates:
  Gradle 9.6.1; Compose BOM 2026.06.01; Hilt 2.60.1; Kotlin serialization
  1.11.0; AndroidX Window 1.5.1; and SHA-pinned `checkout` v7,
  `setup-java` v5 and `setup-android` v4. `testDebugUnitTest`, `lintDebug`,
  debug and release assembly, the workflow structural verifier, and the full
  API 37 disposable device matrix passed. The first device run failed only
  because the disposable Fold had booted closed at 2.0x font scale; the same
  failures reproduced on the pre-update baseline. Re-running opened at 1.0x
  passed in 8m16s.
- A useful repository rollback point now exists:
  `806090a Establish Open Tasks baseline`.

## Work completed in this P0 pass

| ID | Result | Evidence |
|---|---|---|
| P0-01 | Established a real baseline commit after auditing ignored files and scanning for secrets | Commit `806090a` |
| P0-02 | Re-audited the codebase and reconciled the stale recurrence handoff with the implementation | This handoff, architecture and threat-model updates |
| P0-03 | Completed the recurrence rule matrix and edge-case acceptance | Unit tests for all cadences, intervals, weekdays, count/end date, month-end and DST; nine Tasks device tests |
| P0-04 | Hardened duplicate completion, restart/redelivery and exact Undo | In-memory and encrypted Room regression tests |
| P0-05 | Added current-slice accessibility acceptance | 200% text, 48 dp/click semantics and keyboard focus/Enter activation on-device; narrow/fold visual checks |
| P0-06 | Added API 36/37 instrumented CI jobs, repaired to explicit compact/expanded runner profiles and a separate release gate | `.github/workflows/android.yml`; `scripts/verify-actions-workflow.sh`; YAML parsed locally |
| P0-07 | Completed threat and direct-dependency review | `docs/threat-model.md`; weekly Dependabot configuration |
| P0-08 | Added crypto golden, tamper, wrong-passphrase and key-loss coverage | Core crypto unit suite and Android Keystore device suite |
| P0-09 | Rehearsed every migration released at the P0 gate | The then-current v1→v2 encrypted migration device test passed; later P1 migrations are recorded below |
| P0-10 | Strengthened multi-device foundations | Second-device recovery test plus merge/HLC rollback, retry and order tests |
| P0-11 | Fixed repository teardown ordering | All 12 encrypted Room/Keystore device tests pass without connection-pool crashes |
| P0-12 | Verified R8/resource shrinking and installed final debug build in place | Release assembly passes; app data retained after cold restart |
| P0-13 | Published the audited history to GitHub | `main` tracks the now-private `origin/main`; GitHub secret scanning and push protection enabled |

## Work completed in this P1 pass

| ID | Result | Evidence |
|---|---|---|
| P1-L01 | Added persisted reminders, notification actions, in-context permission timing and exact-alarm fallback | Repository/JVM tests, encrypted Room restart/recurrence tests, Compose device test, app scheduling policy tests, architecture/design/threat-model updates |
| P1-L02 | Added independent project/Inbox workflows with add, rename, explicit reorder, archive/restore and semantic-preserving task moves | Repository/JVM tests, encrypted Room restart and v2→v3 migration tests, Tasks/Projects Compose device tests, in-place migration and visual QA, architecture/design/threat-model updates |
| P1-L03 | Added milestone create/edit/complete/reopen/delete, exact membership Undo and project-scoped task assignment | Repository/JVM tests, encrypted Room restart/outbox and v3→v4 migration tests, Tasks/Projects Compose device tests, architecture/design/threat-model updates |
| P1-L04 | Added searchable task prerequisites, cycle/limit rejection, dynamic unblock/reblock semantics and named blocked-completion warnings | Repository/JVM tests, encrypted Room restart/outbox tests, Tasks Compose device tests, full neighbouring-screen regression suite, live Fold visual QA, architecture/design/threat-model updates |
| P1-L06 | Replaced the static Schedule mock with a real compact day agenda, expanded date-grouped week timeline and open-only unscheduled tray | Schedule Compose device tests, reminder/status context checks, compact/unfolded Fold visual QA and 200% text acceptance, architecture/design updates |
| P1-L07 | Completed process restoration for navigation, selection, filters, list/editor scroll, search/quick-add and task/project drafts; bounded every saveable text input and kept running timers in encrypted Room state | Saved-state unit and Compose device tests, encrypted Room restart/timer test, keyboard-submit length guard, architecture/design/threat-model updates |

## Work completed in this P2 pass

| ID | Result | Evidence |
|---|---|---|
| P2-F01 | Added reusable project templates with active workflows, open milestones, open task structure, relative zoned dates, deterministic relation remapping, exact capture/delete Undo and atomic instantiation/outbox writes | Domain/codec/repository JVM tests; 21 encrypted Room/migration device tests; 9 Projects and 3 More Compose device tests; live unfolded Fold visual QA; schema v5; architecture/design/threat-model updates |
| P2-F03 | Added task-scoped manual time-entry add/edit/delete, optional notes, exact Undo, encrypted persistence and explicit timer/manual overlap reconciliation without silent data loss | Domain and repository JVM tests; 22 encrypted Room/Keystore/migration device tests; 18 Tasks Compose device tests; live unfolded Fold editor visual QA; release/lint verification; architecture/design/product/threat-model updates |
| P2-I18N | Fixed the application to UK English and aligned visible terminology/date handling | `en-GB` per-app locale, UK formatters, Organisation/Bin copy, live installed-app verification and product/design documentation |

## P0 verification record

The final source state passed:

```bash
./gradlew :app:assembleRelease --stacktrace
./gradlew testDebugUnitTest lintDebug --stacktrace
./gradlew :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest --stacktrace
./gradlew :app:installDebug
```

Results:

- All debug unit tests passed.
- Lint passed with zero errors and 20 non-blocking warnings: version update
  notices, one obsolete `mipmap-anydpi-v26` folder and one existing modifier
  parameter-order warning.
- R8 minification, resource shrinking and release APK assembly passed.
- All 28 device tests passed on the API 37 Pixel 10 Pro Fold emulator:
  12 data/Room/Keystore, 9 Tasks, 5 Projects and 2 More.
- Debug APK installed over the existing app; no uninstall or data clear was
  performed.
- Cold restart succeeded and the UI hierarchy confirmed persisted workspace
  content, including `Persistence check edited`.

Operational note: do not combine `lintDebug` and `assembleRelease` in the same
parallel Gradle invocation. AGP 9.3.1 lint can race KSP while release Hilt
sources are replaced, producing a transient missing
`Hilt_MainActivity.java`. Running release assembly and unit/lint as the two
phases above is stable. This is a tooling race, not a lint finding.

## P1 verification record

The combined P1-L01 through P1-L04 plus P1-L06 and P1-L07 local source state
passed:

```bash
./gradlew testDebugUnitTest lintDebug --stacktrace
./gradlew :app:connectedDebugAndroidTest \
  :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest --stacktrace
./gradlew :app:assembleRelease --stacktrace
./gradlew :app:installDebug
```

Results:

- All debug unit tests passed.
- Lint passed with zero errors. The app report contains 20 non-blocking
  version/folder warnings; Projects and Tasks each retain one existing
  modifier-order warning.
- R8 minification, resource shrinking and release APK assembly passed.
- All 50 affected device tests passed on the API 37 Pixel 10 Pro Fold
  emulator: 3 app restoration, 19 data/Room/migration, 16 Tasks, 8 Projects,
  2 More and 2 Schedule.
- Debug APK installed over the existing workspace without uninstall or data
  clear. Room v2→v3 and v3→v4 were both exercised in place across the P1
  slices; persisted projects and tasks remained visible, project workflow
  counts resolved to their project-scoped statuses and milestone records
  gained revision metadata without data loss.
- Visual QA passed for Home, Tasks, the reminder controls, Project Workbench
  and the project workflow editor on the unfolded display. The editor exposes
  readable category/task context, 48 dp save/up/down/archive actions and a
  scrollable add/restore path without clipping.
- Live milestone visual QA passed on the unfolded API 37 Fold display. The
  create editor renders as a bounded, readable bottom sheet and the task
  editor exposes only `No milestone` plus open milestones from its selected
  project, without clipping or cross-project options.
- Milestone verification covers v3→v4 migration, encrypted restart, atomic
  milestone/task outbox writes, create/edit/complete/reopen/delete callbacks,
  project-filtered assignment and exact delete/project-move Undo.
- Dependency verification covers encrypted restart, atomic task outbox
  payloads, add/remove Undo, completion resolution, reopen reblocking,
  self/transitive-cycle rejection and the 100-link cap.
- Live dependency visual QA passed on the unfolded API 37 Fold display. The
  searchable sheet is bounded and readable, exposes completion/project
  context and a `0/100` count, and the blocked-completion confirmation names
  the unfinished prerequisite without clipping.
- Schedule visual QA passed on both Fold displays. The compact day agenda
  remains usable at normal and 200% text, while the unfolded view exposes real
  Monday–Sunday columns beside the unscheduled tray with no hard-coded dates.
- Process-restoration acceptance covers serializable navigation, selected
  record IDs, filters, task-list and task/project editor scroll, quick-add,
  search re-query, unsaved editor drafts and active-timer elapsed continuity
  across an encrypted database restart.
- All saveable user text is bounded before it enters Android saved-instance
  state. Domain editors retain one over-limit character so validation remains
  visible; search queries are capped outright. Quick Add's keyboard action now
  enforces the same title limit as its button.
- The final restoration hardening rerun passed all 27 affected device tests:
  3 app restoration, 16 Tasks and 8 Projects.
- Source checks found no application logging calls, credential patterns or
  whitespace errors.

## P2 verification record

The P2-F01, P2-F03 and UK-English source state passed:

```bash
./gradlew testDebugUnitTest lintDebug --stacktrace
./gradlew :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest --stacktrace
./gradlew :app:assembleRelease --stacktrace
./gradlew :app:installDebug
```

Results:

- All debug unit tests and lint checks passed with zero errors.
- R8 minification, resource shrinking and release APK assembly passed.
- The latest P2-F03 affected rerun passed all 40 device tests on the API 37
  Pixel 10 Pro Fold: 22 encrypted Room/Keystore/migration tests and 18 Tasks
  Compose tests. The preceding P2-F01 acceptance also passed 9 Projects and
  3 More tests.
- Template coverage includes relative date/DST-safe wall-clock shifting,
  start-only recurrence anchors, deterministic child IDs, relation remapping,
  progress reset, exclusion rules, payload round-trip, oversize/metadata/cycle
  rejection, encrypted restart, v4→v5 migration, atomic outbox writes and
  capture/delete Undo.
- Live unfolded-Fold visual QA passed for Home, More, the empty Templates
  library, the Project Workbench template action and the capture sheet. It
  exposed and fixed singular count copy before the final build.
- Time-entry acceptance covers strict positive intervals, bounded optional
  notes, the per-task entry cap, encrypted restart, add/update/delete outbox
  writes, exact add/edit/delete Undo and deterministic linear overlap
  reconciliation. Running timer rows remain read-only until stopped.
- Tasks Compose acceptance covers task-scoped history, UK 24-hour ranges,
  date/start/duration/note editing, add and delete callbacks, explicit overlap
  warning/review and 48 dp actions. Live unfolded-Fold visual QA confirmed the
  editor sheet remains bounded, readable and unclipped.
- The final debug APK was installed in place without clearing the existing
  encrypted workspace. The observed UI used `Monday, 27 July`, Bin and other
  UK-English terminology.
- Source scans and `git diff --check` found no new user-visible US-English
  spellings, default-locale date formatters or whitespace errors.

## Train 1 Tasks 1.3–1.5 completion and protected baseline

Train 1 is being executed from
[the checked-in Train 1 plan](docs/superpowers/plans/2026-07-27-train-1-insights-cloud-format-plan.md)
with the approved subagent-driven workflow directly on `main`.

Task 1.3 completed through these independently reviewed commits:

- `cd4de59` — `feat: add pure workspace insights engine`
- `0ae7de2` — `fix: preserve historical insights selection`
- `29c7fb8` — `feat: complete qualified insights metrics`
- `f677fb7` — `fix: qualify insights display totals`
- `2b4df62` — `feat: add accessible workspace insights`
- `aeebbc48` — `fix: preserve live insights qualifications`
- `3bd1f4c0` — `fix: respect insights lifecycle boundaries`
- `f39af40e` — `fix: defer background insights projection`

The correction rounds made restored selections type-safe, refreshed time and
zone qualifications only while foregrounded, preserved restored internal
navigation, exposed every qualified metric, rendered positive sub-minute time
visibly, and kept 200% text in a readable stacked layout. Background workspace
emissions now defer analytics projection until foreground re-entry, and the
boundary scheduler remains tracked across clock jumps.

Final Task 1.3 evidence:

- App JVM: 25/25 passed after the final lifecycle correction;
- More device suite: 24/24 passed after the navigation correction;
- App device suite: 3/3 passed on a sole disposable API 37 emulator;
- debug assembly passed after the final source changes;
- four compact, light, 200%-text Insights captures passed direct inspection,
  including overdue metadata and complete milestone due/health/progress;
- three independent correction re-reviews closed with zero open findings.

Light mode is the required application acceptance colour scheme. Existing dark
theme support remains best-effort and is not a release gate.

Task 1.4 completed through three independently reviewed commits:

- `e2b2dfa` — `feat: add independently wrapped vault content keys`
- `15f15f7` — `fix: harden local vault key isolation`
- `376d35e` — `fix: preserve vault key delete failures`

The final Task 1.4 boundary passed 19/19 crypto JVM tests, 22/22 Android
Keystore tests on a sole disposable API 37 emulator and a forced 257/257-task
crypto-plus-app debug build. Two correction rounds closed failure-atomic
preference rollback, exact UTF-16 vault identity, fail-closed alias loss and
replacement, delete rollback and primary-exception preservation. The protected
workspace was then restored and its complete identity re-verified.

Task 1.5 completed through three independently reviewed commits:

- `7f779e3` — `feat: define bounded encrypted cloud object format`
- `10a2390` — `fix: preserve bounded cloud identity`
- `ce8f5bf` — `fix: isolate verified ciphertext ownership`

The final Task 1.5 boundary passed 28/28 focused cloud-format tests, 39/39
`core:sync` JVM tests, a forced 257/257-task sync-plus-app debug build, release
assembly and a byte-for-byte audit of all four v1 fixtures. Two correction
rounds closed lossy malformed-surrogate identity encoding, full-size buffer
copy amplification, circular fixture expectations and a hostile-stream alias
that could otherwise mutate ciphertext after checksum verification. For
ciphertext reads, the final decoder passes only an 8 KiB-bounded scratch buffer
to the source and retains a separate verified ciphertext array for one-shot
ownership transfer.

At the historical Task 1.5 checkpoint, the repository-wide command
`./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace` was
blocked by five pre-existing `UnrememberedMutableState` findings in
`feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt`
at lines 220, 277, 306, 347 and 572. Task 1.5 did not change that file. Its
final pause audit reproduced only that blocker: the command exited 1 at
`:feature:more:lintDebug` after 12 seconds with 469 actionable tasks (27
executed, 2 from cache and 440 up-to-date). Stage 1 Task 2 subsequently
resolved all five findings, and Task 7 reran the complete repository gate
successfully, as recorded in the Stage 1 checkpoint below.

The following block is historical replacement-baseline evidence from before
the Stage 1 exit restoration; it is not the current protected-state record.
The Android SDK update and Android Studio run coincided with loss of the old
snapshot identity, although causation was unproven. The user authorised the
fresh installation as the replacement protected baseline. The untouched
pre-replacement AVD clone remains at
`/private/tmp/open-tasks-avd-recovery.m7hw3u`. The verified replacement snapshot
was `task13_fixround1_replacement_20260728_055359`, and the emulator used at
that checkpoint was started from it with `-no-snapshot-save`.

Historical replacement protected identity:

```text
Pixel_10_Pro_Fold, API 37 / Android 17
device state: 2 (opened)
font scale: 1.0
night mode: no (light)
package UID: 10232
firstInstallTime: 2026-07-28 05:53:59
CE directory inode: 549494
open_tasks.db inode: 567204
open_tasks.db-wal inode: 567205
open_tasks.db-shm inode: 567234
```

At that checkpoint, the safety procedure prohibited
`:app:connectedDebugAndroidTest` on the protected emulator because AGP
uninstalls `app.opentasks`; connected suites used a sole disposable emulator
started read-only with no snapshot load/save.

The Task 1.4 plan correction for `core/crypto/build.gradle.kts` was applied:
its instrumentation suite has the Android test runner and AndroidX
core/JUnit/runner/rules dependencies it requires.

At that historical pause audit, `adb devices -l` reported no attached device.
The named replacement snapshot and the untouched pre-replacement recovery
clone were both still present. A later Stage 1 runtime session observed font
scale 2.0, but snapshot saving was disabled. The Stage 2 exit audit has now
proved that the named snapshot's saved baseline remains 1.0.

## Stage 1 authenticated object foundation checkpoint

Stage 1's original source foundation completed at `377c5c3`; final review added
the implementation correction `21c33bc`. Its reviewed commit chain is:

- Task 1: `29bd550` — `docs: reset programme to local data authority`;
  correction `4c97929` — `docs: make stage 1 task 2 next`.
- Task 2: `9822e03` — `test: remember Insights composition state`.
- Task 3: `6449940` — `feat: type cloud frame identity failures`; correction
  `a4fffed` — `fix: classify cloud frame length overflow`.
- Task 4: `e9388fb` — `feat: expose associated-data AEAD boundary`.
- Task 5: `53d63fb` — `feat: add authenticated cloud object codec`.
- Task 6: `377c5c3` — `test: freeze authenticated cloud object vectors`.
- Final review: `21c33bc` — `fix: clear rejected cloud ciphertext buffers`.

The original Stage 1 exit host gates passed on 28 July 2026:

```bash
./gradlew :core:sync:testDebugUnitTest \
  :core:crypto:testDebugUnitTest \
  :core:data:testDebugUnitTest \
  :feature:more:lintDebug \
  --stacktrace
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
./gradlew :app:assembleRelease --stacktrace
```

The exact focused command exited 0 in 772 ms (154 actionable tasks: 1
executed, 1 from cache and 152 up-to-date). Its forced fresh confirmation with
`--rerun-tasks` exited 0 in 14 seconds with 154/154 tasks executed and 125/125
JVM tests passing: Sync 49, Crypto 22 and Data 54. More lint reported no errors
and no `UnrememberedMutableState` finding; its 11 non-blocking findings are 10
warnings and one hint.

The exact repository debug gate exited 0 in 17 seconds (547 actionable tasks:
62 executed and 485 up-to-date). Its forced fresh confirmation with
`--rerun-tasks` exited 0 in 25 seconds with 547/547 tasks executed and 186/186
JVM tests passing: App 25, Crypto 22, Data 54, Domain 36 and Sync 49. Lint and
`:app:assembleDebug` completed.

The exact release gate exited 0 in 47 seconds (441 actionable tasks: 49
executed, 4 from cache and 388 up-to-date). Its forced fresh confirmation with
`--rerun-tasks` exited 0 in 54 seconds with 441/441 tasks executed, including
`:app:minifyReleaseWithR8`, resource conversion/shrinking and
`:app:assembleRelease`.

The required ADB audit first found one attached `emulator-5554`. Read-only
inspection identified the protected `Pixel_10_Pro_Fold` AVD, API 37 / Android
17, because its process had none of the disposable flags. Its package identity
matched the protected record: UID `10232`, first install
`2026-07-28 05:53:59`, CE inode `549494`, and database/WAL/SHM inodes
`567204`/`567205`/`567234`. No instrumentation ran against it.

The protected instance was stopped and ADB was verified empty. The same AVD
was then started as the sole disposable target with:

```bash
/Users/kk/Library/Android/sdk/emulator/emulator \
  -avd Pixel_10_Pro_Fold \
  -read-only -no-snapshot-save -no-snapshot-load -no-window
```

The process arguments and sole ADB identity were verified before running:

```bash
./gradlew :feature:more:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest \
  --stacktrace
```

The first run accurately exposed inherited AVD test state: the disposable
overlay had global font scale `2.0`. App restoration passed 3/3, while More
passed 21/24 and failed the three display/layout assertions
`expandedFoldableContentUsesTwoColumnsAfterTheNavigationRail`,
`contentWidthAtSevenHundredTwentyDpUsesTwoColumns`, and
`conflictedTimeIsDisclosedExcludedByDefaultAndCanBeIncluded`. The command
exited 1 in 1 minute 17 seconds (322 actionable tasks: 20 executed, 5 from
cache and 297 up-to-date).

Only the disposable overlay was changed to the suite's accepted baseline font
scale `1.0`; its API, opened Fold posture, density and sole-ADB state remained
unchanged. The full exact command then exited 0 in 1 minute 18 seconds (322
actionable tasks: 2 executed and 320 up-to-date): More passed 24/24 (Insights
21 and More/Archive/Bin 3), and App process restoration passed 3/3, for 27/27
device tests. Dedicated 200%-text coverage remained part of the passing
Insights suite.

The disposable emulator was stopped without snapshot save. The protected
snapshot `task13_fixround1_replacement_20260728_055359` was restored with
`-no-snapshot-save`. Its AVD/API, UID, install time, light mode, opened posture,
CE inode and database/WAL/SHM inodes matched the pre-suite audit exactly.
That runtime session reported font scale `2.0`; it was not saved. The Stage 2
exit audit later reloaded the named snapshot and proved its saved font scale is
`1.0`. No protected install, uninstall, data clear or instrumentation occurred
in this historical Stage 1 session; the exact protected workspace identity
remained unchanged.

The independent fixture generator was rerun and
`git diff --exit-code -- core/data/src/test/resources/cloud-format/v1-authenticated`
returned no diff. The 19 authenticated-object tests passed as part of the
fresh Data suite. Fixture SHA-256 digests are:

```text
6a685fe9ca734e102e0f96408a1e531fff79c912d7474098599cfb5044faa24e  attachment-chunk.json
15d967d35a59e0466a53733fc4a0ee21df1e1aad740f028d279e086f21faf042  manifest.json
8ba80ec4c814abcd767e61131ac2bf9ee14d25a3efd103ca18cd6f17ca3d6410  operation-segment.json
3ce3780b33e62c2954ba8e9999346f7c49b4872b1d08db51b246a7f97ce35cb2  snapshot.json
```

The original Stage 1 exit acceptance audit confirmed:

- Room remains the sole live structured-data authority. Stage 1 added only
  the internal `core:data` → `core:crypto` dependency; no provider transport,
  credential, backup scheduler or cloud-to-Room path was added.
- No Room model, repository, outbox or exported-schema path changed from the
  approved Stage 1 plan commit. `VaultDatabase` remains version 5 with the
  existing five schema resources. The protected package/database identity is
  unchanged.
- All eight `CloudHeaderIdentity` fields are encoded as strict
  length-prefixed AEAD associated data. Tests prove valid family, vault,
  object, chunk-index and chunk-count substitutions fail authentication;
  schema, crypto and minimum-reader incompatibilities reject before AEAD.
- Declared frame length and ciphertext checksum reject before AEAD.
  Untrusted frame/authentication failures map to typed categories; exception
  text interpolates only public version, bound, family or fixed region labels,
  never private identifiers, checksums, keys or recovery metadata.
- Caller plaintext remains caller-owned. Associated-data and owned ciphertext
  buffers are cleared in `finally`; decoder scratch, partial ciphertext and
  checksum-rejected full ciphertext are cleared before ownership can transfer.
  Successful plaintext is closeable, defensively copyable or transferred
  exactly once.
- Active contracts distinguish the implemented internal authenticated codec
  in `core:data` from unimplemented provider transport, backup/blob services,
  recovery, scheduling, Android Auto Backup, and product-visible features.
- `android:allowBackup` remains `false`; extraction rules still exclude the
  application root and legacy rules remain unchanged. Android Auto Backup is
  not shipped and remains Stage 2 work.
- The placeholder/logging scan, fixture provider/private-content scan,
  `git diff --check` and working-tree audit were clean.

### Final-review correction wave

The single authorised final-review correction wave started from clean
`main` at `19db11f`. Its implementation correction is:

- `21c33bc` — `fix: clear rejected cloud ciphertext buffers`.

Strict decoder TDD added three controlled-stream regressions before production
changed. The stream retained every ciphertext read target and proved that it
had held ciphertext. This forced-fresh RED command:

```bash
./gradlew :core:sync:testDebugUnitTest \
  --tests '*CloudObjectFormatTest.decodeClearsSourceRetainedCiphertextScratch*' \
  --stacktrace --rerun-tasks
```

failed all 3/3 new tests at the expected post-failure zeroisation assertion:
checksum mismatch remained `CHECKSUM_MISMATCH`, truncation remained
`TRUNCATED`, and stream failure rethrew the exact injected `IOException`.
After the minimal ownership correction, the unchanged command passed 3/3 with
25/25 Gradle tasks executed. The complete forced-fresh Sync suite then passed
52/52 tests.

Four direct `VaultCrypto` default-record tests use a capturing delegate that
retains the derived associated-data reference. Encrypt and decrypt defaults
both clear that reference after successful return and exact delegate failure,
while caller-owned plaintext and ciphertext remain unchanged. Their focused
forced-fresh command passed 4/4 tests. No production crypto API or internal
visibility changed.

The decoder now:

- clears its bounded scratch array in `finally` on success and every failure;
- clears partially filled owned ciphertext when truncation or a stream
  exception prevents a complete read;
- retains local ownership of complete ciphertext through checksum validation;
  and
- transfers the complete buffer to `CloudObjectFrame` only after checksum
  success, clearing it on every pre-transfer failure.

Typed failures, checksum-before-AEAD ordering, exact framing and all frozen
fixtures remain unchanged. Fresh affected-module verification passed:

```bash
./gradlew :core:sync:testDebugUnitTest \
  :core:crypto:testDebugUnitTest \
  :core:data:testDebugUnitTest \
  --stacktrace --rerun-tasks
```

The command exited 0 with 67/67 Gradle tasks executed and 132/132 JVM tests
passing: Sync 52, Crypto 26 and Data 54.

Deterministic authenticated vectors retained their four recorded SHA-256
digests. The generator produced no diff, and the forced-fresh authenticated
codec/golden command passed all 19 tests with 57/57 tasks executed. A first
attempt to start that Gradle command was denied by the workspace sandbox before
Gradle startup because it could not open the existing wrapper-cache lock; the
unchanged command was rerun with cache-lock access and passed.

The complete requested host gates then passed:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug \
  --stacktrace --rerun-tasks
./gradlew :app:assembleRelease --stacktrace --rerun-tasks
```

The debug gate exited 0 in 37 seconds with 547/547 tasks executed and 193/193
JVM tests passing: App 25, Crypto 26, Data 54, Domain 36 and Sync 52. Lint and
`:app:assembleDebug` completed. The separate release gate exited 0 in 39
seconds with 441/441 tasks executed, including R8, resource shrinking,
packaging and `:app:assembleRelease`.

Final-review contract reconciliation records the internal authenticated codec
as implemented in `core:data`, composed from `core:sync` framing/identity and
`core:crypto` generic AEAD. It does not claim provider transport, separate
backup/blob services, recovery, scheduling, Android Auto Backup, or
product-visible backup/attachment flows. `AttachmentBlobStore` is the active
blob contract name. At that Stage 1 checkpoint Android backup remained
disabled and supplementary future Stage 2 work.

The source/terminology scans found no placeholder, logging, provider/private
fixture, stale codec-status/ownership or obsolete blob-store contract name.
Negative mentions of superseded Drive-primary, multi-device and `keepOffline`
concepts remain only where the approved design explicitly rejects them. There
is no change to Room, schemas, repositories, provider code, manifests,
extraction or backup rules. No ADB, connected test, install, uninstall, data
clear or other device command ran in this correction wave; the protected state
was untouched. The ignored SDD progress ledger was not edited.

That correction wave observed 200% global text in an unsaved protected runtime
session. The Stage 2 exit audit proves the named snapshot itself retains its
1.0 saved baseline. Any future device suite must still verify its disposable
overlay before running; the product's dedicated 200%-text acceptance remains
covered.

At this Stage 1 checkpoint, the next recommended action was to design and plan
Stage 2. That design and its detailed implementation plan have since received
written approval. The approved Stage 2 plan was subsequently executed and is
now complete.

## Previous P1/P2 pause closure verification

After adding the explicit pause and resume instructions, the exact paused code
state passed the repository gate on 27 July 2026:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
```

Gradle reported `BUILD SUCCESSFUL`: all debug unit tests passed, Android lint
completed and the debug APK assembled. This pause closure changed Markdown
only; the immediately preceding P2 verification record remains the device and
release evidence for the unchanged application code. No commit, push, branch
change, app uninstall or emulator-data wipe was performed.

## Train 0 baseline manifest

This is the audited inventory of the completed P1/P2 work that Train 0
committed as the verified baseline. The inventory was captured with
`git status --short` and `git ls-files --others --exclude-standard` before this
section was added. Every captured path is accounted for below; the groups
describe the completed slice(s) they support, not a new runtime API. The
working tree was clean at that baseline checkpoint. It should also be clean at
each reviewed Train 1 task boundary; investigate and preserve unexpected user
changes.

| Group | Captured paths |
|---|---|
| P1/P2 product contracts and UK-English documentation | `DESIGN.md`; `PRODUCT.md`; `README.md`; `docs/architecture.md`; `docs/threat-model.md` |
| P1/P2 application shell, reminders, restoration and UK-English resources | `app/src/main/AndroidManifest.xml`; `app/src/main/kotlin/app/opentasks/MainActivity.kt`; `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`; `app/src/main/kotlin/app/opentasks/OpenTasksApplication.kt`; `app/src/main/kotlin/app/opentasks/QuickAddSheet.kt`; `app/src/main/kotlin/app/opentasks/SearchSurface.kt`; `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`; `app/src/main/res/values/strings.xml`; `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt`; `app/src/main/kotlin/app/opentasks/reminders/ReminderSystem.kt`; `app/src/main/res/drawable/ic_notification.xml`; `app/src/main/res/xml/locales_config.xml`; `app/src/test/kotlin/app/opentasks/WorkspaceSelectionStateTest.kt`; `app/src/test/kotlin/app/opentasks/reminders/ReminderSystemTest.kt` |
| P1/P2 data, migrations, templates and time-entry verification | `core/data/build.gradle.kts`; `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`; `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`; `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`; `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt`; `core/data/src/main/kotlin/app/opentasks/core/data/db/EntityMappers.kt`; `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`; `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`; `core/data/src/test/kotlin/app/opentasks/core/data/db/EntityMappersTest.kt`; `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/3.json`; `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/4.json`; `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/5.json`; `core/data/src/main/kotlin/app/opentasks/core/data/TemplatePayloadCodec.kt`; `core/data/src/test/kotlin/app/opentasks/core/data/TemplatePayloadCodecTest.kt` |
| P1/P2 domain and shared presentation rules | `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/Components.kt`; `core/domain/src/main/kotlin/app/opentasks/core/domain/RecurringTaskPlanner.kt`; `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`; `core/domain/src/main/kotlin/app/opentasks/core/domain/WorkspaceRules.kt`; `core/domain/src/test/kotlin/app/opentasks/core/domain/RecurringTaskPlannerTest.kt`; `core/domain/src/test/kotlin/app/opentasks/core/domain/WorkspaceRulesTest.kt`; `core/domain/src/main/kotlin/app/opentasks/core/domain/ProjectTemplatePlanner.kt`; `core/domain/src/test/kotlin/app/opentasks/core/domain/ProjectTemplatePlannerTest.kt`; `core/model/src/main/kotlin/app/opentasks/core/model/Fixtures.kt`; `core/model/src/main/kotlin/app/opentasks/core/model/Identifiers.kt`; `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt`; `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt` |
| P1/P2 feature surfaces and device coverage | `feature/home/src/main/kotlin/app/opentasks/feature/home/HomeScreen.kt`; `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/TrashScreenInstrumentedTest.kt`; `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`; `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/ProjectWorkbenchInstrumentedTest.kt`; `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`; `feature/schedule/build.gradle.kts`; `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleScreen.kt`; `feature/schedule/src/androidTest/kotlin/app/opentasks/feature/schedule/ScheduleScreenInstrumentedTest.kt`; `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskEditorInstrumentedTest.kt`; `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt` |
| Existing P1/P2 handoff | `HANDOFF.md` (its pre-existing P1/P2 content plus this Train 0 audit record) |

The tracked/untracked audit contained 42 modified paths and 14 untracked
paths. `git diff --check` exited 0 with no output. The credential-pattern scan
reported two benign text matches: `.gitignore` excludes
`client_secret*.json`, and the Train 0 plan documents the scan pattern itself.
Neither is a credential, private key or OAuth client secret. All unrelated
user files remain unstaged; this checkpoint stages only `HANDOFF.md`.

Room schema evidence: exported schemas `1.json`, `2.json`, `3.json`,
`4.json` and `5.json` exist under
`core/data/schemas/app.opentasks.core.data.db.VaultDatabase/`.
`VaultDatabase.version` is 5, and `5.json` declares database version 5
(identity hash `9678c8424993d9f4d0694e59aa6912fa).

Train 0 gate results on 27 July 2026:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
./gradlew :app:assembleRelease --stacktrace
```

Both commands exited 0. The debug gate reported `BUILD SUCCESSFUL` in 9s
(547 actionable tasks: 13 executed, 534 up-to-date); debug unit tests, lint
and debug assembly completed. The separately run release gate reported
`BUILD SUCCESSFUL` in 1s (441 actionable tasks: 5 executed, 436 up-to-date),
including `:app:minifyReleaseWithR8`,
`:app:convertShrunkResourcesToBinaryRelease`,
`:app:optimizeReleaseResources` and `:app:assembleRelease`. The release
configuration retains `isMinifyEnabled = true` and `isShrinkResources = true`.

## Current programme boundary

Stage 2 and Stage 3 are implemented and verified through their task
boundaries. Stage 3 create-only Tasks 1–14 close with the Task 14 qualification
change, and the approved Galaxy Fold 8 trifold-ready adaptive slice closes with
the Task 5 qualification change containing this handoff. Stage 4 is complete
and qualified: notes, activity, search, attachment transport, and attachment
product flows are implemented. Remote merge remains absent by design.

The credential-free GitHub Actions matrix and release gate remain repaired;
the queued dependency updates are resolved in the verified 30 July
maintenance commit. The approved Stage 3, adaptive-slice, and Stage 4
execution authorities are closed. Stage 5 is in progress under its approved
design and plan: Tasks 1–2 of 13 are complete and reviewed, paused before
Task 3. Samsung RTL, native fold
continuity, and broader two-installation live recovery evidence remain
post-Stage-4/external work and are not implied by Stage 4 qualification.

## Resume instructions

1. Read this file first, the
   completed
   [Stage 2 design](docs/superpowers/specs/2026-07-28-stage-2-local-backup-android-auto-backup-design.md),
   the
   [production programme](docs/superpowers/plans/2026-07-27-open-tasks-production-master-plan.md),
   then
   [docs/architecture.md](docs/architecture.md),
   [docs/threat-model.md](docs/threat-model.md), [DESIGN.md](DESIGN.md) and
   [PRODUCT.md](PRODUCT.md).
2. Re-scan the working tree and preserve any user changes. Train 1 Tasks
   1.1–1.5, Stage 1, and Stage 2 are complete; do not amend or reopen their
   reviewed commits without a new verified finding.
3. Start Stage 5 from the current live backlog. Retain the External-blocked
   Samsung RTL gap and route native fold continuity plus broader
   two-installation live recovery evidence as post-Stage-4 work.
4. Run any device suite on a sole disposable emulator. Verify the disposable
   font scale as well as AVD/API/posture before instrumentation. Do not let
   Keystore or App instrumentation mutate the protected workspace.
5. Preserve the read-only legacy outbox, Room v8 local authority, exact Android
   package allow-list, inert restored-package inbox, and local no-upload copy.
   Treat encrypted Google transport backup/restore as external evidence until
   it is actually qualified.
6. Follow the six-stage dependency chain; historical Train 1–6 plans are
   evidence or replanning inputs, not executable contracts.
7. Before recording the next pause or completion, run the repository gate from
   `CLAUDE.md`, affected device suites, release assembly when production code
   changed, and `git diff --check`; update this hand-off and every affected
   contract document in the same change.

## Live backlog: six dependency-ordered stages

This is the only active backlog. The 27 July Train 1–6 documents are historical
evidence or replanning inputs and must not be executed. Completed Train 0,
local-workspace, Insights, key-separation, and bounded-frame evidence remains
recorded above.

| Order | Stage | Status | Exit decision |
|---:|---|---|---|
| 1 | Direction reset and authenticated object foundation | Done | Active contracts match local authority; the authenticated provider-independent object codec is frozen |
| 2 | Local backup and Android Auto Backup | Done | Local generations produce verified primary snapshots and one strictly whitelisted portable package |
| 3 | App-managed backup and recovery takeover | Done | Drive backup, retention, recovery, writer epochs, stale-writer rejection, credential rotation, remote lifecycle, and approved product surfaces are proven |
| 4 | Notes, activity, cloud attachments, and search | Done (qualified) | Notes/activity/search and attachment lifecycle/product flows are implemented; native fold and broader two-installation evidence remain post-Stage-4 |
| 5 | Remaining platform features | In progress (Tasks 1–2 of 13 complete) | Import/export, widget, app lock, input, and calendar features use the final local schema |
| 6 | Production qualification and rollout | Blocked by Stage 5 and external owner gates | Backup, attachment, takeover, recovery, accessibility, performance, privacy, and release gates pass |

The dependency chain is strict:

```text
Stage 1 → Stage 2 → Stage 3 → Stage 4 → Stage 5 → Stage 6
```

### Current execution order

1. Resume Stage 5 at Task 3 (GC closure over retired sets) from base
   `eb343cb`, re-entering superpowers:subagent-driven-development with the
   committed plan and the execution ledger named in the Stage 5 checkpoint.
   Preserve Stage 4 as a closed authority, keep Samsung RTL
   External-blocked, and retain native fold and broader two-installation
   live recovery evidence as post-Stage-4 work.

GitHub dependency-PR checks and resolution remain paused. Android Auto Backup
and device transfer retain the verified exact-file allow-list. Existing Room,
legacy outbox, local package, and workspace data remain protected by their
explicitly planned, verified boundaries.

## Architecture and security rules for the next agent

- Read [docs/architecture.md](docs/architecture.md),
  [docs/threat-model.md](docs/threat-model.md), [DESIGN.md](DESIGN.md) and
  [PRODUCT.md](PRODUCT.md) before changing the corresponding contract.
- Every write must remain a `DomainCommand` through `VaultRepository`.
- Mutations and their ordered backup-journal entries must remain one
  transaction. The legacy outbox is read-only.
- Undo is repository-produced; never reconstruct it in UI code.
- Keep `InMemoryVaultRepository` behaviour aligned with
  `RoomVaultRepository`.
- Close `RoomVaultRepository` before closing its `VaultDatabase`; repository
  close joins the observation job.
- A Room version bump requires an exported schema and non-destructive
  migration fixture.
- Template capture includes only an active project's active workflow, open
  milestones and open/non-Bin/non-complete tasks. Preserve the 100-template,
  500-task, 100-year and 2 MiB limits; validate bounded self-contained payloads
  and acyclic parent/dependency graphs before use. Instantiation must retain
  wall-clock zone intent, derive relation IDs from the new project ID and
  commit every new record/journal entry atomically.
- Workflow status writes stay project/Inbox scoped, preserve immutable
  semantic categories and retain at least one active status per category.
  Moving a task between projects maps by semantic category; Undo restores the
  exact prior status ID.
- Milestones stay project-scoped, retain revision metadata and enforce 120
  character names, case-insensitive project uniqueness and a 100-row project
  cap. Deletion and membership restoration must remain atomic with every
  affected task journal entry.
- Dependency links stay distinct from derived blocking state: `dependencyIds`
  is the durable relation set and `blockedBy` contains only unfinished linked
  tasks. All writes use `SetTaskDependency`, enforce the 100-link cap and
  reject self/transitive cycles before the task, relation and backup-journal
  state are committed atomically.
- Every completion entry point must route through the same repository gate.
  UI confirmation is an acknowledgement, not an authority bypass, and
  notifications must continue to omit Complete for blocked tasks.
- Schedule remains a read-only snapshot projection. Group by the `start`
  moment's local date, falling back to `due`, preserve each moment's stored
  zone and open the canonical task editor for mutation.
- Process restoration stays layered: serializable Navigation 3 state for the
  destination, `SavedStateHandle` for selected IDs, bounded Compose saveable
  state for filters/scroll/drafts and Room for active timers. Never place
  passphrases, keys, attachments or vault payloads in saved-instance state, and
  never let the initial repository emission overwrite a restored draft.
- Time entries are first-class records. Keep add/update/delete/restore as
  repository commands with exact Undo and atomic journal writes; enforce a
  positive interval, 500-character notes and 10,000 entries per task. Running
  entries are timer-owned and cannot be edited or deleted. Preserve overlapping
  records, reconcile them with the deterministic linear sweep and keep the
  warning visible until the user corrects the source intervals. Do not turn
  reconciliation into silent trimming, merging or deletion.
- A crypto-format bump requires old-format fixtures and golden-vector review.
- Never replace a missing Keystore key for an existing local envelope.
- Keep SQLCipher database keys and Tink vault-content keys independently
  generated. Recovery-passphrase changes and local Android Keystore wrapping
  must re-wrap the same content key rather than re-encrypt content or reuse the
  database key.
- Cloud v1 headers remain strict canonical UTF-8 with fixed key ordering,
  explicit version fields, exact vault/object identity and optional attachment
  chunk identity. Validate the 16 KiB header and family-specific length/count
  bounds before allocating or reading ciphertext.
- A checksum detects corruption but is not authentication. The implemented
  `core:data` codec binds the full `CloudHeaderIdentity` as AEAD associated
  data, checks length and checksum before AEAD, and exposes no plaintext until
  authentication succeeds.
- Preserve `CloudObjectFrame`'s one-shot ciphertext ownership. Ciphertext reads
  from caller-controlled streams may use only bounded scratch storage, never
  the retained verified ciphertext array. Clear scratch in `finally` and clear
  partial or checksum-rejected ciphertext before ownership transfer.
- Keep passphrases as `CharArray` and zero temporary key arrays.
- Keep one process-scoped local backup coordinator. It may checkpoint only
  after authenticated strict readback and source comparison; failure never
  blocks local editing or advances the verified checkpoint.
- Keep the recovery envelope bound to the existing content key and the
  portable package capped at 24 MiB. Package readiness reports local
  generation, bytes, and production time only.
- Android backup and device transfer may include only the `file`-domain
  application-relative path
  `android_backup/open_tasks_portable_v1.otb`. Restored or unknown input moves
  to the no-backup inbox and remains inert until the explicit recovery path
  verifies and activates it.
- Never log private content, account data, Drive IDs, attachment names or
  encryption metadata.
- Future Drive code receives encrypted objects only and requests only
  `drive.appdata`.
- Alarm and pending-intent payloads contain record IDs only; private
  notification content must retain a generic lock-screen public version.
- Layout decisions use `WorkspaceLayoutPolicy`, never a device model.
- Feature composables stay stateless and free of Hilt.
- User-facing language is UK English: use UK spelling, Bin terminology,
  day–month dates and the 24-hour clock. Stable internal identifiers may retain
  historic names, but must never leak them into UI copy.
- Update architecture, design, threat-model and handoff documents in the same
  change whenever their contracts are affected.

## Recommended next action

Resume Stage 5 at Task 3 from the checkpoint above. Preserve Room as the
sole live structured-data authority, treat the Stage 2 Android package as
supplementary recovery input rather than upload evidence, and retain the
External-blocked Samsung RTL rows plus native fold and broader
two-installation recovery evidence as post-Stage-4 work.

Keep the protected workspace safe and run future device suites only on a sole
audited disposable emulator unless a new in-place procedure is explicitly
approved. Preserve existing outbox data and the exact Android package
allow-list. Continue normal dependency and pull-request maintenance against the
private GitHub repository; reauthenticate the local `gh` CLI before any future
CLI-only GitHub operation because its current token lacks valid private-repo
access.
