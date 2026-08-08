# Release readiness (sideload) — design

- Date: 8 August 2026
- Status: executed and closed — released as `v1.0.0` (tag on
  `57703d2`); qualification record
  `docs/qualification/release-1.0.0-sideload.md`
- Predecessor: Stage 5 closure (see `HANDOFF.md`)

## Goal

Produce a signed, R8-minified, runtime-verified `app-release.apk` at
version 1.0.0, installable on the user's own Android 16+ devices, with a
repeatable documented release process.

## User rulings fixed before design

1. Distribution is signed sideload only. Play Console, internal-testing
   tracks, and AAB packaging are out of scope for this stage.
2. Release signing uses a local keystore; CI keeps building the unsigned
   release exactly as today. No key material or secrets enter the
   repository or GitHub.
3. The first signed release is versionName 1.0.0 with versionCode 1.

## Non-goals

- Play Console work of any kind (account, listing, data-safety form).
- CI-signed builds, CI release artifacts, or tagged GitHub Releases.
- Any in-app or store-driven update mechanism.
- Changing `minSdk` 36 or any product behaviour.

## Signing

`app/build.gradle.kts` gains a guarded signing config:

- If `keystore.properties` exists at the repository root, the release
  build type signs with it. The file supplies `storeFile`,
  `storePassword`, `keyAlias`, and `keyPassword`.
- If the file is absent, the release build stays unsigned — exactly
  today's CI behaviour. No workflow or workflow-verifier change.
- The properties file is read at configuration time; the configuration
  cache tracks it as an input. `keystore.properties`, `*.jks`, and
  `*.keystore` are already gitignored.

The keystore is generated once with `keytool` (RSA-4096, validity
10,000 days, alias `opentasks`) and lives outside the repository, e.g.
`~/Keys/opentasks-release.jks`. Two hard rules, documented in
`RELEASING.md`:

- Back the keystore up durably. Losing it means existing installs can
  never update (signature mismatch forces uninstall and data loss).
- Neither the keystore nor `keystore.properties` is ever committed.

## Versioning

- This release: `versionName = "1.0.0"`, `versionCode = 1` (no build
  was ever released, so 1 is honest).
- Every future release bumps `versionCode` by exactly 1 — sideload
  updates require a strictly increasing code — and `versionName` by
  semver judgment.
- Each released build gets an annotated git tag (`v1.0.0`) on the exact
  released commit.

## Release verification

Two artifacts, sized to the real risk: the release build has only ever
been compile-verified, and its riskiest subsystems under R8 (SQLCipher,
Tink keysets, kotlinx-serialization frames, Room, Hilt, Glance,
BiometricPrompt) fail at runtime, not compile time.

### `scripts/verify-release-apk.sh`

Automates the manual release-inspection gate previous stage closures ran
by hand. Fails closed with a nonzero exit naming the first failed check:

1. `apksigner verify` passes (the APK is signed, modern scheme).
2. Badging `versionName`/`versionCode` match `app/build.gradle.kts`.
3. The debug-only Drive qualification activity is absent from the
   manifest.
4. A dex-level string scan finds `auth/drive.appdata` as the sole Drive
   scope.
5. `android:debuggable` is not set.

SDK tools (`apksigner`, `aapt2`) resolve from `~/Library/Android/sdk`,
newest installed build-tools. Default APK path
`app/build/outputs/apk/release/app-release.apk`, overridable as `$1`.

### Smoke checklist (human-executed, per release)

Run on a disposable AVD only, started with `-read-only
-no-snapshot-load -no-snapshot-save`. The protected `Pixel_10_Pro_Fold`
AVD must never receive a release install: replacing its debug build
would force the forbidden uninstall of the protected workspace.

Steps: fresh install → create vault → add project, task, checklist
item, tag → force-stop and relaunch → confirm persistence → `.otvault`
export then import round trip → enable app lock and unlock → place the
Today widget → open via the Quick Add shortcut.

Results are recorded in a short per-release qualification record,
`docs/qualification/release-1.0.0-sideload.md`, following house
convention. The record includes the verify-script output and the
smoke-step outcomes.

## Process documentation

Root-level `RELEASING.md`, hard-wrapped near 78 columns:

1. One-time: generate the keystore, write `keystore.properties`, back
   both up.
2. Per release: bump versions → local CI gate (`testDebugUnitTest
   lintDebug :app:assembleDebug`) → `:app:assembleRelease` →
   `scripts/verify-release-apk.sh` → disposable-AVD smoke checklist →
   qualification record → annotated tag → `adb install` on the real
   device.

## Failure handling

- Missing `keystore.properties` → unsigned build by design (the CI
  path), never an error.
- Verify script → fail closed, nonzero exit, first failing check named.
- Smoke failure → the release does not ship; the defect is triaged
  before any tag is created.

## Deliverables

1. Guarded signing config in `app/build.gradle.kts`.
2. `versionName` 1.0.0 in `app/build.gradle.kts`.
3. `scripts/verify-release-apk.sh`.
4. `RELEASING.md` including the smoke checklist.
5. Executed verification: verify script green on a locally signed APK,
   smoke checklist green on a disposable AVD, recorded in
   `docs/qualification/release-1.0.0-sideload.md`.
6. Annotated tag `v1.0.0` on the released commit.

## Constraints carried from the repository

- Never combine `lintDebug` and `assembleRelease` in one Gradle
  invocation.
- Configuration cache stays on; the signing config must be
  config-cache compatible.
- No secrets or env vars in the repository or CI.
- Logs and release artifacts never contain task text, account details,
  Drive IDs, attachment names, or encryption metadata.
