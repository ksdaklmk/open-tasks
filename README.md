# Open Tasks

Open Tasks is a private, offline-first project and task workspace for solo
professionals. It is an Android-only Kotlin and Jetpack Compose application
that adapts from a compact phone window to foldable and tablet workbenches.

## Current implementation

The repository contains the production foundation and the first local
workspace slice:

- Five-destination adaptive shell: Home, Tasks, Projects, Schedule, and More.
- Compact navigation bar, medium/expanded navigation rail, and responsive
  list/detail task workbench.
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
  search, undo-ready results, immutable `StateFlow` UI state, and a one-time
  sample-workspace seed.
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
- Provider-independent bounded cloud-object frames and an authenticated codec
  that binds complete object identity, plus legacy hybrid logical clock and
  merge primitives retained as well-tested internal foundations.
- Additive encrypted Room v6 backup generations: accepted local mutations
  append ordered canonical journal rows in the same transaction, while every
  legacy outbox row remains preserved in the now read-only legacy table.
- Strict complete-snapshot and operation-segment payload v1 codecs, consistent
  one-transaction capture, and verified encrypted current/previous/segment
  recovery objects under `noBackupFilesDir`.
- A single-flight local backup coordinator that performs authenticated
  readback and source comparison before checkpointing, retains recovery bases,
  and leaves local editing/journal rows intact on failure. Application
  lifecycle wiring now coalesces journal changes and resumes pending work.
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
- UK English is the fixed application locale, including UK spelling,
  day–month dates, 24-hour times and Bin terminology.

The approved programme now keeps encrypted Room as the sole live authority.
[HANDOFF.md](HANDOFF.md) is the single authoritative checkpoint, completed
implementation history, and dependency-ordered backlog. Train 0 committed the
verified P1/P2 baseline, and Train 1 Tasks 1.1–1.5 remain accepted historical
evidence.

Stage 2 local backup and the supplementary Android package are implemented.
Room remains the sole live authority; local generations produce authenticated,
verified recovery objects and one prepared portable package. Android Auto
Backup and device transfer may copy only that exact encrypted file. Package
readiness does not prove an Android upload, and a real encrypted Google
transport upload/restore remains external qualification. Google authorisation,
Drive transport, restore activation, writer takeover, remote merge, and
attachments are not operational.

The runnable app is deliberately a foundation slice, not the completed
six-stage release. Encrypted task CRUD and core-field editing, project
workbench editing, project creation and Archive/Restore, Bin/restore
commands, search, timers, manual time history, and the local backup journal now
persist across process restarts. Recurrence rules, series metadata, reminders and
project workflows persist through the encrypted Room store. Transient route,
selection, filter, scroll and draft context is also restored without
overwriting unsaved editor text on the first repository emission. Stage 2 has
copied the preserved legacy outbox into local backup-journal format without
deleting the original rows. Drive transport, recovery activation, attachments,
widgets, import/export, the remaining More subfeatures, and Play release
operations are future milestones.

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
  :feature:more:connectedDebugAndroidTest
./gradlew lintDebug
./gradlew :app:assembleDebug
```

CI runs the unit/lint/build gate and the six instrumented suites on API 36 and
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
- `core/sync` — bounded provider-independent object formats and legacy,
  non-product merge primitives.
- `core/designsystem` — fixed light/dark palette, typography, shapes, and components.
- `feature/*` — destination-specific Compose UI.

See [PRODUCT.md](PRODUCT.md), [DESIGN.md](DESIGN.md),
[docs/architecture.md](docs/architecture.md),
[docs/threat-model.md](docs/threat-model.md), and [HANDOFF.md](HANDOFF.md) for
the implementation contracts and ordered backlog.
