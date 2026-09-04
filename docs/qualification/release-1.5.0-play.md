# Release 1.5.0 Google Play qualification ledger

Append-only evidence ledger for the first Google Play release. Append a dated
entry for each later result; do not overwrite prior evidence. A PASS applies
only to the exact candidate hash recorded with it.

## Fixed release decisions

| Field | Value |
|---|---|
| App | Open Tasks |
| Package | `app.opentasks` |
| Candidate | `versionName` 1.5.0; `versionCode` 7 |
| Account type | Personal Google Play developer account |
| Category | Productivity |
| Default listing language | English (United Kingdom) |
| Price / ads | Free / none |
| Audience | 13 and older; not directed to children |
| Regions | Every Play-supported country and region |
| Privacy URL | `https://ksdaklmk.github.io/open-tasks/privacy/` |
| Support URL | `https://ksdaklmk.github.io/open-tasks/support/` |
| Existing key role | App-signing/delivery key; retains direct-APK update compatibility |
| Upload key role | Separate upload-only key; never a delivery or OAuth key |
| Play artifact | Android App Bundle from `:app:bundleRelease` |
| Approved track path | Internal → mandatory closed → production-access application → production |

## Entry — 2026-08-27T08:43:14Z — worker — candidate 1.5.0/7

### Safe repository baseline

| Gate | Status | Evidence |
|---|---|---|
| Execution worktree | PASS — safe repository evidence recorded | Clean `google-play-submission` branch before this ledger change. |
| Current commit | PASS — safe repository evidence recorded | `7c317506869efebf4d789aa166dfd1f9a703c035` |
| Source package and pre-change version | PASS — safe repository evidence recorded | `app/build.gradle.kts`: `app.opentasks`, 1.4.0 / 6. |
| `v1.4.0` immutable release target | PASS — safe repository evidence recorded | Annotated tag object `b346c55539fd4a68a74a45e6d7ace7cc1f8182d7`; target commit `a47accb45220bee8007da025116b96b878baec0e` (`2026-08-24T13:15:59+07:00`, `docs: qualify release 1.4.0 for signed sideload`). |

### Developer identity, device verification, and package registration

| Gate | Status | Evidence required |
|---|---|---|
| Personal-account creation date and legal/contact/payment profile | PENDING — evidence not yet produced | Owner-only Play Console inspection; account state was not accessed. |
| Two-step verification | PENDING — evidence not yet produced | Owner-only account inspection; security-sensitive state was not accessed. |
| Play device verification | PENDING — evidence not yet produced | Owner-only Play Console and physical-device check. |
| Android developer verification | PENDING — evidence not yet produced | Owner-only Play Console check. |
| `app.opentasks` package registration and ownership | PENDING — evidence not yet produced | Owner-only Play Console check; no account or Console state was accessed. |

### App-signing and upload keys

| Gate | Status | Evidence required |
|---|---|---|
| Existing app-signing key algorithm, size, expiry, SHA-1, and SHA-256 | PENDING — evidence not yet produced | Owner key inspection and independent certificate record; private key access is out of scope. |
| Existing key accepted as Play app-signing/delivery key | PENDING — evidence not yet produced | Owner Play Console setup and recorded public certificate comparison. |
| Separate upload key created and registered | PENDING — evidence not yet produced | Owner-controlled key and Play Console evidence; no key material or account state accessed. |
| Upload certificate matches uploaded AAB signer | PENDING — evidence not yet produced | Exact AAB plus owner-held expected public fingerprint. |
| Shipped 1.4.0 APK signer authenticates to the independent owner record | PENDING — evidence not yet produced | Retained/rebuilt v1.4.0 APK and independent owner record; artifact and private record were not accessed. |

### GitHub Pages and OAuth

| Gate | Status | Evidence required |
|---|---|---|
| Privacy page deployed and public | PENDING — evidence not yet produced | Public HTTPS status/link check after owner enables Pages. |
| Support page deployed and public | PENDING — evidence not yet produced | Public HTTPS status/link check after owner enables Pages. |
| OAuth consent is External and In production | PENDING — evidence not yet produced | Owner-only Google Cloud Console inspection. |
| OAuth package and delivery certificates | PENDING — evidence not yet produced | Owner-only Google Cloud configuration with every applicable Play delivery SHA-1. |
| OAuth scope is only `drive.appdata` | PENDING — evidence not yet produced | Final binary and owner configuration inspection. |

### Repository and candidate qualification

| Gate | Status | Evidence required |
|---|---|---|
| Repository/build verification | PENDING — evidence not yet produced | Candidate 1.5.0/7 build commands and outcomes. |
| Exact AAB signature, identity, manifest, and release posture | PENDING — evidence not yet produced | SHA-256 of `app-release.aab` and bundle verifier output. |
| APK / APK set inspection | PENDING — evidence not yet produced | SHA-256 values and derived-install artifact verification. |
| 16 KB native-page-size compatibility | PENDING — evidence not yet produced | Exact candidate native inspection. |
| Android backup qualification | PENDING — evidence not yet produced | Exact candidate on disposable hardware with synthetic data. |
| Optional encrypted Drive backup and restore | PENDING — evidence not yet produced | Owner-authorized exact-candidate evidence without account details. |
| Signed 1.4.0-to-Play upgrade preserves data | PENDING — evidence not yet produced | Play-delivered upgrade on a disposable device with synthetic encrypted workspace. |
| Fresh Play install | PENDING — evidence not yet produced | Exact candidate result on compatible disposable device. |

### Listing and declarations

| Gate | Status | Evidence required |
|---|---|---|
| Store listing and assets | PENDING — evidence not yet produced | Final listing, committed synthetic assets, and deployed URLs. |
| Data Safety | PENDING — evidence not yet produced | Field-by-field final-binary and transport audit. |
| App Content | PENDING — evidence not yet produced | Owner Console declaration reconciled with final behavior. |
| Content rating | PENDING — evidence not yet produced | Owner Console questionnaire result. |
| Target audience | PENDING — evidence not yet produced | Owner Console declaration matching 13+ and non-Families scope. |

### Internal and closed testing

| Gate | Status | Evidence required |
|---|---|---|
| Internal-track upload and Play pre-review | PENDING — evidence not yet produced | Exact candidate hash, track result, and pre-launch report. |
| Internal fresh-install critical journey | PENDING — evidence not yet produced | Aggregate synthetic-data result on Play-capable physical hardware. |
| Internal signed-upgrade critical journey | PENDING — evidence not yet produced | Aggregate 1.4.0-to-Play result and delivered-signer check. |
| Closed-test continuous opt-in | PENDING — evidence not yet produced | At least 12 compatible testers continuously opted in for 14 days; aggregate counts only. |
| Closed-test feedback and fixes | PENDING — evidence not yet produced | Anonymized themes, dispositions, and affected candidate hashes. |

