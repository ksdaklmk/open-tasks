# Release 1.4.0 sideload qualification

## Status and candidate identity

**Qualified for the approved annotated tag and push.** Automated host,
supply-chain, release-build, size, structural APK, and real independent-owner
signer gates pass. The owner approved the 1.4.0 version bump, tag, and release
on 24 August 2026 and accepted the external evidence boundaries below.

- Date: 24 August 2026
- Product implementation head:
  `68be1b713a665258ba014562b2944af197cd9b18`
- Pre-tag repository base:
  `540526fdb22791b64c2d00c878ece6b89911d128`
- Version: versionName 1.4.0, versionCode 6
- Distribution: signed sideload APKs; no AAB or Play-distributed release
- Signing: unchanged external user-held release identity; no certificate value
  or signing material recorded

## Automated verification

| Gate | Result |
|---|---|
| `./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace` | PASS in 50s; 553 actionable tasks (51 executed, 32 from cache, 470 up-to-date) |
| `scripts/verify-actions-workflow.sh` | PASS |
| `./gradlew help cyclonedxBom --stacktrace` | PASS in 5s; 580 components; Room, SQLCipher, Tink, and Bouncy Castle present; local-secret/path scan clean |
| `bash scripts/verify-release-size-script.sh` | PASS |
| `./gradlew :app:assembleRelease --stacktrace` | PASS in 52s; 442 actionable tasks (23 executed, 11 from cache, 408 up-to-date) |
| `scripts/check-release-size.sh ...arm64... ...universal...` | PASS; both files are 36 bytes over the accepted baseline and below all review/hard-cap thresholds |
| `bash scripts/verify-release-apk-script.sh` | PASS; verifier harness fail-closed cases remain green |
| Direct `apksigner verify` plus `aapt2 dump badging` | PASS for valid signatures, 1.4.0/6, non-debuggable packaging, and exact arm64-only/x86_64-only/universal-both ABI sets |
| Real `scripts/verify-release-apk.sh` with independent owner input | PASS — owner returned only `verify-release-apk: all checks passed`; all three exact artifacts below authenticated without recording the certificate value |

## Signed disposable-AVD smoke

Executed as one complete run against the exact universal APK listed below on
the sole ADB target: the `Pixel6_Scratch` API 37 / `arm64-v8a` AVD
(`sdk_gphone64_arm64`), booted read-only with no snapshot load or save. The
protected `Pixel_10_Pro_Fold` AVD was never booted and no physical device was
connected.

| # | Required step | Result |
|---|---|---|
| 1 | Fresh launch and start without restoring | PASS — `Welcome to Open Tasks` reached the empty local Home after `Continue offline` |
| 2 | Add a project, task with one checklist item, and tag | PASS — all four synthetic records were created and linked |
| 3 | Force-stop and relaunch | PASS — the project, one open task, checklist item, and tag persisted |
| 4 | Export and import `.otvault` | PASS — the 15,143-byte export preview reported 21 records and 0 attachments; replacement restored the same project and one-open-task state |
| 5 | Enable app lock, background past its delay, and unlock | PASS — Immediate lock presented the system credential challenge and the temporary PIN restored access |
| 6 | Place the Today widget | PASS — `0 open today · 0 overdue` and Quick Add rendered, matching the undated test task |
| 7 | Open through the Quick Add launcher shortcut | PASS — `Capture a task` launched through app lock and opened the Quick Add sheet after unlock |

Summary: **7 of 7 rows PASS.** The exported archive and temporary credential
were removed, the package was uninstalled from the disposable overlay, the
overlay was terminated, and the final ADB target list was empty.

## Candidate artifacts

| Artifact | Bytes | SHA-256 | Native code |
|---|---:|---|---|
| `app-arm64-v8a-release.apk` | 9,852,846 | `b77a9baecb88a52163c58f532301514ed85a75f38ac2837abcd469d2ea4f862e` | `arm64-v8a` |
| `app-x86_64-release.apk` | 9,984,216 | `cb0b15191e48412baeb1ea6a3b0ea0833eab2ec54361532a8623e0ea9b41d231` | `x86_64` |
| `app-universal-release.apk` | 12,114,299 | `88401d0e2d138ac2a92cb3191625add60e0d874707a95befa49e1e9f4b7dea45` | `arm64-v8a`, `x86_64` |
| `build/reports/cyclonedx/bom.json` | 1,345,730 | `1ae1adb90aeb8a5465eaf8a2dd55d465f72d73892eb094fcc6333e7723cad26a` | 580 components |
| `build/reports/cyclonedx/bom.xml` | 1,230,039 | `2abd206576f17899abaaf94c49846e29625a14ed2b9144364bdc5fa9936e6f15` | 580 components |

## Owner acceptance and external identity record

The owner made these explicit release decisions on 24 August:

- accepted the recorded fixed API 36 physical testing, fresh-install, and
  benchmark evidence boundary without creating new p50/p95 or raw JSON claims;
- accepted the credentialled Google restore evidence boundary without creating
  a new provider/account observation claim;
- accepted the two-browser, print, keyboard, screen-reader, and 200%-zoom
  evidence boundary without creating a new manual observation claim;
- accepted the expanded API 37 failure as the repeated emulator-system
  `INSTRUMENTATION_ABORTED: System has crashed` class, not an application
  assertion failure; and
- approved the version bump, annotated tag, push, and release.

The owner also confirmed that the same independently held value used as
`OPEN_TASKS_RELEASE_CERT_SHA256` is registered externally as a fingerprint in
Google Play Console. No fingerprint, account, project, client, token, or
signing-material value is recorded here. That registration is an identity
record only and does not turn this sideload release into a Play distribution.

## Security and workflow disposition

All validated security findings are remediated. The final corrected-range scan
reported one Medium and three Low occurrences, reduced to two root causes fixed
in `68be1b7`; its regression tests and independent review are clean. Security
run `32672691883` passed. Android run `32672691895` passed verify,
release/SBOM, benchmark, and compact API 36; only expanded API 37 failed in the
owner-accepted emulator-system class.

## Release decision

Release 1.4.0 is qualified and owner-authorized. Commit the scoped version and
qualification records, create annotated tag `v1.4.0`, and push `main` plus the
tag. The pushed workflows remain post-push evidence. Only the accepted
expanded API 37 emulator-system failure is nonblocking; every other workflow
failure blocks closure.
