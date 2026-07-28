# Open Tasks Handoff

- Last updated: 28 July 2026
- Branch: `main`
- Session status: **Paused at the user's requested boundary after Train 1
  Task 1.5. Tasks 1.1–1.5 are complete and independently reviewed; Task 1.6
  has not started. GitHub maintenance remains paused.**
- Current implementation point: `ce8f5bf` (`fix: isolate verified ciphertext
  ownership`). Task 1.5 passed its final re-review with zero open findings
  after two correction rounds. This documentation update is the pause
  checkpoint. No emulator was ADB-attached during the pause audit. The named
  authorised replacement snapshot remains present; its last verified state was
  expanded, light mode and 100% text.

This is the only live project handoff and ordered backlog. Update it whenever
work changes scope, priority, dependencies, architecture, security assumptions
or verification status.

## Executive status

Open Tasks is a working local-first Android foundation, not yet a production
release. The encrypted local workspace, adaptive shell, task editor, recurring
tasks, custom per-project workflows, editable milestones, Bin, project
workbench, search, timers and due-relative reminders persist across process
restarts. Reminder permission timing, exact-alarm fallback, lock-screen
redaction, task deep links, Snooze and Complete actions are implemented.
Workflow add, rename, reorder, archive and restore preserve immutable reporting
categories and assigned tasks.
Milestone create/edit/complete/reopen/delete and project-scoped task membership
are implemented with atomic outbox writes and exact Undo. Task dependency
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
encrypted Room persistence and atomic outbox writes. Qualified workspace
Insights now cover completion, overdue work, estimate/actual time, project/tag
time and milestone health with accessible table/text alternatives. The
application is fixed to UK English with UK spelling, day–month dates, 24-hour
time and Bin terminology.

