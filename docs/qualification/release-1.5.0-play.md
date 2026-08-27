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
