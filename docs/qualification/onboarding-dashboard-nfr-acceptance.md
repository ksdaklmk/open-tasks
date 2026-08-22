# Onboarding, executive dashboard, and NFR qualification

## Status and source identity

**Programme implementation reached local qualification, then paused with five
validated security remediations open; release qualification is incomplete.**
Every gate that ran safely on this host is recorded below.
The fixed API 36 physical-device benchmark, API 36 connected matrix,
owner-present credentialled Google restore, two-browser/print/screen-reader
HTML review, and pushed GitHub security jobs have not run. They remain release
blockers and are not inferred from emulator or unit-test evidence.

- Qualification date: 22 August 2026
- Programme base: `c28a76ecd89468cc4303dd7c7040fe51257ff1c2`
- Last committed implementation head before this record:
  `4b3928ff46e1d0cfb0ce72684f4276488bd97b7e`
- Task 12 evidence source: the working tree based on that head, containing
  only the four proven-dead `core/sync` deletions and this durable-document
  security checkpoint; Task 12 is not closed
- Release identity: unchanged from 1.3.1; no version bump, tag, push, or
  release was attempted

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
- Reminder mutation and widget actions consult app-lock state, and the live
  process expires that state in the background. The sealed review found that
  the cached decision can be stale at an external action boundary and that
  process death can prevent durable concealment; both remain open below.
- Archive and CSV intake are bounded; timer updates do not rebuild the whole
  workspace each second; search and Insights cancel stale projection work.
- Release packaging, benchmark checks, dependency checksums, SBOM generation,
  CodeQL, dependency review, and the public security policy are now explicit
  release controls.

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
| `./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace` | PASS in 1m19s; 1,402 JVM tests across 135 suites, zero failures; lint and debug assembly green |
| `./gradlew :app:assembleRelease --stacktrace` | PASS in 1m47s |
| `scripts/verify-release-apk.sh app/build/outputs/apk/release/app-universal-release.apk` | PASS for signature self-consistency, version, non-debuggable state, sole `drive.appdata` scope, and absence of the debug Drive qualification activity. It did **not** authenticate an expected signer or inspect the two split APKs; finding 4 remains open. |
| `scripts/check-release-size.sh ...app-arm64-v8a-release.apk ...app-universal-release.apk` | PASS; both artifacts equal the reviewed baseline and remain below hard caps |
| `scripts/verify-actions-workflow.sh` | PASS; full SHA pins, minimal permissions, Java/Kotlin manual CodeQL build, High dependency-review threshold, seven connected modules, and no credential output enforced |
| `./gradlew cyclonedxBom` | PASS; JSON/XML parse and contain 580 components, including application modules, Room, SQLCipher, Tink, and Bouncy Castle |
| `./gradlew clean :app:assembleDebug` | PASS as the exact local manual CodeQL build |

Release artifacts:

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

The current CodeQL and dependency-review actions are pinned to reviewed full
commits. Their actual GitHub jobs and release SBOM upload cannot exist until a
push; local workflow validation and a manual build are not substitutes for
those remote results.

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

No disposable API 36 image is installed. Every locally installed AVD is API
37 and the SDK contains only the API 37 arm64 image, so the required API 36
seven-module gate is **PENDING**, not failed and not waived.

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
capture evidence of zero attempted calls; the physical fresh-install network
audit remains pending.

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
keyboard/reduced-motion tests passed inside the 1,402-test host gate, but the
two-current-browser, print-preview, keyboard-only, screen-reader, and 200%
zoom release acceptance remains **PENDING**.

## Qualification matrix

