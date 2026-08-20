# Drive Release Authorization Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship signed sideload release 1.3.1 (versionCode 5) so the exact
release signing identity can authorize package `app.opentasks`, every account
chooser exit becomes visible and actionable, and the fix is proven on the
owner's physical Galaxy Z Fold 8 before tagging.

**Architecture:** Keep the existing Google Play services
`AuthorizationClient`, Drive session, and backup flow. Bound exceptions at the
authorization-manager identity boundary, consume canceled or incomplete
Activity Results through one new ViewModel operation, and reuse the existing
authorization-required presentation. Treat Google Cloud OAuth configuration
and signed-device qualification as release gates, not repository
configuration.

**Tech Stack:** Kotlin, Android Activity Result APIs, Jetpack ViewModel and
kotlinx.coroutines, Google Play services Auth, JUnit 4, Gradle/AGP 9, and the
existing signed-sideload tooling. No new dependency, permission, scope,
component, database change, or backup-format change.

**Spec:**
`docs/superpowers/specs/2026-08-20-drive-release-authorization-hardening-design.md`

**Execution Status (2026-08-21):** Preflight and Tasks 1–5 are complete and
review-clean; execution is paused by the owner before Task 6. Runtime commits
`67820e7` and `875c80e` bound initial identity failures and consume incomplete
launcher results. Commit `da02ddc` makes the signed owner-present Drive check a
permanent pre-tag gate. The combined focused suite passed, and the Task 4
review of `816e134..da02ddc` returned 0 Critical, 0 Important, and 0 Minor
findings. Task 5's final host gate, separate signed release assembly, APK
verifier, and independent review passed for the exact uncommitted 1.3.1 /
versionCode 5 candidate. No certificate inspection, Cloud mutation, device
qualification, qualification record, tag, or push has started. Resume at Task
6 only on the owner's explicit instruction.

## Global Constraints

- **Start gate (satisfied):** the separate compact API 36 test repairs are
  committed as `670d915` and `3e1a5a7`; run `32382258182` is green. Do not edit
  either instrumented test as part of this plan.
- Work directly on `main`, as required by `CLAUDE.md`. Record the start commit
  before Task 1 so the final review has an exact range.
- Preserve the four user-owned working-tree entries: the modified Stage 3
  Google Drive plan, the deleted Thai-dashboard spec, and untracked `.kotlin/`
  and `artifacts/`. Never stage, restore, clean, or rewrite them.
- Keep `https://www.googleapis.com/auth/drive.appdata` as the only Drive scope.
  Add no endpoint, credential file, OAuth secret, account persistence, or
  logging.
- Never print, paste, commit, or record an account, token, OAuth client ID,
  Drive identifier, result intent, provider message, signing fingerprint,
  keystore path, alias, or password. The owner may inspect the release SHA-1
  locally while configuring Google Cloud, but it must not enter the agent
  transcript or repository.
- Do not run any connected test suite against the physical Fold or the
  protected `Pixel_10_Pro_Fold` AVD. Connected suites and destructive smoke
  setup are allowed only on a verified disposable read-only AVD that is the
  sole ADB target.
- Install 1.3.1 over 1.3.0 on the physical Fold with the unchanged signing
  identity. Never uninstall `app.opentasks` or clear its data on that device.
- Existing remote backups must never be restored, replaced, deleted, or forked
  during qualification. Rendering the existing restore/preserve choices is a
  complete success outcome.
- Release builds run separately from lint. The final host gate is
  `./gradlew testDebugUnitTest lintDebug :app:assembleDebug`; the signed build
  is a later `./gradlew :app:assembleRelease` invocation.
- `v1.3.0` is immutable. The only release identity in this plan is 1.3.1 with
  versionCode 5. Tag and push only after a fresh, explicit owner release
  decision.
- Each code task follows red-green-refactor, stages only its literal files,
  runs `git diff --cached --check`, commits, and is reviewed before the next
  task. Do not add an abstraction for the Activity Result branch or a new UI
  event system.

## File Map

Runtime and tests:

- `app/src/main/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManager.kt`
  — convert identity-layer authorization exceptions to a bounded result while
  preserving coroutine cancellation.
- `app/src/test/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManagerTest.kt`
  — prove ordinary identity failure is bounded and cancellation propagates.
- `app/src/main/kotlin/app/opentasks/backup/EncryptedBackupViewModel.kt` — add
  the one-shot `rejectResolution()` operation under the existing mutex.
