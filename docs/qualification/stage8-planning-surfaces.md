# Stage 8 planning-surfaces qualification

## Status and source identity

**Locally qualified candidate — remote CI and tag pending.** The exact-head
local gates, scope audits, whole-stage review, six-module connected gate,
manual acceptance matrix, signed smoke, and secure cleanup are complete with
zero open Critical or Important findings. Remote GitHub Free CI for the
candidate and the annotated `v1.2.0` tag do not exist yet and are recorded
below as pending; nothing in this record is evidence from a future commit.

- Date: 16 August 2026
- Implementation base (`stage8BaseSha`):
  `8047f136541d22b15ca20db8971ea67685e250b5`
- Final reviewed `implementationHeadSha` and product source commit:
  `b6c438f312b40f228e1d19365062debe479054e7`
- Version-bump commit inside that range:
  `79afd979a494b3fd903acad8616af99df4123400` (`build: set version 1.2.0`),
  the sole `app/build.gradle.kts` delta
- Candidate version: versionName 1.2.0, versionCode 3

The implementation head is deliberately the last product/test commit, not the
later qualification-document commit. Stage 7's explicit waivers (app lock,
secure cleanup, GitHub CI) do **not** carry into Stage 8.

This record contains no account identifier, device serial, process id,
credential, private task text, record UUID, Drive object id, or other private
identifier. The protected `Pixel_10_Pro_Fold` AVD was never booted, installed
to, or mutated during this qualification.

## Delivered boundary

Stage 8 delivers the planning surfaces described in
`docs/superpowers/specs/2026-08-14-stage-8-planning-surfaces-design.md`:

- **Month projection.** A pure Monday-first 42-cell month projection with
  six-dot per-day density (`6+` overflow) and exact count semantics; the
  projection is total and contains no clock, zone, or repository access.
- **Exact single-task rescheduling.** `SetTaskSchedule` owns one atomic
  task-schedule plus reminder mutation in a single transaction and a single
  journal generation, appending ordered `TASK`-then-`REMINDER` entries, with
  repository-produced Undo. `UpdateTask` is start-aware. The task editor gains
  start/due controls with 09:00 and 17:00 defaults.
- **Drag and its fallback.** The Board-proven root drag primitives are
  extracted to `:core:designsystem`, and Week/Month pointer drag is layered
  over a complete 48 dp tap/menu fallback that stays independently sufficient.
  An undated task dropped on a day becomes due at 18:00 in the current device
  zone; DST correctness defers to the Java gap/overlap authority; spans move by
  stored-zone `plusDays`.
- **Project Timeline.** A pure, bounded 84-day (12-week) Monday-first Timeline
  projection with dot-run spans, milestone diamonds, and explicit clipped,
  outside, invalid, and unscheduled states, plus bounded transitive dependency
  context.
- **Saved project view state.** Per-project LIST/BOARD/TIMELINE presentation, a
  Monday-only Timeline anchor, and Timeline selection live in
  `SavedStateHandle` with fail-closed decoding and legacy board-boolean
  migration.
- **Private daily digest.** Opt-in, device-local `daily_digest` preferences
  holding exactly `enabled`, `minute_of_day`, and `last_handled_epoch_day`
  (Boolean/Int/Long only, fail-closed); a wall-time next occurrence bounded by
  the handled day; exactly one inexact `setAndAllowWhileIdle` alarm behind a
  single stable immutable broadcast `PendingIntent`; a delivery order of
  mark-handled → re-arm → vault lookup → post under one mutex; a counts-only
  private notification from `computeTodayProjection(titlesPermitted = false)`
  with a generic public version on its own `daily_digest` channel; a
  non-exported, no-filter `DailyDigestReceiver` as the sole manifest delta; an
  inline More setting using the native 24-hour `TimePickerDialog`; foreground
  reconciliation in `MainActivity`; and `OPEN_DAILY_DIGEST_HOME` routing to
  Home through consumed navigation signals.

Room stays at v9 and the authenticated backup object format stays v1. No
migration, backup family, schema, permission, dependency, route, manifest
component beyond the digest receiver, or Drive scope was added.

## Toolchain and platform

| Component | Version |
|---|---|
| Gradle | 9.6.1 |
| Android Gradle Plugin | 9.3.1 |
| Kotlin | 2.3.21 |
| Gradle daemon JDK | 21 |
| Java source/target | 17 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 36 |
| Room | 2.8.4; schema remains v9 |
| Hilt | 2.60.1 |
| Glance AppWidget | 1.1.1 |

## Review disposition

Tasks 1–14 were each independently reviewed at their own commits; every
Critical or Important finding was fixed and scope re-reviewed before the next
task started.

