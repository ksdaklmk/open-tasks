# Onboarding, executive dashboard, and NFR qualification

## Status and source identity

**Final corrected-range security review is complete, and its four findings are
fixed, verified, and independently approved in implementation commit
`68be1b713a665258ba014562b2944af197cd9b18`.** On 24 August the owner accepted
the recorded physical API 36 testing/benchmark boundary, credentialled Google
restore boundary, and browser/print/accessibility review boundary for this
release. Those are explicit release decisions, not invented p50/p95, raw
physical artifacts, or manual observations. The owner also accepted the
expanded API 37 emulator-system failure as infrastructure evidence rather than
an application failure and approved the version bump, tag, and release.

Follow-up note: final diff scan `48749dd0-0e8f-4ce7-849d-7eb96fd5527d`
sealed with complete coverage over
`4b3928ff46e1d0cfb0ce72684f4276488bd97b7e..0846c1a913cd2ba7db86807161e33b6331320127`
and reported one Medium plus three Low findings. The new regressions failed
before the fixes. Afterward, focused security tests, the complete app JVM
suite, a fresh 563-task host gate, and a fresh 442-task release assembly all
passed. Independent review returned clean after strengthening the
transaction-time title-privacy regression.

- Qualification checkpoint date: 24 August 2026
- Programme base: `c28a76ecd89468cc4303dd7c7040fe51257ff1c2`
- Final security-remediation implementation head:
  `68be1b713a665258ba014562b2944af197cd9b18`
- Documentation checkpoint: follows `68be1b7` without changing product source
- Task 12 state: implementation/security work complete; remaining external
  boundaries accepted by the owner for release
- Release identity: 1.4.0 / versionCode 6; the rebuilt APKs passed the real
  independent-owner signer gate and are qualified for the approved tag/push

The implementation sequence is:

| Commit | Delivery |
|---|---|
| `fea5663` | Empty structural production vault seed |
| `38515dc` | Offline-first Welcome and explicit recovery actions |
| `2615ac0` | Lock authority for reminders and widget concealment |
| `2af5802` | Archive and CSV trust-boundary bounds |
| `58413d4` | Active-timer tick confinement |
| `0dea6b3` | Latest-wins search and Insights projections |
| `ca890c9` | Bounded self-contained executive HTML writer |
| `6c4fbd4` | Insights download/share and plaintext disclosure |
| `63ecd99` | Per-ABI release packaging and size gates |
| `5f88a83` | Release-like Macrobenchmark and threshold gates |
| `4b3928f` | Security policy, dependency verification, SBOM, and security workflows |
| `78ea33d`..`e4f9bfc` | Original five-finding remediation wave, including the three-APK signer harness |
| `ad6ebc1`, `49671a4`, `3b52404` | Closing-scan authorization, biometric, wrapper-cache, and passive-concealment fixes |
| `b28c7d2`, `0b44d0d`, `d670404` | Main integration plus reviewed hosted-build dependency metadata |
| `ff98bd7` | Backup completion bound to the exact generation captured at runner start |
| `6cd690e`, `afb1d93` | Failed `--no-parallel` serialization experiment, then exact rollback after hosted evidence disproved the claim |
| `316bd86` | Settled-database setup removes the API 36 timer-test observation race without changing production |
| `c649801` | Release gate matches every APK label to its exact packaged native-code ABI set |

The untracked approved plan and design remain execution inputs outside Task
12's exact staging list. The pre-existing modified Stage 3 Drive plan, deleted
Thai-dashboard design, `.kotlin/`, and `artifacts/` were not changed.

## Delivered boundary

- A missing vault renders Welcome. Google Drive and portable recovery work
  begin only after the matching explicit action; offline use is a complete
  path.
- Production vault creation writes only the vault/member/primary-workspace
  structure and default Inbox workflow statuses. Demonstration records remain
  test fixtures only.
- Google remains an optional `drive.appdata` backup/recovery capability and
  never becomes identity or local-record authority.