- `app/src/test/kotlin/app/opentasks/backup/EncryptedBackupViewModelTest.kt` —
  prove pending connect/re-authorize rejection and stray-rejection behavior.
- `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt` — route every incomplete
  launcher result to `rejectResolution()`.

Release process and evidence:

- `RELEASING.md` — make the owner-present, release-signed Drive gate mandatory
  before every signed-sideload tag.
- `app/build.gradle.kts` — set versionName 1.3.1 and versionCode 5 only after
  the implementation is reviewed.
- `docs/qualification/release-1.3.1-sideload.md` — record actual host,
  disposable-AVD, Cloud, and physical-Fold results without private identity
  data.
- `HANDOFF.md` — after the release decision, record the final outcome and the
  remaining backlog.

External owner-controlled state:

- Google Cloud Console — Android OAuth client for package `app.opentasks` and
  the exact external sideload signing SHA-1; Drive API and consent audience.
  No repository file represents this state.

## Preflight Gate: Finish the Separate Compact-CI Repair

- [x] **Step 1: Confirm the prerequisite is complete**

Read the current resume point and recent history:

```bash
sed -n '1,110p' HANDOFF.md
git log -5 --oneline --decorate
git status --short
```

Expected: the naked-read repair is recorded as committed, the compact API 36
lane for that commit is green, and the only unstaged entries are the four
protected user-owned entries. If either repair or CI proof is missing, stop
this plan and complete that separate handoff item first.

- [x] **Step 2: Record the implementation review base**

```bash
git rev-parse HEAD
```

Keep that commit value in the execution notes as `driveAuthBase`. Do not write
it into a tracked source file. The whole-change review in Task 4 covers
`driveAuthBase..HEAD`.

### Task 1: Bound Initial Google Identity Authorization Failures

**Files:**

- Modify:
  `app/src/test/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManagerTest.kt`
- Modify:
  `app/src/main/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManager.kt`

**Consumes:** `DriveIdentityBoundary.authorize`,
`DriveAuthorizationResult.Unavailable`, and
`DriveAuthorizationUnavailableReason.REJECTED`.

**Produces:** `DefaultGoogleDriveAuthorizationManager.authorize()` returns a
bounded rejection for an ordinary identity exception and still propagates
`CancellationException`.

- [x] **Step 1: Add the two failing manager tests**

Add the cancellation import and these tests beside the existing resolution
extraction-failure test:

```kotlin
import kotlinx.coroutines.CancellationException

@Test
fun authorizeFailureReportsRejectedWithoutThrowing() = runBlocking {
    val manager = manager(
        permissionId = "account-a",
        authorizeFailure = IllegalStateException("identity failed"),
    )

    val result = manager.authorize(
        DriveAuthorizationMode.EXPLICIT_ACCOUNT,
        expectedAccountDigest = null,
    )

    assertEquals(
        DriveAuthorizationResult.Unavailable(
            DriveAuthorizationUnavailableReason.REJECTED,
        ),
        result,
    )
    assertEquals(0, manager.transportCount)
}

@Test
fun authorizeCancellationStillPropagates() {
    val cancellation = CancellationException("identity cancelled")
    val manager = manager(
        permissionId = "account-a",
        authorizeFailure = cancellation,
    )

    val thrown = assertThrows(CancellationException::class.java) {
        runBlocking {
            manager.authorize(
                DriveAuthorizationMode.EXPLICIT_ACCOUNT,
                expectedAccountDigest = null,
            )
        }
    }

    assertSame(cancellation, thrown)
}
```

Extend the existing fake at the single identity boundary. Insert
`authorizeFailure` immediately before `resultFromIntentFailure` in the
`manager` parameter list:

```kotlin
authorizeFailure: Exception? = null,
```

Replace the existing positional fake construction with:

```kotlin
val identity = FakeDriveIdentityBoundary(
    outcome = outcome,
    authorizeFailure = authorizeFailure,
    resultFromIntentFailure = resultFromIntentFailure,
    revokeFailure = revokeFailure,
)
```

Replace the fake constructor declaration with:

```kotlin
private class FakeDriveIdentityBoundary(
    private val outcome: DriveAuthorizationOutcome,
    private val authorizeFailure: Exception? = null,
    private val resultFromIntentFailure: Exception? = null,
    private val revokeFailure: Exception? = null,
) : DriveIdentityBoundary {
```

