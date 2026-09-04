# Releasing Open Tasks

Open Tasks supports direct signed APKs and Google Play AABs. CI builds release
artifacts unsigned; an owner signs locally and performs every upload manually.
Do not add CI signing or automated Play upload for the first Play launch.

## Direct APK release

Direct release assembly produces arm64-v8a and x86_64 APKs plus one 64-bit
universal fallback.

The programme-level record at
`docs/qualification/onboarding-dashboard-nfr-acceptance.md` is implementation
evidence, not a release waiver. The original and closing security findings are
implemented and their post-fix scans reported zero findings; the owner also
reported PASS for the deferred passive-content process-death device check.
On 24 August the owner accepted the recorded API 36 physical, credentialled
Google, browser/print/accessibility, and API 37 emulator-system evidence
boundaries for release 1.4.0 and explicitly approved its version bump, tag,
and release. That per-release decision does not invent missing measurements or
observations and does not waive these gates for later releases. The rebuilt
1.4.0 candidate passed the real signer authentication of all three APKs using
the independent owner certificate record; the certificate value was not
recorded.

### One-time setup

1. Generate the release keystore OUTSIDE the repository:

       keytool -genkeypair \
         -keystore ~/Keys/opentasks-release.jks \
         -alias opentasks -keyalg RSA -keysize 4096 -validity 10000 \
         -dname "CN=Open Tasks"

   keytool prompts for the store password; use a strong unique one.

   At key creation, establish the independent owner certificate record from
   this known-good keystore:

       keytool -list -v \
         -keystore ~/Keys/opentasks-release.jks \
         -alias opentasks

   Store only the `SHA256` digest in a password manager plus the offline
   backup, outside this repository. Remove its colons so the stored value is
   exactly 64 hexadecimal characters. Never establish or refresh this trusted
   value from a candidate APK.

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

### Per-release process

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
   The value must be exactly 64 hexadecimal characters without colons. Do not
   derive it from any APK, put it on the command line, or echo it into release
   evidence. `read -s` waits silently for the value and Enter:

       read -s OPEN_TASKS_RELEASE_CERT_SHA256
       printf '\n'
       export OPEN_TASKS_RELEASE_CERT_SHA256

   Then authenticate the signer and release invariants of all three signed
   APKs in one gate:

       bash scripts/verify-release-apk.sh \
         app/build/outputs/apk/release/app-arm64-v8a-release.apk \
         app/build/outputs/apk/release/app-x86_64-release.apk \
         app/build/outputs/apk/release/app-universal-release.apk

       unset OPEN_TASKS_RELEASE_CERT_SHA256

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

### Supply-chain qualification

`gradle/verification-metadata.xml` is the reviewed SHA-256 allow-list for
resolved build inputs. Regenerate it only with the complete gate, split into
two invocations because `lintDebug` and `:app:assembleRelease` must never
share one (AGP lint can race KSP while release Hilt sources are generated),
then review every change rather than accepting a checksum merely because the
build requested it:

    ./gradlew --write-verification-metadata sha256 testDebugUnitTest lintDebug
    ./gradlew --write-verification-metadata sha256 \
      :app:assembleRelease cyclonedxBom

The writer only adds entries: prune the superseded versions by hand, and
cross-check every added checksum against the `.sha256` sidecar the source
repository publishes for that artifact.

The aggregate CycloneDX JSON and XML files must contain the application
modules plus Room, SQLCipher, Tink, and Bouncy Castle. They must not contain
keystore properties, signing material, credentials, or other local
configuration. Attach both exact files to the release qualification.

The pushed Security workflow must finish successfully before release. CodeQL
uses a manual Java/Kotlin build, and dependency review blocks newly introduced
High or Critical advisories on pull requests. If the repository is private
and lacks the required GitHub Code Security entitlement, that is an external
release blocker; do not waive either job.

### Performance qualification (per release, disposable physical device only)

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

### Smoke checklist (per release, disposable AVD only)

Boot the emulator as the sole ADB target with `-read-only
-no-snapshot-load -no-snapshot-save`. Never install a release build on
the protected AVD's persistent state; the read-only overlay is
discarded at shutdown, which is what makes this safe. Inside the
session, uninstall the inherited debug build first (overlay-only):

    adb uninstall app.opentasks
    adb install app/build/outputs/apk/release/app-universal-release.apk

