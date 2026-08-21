# Release 1.3.1 sideload qualification

## Status and candidate identity

**Pre-tag candidate.** All required host, disposable-device, Cloud, and
owner-present physical-device gates passed for the exact signed artefact below.
The `v1.3.1` tag and push still require the owner's explicit decision under
`RELEASING.md`.

- Date: 21 August 2026
- Pre-tag source head: `194296e9cea1f0f9d3a510754ad5690da02f0e3d`
- Version: versionName 1.3.1, versionCode 5
- Distribution: signed sideload only; no AAB or Play Console release
- Signing: unchanged external user-held release identity, with no certificate
  or signing-material detail recorded here
- Artefact: `app-release.apk`, 16,595,239 bytes, SHA-256
  `99cc4942a23c6a023d987c97f1bcf0b77f2a88fa1977842c573b3ced63cbe676`

## Automated verification

| Gate | Result |
|---|---|
| Focused `GoogleDriveAuthorizationManagerTest` and `EncryptedBackupViewModelTest` suites | PASS |
| `./gradlew testDebugUnitTest lintDebug :app:assembleDebug` | PASS |
| Separate `./gradlew :app:assembleRelease` | PASS |
| `bash scripts/verify-release-apk.sh` | PASS — 1.3.1/5, signed, non-debuggable, no debug qualification activity, and `auth/drive.appdata` is the sole Drive scope |

## Signed disposable-AVD smoke

Executed as one fresh complete run against the exact signed APK on the sole
audited disposable `Fold8_Acceptance` AVD, booted with `-read-only
-no-snapshot-load -no-snapshot-save`. Every row below was observed; no partial
result from the earlier stopped run is included.

| # | Required step | Result |
|---|---|---|
| 1 | Fresh launch and `Start without restoring` | PASS — local workspace reached Home |
| 2 | Add a project, task with one checklist item, and tag | PASS — all four synthetic items were created |
| 3 | Force-stop and relaunch | PASS — the row-2 content persisted |
| 4 | Export and import `.otvault` | PASS — preview counts matched and replacement restored the project, task, checklist, and tag |
| 5 | Enable app lock, background past its delay, and unlock | PASS — the lock appeared and the temporary credential restored access |
| 6 | Place the Today widget | PASS — counts and Quick Add rendered |
| 7 | Open through the Quick Add launcher shortcut | PASS — the shortcut opened Quick Add |

Summary: **7 of 7 rows PASS.**

## Google Cloud gate

| Required gate | Result |
|---|---|
| Android OAuth client matches package `app.opentasks` and the exact signed-release identity | PASS |
| Google Drive API enabled | PASS |
| Intended account permitted by the consent audience | PASS |
| `https://www.googleapis.com/auth/drive.appdata` remains the sole Drive scope | PASS |

Summary: **4 of 4 Cloud gates PASS.** No account, certificate fingerprint,
OAuth client or project identifier, token, or Drive identifier is recorded.

## Owner-present physical Fold gate

| Required gate | Result |
|---|---|
| Sole authorized physical target and in-place 1.3.1 update over 1.3.0 | PASS — existing workspace preserved; no uninstall or data clear |
| Cancel the initial account chooser | PASS — card showed `Needs re-authorisation` with `Re-authorise` |
| Re-authorise with the intended account | PASS — release identity was accepted and the card reached `Backed up` |
| Force-stop, relaunch, and verify the new connection | PASS — the encrypted-backup card remained coherent at `Backed up` |

No connected suite ran on the Fold, and no existing remote backup was
restored, replaced, deleted, or forked.

## Disposable cleanup

Complete. The exported archive and temporary credential were removed, the
package was uninstalled only from the audited disposable overlay, the overlay
was terminated, and generated helpers and screenshots were deleted. Zero ADB
targets and no emulator process were verified before the physical gate. The
protected `Pixel_10_Pro_Fold` AVD was never booted.

## Pre-tag status

The exact 1.3.1 / versionCode 5 candidate is qualified for signed sideload.
This record does not create or authorize a tag or push; both remain pending a
fresh explicit owner decision.