In its existing `authorize` implementation, insert the throw after recording
the request and before returning the outcome:

```kotlin
authorizeSpecs += spec
authorizeFailure?.let { throw it }
return outcome
```

- [x] **Step 2: Run the manager test and verify the red state**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.drive.GoogleDriveAuthorizationManagerTest'
```

Expected: the suite fails because `IllegalStateException("identity failed")`
escapes `authorizeFailureReportsRejectedWithoutThrowing`. The cancellation
test should already pass.

- [x] **Step 3: Implement the bounded identity catch**

Add the cancellation import to the production file:

```kotlin
import kotlinx.coroutines.CancellationException
```

Wrap only the call to `identity.authorize`; leave `resolveOutcome` and all
Drive transport mappings unchanged:

```kotlin
val outcome = try {
    identity.authorize(
        DriveAuthorizationRequestSpec(
            scopes = listOf(DRIVE_APPDATA_SCOPE),
            promptSelectAccount = mode == DriveAuthorizationMode.EXPLICIT_ACCOUNT,
        ),
    )
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    return unavailable(DriveAuthorizationUnavailableReason.REJECTED)
}
return resolveOutcome(mode, outcome, expectedAccountDigest)
```

Do not log or retain the exception. Catch `Exception`, not `Throwable`.

- [x] **Step 4: Run the focused test and inspect the diff**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.drive.GoogleDriveAuthorizationManagerTest'
git diff --check
git diff -- \
  app/src/main/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManager.kt \
  app/src/test/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManagerTest.kt
```

Expected: the complete manager test class passes; the production diff contains
one bounded catch and one import.

- [x] **Step 5: Commit only Task 1**

```bash
git add \
  app/src/main/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManager.kt \
  app/src/test/kotlin/app/opentasks/backup/drive/GoogleDriveAuthorizationManagerTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "fix: bound Drive authorization failures"
```

Expected staged names: exactly the two Task 1 files.

### Task 2: Consume Rejected or Incomplete Activity Results

**Files:**

- Modify:
  `app/src/test/kotlin/app/opentasks/backup/EncryptedBackupViewModelTest.kt`
- Modify:
  `app/src/main/kotlin/app/opentasks/backup/EncryptedBackupViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`

**Consumes:** the existing `pendingAction`, operation `Mutex`,
`presentation(RemoteBackupStatus)`, and Activity Result launcher.

**Produces:** public `fun rejectResolution()`, one-shot pending-action cleanup,
the existing authorization-required UI state, and total launcher-result
routing.

- [x] **Step 1: Add the three failing ViewModel tests**

Add these tests next to the existing resolution tests. They use only helpers
and imports already present in the class:

```kotlin
@Test
fun rejectingPendingConnectIsActionableAndConsumesResolution() {
    val pending = pendingIntent()
    val connectCalls = AtomicInteger()
    val unexpectedResolvedCall = CountDownLatch(1)
    val viewModel = viewModel(
        connect = { _, resolution ->
            connectCalls.incrementAndGet()
            if (resolution == null) {
                EncryptedBackupActionResult.ResolutionRequired(pending)
            } else {
                unexpectedResolvedCall.countDown()
                EncryptedBackupActionResult.Completed
            }
        },
    )

    viewModel.connect()
    assertSame(pending, takeResolution(viewModel))

    viewModel.rejectResolution()

    val expected = RemoteBackupStatus.ActionRequired(
        RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
    )
    assertTrue(waitUntil { viewModel.presentation.value.status == expected })

    viewModel.acceptResolution(Intent())
    assertFalse(unexpectedResolvedCall.await(250, TimeUnit.MILLISECONDS))
    assertEquals(1, connectCalls.get())
}

@Test
fun rejectingPendingReauthorisationIsActionableWithoutBackupRequest() {
    val pending = pendingIntent()
    val reauthoriseCalls = AtomicInteger()
    val backupRequests = AtomicInteger()
    val viewModel = viewModel(
        status = MutableStateFlow(
            RemoteBackupStatus.ActionRequired(
                RemoteBackupFailureCategory.ACCOUNT_MISMATCH,
            ),
        ),
        reauthorise = { resolution ->
            reauthoriseCalls.incrementAndGet()
            if (resolution == null) {
                EncryptedBackupActionResult.ResolutionRequired(pending)
            } else {
                EncryptedBackupActionResult.Completed
            }
        },
        requestBackupNow = { backupRequests.incrementAndGet() },
    )

    viewModel.reauthorise()
    assertSame(pending, takeResolution(viewModel))

    viewModel.rejectResolution()

    val expected = RemoteBackupStatus.ActionRequired(
        RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
    )
    assertTrue(waitUntil { viewModel.presentation.value.status == expected })
    assertEquals(1, reauthoriseCalls.get())
    assertEquals(0, backupRequests.get())
}

@Test
fun rejectingWithoutPendingActionIsNoOp() {
    val viewModel = viewModel()
    val before = viewModel.presentation.value

    viewModel.rejectResolution()
    Thread.sleep(100)

    assertEquals(before, viewModel.presentation.value)
}
```