### Production access, go/no-go, and monitoring

| Gate | Status | Evidence required |
|---|---|---|
| Production-access application | PENDING — evidence not yet produced | Owner submission based on qualifying closed-test facts. |
| Production access approved | PENDING — evidence not yet produced | Owner-recorded Play decision. |
| Production go/no-go | PENDING — evidence not yet produced | Owner-signed confirmation that all required exact-candidate gates pass. |
| Global production publication | PENDING — evidence not yet produced | Owner-recorded standard publishing result for all selected regions. |
| Day 1–7 monitoring | PENDING — evidence not yet produced | Daily policy, vitals, update, review, Drive, and support review. |
| Day 8–30 monitoring | PENDING — evidence not yet produced | Weekly equivalent review through day 30. |
| First-release incident readiness | PENDING — evidence not yet produced | Owner acknowledgement of unpublish, corrective higher-code release, and requalification procedure. |

### Consumed version codes and candidate hashes

| Version code | Version name | State | SHA-256 / evidence |
|---:|---|---|---|
| 6 | 1.4.0 | Historical signed-sideload release; not a Play upload | See `docs/qualification/release-1.4.0-sideload.md`; no certificate value copied here. |
| 7 | 1.5.0 | PENDING — evidence not yet produced | No AAB, APK set, APK, mapping, symbols, SBOM, or Play upload hash has been produced. |

## Entry — 2026-08-27T10:24:56Z — worker — candidate 1.5.0/7

### Task 5 listing and declaration repository evidence

This entry appends repository evidence only. It does not change any earlier
owner, account, public-deployment, final-binary, Console, asset, testing or
publication gate.

| Gate | Status | Evidence |
|---|---|---|
| Exact en-GB listing copy | PASS — repository evidence produced | `docs/google-play/store-listing.md` versions the fixed app name, short/full descriptions and release notes without unsupported compatibility, sync, collaboration, affiliation or absolute-security claims. |
| Listing character limits | PASS — repository evidence produced | Literal `printf %s | wc -m` measurements: short description 70/80, full description 588/4,000, release notes 150/500; all three contracted `test` commands exited 0. |
| Fixed listing identity | PASS — repository evidence produced | Store listing records Open Tasks, `app.opentasks`, Productivity, English (United Kingdom), Free, No ads, 13+, every Play-supported country/region, and the fixed privacy/support URLs. Developer identity and public URL deployment remain PENDING. |
| Data Safety source matrix | PASS — draft repository evidence produced; PENDING — final AAB audit and Console answer | One row per implicated data type records on-device processing, every known off-device path/recipient, preliminary Play classification position, optionality, purpose, transit, deletion and final-evidence state. It does not collapse behavior to “no data collected.” |
| Off-device behavior separation | PASS — repository evidence produced | Listing evidence separately records automatic encrypted Drive backup/metadata/account binding, Android's allowed portable backup package, user-directed plaintext CSV/Markdown/report exports, encrypted `.otvault` import/export, FileProvider sharing, Play Services authorization and Google Play platform diagnostics. |
| Release dependency audit | PASS — repository evidence produced; PENDING — final AAB audit | `:app:dependencies --configuration releaseRuntimeClasspath` succeeded with the contracted SDK environment. Temporary report `/private/tmp/open-tasks-release-runtime-dependencies.txt` contains `play-services-auth:21.6.0`; no Firebase, analytics, telemetry, advertising, billing or crash-reporting SDK match was found. The report is not committed. |
| Source network/scope audit | PASS — repository evidence produced; PENDING — final AAB and owner Cloud configuration | Production app source contains the Google Drive origin, one active `drive.appdata` scope and the privacy link opened in a browser; no developer-operated application backend or advertising-ID API/permission was found. Broad `Authorization`, `GoogleSignInAccount`, `crash`, XML namespace and test/example URL matches were reconciled. |
| Manifest/backup audit | PASS — repository evidence produced; PENDING — final merged manifest and device qualification | Manifest permissions/components and both backup-rule formats are recorded with their purposes. Android 12+ extraction includes only `open_tasks_portable_v1.otb` and requires cloud encryption capability; legacy backup rules exclude all domains. |
| App Content draft | PASS — draft repository evidence produced; PENDING — current Console wording/owner submission | Evidence matrix covers ads, app access, audience, content rating, excluded app categories, account deletion, optional Drive authorization, permissions/components/backup and reviewer navigation. Console-only questions remain explicitly PENDING. |
| Store assets | PENDING — captured from final release UI | Manifest contracts exact names, dimensions, content and alt text for the icon, feature graphic, four phone screenshots and four large-screen screenshots; no asset was produced or approved in Task 5. |
| Exact candidate artifacts and declarations | PENDING — evidence not yet produced | Task 5 did not build, inspect, sign or upload an AAB/APK, access Play/Cloud Console, verify public deployment, submit declarations or create tester/account evidence. |

## Entry — 2026-08-27T10:41:58Z — worker — Task 5 review pause

This entry supersedes the 2026-08-27T10:24:56Z Task 5 draft PASS claims for
promotion purposes. The draft is not accepted and must not be used for Play
Console answers. It does not rewrite the prior repository evidence.

### Superseding status

- **Task 5 listing and declarations:** FAIL — review findings unresolved.
  Independent review of `4fa949b6d648d18511df791cfa9d208b66252652` found
  specification FAIL and quality FAIL: 4 Important, 2 Minor, 0 Critical. No
  fix has started; the owner paused work. See
  `.superpowers/sdd/2026-08-27-google-play-submission-plan/task-5-review.md`.
- **Data Safety/off-device evidence:** FAIL — calendar-provider handoff is
  absent; Drive disconnect retention is misstated; Android portable attachment
  metadata is conflated with file bytes; and remote-deletion residual records
  are omitted.
- **Deletion and final-binary status wording:** FAIL — `withdraw package` is
  not an exposed deletion path, and some final-binary labels do not use the
  exact `PENDING — final AAB audit` status.
- **Task 6 and release actions:** PENDING — owner resume required. Task 6
  has not started; no Pages/site workflow, deployment, Console, Cloud, key,
  real AAB, device, or external-account action occurred.

## Entry — 2026-08-27T11:00:00Z — worker — Task 5 fix round 1

This dated correction supersedes the Task 5 review-pause FAIL for the six
document defects only. It preserves every earlier entry, including the pause,
and does not create final-AAB, runtime, Console, owner, account, deployment or
publication evidence.

