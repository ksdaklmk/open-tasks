# Releasing Open Tasks (signed sideload)

Distribution is signed sideload only. There is no Play Console, no AAB,
and no CI signing; CI builds the release unsigned.

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

3. Build the signed release:

       ./gradlew :app:assembleRelease

4. Verify the APK:

       bash scripts/verify-release-apk.sh

5. Run the smoke checklist below on a disposable AVD. Record the
   results in `docs/qualification/release-<versionName>-sideload.md`.
6. Commit the version bump and qualification record, then tag:

       git tag -a v<versionName> -m "Release <versionName>" && git push origin main v<versionName>

7. Install on the real device:

       adb install app/build/outputs/apk/release/app-release.apk

## Smoke checklist (per release, disposable AVD only)

Boot the emulator as the sole ADB target with `-read-only
-no-snapshot-load -no-snapshot-save`. Never install a release build on
the protected AVD's persistent state; the read-only overlay is
discarded at shutdown, which is what makes this safe. Inside the
session, uninstall the inherited debug build first (overlay-only):

    adb uninstall app.opentasks
    adb install app/build/outputs/apk/release/app-release.apk

Then, by hand:

1. Fresh launch → create a vault with a passphrase.
2. Add a project, a task with a checklist item, and a tag.
3. Force-stop and relaunch → everything from step 2 persists.
4. Export `.otvault`, then import it back → counts match.
5. Enable app lock, background past the delay, unlock.
6. Place the Today widget on the launcher → counts render.
7. Open the app via the Quick Add launcher shortcut.

Every step must pass before tagging. A failure stops the release; triage
the defect before any tag is created.