- [x] **Step 2: Run the ViewModel test and verify the red state**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.EncryptedBackupViewModelTest'
```

Expected: test compilation fails because `rejectResolution()` does not exist.

- [x] **Step 3: Implement the one-shot rejection operation**

Add this beside `acceptResolution()` and reuse `launchOperation`; add no new
dispatcher, channel, state type, or copy:

```kotlin
fun rejectResolution() = launchOperation {
    pendingAction ?: return@launchOperation
    pendingAction = null
    presented.value = presentation(
        RemoteBackupStatus.ActionRequired(
            RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
        ),
    )
}
```

This runs under the same mutex as connect and re-authorization. A stray call
returns without changing presentation.

- [x] **Step 4: Route every launcher result**

Replace the nullable success-only chain in `OpenTasksApp` with the complete
branch:

```kotlin
) { result ->
    val data = result.data
    if (result.resultCode == Activity.RESULT_OK && data != null) {
        encryptedBackupViewModel.acceptResolution(data)
    } else {
        encryptedBackupViewModel.rejectResolution()
    }
}
```

Do not create a launcher wrapper or an instrumentation harness. The ViewModel
contract is covered by JVM tests; real Activity Result wiring is covered by
the signed Fold gate in Task 8.

- [x] **Step 5: Run focused tests and compile the app**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.EncryptedBackupViewModelTest'
./gradlew :app:assembleDebug
git diff --check
git diff -- \
  app/src/main/kotlin/app/opentasks/backup/EncryptedBackupViewModel.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt \
  app/src/test/kotlin/app/opentasks/backup/EncryptedBackupViewModelTest.kt
```

Expected: all ViewModel tests pass and the app assembles. The runtime diff is
only the new ViewModel operation plus the launcher branch.

- [x] **Step 6: Commit only Task 2**

```bash
git add \
  app/src/main/kotlin/app/opentasks/backup/EncryptedBackupViewModel.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt \
  app/src/test/kotlin/app/opentasks/backup/EncryptedBackupViewModelTest.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "fix: surface rejected Drive authorization"
```

Expected staged names: exactly the three Task 2 files.

### Task 3: Make Release-Signed Drive Authorization a Permanent Gate

**Files:**

- Modify: `RELEASING.md`

**Consumes:** the existing per-release process, disposable-AVD smoke, and
physical-device update rules.

**Produces:** a mandatory pre-tag owner-present Drive gate that remains
separate from the disposable smoke.

- [x] **Step 1: Update the per-release order**

Keep the existing build and seven-row disposable smoke steps. Insert the
owner-present Drive gate after disposable smoke and before the qualification
commit/tag. State that the same qualification record contains both sections.
Change physical update examples to `adb install -r` and retain the warning
that uninstalling or clearing data is forbidden on a real workspace.

- [x] **Step 2: Add the exact owner-present gate**

Add a section with this contract:

```markdown
## Owner-present Google Drive gate (per release, physical device only)

This gate is separate from the disposable-AVD smoke. It uses the exact signed
release APK, the owner-controlled Google account, and that account's hidden
Drive app-data namespace. Run it before tagging.

Before installation, the owner verifies in Google Cloud that:

1. an Android OAuth client matches package `app.opentasks` and the exact
   sideload signing SHA-1;
2. the Google Drive API is enabled;
3. the selected account is permitted by the OAuth consent audience or test-user
   list; and
4. `https://www.googleapis.com/auth/drive.appdata` remains the app's only Drive
   scope.

The owner may inspect the APK certificate locally with `apksigner`, but must
not paste or record its fingerprint, account, project, client ID, or signing
material.

