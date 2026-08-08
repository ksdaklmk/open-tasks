# Release 1.0.0 sideload qualification

- Date: 8 August 2026
- Released source commit: `38fe7d1` (APK built from this tree; the tag
  sits on the commit containing this record)
- Artifact: `app-release.apk`, 15,862,887 bytes, SHA-256
  `d542b64a8143b87fdbbbcabd572293cae1303f69244f8843d7bdbb9e53c7cf57`
- Version: versionName 1.0.0, versionCode 1
- Signing: local keystore (`~/Keys/opentasks-release.jks`, alias
  `opentasks`), held and backed up by the user; nothing committed. CI
  continues to build the release unsigned.

## Automated verification

`bash scripts/verify-release-apk.sh` against the signed APK:

    verify-release-apk: all checks passed

(exit 0; checks: signature verifies, versionName/versionCode match the
build file, debug qualification activity absent from the manifest,
`auth/drive.appdata` present and sole Drive scope in the dex, not
debuggable.)

## Smoke checklist

Executed by the user on the sole disposable ADB target, the
`Pixel_10_Pro_Fold` AVD booted with `-read-only -no-snapshot-load
-no-snapshot-save`. The inherited debug build was uninstalled inside
the overlay session only; the release APK was then installed. The
emulator was shut down after the run and the ADB target list confirmed
empty; the overlay was discarded, leaving the protected persistent
workspace untouched.

| # | Step | Result |
|---|------|--------|
| 1 | Fresh launch, create vault with passphrase | pass |
| 2 | Add project, task with checklist item, tag | pass |
| 3 | Force-stop, relaunch, data persists | pass |
| 4 | `.otvault` export then import round trip, counts match | pass |
| 5 | App lock enable, background past delay, unlock | pass |
| 6 | Today widget placed, counts render | pass |
| 7 | Quick Add launcher shortcut opens the app | pass |

Seven of seven steps passed. This was the first runtime execution of
the R8-minified release build; the passing steps cover SQLCipher,
Tink, kotlinx-serialization, Room, Hilt, Glance, and BiometricPrompt
under minification.