Two later repairs were reviewed as a scoped range, `5fbb24b..94c42f7`:

| Commit | Change | Disposition |
|---|---|---|
| `c495dd2c633e95d30fe2d9f21b613bfd92f82788` | `fix: consume quick add trigger on close` — the cancelled Quick Add sheet is consumed at the single `closeQuickAdd()` boundary through a new `onQuickAddClosed` callback; `MainActivity` resets the signal and prefill, removes the legacy extra, and clears only a matching QUICK_ADD, SEND, or PROCESS_TEXT action. | **APPROVED** — this repairs the earlier signed-smoke release blocker. |
| `94c42f7d1f472eefe6639eca49216ceaafd678cc` | `test: clean owned backup objects in continuity fixture` — androidTest only; teardown deletes the owned local backup root, the storage baseline refuses pre-existing backup objects, and the post-Active alias wait owns its deadline. | **APPROVED** — fixture-side defect, product fail-closed behaviour was correct. |

That scoped review returned spec compliance PASS, quality PASS, **0 Critical**,
one Important (document staleness, since discharged), and three deferred
Minors.

The whole-stage independent review then read the complete validated
`8047f136..b6c438f` range — a 13,908-line diff read in full, plus HEAD-state
tracing of the `MainActivity`/`OpenTasksApp` signal system — and returned
**Ready to tag with fixes: 0 Critical, 1 Important**.

| # | Finding | Disposition |
|---|---|---|
| I1 | Contract documentation at HEAD still described the Task 9 and Task 13 era across `CLAUDE.md`, `DESIGN.md`, `PRODUCT.md`, `docs/architecture.md`, and `docs/threat-model.md`. | **Discharged by this documentation refresh** and its companion records. |

Scope audit, dual-engine parity, privacy, spec conformance, and test integrity
all PASS, each re-verified independently against git.

**Zero Critical and zero Important findings are open.**

### Deferred Minors

Forty-three non-blocking Minors are recorded in the ignored execution ledger
(`.superpowers/sdd/2026-08-14-stage-8-planning-surfaces-plan/progress.md`):
thirty-nine carried from Tasks 1–14, the whole-stage review, and the scoped
repair review, plus four added by the manual acceptance matrix in this
qualification:

1. At `font_scale 2.0` the sixth Month row compresses to 51 × 42.7 dp, below
   the 48 dp guideline height while remaining tappable and correctly labelled.
2. In the compact single-pane Schedule the Quick Add floating action button
   overlaps the last visible agenda row's schedule-actions control; scrolling
   the agenda by one row frees it.
3. Relaunching immediately after a consumed digest tap lands on Home — the
   already-recorded residual that `onCreate` replays `handleIntent` from the
   last `setIntent`, shared by all three intent consumers.
4. The outside-window milestone summary has no singular form
   (`1 milestones before this window`).

None is blocking; the ledger, not this record, is their authority.

## Exact-head automated qualification

All gates below were re-run against the final reviewed head `b6c438f`.

| Gate | Result |
|---|---|
| Six Android-test source compiles | PASS — all six `compileDebugAndroidTestKotlin` targets green (219 tasks). |
| Forced-fresh JVM/lint/debug gate | PASS — `./gradlew testDebugUnitTest lintDebug :app:assembleDebug --rerun-tasks`: BUILD SUCCESSFUL in 5m03s, **553/553 actionable tasks executed**, **1,304 JVM tests across 127 suites, 0 failures, 0 skips**. |
| Forced signed release | PASS — `./gradlew :app:assembleRelease --rerun-tasks`: BUILD SUCCESSFUL in 4m28s, 442/442 tasks; `scripts/verify-release-apk.sh` reported all checks passed for a signed 1.2.0 (3) APK of 16,372,751 bytes, SHA-256 `c81fa17da3c940b4719523be53df4b021d56b81e3c60e31e6e22f357af3c3f17`. |
| Room/fixtures/workflow | PASS — see the audit section below. |
| Base-to-head privacy and release scope | PASS — `STEP7-ALL-GREEN`; see the audit section below. |
| Six-module connected gate | PASS — 453 tests, 0 failures, 0 errors, exactly the two established skips; see the connected-gate section. |

Focused per-task gates remained green throughout the stage and are recorded in
the ignored ledger; representative runs include the Task 4 schedule-command
suite (69 focused JVM tests), the Task 12 digest-preference and occurrence
suite (11/11), and the Task 13 delivery-ordering suite (16/16, observing the
real preference write through a listener).

## Dual-engine and journal evidence