Install over the existing app with the unchanged signing identity:

    adb install -r app/build/outputs/apk/release/app-release.apk

Never uninstall the package, clear its data, or run a connected test suite on
the physical device. Then:

1. Open Backup & recovery and start Google Drive connection.
2. Cancel the chooser once. The card must show `Needs re-authorisation` and
   offer `Re-authorise`.
3. Re-authorise, select the intended account, and complete consent if shown.
4. Accept `Preparing` for an empty app-data namespace, or the existing
   restore/preserve choices when backups already exist.
5. For a new connection only, force-stop and relaunch; the connection must
   remain coherent. If existing backups are found, stop after the choices
   render and select neither action.

Record bounded PASS/FAIL results in the release qualification. A Cloud gate
failure, silent result, account mismatch, or Drive authorization failure blocks
the tag.
```

Do not add certificate values, screenshots of account UI, or provider details
to `RELEASING.md`.

- [x] **Step 3: Verify and commit the release-process change**

```bash
rg -n "Owner-present Google Drive gate|adb install -r|Needs re-authorisation|drive.appdata" \
  RELEASING.md
git diff --check
git add RELEASING.md
git diff --cached --check
git diff --cached --name-only
git commit -m "docs: require signed Drive authorization smoke"
```

Expected staged name: only `RELEASING.md`.

### Task 4: Review the Complete Runtime Patch

**Files:** no planned edits. A validated finding may modify only the files from
Tasks 1–3 and must receive its own focused regression test.

**Consumes:** `driveAuthBase..HEAD`, the approved spec, and the focused test
evidence.

**Produces:** reviewed implementation with zero unresolved blocking findings.

- [x] **Step 1: Run the combined focused suite**

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.opentasks.backup.drive.GoogleDriveAuthorizationManagerTest' \
  --tests 'app.opentasks.backup.EncryptedBackupViewModelTest'
git diff --check
git status --short
```

Expected: both test classes pass. The four protected entries remain unstaged.

- [x] **Step 2: Review the exact implementation range**

Use `superpowers:requesting-code-review` against the approved spec, this plan,
the recorded `driveAuthBase`, and `driveAuthBase..HEAD`. Require checks for:

- ordinary identity failure is bounded at the shared manager boundary;
- `CancellationException` is never swallowed;
- canceled, non-OK, and null-data launcher results clear pending work and
  become actionable;
- successful non-null results still take the original path;
- rejected re-authorization never requests a backup;
- no new scope, dependency, manifest surface, persistence, or log exists; and
- release documentation puts the credentialed physical gate before tagging.

If execution mode does not authorize a reviewer subagent, perform this same
checklist inline and record that limitation in the execution notes.

- [x] **Step 3: Resolve findings before release preparation**

For each valid blocking finding, first add a focused failing test, then make
the smallest fix, rerun both focused classes, stage only the touched plan
files, and commit with:

```bash
git commit -m "fix: resolve Drive authorization review findings"
```

Repeat review until no blocking finding remains. Do not implement speculative
polish or broaden this patch.

### Task 5: Build the Exact 1.3.1 Release Candidate

**Files:**

- Modify: `app/build.gradle.kts`

**Consumes:** the reviewed runtime patch, external release keystore, and
existing APK verifier.

**Produces:** an uncommitted 1.3.1/5 version bump and exact signed candidate
that passes the final host and APK gates.

The version bump intentionally remains uncommitted through Tasks 6–8. The
release process commits it together with observed qualification evidence.

- [x] **Step 1: Set the patch release identity**

Change only these two values:

```kotlin
versionCode = 5
versionName = "1.3.1"
```

- [x] **Step 2: Verify signing setup is local and ignored**

```bash
test -f keystore.properties
git check-ignore -v keystore.properties
git status --short
```

Expected: `keystore.properties` exists and is ignored. Do not open or print it.
The protected entries plus `app/build.gradle.kts` are unstaged.