| Requirement | Evidence | Status |
|---|---|---|
| Welcome and explicit provider boundaries | Unit/Compose/connected tests plus signed API 37 airplane-mode smoke | PARTIAL — required physical API 36 fresh-install and packet audit pending |
| Zero-user-record offline vault | Unit/Room connected proof plus signed fallback empty UI | PASS locally; physical profile confirmation pending |
| Optional Google backup/recovery | No-auto-discovery tests and `drive.appdata` boundary checks | PARTIAL — owner-present credentialled discovery/cancel/verified restore unrun |
| Portable recovery non-overwrite/corruption/cancel | Unit and Room connected recovery suites | PARTIAL — manual signed workflow unrun |
| Locked reminder/widget privacy | Unit and connected authority/concealment/cancellation suites | BLOCKED — stale-authority and process-death findings open; physical exercise also pending |
| Aggregate/detail offline HTML safety | Writer/DOM/hostile-content/limit tests; real aggregate download/share and static scan | PARTIAL — detail file plus two-browser/print/accessibility exercise unrun |
| Archive/CSV bounds and durability | Unit and maximum-bound Room connected tests | PASS locally |
| Timer/search/Insights hot paths | Unit tests, trace boundaries, latest-wins implementation, functional benchmark smoke | PARTIAL — physical threshold measurements unrun |
| APK size | Exact arm64/universal bytes and hashes above | PASS |
| Dependency verification and SBOM | Fail-closed dependency checksum test, strict refresh, 580-component JSON/XML review | BLOCKED — Gradle bootstrap ZIP is not pinned; release upload also pending |
| Release signer identity | Universal APK self-consistency check only | BLOCKED — trusted owner fingerprint and all three APK checks absent |
| CodeQL/dependency review | Workflow policy verifier and exact manual build | PENDING actual pushed GitHub jobs |
| Seven connected modules | 510-test API 37 fallback | PENDING required API 36 run |

The 14-case R8-backed Macrobenchmark suite has a one-iteration emulator
functional mode. Task 12 reran all **14/14** cases successfully in **4m01s**
with zero skips or failures. JUnit evidence is at
`benchmark/build/outputs/androidTest-results/connected/benchmark/TEST-Pixel6_Scratch(AVD) - 17-_benchmark-.xml`
and traces/messages are under
`benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/Pixel6_Scratch(AVD) - 17/`.
Dry-run mode intentionally emits no accepted benchmark-data JSON, and an
emulator result never supplies the NFR percentiles. There are therefore **no
release p50/p95 values and no accepted physical raw JSON** in this record.

## Sealed security review and safe pause

Standard scan `df9a41d7-2458-4943-9c5d-957e98d484e9` reviewed the programme
working tree rooted at `4b3928ff46e1d0cfb0ce72684f4276488bd97b7e` and
sealed successfully. Coverage was partial whole-repository coverage (11/12
surfaces, 579 workbench files) with the programme's high-risk boundaries
covered. It reported **zero Critical, zero High, three Medium, and two Low**
findings. All five are retained as release blockers:

| Severity | Finding | Required remediation |
|---|---|---|
| Medium | Gradle wrapper distribution is downloaded without a checksum | Add the independently reviewed official SHA-256 for `gradle-9.7.0-bin.zip`; require exactly one 64-hex `distributionSha256Sum` in the workflow verifier. |
| Medium | Reminder and widget mutations can use stale unlocked authority | Recompute elapsed lock authority synchronously at the shared controller boundary and use it immediately before both mutation paths. |
| Medium | External lock content can survive process death | Arm a durable private expiry callback that rechecks authority, clears widget titles, and cancels active reminder notifications; cancel it on timely foreground. |
| Low | Release verification trusts any signer and checks one APK | Require an owner-controlled certificate SHA-256 and verify arm64, x86_64, and universal outputs. |
| Low | Same-day dashboard shares reuse a granted FileProvider URI | Give every share operation a unique staging path while retaining the stable SAF download name. |

No remediation or deliberately failing test is left in this checkpoint. The
canonical local scan directory is
`/private/var/folders/cc/zvtsfhf91m747w_86jlft22r0000gn/T/codex-security-scans-Jj7CWg/open-tasks/4b3928ff46e1d0cfb0ce72684f4276488bd97b7e_20260822T072609Z__1usm149/`.
On resume, the fix workflow must create its required
`artifacts/fix_report.md` there after focused and regression verification.

## Review and release decision

The final diff review began with the sealed security scan above. Task 12 is
paused before its remediation, follow-up review, and closing diff check.

Release decision: **STOP / NOT YET ELIGIBLE.** Before any version bump, tag,
push, or release, complete every pending physical/API 36, Google, browser/
accessibility, benchmark, and remote-security gate above, attach their exact
artifacts, resolve all five validated findings (plus any later Critical/High
finding), and obtain the owner's explicit release decision under
`RELEASING.md`.
