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

Instrumented tests need a device. CI runs them on API 36 and 37; use the same
command locally for device-specific diagnosis:

```bash
./gradlew :core:data:connectedDebugAndroidTest :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest :feature:more:connectedDebugAndroidTest
```

Do not uninstall the app or wipe emulator data — the emulator holds a workspace already migrated v1→v2.

## Gradle

- **No Kotlin Android Gradle plugin is applied anywhere.** Modules apply only `com.android.application`/`com.android.library` and rely on AGP 9's built-in Kotlin support. There is no `kotlin.android` alias in the catalog — do not "fix" this.
- Sources live in `src/main/kotlin`, not `src/main/java`.
- All versions come from `gradle/libs.versions.toml`. Configuration cache is on, so anything added must be config-cache compatible.
- `compileSdk`/`targetSdk` 37, `minSdk` 36, Java 17 source/target, daemon toolchain JDK 21.

## Architecture rules

- Every write is a `DomainCommand` executed through `VaultRepository.execute`. Never touch Room outside `RoomVaultRepository`.
- Each mutation updates records **and** appends an outbox operation in one transaction.
- Undo is produced by the repository and returned in `CommandResult.Success(message, undo)`. Never reconstruct undo in the UI.
- Checklist and tag edits use granular commands, not whole-relation replacement.
- `feature/*` modules depend only on `:core:model` and `:core:designsystem`. Feature composables are stateless — plain data in, lambdas out. No Hilt in feature or core modules; all command dispatch lives in `:app`.
- `:core:sync` and `:core:crypto` stay free of Compose and Android UI so merge and recovery proofs run as unit tests.
- `InMemoryVaultRepository` (in `:core:data` `src/main`, used by unit tests) must stay behaviorally in sync with `RoomVaultRepository` whenever a command is added.
- Selected task/project identity lives in `SavedStateHandle`, not in composable state.
- Navigation is **Navigation 3** (`NavDisplay` + `rememberNavBackStack` + `entryProvider`, routes are `@Serializable data object … : NavKey`). Not `navigation-compose`, no `NavHost`.
- Layout comes from `WorkspaceLayoutPolicy.calculate(widthDp, hasSeparatingFold)`, never from `WindowSizeClass` or device model.

## Data and security

- Bumping `VaultDatabase` version requires a new exported schema JSON in `core/data/schemas/` **and** a non-destructive `Migration`.
- Zero database key arrays after use (`key.fill(0)` in a `finally`), as `LocalVaultRepositoryFactory` does.
- Recovery uses Argon2id (64 MiB, 3 iterations, parallelism 1, 16-byte salt); passphrases are never persisted.
- Logs and telemetry must never contain task text, account details, Drive IDs, attachment names, or encryption metadata.
- Drive transport is deliberately not wired to credentials; `CloudObjectStore` and `SyncCoordinator` are the seams for that later slice.

## Style

- Colors are authored as OKLCH via `oklch(...)` in `:core:designsystem`. Never introduce hex color literals.
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
- No secrets or env vars are needed for local development. There is no signing config — release builds are unsigned.

Module-specific instructions can go in a subdirectory `CLAUDE.md` (e.g. `core/data/CLAUDE.md`); it loads automatically when working there. Ask if you want one.
