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
