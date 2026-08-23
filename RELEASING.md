# Releasing Open Tasks (signed sideload)

Distribution is signed sideload only. There is no Play Console, no AAB,
and no CI signing; CI builds the release unsigned. Release assembly produces
arm64-v8a and x86_64 APKs plus one 64-bit universal fallback.

The programme-level record at
`docs/qualification/onboarding-dashboard-nfr-acceptance.md` is implementation
evidence, not a release waiver. The original and closing security findings are
implemented and their post-fix scans reported zero findings; the owner also
reported PASS for the deferred passive-content process-death device check.
Release remains blocked on the fixed API 36 arm64 physical performance and
fresh-install gates, owner-present Google, browser/accessibility evidence, and
the real signer authentication of all three APKs using the independent owner
certificate record. Pushed Security runs `32615516366`, `32617307907`, and
exact-head `32617911327` are green; repeat all
applicable workflow gates after any later source or workflow change. The
expanded API 37 preview lane remains infrastructure-red. Run `32615516358`
proved that the bounded `--no-parallel` experiment did not serialize UTP work,
so the ineffective flag was removed in `afb1d93`. The canary still lost
Android's activity/package services before any application assertion. It is
not an app failure, serialization proof, or a release waiver, and no second
speculative workaround is accepted as evidence.

## One-time setup

1. Generate the release keystore OUTSIDE the repository:

       keytool -genkeypair \
         -keystore ~/Keys/opentasks-release.jks \
         -alias opentasks -keyalg RSA -keysize 4096 -validity 10000 \
         -dname "CN=Open Tasks"

   keytool prompts for the store password; use a strong unique one.

2. Write `keystore.properties` at the repository root (gitignored):

       storeFile=/Users/<you>/Keys/opentasks-release.jks
       storePassword=<store password>
       keyAlias=opentasks
       keyPassword=<store password>

3. Back up BOTH files durably (password manager plus offline copy).
   Losing the keystore means existing installs can never update: a
   signature mismatch forces uninstall and on-device data loss.
4. Never commit either file. `.gitignore` already covers them; do not
   override it.

## Per-release process

1. Bump `versionCode` by exactly 1 and set `versionName` by semver
   judgment in `app/build.gradle.kts`.
2. Run the CI gate (separate invocation from the release build):

       ./gradlew testDebugUnitTest lintDebug :app:assembleDebug

3. Verify the workflow policy, dependency checksums, and aggregate SBOM:

       scripts/verify-actions-workflow.sh
       ./gradlew help cyclonedxBom

   Review `build/reports/cyclonedx/bom.json` and `bom.xml` as described in the
   supply-chain section below.

4. Verify the size-gate script, then build the signed release:

       bash scripts/verify-release-size-script.sh
       ./gradlew :app:assembleRelease

5. Gate the arm64 and universal byte counts against the accepted baseline and
   hard caps:

       scripts/check-release-size.sh \
         app/build/outputs/apk/release/app-arm64-v8a-release.apk \
         app/build/outputs/apk/release/app-universal-release.apk

   A drift over 2% or 250 KiB exits separately for review. Inspect the APK
   entries and record the explanation before updating
   `gradle/release-size-baseline.properties`; never update it just to make the
   gate pass.

6. Run the physical-device performance qualification below. Emulator CI is a
   functional wiring check only and never supplies release threshold results.

7. Provision `OPEN_TASKS_RELEASE_CERT_SHA256` in the current shell from the
   owner's independent trusted certificate record outside the repository.
   Do not derive it from any APK, put it on the command line, or echo it into
   release evidence. Then authenticate the signer and release invariants of
   all three signed APKs in one gate:

       bash scripts/verify-release-apk.sh \
         app/build/outputs/apk/release/app-arm64-v8a-release.apk \
         app/build/outputs/apk/release/app-x86_64-release.apk \
         app/build/outputs/apk/release/app-universal-release.apk

   The verifier rejects an absent or malformed owner input and any missing,
   duplicate, unexpected, invalid, differently signed, or incorrectly labelled
   artifact. It requires arm64-only native code in the arm64 APK, x86_64-only
   native code in the x86_64 APK, and both ABIs in the universal APK.

8. Run the smoke checklist below on a disposable AVD. Record the
   disposable-AVD results in `docs/qualification/release-<versionName>-sideload.md`;
   the same qualification record must also contain the owner-present Drive
   gate results below.
9. Run the owner-present Google Drive gate below on the physical device.
10. Commit the version bump and qualification record, then tag:

       git add app/build.gradle.kts \
         docs/qualification/release-<versionName>-sideload.md
       git commit -m "docs: qualify release <versionName> for signed sideload"
       git tag -a v<versionName> -m "Release <versionName>" && git push origin main v<versionName>

11. Install the matching APK on the real device (normally arm64-v8a):

       adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk

## Supply-chain qualification

`gradle/verification-metadata.xml` is the reviewed SHA-256 allow-list for
resolved build inputs. Regenerate it only with the complete gate command,
then review every change rather than accepting a checksum merely because the
build requested it:

    ./gradlew --write-verification-metadata sha256 \
      testDebugUnitTest lintDebug :app:assembleRelease cyclonedxBom

