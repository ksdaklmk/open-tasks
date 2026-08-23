# Open Tasks

Open Tasks is a private, offline-first project and task workspace for solo
professionals. It is an Android-only Kotlin and Jetpack Compose application
that adapts from a compact phone window to foldable and tablet workbenches.

## Current implementation

The repository contains the production offline-first workspace:

- Five-destination adaptive shell: Home, Tasks, Projects, Schedule, and More.
- Compact navigation bar, medium/expanded navigation rail, and responsive
  list/detail task workbench.
- A fresh-vault Welcome surface with equal-reach offline, optional Google
  backup/recovery, and portable-device restore actions. Provider discovery is
  explicit and an offline vault starts with no demonstration or user records.
- Real date-derived Schedule views: a compact selected-day agenda and an
  expanded Monday–Sunday timeline with reminder context and an open-only
  unscheduled tray.
- Adaptive task editor for title, description, Inbox/project, priority, due
  date, and estimate, with debounced auto-save, inline validation, save status,
  and Undo feedback.
- Recurring tasks with daily, weekly, monthly, and yearly intervals; weekly
  day selection; count or end-date limits; stable series metadata; DST-safe
  wall-clock scheduling; and deterministic occurrence IDs. Completing a
  recurring task creates its next planned occurrence atomically, while Undo
  reopens the current task and removes only the generated occurrence.
- One due-relative reminder per task with flexible or precise delivery,
  in-context notification/special-access prompts, idle-safe exact-alarm
  fallback, private lock-screen content, task deep links, Snooze and Complete
  actions. Recurring occurrences inherit reminder lead time.
- Inline checklist editing for add, rename, complete, delete, and Undo, plus
  reusable tag assignment and create-and-assign controls. Checklist text and
  tag names participate in universal search.
- Independent persisted workflows for every project and Inbox. Project
  workflows support add, rename, explicit reordering, archive and restore while
  retaining immutable semantic reporting categories, assigned tasks, blocked
  completion warnings and exact Undo.
- Project milestones support create, rename, due-date editing, complete,
  reopen and confirmed deletion with exact Undo. Tasks can join one milestone
  from their current project; cross-project membership is rejected and project
  moves clear or exactly restore membership.
- Searchable dependency editing supports add/remove Undo, a 100-link bound,
  transitive cycle rejection, completed-prerequisite resolution and named
  blocked-completion confirmation across task entry points.
- Reusable project templates capture active workflow stages, open milestones
  and open task structure. Relative zoned dates shift from a chosen anchor,
  relationships are deterministically remapped, and completed/Bin history is
  excluded.
- A 30-day Bin workspace with reversible task deletion, restore, explicit
  permanent-delete confirmation, startup expiry cleanup, and durable
  tombstones.
- An adaptive Project Workbench with persisted name, summary, health, and due
  date editing; live progress and workflow counts; milestone context; project
  task links; exact Undo; and Home/Search deep-link continuity.
- Context-aware project creation plus recoverable project Archive/Restore.
  Archiving hides a project from active project views and universal project
  search while preserving every assigned task, milestone, and history record.
- SQLCipher-backed `VaultRepository` with typed commands, immediate updates,
  search, undo-ready results, immutable `StateFlow` UI state, and a structural
  production seed; sample workspaces remain explicit test fixtures.
- Domain rules for workflows, recurrence, deterministic occurrences,
  dependencies, project progress, Bin retention, and time reconciliation.
- Room schema and SQLCipher database factory with atomic task, timer, and
  time-entry backup-journal transactions.
- Task-scoped manual time entry with exact add/edit/delete Undo, bounded notes,
  persisted history and explicit overlap review rather than silent duration
  loss.
- Random 256-bit database key wrapped by a non-exportable Android Keystore key;
  only the AES-GCM envelope is stored in app-private preferences.
- Tink AES-256-GCM record encryption and Argon2id recovery-key envelopes.
- Provider-independent bounded cloud-object frames and authenticated codecs
  that bind complete object identity for encrypted backup and recovery.
- Additive encrypted Room v6 backup generations: accepted local mutations
  append ordered canonical journal rows in the same transaction, while every
  legacy outbox row remains preserved in the now read-only legacy table.
- Strict complete-snapshot and operation-segment payload v1 codecs, consistent
  one-transaction capture, and verified encrypted current/previous/segment
  recovery objects under `noBackupFilesDir`.
