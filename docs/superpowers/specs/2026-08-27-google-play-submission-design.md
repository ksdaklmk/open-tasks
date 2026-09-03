# Google Play Submission Design

Date: 2026-08-27. Status: **approved in chat; implementation not started**.

This design defines the first Google Play publication of Open Tasks. It
supersedes the Play Store, signing, test-track, and rollout portions of the
2026-07-27 production programme and Train 6 documents. Their completed product
qualification remains historical evidence, but their Google-generated signing
key, mandatory open-test, and first-release managed-publishing assumptions are
not authoritative.

## Outcome

Publish Open Tasks through Google Play as a free, ad-free productivity app for
people aged 13 and older in every Play-supported country and region. The app
remains local-first and usable without an account. Google authorization remains
an optional, deliberate action used only for encrypted backup and recovery in
the Drive `appDataFolder`.

The submission takes the shortest compliant path:

1. make the developer account, package ownership, signing, public policy, and
   OAuth configuration ready;
2. qualify one exact Android App Bundle through the internal track;
3. run the required closed test with at least 12 continuously opted-in testers
   for 14 days;
4. apply for production access; and
5. publish the qualified candidate globally.

Open testing, Play Developer API automation, CI-held signing credentials,
billing, advertising, analytics, pre-registration, and launch marketing are
outside this first-submission scope.

## Fixed product and distribution decisions

| Decision | Value |
|---|---|
| App name | Open Tasks |
| Package | `app.opentasks` |
| Category | Productivity |
| Price | Free |
| Advertising | None |
| Target audience | 13 and older; not directed to children |
| Geographic availability | Every Play-supported country and region |
| Default listing language | English (United Kingdom) |
| Authentication | None required for core use |
| Optional provider | Google Drive `appDataFolder` for encrypted backup and recovery |
| Public policy/support host | GitHub Pages |
| First Play candidate | `versionName` 1.5.0, `versionCode` 7 |
| Play artifact | Android App Bundle from the existing `release` build type |
| Test path | Internal, mandatory closed, production-access application, production |

Worldwide availability is geographic only. The existing `minSdk = 36` and
`arm64-v8a`/`x86_64` native-code support continue to determine device
compatibility. This submission does not broaden OS or ABI support.

## Current baseline

The repository currently has:

- `compileSdk = 37`, `targetSdk = 37`, and `minSdk = 36`;
- release `1.4.0`, version code 6, signed for direct APK distribution;
- R8 and resource shrinking in the existing `release` build;
- arm64, x86_64, and universal APK outputs;
- a release APK verifier covering certificate, version, ABI, manifest, Drive
  scope, and debuggability;
- strict Android backup extraction rules;
- no ads, billing, analytics SDK, or developer-operated backend;
- a public `ksdaklmk/open-tasks` repository eligible for free GitHub Pages
  hosting; and
- optional Google authorization requesting only
  `https://www.googleapis.com/auth/drive.appdata`.

The current release process is sideload-only. The Play work extends it rather
than replacing its proven APK path.

## Critical path and ownership

Repository work and read-only inspection may be automated. The account owner
performs every identity, key-transfer, OAuth publication, and Play Console
mutation. No tool uploads an artifact, publishes a page, changes an OAuth
client, submits a declaration, enrolls a signing key, starts a track, or
publishes production without the owner's explicit execution-time approval.

The critical path is:

1. confirm the existing app-signing key is eligible;
2. complete Play identity/device verification and register `app.opentasks`;
3. publish the privacy and support pages;
4. create the Play app and configure the existing app-signing key plus a
   separate upload key;
5. configure production OAuth with Play delivery-certificate fingerprints;
6. complete listing and app-content declarations;
7. build and qualify the exact AAB;
8. pass internal testing and Play-delivered upgrade checks;
9. maintain the qualifying closed test and evidence;
10. obtain production access; and
11. submit the first global production release.

