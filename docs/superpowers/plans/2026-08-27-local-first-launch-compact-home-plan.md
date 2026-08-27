# Local-first Launch and Compact Home Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the Home greeting, make a fresh installation create its
encrypted local vault automatically, and keep both import and verified
Drive/Android recovery deliberately reachable from More.

**Architecture:** Reuse `RecoveryViewModel.startWithoutRestoring()` behind one
pure state gate in `MainActivity`; do not move local-vault creation into a new
coordinator. Route one new Backup & recovery action through the existing
`onOpenRecovery` callback and active-replacement recovery shell, then delete
the unreachable Welcome UI and its one-shot CSV handoff.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android lifecycle/ViewModel,
Navigation 3, JUnit 4, AndroidX Compose UI tests, AndroidX Macrobenchmark, and
Gradle.

**Spec:**
`docs/superpowers/specs/2026-08-27-local-first-launch-compact-home-design.md`
(approved by the owner on 2026-08-27).

## Global Constraints

- Automatic vault creation is allowed only for
  `VaultRuntimeState.NoVault` + `RecoveryPresentation.NoVault` outside active
  replacement mode.
- Never auto-create over `Unreadable`, `Recovering`, or `Active` state.
- Fresh launch makes no Google authorization, provider discovery, picker,
  analytics, or application-network request.
- Reuse `VaultRuntimeManager.createNewVault()`,
  `RecoveryViewModel.startWithoutRestoring()`, and the existing active
  replacement recovery shell; add no parallel recovery implementation.
- Keep top-level generic CSV import, strict Open Tasks CSV import, encrypted
  `.otvault` import, Google backup/recovery, and Android package recovery.
- Preserve Home's UK-English date format, search behavior, 48 dp search target,
  and one semantic heading.
- The first-run fully-drawn signal must represent usable Home, not the neutral
  bootstrap surface.
- Preserve Room v9, authenticated backup and `.otvault` format v1, Android
  backup rules, SDK values, permissions, signing, version, and release state.
- Add no dependency, database migration, preference, sample content, or
  speculative abstraction.
- Follow RED-GREEN for host and script tests. Compose/instrumentation RED and
  execution require an explicitly disposable device; otherwise compile those
  tests and record them as unexecuted.
- Never run connected tests on the protected `Pixel_10_Pro_Fold`.
- Stage only the files named by the current task. Preserve these unrelated
  workspace entries exactly:
  `docs/superpowers/plans/2026-07-30-stage-3-google-drive-backup-recovery-plan.md`,
  deleted `docs/superpowers/specs/2026-08-10-pinfo-thai-dashboard-design.md`,
  `.kotlin/`, `.ua/`, `artifacts/`,
  `docs/superpowers/plans/2026-08-21-open-tasks-onboarding-dashboard-nfr-plan.md`,
  and
  `docs/superpowers/specs/2026-08-21-offline-onboarding-executive-dashboard-nfr-design.md`.

## File Map

- Modify
  `feature/home/src/main/kotlin/app/opentasks/feature/home/HomeScreen.kt`:
  collapse the Home header to date plus search.
- Modify
  `feature/home/src/androidTest/kotlin/app/opentasks/feature/home/HomeScreenInstrumentedTest.kt`:
  assert greeting absence, date heading semantics, and retained search.
- Modify
  `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt` and
  `feature/more/src/main/kotlin/app/opentasks/feature/more/BackupRecoveryScreen.kt`:
  forward and render the one restore entry.
- Modify `feature/more/src/main/res/values/strings.xml`: add exact restore copy,
  delete Welcome copy, and rename the surviving local-start recovery label.
- Delete
  `feature/more/src/main/kotlin/app/opentasks/feature/more/WelcomeScreen.kt`,
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/WelcomeScreenInstrumentedTest.kt`,
  and `feature/more/src/main/res/drawable/ic_google_g.xml`.
- Modify
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/BackupRecoveryScreenInstrumentedTest.kt`:
  prove the restore action is reachable and forwarded once.
- Modify
  `feature/more/src/main/kotlin/app/opentasks/feature/more/RecoveryShellScreen.kt`
  and its existing instrumented test: remove stale Welcome terminology while
  preserving recovery behavior.
- Modify `app/src/main/kotlin/app/opentasks/MainActivity.kt`: gate automatic
  local creation, defer fully-drawn reporting, route active replacement, and
  remove Welcome-only migration plumbing.
- Modify `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`: send the new More
  action through its existing `onOpenRecovery` callback and remove the obsolete
  post-Welcome migration signal.
- Modify
  `app/src/main/kotlin/app/opentasks/backup/RecoveryViewModel.kt`: rename the
  reset action from Welcome-specific to source-specific terminology.
- Modify `app/src/main/kotlin/app/opentasks/TaskMigrationViewModel.kt` and
  `app/src/test/kotlin/app/opentasks/TaskMigrationViewModelTest.kt`: delete the
  unreachable Welcome handoff state and tests.
- Modify `app/src/test/kotlin/app/opentasks/NavigationPresentationTest.kt` and
  `app/src/test/kotlin/app/opentasks/backup/RecoveryViewModelTest.kt`: prove the
  local-bootstrap state boundary and update recovery names.
- Modify
  `app/src/androidTest/kotlin/app/opentasks/MainActivityQuickAddInstrumentedTest.kt`,
  `app/src/androidTest/kotlin/app/opentasks/FoldContinuityInstrumentedTest.kt`,
  and
  `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt`:
  replace Welcome setup with automatic bootstrap and exercise More recovery.
- Modify
  `benchmark/src/main/kotlin/app/opentasks/benchmark/OpenTasksMacrobenchmark.kt`,
  `scripts/check-benchmark-thresholds.sh`, and
  `scripts/verify-benchmark-threshold-script.sh`: replace Welcome startup
  measurement with one cold first-run-to-Home measurement.
- Modify `README.md`, `PRODUCT.md`, `DESIGN.md`, and `HANDOFF.md`: reconcile the
  living product and implementation contract. Historical plans, specs, and
  qualification evidence remain unchanged.