- Insights freezes one snapshot, selection, instant, zone, and detail choice
  and emits one bounded plaintext HTML document. Aggregate is the default;
  task titles are opt-in; descriptions, notes, attachment names, provider
  metadata, and credentials are excluded.
- Download uses SAF and share uses the existing read-only FileProvider
  boundary. Partial plaintext is removed on non-success paths.
- Reminder mutation and widget actions carry revocable authorization into the
  shared repository write boundary and recheck it under the existing write
  mutex immediately before mutation. Biometric success is bound to the
  current foreground prompt generation. Reminder/widget content is concealed
  immediately on background, and the owner-reported process-death device gate
  passed.
- Archive and CSV intake are bounded; timer updates do not rebuild the whole
  workspace each second; search and Insights cancel stale projection work.
- Release packaging, benchmark checks, dependency checksums, a pinned and
  versioned Gradle wrapper cache, SBOM generation, CodeQL, dependency review,
  and the public security policy are explicit release controls. The signer
  gate authenticates all three APKs against owner-controlled input and checks
  arm64-only, x86_64-only, and universal-both packaged ABI sets. The real
  owner-only proof passed for the exact current-head files below; any rebuilt
  final release candidate must repeat it.
- Every dashboard share receives a unique FileProvider staging path while SAF
  downloads retain their stable display name.
- Remote backup completion carries the generation captured under the
  serialized publication gate; an edit arriving during the run remains
  eligible for the next backup.

## Dead-code proof and focused gate

The plan's literal `rg` command also sees its own documentation references, so
its stated “no result” expectation is stale. The equivalent source-only proof
returned no result (exit 1):

```bash
rg -n 'HybridLogicalClock|MergeDecision|mergeTask' \
  --glob '*.kt' \
  --glob '!core/sync/src/main/kotlin/app/opentasks/core/sync/HybridLogicalClock.kt' \
  --glob '!core/sync/src/main/kotlin/app/opentasks/core/sync/MergeRules.kt' \
  --glob '!core/sync/src/test/kotlin/app/opentasks/core/sync/HybridLogicalClockTest.kt' \
  --glob '!core/sync/src/test/kotlin/app/opentasks/core/sync/MergeRulesTest.kt' \
  app core feature benchmark
```

Only then were `HybridLogicalClock.kt`, `MergeRules.kt`, and their two tests
deleted. `InMemoryVaultRepository`, SQLCipher, Tink, Bouncy Castle, crypto
parameters, and live cloud codecs were retained. The focused command
`./gradlew :core:sync:testDebugUnitTest --stacktrace` passed **41 tests**, zero
failures.

## Host, release, size, and supply-chain evidence

| Gate | Result |
|---|---|
| `./gradlew testDebugUnitTest lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --max-workers=2` at the `c649801` working state | PASS in 1m43s; 563/563 tasks executed; unit tests, lint, debug assembly, and Android-test compilation green |
| `./gradlew :app:assembleRelease --stacktrace --rerun-tasks` at the `c649801` working state | PASS in 1m12s; 442/442 tasks executed |
| `scripts/verify-release-apk-script.sh` | PASS for signer, no-leak, and exact-ABI harness cases after a renamed x86-as-arm64 case first failed against the old verifier. This is not the real signer gate and does not authenticate a release candidate without independent owner input. |
| Real `scripts/verify-release-apk.sh` with independently provisioned owner certificate input | PASS on 24 August for all three exact current-head artifacts below; signer identity, signatures, version 1.3.1/5, non-debuggable manifest, release-only activity set, sole `drive.appdata` scope, filenames, and exact ABI sets accepted; no certificate value recorded |
| `scripts/check-release-size.sh ...app-arm64-v8a-release.apk ...app-universal-release.apk` | PASS; both artifacts equal the reviewed baseline and remain below hard caps |
| `scripts/verify-actions-workflow.sh` | PASS; full SHA pins, minimal permissions, Java/Kotlin manual CodeQL build, High dependency-review threshold, seven connected modules, and no credential output enforced |
| Hosted overlap evidence, workflow verifier, whitespace checks, and independent correction review | PASS for removing the ineffective flag in `afb1d93`; this is rollback, not serialization proof |
| `./gradlew cyclonedxBom` | PASS; JSON/XML parse and contain 580 components, including application modules, Room, SQLCipher, Tink, and Bouncy Castle |
| `./gradlew clean :app:assembleDebug` | PASS as the exact local manual CodeQL build |

