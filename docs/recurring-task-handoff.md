# Recurring-Task Implementation Handoff

## Status

Recurring-task support is substantially implemented. The feature supports:

- Daily, weekly, monthly, and yearly recurrence.
- Intervals, weekly day selection, occurrence-count limits, and end dates.
- Stable series IDs, original wall-clock anchors, and occurrence indexes.
- DST-safe local-time scheduling and deterministic occurrence IDs.
- Atomic completion of the current task and creation of the next planned
  occurrence.
- Completion Undo that reopens the current task and removes only the generated
  occurrence.
- SQLCipher/Room persistence and a non-destructive Room v1-to-v2 migration.
- A Material 3 inline repeat editor with accessible 48 dp controls.
- A repeat icon in task rows.

No commit has been created.

## Current stopping point

The final code change preserves recurrence-series metadata when Undo restores a
previous repeat rule. This prevents an edited generated occurrence from losing
its original series ID, anchor, or occurrence index when its rule update is
undone.

The change was added but not compiled or regression-tested before the work was
paused. Start with:

```bash
./gradlew :core:data:compileDebugKotlin :app:compileDebugKotlin
```

The relevant files are:

- `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`

The `DomainCommand.UpdateTask` command now carries optional
`RecurrenceSeriesMetadata`. Repository-generated Undo commands populate it;
normal UI updates leave it absent and derive metadata through
`RecurringTaskPlanner`.

## Regression test still to add

Add this case to `InMemoryVaultRepositoryTest` and, if practical,
`RoomVaultRepositoryInstrumentedTest`:

1. Configure a task with a monthly recurrence.
2. Complete it, producing occurrence index `1`.
3. Update that generated occurrence to a different recurrence rule.
4. Execute the update Undo command.
5. Verify that the original rule, due date, `recurrenceSeriesId`,
   `recurrenceAnchor`, and `recurrenceOccurrenceIndex` are restored exactly.

This specifically guards the final metadata-preservation change.

## Completed implementation files

### Domain and model

- `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt`
  - Added `recurrenceSeriesId`, `recurrenceAnchor`, and
    `recurrenceOccurrenceIndex` to `Task`.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/RecurringTaskPlanner.kt`
  - New series metadata and next-occurrence planner.
- `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
  - Added recurrence to task updates and generated-occurrence support to
    status restore commands.
- `core/domain/src/test/kotlin/app/opentasks/core/domain/RecurringTaskPlannerTest.kt`
  - Covers month-end behavior, DST, deterministic IDs, reset occurrence state,
    count limits, and end dates.

### Persistence

- `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/db/EntityMappers.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
  - Room database version is now `2`.
  - Migration `1 -> 2` adds the four nullable recurrence-series columns and
    updates the stored vault schema version.
- `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/2.json`
  - Generated Room schema snapshot.
- `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
  - Completion creates a next occurrence when allowed by the rule.
  - Undo removes/tombstones only the generated occurrence.
  - Task operation payload format changed to `v2` to include recurrence data.

### UI

- `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
  - Inline repeat controls are in the Planning section:
    - None, Daily, Weekly, Monthly, Yearly.
    - Numeric interval.
    - Weekly day chips.
    - Never, After count, or On date end modes.
    - Inline validation and a dynamic recurrence summary.
  - Due date is required to enable recurrence.
  - Clearing the due date clears the repeat selection.
- `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
  - Wires recurrence through the app command.
- `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/Components.kt`
  - Shows a repeat icon for recurring task rows.

### Tests and documentation

- `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`
- `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`
  - Includes encrypted persistence, restart, Undo, re-completion, and manual
    migration coverage.
- `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskEditorInstrumentedTest.kt`
  - Covers building and auto-saving a weekly recurrence.
- `README.md`
- `docs/architecture.md`

## Checks that passed before the final metadata patch

- Recurrence engine and planner unit tests.
- In-memory repository test suite.
- Encrypted Room recurrence completion, restart, and Undo test.
- Compose recurrence-builder instrumented test.
- Manual Room migration instrumented test.
- Real installed-app v1-to-v2 encrypted database migration.
- Fold main-screen visual review of collapsed and expanded recurrence controls.

During visual QA, the existing `Finish launch proposal` task was temporarily
set to Weekly, then restored to None. Its final UI hierarchy showed the repeat
controls collapsed again, confirming the temporary recurrence was removed.

## Required verification sequence

After compiling the final metadata patch and adding its regression test, run:

```bash
./gradlew :core:domain:testDebugUnitTest :core:data:testDebugUnitTest
```

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest
```

Then run the wider project checks:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleRelease
```

Finally install the verified debug build:

```bash
./gradlew :app:installDebug
```

Do not uninstall the installed app or clear emulator data. The emulator's
existing workspace has already been migrated successfully from v1 to v2.

## Remaining tasks

### Must finish before accepting recurring tasks

- [ ] Compile the final recurrence-metadata Undo patch.
- [ ] Add the regression test for changing a generated occurrence's rule and
  then undoing that change.
- [ ] Run the unit, instrumented, lint, and release-build commands listed
  above.
- [ ] Reinstall the final verified debug build without clearing emulator data.
- [ ] Manually confirm recurrence survives process death, app restart,
  rotation/resizing, and fold/unfold.
- [ ] Manually test each rule form: daily, weekly, monthly, yearly; interval
  greater than one; weekly multiple days; count limit; and end date.