Android developer verification and package registration are P0 because the
current regional enforcement date for Thailand is September 30, 2026.

## Release identity, build, and versioning

The initial candidate is 1.5.0 / 7 and is built with:

```bash
./gradlew :app:bundleRelease
```

Its expected path is:

```text
app/build/outputs/bundle/release/app-release.aab
```

No `productionRelease` build type, product flavor, or deployment framework is
added. APK ABI splits remain available for sideload releases; Google Play
derives optimized APKs from the AAB independently of those Gradle APK outputs.

Every Play upload consumes its version code. If candidate 7 changes after it
has been uploaded, the replacement retains version name 1.5.0 unless its
user-visible scope changes, increments the version code, and repeats every
affected gate. An uploaded code is never reused and an accepted artifact is
never rebuilt in place.

The existing release signing configuration gains one selector for an external
keystore-properties path. With no selector, `keystore.properties` continues to
produce direct-distribution APKs signed by the existing app-signing key. The
Play AAB command selects an external upload-key properties file. Both files and
all passwords remain outside Git.

## Signing and installed-user continuity

Direct update compatibility is a release invariant. Before creating a Play
release, inspect the existing key with `keytool` and the signed 1.4.0 APK with
`apksigner`. Record only its public SHA-1/SHA-256 fingerprints, algorithm, key
size, and validity in the private owner evidence and the non-sensitive
fingerprint in the qualification ledger. The key must meet Play's current
custom-key requirement, including RSA 2048 bits or stronger and adequate
validity. If Play rejects the key, stop: do not accept a different delivery
key, change the package, or break installed-user updates implicitly.

For Play App Signing:

- choose **Change app signing key** during the first release;
- provide an encrypted copy of the existing app-signing key using Play's
  current PEPK/export workflow;
- do not accept the default Google-generated app-signing key;
- create a separate RSA upload key and register only its public certificate;
- sign uploaded AABs with the upload key; and
- keep both keystores and recovery material in owner-controlled secure storage.

The upload key identifies uploads; it never signs APKs delivered to devices and
is never registered with OAuth. The app-signing key signs Play-delivered APKs
and remains the signer for direct APKs. Do not request a signing-key upgrade as
part of the first release. If Play displays more than one delivery certificate,
record and test every applicable certificate and register every required
fingerprint with the OAuth provider.

Before closed testing, install the current signed 1.4.0 APK with a populated,
encrypted workspace, join the internal track, and let Play update that install.
The update must succeed without uninstalling and must preserve the vault,
attachments, settings, alarms, and widgets. Repeat with a fresh Play install.
The delivered certificate must match the expected app-signing identity. If any
supported Android version receives a delivery signature that cannot update the
existing install, promotion stops.

## Repository changes and durable evidence

The implementation is limited to these surfaces:

| Surface | Change |
|---|---|
| `app/build.gradle.kts` | Set 1.5.0 / 7 and allow an external signing-properties path while preserving the current default. |
| `scripts/verify-release-bundle.sh` | Validate one AAB's signature, identity, manifest, SDKs, Drive scope, native contents, and release posture. |
| `scripts/verify-release-bundle-script.sh` | One small runnable contract test for the verifier's success and fail-closed cases. |
| `RELEASING.md` | Separate direct APK and Play AAB procedures and remove obsolete Welcome instructions. |
| More and app-root wiring | Add one stateless **Privacy policy** action that opens the fixed public URL. |
| `site/privacy/index.html` | Public privacy policy with no script, tracker, cookie, or form. |
| `site/support/index.html` | Public support instructions linking to GitHub Issues and warning users not to post private task data. |
| `.github/workflows/pages.yml` | Publish only `site/` to GitHub Pages. |
| `docs/google-play/store-listing.md` | Version the English listing, declaration rationale, asset manifest, and public URLs. |
| `docs/google-play/assets/` | Store the final icon, feature graphic, and privacy-safe screenshots. |
| `docs/qualification/release-1.5.0-play.md` | Record artifact hashes, public certificate fingerprints, gates, Console evidence, tester summary, decisions, and approvals. |