- [x] **Step 3: Run the final host gate**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug
```

Expected: all unit tests, lint, and the debug assembly pass. Any failure blocks
the release candidate.

- [x] **Step 4: Build and verify release separately**

```bash
./gradlew :app:assembleRelease
bash scripts/verify-release-apk.sh
shasum -a 256 app/build/outputs/apk/release/app-release.apk
wc -c app/build/outputs/apk/release/app-release.apk
```

Expected: the signed build and verifier pass; the verifier reports 1.3.1/5,
non-debuggable packaging, no debug qualification activity, and only
`auth/drive.appdata`. Retain the APK SHA-256 and byte count for the
qualification record; neither is private.

- [x] **Step 5: Keep the candidate uncommitted**

```bash
git diff --check
git diff -- app/build.gradle.kts
git status --short
```

Expected: the build-file diff contains only the two version values. Do not
commit or tag yet.

**Execution checkpoint (2026-08-21):** Task 5 is complete and independently
review-clean. The host gate, separate release assembly, and APK verifier
passed. The exact candidate is 16,595,239 bytes with SHA-256
`a3bd0ad3169189469c5a1ff7311bec56f82019caa67b8eb40728bb0d1c3ab066`.
The version bump remains unstaged and uncommitted. The owner instructed a pause
before Task 6; no certificate inspection, Cloud mutation, AVD/ADB action, or
physical-device qualification has started.

### Task 6: Complete the Owner-Present Google Cloud Gate

**Files:** none. This task mutates only owner-controlled Google Cloud state.

**Consumes:** the exact Task 5 signed APK and the Google Cloud project used by
the working debug qualification.

**Produces:** release OAuth identity accepted for package `app.opentasks`,
with Drive API and consent audience ready for the Fold account.

- [ ] **Step 1: Have the owner inspect the exact certificate locally**

Pause for the owner. On the owner's screen, outside captured agent output, use
the installed Android tool on the exact candidate:

```bash
/Users/kk/Library/Android/sdk/build-tools/36.0.0/apksigner \
  verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The owner reads the SHA-1 directly and does not paste it into chat, notes, or a
tracked file.

- [ ] **Step 2: Configure or verify the Android OAuth client**

In the same Cloud project as the debug qualification, the owner creates or
verifies one Android OAuth client with:

- package name exactly `app.opentasks`; and
- SHA-1 exactly matching the Task 5 signed APK.

No client secret or downloaded credential file is required or accepted by
this plan.

- [ ] **Step 3: Verify API and consent state**

The owner confirms all three facts in Cloud Console:

1. Google Drive API is enabled for the project.
2. The account to be selected on the Fold is permitted by the production
   audience or bounded test-user list.
3. The app continues to request only
   `https://www.googleapis.com/auth/drive.appdata`.

Record only four bounded results in execution notes: Android client PASS,
Drive API PASS, consent audience PASS, and least-privilege scope PASS. Do not
record identifiers or values. Any non-PASS result stops the release.

### Task 7: Run the Ordinary Disposable-AVD Sideload Smoke

**Files:** none yet. Keep observed results for Task 9.

**Consumes:** the exact Task 5 signed APK and the Task 3 `RELEASING.md`
checklist.

**Produces:** seven observed smoke results without touching a protected
workspace.

- [ ] **Step 1: Establish a sole disposable target**

Start the established disposable AVD with all three safety flags:

```text
-read-only -no-snapshot-load -no-snapshot-save
```

Before any install, confirm `adb devices` shows exactly one target and
`adb emu avd name` identifies the expected disposable AVD. Abort if the target
is physical, `Pixel_10_Pro_Fold`, unknown, persistent, or not the sole target.

- [ ] **Step 2: Install only inside the disposable overlay**

After the identity check, uninstall any inherited debug package from the
read-only overlay and install the exact candidate:

```bash
/Users/kk/Library/Android/sdk/platform-tools/adb uninstall app.opentasks
/Users/kk/Library/Android/sdk/platform-tools/adb install \
  app/build/outputs/apk/release/app-release.apk
```

These destructive commands are authorized only for the verified disposable
overlay. Never reuse them on the Fold or protected AVD.

- [ ] **Step 3: Execute all seven smoke rows**

Observe every current `RELEASING.md` row:

1. Fresh launch and `Start without restoring` create a local workspace.
2. Add a project, task with one checklist item, and tag.
3. Force-stop and relaunch; all row-2 content persists.
4. Export and import `.otvault`; counts match.
5. Enable app lock, background past its delay, and unlock.
6. Place the Today widget; counts render.
7. Open the app through the Quick Add launcher shortcut.

No row may be waived. Record only bounded observed facts and synthetic smoke
content; no account or Drive data is used in this task.

- [ ] **Step 4: Clean up only the disposable overlay**

