# Drive Release Authorization Hardening

Date: 2026-08-20. Status: user-approved design and implementation plan;
execution explicitly paused before preflight.
Authority: this spec covers the physical-release Google Drive connection
failure reported against signed sideload release 1.3.0. It is independent of
the completed Stage 9 plan. The compact-lane naked-read repair in `HANDOFF.md`
remains a separate prerequisite and is not reimplemented here.

Implementation plan:
`docs/superpowers/plans/2026-08-20-drive-release-authorization-hardening-plan.md`
(approved-plan commit `116b599`). The owner selected subagent-driven
development. No plan task, preflight step, SDD workspace, ledger, or subagent
has started; resume only on the owner's explicit instruction.

## Problem

On a Samsung Galaxy Z Fold 8 running the signed 1.3.0 APK, Backup & recovery →
Connect Google Drive opens Google's account chooser, but selecting the account
returns to Open Tasks without changing the visible state.

The failure has two boundaries:

1. The only recorded live Drive qualification used the debug signing
   certificate. The signed sideload APK uses a different, user-held release
   certificate. Google requires an Android OAuth client matching both package
   name and signing SHA-1, while production OAuth setup remained an owner gate.
2. `OpenTasksApp` forwards an authorization result only when its result code is
   `RESULT_OK` and its data is non-null. Every other outcome is discarded, so
   `EncryptedBackupViewModel` retains its pending action and continues showing
   `RemoteBackupStatus.Disabled`. The same ViewModel also swallows an exception
   thrown by the initial authorization request through its generic operation
   wrapper.

The 1.3.0 signed smoke verified APK packaging and the presence of the sole
`drive.appdata` scope, but did not execute release-signed Google authorization.

## Goal

Ship signed sideload patch release 1.3.1 (versionCode 5) with a correctly
registered release OAuth identity, no silent authorization exit, and recorded
owner-present proof that cancellation is actionable and account selection
reaches the existing Drive connection flow on the physical Fold.

## Non-goals

- Do not replace Google Play services `AuthorizationClient` or add Credential
  Manager.
- Do not add a dependency, permission, exported component, Drive scope,
  provider endpoint, database migration, backup-format change, or sync path.
- Do not add Play Console, AAB, Play App Signing, upload-key, or store-release
  work. The current distribution remains signed sideload only.
- Do not persist or commit an OAuth client file, client secret, signing
  fingerprint, account, token, provider identifier, or result intent.
- Do not redesign encrypted backup, ownership, restore, or separate-lineage
  behavior.
- Do not fold the compact CI test repair or the broader post-release backlog
  into this patch.

## Approved approach

Keep the current authorization and Drive session architecture. Repair the two
boundaries that can leave the UI unchanged, configure the current release
signing identity in Google Cloud, and add a release-signed credentialed gate.

Rejected alternatives:

- Cloud configuration alone would unblock the current certificate but leave
  canceled, null, and future provider failures silent.
- Replacing the authorization stack would enlarge the risk surface without
  evidence that `AuthorizationClient` is defective.

## Runtime design

The successful data flow remains:

1. `EncryptedBackupViewModel.connect()` requests explicit account selection.
2. `DefaultGoogleDriveAuthorizationManager.authorize()` returns a bounded
   result or a `PendingIntent` resolution.
3. `EncryptedBackupViewModel.resolutionEffects` launches that resolution from
   `OpenTasksApp`.
4. A successful result goes to `EncryptedBackupViewModel.acceptResolution()`.
5. The existing app-layer adapter accepts the result, probes Drive `about.get`
   for the account-binding permission ID, and calls the existing remote backup
   configurator.
6. The UI reaches `Preparing`, or offers the existing restore/preserve choices
   when remote backups already exist.

Two narrow changes close the failure paths.

### Incomplete Activity Result

Add this public ViewModel operation:

```kotlin
fun rejectResolution()
```

`OpenTasksApp` calls `acceptResolution(data)` only for `RESULT_OK` plus non-null
data. It calls `rejectResolution()` for every other result.

`rejectResolution()` runs under the existing operation mutex. When a connect
or re-authorise action is pending, it clears the one-shot pending action and
sets the presentation to:

```kotlin
RemoteBackupStatus.ActionRequired(
    RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED,
)
```

That reuses the existing “Needs re-authorisation” status and “Re-authorise”
action. A call with no pending action is a no-op. No provider call is made on
this path. Intentional cancellation deliberately uses this same actionable
state; no snackbar or new event system is added.

### Initial identity exception

`DefaultGoogleDriveAuthorizationManager.authorize()` must match the bounded
behavior already used by `acceptResolution()`:

- rethrow `CancellationException`;
- convert another identity-layer exception to
  `DriveAuthorizationResult.Unavailable(REJECTED)`;
- leave all existing `AuthorizationResult`, account-binding, and Drive
  transport mappings unchanged.

The existing app adapter maps `REJECTED` to
`RemoteBackupFailureCategory.AUTHORIZATION_REQUIRED`, so this closes the
initial silent path without adding a new error enum or UI copy.