Current-head owner-authenticated artifacts built from implementation head
`68be1b7` (documentation-only commits do not change their product source):

| Artifact | Bytes | SHA-256 | Native code | Result |
|---|---:|---|---|---|
| `app/build/outputs/apk/release/app-arm64-v8a-release.apk` | 9,852,846 | `bb812a54f646fe322aaf408f4b96746d2baa8acd0398466d44094d5f9a11f3bb` | `arm64-v8a` | PASS |
| `app/build/outputs/apk/release/app-x86_64-release.apk` | 9,984,216 | `3e92e10625e5a9ac70f01641b72afc06e738c04537436996f66ffdf55648cc7b` | `x86_64` | PASS |
| `app/build/outputs/apk/release/app-universal-release.apk` | 12,114,299 | `77bd183f9b7542ee375b8c18c764f92aa21d137bacbbdfc20488d4658b938765` | `arm64-v8a`, `x86_64` | PASS |

These files still carry versionName 1.3.1 / versionCode 5. Because `v1.3.1`
already identifies the earlier released source at `e4d25a9`, they are evidence
for the current implementation but not a new releasable version. A next
release must bump its version, rebuild, and repeat the owner signer gate.

Historical pre-closing-remediation release artifacts (not current-head signer
evidence):

| Artifact | Bytes | SHA-256 | Result |
|---|---:|---|---|
| `app/build/outputs/apk/release/app-arm64-v8a-release.apk` | 9,852,810 | `b5f3c5e3b63d6f370fa03526d28fec44845d0578ccaed05694482291c5653a76` | PASS, ≤10 MiB |
| `app/build/outputs/apk/release/app-universal-release.apk` | 12,114,263 | `585991ce7e796117ff21bf7aebbe1fcb04e2c7a63d09e79b01ab6e162a7d30e4` | PASS, ≤15 MiB |
| `build/reports/cyclonedx/bom.json` | 1,345,730 | `1ae1adb90aeb8a5465eaf8a2dd55d465f72d73892eb094fcc6333e7723cad26a` | PASS |
| `build/reports/cyclonedx/bom.xml` | 1,230,039 | `2abd206576f17899abaaf94c49846e29625a14ed2b9144364bdc5fa9936e6f15` | PASS |

Dependency verification was generated while resolving the complete
test/lint/release/SBOM graph:

```bash
./gradlew --write-verification-metadata sha256 \
  testDebugUnitTest lintDebug :app:assembleRelease cyclonedxBom --stacktrace
```

The 7,228-line allow-list was reviewed. A deliberately wrong CycloneDX JAR
checksum made
`./gradlew help --no-configuration-cache --refresh-dependencies` fail closed.
The intended file was restored byte-for-byte, cold-cache metadata checksums
were generated and reviewed, and the strict refresh then passed. No dynamic
version, changing module, non-checksum exception, signing material, credential,
or local secret/configuration path was accepted into the SBOM review.

The CodeQL and dependency-review actions remain pinned to reviewed full
commits. Security runs through `32618409567` and current documentation-head
run `32672691883` completed with CodeQL green; dependency review was correctly
skipped for each direct-push event. Android runs `32617307931`, `32617911318`,
`32618409559`, and current documentation-head run `32672691895` settled with
verify, release/SBOM, benchmark, and compact API 36 green. Each overall
workflow failure was confined to the expanded API 37 emulator-system lane; no
replacement workflow was dispatched. The latest uploaded report again records
`INSTRUMENTATION_ABORTED: System has crashed`, not a new application assertion
failure.