Delete the exported archive, clear any temporary screen credential, uninstall
the app from the verified overlay, and terminate that overlay. Confirm no ADB
target remains before Task 8. Do not boot or modify `Pixel_10_Pro_Fold`.

### Task 8: Qualify Authorization on the Physical Galaxy Z Fold 8

**Files:** none yet. Keep observed results for Task 9.

**Consumes:** the exact Task 5 signed APK and successful Task 6 Cloud state.

**Produces:** owner-observed cancellation and successful release-signed Drive
authorization, without destructive local or remote action.

- [ ] **Step 1: Establish the owner-controlled physical target**

With every emulator stopped, connect the Fold by USB or wireless debugging.
The owner confirms `adb devices` has exactly one authorized physical target.
Do not record its serial. If ADB is unavailable, the owner may use the Files
installer, but must still install the exact Task 5 APK over the existing app.

- [ ] **Step 2: Update in place**

For ADB installation, run:

```bash
/Users/kk/Library/Android/sdk/platform-tools/adb install -r \
  app/build/outputs/apk/release/app-release.apk
```

Expected: `Success`, with the 1.3.0 workspace preserved. Stop immediately on a
signature mismatch; never fix it by uninstalling or clearing data.

- [ ] **Step 3: Prove cancellation is actionable**

On the Fold:

1. Open Backup & recovery.
2. Tap `Connect Google Drive`.
3. Cancel the Google account chooser once.
4. Verify the card shows `Needs re-authorisation` and offers `Re-authorise`.

A silent unchanged card fails the gate.

- [ ] **Step 4: Prove release-signed authorization succeeds**

Tap `Re-authorise`, select the intended account, and complete consent if
shown. Accept exactly one of these outcomes:

- `Preparing`, followed by the existing active-backup flow when the app-data
  namespace has no prior Open Tasks backup; or
- the existing restore/preserve choices when prior backups are found.

An account mismatch, silent return, or authorization/provider failure blocks
the release. Do not inspect provider identifiers or capture account UI.

- [ ] **Step 5: Check persistence only when a new connection was made**

If Task 8 Step 4 reached `Preparing`, force-stop without clearing data:

```bash
/Users/kk/Library/Android/sdk/platform-tools/adb shell am force-stop app.opentasks
```

Relaunch manually and verify the connection remains coherent. If Step 4
showed restore/preserve choices, stop in the current session: select neither
choice and make no claim about those ephemeral choices surviving process
death.

- [ ] **Step 6: Report only bounded results**

Retain these facts for Task 9: in-place update PASS, cancellation PASS,
authorization PASS with either `Preparing` or choices, and persistence PASS or
correctly not applicable because choices were shown. Record no account,
fingerprint, client/project ID, token, Drive object ID, or provider message.

### Task 9: Record and Commit the Qualified 1.3.1 Candidate

**Files:**

- Modify: `app/build.gradle.kts`
- Create: `docs/qualification/release-1.3.1-sideload.md`

**Consumes:** actual Task 5–8 evidence only.

**Produces:** one pre-tag qualification commit containing version 1.3.1/5 and
complete bounded evidence.

- [ ] **Step 1: Create the qualification record from observed facts**

Use the structure of `docs/qualification/release-1.3.0-sideload.md`, but write
only facts observed for the exact 1.3.1 APK. Include:

- status and candidate identity: date, pre-tag source head, 1.3.1/5, APK byte
  count and SHA-256, signed-sideload distribution, unchanged external signing
  identity without certificate details;
- automated verification: focused suites, final host gate, separate signed
  release build, and `verify-release-apk.sh` result;
- seven-row disposable smoke table with an observed result in every row;
- Google Cloud gate table with Android client, Drive API, consent audience,
  and sole-scope PASS/FAIL only;
- physical Fold table with in-place update, canceled chooser, successful
  authorization outcome, and the bounded persistence result;
- disposable cleanup statement; and
- pre-tag status stating that tag/push still require the owner's decision.

Do not copy Stage 9 extras or the 1.2.0 migration row; they are not 1.3.1
obligations. Do not write a PASS that was not observed.

- [ ] **Step 2: Audit the record for private data and completeness**

Manually verify the record contains no email/account, device serial, signing
fingerprint, OAuth client/project identifier, token, Drive identifier,
provider message, keystore detail, or screenshot of identity UI.