---

### Task 1: Collapse Home to a date-and-search header

**Files:**

- Modify:
  `feature/home/src/main/kotlin/app/opentasks/feature/home/HomeScreen.kt:493-518`
- Test:
  `feature/home/src/androidTest/kotlin/app/opentasks/feature/home/HomeScreenInstrumentedTest.kt`

**Interfaces:**

- Consumes: `HomeSnapshot.today`, `HOME_DATE_FORMAT`, and `onOpenSearch`.
- Produces: the same `HomeScreen` public API, with the formatted date as its
  header semantic and no greeting node.

- [ ] **Step 1: Add the Home header regression test**

Add these imports:

```kotlin
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.onNodeWithContentDescription
```

Add this test to `HomeScreenInstrumentedTest`:

```kotlin
@Test
fun headerUsesTheDateWithoutATimeOfDayGreeting() {
    val snapshot = OpenTasksFixtures.snapshot.home
    composeRule.setContent {
        OpenTasksTheme {
            HomeScreen(
                snapshot = snapshot,
                projectNames = emptyMap(),
                onOpenSearch = {},
                onPlanToday = {},
                onOpenTask = {},
                onCompleteTask = {},
                onOpenProject = {},
                insightsSummary = OpenTasksFixtures.insightsSummary,
                onOpenInsights = {},
                onToggleTimer = {},
                onRemoveFromMyDay = {},
                onMoveMyDayEntry = { _, _ -> },
                suggestions = emptyList(),
                onAddToMyDay = {},
            )
        }
    }

    composeRule.onNodeWithText("Good afternoon").assertDoesNotExist()
    composeRule.onNodeWithText("Sunday, 26 July")
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        .assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Search workspace").assertIsDisplayed()
}
```

- [ ] **Step 2: Compile the RED test and, only on a disposable device, observe its assertion failure**

Run:

```bash
./gradlew :feature:home:compileDebugAndroidTestKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL`; compilation proves the test is well formed.

If and only if an explicitly disposable device is the sole selected target,
run:

```bash
./gradlew :feature:home:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.opentasks.feature.home.HomeScreenInstrumentedTest#headerUsesTheDateWithoutATimeOfDayGreeting --console=plain
```

Expected before production change: FAIL because `Good afternoon` exists and
the date is not the heading. Without a safe target, record connected RED as
unexecuted rather than touching the protected emulator.

- [ ] **Step 3: Delete the greeting and promote the existing date semantic**

Replace the nested header `Column` and both text nodes with:

```kotlin
Text(
    text = snapshot.today.format(HOME_DATE_FORMAT),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.secondary,
    modifier = Modifier.semantics { heading() },
)
```

Keep the existing `IconButton` unchanged. Do not add greeting logic, a clock,
new spacing, or another title.

- [ ] **Step 4: Compile GREEN and run connected coverage only when safe**

Run:

```bash
./gradlew :feature:home:compileDebugAndroidTestKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL`.

On a disposable target, rerun the focused connected command from Step 2.
Expected: PASS.

- [ ] **Step 5: Commit the compact Home header**

```bash
git add feature/home/src/main/kotlin/app/opentasks/feature/home/HomeScreen.kt feature/home/src/androidTest/kotlin/app/opentasks/feature/home/HomeScreenInstrumentedTest.kt
git commit -m "fix: remove Home greeting"
```

---

### Task 2: Expose verified workspace recovery from More

**Files:**

- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt:105-210,289-340`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/BackupRecoveryScreen.kt:148-370`
- Modify: `feature/more/src/main/res/values/strings.xml:120-220`
- Modify:
  `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt:1780-1870`