- [ ] Manually test month-end and daylight-saving behavior using the existing
  automated cases as the expected behavior.
- [ ] Confirm completion creates exactly one next occurrence on repeated taps
  or after reopening the app, and that Undo only removes that occurrence.
- [ ] Confirm recurrence remains accessible at large font scale, with TalkBack,
  keyboard navigation, and narrow compact windows.

### Local core workspace still remaining

- [ ] Reminders and notification actions, including notification permission
  timing and exact-alarm fallback behavior.
- [ ] Custom per-project workflows: rename, reorder, add, and archive statuses
  while retaining semantic reporting categories.
- [ ] Milestone editing and task milestone membership.
- [ ] Dependency editing, cycle rejection UI, and blocked-completion warnings
  across all task entry points.
- [ ] Full-text search coverage for notes and attachment names.
- [ ] Schedule improvements: compact day agenda, expanded week timeline, and
  unscheduled-task tray.
- [ ] Import/export: encrypted `.otvault` archive and deliberate plaintext CSV
  export warnings.
- [ ] Complete process-restoration coverage for selection, drafts, scroll,
  filters, and timer state.

### Drive-primary storage remaining

- [ ] Google Identity authorization using only the `drive.appdata` scope.
- [ ] Encrypted manifest, snapshots, per-device operation segments, attachment
  blobs, and Drive `changes.list` synchronization.
- [ ] Outbox upload, remote operation download, idempotent merge, pagination,
  backoff, and visible sync-health states.
- [ ] Local-to-Drive migration with checksum verification and seven-day local
  rollback copy.
- [ ] Drive-to-local disconnect without deleting cloud data; guarded cloud
  deletion recovery flow.
- [ ] Resumable Drive attachment uploads and offline attachment cache policy.
- [ ] Multi-device conflict, authentication, quota, corruption, reinstall, and
  new-device recovery tests.

### Productivity and full-workspace features remaining

- [ ] Templates with relative dates, workflow, milestones, and task structure.
- [ ] Notes/activity history and attachments with Photo Picker, Storage Access
  Framework, Sharesheet, drag/drop, FileProvider cleanup, and 100 MB limits.
- [ ] Timer/manual time entries and overlap reconciliation.
- [ ] Insights: completion trends, overdue work, estimate-versus-actual time,
  project/tag time, and milestone health, each with accessible table/text
  equivalents.
- [ ] Today Glance widget, Quick Add launcher shortcut, and app-lock title
  privacy controls.
- [ ] Keyboard shortcut helper, mouse/hover support, and accessible alternatives
  to drag actions.
- [ ] One-way calendar export through `ACTION_INSERT`.

### Hardening and release work remaining

- [ ] Threat model and dependency review.
- [ ] Crypto golden vectors, tamper/wrong-passphrase/Keystore-loss tests, and
  migration rehearsal from every released schema.
- [ ] Accessibility audit: TalkBack, keyboard, switch access, high contrast,
  reduced motion, 100/130/200% font, RTL readiness, and compact/expanded
  layouts.
- [ ] Screenshot and responsive tests for API 36/37, Fold cover/main displays,
  tablet, rotation, split-screen, and resizing.
- [ ] Baseline Profile, Macrobenchmark, R8/resource shrinking, and performance
  validation with large task sets.
- [ ] Privacy policy, OAuth brand verification, Play Data Safety declaration,
  Play App Signing, internal/closed/open testing, and staged production rollout.

### Repository and tooling setup remaining

Claude Code was initialized on 2026-07-26. `CLAUDE.md`, two skills
(`.claude/skills/check`, `.claude/skills/connected-tests`), and a
`.claude/settings.json` hook were added. The following remain:

- [ ] Open `/hooks` once, or restart Claude Code, to load
  `.claude/settings.json`. The file was created mid-session, so the config
  watcher has not picked it up and the hook is not yet firing. The hook warns
  when a `.kt` file outside `core/designsystem` gains a raw `Color(0x...)`
  literal; its command was verified directly but has not fired through the
  harness.
- [ ] Create a real commit. Only two empty files are tracked and there is no
  remote, so git history and diffs are currently useless for reasoning about
  changes, and there is no rollback point. `gh` is installed if a GitHub
  remote is wanted.
- [ ] Decide whether ktlint or spotless is worth adding. IDE-only formatting
  with `kotlin.code.style=official` is the current deliberate choice; without
  tooling, drift only surfaces in review and there is no fast automated style
  check.
- [ ] Consider adding the instrumented suites to CI. CI runs unit tests, lint,
  and debug assembly only, so the Room/SQLCipher migration coverage never runs
  automatically.
- [ ] Optional Claude Code plugins: `frontend-design` for Compose UI work,
  `skill-creator` for refining the two skills above, and `/plugin` to browse
  the rest.

## Manual acceptance check

1. Open Tasks and select a task with a due date.
2. Scroll to Planning and choose Weekly.
3. Set an interval, select weekdays, and choose an end mode.
4. Wait for the save indicator to return to “Saved on this device.”
5. Close and reopen the task to confirm the rule persists.
6. Complete the task and verify the snackbar says the next occurrence was
   scheduled.
7. Verify a new planned task appears at the expected due date.
8. Use Undo and verify the generated task disappears while the original task
   reopens.