Expanded API 37.0 failed in replacement run `32615516358` before any
application test assertion and ran zero tests. Its exact Gradle command
included `--no-parallel`, yet at least four connected-test tasks began within
seven seconds. The canary then lost Android's `activity` and `package`
services, and UTP reported `INSTRUMENTATION_ABORTED: System has crashed`.
This falsifies the serialization claim. `afb1d93` removes the ineffective flag;
no serialization proof or second speculative workaround is claimed.

## Connected-device evidence

The only running target was audited before mutation:

- AVD: `Pixel6_Scratch`
- Reported product/model: `sdk_gphone64_arm64`
- API: 37
- ABI: `arm64-v8a`
- Purpose: disposable fallback only; it is not the required API 36 profile
- Protected Fold AVD and physical owner device: never booted, installed to,
  cleared, or tested

All seven modules ran in one command:

```bash
./gradlew \
  :core:data:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest \
  :feature:home:connectedDebugAndroidTest \
  --stacktrace --continue
```

Result: **BUILD SUCCESSFUL in 22m11s; 510 tests, 508 passed, two skipped,
zero failures/errors.**

| Module | Tests | Passed | Skipped | Failed/errors |
|---|---:|---:|---:|---:|
| `:app` | 92 | 90 | 2 | 0 |
| `:core:data` | 218 | 218 | 0 | 0 |
| `:feature:more` | 80 | 80 | 0 | 0 |
| `:feature:projects` | 37 | 37 | 0 | 0 |
| `:feature:home` | 8 | 8 | 0 | 0 |
| `:feature:tasks` | 52 | 52 | 0 | 0 |
| `:feature:schedule` | 23 | 23 | 0 | 0 |

The skips were the credentialled Drive qualification argument and the
posture-dependent fold-transition case. Exact XML lives under each module's
`build/outputs/androidTest-results/connected/debug/` directory.

No disposable API 36 image was installed on the local host. The pushed compact
API 36 emulator lane subsequently ran the seven-module matrix successfully in
workflow `32608967477`. That closes hosted connected coverage, but it does not
create fixed API 36 arm64 physical performance or fresh-install evidence. On
24 August the owner explicitly accepted that recorded evidence boundary for
this release.

The compact API 36 job in replacement workflow `32615516358` ran all seven
modules and failed only
`RoomVaultRepositoryInstrumentedTest.activeTimerDoesNotReemitWorkspaceWithoutADatabaseWrite`.
The old test subscribed after advancing its fake clock, so a delayed Room
invalidation from the preceding timer write could be mistaken for a clock-only
tick. `316bd86` establishes the active fixture, reopens the settled database
without another write, and retains the bounded no-emission assertion. A fresh
focused API 37 arm64 run passed 1/1 and independent review approved the
test-only correction; hosted compact API 36 confirmation is recorded with the
replacement workflow results above.

The owner later reported **PASS** for the remediation scan's deferred
real-device process-death sequence: previously published reminder/widget
content was checked after backgrounding and process termination. This external
acceptance closes that evidence request without changing the sealed scan's
canonical `partial` coverage record and without exposing certificate data.

## Signed fallback smoke and generated HTML

The signed arm64 release APK was installed fresh on `Pixel6_Scratch` with
airplane mode confirmed as enabled before cold launch. The visible/UI-tree
evidence showed:

- `Welcome to Open Tasks`;
- `Continue with Google`, its immediate optional encrypted-backup/recovery
  disclosure, `Continue offline`, and `Restore from this device`;
- no automatic restore candidate list and no removed promotional bubbles;
- after `Continue offline`, Home displayed no planned work, no project cards,
  `0 completed`, and `0 min recorded`; and
- Insights reported no projects or tags and exposed aggregate dashboard
  generation with task details off by default and the plaintext warning.

Automated Room tests prove the stronger data assertion: the new production
vault contains structural workspace/default workflow records and zero user
projects, tasks, tags, notes, attachments, time entries, or activity records.
Airplane mode proves the flow works without connectivity, but it is not packet
capture evidence of zero attempted calls. The owner accepted that recorded
physical-evidence boundary for this release on 24 August.