| Gate | Status | Evidence |
|---|---|---|
| Data Safety source matrix | PASS — corrected repository evidence | Adds the explicit, confirmed calendar-provider/app handoff for task titles, project names and schedules/dates, with recipient, `ACTION_INSERT` transport, purpose, destination-controlled deletion and classification uncertainty. It corrects Drive disconnect retention, attachment-package scope and remote-deletion residuals. PENDING — final AAB audit. PENDING — current Console wording. |
| Off-device behavior separation | PASS — corrected repository evidence | Separates Drive/`.otvault` encrypted attachment file bytes from Android portable encrypted attachment record/metadata, and records attachment-content/history-deletion residuals. The Android package has reprepare/clear-app-data and platform controls; `withdraw` is not a user deletion path. PENDING — final AAB audit. |
| Final-binary status wording | PASS — corrected repository evidence | Current Task 5 final-AAB/merged-manifest/transitive-binary uncertainties use the exact separate status `PENDING — final AAB audit`; owner, runtime and Console gates remain separately pending. Historical wording above is retained as append-only evidence. |
| Fixed-fact and safety checks | PASS — corrected repository evidence | Focused RED→GREEN declaration check, literal listing limits, fixed-fact/prohibited-claim scans, `git diff --check`, exact two-file scope and sensitive-value scan ran without final AAB, Console or runtime claims. |

## Entry — 2026-08-27T16:01:54Z — worker — Task 6 Pages deployment

This entry supersedes the earlier privacy/support deployment PENDING states
only. It creates no Play Console, Cloud Console, signing, final-AAB, device,
testing, or publication evidence.

The first Pages attempt started before the repository had a Pages site and
failed at `configure-pages` with HTTP 404. Under explicit owner approval, the
repository Pages source was then enabled as GitHub Actions and the same pinned
run was retried. Attempt 2 is the result promoted below.