No new application dependency is required. The AAB verifier reuses Android SDK
and JDK command-line tools plus one locally cached, pinned `bundletool` JAR to
validate the bundle and generate installable APK sets. The verifier receives an
expected upload-certificate fingerprint through an environment variable and
fails closed when it is absent or malformed, mirroring the existing APK
verifier.

The final artifact checks cover:

- AAB structure and JAR signature;
- package `app.opentasks`, candidate version, and target SDK;
- non-debuggable release manifest and absence of debug qualification
  components;
- expected permissions, exports, backup rules, and FileProvider paths;
- `drive.appdata` as the sole embedded Drive authorization scope;
- arm64 and x86_64 native libraries;
- 16 KB page-size compatibility for native dependencies, especially
  SQLCipher;
- R8 mapping and native symbol availability where generated; and
- SHA-256 provenance for the exact submitted AAB and derived APK set.

## Public privacy and support site

GitHub Pages publishes only the static `site/` directory at:

- `https://ksdaklmk.github.io/open-tasks/privacy/`
- `https://ksdaklmk.github.io/open-tasks/support/`

The privacy page states the exact developer identity shown in Play Console at
publication time and identifies Open Tasks and `app.opentasks`. It explains:

- what task, project, schedule, timer, note, attachment, preference, and backup
  data the app processes;
- that ordinary workspace data is held in the encrypted local vault;
- what the optional encrypted Google Drive `appDataFolder` and Android backup
  paths do;
- that the developer does not operate an application backend and has no access
  to the user's vault contents;
- the roles of Google Play, Google Drive, and Android backup services;
- permissions and their purposes;
- retention and deletion through in-app actions, provider controls, app-data
  clearing, or uninstall as applicable;
- security limitations and user responsibilities for recovery material;
- that the app is not directed to children under 13;
- how policy changes are dated; and
- how to submit a privacy or support request.

The support page routes public bug reports to
`https://github.com/ksdaklmk/open-tasks/issues` and tells users not to include
task contents, attachments, recovery material, credentials, or other personal
data. Play Console uses the account's verified developer email as its required
support email; no private address is invented or committed by this design.

The app exposes the privacy URL from More using the existing stateless callback
pattern. The row has a clear accessible label, a 48 dp target, keyboard access,
and usable layout at 200% font. A focused instrumentation test covers the
callback; the website URLs receive a link/status check after deployment.

## Data Safety and app-content declarations

Declarations are derived from the final binary and behavior, never from a
blanket statement that the app is local-first. The audit covers each Play data
type and each dependency, including:

- task titles and bodies, notes, projects, tags, dates, reminders, timer and
  time-entry data;
- attachments and imported files;
- app settings and backup metadata;
- any account identifier retained solely for the user's optional Drive binding;
- automatic encrypted Drive backup after the user enables it;
- Android system backup of the explicitly allowed encrypted portable package;
- Google Play Services authorization behavior; and
- crash/diagnostic behavior provided by Google Play rather than an embedded
  analytics SDK.

The audit decides, field by field, whether Play defines the behavior as
collection, sharing, ephemeral processing, optional processing, or an
applicable exception. It records the purpose, encryption in transit, deletion
path, and whether processing is required or optional. It does not declare “no
data collected” until the installed dependency and transport audit supports
every form answer.

App Content is completed consistently:

- no ads;
- no billing or in-app purchases;
- no financial features;
- not a government, news, health, dating, gambling, or VPN app;
- target audience 13 and older, with no children-directed claims;
- core app access unrestricted and requiring no login credentials;
- optional Google Drive authorization described for reviewers;
- content-rating questionnaire answered from actual productivity content;
- privacy URL and Data Safety answers aligned; and
- exact-alarm, notification, boot, biometric, Internet, exported-component,
  backup, and FileProvider behavior checked against current declaration rules.