The release UI downloaded and staged sharing for one aggregate file without
selecting a recipient. The generated file was then inspected statically:

| Artifact | Bytes | SHA-256 | Evidence |
|---|---:|---|---|
| `/tmp/open_tasks_executive_2026-08-22.html` | 23,412 | `9df4961884f87dcb75d83f5e6797505f6250771044e0ef07f6757c3b6e964793` | One doctype; required semantic sections; CSP with `default-src`, `connect-src`, `img-src`, `object-src`, `base-uri`, and `form-action` set to `none`; hashed inline style/script only; print and reduced-motion CSS; no HTTP(S), external script/link, or CSS URL reference |

The Android share sheet truthfully showed one HTML file. No recipient was
selected and nothing was transmitted. The generated Downloads copy and the
release app were removed from the disposable emulator afterward; airplane
mode was restored to off. `/tmp` is non-durable local evidence and is not a
release attachment.

The in-app browser rejected the local `file:` URL under its browser security
policy. No browser rendering result is claimed, and no alternate route was
used to bypass that control. Automated hostile-content/DOM/CSP/offline/print/
keyboard/reduced-motion tests passed inside the 1,402-test host gate. The owner
accepted the remaining two-current-browser, print-preview, keyboard-only,
screen-reader, and 200% zoom evidence boundary for this release on 24 August.

## Qualification matrix

| Requirement | Evidence | Status |
|---|---|---|
| Welcome and explicit provider boundaries | Unit/Compose/connected tests plus signed API 37 airplane-mode smoke | ACCEPTED BY OWNER — no additional physical API 36 packet-audit claim |
| Zero-user-record offline vault | Unit/Room connected proof plus signed fallback empty UI | ACCEPTED BY OWNER — no additional physical-profile claim |
| Optional Google backup/recovery | No-auto-discovery tests and `drive.appdata` boundary checks | ACCEPTED BY OWNER — credentialled restore boundary accepted; no new provider observation claimed |
| Portable recovery non-overwrite/corruption/cancel | Unit and Room connected recovery suites | ACCEPTED BY OWNER — no new manual signed-workflow claim |
| Locked reminder/widget privacy | Final-scan regressions, live transaction-bound authorization, earlier process-death device PASS, fresh host/release gates, and clean independent patch review | PASS — fixed in `68be1b7` |
| Aggregate/detail offline HTML safety | Writer/DOM/hostile-content/limit tests; real aggregate download/share and static scan | ACCEPTED BY OWNER — no new browser/print/accessibility observation claimed |
| Archive/CSV bounds and durability | Unit and maximum-bound Room connected tests | PASS locally |
| Timer/search/Insights hot paths | Unit tests, trace boundaries, latest-wins implementation, functional benchmark smoke | ACCEPTED BY OWNER — no physical p50/p95 or raw JSON claimed |
| APK size | Exact 1.4.0/6 arm64/universal bytes and hashes in `release-1.4.0-sideload.md` | PASS for the release candidate |
| Dependency verification and SBOM | Pinned Gradle ZIP, versioned wrapper-cache namespace, strict dependency verification, and exact 580-component JSON/XML artifacts in `release-1.4.0-sideload.md` | PASS for the release candidate; any other post-push workflow failure blocks closure |
| Release signer identity and ABI | Fail-closed verifier received independent owner input for both the authenticated 1.3.1 evidence set above and the rebuilt 1.4.0/6 set in `release-1.4.0-sideload.md` | PASS — all three exact 1.4.0 artifacts, signatures, version, manifest, scope, filenames, and ABI sets accepted without recording the certificate value |
| CodeQL/dependency review | Workflow policy verifier plus pushed Security runs `32608967519` and `32615516366` | CodeQL PASS; dependency review correctly skipped on direct pushes and remains a PR-event gate |
| Seven connected modules | Local API 37 fallback plus pushed compact API 36 lanes | ACCEPTED BY OWNER — compact API 36 passed; expanded API 37 is accepted as an emulator-system crash, not an app assertion failure |