`SetTaskSchedule` is implemented identically in `RoomVaultRepository` and
`InMemoryVaultRepository`, and the two suites carry the same thirteen named
schedule cases with exactly matching sorted method-name sets.

- **Atomicity and generation.** A changed success advances the task revision
  once and appends exactly one journal generation. The in-memory success
  asserts the journal families are exactly `TASK, REMINDER` at sequence indexes
  `[0, 1]`. The Room success closes and reopens the encrypted database, then
  asserts the persisted state plus one generation whose canonical changed
  families are exactly `[TASK, REMINDER]`.
- **Undo.** Undo is repository-produced and returned in
  `CommandResult.Success`; the Room case executes and verifies the exact Undo
  command, and both engines prove exact restoration of a past reminder and its
  recurrence metadata, with the restore flag bypassing only the
  future-reminder check.
- **Rejection atomicity.** Every rejection and no-op case captures the complete
  repository snapshot, target revision, journal generation, journal list and
  rows, and activity before execution and asserts them unchanged afterwards.
  Rejections cover mismatched reminder identity, a reminder without a due date,
  recurrence without a schedule, mutually exclusive count and end date, an end
  before the stored-zone schedule date, due before start, a changed past
  reminder, and completed or binned targets.
- **Short circuit.** The unchanged-past-reminder no-op returns success without
  target validation, revision change, writes, journal entries, activity, or
  Undo.
- **Caller boundary.** `SetTaskSchedule` constructions are confined to tests
  and repository-created Undo commands; no UI or app-layer construction exists.
  Every `UpdateTask` construction supplies an explicit `start` (the declaration
  has no default), and the editor still emits one debounced `UpdateTask` — no
  split-save protocol was introduced.
- **Device parity.** The Room instrumented parity proof — restart, one
  revision, one ordered `TASK`/`REMINDER` generation, rejection atomicity, and
  exact Undo — executed on device in the connected gate: `:core:data`
  192/192 passing, zero skips.

## Schema, fixture, workflow, privacy, manifest, preference, and Drive audits

All no-durable-change gates passed at the final reviewed head.

| Audit | Result |
|---|---|
| `scripts/check-schema-drift.sh` | PASS — clean at Room schema v9; no exported schema JSON changed. |
| Deterministic fixtures | PASS — all five fixture generators regenerated byte-identically; `git diff --exit-code core/data/src/test/resources` was empty after every generator. |
| `scripts/verify-actions-workflow.sh` | PASS — SHA pinning and CI matrix shape intact. |
| `git diff --check` | PASS — clean. |

The exact `8047f136..b6c438f` scope audit returned **STEP7-ALL-GREEN** across
85 changed files:

- **Logging and telemetry.** Zero added `android.util.Log`, `Log.`, `println(`,
  or `Timber.` call sites in production `src/main` sources anywhere in the
  range.
- **Durable data.** `core/data/schemas`, `BackupRecordV1.kt`,
  `app/src/main/res/xml/backup_rules.xml`, and
  `app/src/main/res/xml/data_extraction_rules.xml` are unchanged.
- **Build surface.** `gradle/libs.versions.toml` and every `*.gradle.kts` other
  than `app/build.gradle.kts` are unchanged; the app build delta is
  version-only (`versionCode` / `versionName`), so no dependency was added.
- **Manifests.** Thirteen manifests exist; twelve are byte-identical and only
  the app manifest changed. Its delta is exactly three added lines and zero
  removed lines: one `<receiver>` element naming `DailyDigestReceiver` with
  `android:exported="false"` and no intent filter. The `<uses-permission>`
  set is byte-identical to the base, and existing exported components,
  filters, providers, and services are untouched.
- **Network and cloud.** No added `https?://`, `DRIVE_ORIGIN`, `BASE_URL`,
  `baseUrl`, or `endpoint` string, and no added `drive.appdata` reference.
  `drive.appdata` remains the sole Drive authorization scope, the transport
  remains create-only, and no bidirectional sync path exists.
- **Digest preferences.** The `daily_digest` store uses `MODE_PRIVATE` and
  exactly the three keys `enabled`, `minute_of_day`, and
  `last_handled_epoch_day`, written only as Boolean, Int, and Long. There is
  no `putString`, `putStringSet`, or `putFloat` on that boundary, and raw-type
  validation over `prefs.all` rewrites any malformed state fail-closed to
  disabled at 08:00, retaining only a valid handled day. No task title,
  project, count, zone, payload, vault material, or scheduled instant is
  persisted there; `view_prefs` likewise remains enum names and project ids
  only.
- **Digest content privacy.** The notification plan calls
  `computeTodayProjection` once with `titlesPermitted = false` and carries
  counts only. The public (lock-screen) version is generic with no counts, and
  the private version is posted on the dedicated `daily_digest` channel.

