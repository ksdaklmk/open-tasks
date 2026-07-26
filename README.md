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
- Adaptive task editor for title, description, Inbox/project, priority, due
  date, and estimate, with debounced auto-save, inline validation, save status,
  and Undo feedback.
- Recurring tasks with daily, weekly, monthly, and yearly intervals; weekly
  day selection; count or end-date limits; stable series metadata; DST-safe
  wall-clock scheduling; and deterministic occurrence IDs. Completing a
  recurring task creates its next planned occurrence atomically, while Undo
  reopens the current task and removes only the generated occurrence.
- Inline checklist editing for add, rename, complete, delete, and Undo, plus
  reusable tag assignment and create-and-assign controls. Checklist text and
  tag names participate in universal search.
- Persisted workflow status selection for Backlog, Planned, In progress,
  Blocked, and Done. Status changes are validated against workflow records,
  preserve blocked-task completion warnings, and support exact Undo.
- A 30-day Trash workspace with reversible task deletion, restore, explicit
  permanent-delete confirmation, startup expiry cleanup, and sync tombstones.
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
  dependencies, project progress, trash retention, and time reconciliation.
- Room schema and SQLCipher database factory with atomic task/outbox and
  timer/outbox transactions.
- Random 256-bit database key wrapped by a non-exportable Android Keystore key;
  only the AES-GCM envelope is stored in app-private preferences.
- Tink AES-256-GCM record encryption and Argon2id recovery-key envelopes.
- Hybrid logical clock and field-level merge primitives for Drive operation
  reconciliation.
- Hilt application wiring, Navigation 3, Material 3 Adaptive, edge-to-edge,
  predictive Back, backup exclusions, and notification permission discipline.

Drive authorization and transport remain behind production interfaces so a
Google OAuth client is not required for local development.

The runnable app is deliberately a foundation slice, not the completed
five-phase release. Encrypted task CRUD and core-field editing, project
workbench editing, project creation and Archive/Restore, Trash/restore
commands, search, timers, and the local outbox now persist across process
restarts. Recurrence rules and series metadata persist through the encrypted
Room v1→v2 migration. Reminders, custom per-project workflow editing, Drive
transport, attachments, widgets, import/export, the remaining More subfeatures,
and Play release operations are the next milestones.

## Build

Requirements:

- JDK 21 or the Android Studio bundled runtime
- Android SDK Platform 37 and Build Tools 36.0.0+

```bash
./gradlew testDebugUnitTest
./gradlew :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest
./gradlew lintDebug
./gradlew :app:assembleDebug
```

CI runs the unit/lint/build gate and the four instrumented suites on API 36 and
37 emulators. Local device testing remains required for fold posture, rotation,
split-screen, large text and assistive-technology acceptance.

The app ID is `app.opentasks`, `minSdk` is 36, and both `compileSdk` and
`targetSdk` are 37.

## Project map

- `app` — startup, Navigation 3 host, Hilt wiring, quick add, and search.
- `core/model` — durable IDs, records, recurrence, reports, and UI snapshots.
- `core/domain` — commands, repository contracts, dependency and recurrence rules.
- `core/data` — Room/SQLCipher schema and local fixture repository.
- `core/crypto` — Argon2id recovery envelopes and Tink AEAD.
- `core/sync` — hybrid logical clocks and deterministic merges.
- `core/designsystem` — fixed light/dark palette, typography, shapes, and components.
- `feature/*` — destination-specific Compose UI.

See [PRODUCT.md](PRODUCT.md), [DESIGN.md](DESIGN.md),
[docs/architecture.md](docs/architecture.md),
[docs/threat-model.md](docs/threat-model.md), and [HANDOFF.md](HANDOFF.md) for
the implementation contracts and ordered backlog.