Then, by hand:

1. Fresh launch → the local workspace opens automatically. Open More → Backup
   & recovery only when exercising restore or import.
2. Add a project, a task with a checklist item, and a tag.
3. Force-stop and relaunch → everything from step 2 persists.
4. Export `.otvault`, then import it back → counts match.
5. Enable app lock, background past the delay, unlock.
6. Place the Today widget on the launcher → counts render.
7. Open the app via the Quick Add launcher shortcut.

Every step must pass before tagging. A failure stops the release; triage
the defect before any tag is created.

### Owner-present Google Drive gate (per release, physical device only)

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

### Installing on a device

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

   Expect `Success`. On first launch, the local workspace is created
   automatically; restore and import remain under More → Backup & recovery.

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

## Google Play AAB release

Google Play receives one upload-signed Android App Bundle and derives APKs for
devices. Keep the existing release key as the Play app-signing key so direct
1.4.0 installs can update to Play builds. Use a separate upload key only to
authenticate AAB uploads; it must never sign delivered APKs or be registered
with OAuth.

### Signing responsibilities and recovery

- The owner retains the existing app-signing keystore, its passwords, public
  fingerprints, and offline backups. During first-release enrolment, choose
  **Change app signing key** and transfer that key through Play's current PEPK
  flow; do not accept a newly generated delivery key.
- Google Play App Signing safeguards the delivery copy and signs generated
  APKs. The owner must verify its delivery certificate against the existing
  direct-release signer before any tester installs.
- The owner generates, backs up, and controls a separate upload keystore and
  registers only its public certificate. If it is lost or compromised, use
  Play Console's verified upload-key reset procedure and register a new upload
  certificate; this must not change the app-signing key or installed-user
  identity.
- App-signing-key loss is not solved by changing the upload key. Retain the
  owner backup and use only Play's verified app-signing recovery or upgrade
  procedure where available. Never substitute a key that breaks updates.

Keep `/Users/kk/Keys/opentasks-play-upload.properties`, both keystores, and
all passwords outside the repository. The external Gradle property is
`openTasksKeystoreProperties`.

### Pinned bundletool setup

Download only Google's bundletool 1.18.3 release asset to
`/Users/kk/Tools/bundletool-all-1.18.3.jar`:

    curl --fail --location \
      --output /Users/kk/Tools/bundletool-all-1.18.3.jar \
      https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar
    printf '%s  %s\n' \
      a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29 \
      /Users/kk/Tools/bundletool-all-1.18.3.jar \
      | shasum -a 256 -c -

Never execute the JAR when this exact SHA-256 check fails.

### Build and verify the AAB

Run the same CI, workflow-policy, SBOM, dependency-checksum, size-script,
physical-device performance, disposable-AVD, backup, and owner-present Drive
gates required by the direct APK release. Build and authenticate the direct
APK set first so the existing installed-user signer remains independently
proven. Then build the Play artifact once with the upload key:

    ./gradlew :app:bundleRelease \
      -PopenTasksKeystoreProperties=/Users/kk/Keys/opentasks-play-upload.properties \
      --stacktrace

The sole output is
`app/build/outputs/bundle/release/app-release.aab`. Once accepted as a
candidate, do not rebuild it in place.
The bundle invocation disables the ABI splits that the direct APK set uses
(AGP issue 402800800 rejects per-split shrunk resources in a bundle), so
keep the APK set and the AAB in separate Gradle invocations.

Load the expected upload-certificate SHA-256 from its independent owner record
without putting it on the command line or in release evidence:

    read -s OPEN_TASKS_UPLOAD_CERT_SHA256
    printf '\n'
    export OPEN_TASKS_UPLOAD_CERT_SHA256
    export OPEN_TASKS_BUNDLETOOL_JAR=/Users/kk/Tools/bundletool-all-1.18.3.jar
    export OPEN_TASKS_LLVM_OBJDUMP=\
    /Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/llvm-objdump
    bash scripts/verify-release-bundle.sh \
      app/build/outputs/bundle/release/app-release.aab
    unset OPEN_TASKS_UPLOAD_CERT_SHA256