One recorded harness ruling applies to this audit: the plan's Step 7 script
used `rg -c`, whose zero-match output is empty rather than `0`, so the
no-deletion-lines assertion errored mechanically. The controller patched the
harness expression without weakening any assertion; the recorded manifest
delta independently shows zero deletions. A second recorded ruling narrows the
no-new-`Log` check to production sources: the only added `Log` lines in the
whole range are content-free lines in the test-only window-hiding guard.

## Connected gate

The six-module connected gate ran on the sole audited `Fold8_Acceptance`
disposable overlay, booted `-read-only -no-snapshot-load -no-snapshot-save
-no-window -gpu host`, verified as the only ADB target, confirmed by
`adb emu avd name`, unfolded to 2160x1856 @420 with `mTopFocusedDisplayId=0`,
and preceded by a passing display-0 activity canary.

```bash
./gradlew :app:connectedDebugAndroidTest \
  :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest
```

| Module | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| `:app` | 92 | 0 | 0 | 2 |
| `:core:data` | 192 | 0 | 0 | 0 |
| `:feature:tasks` | 48 | 0 | 0 | 0 |
| `:feature:projects` | 30 | 0 | 0 | 0 |
| `:feature:schedule` | 23 | 0 | 0 | 0 |
| `:feature:more` | 68 | 0 | 0 | 0 |
| **Total** | **453** | **0** | **0** | **2** |

BUILD SUCCESSFUL in 10m25s, 455 actionable tasks. The two skips are exactly
the established pair, verified from the authoritative XML: the credentialed
Drive qualification row and the cross-display fold-continuity harness
exception. No other skip, failure, or error occurred.

Two carried device-proof obligations are discharged by this run:

- `expandedWeekDragMovesDatedTaskBetweenDays` passed on the wide-window leg,
  as expected for a layout that reserves a 280 dp unscheduled tray.
- The Task 4 Room instrumented helper's `ByteArray` referential-equality
  comparison did not false-fail: `:core:data` is 192/192.

Instrumented sources for Tasks 9–14 were compile-verified only during
implementation; this gate is their first execution and it is green. The
carried digest and drag device proofs — channel properties, receiver export
scope, notification public/private split, delivery-intent identity, and the
Espresso `TimePicker` dialog leg — all executed.

An earlier attempt at this gate produced one bounded `AppNotIdleException` in
`ScheduleScreenInstrumentedTest`. It was diagnosed from the preserved logcat
as environmental and required no repository edit: a Google Play Store
self-update was AOT-compiling (`dex2oat64`, "Large app") for 78 seconds across
that test's 60-second Espresso window, with Play/dexopt log volume spiking
from a 0–219 lines/minute baseline to 5,689 in the failing minute and the app
process recording a 704 ms frame. The rerun above disabled the Play client on
the disposable overlay and gated start-up on a dexopt quiesce check; its
post-run logcat contains zero `dex2oat` lines, zero `Davey!` frames, and zero
`AppNotIdleException` across 176,043 lines, and the wall clock returned to the
historical ~10.5 minutes.

## Manual acceptance matrix

Executed on the same audited disposable overlay against the debug build at the
reviewed head, recording no private task text, device identifier, vault or
Drive id, or credential. Fixture titles are controller-authored.