The public GitHub authority is
[ksdaklmk/open-tasks](https://github.com/ksdaklmk/open-tasks); local `main`
tracks `origin/main`. Secret scanning, push protection, Dependabot alerts and
security updates are enabled. The Android workflow now uses explicit compact
API 36/stable/Pixel 6 and expanded API 37/canary/Pixel Tablet instrumented
matrix entries, passes the matching channel and profile to the emulator runner,
and runs a separate release-assembly job after verification. Its local
structural verifier prevents mutable Action references. Queued dependency PR
checks and resolution remain paused. A blocking `PreToolUse` guard rejects raw
Kotlin `Color(0x...)` writes outside `core/designsystem`; its deterministic
protocol verifier covers both Write and Edit payloads. Non-provider secret
patterns and validity checks are unavailable in the current repository plan
and remain disabled. Google Identity, Drive transport, cloud recovery and Play
Console work have not started. P0 release gates which depend on those features
remain blocked by their listed prerequisites.

Train 1 Tasks 1.1–1.5 are complete. Vault-content keys are now independent of
SQLCipher database keys and have separate recovery and per-vault Android
Keystore wrapping. Canonical bounded cloud frames exist for manifests,
snapshots, operation segments and attachment chunks. These are foundations
only: the authenticated cloud codec, Drive transport and user recovery flow
are not implemented. Task 1.6 is the next approved task when work resumes.

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
  records plus outbox operations atomically.
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
  separate outbox operations.
- Repeated completion or redelivery creates exactly one next occurrence.
- Completion Undo reopens the original and removes only the generated
  occurrence.
- Editing a generated occurrence and undoing that edit restores its exact
  recurrence rule, due time, series ID, anchor and occurrence index.
- Room v1→v2, v2→v3, v3→v4 and v4→v5 migrations are non-destructive and
  preserve encrypted data; v3 creates project/Inbox workflows and remaps
  existing task statuses, v4 adds milestone revisions and v5 adds template
  revisions.
- On-device Compose coverage exercises every cadence, interval editing,
  multiple weekdays, count ending, 200% text, 48 dp targets and keyboard
  activation.

### Reminders and notifications

- One persisted due-relative reminder per task with deterministic identity.
- Task and reminder editor changes, Undo and independent outbox operations are
  committed atomically.
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
- Room writes and outbox operations are atomic.
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
  its revisioned, delimiter-safe time-entry outbox v2 operation atomically.
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
- Public-repository audit found no credential, private-key, OAuth-client or
  provider-token patterns in the tracked tree or its existing history.
- `.gitignore` excludes local properties, environment files, signing keys,
  OAuth/service-account files, local vault databases/exports and generated
  release artefacts.
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
| P0-13 | Published the audited history as a public GitHub repository | `main` tracks `origin/main`; GitHub secret scanning and push protection enabled |

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

The repository-wide command
`./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace`
remains blocked by five pre-existing `UnrememberedMutableState` findings in
`feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt`
at lines 220, 277, 306, 347 and 572. Task 1.5 did not change that file. Do not
describe the full repository gate as green until those Task 1.3 test-fixture
lint findings are corrected and the gate is rerun. The final pause audit
reproduced only that blocker: the command exited 1 at
`:feature:more:lintDebug` after 12 seconds with 469 actionable tasks
(27 executed, 2 from cache and 440 up-to-date).

The Android SDK update and Android Studio run coincided with loss of the old
snapshot identity; causation is unproven. The user authorised the fresh
installation as the replacement protected baseline. The untouched
pre-replacement AVD clone remains at
`/private/tmp/open-tasks-avd-recovery.m7hw3u`. The verified replacement snapshot
is `task13_fixround1_replacement_20260728_055359`, and the active emulator was
started from it with `-no-snapshot-save`.

Replacement protected identity:

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

Never run `:app:connectedDebugAndroidTest` on this protected emulator: AGP
uninstalls `app.opentasks`. Use a sole disposable emulator started read-only
with no snapshot load/save, then restore and re-verify the named protected
snapshot.

The Task 1.4 plan correction for `core/crypto/build.gradle.kts` was applied:
its instrumentation suite has the Android test runner and AndroidX
core/JUnit/runner/rules dependencies it requires.

At the final pause audit, `adb devices -l` reported no attached device. The
named replacement snapshot and the untouched pre-replacement recovery clone
were both still present. Treat the recorded identity as the last verified
protected state and restore the named snapshot before any future live-app
acceptance.

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

## Current in-progress work

There is no in-progress implementation. Work is deliberately paused at the
user's request after the independently reviewed Task 1.5 commit `ce8f5bf`.
Task 1.6 has not started; no authenticated cloud-object codec, new
`core:data`/`core:crypto` dependency, Drive transport or user-facing sync flow
should be inferred from the completed frame foundation.

The credential-free GitHub Actions matrix and release gate remain repaired;
queued dependency PR checks and resolution remain paused. P2-F02 cannot
complete until its attachment/cloud prerequisites exist. Train 1 resumes with
Task 1.6 before blocked Drive/provider work.

## Resume instructions

1. Read this file first, the
   [Train 1 plan](docs/superpowers/plans/2026-07-27-train-1-insights-cloud-format-plan.md),
   its ignored SDD progress ledger, then
   [docs/architecture.md](docs/architecture.md),
   [docs/threat-model.md](docs/threat-model.md), [DESIGN.md](DESIGN.md) and
   [PRODUCT.md](PRODUCT.md).
2. Re-scan the working tree and preserve any user changes. Tasks 1.1–1.5 are
   complete; do not amend or reopen their reviewed commits without a new
   finding.
3. Resume the approved subagent-driven workflow at Task 1.6 with strict
   test-first coverage. Add `core:data`'s dependency on `core:crypto`, bind the
   full canonical header identity as AEAD associated data, verify checksum
   before decryption, translate failures to typed values and freeze independent
   golden vectors.
4. Before claiming the train exit gate is green, correct the five recorded
   `UnrememberedMutableState` findings in the Task 1.3 Insights device test and
   rerun the repository command from `CLAUDE.md`.
5. Run any device suite on a sole disposable emulator. Do not let Keystore or
   App instrumentation mutate the protected workspace.
6. Keep `P2-F02`, `P1-L05` and the blocked cloud/release gates paused unless
   their listed prerequisites or the user's direction change. The P3 baseline
   developer-experience tasks are closed.
7. Complete and independently review Task 1.6 before starting any Drive or
   provider work.
8. Before recording the next pause or completion, run the repository gate from
   `CLAUDE.md`, affected device suites, release assembly when production code
   changed, and `git diff --check`; update this hand-off and every affected
   contract document in the same change.

## Remaining tasks ordered by priority and dependency

The order within each table is topological: start with the lowest-numbered
ready item whose dependencies are satisfied. Release-gate P0 items remain P0
even when their implementation is blocked by P1/P2 product work.

### P0 — release and acceptance gates

| Order | ID | Status | Task | Depends on |
|---:|---|---|---|---|
| 1 | P0-R02 | External | Direct TalkBack, Switch Access, high-contrast, reduced-motion and RTL acceptance on the currently implemented surfaces | Human/physical-device session |
| 2 | P0-R03 | Blocked | End-to-end multi-device conflict, authentication expiry, quota, pagination, corruption, retry, reinstall and new-device recovery tests | P1-D01 through P1-D06 |
| 3 | P0-R04 | Blocked | Final full-app accessibility audit at 100/130/200% text, keyboard, TalkBack, Switch Access, high contrast, reduced motion, RTL and compact/expanded layouts | All P1/P2 UI surfaces complete |
| 4 | P0-R05 | Blocked | Screenshot/responsive regression suite for API 36/37, Fold cover/main, tablet, rotation, split-screen and live resizing | Stable P1/P2 UI plus screenshot harness |
| 5 | P0-R06 | Blocked | Baseline Profile and Macrobenchmark module; validate startup, scrolling and large task sets | Stable critical journeys plus large-data fixture |
| 6 | P0-R07 | Blocked | Final R8/resource-shrinking and performance budgets against production feature set | P0-R05, P0-R06 and all release features |
| 7 | P0-R08 | Done | Pin GitHub Actions to reviewed commit SHAs | Completed in Train 0 |
| 8 | P0-R09 | Blocked | Complete recovery UX for Keystore loss, reinstall and new devices | P1-D04 cloud migration/recovery |
| 9 | P0-R10 | External | Privacy Policy, OAuth brand verification, Play Data Safety, Play App Signing and internal/closed/open/staged rollout | P1 Drive slice, all product features, owner accounts and policy decisions |

### P1 — local core workspace

| Order | ID | Status | Task | Depends on |
|---:|---|---|---|---|
| 1 | P1-L01 | Done | Reminders and notification actions, permission timing and exact-alarm fallback | Existing task/due model |
| 2 | P1-L02 | Done | Custom per-project workflows: rename, reorder, add and archive while preserving semantic reporting categories | Existing workflow records and commands |
| 3 | P1-L03 | Done | Milestone editing and task milestone membership | Existing milestone schema/project workbench |
| 4 | P1-L04 | Done | Dependency editor, cycle-rejection UI and blocked-completion warnings at every task entry point | Existing dependency rules |
| 5 | P1-L05 | Blocked | Full-text search for notes and attachment names | P2-F02 note/attachment records |
| 6 | P1-L06 | Done | Schedule compact day agenda, expanded week timeline and unscheduled-task tray | Existing due dates; P1-L01 for reminder affordances |
| 7 | P1-L07 | Done | Complete process restoration for selection, drafts, scroll, filters and timer state | Current SavedStateHandle/navigation foundation |
| 8 | P1-L08 | Deferred | Encrypted `.otvault` import/export and deliberate plaintext CSV warnings | Threat-model parser gates; final local schemas |

### P1 — Drive-primary storage and recovery

| Order | ID | Status | Task | Depends on |
|---:|---|---|---|---|
| 1 | P1-D01 | Ready with credentials | Google Identity authorisation using only `drive.appdata` | OAuth client/account configuration |
| 2 | P1-D02 | Paused | Finish authenticated encryption for the completed versioned, checksummed and bounded manifest, snapshot, operation-segment and attachment-chunk frames | Train 1 Task 1.6; existing `core:crypto`, `core:sync`, threat model |
| 3 | P1-D03 | Blocked | Drive `CloudObjectStore`, `changes.list`, pagination and resumable object transport | P1-D01, P1-D02 |
| 4 | P1-D04 | Blocked | Outbox upload, remote download, idempotent merge, retry/backoff and visible sync health | P1-D03 |
| 5 | P1-D05 | Blocked | Local-to-Drive migration with checksum verification and a seven-day local rollback copy | P1-D04 |
| 6 | P1-D06 | Blocked | Drive-to-local disconnect without cloud deletion; guarded cloud-delete recovery | P1-D04, P1-D05 |
| 7 | P1-D07 | Blocked | Resumable encrypted attachment upload and offline attachment cache policy | P1-D02 through P1-D04; P2-F02 |
| 8 | P1-D08 | Blocked | Complete the cloud/multi-device P0 test matrix | P1-D01 through P1-D07 |

### P2 — productivity and full-workspace features

| Order | ID | Status | Task | Depends on |
|---:|---|---|---|---|
| 1 | P2-F01 | Done | Templates with relative dates, workflows, milestones and task structure | P1-L02, P1-L03 |
| 2 | P2-F02 | Blocked | Notes/activity history and attachments using Photo Picker, Storage Access Framework, Sharesheet, drag/drop, constrained `FileProvider` cleanup and 100 MB limits | Local attachment encryption/design; P1-D07 for cloud |
| 3 | P2-F03 | Done | Manual time entries and timer-overlap reconciliation | Existing timer foundation |
| 4 | P2-F04 | Done | Accessible qualified insights for completion, overdue work, estimate/actual time, project/tag time and milestone health | P1-L03, P2-F03 |
| 5 | P2-F05 | Deferred | Today Glance widget, Quick Add launcher refinement and app-lock title privacy | P1-L01; privacy review |
| 6 | P2-F06 | Deferred | Keyboard shortcut helper, mouse/hover support and accessible alternatives to drag actions | Stable final navigation/editors |
| 7 | P2-F07 | Deferred | One-way calendar export through `ACTION_INSERT` | P1-L06; export/privacy review |

### P3 — repository and developer experience

| Order | ID | Status | Task | Depends on |
|---:|---|---|---|---|
| 1 | P3-T00 | Done | Repaired the GitHub Actions instrumented matrix with API 36 stable/Pixel 6 and API 37 canary/Pixel Tablet entries, a structural verifier and a separate release gate | Queued dependency PR checks and resolution remain paused |
| 2 | P3-T01 | Done | Replaced the warning-only post-write colour check with a blocking `PreToolUse` guard and deterministic Write/Edit protocol verifier | None |
| 3 | P3-T02 | Done | Retained the official Kotlin IDE formatter without adding ktlint or Spotless | None |
| 4 | P3-T03 | Done | Closed optional plugin evaluation without installation; reconsider only for a concrete workflow need | None |

## Architecture and security rules for the next agent

- Read [docs/architecture.md](docs/architecture.md),
  [docs/threat-model.md](docs/threat-model.md), [DESIGN.md](DESIGN.md) and
  [PRODUCT.md](PRODUCT.md) before changing the corresponding contract.
- Every write must remain a `DomainCommand` through `VaultRepository`.
- Mutations and their outbox operations must remain one transaction.
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
  commit every new record/outbox operation atomically.
- Workflow status writes stay project/Inbox scoped, preserve immutable
  semantic categories and retain at least one active status per category.
  Moving a task between projects maps by semantic category; Undo restores the
  exact prior status ID.
- Milestones stay project-scoped, retain revision metadata and enforce 120
  character names, case-insensitive project uniqueness and a 100-row project
  cap. Deletion and membership restoration must remain atomic with every
  affected task outbox operation.
- Dependency links stay distinct from derived blocking state: `dependencyIds`
  is the durable relation set and `blockedBy` contains only unfinished linked
  tasks. All writes use `SetTaskDependency`, enforce the 100-link cap and
  reject self/transitive cycles before the task, relation and v5 outbox state
  are committed atomically.
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
  repository commands with exact Undo and atomic outbox writes; enforce a
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
- A checksum detects corruption but is not authentication. Task 1.6 must bind
  the full `CloudHeaderIdentity` as AEAD associated data and must not expose
  plaintext or claim cloud integrity until decryption succeeds.
- Preserve `CloudObjectFrame`'s one-shot ciphertext ownership. Ciphertext reads
  from caller-controlled streams may use only bounded scratch storage, never
  the retained verified ciphertext array.
- Keep passphrases as `CharArray` and zero temporary key arrays.
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

Wait for the user's explicit resume. Then continue with Train 1 Task 1.6:
authenticate the completed canonical frames with the independent vault-content
key, bind every header identity field as associated data, verify the checksum
before AEAD decryption, return typed decode failures and freeze independent
golden vectors. Correct the recorded Task 1.3 lint findings before claiming the
train exit gate is green.

`P2-F02` and therefore `P1-L05` remain blocked until the attachment/cloud
design and `P1-D07` exist; do not weaken encrypted attachment requirements to
start them early. Keep queued dependency PR checks and resolution paused unless
the user explicitly resumes GitHub maintenance. Do not start Task 1.6, Drive or
blocked P0 cloud/release work during this pause.
