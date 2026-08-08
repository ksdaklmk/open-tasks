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

       git add app/build.gradle.kts \
         docs/qualification/release-<versionName>-sideload.md
       git commit -m "docs: qualify release <versionName> for signed sideload"
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

       adb install app/build/outputs/apk/release/app-release.apk

   Expect `Success`. First launch asks for a new vault passphrase.

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

Updates install straight over the top with `adb install` — no
uninstall, data preserved — because every release is signed with the
same keystore. That is why the keystore must never be lost.