| Gate | Status | Evidence |
|---|---|---|
| Integration and push | PASS — exact repository commit | Local `main` and `origin/main` advanced to `f0c85cc04b3c68dc6c60849aa450ca74f065e2b0`. The post-integration `testDebugUnitTest lintDebug :app:assembleDebug` gate reported `BUILD SUCCESSFUL in 27s`, 553 actionable tasks, 35 executed, 37 from cache, and 481 up-to-date. |
| GitHub Pages source | PASS — owner-approved repository configuration | GitHub's Pages API reports `build_type: workflow`, `public: true`, and `https_enforced: true` for `https://ksdaklmk.github.io/open-tasks/`. |
| Pinned Pages deployment | PASS — deployed exact commit | [Pages run 33090904000](https://github.com/ksdaklmk/open-tasks/actions/runs/33090904000), attempt 2, deployed commit `f0c85cc04b3c68dc6c60849aa450ca74f065e2b0` successfully through deployment `6126316671`; the successful status was recorded at `2026-08-27T16:01:54Z`. |
| Privacy page public HTTP/content | PASS — anonymous public evidence | `https://ksdaklmk.github.io/open-tasks/privacy/` returned HTTP 200 and `text/html; charset=utf-8`. The fetched bytes equal `site/privacy/index.html` and contain `Open Tasks privacy policy`, `app.opentasks`, and the exact public developer display name `Kritsada K.`. |
| Support page public HTTP/content | PASS — anonymous public evidence | `https://ksdaklmk.github.io/open-tasks/support/` returned HTTP 200 and `text/html; charset=utf-8`. The fetched bytes equal `site/support/index.html` and contain the fixed GitHub Issues route plus the public-data warning. |
| Deployed static-safety scan | PASS — fetched-content evidence | Both fetched pages contain no script, form, tracker, third-party font, insecure `http:` reference, or mixed-content path. Anonymous retrieval required no authentication. |
| Normal/private browser, narrow/wide layout, and keyboard focus | PENDING — browser evidence not produced | No controllable browser was connected. No rendered layout, private-window, or keyboard-tab result is claimed from the HTTP/content checks. |

## Entry — 2026-08-27T23:28:53Z — worker — Task 6 normal-browser QA

This entry supersedes only the normal-browser portions of the combined browser
PENDING row above. An actual private-window run remains PENDING. It creates no
Play Console, Cloud Console, signing, final-AAB, device, testing, or publication
evidence.

| Gate | Status | Evidence |
|---|---|---|
| Wide normal-Chrome layout | PASS — rendered browser evidence | Both deployed URLs rendered at 1425×802 without clipping, collision, or horizontal overflow; each document's scroll width equalled the viewport width. The semantic headings, public developer name, support warning, and links were visible in the expected order. |
| Narrow normal-Chrome layout | PASS — rendered browser evidence | Both deployed URLs rendered at 360×800 without clipping, collision, or horizontal overflow; every heading and link remained within the 360 px viewport. The temporary viewport override was reset after the checks. |
| Normal-Chrome keyboard focus and activation | PASS — rendered browser evidence | Privacy Tab order reached `Open Tasks support`. Support Tab order reached `GitHub Issues` then `Privacy policy`. Every focused link showed a solid 3 px `rgb(198, 78, 43)` outline with a 3 px offset. Enter activated both internal page transitions, including the narrow support-to-privacy transition. |
| Normal-Chrome browser warnings | PASS — observed browser evidence | The privacy page produced no browser console warning or error; its only DOM resource reference was the HTTPS support link. The earlier fetched-content scan remains the authoritative two-page mixed-content/static-safety evidence. |
| Private/Incognito browser | PENDING — owner action required | The connected Chrome control cannot create an Incognito window. The owner paused before opening and exposing one to the extension, so no private-window layout, authentication, or keyboard result is claimed. |

## Entry — 2026-08-29T03:24:40Z — worker — Task 6 Incognito-browser QA

This entry supersedes the private/Incognito PENDING row above and closes the
Task 6 browser gate. It creates no Play Console, Cloud Console, signing,
final-AAB, device, testing, or publication evidence.

| Gate | Status | Evidence |
|---|---|---|
| Incognito public access | PASS — rendered private-window evidence | The owner opened a real Chrome Incognito window and allowed the ChatGPT extension there. Both fixed HTTPS URLs loaded directly with their expected titles and content, without an authentication prompt or redirect. |
| Wide Incognito layout | PASS — rendered browser evidence | At 1425×802, both pages had document scroll width exactly equal to the 1,425 px client width, no horizontal overflow, and no clipped content. The privacy headings and public developer name, plus the support warning and links, rendered in the expected order. |
| Narrow Incognito layout | PASS — rendered browser evidence | At 360×800, both pages had document scroll width exactly equal to the 360 px client width. Element-boundary inspection found no content outside the viewport, and visual inspection found no clipping, collision, or unreadable layout. |
| Incognito keyboard focus and activation | PASS — rendered browser evidence | Privacy Tab order reached `Open Tasks support`. Support Tab order reached `GitHub Issues` then `Privacy policy`. Every focused link showed a solid 3 px `rgb(198, 78, 43)` outline with a 3 px offset and remained within the viewport. Enter activated both internal page transitions at wide and narrow widths. |
| Incognito warnings and resource boundary | PASS — observed browser evidence | Browser warning/error logs were empty on both pages at both widths. Neither page exposed a script, external stylesheet, image, or iframe resource; the expected HTTPS links were the only navigation targets. |
| Viewport cleanup | PASS — browser state restored | The temporary responsive override was reset. Chrome returned to its normal 1425×780 page viewport with the privacy URL open and no horizontal overflow. |

## Entry — 2026-08-29T10:13:48Z — worker — Task 7 Play identity and signing setup

This entry supersedes the earlier Task 7 PENDING states for owner verification,
package registration, Play app creation, and signing-key registration. It does
not create final-binary, OAuth, testing, release, review, or publication
evidence.

| Gate | Status | Evidence |
|---|---|---|
| Current Play signing and registration rules | PASS — official guidance rechecked 2026-08-29 | Current Google Play and Android guidance still permits an account owner to replace the initial Google-managed key with a supplied RSA 2048+ app-signing key before open testing or production, and recommends a distinct RSA 2048+ upload key. The 30 September 2026 package-registration and Thailand enforcement milestone remains current. |
| Personal developer account and owner verification | PASS — owner/Console evidence 2026-08-29 | The personal developer account is active. The owner confirmed that legal and payment-profile details are correct, two-step verification is active, and Play device verification is complete or not required. Legal/contact/payment values and evidence remain only in private owner storage. |
| Android developer verification and package ownership | PASS — Play Console evidence | `Open Tasks` / `app.opentasks` is `Registered`, last updated 14 August 2026. Its sole package key is `Verified` and has SHA-256 `A6:1E:11:E1:0E:CA:14:B8:88:CD:68:50:86:2F:9C:EA:61:90:9D:CC:6A:75:C5:A5:1D:F0:C8:F6:4B:B5:1A:97`, matching the independently authenticated existing release certificate. No proof APK was required. |
| Play app creation | PASS — app created 2026-08-29 | One Play app exists for `Open Tasks`, package `app.opentasks`, English (United Kingdom), App, Free; internal Play app ID `4975932743584513202`. The owner accepted the three required declarations. Automatic protection was turned off before creation to preserve the qualified direct-APK/local-first behavior. |
| Fixed Console settings | PASS — saved owner-approved settings | Ads `No`; sign-in details `Yes` with credential-free instructions for the optional biometric app lock; target ages 13–15, 16–17, and 18+; category `Productivity`; required public developer email present but omitted here; trusted-partner use of access instructions off. Phone and website remain blank. |
| Countries and regions | PENDING — deferred to first production availability step | Production availability is locked until the personal-account closed-test and production-access requirements are met, so the planned all-Play-regions selection cannot yet be saved. No region or release state was changed. |
| Existing app-signing/delivery key | PASS — Play-held certificate independently downloaded and checked | Alias `opentasks`; RSA 4096; `SHA384withRSA`; valid 8 August 2026 through 24 December 2053. Play app-signing SHA-1 is `FC:FE:F1:3C:86:18:79:A6:1E:78:6C:BC:FF:61:C5:35:20:F0:BB:D0`; SHA-256 is `A6:1E:11:E1:0E:CA:14:B8:88:CD:68:50:86:2F:9C:EA:61:90:9D:CC:6A:75:C5:A5:1D:F0:C8:F6:4B:B5:1A:97`. Both exactly match the authenticated existing release JKS and the verified package certificate. |
| Separate upload key | PASS — generated, backed up, and registered | Alias `opentasks-upload`; RSA 4096; `SHA384withRSA`; valid 29 August 2026 through 14 January 2054. Play upload SHA-1 is `7F:CC:5A:0C:9C:32:AC:53:E8:7A:A7:EF:8B:AB:ED:DF:5D:3D:57:13`; SHA-256 is `54:D6:F8:C3:CF:D1:DA:63:31:52:69:37:CC:03:DB:6D:45:EA:D5:A7:4E:B6:58:5F:EC:B8:72:1D:6B:92:2F:FF`. Play displays the same public fingerprints. The JKS and four-field properties file are mode `0600`; passwords were never displayed or recorded. |
| Signing-role separation | PASS — required equality and inequality checks | Play app-signing SHA-1/SHA-256 equals the existing release certificate; Play upload SHA-1/SHA-256 equals the new upload certificate; the app-signing and upload fingerprints differ. The owner confirmed primary and offline backup of the upload key, password, public certificate, and recovery record. |
| Disposable transfer cleanup | PASS — exact temporary files removed | After Play accepted the change and the public certificates were verified, the encrypted PEPK ZIP, exported upload-certificate PEM, private launcher, downloaded encryption public key, downloaded PEPK JAR, and downloaded verification certificate were removed. The persistent release JKS, upload JKS, and upload properties remain present and mode `0600`. |
| Version-code consumption and release state | PASS — no code consumed by Task 7 | No AAB or APK was uploaded, and no internal, closed, open, or production release was created. Play shows every release entry point as `Get started` and no unpublished changes. Candidate version code 7 remains unused. |

Private owner storage retains account evidence, key backups, passwords,
recovery instructions, and Play Console evidence. Git contains only the public
certificate metadata and non-sensitive completion states above.

## Entry — 2026-08-29T11:34:54Z — worker — Task 8 OAuth domain-verification pause

This entry supersedes the earlier owner-Cloud-configuration PENDING state but
does not close Task 8. It records configuration and root-cause evidence only;
no private account/contact value, OAuth client ID, token, secret, final
candidate, Play upload, track, tester or publication evidence is created.

| Gate | Status | Evidence |
|---|---|---|
| Source authorization boundary | PASS — source and history evidence | Production and debug use Google Play Services authorization and request only `https://www.googleapis.com/auth/drive.appdata`. Production embeds no client secret or server client ID. Current, debug, released 1.4.0 and repository history contain no active use of the 17 removed broad scopes. |
| Task 8 linked-worktree baseline | PASS — non-device build evidence | With the known Android SDK environment, `testDebugUnitTest`, `lintDebug` and `:app:assembleDebug` reported `BUILD SUCCESSFUL in 35s`; 553 actionable tasks, 30 executed, 30 from cache and 493 up-to-date. The first attempt failed before tasks only because ignored `local.properties` is absent from linked worktrees. |
| Audience and publication | PASS — current Cloud configuration | Google Auth Platform reports user type `External` and publishing status `In production`. The former Testing-user-only restriction is removed at configuration level. |
| Scope configuration | PASS — current Cloud configuration | Exactly one non-sensitive scope persists: `https://www.googleapis.com/auth/drive.appdata`. All 17 source-proven stale scopes were removed. The Verification centre states that data-access verification is not required because no sensitive or restricted scope remains. |
| Branding fields | PARTIAL PASS — saved Cloud configuration | App name `Open Tasks`, the current public privacy/support URLs and authorised domain are saved. Existing private support/developer contacts were retained without recording them. No logo was uploaded. Branding is not yet shown to users. |
| Android OAuth clients | PASS — current Cloud and signing evidence | Two existing Android clients for `app.opentasks` remain. The Play delivery client matches the authenticated release certificate; the older direct/debug client remains unchanged. The upload certificate is absent from both delivery clients. No client ID is recorded. |
| Public Search Console verification | PASS — URL-prefix ownership only | Public file `site/googlebfb12df764b54328.html` was committed to `main` as `22fa396ecfcd63034c51192ebecb8b852f17a3c8`, deployed by successful Pages run `33248701409`, and returned its exact one-line token anonymously. Search Console verified `https://ksdaklmk.github.io/open-tasks/` by that file and auto-verified the exact support-homepage child property. |
| OAuth brand verification | BLOCKED — DNS Domain property required | Two reverification attempts still reported the homepage as not registered. Current official Google guidance requires a Search Console Domain property verified at DNS level by a Cloud project owner/editor and explicitly rejects URL-prefix verification for this gate. The shared `github.io` DNS zone is controlled by GitHub, so the current Pages subdomain cannot satisfy the proof. No third review was submitted. |
| Required plan amendment | PENDING — owner domain and approval required | The owner must supply a custom domain/subdomain with DNS control. Before execution, amend Task 8 because the migration changes `OpenTasksApp.kt`'s hard-coded privacy URL, both static-page cross-links, public deployment evidence, listing/qualification evidence and OAuth branding values. Keep the current URLs and verification file live during transition. |
| Outside-allowlist consent flow | PENDING — owner account evidence | After branding passes, an ordinary Google account outside the former Testing allowlist must reach consent without recording the account. Full credentialed backup/restore remains Tasks 9 and 11. |
| Candidate and release state | PENDING — Task 9 not started | No immutable candidate, AAB, APK set, APK, mapping, symbols, SBOM, Play upload, release, track, tester, review submission, production-access request, tag or publication was created. |

Resume only from the new authoritative section at the top of `HANDOFF.md`.
Do not broaden scope, register the upload certificate as OAuth delivery,
remove the public verification file, submit another brand review, or start Task
9 until the custom-domain plan amendment is approved and Task 8 closes.

## Entry — 2026-09-03T08:15:51Z — worker — Task 8 closure with brand verification deferred

This entry closes Task 8 under the owner's decision of 3 September 2026 to
defer OAuth brand verification instead of adopting a custom domain. It
supersedes the BLOCKED and PENDING-amendment rows of the 2026-08-29 pause
entry. No Cloud Console, DNS, Play, key, device, candidate, or publication
action occurred; no account, client ID, token, secret, or private contact
value is recorded.

| Gate | Status | Evidence |
|---|---|---|
| Owner decision | PASS — recorded | The owner chose to keep the deployed `github.io` Pages URLs and defer brand verification. The spec's OAuth section and the plan's Task 8 Steps 4 and 5 carry dated amendments; `docs/google-play/store-listing.md` records the closure. |
| Google rule | PASS — official guidance read on 3 September 2026 | Google's OAuth verification guidance (`support.google.com/cloud/answer/9110914` and `13464321`) states that an app using only non-sensitive scopes need not complete verification, that brand verification is a lighter optional process required only to display the app name and logo on the consent screen, and that the homepage must be hosted on a verified domain the developer owns. |
| Source authorization boundary | PASS — re-run at `979a133` | `rg` over `app/src/main` and `app/src/debug` finds `AuthorizationRequest` and `DRIVE_APPDATA_SCOPE` only in `GoogleDriveAuthorizationManager.kt` and the debug-only qualification activity, both requesting exactly `https://www.googleapis.com/auth/drive.appdata`. The only `default_web_client_id`/`client_secret`/`server_client_id` match is the negative assertion in `RemoteBackupBoundaryInstrumentedTest.kt`. |
| Audience, scope, and Android clients | PASS — carried forward | The 2026-08-29 entry's `External`, `In production`, single-scope, and two-client evidence stands; the Console was neither changed nor re-inspected for this entry. |
| Branding fields | PARTIAL PASS — carried forward | App name, public URLs, and existing private contacts remain saved; the app name is not shown on the consent screen and no logo exists. |
| OAuth brand verification | DEFERRED — not a gate | No third review was submitted, no custom domain was adopted, and no workaround was applied. Users see Google's unbranded app identity on the consent screen until a custom domain is adopted under a separately approved change. Google's guidance does not state the exact unbranded rendering. |
| Public Search Console verification | PASS — retained | `site/googlebfb12df764b54328.html` stays deployed and the URL-prefix properties stay verified. |
| Outside-allowlist consent flow | PENDING — moved | Proven by the owner-present credentialed Drive gates in Task 9 Step 10 and Task 11 rather than as a Task 8 configuration check. |
| Candidate and release state | PENDING — Task 9 next | No immutable candidate, AAB, APK set, APK, mapping, symbols, SBOM, Play upload, release, track, tester, review submission, production-access request, tag, or publication exists. Android run `33728285482` on `979a133` is green on `verify`, `release`, `benchmark`, and the compact API 36 lane; the expanded API 37.0 lane failed before any test ran, its known observe-only class. |

Task 8 is closed. Resume from the authoritative section at the top of
`HANDOFF.md`; Task 9 begins from a clean commit at or after the one that
carries this entry.

## Entry — 2026-09-03T13:01:03Z — worker — Task 9 candidate qualification

Build commit: `4a47962f961e21869df76cf6545a0530cd5856d5`
Candidate: `versionName` 1.5.0, `versionCode` 7 (no version code consumed;
no Play upload, track, tester, review, or publication exists).

Two earlier candidates on the same version code were built and superseded
today without any upload: `301df82` (first bundle build) and `c9c51e2`
(a mistaken Kotlin plugin revert). Their artifacts are discarded; every
PASS below binds to the hashes in this entry only.

Tools: AGP 9.3.1, Gradle 9.7.1, Kotlin Gradle plugins 2.4.10, Compose BOM
2026.08.00 (Material 3 1.4.0), build-tools 36.0.0 (zipalign, apksigner,
aapt2), bundletool 1.18.3 (SHA-256 verified against the pinned value),
Xcode llvm-objdump, JDK 26 for bundletool/keytool, daemon JDK 21.

### Defects found by this task and fixed on `main`

| Commit | Defect | Evidence |
|---|---|---|
| `301df82` | `:app:bundleRelease` failed in `buildReleasePreBundle`: ABI splits plus `shrinkResources` emit one shrunk-resources file per split and AGP 9.3.1 rejects the bundle (issue 402800800). Reproduced from a clean build directory. Bundle invocations now drop ABI splits; the APK set is unchanged. | Failure log before, `BUILD SUCCESSFUL` after; APK sizes identical before and after. |
| `b9c53bd` | Blank UI after any cold start with app lock enabled: `OpenTasksColors` initialised by calling `oklch()` in the theme facade, whose static initialiser built `LightColorScheme` from still-zero fields, so every `MaterialTheme` colour was transparent for the process. Pre-existing: reproduced on 1.4.0/6, in debug and release, on host-GPU and SwiftShader AVDs; a warm relock did not trigger it. `oklch()` moved to `Oklch.kt`; `OklchTest` guards the order. | Home header region after PIN unlock: 1 colour and window background (250,250,250) before; 229+ colours and theme background (247,247,247) after, on 1.4.0→candidate upgrade, fresh candidate, and repeated cold starts. |
| `4a47962` | Re-lands the Kotlin Gradle plugins 2.4.10 bump that `c9c51e2` had reverted on a confounded bisect (fresh installs versus an upgraded locked install). | The blank reproduced on the 2.3.21 plugins and on 1.4.0. |

### Gates

| Gate | Status | Evidence |
|---|---|---|
| Step 1 bundletool | PASS | `bundletool-all-1.18.3.jar` downloaded once with owner approval; `shasum -a 256 -c` OK. |
| Step 2 freeze | PASS | Clean tree at the build commit; 1.5.0/7. |
| Step 3 repository and supply-chain gates | PASS | `testDebugUnitTest lintDebug :app:assembleDebug` `BUILD SUCCESSFUL` (553 tasks); `verify-actions-workflow.sh`, `verify-release-size-script.sh`, `verify-release-bundle-script.sh` exit 0; `cyclonedxBom` OK with all 15 modules, Room 2.8.4, SQLCipher 4.18.0, Tink 1.23.0/1.18.0, Bouncy Castle 1.79/1.84, and no local path or credential (only the public repository URL). Android run `33748773138`: `verify`, `release`, `benchmark`, compact API 36 lane green; expanded API 37.0 lane failed before any test (observe-only class). Security run `33748773080` green. |
| Step 4 direct APK set | PASS | `check-release-size` within baseline deltas; owner ran `verify-release-apk.sh` on all three APKs with the release fingerprint through `read -s`: `all checks passed`. |
| Step 5 AAB build | PASS | Single `:app:bundleRelease` with the upload properties file; no source diff. |
| Step 6 AAB authentication | PASS | Owner ran `verify-release-bundle.sh` with the upload fingerprint from the upload keystore: `all checks passed` (bundletool validate, JAR signature, upload signer, manifest, ABI, 16 KB ELF). Manifest audit: permissions ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, INTERNET, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, SCHEDULE_EXACT_ALARM, USE_BIOMETRIC, WAKE_LOCK, DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION; exported components MainActivity, QuickAddTileService (BIND_QUICK_SETTINGS_TILE), GMS RevocationBoundService, Glance GlanceRemoteViewsService (BIND_REMOTEVIEWS), WorkManager SystemJobService (BIND_JOB_SERVICE) and DiagnosticsReceiver (DUMP), ProfileInstallReceiver (DUMP); native libs sqlcipher and androidx.graphics.path for arm64-v8a and x86_64; `debuggable` false; target 37, min 36. |
| Step 7 Play-like universal APK | PASS | Owner ran bundletool `build-apks --mode=universal` with the app-signing key (passwords at the prompt only); `zipalign -c -P 16 -v 4` `Verification successful`; signer equals the app-signing key, not the upload key. |
| Step 8 1.4.0 reproduction | PASS | Rebuilt from tag `v1.4.0` in a detached temporary worktree (removed afterwards); 1.4.0/6; signer matched the release fingerprint on the owner's terminal. |
| Step 9 offline upgrade and fresh install | PASS on the disposable `Pixel6_Scratch` AVD (API 37 Google Play arm64-v8a image, headless `-gpu host`, device PIN set for app lock) | Seeded on 1.4.0: project with summary and note, task with description, High priority, tag, due date, 1-day reminder, 30-minute estimate, checklist item, running 25/5 focus cycle and timer, app lock (5 minutes), Android backup package, Today widget. `adb install -r` of `play-universal.apk`: `Success`, first-install time retained, FOCUS_PHASE_BOUNDARY and DELIVER_REMINDER alarms retained, widget retained, app-lock prompt then PIN unlock, every seeded value present in the editor, project, and settings; backup package at the current local generation. Pixel checks after unlock, after a force-stop cold start through the lock, and on the editor, project, and privacy screens all render with the theme background. `pm clear` then fresh start: Home immediately (no Welcome, no Google prompt, no lock), renders, and Backup & recovery shows the optional Drive UI. Attachments cannot be seeded without the owner's Google account. |
| Step 10 release qualification | PENDING — owner-present | Physical API 36 arm64 benchmark, full connected suite on physical hardware, Android backup package restore, browser/print/share, notification delivery, quick tile, accessibility and font scale, and the credentialed Drive backup/list/restore/takeover/delete-history gates are not executed. |
| Step 11 hashes | PASS | Below; re-hash the AAB immediately before upload. No native debug-symbols archive was generated. |
| Step 12 declaration reconciliation | PASS for manifest-bound rows | `docs/google-play/store-listing.md` rows that waited on the final AAB audit now cite this entry; runtime and Console-wording rows stay PENDING for Step 10 and Task 11. |

### Candidate hashes (SHA-256)

| Artifact | Bytes | SHA-256 |
|---|---|---|
| `app-release.aab` | 12,158,942 | `019db0881f5770a35d4d1ab2c3a14574fbcda1994d15ba9a0657cb1e249cb885` |
| `app-release.apks` | 8,844,954 | `dc3d94751e15c45c66c95beb6c4e0e6958ac099cce6ed0afc21685ca5266740d` |
| `play-universal.apk` | 8,844,656 | `7e8fd3cc3671d4b8c1553c90b6f12595642ca3665794c6f66a3703752fb04cc0` |
| `app-arm64-v8a-release.apk` | 10,020,081 | `c44623aa539ecf362c7efdaac667a77e13d8b4e381c9384852e8ffed9f251b44` |
| `app-x86_64-release.apk` | 10,151,203 | `d0ecaf0db251a43bf8ecf425ce0ae1510db134c20781be7365ce3680cc9cd866` |
| `app-universal-release.apk` | 12,281,286 | `58e92e6df83e3493546710c31062b8a0896d7592f1a8245ce063ff8d77f48a90` |
| `mapping.txt` | 96,202,818 | `84f4bd0e50d8bd3847c9ec09a6100a1b9952d2cd85abbd4a42fe3f38ebda9b61` |
| `bom.json` | — | `89a93fcb4e6294de226977c25d7780514febbed1f6a5ee7c9a2404d9c48ef76c` |
| `bom.xml` | — | `86415cc01bbe07d25409b3fe7844c8d51bfdeeed8d47e12cdf514d3ea8c6ccad` |
| `/private/tmp/open-tasks-v1.4.0.apk` (1.4.0/6) | 12,114,299 | `62191206ec771f790a2050a96b74a18c5b677a3eb8ba13a98517e93f79d2a48b` |

No certificate fingerprint, password, account, token, or private contact is
recorded. The Pixel6_Scratch AVD was stopped after this entry.

## Entry — 2026-09-03T14:39:43Z — worker and owner — Task 9 Step 10 release qualification

Build commit `4a47962f961e21869df76cf6545a0530cd5856d5`, candidate 1.5.0/7,
AAB `019db0881f5770a35d4d1ab2c3a14574fbcda1994d15ba9a0657cb1e249cb885`.
No version code was consumed and no Play upload, track, tester, review,
production-access request, tag, or publication exists.

### Owner-run gates

| Gate | Status | Evidence |
|---|---|---|
| Owner-present Google Drive gate | PASS — owner-reported | The owner ran `RELEASING.md`'s owner-present Drive gate on their own physical device against this candidate installed over the existing signed 1.4.0 with the unchanged signing identity, and reported PASS. This is the first real-account exercise of the production OAuth configuration closed in Task 8, and the first run of the `b9c53bd` render fix on physical hardware. No account, token, client ID, fingerprint, or Drive object identifier is recorded. The attachment observation requested alongside it was not separately itemised by the owner and stays unproven. |
| Physical-device performance qualification | WAIVED — owner decision, 2026-09-03T14:39:43Z | The owner declined the disposable physical API 36 arm64 benchmark for this candidate. No `benchmarkData.json` was produced and `check-benchmark-thresholds.sh` did not run, so no startup or frame-timing claim exists for 1.5.0/7 and the accepted baseline is unchanged. Emulator timing is never threshold evidence. This waiver covers this candidate only; the gate is not removed from `RELEASING.md`. Residual risk accepted by the owner: an unmeasured startup or frame regression. The changes since the last measured release are dependency bumps plus `301df82` (bundle-only ABI-split change), `b9c53bd` (colour-constant file split), and `4a47962` (Kotlin plugin re-land), none of which alter product logic. |

### Disposable-AVD qualification (worker)

Target: sole ADB device `Pixel6_Scratch`, API 37 Google Play arm64-v8a image,
booted headless `-no-window -read-only -no-snapshot-load -no-snapshot-save
-gpu host`, animation scales 0, device PIN set for app lock, `play-universal.apk`
installed after uninstalling the inherited build. The read-only overlay was
discarded at shutdown.

`RELEASING.md` smoke checklist, all seven steps:

| Step | Status | Evidence |
|---|---|---|
| 1 Fresh launch opens the local workspace | PASS | Home reached with no Welcome surface, no Google prompt, and no authorization request. |
| 2 Project, task with a checklist item, tag | PASS | Project `Smoke Project`; task `Smoke Task` with checklist item (`0/1 complete`) and tag (`1 selected`). |
| 3 Force-stop and relaunch persists | PASS | Both records and the tag and checklist item present after restart. |
| 4 `.otvault` export then import, counts match | PASS | Export wrote a 14,534-byte archive and reported 0 attachments. A marker task was added afterwards, then the archive was imported: the preview declared 20 records and 0 attachments, the replacement warning and passphrase note rendered, and after `Replace vault` the workspace held exactly the pre-export project and task with the tag and checklist item, while the post-export marker task was correctly absent. |
| 5 App lock, background past the delay, unlock | PASS | Lock enabled at `Immediately`; backgrounding then relaunching showed the lock overlay; device-credential unlock returned to Home, which rendered with the theme background. |
| 6 Today widget renders counts | PASS | Widget placed on the launcher shows `1 · 0` with the accessibility text `1 open today · 0 overdue`, matching the single task then due today. |
| 7 Quick Add launcher shortcut | PASS | The `app.opentasks.action.QUICK_ADD` shortcut opens the Quick Add sheet. The Quick Settings tile was also added and tapped, and opens the same sheet. |

Additional Step 10 observations available without physical hardware:

| Check | Status | Evidence |
|---|---|---|
| Reminder delivery | PASS | A task due today at a time four minutes ahead with reminder `At time` armed exactly one `RTC_WAKEUP` `DELIVER_REMINDER` alarm at that instant and posted a notification when it arrived: channel `task_reminders`, `category=reminder`, two actions, `vis=PRIVATE` with title the task name and text the due timestamp, and a public lock-screen version reading `Task reminder` / `Open Open Tasks to view it`. The `task_reminders` and `daily_digest` channels exist at importance 3. |
| Accessibility at 200% font scale | PASS | At `font_scale 2.0` Home and Tasks render without loss, and the bottom navigation drops its five text labels while retaining all five content descriptions for screen readers, matching `shouldShowNavigationLabels`. Restoring 1.0 returns the labels. |
| Share hand-off (FileProvider) | PASS | Insights → Generate executive dashboard → `Share HTML` produced the plaintext disclosure, the include-task-details switch, and a system chooser reading `Sharing 1 file` for a generated `open_tasks_executive_*.html`. Nothing was sent to any recipient. |
| Browser hand-off for the privacy policy | PASS for the app's behaviour; page render not observed | More → Privacy policy emitted `act=android.intent.action.VIEW dat=https://ksdaklmk.github.io/…` from the app's uid to the device's registered https handler (`com.android.chrome/…IntentDispatcher`); no Open Tasks WebView activity exists. The page itself did not render because this fresh AVD's Chrome shows its own first-run Terms of Service screen, which the worker did not accept on the owner's behalf; the deployed page was already verified anonymously over HTTPS in the Task 6 entries. |
| Print | NOT APPLICABLE | The repository contains no `PrintManager`, `PrintHelper`, `ACTION_PRINT`, or `PrintAttributes` usage in any main source set; reporting is delivered as the downloadable or shareable HTML dashboard above. |
| Android backup package restore | PENDING — physical device | The package prepares and reports its generation on the AVD, but this image's Backup Manager is disabled and its transport is the Google transport, so a platform restore cannot be exercised here. |
| Attachments | PENDING — owner account | Attachment content requires a connected encrypted Drive backup, so no attachment could be created on the AVD. |

One test-harness artifact, not an app defect: two identically titled reminder
probe tasks exist because a worker automation batch ran twice. The
application's own counts stayed self-consistent throughout (three open tasks
across three rows, one overdue after the due time passed, and the project
workbench correctly reporting no tasks because none was assigned to it).