- A single-flight local backup coordinator that performs authenticated
  readback and source comparison before checkpointing, retains recovery bases,
  and leaves local editing/journal rows intact on failure. Application
  lifecycle wiring coalesces journal changes, binds each run to its exact
  start generation, and resumes newer pending work.
- A verified recovery-passphrase envelope and atomically replaced portable
  package no larger than 24 MiB. More reports only local package facts and
  preserves Android-restored packages as inert Stage 3 recovery input.
- Hilt application wiring, Navigation 3, Material 3 Adaptive, edge-to-edge,
  predictive Back, notification permission discipline, and exact Android
  backup/device-transfer rules that include only
  `android_backup/open_tasks_portable_v1.otb`.
- Process restoration for the top-level route, selected task/project, task
  filters, list/editor scroll, quick-add/search and editor drafts. Active
  timers resume from their encrypted Room time entry and original start time.
- Insights export of one bounded, self-contained executive HTML dashboard.
  Aggregate output is the default, task titles are opt-in, remote assets are
  forbidden, and download/share both disclose that the file is plaintext.
- UK English is the fixed application locale, including UK spelling,
  day–month dates, 24-hour times and Bin terminology.

Encrypted Room remains the sole live authority.
[HANDOFF.md](HANDOFF.md) is the single authoritative checkpoint, completed
implementation history, and dependency-ordered backlog. Local encrypted
generations, portable packages, create-only Google Drive backup/recovery,
recovery activation, attachments, widgets, import/export, reminders, planning
surfaces, and executive reporting are implemented. Google remains optional
and backup/recovery-scoped; release qualification still requires the explicit
owner-present provider gate.

The five validated security candidates are remediated and pushed, and both
post-fix scans report zero findings. Security runs `32615516366`,
`32617307907`, and exact-head `32617911327` are green. Android run
`32615516358` passed verify, release/SBOM,
and benchmark, then exposed a test-only API 36 timer-observation race; that test
was corrected in `316bd86`. The expanded preview API 37 suite remains
infrastructure-red after the emulator lost core Android services before any app
assertion. Its `--no-parallel` experiment did not serialize UTP work and was
removed in `afb1d93`; no serialization proof is claimed. The three-APK gate now
also checks the exact packaged ABIs in `c649801`, but real owner-certificate
proof, physical fixed-API-36 arm64 performance and fresh-install evidence,
owner-present Google restore, and manual browser/accessibility checks remain
pending. Resume from
[HANDOFF.md](HANDOFF.md) and record release evidence in
[docs/qualification/onboarding-dashboard-nfr-acceptance.md](docs/qualification/onboarding-dashboard-nfr-acceptance.md).

## Build

Requirements:

- JDK 21 or the Android Studio bundled runtime
- Android SDK Platform 37 and Build Tools 36.0.0+

```bash
./gradlew testDebugUnitTest
./gradlew :core:data:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest \
  :feature:home:connectedDebugAndroidTest
./gradlew lintDebug
./gradlew :app:assembleDebug
```

CI runs the unit/lint/build gate and the seven instrumented suites on API 36 and
37 emulators. Local device testing remains required for fold posture, rotation,
split-screen, large text and assistive-technology acceptance.

Run the App suite only on a disposable emulator: AGP uninstalls
`app.opentasks`.

The app ID is `app.opentasks`, `minSdk` is 36, and both `compileSdk` and
`targetSdk` are 37.

Format Kotlin with the official Kotlin IDE formatter built into Android
Studio. The baseline intentionally adds neither ktlint nor Spotless.

## Project map

- `app` — startup, Navigation 3 host, Hilt wiring, quick add, and search.
- `core/model` — durable IDs, records, recurrence, reports, and UI snapshots.
- `core/domain` — commands, repository contracts, dependency and recurrence rules.
- `core/data` — Room/SQLCipher schema and local fixture repository.
- `core/crypto` — Argon2id recovery envelopes and Tink AEAD.
- `core/sync` — bounded provider-independent encrypted object formats.
- `core/designsystem` — fixed light/dark palette, typography, shapes, and components.
- `feature/*` — destination-specific Compose UI.

See [PRODUCT.md](PRODUCT.md), [DESIGN.md](DESIGN.md),
[docs/architecture.md](docs/architecture.md),
[docs/threat-model.md](docs/threat-model.md), and [HANDOFF.md](HANDOFF.md) for
the implementation contracts and ordered backlog.
