# Open Tasks Handoff

- Last updated: 30 July 2026
- Branch: `main`
- Session status: **Stage 3 execution is paused at the user's request after
  Task 6 of the create-only plan. Tasks 1–6 are complete, independently
  reviewed, and committed: the qualified create-only transport (`b6191fd`),
  frozen ownership and publication formats (`7ce27b1`), additive Room v7
  remote state (`b0284bb`, `184786f`), crash-safe vault slot gating
  (`5fc11ae`, `839efc5`, `4f57781`), explicit authorization and account
  binding (`ca70ca8`, `2bb76dc`), and create-only Drive object storage
  (`051ce53`, `05a1d44`, `75f94a8`). Every task review closed with zero open
  Critical or Important findings; deferred minors, rulings, and fix-round
  history live in the ignored SDD ledger
  `.superpowers/sdd/2026-07-30-stage-3-drive-create-only-backup-recovery-plan/progress.md`.
  Resume at Task 7 (Resolve Ownership and Publish Epoch One) of
  `docs/superpowers/plans/2026-07-30-stage-3-drive-create-only-backup-recovery-plan.md`
  with subagent-driven execution. Two user rulings amended that plan in
  place: the v7 config entity gained `previousPublicationGeneration`, and
  `DriveAuthorizationResult.Unavailable` now carries a bounded reason enum.
  One ruling is pending implementation at Task 12: tighten the publication
  codec so equal local generation requires a recovery-credential-generation
  increase, amending the brief-mandated equal-generation test. Task 14
  carry-forwards: live confirmation of the Google `Account` derivation used
  for revoke, and R8 keep-rule reachability once product UI calls
  `authorize()`. The disposable emulator was shut down cleanly; the AVD
  retains the signed-in Google account that disposables inherit for
  credentialed gates. Stage 2, Train 1 Tasks 1.1–1.5, and Stage 1 remain
  complete and independently reviewed.**
- Current source implementation point: `75f94a8` (`fix: bound oversized
  upload rejection`). The prior Stage 2 correction point is
  `f9e091b` (`fix: harden stage 2 backup state transitions`); it closes content-key authority, complete
  Inbox capture, same-generation state ownership, initial crash
  reconciliation, active-loop Retry, durable inbox truth, transient intake
  I/O, bounded threshold selection, and stale Ready presentation. Detailed
  RED/GREEN traces remain in
  `.superpowers/sdd/2026-07-29-stage-2-local-backup-android-auto-backup-plan/final-review-fix-brief.md`
  and its adjacent ignored `final-review-fix-report.md`. The implemented
  design remains
  `docs/superpowers/specs/2026-07-28-stage-2-local-backup-android-auto-backup-design.md`
  and the active execution plan is
  `docs/superpowers/plans/2026-07-29-stage-2-local-backup-android-auto-backup-plan.md`.
  The controller verified the correction on the sole audited API 37
  read-only/no-snapshot disposable and shut it down cleanly. The protected
  workspace was not touched. The user-owned untracked `artifacts/` directory
  remains untouched.

This is the only live project handoff and ordered backlog. Update it whenever
work changes scope, priority, dependencies, architecture, security assumptions
or verification status.

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
1–6 are committed and reviewed: qualified transport, frozen formats, Room v7
remote state, crash-safe vault slot gating, explicit authorization with HMAC
account binding, and byte-bounded create-only object storage. Ownership
resolution, publication, scheduling, recovery activation, writer takeover,
lifecycle, product UI, cloud attachments and Play Console work have not
started (plan Tasks 7–14). Release gates which depend
on those later features remain blocked by their listed prerequisites.

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
Drive transport, user recovery activation, and attachments are not
implemented. The historical Train 1 Task 1.6 is superseded.

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
the ignored SDD ledger. The pending Task 12 codec-tightening ruling and the
Task 14 live-confirmation carry-forwards are recorded there and in the
session status above. The protected workspace and user-owned `artifacts/`
were untouched; all connected and credentialed evidence came from sole
audited read-only disposables.

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
- Final visual acceptance passed on the API 37 Pixel 10 Pro Fold main display
  at normal density and text scale. Earlier checks covered the compact cover
  display, fold/unfold, narrow detail panes and 200% text.

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

There is no uncommitted source implementation. Stage 2 is implemented,
verified, and reviewed through its task boundaries. The local coordinator and
portable Android package are shipped source features, but package readiness is
only a local fact. Drive transport, recovery activation, writer takeover,
remote merge, and attachment transport remain absent.

The credential-free GitHub Actions matrix and release gate remain repaired;
the queued dependency updates are resolved in the verified 30 July maintenance
commit. No Stage 3 source change is authorised until focused brainstorming, design, and implementation
planning are reviewed and approved.

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
3. With a new explicit user request, begin focused Stage 3 brainstorming and
   design. Do not write provider, recovery, writer-epoch, or remote-retention
   source before the new design and implementation plan are approved.
4. Run any device suite on a sole disposable emulator. Verify the disposable
   font scale as well as AVD/API/posture before instrumentation. Do not let
   Keystore or App instrumentation mutate the protected workspace.
5. Preserve the read-only legacy outbox, Room v6 local authority, exact Android
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
| 3 | App-managed backup and recovery takeover | Ready for brainstorming and design | Drive backup, retention, recovery, writer epochs, and stale-writer rejection are proven |
| 4 | Notes, activity, cloud attachments, and search | Blocked by Stage 3 | Cloud-authoritative blob lifecycle and final structured metadata are complete |
| 5 | Remaining platform features | Blocked by Stage 4 | Import/export, widget, app lock, input, and calendar features use the final local schema |
| 6 | Production qualification and rollout | Blocked by Stage 5 and external owner gates | Backup, attachment, takeover, recovery, accessibility, performance, privacy, and release gates pass |

The dependency chain is strict:

```text
Stage 1 → Stage 2 → Stage 3 → Stage 4 → Stage 5 → Stage 6
```

### Current execution order

1. Brainstorm and design Stage 3 app-managed backup and recovery takeover
   against the completed Stage 2 boundary.
2. Review and approve the Stage 3 design and detailed implementation plan.
3. Only then begin Stage 3 source work.

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
  to the no-backup inbox, remains inert, and exposes no activation action
  before Stage 3.
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

After a new explicit user request, run focused Stage 3 brainstorming and design
for app-managed encrypted backup, retention, recovery takeover, writer epochs,
and stale-writer rejection.
Preserve Room as the sole live structured-data authority and treat the Stage 2
Android package as supplementary recovery input, not upload evidence.

Keep the protected workspace safe and run future device suites only on a sole
audited disposable emulator unless a new in-place procedure is explicitly
approved. Preserve existing outbox data and the exact Android package
allow-list. Continue normal dependency and pull-request maintenance against the
private GitHub repository; reauthenticate the local `gh` CLI before any future
CLI-only GitHub operation because its current token lacks valid private-repo
access.