### Outcome

Step 10 is complete for this candidate under the recorded owner waiver, with
the Android backup package restore and attachment items carried forward as
PENDING. No Critical or High defect is open. Task 11 may proceed: re-hash the
AAB immediately before upload and confirm it still equals the hash above.

## Entry — 2026-09-04T00:01:48Z — worker — Task 10 listing assets paused at the owner's request

Candidate unchanged: build commit `4a47962f961e21869df76cf6545a0530cd5856d5`,
AAB `019db0881f5770a35d4d1ab2c3a14574fbcda1994d15ba9a0657cb1e249cb885`.

Two of the ten assets exist in the working tree, deliberately **uncommitted**
because the plan requires the owner's visual approval of a contact sheet before
any asset is committed or uploaded:

| Asset | Dimensions | Alpha | Bytes | SHA-256 | Source |
|---|---:|---|---:|---|---|
| `icon-512.png` | 512 × 512 | yes | 9,666 | `d760aeda4aa7dc9a216040bed13b268838d925928c56efe8923d1d86f5aeb5f0` | Byte-exact copy of the owner-approved `ic_launcher-playstore-512.png` from the Ember deliverables; not redrawn or masked. |
| `feature-graphic-1024x500.png` | 1024 × 500 | no | 13,137 | `bb49e935195fd596e37258570266e1e5451ee0d3af753d1567feab4ec127e0e5` | Composed by the worker: full-bleed `#C64E2B`, the approved white-card/charcoal glyph drawn from the launcher foreground vector's exact path geometry, and the words "Open Tasks" in Helvetica Neue Bold; content spans x 139–885 of 1024, inside the safe area; no device frame, provider mark, badge, price, or claim. Awaiting owner approval. |