| # | Row group | Result |
|---|---|---|
| 1 | Schedule surfaces | PASS — Week/Month switching; exactly 42 Monday-first cells (Mon 27 Jul – Sun 6 Sep) with selectable muted adjacent dates; Previous/Today/Next; selected-day agenda with correct singular and plural counts; completed work visible and counted; open-only tray; six dots with a `6+` overflow at seven tasks; labelled overdue markers; merged cell semantics stating the full date and exact total, completed, and overdue counts. |
| 2 | Rescheduling | PASS — all four source shapes including the 18:00 undated rule; stored-zone time preserved on dated moves; an in-process device-zone replacement producing 18:00 in the replacement zone; start+due spans; the DST gap proved discriminatingly (two tasks on 8 March 2026: the `Asia/Bangkok`-stored task keeps 02:30, the `America/New_York`-stored task shifts to 03:30); due-relative reminder at the correct lead; past-reminder rejection with actionable copy; recurring day move preserving series metadata; recurring tray rejection; reminder-removal confirm and cancel; Undo; same-source and outside-target snapback without a callback; compact fallback; expanded Week and Month pointer drag; RTL mirroring with a working RTL drag. |
| 3 | Task editor | PASS — add, edit, and clear start; explicit start and due times through the native 24-hour picker; 09:00 and 17:00 defaults stated and applied; existing times preserved across date changes; `Due cannot be before start.` shown and cleared; one autosave observed to persist. |
| 4 | Projects | PASS — independent per-project LIST/BOARD/TIMELINE with restoration across project switches and process recreation; the 84-day Monday window `10 Aug 2026 to 1 Nov 2026`; exact four-week navigation and Today; live re-anchoring under a replacement device zone; in-window spans with exact day counts; before-window, unscheduled, completed, and blocked states; 48 dp milestone diamonds; exact outside-window milestone counts; separate row selection and Open; transitive prerequisite highlighting and the external chain count; unchanged Board; non-colour semantics throughout; no bar or diamond drag. |
| 5 | Digest behaviour | PASS — off by default at 08:00; a near-future 24-hour time arming exactly one inexact allow-while-idle `RTC_WAKEUP` broadcast alarm; exactly one post; zero-count silence; disable recording `alarm_cancelled`; re-enable arming the next day without duplicating; denied `POST_NOTIFICATIONS` retaining the setting and surfacing `Turn on notifications`; and reboot plus two forward wall-clock date changes all retaining the same local wall time. |
| 6 | Digest privacy | PASS — private shade content `Today` / `<n> open today · <n> overdue` on the `daily_digest` channel at `vis=PRIVATE`; generic public version `Daily digest` / `Open Open Tasks to view it` with no counts and `contentIntent=null`; no task title in either version; and a tap that reaches Home only after the app-lock overlay when locked. |
| 7 | Fold and continuity | PASS for rotation and process recreation — Month, selected date, per-project Timeline anchor, and the digest preference all survive; every interactive target stays reachable at 200% text. **Native fold posture remains environment-blocked** on this AVD, unchanged from the recorded Stage 8 finding, and no fold claim is made. |

Signed-sideload smoke rows and the Stage 8 extras are recorded in
`docs/qualification/release-1.2.0-sideload.md`. No Stage 7 waiver is
inherited; row 5 of the sideload checklist (immediate app lock, background past
the delay, unlock) was executed for this candidate.

## Secure cleanup

Complete. The single temporary exported `.otvault` was deleted through its
owning provider and the Downloads directory verified empty; the temporary
device screen credential was cleared and re-read as `CredentialType: NONE`; the
application package was uninstalled, leaving zero digest alarms and zero
notification records; no controller-created credential helper file was ever
written. The audited AVD was then killed through `adb emu kill` against its
recorded pid and serial, reporting `OVERLAY-STOPPED-CLEAN`. The final host
audit shows no ADB target, no disposable emulator process, and no
`/tmp/opentasks-stage8.*` directory. Only the four preserved user-owned dirty
paths remain in `git status`. The protected `Pixel_10_Pro_Fold` AVD is out of
scope and was never touched. Physical-device installation is owner-controlled
and outside this task.

## Remote CI and tag — pending

- **GitHub Free CI for this candidate: pending.** No candidate run has been
  dispatched or observed. The standing project residual is currently active:
  the most recent push run reported every job failing in two to four seconds
  with zero executed steps and the annotation "The job was not started because
  recent account payments have failed or your spending limit needs to be
  increased." Until that account condition is resolved no candidate run can
  exist, and none is claimed here.
- **Tag `v1.2.0`: pending.** The annotated tag does not exist. Nothing in this
  record is derived from a future, self-referential qualification commit; the
  implementation SHA above is the last product/test commit and remains the only
  source identity claimed.
- The expanded API 37.0 canary lane remains observe-only.

## Closure and residuals

Stage 8 changes no durable Room schema, backup-record schema or family, frozen
`.otvault` format, cloud transport, Drive scope, permission, dependency, route,
or live-data authority. Encrypted Room stays the sole live structured-data
authority. The one added manifest component is a non-exported, no-filter
broadcast receiver; the one added preference namespace is device-local,
strictly typed, fail-closed, and free of task content.

Standing residuals carried out of this stage:

- forty-three deferred Minors in the ignored execution ledger, none blocking,
  including three pre-existing navigation and dialog-lifecycle residuals that
  predate Stage 8;
- one un-executed signed-smoke row (Today widget placement), blocked by
  headless-automation limits rather than by observed behaviour and recorded as
  such in the release record;
- native fold posture, which this AVD cannot present;
- the repository-wide Unicode tag-identity normalization policy, still deferred
  from Stage 7;
- GitHub Actions availability, which remains an external account condition; and
- Play Console, store listing assets, and store distribution, which remain
  outside the sideload-only boundary.

There are **zero open Critical and zero open Important findings**.