Open Tasks has no app account, so Play's account-deletion requirement is not
triggered merely by optional Google provider authorization. The privacy and
support material still explains how to disconnect Drive and remove local or
provider-held backups.

## Store listing and assets

The default English (UK) listing describes only shipped behavior. It presents
Open Tasks as a private, local-first task and project manager with optional
encrypted backup. It may describe reminders, widgets, focus timing, import,
and adaptive layouts when the corresponding final build demonstrates them. It
must not claim live sync, collaboration, cross-device convergence, Google
affiliation, absolute security, or functionality that requires a future
release.

The minimum committed asset set is:

- one 512 x 512 PNG store icon at or below Play's size limit;
- one 1024 x 500 feature graphic;
- four phone screenshots; and
- four large-screen screenshots representing tablet/fold layouts.

Screenshots use synthetic workspaces, show the actual release UI, omit account
names and identifiers, contain no real task or attachment data, and need no
promotional device frame. The selected screens cover Home, task management,
project/planning work, and More/backup without suggesting that Google Drive is
required. Alt text accompanies each screenshot in the asset manifest.

No translation, custom store listing, store-listing experiment, or video is
part of the first submission.

## Account verification and package registration

The owner confirms the personal Play account has accurate legal name, address,
phone, developer contact, and payments-profile information and has two-step
verification enabled. If the dashboard requires device verification, the owner
uses the Play Console mobile app on a non-rooted physical Android device running
Android 10 or newer.

The owner then checks Android developer verification in Play Console and
registers `app.opentasks`. Because this package has already been installed from
outside Play, automatic registration may require proof of the known signing
key. If manual proof is requested:

1. select the existing public certificate when eligible;
2. obtain Play's current challenge snippet;
3. add it only to the proof APK asset structure specified by Play;
4. sign the proof APK with the existing private app-signing key; and
5. upload the proof and retain the successful registration evidence.

The challenge does not enter the production application or Git history. If the
certificate is not eligible, submit Play's package-name request with evidence
of the existing signed releases rather than silently choosing a new package.

## Play app and signing setup

Create the app with the fixed name, type, category, price, audience, and
default language. Configure global production country/region availability but
do not start a production release.

During the first release's App integrity setup, transfer the existing signing
key before any internal or closed tester relies on a Play-generated signer. If
a bundle must be uploaded before the key can be changed, do not roll it out;
change the key, discard any incompatible artifact as Play instructs, upload a
higher-code candidate if required, and qualify only the post-change delivered
artifact. Play warns that testers who install before a first-release key change
may need to reinstall, so signer configuration precedes tester recruitment.

After enrollment, compare the Play app-signing certificate with the current
sideload certificate and compare the registered upload certificate with the
AAB signer. Any mismatch blocks upload or promotion.

## OAuth production configuration

The Google Auth project is External and moved from Testing to In production so
ordinary Google Accounts can authorize optional Drive backup without the
Testing mode's 100-user and seven-day authorization limits.

The consent configuration uses:

- product name Open Tasks;
- the deployed GitHub Pages privacy and support URLs;
- the verified owner contact required by Google;
- only the app's actively requested `drive.appdata` scope; and
- Android client package `app.opentasks`.

Register the SHA-1 fingerprint for every Play certificate that can sign an APK
delivered on a supported Android version. Preserve any local debug or direct
sideload Android clients needed for their independently signed builds. Never
register the upload certificate as a delivery client.

`drive.appdata` is currently classified as non-sensitive. Sensitive/restricted
scope verification is therefore not planned, but Google may still require
brand or identity verification before showing the chosen app name and logo.
Any request from the Console is treated as a gate and resolved with the public
policy, support, branding, and exact scope evidence.