## Google Cloud owner gate

An owner-present step configures the same Google Cloud project used by the
working debug qualification:

1. Derive the SHA-1 signing fingerprint from the exact signed release APK or
   its external release keystore using local Android tooling.
2. Create or verify an Android OAuth client whose package is `app.opentasks`
   and whose SHA-1 is that sideload signing fingerprint.
3. Verify the Google Drive API is enabled.
4. Verify the OAuth consent configuration permits the account selected on the
   Fold, either through the app's production audience or its bounded test-user
   list.
5. Verify `https://www.googleapis.com/auth/drive.appdata` remains the only
   Drive scope requested by the app.

The Cloud Console state is not represented by a repository credential file.
The qualification record states only that each gate passed; it records no
fingerprint, account, project identifier, token, or provider object ID.

Google's client-auth contract is the authority for the package/signing SHA-1
pair:

- <https://developers.google.com/android/guides/client-auth>
- <https://developers.google.com/workspace/guides/create-credentials>

## Security and privacy

- Preserve the current `drive.appdata` least-privilege scope.
- Preserve ephemeral token/account ownership inside `AuthorizedDriveSession`.
- Log no account details, OAuth values, Drive IDs, signing fingerprint,
  provider messages, result intent, or encryption metadata.
- Do not print or commit `keystore.properties` or keystore contents.
- The physical-device test installs over the existing app with the same
  signing identity. It must never uninstall the package or clear app data.
- Existing remote backups are never restored, replaced, deleted, or forked
  automatically during qualification. When the scan finds existing backups,
  rendering the restore/preserve choices is sufficient connection proof.

## Automated verification

Add focused JVM coverage in the existing test classes:

1. `GoogleDriveAuthorizationManagerTest` proves an exception from the initial
   identity `authorize` call returns `Unavailable(REJECTED)` without throwing.
2. The same class proves `CancellationException` still propagates.
3. `EncryptedBackupViewModelTest` proves rejecting a pending connect clears
   the one-shot resolution and presents authorization-required state.
4. The same class proves rejecting a pending re-authorisation does not request
   a backup and presents the same actionable state.
5. The same class proves a stray rejection with no pending action is a no-op.

The Activity Result callback is a trivial branch over these tested ViewModel
operations. Its real framework wiring is proven by the owner-present signed
APK gate rather than a new launcher abstraction or test-only root-composable
harness.

Run the focused app tests, then the repository CI-equivalent gate. Build the
signed release separately from lint, in accordance with `RELEASING.md`, and
verify it with `scripts/verify-release-apk.sh`.

## Signed-release qualification

Release as 1.3.1 with versionCode 5. Do not replace or retag the immutable
1.3.0 artifact.

The ordinary seven-row sideload smoke remains on a sole disposable read-only
AVD. Add an owner-present credentialed Drive section to the 1.3.1
qualification record and run it on the Fold before tagging:

1. Install 1.3.1 over 1.3.0 with the same signing identity, without uninstall
   or data clearing.
2. Open Backup & recovery and start Google Drive connection.
3. Cancel the chooser once. Verify the card shows “Needs re-authorisation” and
   offers “Re-authorise.”
4. Re-authorise, select the intended account, and complete consent if shown.
5. Accept either of these bounded success outcomes:
   - `Preparing`, followed by the existing active-backup flow for an empty
     remote namespace; or
   - the existing restore/preserve choices when backups already exist.
6. For a new connection, force-stop and relaunch and verify the persisted
   connection remains coherent. When existing backups are found, stop after
   verifying the choices in the current session; do not select an action or
   claim those ephemeral choices survive process death.

Record only bounded PASS/FAIL results. A Cloud setup failure, silent UI result,
unexpected account mismatch, or Drive authorization failure blocks the tag.

Update `RELEASING.md` so later signed-sideload releases retain the same
owner-present cancellation and successful-authorization gate. It must remain
separate from the disposable-AVD smoke because it requires an owner-controlled
Google account and may read that account's app-data namespace.

After all host, disposable-device, and owner-present gates pass, commit the
version bump and qualification record, create annotated tag `v1.3.1`, and push
only with the owner's explicit release decision under `RELEASING.md`.

## Acceptance criteria

- The exact release signing identity is accepted by Google authorization for
  package `app.opentasks`.
- Canceling or failing account selection never leaves the encrypted-backup
  card silently unchanged.
- Initial identity exceptions produce the existing actionable authorization
  state and never escape through the generic ViewModel operation wrapper.
- Successful account selection reaches `Preparing` or the existing
  restore/preserve choice without widening Drive access.
- Focused tests, the full host gate, the signed release build, APK verification,
  disposable smoke, and physical Fold credentialed gate all pass.
- Release identity is 1.3.1 / versionCode 5; 1.3.0 remains unchanged.
- No protected working-tree entry, credential, signing fingerprint, account,
  token, or Drive identifier enters a commit or qualification record.