The eight screenshots are not captured. Work performed towards them on the
disposable `Pixel6_Scratch` AVD (read-only overlay, since discarded): a
`wm size 1080x1920` override produces exact 1080 × 1920 `screencap` output at
the AVD's 420 dpi, and `play-universal.apk` with four synthetic projects and
one partially seeded task rendered correctly at that viewport. No screenshot
was taken, so no listing image exists yet. No Play Console action occurred.

## Entry — 2026-09-04T03:38:53Z — worker — Task 10 asset capture and a defect found

Candidate unchanged: build commit `4a47962f961e21869df76cf6545a0530cd5856d5`,
AAB `019db0881f5770a35d4d1ab2c3a14574fbcda1994d15ba9a0657cb1e249cb885`.
Screenshots were taken from that candidate's `play-universal.apk` on the
disposable `Pixel6_Scratch` AVD, phone viewport `wm size 1080x1920` at the
AVD's 420 dpi and large-screen viewport `wm size 1920x1080` with
`wm density 240`, which renders the real expanded navigation-rail layout.
SysUI demo mode fixed the clock at 09:30 and hid mobile/notification icons so
no account, device identifier, or notification preview appears. Synthetic
content only: four projects, six tasks, one milestone, tags, priorities, due
dates, a checklist, a reminder, and an estimate. Drive stayed disconnected,
app lock off, light theme, 100% font.