- Test:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/BackupRecoveryScreenInstrumentedTest.kt`

**Interfaces:**

- Consumes: `OpenTasksApp(onOpenRecovery: () -> Unit)` and the existing
  `RecoveryShellMode.ActiveReplacement` surface.
- Produces:
  `MoreScreen(onRestoreExistingWorkspace: () -> Unit = {})` and
  `BackupRecoveryScreen(onRestoreExistingWorkspace: () -> Unit = {})`.

- [ ] **Step 1: Add a compile-failing callback and reachability test**

Add to `BackupRecoveryScreenInstrumentedTest`:

```kotlin
@Test
fun restoreExistingWorkspaceIsReachableAndForwardedOnce() {
    val restores = AtomicInteger()
    composeRule.setContent {
        OpenTasksTheme {
            MoreScreen(
                tasks = emptyList(),
                projects = emptyList(),
                onRestoreExistingWorkspace = { restores.incrementAndGet() },
                onRestoreProject = {},
                onRestoreTask = {},
                onPermanentlyDeleteTask = {},
            )
        }
    }

    composeRule.onNodeWithTag("open-backup-recovery")
        .performScrollTo()
        .performClick()
    composeRule.onNodeWithText(
        "Your current workspace stays unchanged until a verified restore is confirmed.",
        substring = true,
    ).performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("restore-existing-workspace")
        .performScrollTo()
        .assertHeightIsAtLeast(48.dp)
        .performClick()

    assertEquals(1, restores.get())
}
```

In `remoteActionsAndLifecycleDisclosuresStayScrollReachableAtLargeText`, add
`"restore-existing-workspace"` as the first tag in the existing list. Its
existing 2.0 font scale and `assertHeightIsAtLeast(48.dp)` loop then cover the
new action without a second large-text fixture.

- [ ] **Step 2: Run the test compilation to verify RED**

Run:

```bash
./gradlew :feature:more:compileDebugAndroidTestKotlin --console=plain
```

Expected: FAIL with an unknown `onRestoreExistingWorkspace` argument.

- [ ] **Step 3: Add the callback through the existing More stack**

Add this defaulted parameter to `MoreScreen`:

```kotlin
onRestoreExistingWorkspace: () -> Unit = {},
```

Pass it to `BackupRecoveryScreen`:

```kotlin
onRestoreExistingWorkspace = onRestoreExistingWorkspace,
```

Add the same defaulted parameter to `BackupRecoveryScreen` immediately before
`onBack`:

```kotlin
onRestoreExistingWorkspace: () -> Unit = {},
```

In `OpenTasksApp`, pass its existing callback at the production `MoreScreen`
call:

```kotlin
onRestoreExistingWorkspace = onOpenRecovery,
```

Do not add a channel, navigation route, or second recovery ViewModel.

- [ ] **Step 4: Render one explicit action near the top of Backup & recovery**

Add these resources:

```xml
<string name="backup_restore_existing_action">Restore existing workspace</string>
<string name="backup_restore_existing_explanation">Choose Google Drive or an Android backup package. Your current workspace stays unchanged until a verified restore is confirmed.</string>
```

In `BackupRecoveryScreen`, immediately after the title row and its 24 dp
spacer, add:

```kotlin
Text(
    stringResource(R.string.backup_restore_existing_explanation),
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
Spacer(Modifier.height(12.dp))
BackupAction(
    R.string.backup_restore_existing_action,
    "restore-existing-workspace",
    onRestoreExistingWorkspace,
)
HorizontalDivider(Modifier.padding(vertical = 24.dp))
```

Then retain the existing Encrypted backup heading and every existing section
unchanged.

- [ ] **Step 5: Compile GREEN and run the focused test only when safe**

Run:

```bash
./gradlew :feature:more:compileDebugAndroidTestKotlin :app:compileDebugKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL`.

On a disposable target only:

```bash
./gradlew :feature:more:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.opentasks.feature.more.BackupRecoveryScreenInstrumentedTest#restoreExistingWorkspaceIsReachableAndForwardedOnce --console=plain
```

Expected: PASS.

- [ ] **Step 6: Commit the reused recovery entry**

```bash
git add feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt feature/more/src/main/kotlin/app/opentasks/feature/more/BackupRecoveryScreen.kt feature/more/src/main/res/values/strings.xml feature/more/src/androidTest/kotlin/app/opentasks/feature/more/BackupRecoveryScreenInstrumentedTest.kt app/src/main/kotlin/app/opentasks/OpenTasksApp.kt
git commit -m "feat: expose workspace recovery from More"
```

---

### Task 3: Bootstrap only a genuinely missing local vault

**Files:**

- Modify: `app/src/main/kotlin/app/opentasks/MainActivity.kt:90-220,280-405,519-535`
- Modify: `app/src/main/kotlin/app/opentasks/backup/RecoveryViewModel.kt`
- Test: `app/src/test/kotlin/app/opentasks/NavigationPresentationTest.kt`
- Test: `app/src/test/kotlin/app/opentasks/backup/RecoveryViewModelTest.kt`
- Test:
  `app/src/androidTest/kotlin/app/opentasks/MainActivityQuickAddInstrumentedTest.kt`

**Interfaces:**

- Consumes: `VaultRuntimeState`, `RecoveryPresentation`,
  `RecoveryViewModel.startWithoutRestoring()`, and the active-workspace
  `ReportDrawnWhen`.
- Produces:
  `shouldCreateInitialVault(VaultRuntimeState, RecoveryPresentation, Boolean): Boolean`
  and `shouldReportRecoveryFullyDrawn(VaultRuntimeState): Boolean`.

- [ ] **Step 1: Add the host state-boundary test**

Add `VaultRuntimeState` and `VaultSlot` imports, then add:

```kotlin
@Test
fun onlyAnIdleOrdinaryNoVaultSurfaceCreatesTheInitialVault() {
    assertTrue(
        shouldCreateInitialVault(
            VaultRuntimeState.NoVault,
            RecoveryPresentation.NoVault,
            activeReplacement = false,
        ),
    )
    assertFalse(
        shouldCreateInitialVault(
            VaultRuntimeState.NoVault,
            RecoveryPresentation.Discovering,
            activeReplacement = false,
        ),
    )
    assertFalse(
        shouldCreateInitialVault(
            VaultRuntimeState.NoVault,
            RecoveryPresentation.NoVault,
            activeReplacement = true,
        ),
    )
    assertFalse(
        shouldCreateInitialVault(
            VaultRuntimeState.Unreadable(VaultSlot.LEGACY),
            RecoveryPresentation.NoVault,
            activeReplacement = false,
        ),
    )
    assertFalse(
        shouldCreateInitialVault(
            VaultRuntimeState.Recovering("operation"),
            RecoveryPresentation.NoVault,
            activeReplacement = false,
        ),
    )
}

@Test
fun onlyOrdinaryNoVaultRecoveryWithholdsFullyDrawn() {
    assertFalse(shouldReportRecoveryFullyDrawn(VaultRuntimeState.NoVault))
    assertTrue(
        shouldReportRecoveryFullyDrawn(VaultRuntimeState.Unreadable(VaultSlot.LEGACY)),
    )
    assertTrue(
        shouldReportRecoveryFullyDrawn(VaultRuntimeState.Recovering("operation")),
    )
}
```

- [ ] **Step 2: Run the host test to verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "*NavigationPresentationTest" --console=plain
```

Expected: FAIL to compile because `shouldCreateInitialVault` and
`shouldReportRecoveryFullyDrawn` do not exist.

- [ ] **Step 3: Extract the existing three-part safety condition**

Add next to `recoveryShellMode`:

```kotlin
internal fun shouldCreateInitialVault(
    runtimeState: VaultRuntimeState,
    presentation: RecoveryPresentation,
    activeReplacement: Boolean,
): Boolean =
    runtimeState == VaultRuntimeState.NoVault &&
        presentation == RecoveryPresentation.NoVault &&
        !activeReplacement

internal fun shouldReportRecoveryFullyDrawn(runtimeState: VaultRuntimeState): Boolean =
    runtimeState != VaultRuntimeState.NoVault
```

In `RecoverySurface`, replace `showWelcome` with:

```kotlin
val createInitialVault = shouldCreateInitialVault(
    runtimeState = runtimeState,
    presentation = presentation,
    activeReplacement = activeReplacement,
)
```

- [ ] **Step 4: Replace the Welcome branch with one keyed bootstrap effect**

Inside `OpenTasksTheme`, remove the unconditional `ReportDrawn()` and insert
this branch immediately after `val migrationState = taskMigrationState`:

```kotlin
if (createInitialVault) {
    LaunchedEffect(recoveryViewModel) {
        recoveryViewModel.startWithoutRestoring()
    }
    Surface(modifier = Modifier.fillMaxSize()) {}
} else if (migrationState != null) {
    ReportDrawn()
    TaskMigrationPane(folds = rawFolds) {
        TaskMigrationScreen(
            state = migrationState,
            onMapField = taskMigrationViewModel::mapField,
            onStatusChoice = taskMigrationViewModel::chooseStatus,
            onPriorityChoice = taskMigrationViewModel::choosePriority,
            onDateOrder = taskMigrationViewModel::chooseDateOrder,
            onEstimateUnit = taskMigrationViewModel::chooseEstimateUnit,
            onTagMode = taskMigrationViewModel::chooseTagMode,
            onImport = {
                if (taskMigrationViewModel.confirmForWelcome(emptyTaskCsvTarget())) {
                    recoveryViewModel.startWithoutRestoring()
                }
            },
            onChooseAnother = taskMigrationViewModel::chooseAnother,
            onCancel = taskMigrationViewModel::cancel,
        )
    }
} else {
    if (shouldReportRecoveryFullyDrawn(runtimeState)) ReportDrawn()
    RecoveryShellScreen(
        mode = recoveryShellMode(
            runtimeRecovering = runtimeState is VaultRuntimeState.Recovering,
            presentation = presentation,
            activeReplacement = activeReplacement,
        ),
        candidates = candidates,
        takeoverGeneration =
            (presentation as? RecoveryPresentation.TakeoverConfirmation)?.generation,
        failureReason = (presentation as? RecoveryPresentation.Failed)?.reason,
        onDiscoverDrive = recoveryViewModel::discoverDrive,
        onDiscoverPortable = recoveryViewModel::discoverPortable,
        onRestore = recoveryViewModel::restore,
        onConfirmTakeover = recoveryViewModel::confirmTakeover,
        onStartWithoutRestoring = recoveryViewModel::startWithoutRestoring,
        onBack = recoveryViewModel::returnToWelcome,
        canStartWithoutRestoring = !activeReplacement,
        onRetryUnreadable = {
            lifecycleScope.launch {
                runCatching { vaultRuntimeManager.initialize() }
            }
        },
    )
}
```

Delete the old `else if (showWelcome)` branch rather than retaining a second
entry surface. The bootstrap branch must not call `ReportDrawn()`. The active
workspace continues to call `ReportDrawnWhen` after its workflow statuses
load. Every ordinary `NoVault` recovery presentation, including a failed
creation or user-selected recovery in progress, also withholds recovery-shell
fully-drawn reporting. `Unreadable`, `Recovering`, and active replacement keep
their existing recovery-shell reporting.

Before relying on the keyed effect, add this host regression:

```kotlin
@Test
fun localStartDropsAConcurrentRequestInsteadOfQueueingIt() {
    val firstEntered = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondEntered = CountDownLatch(1)
    val calls = AtomicInteger()
    val viewModel = viewModel(
        createNewVault = {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            } else {
                secondEntered.countDown()
            }
        },
    )

    viewModel.startWithoutRestoring()
    assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
    viewModel.startWithoutRestoring()
    releaseFirst.countDown()

    assertFalse(secondEntered.await(1, TimeUnit.SECONDS))
    assertEquals(1, calls.get())
}
```

Run it alone:

```bash
./gradlew :app:testDebugUnitTest --tests "*RecoveryViewModelTest.localStartDropsAConcurrentRequestInsteadOfQueueingIt" --console=plain
```

Expected: FAIL because the queued second creation runs after the first
unlocks. Then make only local start non-queueing:

```kotlin
fun startWithoutRestoring() = launchOperation(waitForTurn = false) {
    createNewVault()
    presented.value = RecoveryPresentation.Activating
}

private fun launchOperation(
    waitForTurn: Boolean = true,
    block: suspend () -> Unit,
) {
    if (!waitForTurn && !operation.tryLock()) return
    viewModelScope.launch(Dispatchers.Default) {
        if (waitForTurn) {
            operation.lock()
        }
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            presented.value = RecoveryPresentation.Failed(
                RecoveryFailureCategory.CORRUPT_OR_INCOMPATIBLE,
            )
        } finally {
            operation.unlock()
        }
    }
}
```

The default waiting lock preserves every unrelated recovery action. Re-run
`*RecoveryViewModelTest` GREEN before continuing; a later explicit local-start
request may retry after the first attempt completes or fails.

- [ ] **Step 5: Make the warm quick-add test expect the now-active local workspace**

In `MainActivityQuickAddInstrumentedTest`, replace both Welcome waits and
assertions with this initial wait:

```kotlin
composeRule.waitUntil(timeoutMillis = 10_000) {
    composeRule.onAllNodesWithContentDescription("Search workspace")
        .fetchSemanticsNodes().isNotEmpty()
}
composeRule.onNodeWithTag("quick-add-title").assertDoesNotExist()
```

Add the `onAllNodesWithContentDescription` import. After delivering
`QUICK_ADD_ACTION`, replace the existing wait with:

```kotlin
composeRule.waitUntil(timeoutMillis = 10_000) {
    resumed.get() === first.get() &&
        resumedAction.get() == MainActivity.QUICK_ADD_ACTION &&
        composeRule.onAllNodesWithTag("quick-add-title")
            .fetchSemanticsNodes().isNotEmpty()
}
```

Replace the final Welcome assertion with:

```kotlin
composeRule.onNodeWithTag("quick-add-title").assertIsDisplayed()
```

The retained lifecycle assertions must still prove that exactly one
`MainActivity` instance handled the warm intent.

- [ ] **Step 6: Run the host GREEN gate and compile the app instrumentation test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "*RecoveryViewModelTest" --tests "*NavigationPresentationTest" :app:compileDebugAndroidTestKotlin --console=plain
```

Expected: both host suites PASS and `BUILD SUCCESSFUL`.

On a disposable target only, run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.opentasks.MainActivityQuickAddInstrumentedTest --console=plain
```

Expected: PASS; the local workspace becomes active without a tap and the warm
quick-add intent reuses the activity.

- [ ] **Step 7: Commit the guarded local bootstrap**

```bash
git add app/src/main/kotlin/app/opentasks/MainActivity.kt app/src/test/kotlin/app/opentasks/NavigationPresentationTest.kt app/src/androidTest/kotlin/app/opentasks/MainActivityQuickAddInstrumentedTest.kt
git commit -m "feat: bootstrap fresh installs locally"
```

---

### Task 4: Delete Welcome and its one-shot migration handoff

**Files:**

- Delete:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/WelcomeScreen.kt`
- Delete:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/WelcomeScreenInstrumentedTest.kt`
- Delete: `feature/more/src/main/res/drawable/ic_google_g.xml`
- Modify: `feature/more/src/main/res/values/strings.xml:204-220`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/RecoveryShellScreen.kt:105-135`
- Modify:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/RecoveryShellScreenInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/MainActivity.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt:297-322,960-985`
- Modify: `app/src/main/kotlin/app/opentasks/TaskMigrationViewModel.kt:65-235`
- Modify:
  `app/src/main/kotlin/app/opentasks/backup/RecoveryViewModel.kt:85-140`
- Modify: `app/src/test/kotlin/app/opentasks/TaskMigrationViewModelTest.kt:180-235`
- Modify:
  `app/src/test/kotlin/app/opentasks/backup/RecoveryViewModelTest.kt`
- Modify:
  `app/src/androidTest/kotlin/app/opentasks/FoldContinuityInstrumentedTest.kt`
- Modify:
  `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt:680-735`

**Interfaces:**

- Consumes:
  `TaskMigrationViewModel.confirm(latestTarget: TaskCsvTarget): List<ImportedTaskRow>?`
  and
  `OpenTasksApp(onOpenRecovery)`.
- Produces: no Welcome API; `RecoveryViewModel.returnToSources()` is the one
  surviving recovery reset name.

- [ ] **Step 1: Delete the Welcome-only migration tests**

Remove these three tests from `TaskMigrationViewModelTest`:

```text
confirmedWelcomeRowsAreTakenExactlyOnce
abandoningWelcomeHandoffDropsRowsAndReview
rejectedWelcomeDispatchKeepsReviewReadyForActiveRetry
```

The active-workspace tests, including
`aRejectedCommitKeepsTheReviewAndASuccessClearsIt`, remain unchanged.

- [ ] **Step 2: Delete the Welcome handoff state from the ViewModel**

Remove:

```kotlin
private var pendingWelcomeRows: List<ImportedTaskRow>? = null

fun confirmForWelcome(latestTarget: TaskCsvTarget): Boolean
fun takeWelcomeRows(): List<ImportedTaskRow>?
fun abandonWelcomeHandoff(): Boolean
```

Make `cancel()` exactly:

```kotlin
fun cancel() {
    draft = null
    targetAtSelection = null
    mutableState.value = null
}
```

Keep `confirm`, `onCommitFinished`, and every parser/review operation because
More still uses them.

- [ ] **Step 3: Remove the dead first-run migration composition and signal**

From `MainActivity` remove:

- `openTasksAfterMigrationSignal`;
- the active-workspace `takeWelcomeRows()` effect;
- both corresponding `OpenTasksApp` arguments;
- the `TaskMigrationViewModel`, migration state, CSV document launcher,
  `rawFolds`, failure cleanup, and `TaskMigrationPane` branch inside
  `RecoverySurface`; and
- imports used only by that branch:
  `emptyTaskCsvTarget`, `CommandResult`, `DomainCommand`,
  `TaskMigrationScreen`, and `WelcomeScreen`.

Restore the manager-owned runtime identity and the non-secret recovery-route
flag immediately after `super.onCreate(savedInstanceState)`:

```kotlin
@Suppress("DEPRECATION")
activeRuntime = lastCustomNonConfigurationInstance as? LocalVaultRuntime
activeRecovery = savedInstanceState?.getBoolean(STATE_ACTIVE_RECOVERY) == true
```

Retain that exact process-owned identity only across configuration changes and
save only the route flag:

```kotlin
override fun onSaveInstanceState(outState: Bundle) {
    outState.putBoolean(STATE_ACTIVE_RECOVERY, activeRecovery)
    super.onSaveInstanceState(outState)
}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
override fun onRetainCustomNonConfigurationInstance(): Any? = activeRuntime
```

Add `private const val STATE_ACTIVE_RECOVERY = "active_recovery"` to the
existing companion object.

Keep the runtime-transition block:

```kotlin
if (next !== activeRuntime) {
    viewModelStore.clear()
    activeRuntime = next
    activeRecovery = false
}
```

An Activity attaching to the manager's already-active runtime now starts with
the same retained identity, so the manager's initial hot emission does not
clear the retained ViewModel store. A genuine first `NoVault` to `Active`
activation, transition away from an active runtime, or later active-runtime
identity replacement still differs and clears ViewModels before the next
composition. Saving `activeRecovery` preserves the active-replacement route;
do not save passphrases, candidate handles, recovery results, transient CSV
rows, or any other recovery data.

After removal, the themed part of `RecoverySurface` has only:

```kotlin
OpenTasksTheme {
    if (createInitialVault) {
        LaunchedEffect(recoveryViewModel) {
            recoveryViewModel.startWithoutRestoring()
        }
        Surface(modifier = Modifier.fillMaxSize()) {}
    } else {
        if (shouldReportRecoveryFullyDrawn(runtimeState)) ReportDrawn()
        RecoveryShellScreen(
            mode = recoveryShellMode(
                runtimeRecovering = runtimeState is VaultRuntimeState.Recovering,
                presentation = presentation,
                activeReplacement = activeReplacement,
            ),
            candidates = candidates,
            takeoverGeneration =
                (presentation as? RecoveryPresentation.TakeoverConfirmation)?.generation,
            failureReason = (presentation as? RecoveryPresentation.Failed)?.reason,
            onDiscoverDrive = recoveryViewModel::discoverDrive,
            onDiscoverPortable = recoveryViewModel::discoverPortable,
            onRestore = recoveryViewModel::restore,
            onConfirmTakeover = recoveryViewModel::confirmTakeover,
            onStartWithoutRestoring = recoveryViewModel::startWithoutRestoring,
            onBack = recoveryViewModel::returnToSources,
            canStartWithoutRestoring = !activeReplacement,
            onRetryUnreadable = {
                lifecycleScope.launch {
                    runCatching { vaultRuntimeManager.initialize() }
                }
            },
        )
    }
}
```

From `OpenTasksApp` remove:

```kotlin
openTasksAfterMigrationSignal: Int = 0,
onOpenTasksAfterMigrationConsumed: () -> Unit = {},
```

Also remove the `LaunchedEffect(openTasksAfterMigrationSignal)` block. The
active More import already navigates directly to `TasksRoute` on successful
commit and remains unchanged.

- [ ] **Step 4: Rename the recovery reset and surviving local-start copy**

Rename:

```kotlin
fun returnToWelcome()
```

to:

```kotlin
fun returnToSources() {
    presented.value = RecoveryPresentation.NoVault
}
```

Update all `MainActivity` and `RecoveryViewModelTest` callers and rename test
phrases from “Welcome” to “sources” or “local start” without changing their
assertions.

Rename this resource:

```xml
<string name="welcome_offline">Continue offline</string>
```

to:

```xml
<string name="recovery_start_local">Start with a local workspace</string>
```

Update `RecoveryShellScreen` and its tests to use/assert the new text. This
button remains only as the recovery no-candidate fallback when
`canStartWithoutRestoring` is true.

- [ ] **Step 5: Delete the Welcome UI, artwork, test, and unused copy**

Delete the three files listed in this task. Remove these resources:

```xml
<string name="welcome_title">Welcome to Open Tasks</string>
<string name="welcome_body">Plan projects and focused work in a private workspace on this device.</string>
<string name="welcome_google">Continue with Google</string>
<string name="welcome_google_disclosure">Optional — Google Drive is used only for encrypted backup and recovery.</string>
<string name="welcome_restore_device">Restore from this device</string>
```

Do not remove Drive authorization, encrypted backup, recovery candidate, or
Google account-resolution code.

- [ ] **Step 6: Launch MainActivity only in the folding suite's UI tests**

`FoldContinuityInstrumentedTest` also contains two guard tests that require a
clean `NoVault` baseline. An automatic launch from
`createAndroidComposeRule` would create a vault before those test bodies run,
so replace its import and field with:

```kotlin
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule

private val composeRule = createEmptyComposeRule()
private var activeScenario: ActivityScenario<MainActivity>? = null
```

The existing RuleChain remains `vaultFixtureRule` -> `composeRule` ->
`HideWindowsRule`. Remove the two
`composeRule.activityRule.scenario.close()` calls because no default activity
now exists.

The digest and quick-add tests already use an
`ActivityScenario.launch<MainActivity>(launchIntent)` `use` scope;
leave those scopes in place, delete their `welcome-screen` waits and
`Continue offline` clicks, and call:

```kotlin
ownedVault = awaitCreatedVault()
```

Keep their existing next waits: `More` for the digest test and
`quick-add-title` for the quick-add test.

In `draftAndSelectionSurviveFoldTransition`, create an explicit
`Intent(context, MainActivity::class.java)` and wrap the complete existing
device-state `try/finally` in the `use` scope returned by
`ActivityScenario.launch<MainActivity>(launchIntent)`.
Assign `activeScenario = scenario` immediately inside `use` and set
`activeScenario = null` as the first statement of the existing `finally`.
Do not change the existing device reset, keyguard, idle, fold, draft, scroll,
or recreation assertions. Delete only the Welcome wait/click and start the
active portion with:

```kotlin
ownedVault = awaitCreatedVault()
composeRule.waitUntil(timeoutMillis = 10_000) {
    composeRule.onAllNodesWithText("Quick add", useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
}
```

Replace the two helpers that referenced `activityRule.scenario` with:

```kotlin
private fun currentActivity(): MainActivity {
    var current: MainActivity? = null
    checkNotNull(activeScenario).onActivity { current = it }
    return checkNotNull(current)
}

private fun awaitActivityState(expected: Lifecycle.State) {
    val scenario = checkNotNull(activeScenario)
    val deadline = SystemClock.elapsedRealtime() + 10_000
    while (scenario.state != expected && SystemClock.elapsedRealtime() < deadline) {
        SystemClock.sleep(50)
    }
    assertTrue(
        "Expected MainActivity state $expected, was ${scenario.state}",
        scenario.state == expected,
    )
}
```

The two legacy-baseline guard tests now run without launching
`MainActivity`, so their safety assertions and cleanup stay unchanged.

- [ ] **Step 7: Move process-restoration recovery through More**

In
`productionRecoveryRouteClearsPassphraseAfterActivityRecreation`, replace the
Welcome assertions and direct device-restore click with:

```kotlin
composeRule.waitUntil(timeoutMillis = 10_000) {
    composeRule.onAllNodesWithText("More", useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
}
composeRule.onNodeWithText("More", useUnmergedTree = true).performClick()
composeRule.onNodeWithTag("open-backup-recovery")
    .performScrollTo()
    .performClick()
composeRule.onNodeWithTag("restore-existing-workspace")
    .performScrollTo()
    .performClick()
composeRule.onNodeWithTag("recovery-portable").performClick()
```

Keep the existing passphrase entry, activity recreation, and post-recreation
`recovery-passphrase` assertion unchanged. The node's continued presence proves
that the non-secret active-recovery route survived recreation; its empty input
proves that the passphrase did not. The test also proves that first activation
cleared the bootstrap `RecoveryViewModel`: otherwise the More action would show
an `Activating` spinner instead of `recovery-portable`. Retain the file's
existing `performScrollTo` import.

- [ ] **Step 8: Run host tests, Android-test compilation, and the dead-name scan**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "*TaskMigrationViewModelTest" --tests "*RecoveryViewModelTest" --tests "*NavigationPresentationTest" :feature:more:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin --console=plain
```

Expected: all selected host tests PASS and `BUILD SUCCESSFUL`.

Run:

```bash
rg -n "WelcomeScreen|welcome[-_]|confirmForWelcome|takeWelcomeRows|abandonWelcomeHandoff|returnToWelcome|openTasksAfterMigrationSignal" app/src feature/more/src
```

Expected: no matches.

On a disposable target only, run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.opentasks.MainActivityRecoveryRestorationInstrumentedTest,app.opentasks.FoldContinuityInstrumentedTest --console=plain
```

Expected: PASS. Otherwise record both as compiled but unexecuted.

- [ ] **Step 9: Commit the deletion**

```bash
git add feature/more/src/main/kotlin/app/opentasks/feature/more/WelcomeScreen.kt feature/more/src/androidTest/kotlin/app/opentasks/feature/more/WelcomeScreenInstrumentedTest.kt feature/more/src/main/res/drawable/ic_google_g.xml feature/more/src/main/res/values/strings.xml feature/more/src/main/kotlin/app/opentasks/feature/more/RecoveryShellScreen.kt feature/more/src/androidTest/kotlin/app/opentasks/feature/more/RecoveryShellScreenInstrumentedTest.kt app/src/main/kotlin/app/opentasks/MainActivity.kt app/src/main/kotlin/app/opentasks/OpenTasksApp.kt app/src/main/kotlin/app/opentasks/TaskMigrationViewModel.kt app/src/main/kotlin/app/opentasks/backup/RecoveryViewModel.kt app/src/test/kotlin/app/opentasks/TaskMigrationViewModelTest.kt app/src/test/kotlin/app/opentasks/backup/RecoveryViewModelTest.kt app/src/androidTest/kotlin/app/opentasks/FoldContinuityInstrumentedTest.kt app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt
git commit -m "refactor: remove obsolete Welcome flow"
```

---

### Task 5: Measure first run through usable Home

**Files:**

- Modify:
  `benchmark/src/main/kotlin/app/opentasks/benchmark/OpenTasksMacrobenchmark.kt:45-55,147-190`
- Modify: `scripts/check-benchmark-thresholds.sh:118-130`
- Test: `scripts/verify-benchmark-threshold-script.sh:20-55`

**Interfaces:**

- Consumes: AndroidX `StartupTimingMetric` and the active-workspace
  `ReportDrawnWhen`.
- Produces: `firstRunColdFullyDrawn` plus the existing empty/500/5000 cold and
  warm benchmark names.

- [ ] **Step 1: Change the script fixture first**

Replace the fixture's startup names with:

```python
for name in (
    "firstRunColdFullyDrawn",
    "emptyColdFullyDrawn",
    "emptyWarmFullyDrawn",
    "tasks500ColdFullyDrawn",
    "tasks500WarmFullyDrawn",
    "tasks5000ColdFullyDrawn",
    "tasks5000WarmFullyDrawn",
):
```

Because the operation metrics now begin at index 7, change both
`benchmarks[8]` references to `benchmarks[7]`.

- [ ] **Step 2: Run the script test to verify RED**

Run:

```bash
bash scripts/verify-benchmark-threshold-script.sh
```

Expected: non-zero with a missing obsolete `welcomeColdFullyDrawn` or
`welcomeWarmFullyDrawn` benchmark.

- [ ] **Step 3: Update the threshold contract**

Make `startup_limits` exactly:

```python
startup_limits = {
    "firstRunColdFullyDrawn": 1500.0,
    "emptyColdFullyDrawn": 1500.0,
    "tasks500ColdFullyDrawn": 1500.0,
    "tasks5000ColdFullyDrawn": 1500.0,
    "emptyWarmFullyDrawn": 500.0,
    "tasks500WarmFullyDrawn": 500.0,
    "tasks5000WarmFullyDrawn": 500.0,
}
```

Do not compare the new first-run metric against an accepted JSON containing
the old Welcome key; the next physical API 36 qualification must capture a
new accepted baseline for this changed semantic.

- [ ] **Step 4: Replace the macrobenchmark and remove the impossible warm first run**

Replace the two Welcome tests with:

```kotlin
@Test fun firstRunColdFullyDrawn() = firstRunStartup()
```

Add:

```kotlin
private fun firstRunStartup() {
    benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { clearTarget() },
    ) {
        startActivityAndWait()
    }
}
```

Change the existing `startup` helper to accept a non-null `Int` and always call
`prepareDataset(datasetSize)` before `measureRepeated`. In `prepareDataset`,
replace:

```kotlin
waitForText("Welcome to Open Tasks")
tapText("Continue offline")
waitForDescription("Search workspace")
```

with:

```kotlin
waitForDescription("Search workspace")
```

The existing `emptyColdFullyDrawn` and `emptyWarmFullyDrawn` remain the cold
and warm measurements after a local vault exists.

- [ ] **Step 5: Run the script GREEN gate and compile the benchmark APK**

Run:

```bash
bash scripts/verify-benchmark-threshold-script.sh
./gradlew :benchmark:assembleBenchmark --console=plain
```

Expected: the script prints
`verify-benchmark-threshold-script: all checks passed` and Gradle reports
`BUILD SUCCESSFUL`.

Do not run `connectedBenchmarkAndroidTest` without the owner-approved physical
API 36 performance target.

- [ ] **Step 6: Commit the truthful startup measurement**

```bash
git add benchmark/src/main/kotlin/app/opentasks/benchmark/OpenTasksMacrobenchmark.kt scripts/check-benchmark-thresholds.sh scripts/verify-benchmark-threshold-script.sh
git commit -m "perf: benchmark automatic first-run bootstrap"
```

---

### Task 6: Reconcile living docs and run the full gate

**Files:**

- Modify: `README.md`
- Modify: `PRODUCT.md:175-195`
- Modify: `DESIGN.md:506-570`
- Modify: `HANDOFF.md:1-145`

**Interfaces:**

- Consumes: the implemented behavior and verification results from Tasks 1-5.
- Produces: one authoritative current contract and handoff; historical
  qualification records remain immutable evidence.

- [ ] **Step 1: Update the concise product entry points**

In `README.md`, replace the Welcome feature bullet with:

```markdown
- Automatic account-free first launch into an encrypted local vault, with
  generic CSV import and verified Drive/Android recovery available from More.
```

In `PRODUCT.md`, replace the fresh-install and delivered-onboarding wording
with:

```markdown
- A fresh installation creates the normal empty encrypted local vault and
  proceeds to Home without provider discovery, Google authorization, a picker,
  or an application network call.
- Account-free local use is the default and remains complete. Google
  authorization is optional and serves encrypted backup/recovery only from
  More.

### Delivered local-first launch, dashboard, and NFR programme

A missing vault now creates the normal empty local workspace automatically.
Import from another app remains top-level in More, while Backup & recovery
offers an explicit Restore existing workspace action for verified Google Drive
or Android package recovery. Google never becomes identity or authority for
local records.
```

Keep the dashboard and NFR paragraphs that follow unchanged.

- [ ] **Step 2: Replace the living Welcome design and migration entry**

In `DESIGN.md`, replace the `### Welcome` subsection with:

```markdown
### Local-first launch

A genuinely missing vault creates the normal empty encrypted local workspace
automatically and proceeds to Home. The neutral initialization surface makes
no provider discovery, Google authorization, picker request, or application
network call and does not report fully drawn before the active workspace is
ready. Unreadable, recovering, and active-replacement states retain their
existing recovery surfaces.

More keeps Import from another app at top level. Backup & recovery adds one
Restore existing workspace action that opens the existing Google Drive and
Android package recovery-source screen; verified staging, explicit takeover,
and nondestructive Back behavior remain unchanged.
```

Change the opening of `Generic CSV migration (implemented)` to:

```markdown
More places **Import from another app** at top level after Weekly review,
separate from Backup & recovery's strict **Import Open Tasks CSV** action.
Selecting it opens the existing combined mapping surface; cancellation returns
to the active workspace without changing local data.
```

Retain the mapper, bounds, accessibility, and commit semantics below it.

- [ ] **Step 3: Add the new authoritative handoff checkpoint**

Prepend a `HANDOFF.md` current-state section titled:

```markdown
## Current state — local-first launch and compact Home complete, 27 August 2026
```

State that:

- Home now uses date plus search with no greeting;
- fresh `NoVault` launch creates the empty local vault automatically;
- More > Backup & recovery opens the existing Drive/Android replacement shell;
- Welcome UI/resources and Welcome-only CSV handoff were deleted;
- first-run fully drawn now measures usable Home;
- Room/backup formats, version, signing, publication, and release state did not
  change;
- connected Compose/process/fold and physical benchmark execution are PASS only
  if they actually ran on an approved disposable/physical target, otherwise
  they remain explicitly unexecuted; and
- the unrelated workspace entries named in Global Constraints were preserved.

Record the exact commands and observed pass counts from Steps 4-6 below; do not
copy old counts or claim unexecuted device evidence.

- [ ] **Step 4: Run focused regression gates**

Run:

```bash
./gradlew :app:testDebugUnitTest :feature:home:compileDebugAndroidTestKotlin :feature:more:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :benchmark:assembleBenchmark --console=plain
bash scripts/verify-benchmark-threshold-script.sh
```

Expected: all host tests PASS, every Android test source set and benchmark APK
compile, the script self-test passes, and both commands exit zero.

- [ ] **Step 5: Run repository-wide non-device verification**

Run:

```bash
./scripts/check-schema-drift.sh
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --console=plain
git diff --check
```

Expected: no schema drift, all host tests and lint PASS, debug assembly
succeeds, and `git diff --check` prints nothing.

- [ ] **Step 6: Prove the live tree contains no obsolete entry-point contract**

Run:

```bash
rg -n "WelcomeScreen|welcome[-_]|confirmForWelcome|takeWelcomeRows|abandonWelcomeHandoff|returnToWelcome|openTasksAfterMigrationSignal|Good afternoon" app/src feature/home/src feature/more/src benchmark/src scripts
```

Expected: no matches.

Run:

```bash
rg -n "fresh installation starts at Welcome|Welcome now offers|### Welcome|Welcome places" README.md PRODUCT.md DESIGN.md
```

Expected: no matches. Do not use this result to rewrite historical specs,
plans, or qualification records.

- [ ] **Step 7: Review the exact implementation range**

Use the selected execution workflow's review step on the range beginning
immediately before Task 1 and ending at the current HEAD. Review specifically:

- whether any state other than idle ordinary `NoVault` can create a vault;
- whether the neutral bootstrap surface reports fully drawn;
- whether More recovery reaches both Drive and Android sources without
  duplicating recovery logic;
- whether first activation clears the bootstrap `RecoveryViewModel` before a
  later More recovery;
- whether Back can replace or delete an active vault;
- whether any Welcome-only transient rows or intents survive; and
- whether benchmark names, threshold fixtures, and living docs agree.

Fix every validated finding in the owning task, rerun its focused gate, then
rerun Steps 4-6 once.

- [ ] **Step 8: Commit the durable contract**

```bash
git add README.md PRODUCT.md DESIGN.md HANDOFF.md docs/superpowers/specs/2026-08-27-local-first-launch-compact-home-design.md docs/superpowers/plans/2026-08-27-local-first-launch-compact-home-plan.md
git commit -m "docs: document local-first launch"
```

The implementation is complete only after the focused review is clean, all
non-device gates pass, and every unexecuted connected/physical check is
reported without being promoted to PASS.
