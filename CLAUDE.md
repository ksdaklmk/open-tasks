# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Open Tasks is an offline-first Android task workspace (Kotlin, Compose, 13 Gradle modules).
Read `@docs/architecture.md` before changing data or command flow, `@DESIGN.md` before changing UI.
`@HANDOFF.md` is the only live backlog and current state of in-flight work. A
retained programme worktree under `.worktrees/` (`git worktree list`) may hold
a newer copy that supersedes `main`'s; check it before trusting "next task".

## Build and test

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug   # the CI gate; run before calling work done
./gradlew :core:domain:testDebugUnitTest --tests "*RecurrenceEngineTest.monthlyRecurrenceUsesOriginalDayInsteadOfDrifting"
```

Generic task CSV migration has two focused host/compile gates:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "*GenericTasksCsvMapperTest" \
  --tests "*TasksCsvParserTest" \
  --tests "*InMemoryImportTasksTest" \
  --console=plain
./gradlew :app:testDebugUnitTest \
  --tests "*TaskMigrationViewModelTest" \
  --tests "*WindowPostureMapperTest" \
  :core:data:compileDebugAndroidTestKotlin \
  :feature:more:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin \
  --console=plain
```

Instrumented tests need a device. CI runs them on API 36 and 37.0; use the same
command locally for device-specific diagnosis:

```bash
./gradlew :app:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest :feature:more:connectedDebugAndroidTest \
  :feature:home:connectedDebugAndroidTest
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
- `app/build.gradle.kts` disables ABI splits whenever a requested task name
  contains `bundle` (AGP issue 402800800). Never request `assemble` and
  `bundle` tasks in one invocation.
- All versions come from `gradle/libs.versions.toml`. Configuration cache is on, so anything added must be config-cache compatible.
- Dependency verification is on: every version bump must add the new
  artifacts' SHA-256 entries to `gradle/verification-metadata.xml` (the two
  `--write-verification-metadata sha256` invocations in `RELEASING.md`),
  prune the superseded entries, and cross-check the added checksums against
  the repository's published `.sha256` sidecars. Dependabot PRs cannot merge
  on their own for this reason; land the bump on `main` and the PR auto-closes.
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
- Stage 8 Month and Timeline are pure projections and never write.
  `computeScheduleMonthProjection` is Monday-first over a fixed 42 cells,
  with up to six density dots, a `6+` overflow label, and exact
  task/completed/overdue counts. `computeProjectTimelineProjection` is a
  bounded, Monday-anchored 84-day (12-week) window with dot-run spans,
  milestone diamonds, clipped/outside/invalid/unscheduled states, and
  bounded transitive dependency context. Do not recreate either rule in
  `app` or a feature, and do not widen either window.
- `SetTaskSchedule` owns the single-task schedule mutation: task schedule
  and reminder change in one transaction and one journal generation, with
  ordered TASK-then-REMINDER entries and repository-produced Undo,
  identically in `RoomVaultRepository` and `InMemoryVaultRepository`.
  `UpdateTask` stays start-aware. Never reschedule by pairing separate
  commands or by rebuilding the reminder in the UI.
- Week/Month pointer drag layers over the complete 48 dp tap/menu
  fallback; that fallback stays sufficient on its own and must be
  preserved. Drag adds no command, arithmetic, controller, or persistence
  state, and reuses the shared `PlanningDrag` root primitives in
  `:core:designsystem` that Board also consumes. An undated day drop
  becomes due 18:00 in the current device zone, spans move by stored-zone
  `plusDays`, and Java gap/overlap resolution is the sole DST authority;
  the editor's own defaults stay 09:00 start and 17:00 due.
- Per-project planning state — LIST/BOARD/TIMELINE presentation, the
  Monday-only Timeline anchor, and Timeline selection — lives in
  `SavedStateHandle` through `WorkspaceProjectViewState`. Decoding is
  fail-closed, legacy board booleans restore as BOARD, and LIST defaults
  and null selections are never persisted.

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
  `min(128 MiB, 5% available storage)`. `retired_blob_sets` and its
  `RETIRED_BLOB_SET` backup family arrived in Room v9 (Stage 5); Room is now
  v10 (`VAULT_DATABASE_VERSION`, exported schema `10.json`).
- Keep Stage 6 bounds: at most 20 saved views, 200 distinct task IDs per bulk
  command, 5,000 rows and 5 MiB per Tasks CSV import, and 14 days for weekly
  review staleness. Backup & recovery's strict parser accepts only the exact
  app-exported 14-column Tasks schema. The separate generic migration parser
  accepts at most 100 columns and maps only title, project, status, priority,
  start, due, completion, estimate, tags, and description. Both paths create
  new records; neither matches, merges, or deduplicates existing records.
- Generic CSV migration remains local and transient: no network or provider
  account, no persisted URI permission or source copy, and no mapping draft in
  `SavedStateHandle`, preferences, Room, or backup. Process death restarts the
  flow and requires choosing the source again.
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
- Stage 7 ergonomics state and Stage 8 planning surfaces/daily digest own no
  schema, backup family, exported surface, permission, Drive scope, route, or
  network path; Stage 8's sole manifest delta is the non-exported,
  no-intent-filter `DailyDigestReceiver`. Add none of these for further work
  there without an approved plan change.
- The daily digest is opt-in, off by default, and device-local non-vault
  state. Its `daily_digest` preference file holds exactly `enabled`
  (Boolean), `minute_of_day` (Int, `0..1439`), and optional
  `last_handled_epoch_day` (Long) — never task, project, count, zone id,
  notification payload, or scheduled instant. Invalid or wrongly typed
  state fails closed to disabled/08:00 and cancels the alarm; disabling
  retains the handled day so off/on cannot repeat a day's digest.
- Digest delivery arms exactly one inexact `setAndAllowWhileIdle` alarm
  through a single stable immutable broadcast `PendingIntent` — never
  exact-alarm access — and runs under one mutex in the order mark-handled
  → re-arm → vault lookup → post, so a missing vault, denied permission,
  disabled channel, or notification failure stays handled for that day
  with the next alarm still armed. Content comes from one
  `computeTodayProjection(titlesPermitted = false)` call: counts only in
  the private notification on the digest's own `daily_digest` channel, a
  generic public lock-screen version, and nothing posted when both counts
  are zero. `OPEN_DAILY_DIGEST_HOME` routes to Home behind the app lock,
  and its navigation signal must be consumed exactly once.
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
- Encrypted Room is the remote-backup persistence authority. An active runtime is
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
- Room instrumented tests: after `execute`, read through
  `observeWorkspace().first { … }`, never a bare `currentWorkspace()` (the
  snapshot refreshes asynchronously). Compare entities through one shared
  `ByteArray` instance (data classes compare arrays by reference). Never bin
  with a fixed past instant under the real clock: the 30-day retention purge
  runs on every repository open.
- `TaskMigrationScreen` is a `LazyColumn`: scroll through its
  `task-migration-screen` tag before asserting on an item. The disposable
  `Pixel6_Scratch` AVD reproduces the CI compact lane; uninstall
  `app.opentasks` before `FoldContinuityInstrumentedTest`.
- Compose tests use `androidx.compose.ui.test.junit4.v2.createComposeRule` (note `.v2`) and drive debounce with `mainClock.autoAdvance = false`. Feature UI tests instantiate the stateless screen inside `OpenTasksTheme { }` with `OpenTasksFixtures` data — no ViewModel, no Hilt.
- The `.v2` rules run composition on a `StandardTestDispatcher`: a
  `LaunchedEffect` only executes when the rule advances its scheduler
  (`waitUntil`, `waitForIdle`, any action or assertion). Never wait on app or
  repository state with a bare `SystemClock`/`Thread.sleep` loop — the effect
  stays queued forever; wait through `composeRule.waitUntil` instead.

## Repo

- Commit straight to `main`; no branch or PR ceremony.
- No secrets or env vars are needed for local development. Release builds sign
  only when a keystore properties file exists: `-PopenTasksKeystoreProperties=
  <path>` selects one (the Play upload key), else the gitignored root
  `keystore.properties` (the app-signing key). CI release builds stay unsigned.
- GitHub Actions `uses:` references stay SHA-pinned;
  `scripts/verify-actions-workflow.sh` enforces this, the seven-module
  connected matrix, and the exact Pages workflow. The CI `verify` job runs it
  first; run it by hand before pushing any workflow change.

## Release and Play

- Read `@RELEASING.md` before any release or Play step. Direct APKs and the
  Play AAB use different keys: app-signing (`keystore.properties`,
  `OPEN_TASKS_RELEASE_CERT_SHA256`, `scripts/verify-release-apk.sh`) versus
  upload (`-PopenTasksKeystoreProperties`, `OPEN_TASKS_UPLOAD_CERT_SHA256`,
  `OPEN_TASKS_BUNDLETOOL_JAR`, `scripts/verify-release-bundle.sh`). Never
  register the upload certificate as an OAuth or delivery identity.
- Certificate fingerprints come from the owner's independent record through
  `read -s`. Never derive one from a candidate artifact or print one in a
  command, doc, log, or chat.
- `docs/qualification/release-*-play.md` is append-only: add a dated entry and
  never edit prior evidence. A PASS binds to one artifact hash; any rebuild
  invalidates it.
- `site/` is the GitHub Pages source and holds exactly `privacy/index.html`,
  `support/index.html`, and the Search Console token
  `googlebfb12df764b54328.html` (Google rechecks it; never remove). Static
  HTML only: no script, form, cookie, analytics, remote font, or stylesheet.
  The privacy URL is also hard-coded in `OpenTasksApp.kt`; change both
  together.
- Linked worktrees under `.worktrees/` have no `local.properties`; copy the
  root one there before running Gradle.

Module-specific instructions can go in a subdirectory `CLAUDE.md` (e.g. `core/data/CLAUDE.md`); it loads automatically when working there. Ask if you want one.