The aggregate CycloneDX JSON and XML files must contain the application
modules plus Room, SQLCipher, Tink, and Bouncy Castle. They must not contain
keystore properties, signing material, credentials, or other local
configuration. Attach both exact files to the release qualification.

The pushed Security workflow must finish successfully before release. CodeQL
uses a manual Java/Kotlin build, and dependency review blocks newly introduced
High or Critical advisories on pull requests. If the repository is private
and lacks the required GitHub Code Security entitlement, that is an external
release blocker; do not waive either job.

## Performance qualification (per release, disposable physical device only)

This suite clears and replaces the installed Open Tasks benchmark package.
Run it only with fresh owner approval and exactly one audited, disposable API
36 arm64 physical device visible to ADB. Never run it on an owner workspace,
the protected AVD, or any device containing data that must survive.

Confirm the sole target, then run the release-like benchmark without dry-run
arguments so every benchmark records its fixed ten iterations:

    ~/Library/Android/sdk/platform-tools/adb devices -l
    ./gradlew :benchmark:connectedBenchmarkAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.enable=true

Pass the resulting unmodified `benchmarkData.json` and the accepted JSON from
the same physical-device model to the gate:

    scripts/check-benchmark-thresholds.sh \
      benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/<device>/<current>-benchmarkData.json \
      docs/qualification/<accepted>-benchmarkData.json

The checker rejects emulator/generic evidence, non-API-36 results, a device
model mismatch, fewer than ten iterations, missing metrics, hard-threshold
failures, and startup regressions over 10%. For the first accepted baseline,
run the candidate against itself to enforce every hard threshold, review the
result, then archive that exact JSON; never accept a failing candidate merely
to establish a baseline.

Record the build SHA, device model, API/ABI, dataset, iteration count, p50,
p95, checker result, and raw JSON path in the release qualification. Preserve
the raw artifact with that record. Any failure blocks the release.

## Smoke checklist (per release, disposable AVD only)

Boot the emulator as the sole ADB target with `-read-only
-no-snapshot-load -no-snapshot-save`. Never install a release build on
the protected AVD's persistent state; the read-only overlay is
discarded at shutdown, which is what makes this safe. Inside the
session, uninstall the inherited debug build first (overlay-only):

    adb uninstall app.opentasks
    adb install app/build/outputs/apk/release/app-universal-release.apk

Then, by hand:

1. Fresh launch → choose `Start without restoring` to create the local workspace.
2. Add a project, a task with a checklist item, and a tag.
3. Force-stop and relaunch → everything from step 2 persists.
4. Export `.otvault`, then import it back → counts match.
5. Enable app lock, background past the delay, unlock.
6. Place the Today widget on the launcher → counts render.
7. Open the app via the Quick Add launcher shortcut.

Every step must pass before tagging. A failure stops the release; triage
the defect before any tag is created.

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

The owner may inspect the APK certificate locally with `apksigner`, but that
inspection must never supply the verifier's expected value. Do not paste or
record its fingerprint, account, project, client ID, or signing material in
qualification prose.

Install the arm64 APK over the existing app with the unchanged signing identity:

    adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk

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

## Installing on a device

The phone must run Android 16 or newer (`minSdk` 36); installation
fails on anything older. `adb` is not on PATH on this machine — use
`~/Library/Android/sdk/platform-tools/adb`. Shut the emulator down
first so adb has exactly one target.

1. Enable Developer options on the phone: Settings → About phone → tap
   Build number seven times.
2. Enable debugging, either route:
   - USB: Developer options → USB debugging → on. Plug in, and accept
     the phone's "Allow USB debugging?" dialog (check "Always allow
     from this computer"). If no dialog appears, set the USB mode to
     File transfer and reconnect.
   - Wireless (same Wi-Fi): Developer options → Wireless debugging →
     on → Pair device with pairing code, then:

         adb pair <ip>:<pairing-port>      # six-digit code from phone
         adb connect <ip>:<port>           # main port, not pairing port

3. Confirm visibility: `adb devices` shows exactly one line ending in
   `device`. `unauthorized` means the phone's dialog is still waiting.
4. Install from the repository root:

       adb install app/build/outputs/apk/release/app-arm64-v8a-release.apk

   Expect `Success`. On first launch, choose `Start without restoring` to create the local workspace.

Troubleshooting:

- `INSTALL_FAILED_OLDER_SDK` — the phone is below Android 16.
- `more than one device/emulator` — an emulator is still up; kill it
  or target the phone with `adb -s <serial> install ...`.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — a differently-signed build
  (for example a debug build) is installed; uninstall it first. Never
  do this to update a real workspace — uninstalling deletes on-device
  data.
- No cable and no shared Wi-Fi: copy the APK to the phone (Drive, USB
  file copy), open it from the Files app, and allow "install unknown
  apps" for Files when prompted.

Use `app-x86_64-release.apk` for an x86_64 target, or
`app-universal-release.apk` when the 64-bit ABI is unknown. Updates install
straight over the top with `adb install -r` — no
uninstall or data clearing, data preserved — because every release is signed
with the same keystore. That is why the keystore must never be lost.