Record the verifier's complete non-sensitive permission/export audit and the
AAB SHA-256 in the release qualification.

### Derive and test a Play-like universal APK

Use the existing app-signing key, not the upload key, to simulate Play's
delivered signer. Let bundletool prompt for passwords; never add password
arguments to the command line:

    java -jar /Users/kk/Tools/bundletool-all-1.18.3.jar build-apks \
      --bundle=app/build/outputs/bundle/release/app-release.aab \
      --output=app/build/outputs/bundle/release/app-release.apks \
      --mode=universal \
      --ks=/Users/kk/Keys/opentasks-release.jks \
      --ks-key-alias=opentasks \
      --overwrite
    unzip -p app/build/outputs/bundle/release/app-release.apks universal.apk \
      > app/build/outputs/bundle/release/play-universal.apk
    play_build_tools="$(find /Users/kk/Library/Android/sdk/build-tools \
      -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
    "$play_build_tools/zipalign" -c -P 16 -v 4 \
      app/build/outputs/bundle/release/play-universal.apk
    "$play_build_tools/apksigner" verify --print-certs \
      app/build/outputs/bundle/release/play-universal.apk

The `zipalign` check must pass and the APK signer must equal the independent
existing app-signing fingerprint, never the upload fingerprint.

On a sole disposable Android 16+ AVD, prove a fresh install:

    adb uninstall app.opentasks
    adb install app/build/outputs/bundle/release/play-universal.apk

The automatic local-first workspace must open and the direct-release smoke
checklist must pass. Never uninstall from a physical device containing data.

Separately build and authenticate the exact tagged 1.4.0 universal APK, then
prove the direct-to-Play upgrade on a fresh disposable AVD:

    adb install /private/tmp/open-tasks-v1.4.0.apk
    adb install -r app/build/outputs/bundle/release/play-universal.apk

Confirm the 1.4.0 workspace survives, opens automatically after the update,
and reaches recovery through More → Backup & recovery. Run the owner-present
Google Drive gate on the physical device without uninstalling, clearing data,
or running connected tests.

### Capturing the store listing assets

The eight listing screenshots must come from the qualified candidate's own
release build, on a disposable AVD, with synthetic content only. Seed the
workspace with:

    python3 scripts/seed-listing-workspace.py

It creates four projects and five tasks with tags, priorities, due dates, a
checklist and a reminder, and resumes with a task number if a step fails. Add
by hand a sixth task moved to In progress, the project due date, and one
milestone, so the planning surfaces are not empty.

Capture the four phone images at `adb shell wm size 1080x1920` (the AVD's own
420 dpi), and the four large-screen images at `adb shell wm size 1920x1080`
with `adb shell wm density 240`, which is what makes the app lay out its real
navigation-rail arrangement instead of a stretched phone view. Reset both with
`wm size reset` and `wm density reset` afterwards.

Clean the status bar through SysUI demo mode so no account, device identifier
or notification preview appears:

    adb shell settings put global sysui_demo_allowed 1
    adb shell am broadcast -a com.android.systemui.demo -e command enter
    adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0930
    adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
    adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile hide
    adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true
    adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false

Send the demo `exit` command and re-enter it if a duplicate icon appears after
a viewport change. Before committing, check every file's dimensions and record
its SHA-256 in the asset manifest, and confirm Google Drive is disconnected in
each shot so the listing never implies an account is required.

### Track progression and version codes

Promote the exact immutable AAB through **internal → closed →
production-access → production**. Before each promotion, compare the retained
AAB hash, source commit, version, App Bundle Explorer identity, permissions,
ABIs, 16 KB status, and Play delivery signer with the release qualification.
Re-run fresh-install and direct-1.4.0 upgrade checks against Play-delivered
artifacts.

Every AAB uploaded to Play consumes its version code, even when rollout never
starts. A changed or rejected replacement must use a higher `versionCode`,
repeat every affected gate, and receive a new immutable hash. Never reuse an
uploaded code or rebuild an accepted artifact in place.

For the first production release, a critical defect requires emergency
unpublish plus a fixed AAB with a higher version code. There is no previous
Play release to halt back to. Record the unpublish decision, affected code,
fix code, artifact hash, regression gates, and owner approval before the fixed
rollout.