```bash
rg -n "^## |^\|" docs/qualification/release-1.3.1-sideload.md
rg -n "1.3.1|versionCode 5|drive.appdata|7 of 7|pre-tag" \
  docs/qualification/release-1.3.1-sideload.md
git diff --check
```

Expected: every required heading/table is present, all required gates are
bounded and complete, and no whitespace error exists.

- [ ] **Step 3: Re-verify the exact APK immediately before commit**

```bash
bash scripts/verify-release-apk.sh
shasum -a 256 app/build/outputs/apk/release/app-release.apk
wc -c app/build/outputs/apk/release/app-release.apk
```

Expected: verifier, hash, and size exactly match the qualification record.

- [ ] **Step 4: Stage only release identity and evidence**

```bash
git add app/build.gradle.kts \
  docs/qualification/release-1.3.1-sideload.md
git diff --cached --check
git diff --cached --name-only
git status --short
```

Expected staged names: exactly the two Task 9 files. The four protected
entries remain unstaged.

- [ ] **Step 5: Commit the qualified candidate**

```bash
git commit -m "docs: qualify release 1.3.1 for signed sideload"
```

Do not tag or push in this step.

### Task 10: Release Only on the Owner's Explicit Decision

**Files:**

- Modify after release: `HANDOFF.md`

**Consumes:** the Task 9 qualification commit and a fresh owner decision.

**Produces:** immutable `v1.3.1`, pushed release state, and an accurate live
handoff.

- [ ] **Step 1: Present the release evidence and ask for the decision**

Report the Task 9 commit, final host gate, verifier, 7/7 disposable smoke,
Cloud 4/4 gate, Fold cancellation result, Fold success outcome, and protected
working-tree status. Ask the owner whether to create and push `v1.3.1`.

If the owner does not explicitly approve both tag and push, stop with the
qualified commit untagged.

- [ ] **Step 2: Create and push the immutable release**

Only after approval:

```bash
git tag -a v1.3.1 -m "Release 1.3.1"
git push origin main v1.3.1
```

Never move or replace `v1.3.0` or `v1.3.1`.

- [ ] **Step 3: Observe remote release checks**

Use the pushed release commit to observe the repository's verify, release,
and compact API 36 jobs. Record exact run IDs and conclusions. The expanded
API 37.0 lane retains its documented observe-only policy; any new failure
signature requires diagnosis before declaring the handoff current.

- [ ] **Step 4: Update the authoritative handoff**

Replace the current resume point in `HANDOFF.md` with observed facts:

- 1.3.1 commit, tag, push date, and remote-check outcomes;
- the reported Fold symptom and its two-part root cause;
- the runtime fix, Cloud Android-client gate, and physical Fold results;
- the compact naked-read prerequisite outcome; and
- the still-open post-release backlog, without resurrecting completed items.

Keep older checkpoints historical. Record no private account, OAuth, signing,
device, or Drive identifier.

- [ ] **Step 5: Commit and push the handoff update**

```bash
git add HANDOFF.md
git diff --cached --check
git diff --cached --name-only
git commit -m "docs: record 1.3.1 Drive authorization release"
git push origin main
```

Expected staged name: only `HANDOFF.md`; the four protected entries remain
unstaged.

## Completion Checklist

- [x] Compact API 36 prerequisite repairs are committed and green
  (`670d915`, `3e1a5a7`; run `32382258182`).
- [x] Initial identity exceptions map to `Unavailable(REJECTED)`.
- [x] `CancellationException` still propagates.
- [x] Every incomplete Activity Result calls `rejectResolution()`.
- [x] Rejection consumes pending work and shows the existing actionable state.
- [x] All five new JVM cases and all existing focused tests pass.
- [x] Full host gate and separately invoked signed release build pass.
- [x] APK verifier confirms 1.3.1/5, non-debuggable packaging, and sole
  `drive.appdata` scope.
- [ ] Google Cloud Android client, Drive API, and consent audience gates pass.
- [ ] Disposable sideload smoke passes 7/7 and is cleaned up safely.
- [ ] Physical Fold update, cancellation, and authorization gates pass without
  uninstall, data clearing, connected tests, or remote mutation.
- [ ] Qualification record contains only bounded evidence.
- [ ] Version bump and record are committed before tag creation.
- [ ] Tag/push occur only after explicit owner approval.
- [ ] `HANDOFF.md` reflects the released state and remaining backlog.
- [ ] The four protected working-tree entries remain untouched and unstaged.