The 14-case R8-backed Macrobenchmark suite has a one-iteration emulator
functional mode. Task 12 reran all **14/14** cases successfully in **4m01s**
with zero skips or failures. JUnit evidence is at
`benchmark/build/outputs/androidTest-results/connected/benchmark/TEST-Pixel6_Scratch(AVD) - 17-_benchmark-.xml`
and traces/messages are under
`benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/Pixel6_Scratch(AVD) - 17/`.
Dry-run mode intentionally emits no accepted benchmark-data JSON, and an
emulator result never supplies the NFR percentiles. There are therefore **no
release p50/p95 values and no accepted physical raw JSON** in this record; the
owner explicitly accepted that evidence boundary for this release.

## Security review closure

Standard scan `df9a41d7-2458-4943-9c5d-957e98d484e9` reported zero Critical,
zero High, three Medium, and two Low findings. All five implementation defects
are remediated. The signer verifier is implemented, and its real owner-input
proof passed for the exact current-head artifacts recorded above. A changed or
rebuilt final candidate must repeat that external gate. The original scan's
local `artifacts/fix_report.md` now exists, maps all five occurrences to their
fixes and evidence, and is reconciled through the exact follow-up scan below.

Closing diff scan `31e83519-4242-4240-9b61-7cb357b440e8` completed over
`8bb2a6675fbf26f9b265306823e86a759bb6dedf..e4f9bfcae4ce6f9f8341229b68dffebaff991b85`
with complete coverage and five reportable findings: one Medium and four Low.
They were fixed in `ad6ebc1`, `49671a4`, and `3b52404` without collapsing the
reminder and widget instances.

Post-fix scan `19aa7c94-d7a0-4b6d-9cef-0e6c347e0ce5` reviewed
`e4f9bfcae4ce6f9f8341229b68dffebaff991b85..3b524043cd8a78b99fee266f9f4187fcef38c72d`
and sealed with **zero reportable findings**. Its canonical coverage is
partial only for the real-device asynchronous purge proof; the owner later
reported that exact device sequence **PASS**. Exact follow-up scan
`2cb2540b-e277-43c5-a12f-564f386de9c8` reviewed
`d670404062c958cfa0d2161d8d0c03139a4da7b7..ff98bd73851af73e89a46beebe04e0ff87e9394e`
with complete coverage and **zero findings**.

Final corrected-range scan `48749dd0-0e8f-4ce7-849d-7eb96fd5527d` reviewed
`4b3928ff46e1d0cfb0ce72684f4276488bd97b7e..0846c1a913cd2ba7db86807161e33b6331320127`
with complete coverage and reported four findings: one Medium and three Low.
They reduced to two root causes and are fixed in four-file implementation
commit `68be1b7`. The regressions failed before the fixes; focused tests, the
complete app JVM suite, the fresh 563-task host gate, and the fresh 442-task
release assembly passed afterward. Independent review returned clean after
the transaction-time title-privacy regression was strengthened. Both the
final scan-local fix report and the original Standard scan's final receipt are
written.

No known product remediation or deliberately failing test remains at the
reviewed implementation commit. The bounded CI experiment at `6cd690e` was
disproved by replacement evidence and removed at `afb1d93`; this is rollback,
not serialization proof. The separate signer-gate ABI-label gap is closed at
`c649801`. No second speculative workflow workaround is claimed.

## Review and release decision

The earlier five-candidate validation, attack-path analysis, remediation,
post-fix review, exact backup follow-up review, local gates, owner-reported
device check, and push are complete. The final corrected-range scan and its
independent remediation review are also complete; the resulting four-file fix
landed in `68be1b7`.

Release decision: **QUALIFIED AND OWNER APPROVED FOR 1.4.0 TAG/PUSH/RELEASE.**
The owner accepted the external physical/provider/browser evidence boundaries
and the API 37 emulator-system classification on 24 August. The rebuilt
1.4.0/6 three-APK set passed the real independent-owner signer gate; no
certificate value or signing material was recorded.