### Assets complete and mechanically validated (uncommitted, pending owner approval)

| Asset | Dimensions | Alpha | Bytes |
|---|---:|---|---:|
| `icon-512.png` | 512 × 512 | yes | 9,666 |
| `feature-graphic-1024x500.png` | 1024 × 500 | no | 13,137 |
| `phone-02-tasks.png` | 1080 × 1920 | yes | 144,230 |
| `phone-03-project.png` | 1080 × 1920 | yes | 157,556 |
| `phone-04-more-backup.png` | 1080 × 1920 | yes | 147,035 |
| `large-02-project-board.png` | 1920 × 1080 | yes | 144,739 |
| `large-03-schedule.png` | 1920 × 1080 | yes | 116,926 |
| `large-04-more-backup.png` | 1920 × 1080 | yes | 110,788 |

The owner approved the feature graphic on 4 September. SHA-256 values are
recorded when the set is complete and committed, because a re-capture of the
two held images may accompany a rebuild.

### Defect found by this capture — Home project progress always 0/0

`HomeScreen` (`feature/home/.../HomeScreen.kt:232`) passes `snapshot.projects`
straight to `ProjectProgressRow`, which prints
`completedTasks/totalTasks`. Those are stored columns on `ProjectEntity`,
written `= 0` at project creation in both repositories and never updated by
any command, so Home shows `0/0` and an empty progress bar for every project
a user creates, permanently. `ProjectsScreen` is unaffected because it
recomputes both counts from the task list before rendering
(`ProjectsScreen.kt:202-209`); the project workbench is likewise correct.

Proof on the candidate build: project "Design review" with two tasks, one
completed, showed "1 open • 1 complete • 0 blocked" on the workbench and
"0/2" on the Projects list, while Home showed "0/0".

Impact is cosmetic, with no data loss, but it is on the app's primary screen
and would appear in the listing. `phone-01-home.png` and `large-01-home.png`
are therefore **held out of `docs/google-play/assets/`** rather than shipped;
the pending captures are retained in the session scratch directory. Task 10
cannot close until they are re-captured.

No Play Console action occurred. Version code 7 remains unconsumed, so a fix
can rebuild at 1.5.0/7 without burning a version code, at the cost of
re-running the owner's three artifact-bound signing gates.