Amendment, 3 September 2026: Google's brand verification requires the
homepage to be hosted on a domain the project owner has verified at DNS
level, which the shared `github.io` zone cannot provide, and it is optional
for apps that request only non-sensitive scopes. The owner chose to defer
brand verification rather than adopt a custom domain. The consent screen
therefore shows Google's unbranded app identity until a custom domain is
adopted later. Brand verification is not a gate for Tasks 9 to 14, and the
deployed Pages URLs remain the listing, in-app, and OAuth values.

The credentialed release test authorizes Drive, creates an encrypted backup,
lists it, restores it through the existing verified takeover path, and confirms
that core app use still performs no authorization request.

## Test tracks and production access

### Internal

Upload the exact qualified AAB to the internal track after signing and OAuth
setup. Complete Play pre-review checks and the pre-launch report. On Play-capable
physical hardware, prove both a fresh install and the signed 1.4.0-to-Play
upgrade. Exercise startup, task/project CRUD, reminders, widgets, focus timing,
attachments, import, app lock, Android backup preparation, and optional Drive
backup/restore. Inspect the App Bundle Explorer outputs and delivered signer.

No failure is waived merely because the internal track is optional.

### Closed

Complete app setup, then recruit 15-20 compatible testers to provide margin
above the required 12. Testers must opt in through the official link and remain
continuously opted in for at least 14 days. An opt-out breaks that tester's
continuous period; adding a replacement does not inherit elapsed time.

Give testers a short critical-journey checklist and the GitHub support route.
Do not commit tester names, email addresses, or device identifiers. Record only
counts, compatible-device coverage, anonymized feedback themes, defects,
responses, and resulting changes. Updating the closed-test build does not
replace the need to keep enough testers continuously opted in.

### Production-access application

Apply only when Play shows the requirement satisfied and at least 12 qualifying
testers remain opted in. The application answers with the recorded facts:

- how testers were recruited and engaged;
- which features and device types they exercised;
- whether behavior resembled expected production use;
- how feedback was collected;
- what feedback was received and what changed;
- the 13+ productivity audience and value proposition;
- expected first-year install range selected honestly by the owner; and
- why the artifact is production-ready.

If Google requests more testing, continue the same closed track, address the
specific weakness, and reapply. Open testing becomes available after production
access but remains unnecessary for this approved path.

## Production publication

The first production release is standard publishing. Current Play behavior
does not permit managed publishing or a percentage staged rollout for an app's
first publication. Starting rollout publishes the approved release to all
selected countries and regions, so the plan includes at least a one-week review
buffer and treats closed testing as the risk-containment stage.

The production button is pressed only after:

- the artifact hash matches the qualified internal/closed candidate;
- Play app-signing and OAuth fingerprints are verified;
- fresh-install and upgrade results are recorded;
- pre-launch, policy, listing, declaration, and tester gates are green;
- the public pages resolve without authentication;
- no critical or high defect remains; and
- the owner signs the final evidence ledger.

## Failure handling and rollback

Before production, any blocker stops promotion. A code or resource fix creates
a higher version code and repeats affected gates. A listing or declaration
rejection is reconciled against actual app behavior; appeal is used only when
the evidence shows the rejection is factually wrong.

The first production release cannot be halted because no earlier Play release
exists to restore. If a critical data-loss, security, startup, signing, or
backup defect is found after publication:

1. unpublish the app to prevent new users from installing it;
2. keep the support and privacy pages available;
3. diagnose and fix the root cause;
4. build a higher-version corrective release;
5. rerun the complete affected qualification; and
6. submit the fix for existing users before republishing availability.

Unpublishing does not remove the app from existing devices. There is no
downgrade rollback for users who already installed the first release.

After a stable first production release exists, future updates may use managed
publishing, percentage staged rollouts, and Play's halt mechanism. Those update
controls are documented in the release procedure but are not implemented as
automation.

## Monitoring

No telemetry SDK is added. Monitor Play Console and the public support route:

- daily for the first seven days after production;
- weekly through day 30; and
- before each later release promotion.

Review policy status, Android vitals, user-perceived crash and ANR clusters,
installation/update failures, ratings and reviews, device compatibility,
pre-launch findings, and reports of Drive authorization or restore failures.
Low install volume may suppress aggregate metrics, so individual reproducible
reports remain actionable.

Any reproducible vault loss or corruption, signing/update failure, security
regression, inaccessible backup, widespread startup/authentication failure, or
critical policy issue triggers the failure procedure immediately. Lesser issues
are prioritized in the normal backlog with their evidence and user impact.

## Acceptance criteria

- Play Console identity and required physical-device verification are complete.
- `app.opentasks` and its existing signing key are registered before the
  September 30, 2026 enforcement date.
- The existing key is accepted as the Play app-signing key and a separate
  upload key is registered.
- The GitHub Pages privacy and support URLs are public, accurate, and linked
  from both the app and Play listing.
- Listing, Data Safety, App Content, OAuth, and privacy statements agree with
  the inspected release binary.
- The exact AAB passes repository, bundle, native-page-size, fresh-install,
  upgrade, backup, and Drive gates.
- A Play-delivered update from signed 1.4.0 preserves the encrypted workspace
  without uninstalling.
- At least 12 testers remain continuously opted in for 14 days, and useful
  feedback and responses are recorded.
- Production access is approved.
- The same qualified artifact is published free, without ads, to every
  Play-supported country and region.
- The first 30 days of monitoring and the first-release emergency procedure
  have named owner actions in the qualification ledger.

## Stop conditions

Submission stops on any of the following:

- existing signing key is unavailable, ineligible, or mismatched;
- package ownership cannot be registered;
- Play delivery cannot update the current sideload installation;
- an AAB, native page-size, manifest, backup, or release verification fails;
- fresh install or upgrade loses or corrupts user data;
- optional Drive authorization or verified restore is broken in the release;
- privacy, listing, or declarations differ from actual behavior;
- a critical/high security, accessibility, stability, or data-integrity defect
  remains;
- fewer than 12 testers meet the continuous 14-day requirement at application
  time; or
- Google rejects account, OAuth, app-content, or production-access readiness.

## Authoritative references

Requirements are rechecked at execution time because Play policy and Console
navigation change. Sources consulted on 2026-08-27:

- [Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-GB_ALL)
- [Testing requirements for new personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-GB)
- [Physical-device verification](https://support.google.com/googleplay/android-developer/answer/14316361?hl=en-GB)
- [Android developer verification](https://developer.android.com/developer-verification/guides)
- [Registering Play package names](https://support.google.com/googleplay/android-developer/answer/16984799?hl=en)
- [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en)
- [Android app-signing guidance](https://developer.android.com/studio/publish/app-signing)
- [Android App Bundle guidance](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en)
- [16 KB page-size support](https://developer.android.com/guide/practices/page-sizes)
- [Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
- [User Data and privacy-policy requirements](https://support.google.com/googleplay/android-developer/answer/17190352?hl=en)
- [App-content declarations](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en-EN)
- [Target-audience declarations](https://support.google.com/googleplay/android-developer/answer/9867159?hl=en)
- [Financial-features declarations](https://support.google.com/googleplay/android-developer/answer/13849271?hl=en)
- [Store-listing asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)
- [Drive API scope classification](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [OAuth application audience and publishing status](https://support.google.com/cloud/answer/15549945?hl=en)
- [First release and rollout behavior](https://support.google.com/googleplay/android-developer/answer/9859348?hl=en)
- [Managed publishing limitations](https://support.google.com/googleplay/android-developer/answer/9859654?hl=en)
- [Halting a fully rolled-out release](https://support.google.com/googleplay/android-developer/answer/16285429?hl=en)
- [Android vitals](https://support.google.com/googleplay/android-developer/answer/9844486?hl=en)
