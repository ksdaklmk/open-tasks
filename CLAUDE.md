# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Open Tasks is an offline-first Android task workspace (Kotlin, Compose, 12 Gradle modules).
Read `@docs/architecture.md` before changing data or command flow, `@DESIGN.md` before changing UI.
`@HANDOFF.md` is the only live backlog and current state of in-flight work.

## Build and test

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug   # the CI gate; run before calling work done
./gradlew :core:domain:testDebugUnitTest --tests "*RecurrenceEngineTest.monthEndDoesNotDrift"
```

Instrumented tests need a device. CI runs them on API 36 and 37.0; use the same
command locally for device-specific diagnosis:

```bash
./gradlew :app:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest :feature:more:connectedDebugAndroidTest
```

`:app:connectedDebugAndroidTest` uninstalls `app.opentasks`. Run connected
suites only against a sole disposable ADB target started with `-read-only
-no-snapshot-load -no-snapshot-save`, never against the normal emulator.

Do not uninstall the app or wipe emulator data — the `Pixel_10_Pro_Fold` AVD
holds a protected workspace migrated v1→v6 plus the signed-in Google account
the Stage 3 credentialed gate needs (Google's Authorization API fails with
`INTERNAL_ERROR` 8 on a device with no account).

CI also runs `:app:assembleRelease` as a separate job. Never combine
`lintDebug` and `assembleRelease` in one Gradle invocation — AGP lint can
race KSP while release Hilt sources are generated.

## Gradle

- **No Kotlin Android Gradle plugin is applied anywhere.** Modules apply only `com.android.application`/`com.android.library` and rely on AGP 9's built-in Kotlin support. There is no `kotlin.android` alias in the catalog — do not "fix" this.
- Sources live in `src/main/kotlin`, not `src/main/java`.
- All versions come from `gradle/libs.versions.toml`. Configuration cache is on, so anything added must be config-cache compatible.
- `compileSdk`/`targetSdk` 37, `minSdk` 36, Java 17 source/target, daemon toolchain JDK 21.

## Architecture rules

- Every write is a `DomainCommand` executed through `VaultRepository.execute`. Never touch Room outside `RoomVaultRepository`.
- Each accepted mutation updates records **and** appends ordered backup-journal
  entries in one transaction. The old outbox table is migration-only and must
  not receive new mutations.
- Undo is produced by the repository and returned in `CommandResult.Success(message, undo)`. Never reconstruct undo in the UI.
- Checklist and tag edits use granular commands, not whole-relation replacement.
- `feature/*` modules depend only on `:core:model` and `:core:designsystem`. Feature composables are stateless — plain data in, lambdas out. No Hilt in feature or core modules; all command dispatch lives in `:app`.
- `:core:sync` and `:core:crypto` stay free of Compose and Android UI so merge and recovery proofs run as unit tests.
- `InMemoryVaultRepository` (in `:core:data` `src/main`, used by unit tests)
  must stay behaviorally in sync with `RoomVaultRepository` whenever a command
  is added, including backup-journal atomicity.
- Selected task/project identity lives in `SavedStateHandle`, not in composable state.
- Navigation is **Navigation 3** (`NavDisplay` + `rememberNavBackStack` + `entryProvider`, routes are `@Serializable data object … : NavKey`). Not `navigation-compose`, no `NavHost`.
- Layout comes from `WorkspaceLayoutPolicy.calculate(WindowPosture)`, never
  from `WindowSizeClass` or device model.
- `arrangeTasks` in `core:domain` is the comparator authority for Stage 7
  Tasks, workbench, and board ordering; `searchWorkspace` is the shared filter
  and ranking authority for both repositories. Do not recreate either rule in
  `app` or a feature. Schedule and Home retain their separate existing order.

## Data and security

- Bumping `VaultDatabase` version requires a new exported schema JSON in `core/data/schemas/` **and** a non-destructive `Migration`.
- Zero database key arrays after use (`key.fill(0)` in a `finally`), as `LocalVaultRepositoryFactory` does.
- Recovery uses Argon2id (64 MiB, 3 iterations, parallelism 1, 16-byte salt); passphrases are never persisted.
- Logs and telemetry must never contain task text, account details, Drive IDs, attachment names, or encryption metadata.
- Stage 3 Drive transport is create-only. `CreateOnlyDriveTransport` /
  `HttpCreateOnlyDriveTransport` use only the `drive.appdata` scope and
  immutable creates: no update/PATCH path and no ETag, If-Match, or
  provider-revision concepts (Drive supplies no strong HTTP revision; the
  mutable-control design was abandoned for that reason). Credentialed access
  exists only in the debug-only, non-exported qualification activity. Future
  cloud work must keep encrypted Room as the sole live structured-data
  authority, add no bidirectional sync path, encrypt objects locally through
  the provider-neutral authenticated codec, and use separate
  `BackupObjectStore` and `AttachmentBlobStore` boundaries.
- Stage 4 attachment objects are immutable exact-ID chunks plus one manifest
  in the separate attachment namespace. Use only authenticated lineage/blob-set
  IDs and attachment role properties; unknown, malformed, or cross-namespace
  objects are retained and fail closed. Never broaden `drive.appdata`, add an
  update/PATCH path, or scan the namespace as a substitute for exact lookup.
- Keep Stage 4 bounds: note body 10,000 characters and 500 notes per owner;
  activity body 500 characters and 500 entries per task or project; 50 search
  results; 100 active attachments per task; 100 MiB per attachment, 4 MiB
  chunks, at most 25 chunks; sessions expire after 24 hours; and cache
  `min(128 MiB, 5% available storage)`. Room v9 adds the durable
  `retired_blob_sets` index and its `RETIRED_BLOB_SET` backup family
  (Stage 5); any further durable schema change requires a later migration
  and exported schema.
- Keep Stage 6 bounds: at most 20 saved views, 200 distinct task IDs per bulk
  command, 5,000 rows and 5 MiB per Tasks CSV import, and 14 days for weekly
  review staleness. CSV import accepts only the exact app-exported Tasks schema
  and creates new records; it never matches or merges existing records.
- Focus cycles have exactly two presets, 25/5 and 50/10. Start, boundary,
  reconcile, banner Stop, and timer Stop stay serialized through the existing
  focus coordinator gate. Stopping the focus-owned task must clear the
  session/alarm before `StopTimerIfOwned` and must not allow foreground
  reconciliation to create a replacement time entry; stopping another task
  retains ordinary `StopTimer` behaviour.
- `SAVED_VIEW` backup identity remains the saved-view ID, but its journal
  fingerprint must remain content-based over the complete encoded backup
  record. Never revert it to identity-only: a rename or query update must
  journal an upsert without a Room or backup-format change.
- `SavedViewPayloadCodec` reads `formatVersion` first, strictly decodes only v1
  and v2, and deterministically encodes v2. Unknown or malformed payloads stay
  stored but invisible. Keep the 20-view, 64-character name, 500-character
  query, and 2 MiB payload bounds; never rewrite existing v1 rows merely to
  upgrade them.
- `view_prefs` is device-local non-vault state. Store only arrangement enum
  names and project IDs there—never task/query text, keys, or backup content.
- Quick Add grammar is suggestion-only: every token requires an individual
  confirmation before title stripping or command fields change. Keep matching
  on `Locale.ROOT`, the 50-tags-per-task limit, positive estimates no greater
  than 24 hours, and recurrence's required due date. Multi-word grammar tags
  and weekday lists remain unsupported.
- The Today widget remains responsive at a 2×1 minimum: compact layouts expose
  both counts and Quick Add; only expanded heights render privacy-gated focus
  titles and their open/complete actions.
- `DuplicateTask` must exclude completion, reminders, recurrence/series state,
  prior activity, time entries, notes, and attachments. Both repositories must
  keep the copy and its repository-produced Undo atomic.
- Stage 7 stays on Room v9 and authenticated backup object format v1. Do not
  add a schema, backup family, exported surface, permission, or network path for
  its ergonomics state.
- `AttachmentBlobCoordinator.resume()` has exactly one product caller: the
  silent auto-resume in `AttachmentRuntime.resumeInterruptedSessions()`,
  which runs strictly after session expiry on runtime start and re-arms
  after each completed publication run, and never authorizes when nothing
  is pending. Retain the coordinator's tests. Do not add in-row transfer
  progress without a durable product contract.
- `.otvault` v1 (Stage 5) is a frozen archive format: `OtVaultCodec` wraps
  the vault content key in a real recovery envelope (export passphrase =
  recovery passphrase) and streams frozen Stage 1 frames at archive-scoped
  object IDs. Exports are snapshot-only baselines. Its Node fixture
  generator must regenerate byte-identically; change the format only with
  a new version, never in place.
- Room v9 is the remote-backup persistence authority. An active runtime is
  bound to one active vault slot and must stop when that slot is replaced,
  ownership is lost, or the lineage terminates.
- Ownership claims, publications, recovery bases, and the terminal tombstone
  are immutable create-by-ID objects. Publication sequence is monotonic within
  one authenticated ownership tip; generation may stay equal only for an
  explicitly forced complete recovery baseline or passphrase rotation.
- Remote cleanup is bounded and namespace-safe: it may act only on objects
  authenticated to the exact lineage, epoch, device, role, and durable
  operation. Unknown or ambiguous objects fail closed.
- Permanent remote deletion authenticates the terminal tombstone before
  recoverable objects are removed, resumes cleanup after process death, and
  retains that non-recoverable tombstone as the final lineage state.

## Style

- Colors are authored as OKLCH via `oklch(...)` in `:core:designsystem`. Never introduce hex color literals. A `PreToolUse` hook blocks `.kt` writes containing `Color(0x` outside `core/designsystem`.
- `OpenTasksTheme` is light-only, even under a dark device configuration. Do
  not add a dark scheme or theme preference.
- Spacing uses the 4 dp scale (4, 8, 12, 16, 24, 32, 48, 64). Typography uses Material roles only — no ad-hoc sizes in feature code. Dynamic Color is disabled.
- No formatter is configured by choice; `kotlin.code.style=official` plus IDE reformat is the authority. Follow the existing conventions: trailing commas in multi-line lists, wrap near 100 chars, explicit imports (no wildcards), underscores in numeric literals, per-declaration `@OptIn`.
- New UI copy goes in `res/values/strings.xml` and is read with `stringResource` (existing screens hardcode literals; do not follow that).
- Markdown docs are hard-wrapped near 78 columns.

## Tests

- JUnit 4 with `org.junit.Assert.*`. No mocking library, no Turbine, no Robolectric, no coroutines-test — suspend code uses `runBlocking` + `withTimeout(5_000)` and real flow collection.
- Test names are camelCase describing behavior (`compactWindowUsesBottomNavigationAndOnePane`). Never backtick-quoted.
- Compose tests use `androidx.compose.ui.test.junit4.v2.createComposeRule` (note `.v2`) and drive debounce with `mainClock.autoAdvance = false`. Feature UI tests instantiate the stateless screen inside `OpenTasksTheme { }` with `OpenTasksFixtures` data — no ViewModel, no Hilt.

## Repo

- Commit straight to `main`; no branch or PR ceremony.
- No secrets or env vars are needed for local development. Local release builds
  sign only when the gitignored `keystore.properties` and external keystore are
  present; CI release builds remain unsigned.
- GitHub Actions `uses:` references stay SHA-pinned;
  `scripts/verify-actions-workflow.sh` enforces this and the CI matrix shape.

Module-specific instructions can go in a subdirectory `CLAUDE.md` (e.g. `core/data/CLAUDE.md`); it loads automatically when working there. Ask if you want one.
