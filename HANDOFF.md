# Open Tasks Handoff

## Current state — Google Play Task 8 paused at DNS domain gate — 29 August 2026

This is the authoritative resume point for the Google Play submission. It
supersedes the older Task 6 Play checkpoint below wherever the two conflict.
The owner paused execution after the Task 8 domain-verification blocker was
isolated. Do not resume Console, DNS, build, release, or Task 9 work from the
older sections.

The retained workspace is
`/Users/kk/projects/open-tasks/.worktrees/google-play-submission` on branch
`google-play-submission`. Task 7 is complete at `38f7ac5`: the Play app exists
for `app.opentasks`, the authenticated existing release certificate is the Play
delivery identity, the separate upload certificate is registered only for
uploads, its private material is backed up and mode `0600`, and no version code
or release was consumed. Countries/regions remains deferred to the first
production-availability step.

Task 8 has the following completed evidence:

- production and debug source request only
  `https://www.googleapis.com/auth/drive.appdata` through Google Play Services;
  no client secret or server client ID is embedded;
- the focused linked-worktree baseline passed `testDebugUnitTest`, `lintDebug`,
  and `:app:assembleDebug` with 553 actionable tasks;
- Google Auth Platform is `External` and `In production`, with app name
  `Open Tasks`, the existing private support/developer contacts retained, and
  the current public privacy and support URLs saved;
- all 17 source-proven stale scopes were removed, leaving exactly the one
  non-sensitive `drive.appdata` scope; data-access verification is not
  required;
- two pre-existing Android OAuth clients for `app.opentasks` remain: the Play
  delivery client matches the release certificate and the older direct/debug
  client is unchanged; the upload certificate is not an OAuth delivery
  identity; and
- no OAuth client ID, token, secret, account address, or private contact value
  is stored in repository evidence.

Brand verification remains incomplete. Google reports that the homepage is not
registered to the project owner, so the configured app name is not yet shown on
the consent screen. Under explicit owner approval, public verification file
`site/googlebfb12df764b54328.html` was committed to `main` as `22fa396`, pushed,
and deployed successfully by Pages run `33248701409`. Anonymous HTTPS retrieval
returned its exact one-line token. Search Console verified
`https://ksdaklmk.github.io/open-tasks/` by that HTML file and auto-verified the
exact `https://ksdaklmk.github.io/open-tasks/support/` child property.

Two OAuth branding reverification attempts still returned the same homepage
ownership issue. Root-cause review found that Google's current OAuth guidance
requires a Search Console **Domain property** verified at DNS level by a Cloud
project owner/editor; it explicitly says a URL-prefix property is insufficient.
The shared `github.io` DNS zone is controlled by GitHub, so the current Pages
subdomain cannot satisfy that proof. Do not submit another automated review,
select “issue is incorrect”, broaden the Drive scope, add a logo, or remove the
public verification file as a workaround.

Task 8 is therefore **PAUSED**, not complete. Its remaining gates are:

1. The owner must supply the exact custom domain or subdomain and confirm DNS
   control plus approval to configure it for GitHub Pages and Google.
2. Amend and approve Task 8 before execution because the domain migration also
   changes the hard-coded privacy URL in
   `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`, the two links in
   `site/privacy/index.html` and `site/support/index.html`, listing/qualification
   evidence, and the OAuth homepage/privacy/authorised-domain values.
3. Configure the Pages custom domain and DNS, keep the current public URLs live
   during transition, wait for HTTPS, and verify the new privacy/support pages
   anonymously before changing OAuth.
4. Using the same Cloud project owner/editor account, verify the custom domain
   as a Search Console Domain property with Google's DNS record.
5. Update OAuth branding to the verified custom-domain URLs while preserving
   `External`, `In production`, `Open Tasks`, existing private contacts, the two
   Android clients, and `drive.appdata` only; then request branding verification
   once and require a definitive pass.
6. Confirm an ordinary Google account outside the former Testing allowlist can
   reach the consent flow. Do not record that account.
7. Update the Task 8 listing and append-only qualification evidence, run the
   prescribed sensitive-value/diff checks, and commit
   `docs: record production OAuth setup` only after every gate above passes.

Task 9 has not started. No immutable candidate, AAB, APK set, mapping, symbols,
SBOM, Play upload, track, tester, review submission, production-access request,
tag, or publication was created. Do not start Task 9 until Task 8 closes.

## Current state — Task 6 complete — 29 August 2026

This is the authoritative current section for the Google Play submission.
Older checkpoints remain historical evidence and are superseded where they
conflict with this record.

The reviewed branch was integrated under explicit owner approval. Remote
`origin/main` had a squash commit whose tree exactly matched local pre-Play
commit `edc157a`; content-empty merge `f0c85cc` records that remote parent
without replacing the reviewed tree. Local `main` was fast-forwarded and
pushed, then deployment evidence was appended in `6397448`. The retained
`google-play-submission` worktree remains available for later programme work.

Tasks 1–5 remain complete and cleanly reviewed. Task 4 connected
instrumentation and 200% font checks remain PENDING because no disposable
device was approved. Task 5 source-level declarations remain accepted, while
final AAB, runtime, current Console, owner, deployment, and publication
evidence remain PENDING.

Task 6 repository work is complete. The owner supplied the exact verified
public Play developer display name `Kritsada K.`; only that public value is
in the privacy page. No reference screenshot or other account detail was
retained, cited, or committed. Commit `37b8b75` added the two self-contained
static pages, the exact pinned Pages workflow, and its repository policy
checks. It publishes only `site/` and introduces no JavaScript, form, cookie,
analytics, third-party font, remote stylesheet, build framework, or
dependency.

The first independent review found three Important issues: an inaccurate
Internet-permission phrase, sub-AA ember text contrast, and incomplete Pages
policy enforcement. Fix `6ac0fe6` corrected all three. Its scoped re-review
was clean, but controller adversarial verification then found one remaining
permission-binding bypass. Fix `0eccd1d` binds the exact three required
entries to the actual top-level `permissions` block. The final scoped review
reported specification PASS, quality PASS, and zero Critical, Important, or
Minor findings.

Pre-integration controller checks passed shell syntax, the workflow policy,
the four exact action pins, exact two-file site inventory, forbidden
web-behaviour scan, required disclosures, public-name-only scan, corrected
Task 5 privacy facts, 6.89:1 accent-text contrast, semantic structure, exact
four-file Task 6 scope, `git diff --check`, and clean status. The exact
permissions-versus-`env` adversarial mutation now fails with exit 1, and the
approved workflow passes again after byte-exact restoration.

The post-integration full gate passed on local `main`: `testDebugUnitTest`,
`lintDebug`, and `:app:assembleDebug` reported `BUILD SUCCESSFUL in 27s` with
553 actionable tasks. GitHub Pages is enabled with the GitHub Actions source,
public access, and HTTPS enforcement. The first Pages attempt raced enablement
and failed at `configure-pages`; attempt 2 of Pages workflow run
`33090904000` deployed exact commit
`f0c85cc04b3c68dc6c60849aa450ca74f065e2b0` successfully at
`2026-08-27T16:01:54Z`.

Both fixed public URLs return HTTP 200 with `text/html; charset=utf-8`. Their
fetched bytes match the two committed HTML files exactly, the privacy page has
the exact public developer display name, the support route and warning match,
and the deployed forbidden-content scan is clean. Anonymous retrieval required
no authentication.

Normal and Incognito Chrome browser QA now pass both deployed URLs at a wide
1425×802 viewport and a narrow 360×800 viewport. Both pages rendered without
clipping, collision, or horizontal overflow. Privacy-page Tab order reached
Open Tasks support; Support-page order reached GitHub Issues, then Privacy
policy. Every focused link showed the expected solid 3 px ember outline with a
3 px offset, and Enter activated both internal page transitions. The Incognito
run loaded both URLs directly without an authentication prompt or redirect,
reported no browser warning or error, and exposed no external page resource.
The temporary viewport override was reset.

The deployed commit's Security workflow passed. The later docs-only push at
`e657815` exposed a repeatable CodeQL workflow defect: Gradle restored every
Kotlin compilation task from cache, so CodeQL finalisation found no compiled
source. No workflow fix was attempted. Android verification, release, and
benchmark jobs passed, but both instrumented matrix jobs failed: API 37 lost
the emulator package service, while API 36 reported app, core-data, and
migration instrumented failures and timeouts. Those failures were not
diagnosed or changed as part of this Pages deployment task.

No Play or Cloud Console, key, device, real AAB, tag, Play release, or Task 7
action occurred. Task 6 is complete. The next numbered plan item is Task 7,
owner verification, package registration, and Play signing. Do not start it or
another external action without its required owner input and explicit
execution-time approval.

## Current state — local-first launch and compact Home complete, 27 August 2026

This section is authoritative. Older checkpoints below remain historical
evidence and are superseded wherever they conflict with this current contract.
The release baseline below remains authoritative for release identity and
qualification evidence.

The implemented local-first launch is now the current product behavior:

- Home uses the UK-English date plus search, with no greeting;
- an idle ordinary `NoVault` first launch creates the normal empty encrypted
  local vault automatically and proceeds to Home;
- More > Backup & recovery exposes Restore existing workspace and opens the
  existing Google Drive/Android replacement shell;
- the Welcome UI, Welcome resources, and Welcome-only CSV handoff were deleted;
- first-run fully drawn now measures usable Home rather than the neutral
  initialization surface; and
- verified staging, explicit takeover, and nondestructive Back behavior remain
  in the existing recovery implementation.

Room remains v9. The authenticated backup, `.otvault`, and Android portable
package formats, SDK values, permissions, version, signing, publication, and
release state did not change. No dependency, migration, sample content,
provider scope, or application-network path was added.

Fresh focused non-device verification in this worktree reported:

```text
./gradlew :app:testDebugUnitTest :feature:home:compileDebugAndroidTestKotlin :feature:more:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :benchmark:assembleBenchmark --console=plain
bash scripts/verify-benchmark-threshold-script.sh
```

The Gradle command passed 499/499 app host tests with zero skips, failures, or
errors; compiled Home, More, and app Android-test Kotlin; and assembled the
benchmark APK. It reported `BUILD SUCCESSFUL in 27s`, 254 actionable tasks,
one executed and 253 up-to-date. The script reported
`verify-benchmark-threshold-script: all checks passed`.

Fresh repository-wide non-device verification reported:

```text
./scripts/check-schema-drift.sh
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --console=plain
git diff --check
rg -n "WelcomeScreen|welcome[-_]|confirmForWelcome|takeWelcomeRows|abandonWelcomeHandoff|returnToWelcome|openTasksAfterMigrationSignal|Good afternoon" app/src feature/home/src feature/more/src benchmark/src scripts
rg -n "fresh installation starts at Welcome|Welcome now offers|### Welcome|Welcome places" README.md PRODUCT.md DESIGN.md
```

The schema script found no drift and reported `BUILD SUCCESSFUL in 3s` with
38/38 tasks executed. The repository Gradle command passed 1,462/1,462 host
tests across 138 suites with zero skips, failures, or errors; lint and debug
assembly passed; it reported `BUILD SUCCESSFUL in 46s`, 553 actionable tasks,
254 executed, 60 from cache, and 239 up-to-date. `git diff --check` passed with
no output. The living-document scan passed with no matches. The source scan had
one test-only match at
  `feature/home/src/androidTest/kotlin/app/opentasks/feature/home/HomeScreenInstrumentedTest.kt:110`,
  where a negative assertion proves `Good afternoon` is absent. Production
  code has no match, but the exact prescribed no-match scan is not promoted to
  PASS.

No disposable device or owner-approved physical target was authorized.
Connected Home/More Compose, app process-restoration/fold, and benchmark
execution are explicitly **UNEXECUTED**, not PASS. The Android-test source
sets and benchmark APK compiled only; no physical launch or performance result
is claimed.

The unrelated original-checkout entries named in the plan's Global Constraints
remain preserved exactly: the modified Stage 3 Google Drive plan, deleted Thai
dashboard spec, `.kotlin/`, `.ua/`, `artifacts/`, and the two untracked 21
August onboarding plan/design files. This feature worktree contains only the
four intended living-document changes.

## Current state — generic CSV migration complete, 27 August 2026

This section is authoritative. Older checkpoints below are historical and
superseded wherever they conflict with this one. The release baseline below
remains authoritative for release identity and qualification evidence.

The Undo plan remains closed at `12ac37e`. Generic CSV Tasks 1–8 and the final
review fixes are clean on `main`; the implementation range is
`b22d967e7ab5b55f6e0c50a5c7228b6180302e35` through
`01dba43bd5978f976298321973d568f9d83bd977`:

- `b22d967` shares the bounded CSV record reader while retaining the exact
  strict Open Tasks parser;
- `418dbcd`, `4db6864`, and `7e8ac2e` add transient semantic resolution,
  conservative suggestions, and pure full-field generic review;
- `0231124`, `d1fc5ba`, `ca1144c`, `dacd81f`, and `8e5344d` add and harden the
  Activity-scoped transient state and the combined review page;
- `8d63453` adds the More entry and active-workspace command/Undo path; and
- `c074efe` plus `1ab7652` add and correct the Welcome one-shot handoff.
- `c0b195e` applies the migration overlay's zero Scaffold insets and consumes
  the resulting padding; its focused Task 7 review is clean.
- `01dba43` distinguishes completion-date 17:00 from confirmation-time copy,
  removes the fabricated status-override warning when no source Status exists,
  and restores the explicit Ignore fallback labels.

Initial Task 9 focused verification at `c0b195e` passed. The core-data command
ran 53/53 tests: 17 `GenericTasksCsvMapperTest`, 21 `TasksCsvParserTest`, and
15 `InMemoryImportTasksTest`, with zero skips, failures, or errors. The app
command ran 21/21 tests: 16 `TaskMigrationViewModelTest` and five
`WindowPostureMapperTest`, with zero skips, failures, or errors. That same app
command compiled `:core:data`, `:feature:more`, and `:app` Android-test Kotlin;
Gradle reported `BUILD SUCCESSFUL in 1s`, 222 actionable tasks, three executed
and 219 up-to-date.

Final-review focused verification at `01dba43` ran the mapper regression RED,
then GREEN, and the complete mapper suite passed 18/18 with zero skips,
failures, or errors. `:feature:more:compileDebugAndroidTestKotlin` compiled the
updated Compose assertions successfully: `BUILD SUCCESSFUL in 911ms`, 30
actionable tasks, seven executed and 23 up-to-date. A three-assertion host
resource/mapping check also passed after failing on all three corrected
meanings before the fix.

The final-review commands were:

```text
./gradlew :core:data:testDebugUnitTest \
  --tests "*GenericTasksCsvMapperTest" \
  --console=plain
./gradlew :feature:more:compileDebugAndroidTestKotlin --console=plain
```

The exact focused commands were:

```text
./gradlew :core:data:testDebugUnitTest \
  --tests "*GenericTasksCsvMapperTest" \
  --tests "*TasksCsvParserTest" \
  --tests "*InMemoryImportTasksTest" \
  --console=plain
./gradlew :app:testDebugUnitTest \
  --tests "*TaskMigrationViewModelTest" \
  --tests "*WindowPostureMapperTest" \
  :core:data:compileDebugAndroidTestKotlin \
  :feature:more:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin \
  --console=plain
```

Task 9's final gates were consolidated here before its ignored SDD workspace
was closed. The first host gate exposed Task 7's ignored Scaffold-padding lint
error; the scoped `c0b195e` fix and review closed it. Fresh post-fix
verification then reported:

- `./scripts/check-schema-drift.sh`: PASS; no schema drift; 38/38 Gradle tasks
  executed;
- `./gradlew testDebugUnitTest lintDebug :app:assembleDebug --console=plain`:
  PASS; 1,463/1,463 host tests across 138 suites, zero skips, failures, or
  errors; final ordered rerun `BUILD SUCCESSFUL in 447ms`; 553 actionable
  tasks, 12 executed and 541 up-to-date;
- `./gradlew :app:assembleRelease --console=plain`: PASS; `BUILD SUCCESSFUL in
  355ms`; 442 actionable tasks, two executed and 440 up-to-date; and
- `git diff --check`: PASS; `git status --short` contained only the five
  intended docs plus the preserved unrelated entries listed below.

The mandatory whole-slice review of `12ac37e..762c35e` found zero Critical,
two Important, and two Minor issues. `01dba43` fixed all four, `24c5093`
reconciled the durable docs, and the one allowed scoped re-review of
`762c35e..24c5093` was clean with no new breakage.

Fresh controller verification after those fixes completed the gate:

- `./scripts/check-schema-drift.sh`: PASS; no schema drift; `BUILD SUCCESSFUL
  in 3s`; 38/38 tasks executed;
- `./gradlew testDebugUnitTest lintDebug :app:assembleDebug --console=plain`:
  PASS; `BUILD SUCCESSFUL in 55s`; 553 actionable tasks, 58 executed and 495
  up-to-date;
- `./gradlew :app:assembleRelease --console=plain`: PASS; `BUILD SUCCESSFUL in
  43s`; 442 actionable tasks, 50 executed, five from cache, and 387
  up-to-date; and
- `git diff --check`: PASS; `git status --short` contained only the preserved
  unrelated entries listed below.

Task 9 and the generic CSV migration slice are complete. With explicit owner
approval, the plan's 804 KiB ignored SDD workspace and its task reports and
review diffs were permanently deleted after their rulings and evidence were
surfaced; only committed history and this durable handoff remain.
Implementation and review should not be repeated unless the code changes.

Room remains v9 and the authenticated backup and `.otvault` archive formats
remain v1. No version, signing, artifact copy, tag, push, publication, or Play
state changed.

No disposable device or owner-approved physical target exists. The Room
instrumented tests and Welcome/More/migration Compose tests compiled but did
not run. Welcome cancellation/import, exact Undo, repeated import, Done-time
inference, repository-race rejection, strict-parser round trip/rejection,
compact/expanded/folding layouts, RTL, TalkBack/keyboard order, and 200% font
reachability are all **unexecuted**, not PASS. The protected
`Pixel_10_Pro_Fold` was not touched.

The next agreed slice is version/trust footer. It still requires its own
approved design and plan. Do not start it, bump a version, sign/copy an APK,
tag, push, publish, or start Play Console work from this checkpoint.

Preserve the unrelated modified Stage 3 Drive plan, deleted Thai-dashboard
spec, `.kotlin/`, `.ua/`, `artifacts/`, and the two untracked onboarding
plan/design files.

## Previous planning checkpoint — enhancement execution paused, 24 August 2026

The owner approved this delivery order:

1. close the known Undo gaps;
2. reduce migration friction with the generic CSV mapper;
3. add the version/trust footer; and
4. publish a Play internal beta.

The first two slices are designed and have implementation plans:

- Undo design `e125faa`; Undo plan `f9d4a32`;
- generic CSV migration design `1dd8990`; migration plan `ece0c81`.

The owner selected `superpowers:subagent-driven-development` as the future
execution mode. Each plan must use a fresh implementer for each task, a scoped
task review, and a final whole-plan review, with progress recorded in that
plan's ignored SDD workspace and ledger.

**Execution has not started and is not currently authorized.** No SDD
workspace or ledger has been initialized, no subagent has been dispatched, and
no product code, tests, schema, backup format, version, release artifact, or
Play Console state has changed for this programme. The version/trust-footer and
Play-internal-beta slices still need their own approved designs and plans.

When the owner explicitly resumes execution, start with Task 1 of
`docs/superpowers/plans/2026-08-24-undo-gap-closure-plan.md`. Complete and
review that plan before starting
`docs/superpowers/plans/2026-08-24-generic-csv-migration-plan.md`, unless the
owner changes the sequence. Do not infer execution permission from the chosen
workflow alone.

Preserve the unrelated modified Stage 3 Drive plan, deleted Thai-dashboard
spec, `.kotlin/`, `.ua/`, `artifacts/`, and the two untracked onboarding
plan/design files.

## Release baseline — release 1.4.0 tagged, pushed, and closed, 24 August 2026

This section is authoritative for release facts. Older release checkpoints
below are historical and are superseded wherever they conflict with this one.

Implementation commit `68be1b713a665258ba014562b2944af197cd9b18`
(`security: close final lock privacy findings`) follows checkpoint
`0846c1a913cd2ba7db86807161e33b6331320127` on `main`. Documentation
checkpoint `540526fdb22791b64c2d00c878ece6b89911d128` records that integration
and was the pre-release `origin/main` baseline. Release commit
`a47accb45220bee8007da025116b96b878baec0e` is the target of annotated tag
`v1.4.0`; both the commit on `main` and the tag were pushed.

Final corrected-range diff scan `48749dd0-0e8f-4ce7-849d-7eb96fd5527d`
completed and sealed with complete coverage over exact immutable range
`4b3928ff46e1d0cfb0ce72684f4276488bd97b7e..0846c1a913cd2ba7db86807161e33b6331320127`.
It reported one Medium and three Low findings:

| Occurrence | Finding | Disposition in `68be1b7` |
|---|---|---|
| `occ_d55f2df9303320bcc103cd25` | Short foreground bounce exposes widget titles while the app remains locked | Fixed by preserving passive concealment whenever the controller remains locked |
| `occ_0714afb3d4db4d044a230956` | Same state divergence exposes reminder content | Fixed by the same shared lifecycle transition |
| `occ_65ffc615ef23e8ad67d38253` | Stale Snooze ignores title-privacy revocation | Fixed by a live combined app-lock/title-privacy predicate carried to the final repository check |
| `occ_7b4ef44ba9cd848967844ccc` | Stale Complete ignores title-privacy revocation | Fixed by the same shared reminder-mutation predicate |

The owner authorized these two root-cause fixes and regression tests. Commit
`68be1b7` is limited to:

- `app/src/main/kotlin/app/opentasks/lock/AppLockController.kt`;
- `app/src/main/kotlin/app/opentasks/reminders/ReminderSystem.kt`;
- `app/src/test/kotlin/app/opentasks/lock/AppLockControllerTest.kt`; and
- `app/src/test/kotlin/app/opentasks/reminders/ReminderSystemTest.kt`.

The two regressions failed against the original production code. After the
fixes, the lock/reminder focused tests and complete app JVM suite passed. The
fresh host gate (`testDebugUnitTest`, `lintDebug`, debug assembly, and
Android-test Kotlin compilation) passed in 1m51s with 563/563 tasks executed;
fresh release assembly passed in 1m37s with 442/442 tasks executed. Independent
patch review requested one stronger transaction-time title-privacy test and
returned clean after it was amended and rerun.

On 24 August the owner provisioned the expected signing-certificate SHA-256
from an independent trusted record and ran the real gate against all three
current-head APKs. `scripts/verify-release-apk.sh` returned
`verify-release-apk: all checks passed`; no certificate value or signing
material was recorded. The exact authenticated artifacts are versionName
1.3.1 / versionCode 5:

| APK | Bytes | SHA-256 | Packaged native code |
|---|---:|---|---|
| `app-arm64-v8a-release.apk` | 9,852,846 | `bb812a54f646fe322aaf408f4b96746d2baa8acd0398466d44094d5f9a11f3bb` | `arm64-v8a` |
| `app-x86_64-release.apk` | 9,984,216 | `3e92e10625e5a9ac70f01641b72afc06e738c04537436996f66ffdf55648cc7b` | `x86_64` |
| `app-universal-release.apk` | 12,114,299 | `77bd183f9b7542ee375b8c18c764f92aa21d137bacbbdfc20488d4658b938765` | `arm64-v8a`, `x86_64` |

This closes the previously unrun owner proof for these exact files. It does
not create a new release candidate: tag `v1.3.1` already identifies the older
released source at `e4d25a9`. Any next release must increment its version,
rebuild the APKs, and repeat this gate against the rebuilt final artifacts.

Remote workflows for documentation head `540526f` are also settled. Security
run `32672691883` passed. Android run `32672691895` passed verify, release,
benchmark, and compact API 36; only expanded API 37 failed. Its uploaded
reports again show `INSTRUMENTATION_ABORTED: System has crashed`, preserving
the existing emulator-system classification rather than exposing a new app
assertion failure.

The release-head workflows are settled too. Security run `32696640570`
passed. Android run `32696640603` passed verify, compact API 36, release/SBOM,
and benchmark; only expanded API 37 failed. Its uploaded reports contain the
same `INSTRUMENTATION_ABORTED: System has crashed` and Android package-service
loss class as the prior accepted run, plus pre-unlock credential-storage
fallout, with no `AssertionError`. This is the owner-accepted emulator-system
failure, not a new application failure.

On 24 August the owner accepted the recorded physical API 36 testing/benchmark,
credentialled Google restore, and browser/print/accessibility evidence
boundaries. The owner also accepted the expanded API 37 emulator-system issue
and approved any required version bump, annotated tag, push, and release. No
missing p50/p95, raw physical artifact, or manual provider/browser observation
was invented by recording those decisions.

Release commit `a47accb` carries the semver feature bump to versionName 1.4.0 /
versionCode 6 and the final qualification record. The required host gate,
workflow verifier, dependency/SBOM gate, size harness, signed release assembly,
size check, release-verifier harness, direct signature validation, version,
non-debuggable, and exact-ABI checks all pass. The exact rebuilt APKs are:

| APK | Bytes | SHA-256 | Packaged native code |
|---|---:|---|---|
| `app-arm64-v8a-release.apk` | 9,852,846 | `b77a9baecb88a52163c58f532301514ed85a75f38ac2837abcd469d2ea4f862e` | `arm64-v8a` |
| `app-x86_64-release.apk` | 9,984,216 | `cb0b15191e48412baeb1ea6a3b0ea0833eab2ec54361532a8623e0ea9b41d231` | `x86_64` |
| `app-universal-release.apk` | 12,114,299 | `88401d0e2d138ac2a92cb3191625add60e0d874707a95befa49e1e9f4b7dea45` | `arm64-v8a`, `x86_64` |

The owner reports that the same independently held value used as
`OPEN_TASKS_RELEASE_CERT_SHA256` is registered externally as a Play Console
fingerprint. The owner provisioned that independent value outside Codex and
returned only `verify-release-apk: all checks passed` for the exact rebuilt
files above. No certificate value or signing material was recorded.

The final scan now has `artifacts/fix_report.md`, and the original Standard
scan's existing remediation report has the final corrected-range receipt.
The fixes are integrated in `68be1b7`; the earlier owner-signer gate passed for
the exact 1.3.1/5 artifacts above; all other external boundaries are now owner
accepted for 1.4.0. The rebuilt candidate's fresh independent signer comparison
also passed; annotated tag `v1.4.0` and the release push are complete.

Preserve the unrelated modified Stage 3 Drive plan, deleted Thai-dashboard
spec, `.kotlin/`, `.ua/`, `artifacts/`, and the two untracked onboarding
plan/design files. None belongs to this security remediation.

### Next safe steps

No release task remains. Preserve the owner decisions and authenticated
artifact identities above; do not rewrite accepted boundaries as observations
or record the fingerprint. Any later release must use a new version and tag;
do not move `v1.4.0`.

## Superseded checkpoint — owner-requested pause during final security discovery, 23 August 2026

This checkpoint is historical and is superseded by the 24 August section
above.

### Safe pause boundary

The owner requested an immediate pause while final security candidate discovery
was in progress. All three read-only discovery workers were interrupted. Do not
resume them, start another scan or review, change product code, commit, push,
dispatch a workflow, run an external gate, or make a release until the owner
asks to continue.

`HEAD` and `origin/main` are both
`0846c1a913cd2ba7db86807161e33b6331320127` (`docs: record security
qualification pause`). Functional implementation head remains `c649801`.
The existing CI runs have settled: Security `32618409567` passed at `0846c1a`,
while Android `32618409559` failed only in the expanded API 37 emulator lane;
its verify, release, benchmark, and compact API 36 jobs passed. The same job
classification applies to Android runs `32617307931` at `316bd86` and
`32617911318` at `c649801`. No replacement workflow was dispatched.

Final diff scan `48749dd0-0e8f-4ce7-849d-7eb96fd5527d` is durable but
**unsealed**, pinned to exact immutable range
`4b3928ff46e1d0cfb0ce72684f4276488bd97b7e..0846c1a913cd2ba7db86807161e33b6331320127`.
Preflight, advisory access, security-guidance resolution, and threat-model
capture completed; discovery was split across 22 review items. No discovery
candidates or validations were recorded. The interrupted workers had only
preliminary, unvalidated hypotheses involving reminder title privacy,
resumed-backup generation accounting, and duplicate Gradle wrapper properties;
none is a finding. Continue the same scan rather than opening a replacement.
Its directory is
`/private/var/folders/cc/zvtsfhf91m747w_86jlft22r0000gn/T/codex-security-scans-2m0lz4/open-tasks/0846c1a913cd2ba7db86807161e33b6331320127_20260823T061600Z_ake0159w/`.
A fresh independent whole-range review has not started, and the original
scan-local `artifacts/fix_report.md` has not received the final receipt.

Focused independent reviews approved the timer-test correction, failed-CI-
experiment rollback, and exact-ABI signer-gate correction. The earlier
whole-range review over `4b3928f..6cd690e` was not approved because it found
the ineffective serialization claim and the ABI-label gap; both findings are
now corrected. The original scan-local `artifacts/fix_report.md` has been
reconciled through `c649801` but still needs the final scan receipt.

### Source identity and delivered remediation

Tasks 1–11 of
`docs/superpowers/plans/2026-08-21-open-tasks-onboarding-dashboard-nfr-plan.md`
landed as recorded below. Task 12's two security-remediation waves and the
subsequent focused review corrections are implemented, independently reviewed,
and pushed. `origin/main` contains implementation commit
`c649801d720293a0298d4324e02d21f0a42e25d2` (`security: verify release APK
architectures`) followed only by documentation commit `0846c1a`. This is a
security/qualification checkpoint, not a release decision.

The original sealed Standard review's five remediations remain:

1. `78ea33d` — unique FileProvider share staging paths;
2. `3bb60a2` and `939c823` — synchronous reminder/widget lock authority;
3. `bac386f` and `c9974b0` — private durable external-content concealment;
4. `a043152` — the official Gradle 9.7.0 checksum plus policy enforcement;
5. `a7ba7da` and `e4f9bfc` — fail-closed owner-controlled signer verification
   for the arm64, x86_64, and universal release APKs.

The closing scan then produced five distinct findings. Keep their identities
separate in any later audit:

| Candidate | Final severity / confidence | Root fix |
|---|---|---|
| `candidate-4a2ccb9c2d78c2c3` | Low / Medium | `ad6ebc1`, completed by prompt-generation binding in `49671a4` |
| `candidate-d23431158a225406` | Low / High | transaction-bound reminder authorization in `ad6ebc1` |
| `candidate-763115ddae2d9ff7` | Low / High | transaction-bound widget generation/authority checks in `ad6ebc1` |
| `candidate-06c83a55b6738660` | Low / High | versioned Gradle wrapper distribution/ZIP cache namespace in `ad6ebc1` |
| `candidate-da65747ac45e845a` | Medium / High | immediate passive-surface concealment in `3b52404` |

Merge `b28c7d2` integrated those fixes into `main`. `0b44d0d` and `d670404`
added the independently reviewed Linux AAPT2 and coroutines-BOM dependency
metadata needed by clean hosted builds. `ff98bd7` fixes a separate CI-exposed
backup scheduling race: the runner captures the exact local generation under
the existing publication gate and carries it through completion, so a later
edit is never mistaken for the generation already attempted. `6cd690e` tried
`--no-parallel` on the seven-task connected-test Gradle invocation, but hosted
evidence proved that UTP work still overlapped; `afb1d93` removes the
ineffective flag and restores the pre-experiment workflow exactly. This is
rollback, not serialization proof. `316bd86` separately removes a test-only
timer-observation race exposed by compact API 36, and `c649801` requires exact
packaged native-code ABI sets for all three labelled release APKs.

The ignored SDD ledger and task reports remain under
`.superpowers/sdd/2026-08-21-open-tasks-onboarding-dashboard-nfr-plan/`.
Do not delete them while the programme remains incomplete.

### Sealed security evidence

- Original Standard scan `df9a41d7-2458-4943-9c5d-957e98d484e9` reported
  three Medium and two Low findings, zero Critical/High. Its scan-local
  `artifacts/fix_report.md` now exists, maps all five occurrences to their
  fixes and evidence, and has been reconciled through exact follow-up scan
  `2cb2540b-e277-43c5-a12f-564f386de9c8`. The real signer proof remains an
  external release gate, not missing implementation documentation.
- Closing diff scan `31e83519-4242-4240-9b61-7cb357b440e8`, exact range
  `8bb2a6675fbf26f9b265306823e86a759bb6dedf..e4f9bfcae4ce6f9f8341229b68dffebaff991b85`,
  completed with complete coverage and the one-Medium/four-Low findings in the
  table above.
- Remediation diff scan `19aa7c94-d7a0-4b6d-9cef-0e6c347e0ce5`, exact range
  `e4f9bfcae4ce6f9f8341229b68dffebaff991b85..3b524043cd8a78b99fee266f9f4187fcef38c72d`,
  sealed with **zero reportable findings**. Its canonical coverage remains
  partial only because asynchronous purge completion required a real-device
  process-death check. The owner subsequently reported that exact on-device
  check **PASS**; preserve that as external acceptance evidence rather than
  rewriting the sealed scan.
- Exact follow-up diff scan `2cb2540b-e277-43c5-a12f-564f386de9c8`, range
  `d670404062c958cfa0d2161d8d0c03139a4da7b7..ff98bd73851af73e89a46beebe04e0ff87e9394e`,
  completed with complete coverage and **zero findings**.
- Final corrected-range diff scan
  `48749dd0-0e8f-4ce7-849d-7eb96fd5527d`, range
  `4b3928ff46e1d0cfb0ce72684f4276488bd97b7e..0846c1a913cd2ba7db86807161e33b6331320127`,
  is paused during discovery and is neither validated nor sealed. Its worker
  notes are hypotheses, not security findings.

The latest advisory Trusted Access for Cyber check returned `granted`, and
the owner reports completed verification plus OpenAI Daybreak Blue approval.
That account state is advisory context, not repository authorization and not
a substitute for any release gate; refresh it only when the active Codex
Security workflow requires it.

### Verification and remote CI evidence

- Owner-provided device acceptance: **PASS** for the deferred passive-content
  process-death/purge sequence. No certificate value was disclosed.
- Fresh exact-head host gate:
  `./gradlew testDebugUnitTest lintDebug :app:assembleDebug
  :app:compileDebugAndroidTestKotlin --rerun-tasks --max-workers=2` —
  **BUILD SUCCESSFUL in 1m43s; 563/563 tasks executed** at the `c649801`
  working state. Forced-fresh `:app:assembleRelease` also passed in 1m12s with
  442/442 tasks executed.
- `scripts/verify-actions-workflow.sh`,
  `scripts/verify-benchmark-threshold-script.sh`, and
  `scripts/verify-release-apk-script.sh`: PASS. The last command includes
  exact ABI-label and certificate non-disclosure cases; it is not the
  owner-only real three-APK signer proof.
- `git diff --check`: PASS for the focused code commits and this documentation
  checkpoint.
- Security workflow runs `32608967519`, `32615516366`, `32617307907`,
  `32617911327`, and exact-head `32618409567`: CodeQL PASS; dependency review
  was correctly skipped for each direct-push event.
- Android runs `32617307931`, `32617911318`, and exact-head `32618409559`:
  verify, release, benchmark, and compact API 36 jobs PASS. The exact-head
  compact job completed all seven modules with 515 reported test results. Each
  overall workflow failure is confined to expanded API 37 emulator-system
  breakage; no duplicate run was dispatched.
- Earlier Android workflow run `32608967477`: `verify`, `release`, `benchmark`,
  and compact API 36 instrumented tests PASS. Its expanded API 37.0 lane lost
  its `package` and `activity` services before any app assertion.
- Replacement Android workflow run `32615516358` at `6cd690e`: `verify`,
  release/SBOM, and benchmark PASS. Compact API 36 ran all seven modules but
  failed only the test-observation race corrected in `316bd86`. Expanded API
  37.0 failed before any application test assertion and ran zero tests.
- The focused timer test passed 1/1 on the sole audited API 37 arm64
  disposable; Android-test compilation, whitespace checks, and independent
  review also passed for `316bd86`.
- Hosted overlap evidence, the workflow verifier, whitespace checks, and
  independent review approve `afb1d93` as removal of the failed experiment,
  not as serialization proof.
- The signer harness first failed when an x86 payload was renamed as arm64,
  then passed after `c649801` added exact native-code checks for arm64-only,
  x86_64-only, and universal-both. Independent focused review approved the
  correction; no owner certificate was inspected or inferred.

The API 37 signature seen in runs `32607469479` and `32608967477` repeated in
replacement run `32615516358`. The exact Gradle command included
`--no-parallel`, yet at least four connected-test tasks began within seven
seconds. The canary emulator then lost Android's `activity` and `package`
services; UTP reported `INSTRUMENTATION_ABORTED: System has crashed`. The
experiment was therefore disproved and removed in `afb1d93`. This is zero-test
emulator-system evidence, not an application assertion failure or serialization
proof, and no second speculative workaround is claimed.

### Remaining blockers

1. The real signer gate is still pending. The implementation and harness are
   complete, including exact native-code ABI checks, but the owner must supply
   `OPEN_TASKS_RELEASE_CERT_SHA256` from an independent trusted record and
   return only generic PASS/FAIL for the real arm64, x86_64, and universal
   signed APKs. Never derive the expected value from a candidate APK or print
   it in chat, documentation, arguments, or logs.
2. The API 37 expanded CI lane remains red on an emulator-system crash. The
   failed `--no-parallel` experiment was removed; do not claim serialization or
   a second workaround without new root-cause evidence.
3. Earlier compact API 36 emulator CI is green; `32615516358` exposed the
   now-corrected timer-test race. The fixed API 36 arm64 physical performance/
   fresh-install gate remains pending, and no release p50/p95 values are
   claimed.
4. Owner-present credentialled Google restore, two-browser print/keyboard/
   screen-reader/200%-zoom review, and the remaining version-specific release
   evidence remain pending.
5. Final corrected-range scan discovery is paused and unsealed; its preliminary
   hypotheses remain unvalidated. The independent whole-range review has not
   started, and the original fix report still lacks the final receipt. No
   version bump, tag, or release is authorized.

### Exact resume sequence

1. Read this section first. Run `git status --short --branch`, fetch without
   overwriting local work, confirm `HEAD` and `origin/main`, and preserve the
   unrelated working-tree entries listed below. At pause both refs were
   `0846c1a913cd2ba7db86807161e33b6331320127`.
2. Do **not** repeat the completed five-candidate validation, either sealed
   post-fix scan, or the already-passed local/device gates unless source or
   evidence changes.
3. Do not repeat CI runs `32617307931`, `32617911318`, or `32618409559`; their
   useful jobs passed and only expanded API 37 failed at the emulator-system
   layer. Do not repeat the failed configuration or add another workaround
   without new root-cause evidence.
4. Resume final diff scan `48749dd0-0e8f-4ce7-849d-7eb96fd5527d`; never start
   a replacement for the same range. Resume the three interrupted read-only
   partitions or finish their assigned files, record discovery exactly once,
   validate every durable candidate, and treat the three preliminary
   hypotheses above as untrusted until proven. Ask before applying any newly
   validated remediation.
5. Obtain a fresh independent whole-range review over
   `4b3928ff46e1d0cfb0ce72684f4276488bd97b7e..0846c1a913cd2ba7db86807161e33b6331320127`,
   explicitly including `316bd86`, `afb1d93`, `c649801`, and the working
   handoff-only diff.
6. Run the real three-APK signer gate only when the owner has independently
   provisioned the expected certificate fingerprint:

       read -s OPEN_TASKS_RELEASE_CERT_SHA256
       export OPEN_TASKS_RELEASE_CERT_SHA256
       bash scripts/verify-release-apk.sh \
         app/build/outputs/apk/release/app-arm64-v8a-release.apk \
         app/build/outputs/apk/release/app-x86_64-release.apk \
         app/build/outputs/apk/release/app-universal-release.apk
       unset OPEN_TASKS_RELEASE_CERT_SHA256

7. Complete the fixed API 36 arm64 physical benchmark and the remaining
   provider/browser/accessibility gates. Record exact artifacts without
   converting emulator evidence into physical-device claims.
8. After the same final scan is sealed and the review is clean, append its
   receipt to the original scan's existing `artifacts/fix_report.md`, reconcile
   the acceptance documentation, and verify the documentation-only diff.
9. Re-run the full release gate only after any source/workflow change. A
   version bump, tag, or release still requires the owner's explicit decision
   under `RELEASING.md`.

Preserve every current unrelated status entry exactly: the modified Stage 3
Drive plan, deleted Thai-dashboard spec, `.kotlin/`, `artifacts/`, and the two
untracked onboarding plan/design files. None belongs in a handoff-only commit.
The intended new working-tree change is this `HANDOFF.md` update only. No
product fix, candidate validation, fix-report edit, second workflow workaround,
commit, or push is partially applied at this pause.

## Superseded checkpoint — closing security validation paused, 22 August 2026

The following checkpoint is historical and is superseded by the 23 August
2026 section above.

### Onboarding/dashboard/NFR programme — remediation implemented; closing scan not yet closed

Tasks 1–11 of
`docs/superpowers/plans/2026-08-21-open-tasks-onboarding-dashboard-nfr-plan.md`
landed directly on `main` as `fea5663`, `38515dc`, `2615ac0`, `2af5802`,
`58413d4`, `0dea6b3`, `ca890c9`, `6c4fbd4`, `63ecd99`, `5f88a83`, and
`4b3928f`. Task 12 reached its full local-gate run, implemented fixes for all
five findings from the sealed Standard review (with the real owner-only signer
proof still blocked), and then paused during validation of five new closing
diff-scan candidates. The implementation head is
`e4f9bfcae4ce6f9f8341229b68dffebaff991b85` on `main`.

The original five remediations landed in these reviewed commits:

1. `78ea33d` — unique FileProvider staging paths while preserving stable SAF
   download names;
2. `3bb60a2` and `939c823` — synchronous elapsed lock authority owned by the
   reminder and widget action gates;
3. `bac386f` and `c9974b0` — private AlarmManager-backed external-content
   concealment with one monotonic deadline calculation;
4. `a043152` — the independently verified official Gradle 9.7.0 binary
   distribution checksum plus workflow-policy enforcement; and
5. `a7ba7da` and `e4f9bfc` — owner-controlled signer authentication for all
   arm64, x86_64, and universal release APKs, with a fail-closed verifier
   harness that does not disclose certificate values.

Every scoped implementation review and fix-round re-review is clean. The
ignored SDD ledger and task reports are under
`.superpowers/sdd/2026-08-21-open-tasks-onboarding-dashboard-nfr-plan/`.
Do not delete that directory while the programme remains incomplete.

The original sealed Standard security review found **three Medium and two Low
findings, with zero Critical or High**. Its canonical scan ID is
`df9a41d7-2458-4943-9c5d-957e98d484e9`; its local report is under
`/private/var/folders/cc/zvtsfhf91m747w_86jlft22r0000gn/T/codex-security-scans-Jj7CWg/open-tasks/4b3928ff46e1d0cfb0ce72684f4276488bd97b7e_20260822T072609Z__1usm149/`.
The required `artifacts/fix_report.md` has **not** been written. Record four
findings as fixed. The signer implementation is complete, but its finding
remains blocked until the owner runs the real three-APK gate with an
independently trusted input. Never derive that input from a candidate APK and
never print it in chat, documentation, process arguments, or logs.

### Fresh verification at `e4f9bfc`

- `./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace`:
  PASS; authoritative XML totals are 1,414/1,414 passed across 136 suites,
  with zero skips, failures, or errors.
- `./gradlew :app:assembleRelease --stacktrace`: PASS.
- Workflow, release-APK harness, release-size harness, accepted-size, and
  benchmark-threshold verifiers: PASS.
- arm64 APK: 9,852,846 bytes, SHA-256
  `fcc94a0e51b84c6217695de576ef7862a42ad14a2a0ae11488277958df6b590a`.
- x86_64 APK: 9,984,216 bytes, SHA-256
  `6c5bd92aef1b0524044076a094544b2bf714b7c8ba41f44eab1121c0eebf5771`.
- universal APK: 12,114,299 bytes, SHA-256
  `1873ae3b257ef0f7e843159661ef0117f070ae31db5981c909078581355a65bd`.
- `./gradlew cyclonedxBom --stacktrace`: PASS; 580 components; required
  Room, SQLCipher, and Tink components present. JSON SHA-256 is
  `1ae1adb90aeb8a5465eaf8a2dd55d465f72d73892eb094fcc6333e7723cad26a`;
  XML SHA-256 is
  `2abd206576f17899abaaf94c49846e29625a14ed2b9144364bdc5fa9936e6f15`.
- Disposable `Pixel6_Scratch`, API 37 arm64, was the sole ADB target and ran
  headless/read-only with snapshots disabled.
- `:app:connectedDebugAndroidTest`: PASS; XML is authoritative at 95 tests,
  93 passed, two established skips, zero failures/errors. Gradle's console
  `Finished 97 tests` line double-counted the two skipped cases.
- The earlier unchanged seven-module API 37 run remains 510 total, 508 passed,
  two established skips, zero failures. Only `:app` changed afterward.

### Closing diff scan — paused in validation

Closing scan ID: `31e83519-4242-4240-9b61-7cb357b440e8`.

Exact range:
`8bb2a6675fbf26f9b265306823e86a759bb6dedf..e4f9bfcae4ce6f9b8341229b68dffebaff991b85`.

Scan directory:
`/private/var/folders/cc/zvtsfhf91m747w_86jlft22r0000gn/T/codex-security-scans-6vXDUJ/open-tasks/e4f9bfcae4ce6f9b8341229b68dffebaff991b85_20260822T140527Z_qp__djef/`.

Preflight, threat-model, and discovery phases are complete. Discovery covered
all 19 changed files and recorded five candidates. The scan was advanced to
validation with 5 total / 0 persisted completions. Do not call the advisory
TAC-status endpoint again; it was checked once and returned `not_granted`.
That advisory state did not gate the scan.

Independent read-only validation completed locally for all five candidates,
but **none of the dispositions has been persisted to the scan yet**. All five
survived as reportable; severity has not been calibrated because attack-path
analysis has not run:

1. `candidate-4a2ccb9c2d78c2c3` — medium-confidence late biometric success can
   call `AppLockController.onUnlocked()` after `onStop`, cancel both expiry
   paths, and republish titles while the activity remains backgrounded.
2. `candidate-d23431158a225406` — high-confidence reminder-action TOCTOU: the
   live authority check precedes `RoomVaultRepository` readiness/mutex waits,
   so a transaction can begin after authority expires.
3. `candidate-763115ddae2d9ff7` — high-confidence widget-action TOCTOU:
   generation/authority invalidation does not revoke a completion already
   waiting to cross the Room mutation boundary.
4. `candidate-06c83a55b6738660` — high-confidence Gradle migration gap: a
   valid-looking pre-pin distribution plus `.ok` marker takes the wrapper
   cache fast path before checksum verification. Clean CI is protected by its
   changed cache key; persistent local release caches are not.
5. `candidate-da65747ac45e845a` — high-confidence external-content deadline
   gap: missing exact-alarm access permits inexact delay, while later access
   revocation stops the process and cancels the only future exact alarm.

Focused temporary-copy reproductions confirmed candidates 2 and 3. Android
API 37 framework source confirms candidate 1's queued callback ordering.
Bundled wrapper bytecode plus official Gradle source confirms candidate 4.
Official Android alarm behavior plus the repository's maximum-background-
interval contract confirms candidate 5. No validation agent edited the
repository or inspected APK certificate data.

### Exact resume sequence

1. Re-read the Codex Security validation, attack-path, severity, reporting,
   and fix-finding instructions before acting. Preserve the five-candidate
   instance inventory.
2. Persist **all five** validation dispositions in one candidate-validation
   call, then advance validation to 5/5. Do not collapse the reminder and
   widget instances.
3. Run attack-path analysis and severity calibration for every surviving
   candidate, record the paths, draft the semantic report, and complete/read
   scan `31e83519-4242-4240-9b61-7cb357b440e8` exactly once.
4. Fix only the completed scan's validated findings, sequentially and
   test-first. The smallest currently supported root fixes are:
   - reject `onUnlocked()` while `AppLockController` is backgrounded;
   - recheck action-specific authority/generation after the repository's
     existing write mutex is acquired and immediately before its transaction;
   - conceal passive external content immediately on background when a hard
     deadline cannot be guaranteed, while retaining the in-app overlay delay;
   - move `distributionPath` and `zipStorePath` once to
     `wrapper/dists-sha256-v1`, retaining the checksum, so pre-pin `.ok`
     entries are stranded non-destructively.
5. Run a fresh closing security diff scan over the resulting remediation
   range. Do not treat the current incomplete scan as proof that later fixes
   are clean.
6. Write the original sealed scan's `artifacts/fix_report.md`, refresh
   `docs/qualification/onboarding-dashboard-nfr-acceptance.md`,
   `docs/threat-model.md`, `docs/architecture.md`, `RELEASING.md` only if its
   facts change, and this handoff. Then obtain one independent whole-range
   code review.
7. Re-run focused tests, the full host gate, release assembly and all local
   verifiers, SBOM, `git diff --check`, the no-caller proof, and the disposable
   app connected suite before any completion claim.
8. Keep Task 12 and the signer finding blocked until the owner securely
   provisions `OPEN_TASKS_RELEASE_CERT_SHA256` from an independent trusted
   record and returns only the generic PASS/FAIL result from:

       read -s OPEN_TASKS_RELEASE_CERT_SHA256
       export OPEN_TASKS_RELEASE_CERT_SHA256
       bash scripts/verify-release-apk.sh \
         app/build/outputs/apk/release/app-arm64-v8a-release.apk \
         app/build/outputs/apk/release/app-x86_64-release.apk \
         app/build/outputs/apk/release/app-universal-release.apk
       unset OPEN_TASKS_RELEASE_CERT_SHA256

This is not a release decision. No disposable API 36 image or authorised
physical threshold device was available; no owner credential was used; the
two-browser/print/screen-reader exercise did not run; and pushed CodeQL and
dependency-review jobs cannot exist before a push. Those gates remain pending,
with no p50/p95 values claimed. The hard stop before version bump, tag, push,
or release remains in force.

The approved onboarding plan/design stay untracked because Task 12's exact
staging list does not include them. Preserve every current unrelated status
entry exactly: the modified Stage 3 Drive plan, deleted Thai-dashboard spec,
`.kotlin/`, `artifacts/`, and the two untracked onboarding plan/design files.
No product fix for the five closing-scan candidates is partially applied at
this pause.

### Release outcome

The approved Drive release-authorization plan is complete. The physical Fold
passed the in-place update, actionable canceled chooser, release-signed
authorization, and post-force-stop persistence gates; after relaunch, Backup &
recovery remained coherent at `Backed up`. The Cloud gate passed 4/4, the
fresh disposable signed smoke passed 7/7, secure cleanup completed, and the
protected `Pixel_10_Pro_Fold` AVD was never booted.

Qualification commit `e4d25a9` carries versionName 1.3.1 / versionCode 5 and
`docs/qualification/release-1.3.1-sideload.md`. After the owner's explicit
release decision, annotated tag `v1.3.1` and `main` were pushed to origin on
21 August 2026 (`3e1a5a7..e4d25a9`).

Remote workflow run `32468886419` on `e4d25a9` finished with `verify` green,
`release` green, and **compact API 36 green**. The expanded API 37.0
observe-only lane failed in the already documented emulator/profile class:
package and activity services broke, instrumentation reported `System has
crashed`, and later modules could not resolve their activities. Compact ran
the same seven-module matrix successfully, and the expanded lane showed no new
app-regression signature.

### Where things stand

**Stage 9 is complete and released.** Tasks 1–17, the pre-17 repair,
the final whole-branch review (0 Critical, 0 Important, 8 Minor; 0
MUST-FIX in triage), the owner Step 5 manual acceptance matrix, and the
Step 6 release are all done. The ledger
`.superpowers/sdd/2026-08-17-stage-9-board-flow-automation-plan/progress.md`
remains the authoritative execution record.

- Tag `v1.3.0` on `a6a0e0c` (qualification record; versionName 1.3.0 /
  versionCode 4), **pushed to origin 19 August 2026** together with
  `main` (`3abe9cc..a6c8346`, 44 commits) per the owner's decision.
- Remote run on the release head (`32262267179`): `verify` green,
  `release` green, **compact API 36 green** — the five posture-sensitive
  tests red through 1.2.0 now pass on hosted runners. The expanded API
  37.0 observe-only lane stayed red with its known profile-mismatch
  class; not a gate, no new signature.
- Qualification evidence:
  `docs/qualification/stage9-board-flow-automation.md` (machine gates,
  Step 5 acceptance) and
  `docs/qualification/release-1.3.0-sideload.md` (signed smoke 7/7 —
  including the widget row executed by hand, closing the 1.2.0 gap —
  plus 3/3 Stage 9 extras and the v9→v10 in-place upgrade over a 1.2.0
  baseline built in a separate worktree).
- **First post-release follow-up landed** as `0b366f0`
  ("ci: run feature home instrumented tests in the connected matrix"):
  `:feature:home:connectedDebugAndroidTest` joined both instrumented
  lanes, and `scripts/verify-actions-workflow.sh` now enforces the full
  seven-module connected list (mode 644 preserved). Backlog item 1
  discharged.
- **The compact-CI prerequisite is closed.** Two test-only commits landed:
  `670d915` awaits the Room workspace collector after the successful
  automation-rule create, and `3e1a5a7` awaits the recovery candidate UI
  after portable discovery starts on `Dispatchers.Default`. Run
  `32382258182` on `3e1a5a7` finished with `verify` green (2m04s), `release`
  green (6m28s), and **compact API 36 green** (28m02s; all seven modules,
  495 tests, 2 established skips, 0 failures). The expanded API 37.0
  observe-only lane again failed in its known emulator/profile class: Android
  system services disappeared before test execution, yielding zero-test
  infrastructure failures rather than an app regression signal.
- **The final Drive records and backup-boundary follow-up are pushed.** Commit
  `38405e7` finalizes the two stale Drive authorization plan/spec records;
  `4a8cb8c` adds the required-workspace capture guard for automation rules and
  extends recovery dangling-reference coverage to automation-rule workspaces
  and My Day task references. `main` was pushed to origin on 21 August 2026
  (`8d0704c..4a8cb8c`). Remote run `32483325764` finished with `verify` green,
  `release` green, and **compact API 36 green**. The expanded API 37.0
  observe-only lane again lost Android activity/package services,
  instrumentation aborted with `System has crashed`, and later modules could
  not install or resolve activities. This matches the established
  emulator/profile infrastructure class and adds no app-regression signature.
- Secure cleanup complete: both disposable overlays killed with the
  package uninstalled, credential cleared, exported archive deleted,
  worktree and its keystore copy removed, no ADB target or emulator
  process remains. The protected `Pixel_10_Pro_Fold` AVD was never
  booted this session.

**The signed-release Google Drive defect is fixed and released in 1.3.1.** On
the owner's Samsung Galaxy Z Fold 8, the signed 1.3.0 APK opens Google's
account chooser from Backup & recovery, but selecting the account returns to
an unchanged card. The approved diagnosis has two boundaries:

1. The working live Drive qualification covered the debug signing identity,
   while the sideload release identity still needs an Android OAuth client
   matching package `app.opentasks` plus its external signing SHA-1.
2. Before this patch, `OpenTasksApp` discarded every non-OK or null-data
   Activity Result, leaving the ViewModel's pending action and disabled
   presentation unchanged; an initial identity exception could also disappear
   through the generic ViewModel operation wrapper.

The released solution keeps `AuthorizationClient` and the existing
`drive.appdata`-only backup architecture. Initial identity failures are
bounded, one `rejectResolution()` ViewModel path consumes incomplete launcher
results, and `194296e` retains whether a rejected resolution belongs to an
initial connect so `Re-authorise` retries the truthful path. The permanent
owner-present Cloud and physical-device gate is documented in `RELEASING.md`.

- Approved design:
  `docs/superpowers/specs/2026-08-20-drive-release-authorization-hardening-design.md`
  (`36a131f`).
- Approved implementation plan:
  `docs/superpowers/plans/2026-08-20-drive-release-authorization-hardening-plan.md`
  (`116b599`).
- Execution: Preflight and Tasks 1–10 are complete. The resumed physical and
  release gates ran directly from the controller checkout on `main`.
- Implementation base `driveAuthBase`: `816e134`. Task commits:
  `67820e7` bounds ordinary identity failures while preserving coroutine
  cancellation; `875c80e` adds one-shot `rejectResolution()` handling and
  complete launcher-result routing; `da02ddc` adds the permanent
  owner-present signed Drive release gate to `RELEASING.md`; `194296e` makes
  rejected initial connections retry the initial-connect path.
- TDD evidence is recorded in the ignored plan ledger. The combined
  `GoogleDriveAuthorizationManagerTest` and `EncryptedBackupViewModelTest`
  suite passed at `da02ddc`. Independent per-task reviews and Task 4's exact
  `816e134..da02ddc` whole-patch review all returned 0 Critical, 0 Important,
  and 0 Minor findings; the Task 4 checklist passed 11/11.
- Task 9 commit `e4d25a9` contains only the 1.3.1 / versionCode 5 bump and the
  bounded qualification record. The full host gate, separate signed release
  assembly, and `scripts/verify-release-apk.sh` passed for the exact released
  candidate: 16,595,239 bytes with SHA-256
  `99cc4942a23c6a023d987c97f1bcf0b77f2a88fa1977842c573b3ced63cbe676`.
- Task 6 recorded only the four bounded 4/4 PASS results. Task 7's fresh
  seven-row run passed 7/7 and completed secure cleanup. Task 8 records
  in-place update PASS, canceled chooser PASS, and authorization PASS with
  `Backed up`; after the verified force-stop, relaunch persistence also passed
  with the card still at `Backed up`.
- Tag `v1.3.1` points to `e4d25a9`; `main` and the tag are pushed. Remote run
  `32468886419` is green for `verify`, `release`, and compact API 36. Its
  expanded API 37.0 failure matches the known observe-only infrastructure
  class and adds no release-blocking signature.
- `RELEASING.md` now requires the owner-present Drive gate after disposable
  smoke and before qualification commit/tag, with `adb install -r` and the
  physical-workspace safety restrictions.
- The four protected working-tree entries remain untouched and unstaged.

### Remaining work

No work remains in the 1.3.1 Drive authorization release plan. The open items
below are post-release follow-ups and do not block the shipped patch.

### Closed item — compact prerequisite verified

Run `32265891863` on `0b366f0` exposed the remaining naked
`currentWorkspace()` sibling in
`RoomVaultRepositoryInstrumentedTest.automationRuleWorkspaceMismatchIsRejectedOnCreateAndUpdate`.
Commit `670d915` converted only the post-success read to the established
`observeWorkspace()` await pattern. Run `32379099144` confirmed that test
green, then exposed an independent app-test race:
`MainActivityRecoveryRestorationInstrumentedTest.productionRecoveryRouteClearsPassphraseAfterActivityRecreation`
asserted the candidate passphrase field immediately after portable discovery
was launched on `Dispatchers.Default`. Commit `3e1a5a7` added the existing
Compose condition-wait pattern before the unchanged assertion.

Local Android-test compilation passed after each repair. Hosted run
`32382258182` is the final proof: compact API 36 green, 495 tests across seven
modules, 2 established skips, 0 failures. Both changes are test-only; no
production assertion or behavior was weakened or changed.

### Post-release follow-up backlog (from the final review's triage)

None block 1.3.0. In priority order:

1. ~~CI connected matrix~~ **DONE** (`0b366f0`, plus the seven-module
   enforcement in `scripts/verify-actions-workflow.sh`).
2. ~~Compact-lane naked-read repair~~ **DONE** (`670d915`), with the
   independently surfaced recovery discovery wait closed by `3e1a5a7`; hosted
   compact proof is run `32382258182`.
3. AUTOMATION_RULE capture-vs-read asymmetry: an undecodable persisted
   rule `type` is invisible to the editor/engine but hard-fails ALL
   snapshot capture. Unreachable from 1.3.0 writes, but a standing
   constraint on future rule-type additions; decide capture-side
   handling before any v11 rule work.
4. ~~Backup-boundary hardening~~ **DONE**: capture now rejects an
   `automation_rules` row whose required workspace is missing, while optional
   broken rule references retain their editor-visible behavior;
   `RecoveryImportDao.danglingReferenceCount()` now covers both
   `automation_rules.workspaceId` and `my_day_entries.taskId`. The three
   focused tests passed RED→GREEN, the full host gate and separate release
   assembly passed, and the complete `:core:data` instrumented suite passed
   215/215 on the disposable Fold8 AVD. Commit `4a8cb8c` is pushed; hosted run
   `32483325764` passed `verify`, `release`, and compact API 36, with only the
   established expanded API 37.0 observe-only infrastructure failure.
5. Product polish: ~~restore-detach not undoable / detaching move one-way~~
   **DONE in the current checkpoint**; inert-rule visibility in the
   automations editor; remove render-dead `HomeSnapshot.focusTasks`;
   `parentViolation` defence-in-depth on the candidate's `deletedAt`.
6. Small code/test hygiene: Task 16's 20 dp spacers + divider seam;
   duplicate testTags (MyDayRow/MyDaySuggestionRow) and the
   `insightsSummary` file-local fixture; engine-test pinholes
   (`dueInDays` 0/365 bounds, null-config skips); a same-position My
   Day move journals a redundant rank rewrite.

Items 3–6 remain outside the security- and release-bounded 1.3.1 patch.

The Task 9 Undo asymmetries are closed in the current checkpoint. Resume with
Task 1 of `docs/superpowers/plans/2026-08-24-generic-csv-migration-plan.md`;
that plan is already approved. Do not bundle the remaining backlog polish,
version/trust footer, or Play internal beta into that slice. Keep backlog 3
deferred until v11 rule work begins.

The four protected working-tree entries (modified Stage 3 plan doc,
deleted Thai-dashboard spec, untracked `.kotlin/` and `artifacts/`)
remain uncommitted and must stay that way.

## Superseded checkpoint — Stage 9 shipped as 1.3.0, 19 August 2026

This checkpoint is historical and is superseded by the current resume point
above.

### Where Stage 9 stands

Tasks 1–16, the pre-17 instrumented-test repair, AND Task 17's machine
gates (Steps 0–3) are complete with clean reviews. The ledger
`.superpowers/sdd/2026-08-17-stage-9-board-flow-automation-plan/progress.md`
remains the authoritative execution record (audit base on its LINE 2).
HEAD at this checkpoint: `86af72b` plus this docs commit. `main` now
advertises versionName 1.3.0 / versionCode 4 (Ruling L: bump precedes
the gates so the signed release evidence carries the shipping version;
the tag does not exist yet).

**Complete this session (session 6, 18–19 Aug):**

- **Pre-17 repair** (`1de535b` + `73b6655`, review clean): five
  compact-profile tests hardened; compact fully green (9/9, 23/23,
  14/14). The implementer's Fold8 failures were proven to be
  boot-configuration artifacts — `Fold8_Acceptance` is a deterministic
  baseline ONLY under the Stage 8 procedure (`-read-only
  -no-snapshot-load -no-snapshot-save -no-window -gpu host`); the
  controller ran Schedule 23/23 three consecutive times on it
  (Ruling T). Windowed boots of that AVD give false failures.
- **Pre-17 advisor round** (opus, report:
  `advisor-pre-17-report.md`; rulings L–S): bump-first ruling; the
  connected gate is SEVEN modules (`:feature:home` gained its first
  androidTest source set this stage and NO other gate runs it — its
  absence from the CI matrix is an explicit carry-forward); scope-scan
  allowlist; `bash scripts/verify-actions-workflow.sh` (mode 644);
  `check-schema-drift.sh` deletes 10.json mid-run (recovery:
  `git checkout -- core/data/schemas`); Step 6 must NEVER
  `git add docs` (would stage the protected owner entries); the
  AUTOMATION_RULE capture-vs-read backup-outage trap and the
  undischarged Task 8→9 deletedAt carry-forward recovered onto the
  final-review list (Rulings P/Q).
- **Task 17 Steps 0–3** (`21e3926` bump, `7e7dfb6` + `d01c950`
  test-only fixes, `86af72b` qualification record; review clean):
  host gates 1,355/0 + signed minified release APK; all determinism/
  scope scans clean; seven-module Fold8 gate + ten-class compact gate
  green; migration 10/10. The Room automation twins failed on first
  device execution — Ruling U diagnosed the nine Stage 9 twins
  asserting on naked `currentWorkspace()` reads against the documented
  decoupled-collector lag; fix round 1 adopted the house await pattern
  (no assertion weakened, verified by scoped re-review), after which
  `:core:data` ran 212/0 and **Task 14's blocking Important 1 is
  DISCHARGED** (both twins PASS, named rows in the record).
  Qualification evidence: `docs/qualification/stage9-board-flow-automation.md`.

**Final whole-branch review: CLEAN — ready to ship 1.3.0.** The
review (most capable model, range `8d70c96..86af72b`, report at
`final-review-report.md` in the ledger directory) returned 0 Critical,
0 Important, 8 Minor; it verified the four highest-risk surfaces
(engine write path, subtask cascade, My Day rank arithmetic,
backup/journal boundary) correct and dual-engine symmetric against
production source, and re-checked the controller rulings in code. Its
triage of the 20 deferred items: 0 MUST-FIX, 11 FOLLOW-UP, 9 STANDS.
No fix wave was needed — Task 17 Step 4's zero-Critical/zero-Important
criterion is met.

### Post-release follow-up backlog (from the final review's triage)

None block 1.3.0. In priority order:

1. Add `:feature:home:connectedDebugAndroidTest` to the CI connected
   matrix (`.github/workflows/android.yml`), updating
   `scripts/verify-actions-workflow.sh` in the same commit — the FIRST
   post-release commit; until it lands, Home's instrumented tests run
   only in local qualification.
2. AUTOMATION_RULE capture-vs-read asymmetry: an undecodable persisted
   rule `type` is invisible to the editor/engine but hard-fails ALL
   snapshot capture. Unreachable from 1.3.0 writes, but a standing
   constraint on future rule-type additions; decide capture-side
   handling before any v11 rule work.
3. Backup-boundary hardening: capture-side dangling guard for
   `automation_rules` (asymmetric with `danglingMyDayEntryCount`);
   widen `RecoveryImportDao.danglingReferenceCount()` for the two new
   tables.
4. Product polish: restore-detach not undoable / detaching move
   one-way (Task 9); inert-rule visibility in the automations editor;
   remove render-dead `HomeSnapshot.focusTasks`;
   `parentViolation` defence-in-depth on the candidate's `deletedAt`.
5. Small code/test hygiene: Task 16's 20 dp spacers + divider seam;
   duplicate testTags (MyDayRow/MyDaySuggestionRow) and the
   `insightsSummary` file-local fixture; engine-test pinholes
   (`dueInDays` 0/365 bounds, null-config skips); convert the one
   remaining naked-`currentWorkspace()` sibling instrumented test to
   the await pattern; a same-position My Day move journals a
   redundant rank rewrite.

### What remains — owner-present only

1. Owner-present **Step 5 manual acceptance matrix** (eight rows in the
   record, plus Ruling R's additions: the inert
   ON_ENTER_SET_DUE-on-completed-column row, the
   background/foreground-once instruction on the rollover row).
2. Owner-present **Step 6 release**: signed sideload per RELEASING.md,
   `bash scripts/verify-release-apk.sh`, smoke rows recorded in
   `docs/qualification/release-1.3.0-sideload.md` (a separate
   obligation from the Step 5 matrix), tag `v1.3.0`, and the push
   decision (RELEASING.md pushes at tag time; the owner decides).
   Prerequisite: a 1.2.0 APK built from tag `v1.2.0` in a SEPARATE git
   worktree (never check out the tag on `main` — the four protected
   tree entries must survive).
3. Post-release carry-forward: add `:feature:home:connectedDebugAndroidTest`
   to the CI connected matrix (`.github/workflows/android.yml` — out of
   Task 17 scope by ruling).

The four protected working-tree entries (modified Stage 3 plan doc,
deleted Thai-dashboard spec, untracked `.kotlin/` and `artifacts/`)
remain uncommitted and must stay that way.

## Superseded checkpoint — Stage 9 paused mid pre-17 repair, 18 August 2026

Superseded by the checkpoint above; retained for session-5 detail.

### Where Stage 9 stands

Execution of
`docs/superpowers/plans/2026-08-17-stage-9-board-flow-automation-plan.md`
continues on `main` (audit base `8d70c96`, HEAD `c945282`). The ignored
ledger
`.superpowers/sdd/2026-08-17-stage-9-board-flow-automation-plan/progress.md`
remains the authoritative execution record — every ruling, deferred minor
and carry-forward note lives there, not here. The audit base sits on the
ledger's LINE 2 (`Audit-base:`), not line 1.

**Tasks 1–16 are complete with clean reviews. What remains: the pre-17
repair (dispatched once, failed silently — see below), then Task 17
(qualification and release 1.3.0), the advisor call, and the final
whole-branch review.**

### Pre-17 repair state (session 5, 18 August)

The dedicated repair round for the five CI-profile-red instrumented
tests is PREPARED but NOT EXECUTED:

- **Reproduction infrastructure is built and verified.** The SDK has no
  cmdline-tools, so `avdmanager` does not exist; a compact-profile
  scratch AVD `Pixel6_Scratch` was hand-crafted instead (pixel_6
  profile, 1080×2400 @420 ≈ 411 dp, android-37.0 google_apis_playstore
  arm64-v8a — config.ini + pointer `.ini` only; the emulator generated
  its runtime files at first boot). The controller reproduced the CI
  failures on it with byte-identical signatures:
  `ScheduleScreenInstrumentedTest` 23 tests / the exact 2 CI failures,
  `ProjectWorkbenchInstrumentedTest` 14 tests / the exact 2 CI
  failures. Failure 1 (`ProcessRestorationInstrumentedTest`, :app) was
  not pre-run. Profile, not API level, is the confirmed failure driver.
  The AVD was booted `-read-only -no-snapshot-load -no-snapshot-save`
  and holds no state; it is safe to boot again.
- **The brief is written and ready** at
  `.superpowers/sdd/2026-08-17-stage-9-board-flow-automation-plan/repair-brief.md`:
  test-only scope with stop-and-report on any production defect, the
  five verbatim CI failure stanzas (also in `repair-ci-failures.md`),
  boot/animation/class-run commands, hardening rules
  (scroll-before-assert, bounds-derived drags, explicit layout inputs,
  never weaken), both-profiles verification (compact GREEN on
  `Pixel6_Scratch` plus class-scoped regression on `Fold8_Acceptance`),
  and the full process contract.
- **The first implementer dispatch FAILED SILENTLY**: the agent
  completed with an empty result — no commit, no report, no test edits,
  no tree changes, no emulator left running. Zero work product, so
  nothing needs salvage or review; the re-dispatch needs no
  re-verification of the prepared infrastructure above.

### Task completion summary (Tasks 1–16)

- **Tasks 1–11** are summarised in the superseded checkpoint below;
  nothing about their state has changed.
- **Task 12 complete** (`4c9777d` + `a1ffab0`, review clean): My Day
  replaces Today focus on Home, with drag and the 48 dp menu fallback.
  The reviewer traced the drag arithmetic algebraically: the self-drop
  guard is a general structural check, the regression test genuinely
  fails against unguarded code, and `afterTaskId = null` fires exactly
  for the front position. `HomeSnapshot.focusTasks` turned out to have
  NO remaining consumer (the widget reads `computeTodayProjection`) —
  the field stays per plan but is render-dead; parked for final-review
  triage.
- **Task 13 complete** (`d2c5d30`, review clean): My Day curation sheet,
  suggestions, board-card menu and detail-toggle entry points, and the
  Plan button rewired to the sheet. Exactly one `myDaySuggestions` call
  site, in `:app`.
- **Task 14 complete** (`f6d0016` + `0d36217` + `d123cf5`, review clean
  after one fix round): the automation engine — rule evaluation inside
  both engines' `execute()` for the three status-transition commands,
  outputs via internal `dispatch()`, flattened one-batch undo, one
  journal generation, and `MyDaySweeper` on `MainActivity.onStart`. The
  pre-dispatch advisor call (recorded cadence) produced eleven binding
  controller rulings (ledger Rulings A–K), including: never wrap
  `dispatch(output)` in `runCatching`; SET_DUE outputs set
  `restorePastReminder = true`; the sweeper gate lives as
  `myDaySweepEnabled` in core:domain; `MAX_MY_DAY_ENTRIES` moved to
  `core/domain/MyDayRules.kt`. The opus review verified all seven
  load-bearing properties against the code; both Important findings
  were coverage-only, and the fix round extracted
  `automationTransitionedTaskIds` to core:domain and added two-task
  `CompleteTasks` coverage.
- **Task 15 complete** (`4e97d6a` + `5ed996f` + `d17a8b8` + `08dd28d`,
  review clean after one fix round): `staleTaskIds` pure projection in
  core:domain plus 18 dp badges on Tasks, board and workbench rows;
  Home deliberately badge-free. `lastTouched` matches ReviewQueue
  (`Instant.MIN` sentinel). The fix round added same-scope tie-break
  coverage.
- **Task 16 complete** (`2a8ea0d`, review clean): automations editor on
  More — rule list with enable switch and dialog-guarded delete, a
  five-type add sheet whose confirm gating mirrors the validator
  matrix, and render-only broken-reference rows that stay deletable.
  The section sorts rules by `id.value` ascending (controller ruling),
  discharging the Task 5 engine-ordering carry-forward. Five cosmetic
  minors deferred to the ledger.

The full CI-equivalent gate is green at `2a8ea0d`. Every Compose and
instrumented addition from Tasks 12–16 remains compile-only until Task
17's device gate.

### Resume instructions, in order

1. **Re-dispatch the repair implementer** (sonnet per the model ruling)
   using `repair-brief.md` — the same dispatch wording as the failed
   attempt (no-subagents, foreground builds, behavioural-vs-compile-
   existence RED, scoped git add, commit-early, kill-emulators). Start
   `caffeinate -i` first if it is not running. After it lands: review
   the repair diff (the failed dispatch produced nothing to review, so
   this is a fresh full review, not a re-review), then continue.
   Boot commands and the one-emulator-at-a-time rule are in the brief;
   never boot `Pixel_10_Pro_Fold`.
2. **Then dispatch Task 17** (brief staged: `task-17-brief.md`; BASE is
   the repair commit unless HEAD moved further). Two corrections to
   carry: the brief's Step 2 reads the audit base with `head -1` on the
   ledger — WRONG, extract the `Audit-base:` value from line 2; and
   Step 3's connected gate runs only on the sole disposable ADB target
   started `-read-only -no-snapshot-load -no-snapshot-save`, NEVER the
   protected `Pixel_10_Pro_Fold` AVD. The SDK is at
   `~/Library/Android/sdk`; adb/emulator are not on PATH.
3. **Task 17 carries blocking device gates from Task 14's review**: the
   instrumented twins
   `automationRulesFireInsideTheTriggerGenerationAndComposeOneUndo` and
   `automationMyDayOutputUndoReplaysAndExclusionsNeverFire` must run
   green on device before the stage closes — the Room evaluation path
   has zero executed coverage until then.
4. **Call the advisor BEFORE the final whole-branch review** (cadence
   trigger 2, recorded in the ledger): the accumulated rulings compound
   into the release.
5. **Point the final whole-branch review at the ledger's deferred-minor
   and parked lines.** Named must-triage items: the two Task 9
   asymmetries (restore-detach not undoable; a detaching move is
   one-way), the three surfaces with pre-existing rejection degradation
   (Today widget, reminder actions, weekly Review queue), render-dead
   `HomeSnapshot.focusTasks`, the rank-overflow undo asymmetry (a
   rule-driven My Day add can re-rank up to 200 entries and its undo
   restores membership, not order), inert-rule invisibility (the
   ruled-by-design silent no-ops of SET_DUE on completed columns and
   fired-reminder edge cases), and Task 16's five cosmetic minors
   (including three 20 dp spacers off the 4 dp scale).
6. Task 17's Step 5 manual acceptance matrix and the sideload release
   need the owner at the device — plan that session accordingly.

### Session notes worth knowing

- Session 5 (18 August evening, this checkpoint) prepared the repair
  infrastructure and lost one agent to a silent empty-result completion
  (no output, no work, nothing to salvage — the first loss of its kind;
  re-dispatch fresh rather than resuming). `caffeinate -i` was running
  the whole time, so this was not a host-sleep loss.
- Session 3 (18 August) ran clean: `caffeinate -i` was
  started per the previous session's note and ZERO agents were lost to
  host sleep or watchdog stalls across five full task loops — versus
  nine losses the session before. Keep doing this.
- The advisor cadence paid for itself again: the pre-Task-14 call found
  a genuinely unwritable test in the plan (internal types), two silent
  rejection traps that would have gutted SET_DUE rules, and stale line
  anchors — all ruled before dispatch instead of surfacing as review
  findings.
- Keep stating the no-subagents rule, foreground builds, and the
  behavioural-vs-compile-existence RED distinction in every implementer
  dispatch; every session-3 implementer complied.

## Superseded checkpoint — Stage 9 paused mid-Task 12, 18 August 2026

This section was authoritative until Tasks 12–16 landed. It is retained for
the Task 1–11 detail and session notes, and is superseded by the checkpoint
above wherever they conflict.

### Where Stage 9 stands (as of the Task 12 pause)

Execution of
`docs/superpowers/plans/2026-08-17-stage-9-board-flow-automation-plan.md`
continues on `main` (audit base `8d70c96`). The ignored ledger
`.superpowers/sdd/2026-08-17-stage-9-board-flow-automation-plan/progress.md`
remains the authoritative execution record — every ruling, deferred
minor and carry-forward note lives there, not here.

**Tasks 1–11 are complete with clean reviews. Task 12 is committed and
gate-green but has NOT been reviewed.**

- **Tasks 1–4** (`be24440`, `28ea96b`, `6d616a6`…`cd0a425`, `414bf36`) —
  Room v10 boundary, dual-arity WORKFLOW_STATUS, AUTOMATION_RULE and
  MY_DAY backup families, My Day commands. Recorded in the superseded
  checkpoint below.
- **Task 5 complete** (`0bd9232` + `b760c4c`, review clean): automation
  rule CRUD in both engines with per-type config validation that matches
  `BackupMutationCodec`'s matrix field for field, workspace and reference
  checks, the 20-rule bound, and repository-produced undo. Fix round 1
  extracted the pure validator trio to `core/domain/AutomationEngine.kt`.
- **Task 6 complete** (`34bdc01`, review APPROVED 0C/0I): WIP limits —
  `SetWorkflowStatusWipLimit`, `ChangeTaskStatus.acknowledgeWipLimit`,
  and a confirm-never-block gate in `changeTaskStatus` for non-COMPLETED
  destinations only. Range rule lives once in
  `core/domain/WorkflowWipLimit.kt`.
- **Task 7 complete** (`d982847`, review APPROVED 0C/0I): WIP limits UI —
  editor field, board column count-against-limit header, and the unified
  confirm path. Also closed a latent gap where board drops bypassed the
  blocked-completion confirm.
- **Task 8 complete** (`8b76136`, review APPROVED 0C/0I): `SubtaskRules`
  as the single nesting authority (`parentViolation`, `subtaskRollups`,
  `attachableSubtasks` — signatures are frozen contracts), `SetTaskParent`,
  and `CreateTask(parent:)` with one-level guards in both engines.
- **Task 9 complete** (`c699f35` + `da38d37`, review clean after one fix
  round): subtask semantics — open-subtasks completion confirm, subtree
  bin cascade with atomic parent-first undo, and move expansion, across
  seven handlers in each engine. The Opus review found a real
  cross-project bug the implementer's own reasoning had ruled out; see
  the ledger for the repro and the ruling.
- **Task 10 complete** (`a6a5ecb` + `13a10f1`, review clean after one fix
  round): nesting in `arrangeTasks`, `indentedTaskIds`, list indentation
  on Tasks and the workbench, and board rollup chips. The fix corrected a
  modifier-chain order that put `testTag` outside `padding`.
- **Task 11 complete** (`d1975eb`, `c7b1e1d`, `2a8514e` + `bc565c6`,
  review clean after one fix round): task-detail subtasks section, attach
  and detach flows, parent breadcrumb, and the single and bulk
  completion-confirm dialogs. This is the task that made the
  `OPEN_SUBTASKS_CONFIRM_REQUIRED` gate satisfiable. The fix closed a race
  where an attach/detach rejection resolving after navigation rendered
  against the wrong task.
- **Task 12 committed but UNREVIEWED** (`4c9777d` + `a1ffab0`): My Day
  replaces Today focus on Home, with drag and the 48 dp menu fallback.

### Superseded resume instructions (do not use — Tasks 12–16 are complete; see the current checkpoint above)

1. **Dispatch the Task 12 reviewer first, over `36669f1..a1ffab0`.**
   Do not start Task 13 until it passes. The review package is already
   written at
   `.superpowers/sdd/2026-08-17-stage-9-board-flow-automation-plan/review-36669f1..a1ffab0.diff`
   and `task-12-report.md` exists, so the reviewer gets the normal full
   inputs. A reviewer was dispatched and stopped mid-run when the session
   paused; it produced no output, so there is no partial verdict.
2. **This review needs more than routine attention.** The implementer
   reported DONE_WITH_CONCERNS: an index bug reached commit despite its
   own self-review — dragging a My Day row onto its immediate successor
   computed `afterTaskId == taskId`, which `moveMyDayEntry` rejects as
   `NOT_FOUND`. It added a guard and a regression test. The reviewer must
   check that the guard covers every drop position resolving to the
   dragged row (including first, last, and a drop on the row's own
   bounds), whether the regression test genuinely exercises that case,
   and whether sibling boundary off-by-ones exist elsewhere in the drag
   arithmetic — particularly whether `afterTaskId = null` (move to front)
   is produced correctly. The full dispatch wording is in the ledger.
3. What IS verified about `a1ffab0`: the full CI-equivalent gate plus
   both `compileDebugAndroidTestKotlin` targets are green, twice, with
   zero warnings. What is NOT verified: anything a review would catch,
   and all drag behaviour, which is compile-only until Task 17.
4. Then continue the plan loop at Task 13 (Tasks 13–17 remain).
5. **Call the advisor before dispatching Task 14** — recorded cadence
   decision. It is the stage's riskiest work (rule evaluation inside
   `execute()`, non-reentrant mutex so outputs apply via internal
   `dispatch()`, opus implementer) and carries two live carry-forwards.
   The advisor has already caught one over-broad controller ruling this
   stage.
6. Carry the ledger's live carry-forward notes into their dispatches:
   the codec-authority note into Task 14; `AutomationEngine.kt` already
   EXISTS so Task 14 modifies rather than creates it; and the
   automation-rule ordering asymmetry between engines into Task 16.
7. The five CI-profile-red instrumented tests from the 1.2.0 hosted
   lanes still need hardening. Ruled to run as a dedicated repair
   dispatch immediately before Task 17, because Tasks 13–16 keep
   rewriting the same posture-sensitive UI surfaces.
8. **Before the stage ships**, note what Task 11 did and did not close.
   It wired the open-subtasks confirm dialogs for **Tasks, Board and
   Home**. Three surfaces keep their pre-existing degradation — the Today
   widget and reminder actions (which discard rejections entirely) and
   the weekly **Review** queue (whose `executeReview` has no rejection
   special-casing at all). All three already dead-ended on blocked-task
   completions before Stage 9, so open-subtasks inherits that asymmetry
   rather than creating it; ruled by-design and parked for final-review
   triage. The ledger holds the verified precedent for each.
9. **Point the final whole-branch review at the ledger's deferred-minor
   and parked lines.** Two Task 9 minors — restore-detach not being
   undoable, and a detaching move being one-way — were called real
   asymmetries by their reviewer, and no scheduled task owns them. That
   pointer is the only mechanism that surfaces them.

### Session notes worth knowing

- **Infrastructure has been the dominant cost.** Nine agents so far were
  killed mid-run by `Your computer went to sleep` API errors or a 600s
  stream watchdog stall. No work was ever lost — the tree was checked
  before each resume and the agents' contexts survived — but each one
  cost a restart. Running `caffeinate -i` (or disabling sleep) would
  materially speed up the remaining tasks. One reviewer also burned 49
  minutes on a 5 KB diff before stalling out; re-dispatching fresh with
  an explicit "this should take minutes, not an hour" and permission to
  answer with stated uncertainty fixed it.
- **One process violation, self-disclosed and cleared.** Task 9's
  implementer launched a read-only Explore subagent mid-task, which its
  dispatch forbade, then discarded the output and redid the check by
  hand. Verified no swarm occurred (one commit in range, clean tree, no
  stray agents), and the reviewer independently re-verified the
  underlying cross-module call-site question rather than taking the
  claim on trust. Keep stating the no-subagents rule in every
  implementer dispatch.
- Implementer dispatches now also carry: run every build in the
  FOREGROUND (one agent lost a whole turn backgrounding its gate build),
  and distinguish behavioural RED from compile-existence RED in reports.

## Superseded checkpoint — Stage 9 paused after Task 4 complete, 17 August 2026

This section was authoritative until Tasks 5–10 landed. It is retained for
the Task 1–4 detail and the release record, and is superseded by the
checkpoint above wherever they conflict.

### Stage 9 execution state (subagent-driven, paused by owner)

Execution of
`docs/superpowers/plans/2026-08-17-stage-9-board-flow-automation-plan.md`
started on `main` after the tag (audit base `8d70c96`, recorded in the
ignored ledger
`.superpowers/sdd/2026-08-17-stage-9-board-flow-automation-plan/progress.md`
— the ledger is the authoritative execution record: preflight scan,
per-task completions, rulings, deferred minors).

- **Task 1 complete** (`be24440`, review clean): Room v10 boundary —
  `automation_rules` + `my_day_entries` tables, `wipLimit` column,
  `MIGRATION_9_10` with stamped marker, exported `10.json`, models and
  snapshot fields, purge cleanup hook.
- **Task 2 complete** (`28ea96b`, review clean): dual-arity
  WORKFLOW_STATUS amendment inside format v1 (9- and 10-field accepted
  forever, encoder emits 10), absence-tolerant import, stage-2 fixtures
  regenerated, OtVault frozen-archive compat proven green.
- **Task 3 complete** (`6d616a6` + `dbda761` + `3a34bd7` + `cd0a425`,
  review clean): AUTOMATION_RULE and MY_DAY families through every backup
  layer, StagedVaultVerifier extended (controller ruling — the plan's
  "needs no change" was wrong), MY_DAY snapshot-level existence check,
  and fix round 1 landed the review's one open Important: AUTOMATION_RULE
  joined `validateWorkspaceOwnedRecords` in `BackupPayloadCodec`
  (workspaceId resolves in-snapshot, fail closed on encode and on
  decode/import, focused JVM test with RED/GREEN proof). Scoped re-review
  of the fix: APPROVED, 0 Critical, 0 Important.
- **Task 4 complete** (`414bf36`, review APPROVED 0 Critical / 0
  Important): MyDayRules (base-36 midpoint ranks with bounded-null
  fallback, suggestions), five My Day commands in both engines
  (Add/Remove/Move/Sweep/Restore) with repository-produced Undo and
  MY_DAY journal records, `HomeSnapshot.myDayTasks` real in both engines,
  `MY_DAY_LIMIT_REACHED`, and the controller's pre-dispatch append-bound
  ruling implemented: appends whose naive `rankAfter` rank would exceed
  the journal codec's 200-char bound full re-rank with
  `myDayRankForIndex` (the 200-add bound test proves the re-rank ran).
  Task 1's InMemory My Day purge-parity minor discharged. Two
  reviewer-accepted parity rulings and two deferred minors are in the
  ledger.
- Full CI-equivalent gate is green at `414bf36`
  (`testDebugUnitTest lintDebug :app:assembleDebug`, 553 tasks; `:core:data`
  683 and `:core:domain` 125 unit tests). Instrumented suites remain
  compile-only until the plan's Task 17.

**Superseded resume instructions (do not use — Task 5 is complete; see
the current checkpoint above):** dispatch Task 5, then continue the plan
loop task by task, carrying the ledger's codec-authority carry-forward
note into the Task 5 and Task 14 dispatches. Only the Task 14 half of
that note is still live.

### Release 1.2.0 is tagged

- **`v1.2.0` is an annotated tag on `8841fa1` (the qualified candidate),
  pushed to origin on 17 August 2026.** The tagged SHA was asserted equal
  to the candidate and to the CI run's `headSha` before pushing. The
  qualification invalidation invariant is lifted.
- The GitHub account billing lock was cleared by the owner on 17 August.
  Android run `31957165017` then executed for real: **attempt 4 and the
  failed-jobs retry attempt 5 both landed `verify` and `release` green.**
- **The two hosted instrumented lanes stayed red, deterministically.**
  Compact API 36 failed the same five tests on both attempts — all
  posture-sensitive Stage 8 UI tests meeting the generic hosted-AVD
  screen profile for the first time (the billing lock had blocked every
  earlier attempt):
  `ProjectWorkbenchInstrumentedTest.workbenchUsesOpaqueLazyKeysForMilestonesGroupsAndTasks`,
  `ProjectWorkbenchInstrumentedTest.suppliedWorkbenchGroupsKeepTheirOrderAndListControlsStayStateless`,
  `ProcessRestorationInstrumentedTest.timelinePresentationAnchorAndSelectionIsolateBetweenProjectsAfterRootRecreation`,
  `ScheduleScreenInstrumentedTest.expandedWeekTrayDragUsesDayAndRemoveCallbacks`,
  `ScheduleScreenInstrumentedTest.expandedWeekDragMovesDatedTaskBetweenDays`.
  The expanded API 37.0 observe-only lane is broadly red (board drag,
  project notes, fold8 pane-fraction, timeline suites) — **not** its
  historical single "credential-encrypted storage unavailable"
  signature; same profile-mismatch class.
- **Owner ruling, 17 August: tag on the local qualification.** The
  substantive evidence is the audited local gate — six-module connected
  suite 453/453 on the API 36 disposable, the full manual acceptance
  matrix, and the signed sideload smoke — with CI `verify` and `release`
  green. The five compact-lane tests need CI-profile hardening; that
  work rides Stage 9 execution (post-tag, no invariant at stake) as a
  repair task, not a re-qualification.

### Remaining order of work

1. Merge Dependabot #14–#19 as one batch (the instrumented lanes stay
   red under the same accepted profile gap; require `verify` and
   `release` green on the post-merge head).
2. Run the local CI-equivalent gate on the merged `main`
   (`./gradlew testDebugUnitTest lintDebug :app:assembleDebug`) before
   any Stage 9 task starts — six dependency bumps land at once.
3. Record the Stage 9 audit base SHA in
   `.superpowers/sdd/2026-08-17-stage-9-board-flow-automation-plan/progress.md`.
4. Execute the Stage 9 plan subagent-driven (the "Stage 9 planned"
   checkpoint below holds the spec/plan/decision record). Fold the
   five-test CI hardening in as repair work during the stage.

## Superseded checkpoint — release 1.2.0 pushed and awaiting one CI re-run, 17 August 2026

This section was authoritative until the tag landed. It is retained for
chronological context and is superseded by the checkpoint above.

### Resume in one paragraph

Release 1.2.0 is fully qualified locally and pushed. Exactly one thing stands
between here and the tag: the GitHub account is **locked for billing**, so
Actions refuses to start any job. Clear the lock through Payment history, then
run `gh run rerun 31957165017` — that re-executes the Android workflow against
the exact candidate commit `8841fa1`. When `verify`, compact API 36, and
`release` are green, tag `v1.2.0` **on `8841fa1` by SHA** and commit the
release handoff. Until the tag exists, make no production, build, or
test-source edit.

### The blocker, diagnosed

- **The GitHub account is locked for billing.** The authoritative evidence is
  the check-run annotation on re-run attempt 2 of run `31957165017`, taken
  after the repository was made public: "The job was not started because your
  account is locked due to a billing issue." Blocked jobs are never assigned a
  runner, so they consume zero minutes and incur zero charge.
- **This is account-level, not repository-level or minute-related.** Two
  earlier hypotheses are disproved and should not be retried:
  - *Making the repository public does not help.* It was switched to public on
    17 August and the block persisted unchanged. Public repositories do not
    meter Actions minutes at all, so the lock is clearly not about the
    allowance. The repository is still public; that is now an independent
    decision to revisit on its own merits, not a fix for this.
  - *It is not the `$0` spending limit.* The billing overview for August shows
    gross metered usage `$12.13` fully offset by an included discount of
    `$12.13` with **Next payment due: –**, and the annotation changed from the
    generic payments-or-spending-limit wording to an explicit account lock.
    Raising a budget will not clear a lock.
- **Resolution.** Settings → Billing and licensing → **Payment history**, and
  look for an unpaid or failed invoice, including from a period before August;
  the current-month view will not show it. Settle the outstanding amount and
  update the payment method under **Payment information**. Check email for a
  GitHub billing notice as well — account locks are normally announced there.
  If Actions is still refused once the balance clears, the lock has to be
  released by GitHub Support; that is the escalation path.

### Exact state

- **Release 1.2.0 (versionCode 3) is a pushed, locally qualified candidate. It
  is not tagged and not released.** The final reviewed
  `implementationHeadSha` is
  `b6c438f312b40f228e1d19365062debe479054e7`; Stage 8 began at
  `8047f136541d22b15ca20db8971ea67685e250b5`. The qualification records are
  `docs/qualification/stage8-planning-surfaces.md` and
  `docs/qualification/release-1.2.0-sideload.md`.
- All review obligations are discharged: the scoped review of the two repairs
  (`c495dd2` Quick Add close-consumption, `94c42f7` fixture backup-object
  cleanup) is APPROVED, and the literal whole-stage review of
  `8047f136..b6c438f` returned **Ready to tag with fixes — 0 Critical**, its
  sole Important being the contract-docs refresh that this checkpoint's commit
  lands. Zero Critical and zero Important findings are open.
- Forced-fresh host gates at `b6c438f` are green: six androidTest source sets
  compile; `testDebugUnitTest lintDebug :app:assembleDebug --rerun-tasks`
  BUILD SUCCESSFUL in 5m03s with 553/553 tasks and 1,304 JVM tests across 127
  suites; `:app:assembleRelease --rerun-tasks` BUILD SUCCESSFUL in 4m28s with
  442/442 tasks and `verify-release-apk.sh` all checks passed; schema, all five
  fixture generators, workflow pinning, and diff checks clean; scope audit
  `STEP7-ALL-GREEN` over 85 changed files.
- **The six-module connected gate is green:** 453 tests, 0 failures, 0 errors,
  exactly the two established skips (credentialed Drive, cross-display fold),
  BUILD SUCCESSFUL in 10m25s. Module totals: `:app` 92 with 2 skips,
  `:core:data` 192, `:feature:tasks` 48, `:feature:projects` 30,
  `:feature:schedule` 23, `:feature:more` 68.
- The earlier bounded `AppNotIdleException` in `ScheduleScreenInstrumentedTest`
  is diagnosed and closed as **environmental, with no repository edit**: a Play
  Store self-update was AOT-compiling for 78 seconds across that test's
  60-second Espresso window. The rerun disables the Play client on the
  disposable overlay and gates start-up on a dexopt quiesce check; its logcat
  has zero `dex2oat` lines, zero `Davey!` frames, and zero AppNotIdle events.
- The full Stage 8 manual acceptance matrix passed, including the pinned 18:00
  undated rule under a live zone replacement, a discriminating DST-gap proof,
  pointer drag in expanded Week and Month, Timeline chain highlighting, digest
  privacy and zero-count silence, and process restoration. Native fold posture
  stays environment-blocked on this AVD and is not claimed.
- Signed smoke on the verified 1.2.0 (3) APK (16,372,751 bytes, SHA-256
  `c81fa17d…3c3f17`) passed **6 of 7 rows and 3 of 3 Stage 8 extras**,
  including the exact scenario that blocked the previous candidate:
  cancelled Quick Add → immediate app lock → digest tap → unlock landed on
  Home with no sheet reopening. **Row 6 (placing the Today widget) was not
  executed** — the launcher will not begin its widget drag from synthetic
  pointer events on a headless overlay. It is recorded as un-executed, not
  waived; place it by hand before claiming a complete 7-of-7 smoke.
- Secure cleanup is complete: exported archive deleted, temporary device
  credential cleared to `CredentialType: NONE`, package uninstalled, overlay
  killed `OVERLAY-STOPPED-CLEAN`, and no ADB target, emulator process, or
  temporary directory remains.
- **`main` is pushed.** `ea7cd31..8841fa1` went to origin on 16 August, so the
  whole Stage 8 implementation and both qualification records are on GitHub.
  The candidate commit is
  `8841fa19e6b3b989590e6bbdeb7a3813eec06be1` (`8841fa1`,
  `docs: qualify release 1.2.0 candidate`).
- **The re-run handle is Android workflow run `31957165017`**, created against
  that exact candidate SHA. Every job failed in 2–3 seconds with zero executed
  steps and `release` was skipped, under the billing annotation above; zero
  minutes were consumed. That failed run is deliberately valuable: the workflow
  triggers only on `push: branches: [main]` and `pull_request` — there is **no
  `workflow_dispatch`** — so `gh run rerun 31957165017` is the only way to get
  a green run on the literal candidate without a new commit. Adding
  `workflow_dispatch` would be a workflow-file edit that trips the
  invalidation invariant and the `scripts/verify-actions-workflow.sh` shape
  check. **GitHub allows re-runs for about 30 days, so this handle expires
  around 15 September 2026**; after that a fresh push is needed and the tag
  target moves.
- **Recorded ruling — tag by SHA, not by HEAD.** This handoff commit sits on
  top of the candidate, so `8841fa1` is no longer HEAD. Step 19's literal
  `test "$(git rev-list -n 1 v1.2.0)" = "$(git rev-parse HEAD)"` is therefore
  replaced by the stronger equivalent: create the tag with
  `git tag -a v1.2.0 8841fa1` and assert that the tagged SHA equals the
  `headSha` of the green run. The plan already expects a HANDOFF commit to
  follow the tag; only the ordering differs.
- Remaining steps, in order, once billing is fixed: (18) `gh run rerun
  31957165017` and require `verify`, compact API 36, and `release` green, with
  expanded API 37.0 observe-only and permitted to stay red **only** on the
  unchanged historical pre-test failure "credential-encrypted storage
  unavailable"; (19) `git tag -a v1.2.0 8841fa1` and `git push origin v1.2.0`;
  (20) rewrite this section with the real run id, job results, and tag, and
  commit it as `docs: record release 1.2.0 handoff`. Do not tag before that
  run is green.
- The qualification invalidation invariant still governs: any production,
  build, or test-source edit before the tag invalidates the recorded
  implementation SHA, the signed APK, the review, and every connected, manual,
  and signed result collected after it. The danger case is concrete — if the
  re-run comes back red and needs a fix while unrelated work sits on the head,
  a clean 1.2.0 can no longer be produced.
- **The six open Dependabot PRs (#14–#19) must wait for the tag.** Every one is
  a `libs.versions.toml` or workflow edit, so merging any of them before the
  tag restarts the qualification chain. Merge them as one batch afterwards.
- After the tag, the ordered remaining work is Stage 9 (the single Room v10
  wave) and then repository-wide Unicode tag-identity hardening. **Stage 9's
  documentation phases are complete on the `stage-9` branch** (see the
  "Stage 9 planned" checkpoint below): brainstorm rulings, the approved
  design spec, and the reviewed implementation plan, all docs-only, with
  `main` still pinned at the candidate. Stage 9 *implementation* waits for
  `v1.2.0`; the chosen execution mode is subagent-driven development.
- Preserve the unrelated user state exactly: modified
  `docs/superpowers/plans/2026-07-30-stage-3-google-drive-backup-recovery-plan.md`,
  deleted
  `docs/superpowers/specs/2026-08-10-pinfo-thai-dashboard-design.md`, and
  untracked `.kotlin/` plus `artifacts/`. The detailed execution ledger is the
  ignored
  `.superpowers/sdd/2026-08-14-stage-8-planning-surfaces-plan/progress.md`.

## Stage 9 planned — spec and implementation plan on `stage-9` — 17 August 2026

Stage 9 (board flow and automation, the single Room v10 wave) finished
its three documentation phases on the `stage-9` branch, cut from the
checkpoint commit so `main` stays pinned at the release candidate. Both
documents are user-approved:

- Spec (`a83b95c`):
  `docs/superpowers/specs/2026-08-17-stage-9-board-flow-automation-design.md`.
  Brainstorm rulings recorded there: rule menu trimmed to three
  (auto-remove completed from My Day; on-enter add-tag / add-to-My-Day /
  set-due; stale marking), stale as a pure projection with no write,
  My Day persistent with dimmed completed and a rollover sweep,
  Home's Today-focus replaced by My Day, subtasks one level deep with
  confirm-on-parent-completion and subtree binning, rule evaluation
  repository-internal in the trigger's transaction and generation, and
  dual-arity WORKFLOW_STATUS records inside backup format v1.
- Plan (`8a90458`):
  `docs/superpowers/plans/2026-08-17-stage-9-board-flow-automation-plan.md`.
  Seventeen tasks: Tasks 1–5 are the v10 wave (migration with a stamped
  row marker, dual-arity amendment, `AUTOMATION_RULE` and `MY_DAY`
  families appended after `TOMBSTONE`, My Day and rule commands in both
  engines), then WIP limits, subtasks, My Day surfaces, engine plus
  stale plus editor, and qualification ending in sideload release
  1.3.0. A verification pass against the live code fixed two critical
  drafting defects before commit: the migration byte-capture must read
  `workflow_statuses` with explicit v9 columns (the `SELECT *` helper
  cannot byte-match across `ADD COLUMN`), and the engine's composed
  undo must flatten a trigger `UndoBatch` and extend
  `rejectUndoCommand` (which fails closed on unknown undo shapes) with
  `RemoveTaskFromMyDay`.

**Execution decision (user, 17 August): subagent-driven development** —
fresh implementer subagent per task with independent review between
tasks; implementers must not spawn further subagents.

The plan's start gate governs the resume: tag `v1.2.0` first (billing
lock above), merge Dependabot #14–#19 with green checks, merge
`stage-9` into `main`, record the audit base SHA in the plan's ignored
ledger, then dispatch Task 1 via superpowers:subagent-driven-development.
Until that gate clears, no production, build, or test-source edit.

## Superseded checkpoint — reviews and host gates green, one connected failure open, 16 August 2026

This section was authoritative at that checkpoint. It is retained only for
chronological context and is superseded by the checkpoint above.

- All review obligations are discharged at head `b6c438f`. The scoped
  independent review of `5fbb24b..94c42f7` (the `c495dd2` Quick Add
  close-consumption fix plus the `94c42f7` fold-continuity fixture
  backup-object cleanup) is APPROVED — spec PASS, quality PASS, 0 Critical.
  The mandatory literal whole-stage review of `8047f136..b6c438f` (13,908
  diff lines read fully) returned **Ready to tag WITH FIXES — 0 Critical,
  no new Important**: its sole Important is the already-queued Step 16
  contract-docs refresh (`step16-drafts/`, anchors pre-verified), which
  must land before candidate CI/tag. Scope audit, dual-engine parity,
  privacy, spec conformance, and test integrity all PASS, re-verified
  independently against git. Four new deferred Minors joined the ledger
  (Tasks start/due TimePickerDialog window-leak shape at
  `TasksScreen.kt:1799/:1881`; MonthCalendar 20 dp paddings; Quick Add
  grammar's memoized projection clock; digest NOTIFICATION_ID derivation),
  plus three from the scoped review (process-death intent redelivery,
  shared by all three consumers; untested `EXTRA_OPEN_QUICK_ADD` removal
  line; fixture leaves `remoteTransferRoot` by design). The reviewed
  `implementationHeadSha` is `b6c438f`.
- Forced-fresh Steps 2/3/4/6/7 are all green at `b6c438f`: six androidTest
  source sets compile (219 tasks); full gate 553/553 with 1,304 JVM tests
  across 127 suites; release 442/442 with `verify-release-apk.sh` all
  checks passed (signed 1.2.0/3); schema/fixtures/workflow/diff-check
  clean; scope audit `STEP7-ALL-GREEN` (85 changed files, manifest delta
  exactly the 3-line non-exported DailyDigestReceiver, permissions
  byte-identical, digest prefs typed-only). Evidence: `step7-b6c438f/`.
- **Open failure, not diagnosed:** Step 11 attempt 16 (fresh audited
  headless host-GPU overlay, unfolded 2160x1856 @420, display-0 focus,
  canary passed) executed all 453 connected tests: 450 passed, 0 errors,
  exactly the 2 established skips, and ONE failure —
  `ScheduleScreenInstrumentedTest.completedTaskIsNotADragSource`, a
  bounded 64.2 s `AppNotIdleException` (Espresso looped 60 s on
  MAIN_LOOPER_HAS_IDLED; last message a Choreographer frame callback —
  the renderer frame-churn signature that `HideWindowsRule` converts from
  an eternal hang into a bounded failure). Every other module was fully
  green: core:data 192/192, tasks 48/48, projects 30/30, More 68/68, app
  90+2 skips; schedule 22/23. The same class passed 23/23 in attempts 9
  and 13 and no source changed since the attempt-15 green runs; the run
  took 25m06s versus the usual ~10.5m, so environmental renderer
  starvation is the first suspect — but no diagnosis exists. Evidence:
  `attempt16-evidence/` in the ignored SDD workspace (six authoritative
  XMLs, full Gradle log, post-run logcat, start/stop scripts). The
  overlay was secure-stopped (`OVERLAY-STOPPED-CLEAN`) with a clean final
  audit; no signed APK or device credential existed this attempt.
- Resume in this order (the ledger tail carries the same sequence):
  1. Diagnose the `completedTaskIsNotADragSource` bounded AppNotIdle
     failure (read the preserved logcat around the 64 s window first).
     If no edit results and a fresh-overlay rerun of the complete
     six-module gate is green, record a controller ruling accepting that
     rerun as Step 11 — the `b6c438f` host gates remain valid because no
     source changed. Any edit instead restarts from scoped review per
     the invalidation invariant, then forced-fresh Steps 2/3/4/6/7,
     before a fresh overlay and the full 453-test gate.
  2. Step 12 manual matrix and Steps 13–14 signed smoke on the same
     overlay as the green gate, including the exact cancelled-Quick-Add
     → app lock → digest-tap row that found the original blocker.
  3. Step 15 secure cleanup and overlay destruction.
  4. Steps 16–17 qualification docs from `step16-drafts/` with evidence
     numbers (discharges the whole-stage review's Important), Step 18
     exact-candidate GitHub CI, Step 19 tag `v1.2.0`, Step 20 release
     handoff.
- The qualification invalidation invariant governs: any production or test
  edit before the tag restarts the chain from the review steps. With this
  checkpoint, local `main` is 51 commits ahead of `origin/main`; nothing
  is pushed and no `v1.2.0` tag exists. Release 1.2.0 remains unqualified.
- Preserve the unrelated user state exactly: modified
  `docs/superpowers/plans/2026-07-30-stage-3-google-drive-backup-recovery-plan.md`,
  deleted
  `docs/superpowers/specs/2026-08-10-pinfo-thai-dashboard-design.md`, and
  untracked `.kotlin/` plus `artifacts/`. The detailed execution ledger is
  the ignored
  `.superpowers/sdd/2026-08-14-stage-8-planning-surfaces-plan/progress.md`.

## Superseded checkpoint — repairs reviewed, whole-stage review next, 16 August 2026

This section was authoritative at that checkpoint. It is retained only for
chronological context and is superseded by the checkpoint above.

- The signed-smoke release blocker is repaired and committed at `c495dd2`
  (`fix: consume quick add trigger on close`), exactly four files. Closing
  the Quick Add sheet now consumes the whole trigger at the single
  `closeQuickAdd()` boundary through a new `onQuickAddClosed` callback:
  `MainActivity` resets `quickAddSignal` and the prefill, removes the legacy
  `EXTRA_OPEN_QUICK_ADD` extra, and clears only a matching QUICK_ADD, SEND,
  or PROCESS_TEXT action from the stored intent. Prefill consumption stays a
  separate callback, so a sheet still open when app lock disposes the
  workspace keeps its armed signal and survives the remount, exactly as
  before. The repair was test-first (attempt-14 RED/GREEN evidence): the new
  regressions are
  `cancelledExplicitQuickAddDoesNotReopenWhenTheWorkspaceRemounts`
  (replica-level, `QuickAddPrefillRootWiringInstrumentedTest`) and
  `cancelledQuickAddIntentDoesNotReopenAfterRecreation` (behaviour-level,
  `FoldContinuityInstrumentedTest`, beside the `ee13170` digest precedent).
- The attempt-14 full-class alias-wait failure is **diagnosed and fixed** at
  `94c42f7` (`test: clean owned backup objects in continuity fixture`),
  exactly one androidTest file. Root cause, proved by a controlled on-device
  experiment: the fold-continuity fixture teardown deleted the vault
  database, keys, aliases, and slot marker but left the owned session's
  local backup object (`no_backup/backup/v1/current/snapshot-0.otf`)
  behind; the next vault-owning test's content-key bootstrap then saw the
  residue, `requiresEstablishedContentKey` turned true, `openExisting`
  failed against the deleted key, and `bootstrapKeyOnce` never retries — so
  the content alias never appeared and `awaitCreatedVault` timed out. That
  is correct fail-closed product behaviour; the defect was fixture-side.
  The fix deletes the owned local backup root at teardown, refuses
  pre-existing backup objects in the storage baseline, verifies absence
  after cleanup, and gives the post-Active alias wait its own 10 s deadline
  (the deadline split alone was proven insufficient). On a fresh audited
  attempt-15 overlay the exact previously failing two-class order passed:
  11 tests, 0 failures, 1 established fold skip; evidence in
  `attempt15-evidence/`; overlay secure-stopped with a clean audit.
- Scoped independent review of `5fbb24b..94c42f7` is **APPROVED** — spec
  compliance PASS, quality PASS, 0 Critical. Its one Important finding (this
  document's staleness) is discharged by this checkpoint. Three minors are
  recorded for the final review: true process death from Recents still
  redelivers a consumed Quick Add/digest/reminder intent (pre-existing,
  consistent across all three consumers; ActivityScenario.recreate cannot
  cover it); the `EXTRA_OPEN_QUICK_ADD` removal line has no dedicated
  regression; fixture teardown deliberately leaves `remoteTransferRoot`
  (only credentialed flows write it). The reviewed head is now `94c42f7`;
  the local gate there is green (553/553, `git diff --check` clean). With
  this checkpoint, local `main` is 50 commits ahead of `origin/main`;
  nothing is pushed and no `v1.2.0` tag exists. Release 1.2.0 remains
  unqualified.
- Resume in this order (the ledger tail carries the same sequence):
  1. The pending literal whole-stage review of `8047f136..HEAD`.
  2. Forced-fresh Steps 2/3/4/6/7 and a refreshed reviewed
     `implementationHeadSha`.
  3. Fresh audited overlay and the complete six-module connected gate
     (now 453 tests).
  4. Manual matrix and signed smoke, including the exact
     cancelled-Quick-Add → app lock → digest-tap row that found the
     blocker.
  5. Step 16 qualification docs from `step16-drafts/` with evidence
     numbers, exact-candidate GitHub CI, tag `v1.2.0`, release handoff.
- The qualification invalidation invariant governs: `c495dd2` and `94c42f7`
  already invalidate all prior forced-fresh, connected, manual, and signed
  evidence, and any further production or test edit before the tag
  restarts the chain again from the review steps.
- Preserve the unrelated user state exactly: modified
  `docs/superpowers/plans/2026-07-30-stage-3-google-drive-backup-recovery-plan.md`,
  deleted
  `docs/superpowers/specs/2026-08-10-pinfo-thai-dashboard-design.md`, and
  untracked `.kotlin/` plus `artifacts/`. The detailed execution ledger is
  the ignored
  `.superpowers/sdd/2026-08-14-stage-8-planning-surfaces-plan/progress.md`.

## Superseded checkpoint — Stage 8 release qualification paused at signed-smoke blocker, 16 August 2026

This section was authoritative at that checkpoint. It is retained only for
chronological context and is superseded by the repair checkpoint above.

- Stage 8 Tasks 1–14 are complete. Task 15 is not complete, so release 1.2.0
  is not qualified, tagged, or pushed. The Stage 8 implementation head is
  `5fbb24b2daca0ea572a50e64178c892510fa6efd`; with this handoff checkpoint,
  local `main` is 46 commits ahead of `origin/main`
  (`ea7cd310e33b53c2448d72723bb3d742d9d2397a`). No `v1.2.0` tag exists.
- At `5fbb24b`, the final forced-fresh host gates were green: all six
  androidTest source sets compiled (219 tasks); the full local gate completed
  553/553 tasks with 1,304 JVM tests across 127 suites; the release build
  completed 442/442 tasks; Room v9, all five fixture families, workflow, scope,
  privacy, and release-APK verification checks passed. Every recorded Critical
  or Important finding was discharged by scoped re-review. A final literal
  whole-stage review of `8047f136..5fbb24b` was not durably recorded before
  the smoke blocker and remains mandatory after the repair.
- The final connected attempt executed 451 tests: 449 passed, zero failed,
  zero errored, and exactly two established skips (credential-only Drive and
  the unavailable native fold transition). Module totals were core:data
  192/192, app 88 passed plus 2 skips, projects 30/30, schedule 23/23, tasks
  48/48, and More 68/68. Evidence remains under the ignored
  `attempt13-final-evidence/` directory.
- All executable Step 12 manual rows were exercised. Rotation and true process
  recreation passed; this Fold8 AVD exposes no usable native fold posture, so
  native fold is recorded as environment-blocked and is not claimed as a
  pass. These results are diagnostic, not final release evidence, because the
  later signed smoke found a blocker.
- The signed/minified APK at this head is version 1.2.0 (3), 16,372,751 bytes,
  SHA-256
  `d631cb847c53c461cc2808caaac7da2c703ddf95170593ac81a6e13b7e3e3c4d`.
  Signed smoke passed vault creation/persistence, encrypted `.otvault`
  export/import, Month move plus Undo, Timeline chain summary plus Open,
  immediate app lock, the Today widget (`3 open today · 5 overdue`), and the
  real launcher Quick Add shortcut. Digest rendering also passed: private
  `Today` / `3 open today · 5 overdue`, generic public `Daily digest` /
  `Open Open Tasks to view it`, with no task-title leakage.
- **Release blocker:** after opening and cancelling launcher Quick Add, then
  backgrounding behind immediate app lock, tapping the signed digest and
  authenticating selected Home underneath but reopened the already-consumed
  empty Quick Add sheet. This violates the signed-smoke requirement that a
  digest tap lands on Home. The existing APK must not be distributed or
  tagged.
- Root cause is confirmed and no source fix has been applied yet.
  `MainActivity.onQuickAddConsumed` clears only the prefill while
  `quickAddSignal` stays armed. App lock disposes `OpenTasksApp`; remounting it
  reruns `LaunchedEffect(quickAddSignal)` and reopens the sheet. The Activity's
  matching Quick Add/share/process-text intent token can also survive
  recreation.
- Minimum safe repair on resume: consume Quick Add at the existing common
  `closeQuickAdd()` boundary. Add an `onQuickAddClosed` callback to
  `OpenTasksApp`; from `MainActivity`, reset the signal/prefill, remove the
  legacy Quick Add extra, and clear only matching Quick Add, SEND, or
  PROCESS_TEXT actions. Keep the current prefill-consumed callback so an open
  externally launched sheet still survives lock/recovery remount. Add one
  focused cancel→unmount/remount regression and one Activity recreation
  regression. Do not patch the digest path or add sleeps/retries.
- Any production or test edit activates Task 15's invalidation rule: repeat
  the whole-stage review and forced-fresh Steps 2/3/4/6/7, start a new audited
  overlay, rerun the 451-test connected gate, then repeat the manual and signed
  smoke evidence before qualification docs, candidate CI, and tagging.
- Secure stopping point is complete. The temporary device credential is
  removed (`CredentialType: NONE`), the exported vault and exact temporary
  screenshots/UI dumps are deleted, digest has no active alarm/notification,
  emulator PID 51190 is gone, ADB reports zero targets, and
  `/tmp/opentasks-stage8.QM8w4q` is absent. Those deletions are unrecoverable;
  the read-only/no-snapshot emulator retained no state.
- Preserve the unrelated user state exactly: modified
  `docs/superpowers/plans/2026-07-30-stage-3-google-drive-backup-recovery-plan.md`,
  deleted
  `docs/superpowers/specs/2026-08-10-pinfo-thai-dashboard-design.md`, and
  untracked `.kotlin/` plus `artifacts/`. The detailed local execution ledger
  is the ignored
  `.superpowers/sdd/2026-08-14-stage-8-planning-surfaces-plan/progress.md`.

Resume in this order: implement the focused Quick Add close-consumption fix
test-first, obtain scoped review, restart the invalidated qualification chain,
repeat signed smoke, then update the eight qualification documents, run exact-
candidate GitHub CI, create `v1.2.0`, and finally record the release handoff.
Pause now; do not push or tag from this checkpoint.

## Superseded checkpoint — Stage 8 Task 15 paused mid-qualification, 15 August 2026

This section was authoritative at that checkpoint. It is retained only for
chronological context and is superseded by the 16 August checkpoint above.

- Stage 8 began from `8047f136541d22b15ca20db8971ea67685e250b5` and Tasks
  1–9 end at `5074c10ef1f2150f8bbb4e786c054ae20ac4ee9f` (`5074c10`). All
  nine tasks are committed and independently reviewed; every Critical or
  Important finding was fixed and scoped re-reviewed. Task 9 is
  spec-compliant and Approved with no Critical or Important findings.
- Task 9 layered long-press pointer drag over the Task 8 non-drag fallback:
  expanded Week drags between its seven day columns and the tray; Month
  registers its 42 visible cells and sources individual agenda/tray rows;
  compact Week stays tap/menu only by design. It reuses the Task 6 root-drag
  primitives and the Task 8 `onRescheduleTask`/`onRemoveTaskSchedule`
  callbacks; only open non-binned tasks drag, recurring tasks cannot target
  the tray, same-source/outside drops snap back without a callback, and the
  preview is unclipped and RTL-safe. A recorded controller ruling allowed a
  fourth staged file beyond the task manifest: the Task 8 confirmation
  `AlertDialog` was hoisted verbatim into one internal
  `RemoveScheduleConfirmation` in `ScheduleReschedule.kt` so the
  tray-with-reminder drop opens the same dialog as the menu path — one
  definition, unchanged strings, tags, and menu behaviour.
- Execution resumed on 15 August 2026 by user instruction. Task 10 is
  complete and Approved with no Critical or Important findings at `3530648`
  (`feat: save project planning views`): per-project planning state —
  LIST/BOARD/TIMELINE presentation, Monday-only Timeline anchor, and Timeline
  task selection — now lives in `SavedStateHandle` through
  `WorkspaceProjectViewState`, replacing the Board boolean state. Legacy
  `projectBoardModeIds` restores as BOARD, decoding is fail-closed (Timeline
  wins a corrupt dual-mode row), LIST defaults and null selections are never
  persisted, and `OpenTasksApp` derives unchanged LIST/BOARD behaviour.
- Task 11 is complete at `17694db` after one fix round (Approved; the fix
  separated the clipped-edge continuation chevron from the completed/blocked
  status icon into side-by-side slots with combined-cue coverage). It ships
  the read-only 84-day `ProjectTimelineView` (Gantt-lite): dot-run spans via
  the unchanged `DotRunBar`, start/due marker icons, invalid/outside/
  unscheduled states, clipped-edge continuation, 48 dp milestone diamonds
  with exact before/after counts, and merged non-colour row semantics.
  `ProjectsScreen` now branches LIST/BOARD/TIMELINE on `ProjectPresentation`
  with three 48 dp segmented actions (`boardMode` Boolean plumbing is gone);
  `OpenTasksApp` computes `computeProjectTimelineProjection` for the selected
  project, navigates by exactly four weeks, and resolves Today/default
  anchors through the injectable zone provider at action time. Timeline
  Compose tests and extended restoration/zone regressions are compile-only
  until Task 15.
- Task 12 is complete and Approved with no Critical or Important findings at
  `f91a1bb` (`feat: plan private daily digests`): `DailyDigestSystem.kt`
  holds the exact `daily_digest` preference boundary (keys `enabled`,
  `minute_of_day`, `last_handled_epoch_day`, Boolean/Int/Long only,
  raw-type validation via `prefs.all`, fail-closed rewrite to
  disabled/08:00 retaining only a valid handled day, disable writes only
  `enabled`), the brief-exact wall-time `nextDailyDigestOccurrence` (Java
  gap/overlap DST authority, handled-day bound on a rewound clock), and
  `dailyDigestNotificationPlan` calling `computeTodayProjection` once with
  `titlesPermitted = false` and carrying counts only. One singleton store
  provider joined `AppModule`; no alarm, notification, receiver, manifest,
  dependency, or permission surface was touched (that is Task 13). The
  reviewer independently re-derived the Europe/London DST instants and
  re-verified compile/tests with a forced clean rebuild (11/11).
- The controller re-ran the full gate
  `./gradlew testDebugUnitTest lintDebug :app:assembleDebug` at the Task 12
  checkpoint head `f91a1bb`: BUILD SUCCESSFUL in 34s, 553 actionable tasks
  (24 executed, 529 up-to-date); the run is recorded in the ignored ledger.
- Execution resumed again on 15 August 2026 by user instruction. Task 13 is
  complete and Approved with no Critical or Important findings at `5e5ff38`
  (`feat: deliver private daily digests`): `DailyDigestSystem.kt` gained
  `DailyDigestIntents`, the one stable immutable broadcast PendingIntent in
  `DailyDigestScheduler` (`setAndAllowWhileIdle` only), the private/public
  `DailyDigestNotifier` on its own `daily_digest` channel, the mutex-held
  `DailyDigestCoordinator` enforcing mark → re-arm → vault → post with
  `Provider<VaultRepository>` (never `Lazy`), and the action/data-validating
  `goAsync` receiver. The digest channel joins reminder-channel creation in
  `OpenTasksApplication`; `ReminderSystemEventReceiver` reconciles the
  digest before its lazy vault lookup; the manifest delta is exactly the
  one non-exported no-filter `DailyDigestReceiver`; only the three
  permitted strings were added. The ordering, mutual-exclusion, and
  vault-refresh host tests observe the real store write through a
  preference listener (16/16 green); the five instrumented tests are
  compile-only until Task 15. The digest stays dormant (default off) and
  `OPEN_DAILY_DIGEST_HOME` unconsumed until Task 14's toggle and
  MainActivity wiring.
- Task 14 is complete and Approved with no Critical or Important findings
  at `92bb191` (`feat: configure daily digest in More`): six primitive
  digest parameters joined the stateless `MoreScreen` with an inline
  setting row in the existing overview list (no new route), an
  enabled-only `HH:mm` row whose 48 dp button launches the native 24-hour
  `TimePickerDialog` emitting `hour * 60 + minute` (the store's range
  guard is unreachable from the UI), and notification guidance that never
  gates the switch. `MainActivity.onStart` reconciles the digest and
  initialises the vault in independent `runCatching`s; `handleIntent`
  recognises only `OPEN_DAILY_DIGEST_HOME` and increments
  `openHomeSignal`, consumed only inside the unlocked Active workspace
  arm; `OpenTasksApp` collects coordinator settings lifecycle-aware,
  recomputes notification availability from `permissionStateVersion`, and
  navigates Home via the brief's literal `LaunchedEffect`.
  `PrivacyToggleRow` was renamed `SettingToggleRow` (three call sites,
  behaviour unchanged). The four named instrumented tests are
  compile-only until Task 15.
- The Tasks 1–8 documentation checkpoint remains
  `ba88821c7b5779ce4f1521e19c89e85a66b86c44` with its correction at
  `cd773d148295469063ea86547c08d7caad71246d`; this Tasks 1–9 checkpoint
  commit follows `5074c10`.
- Landed scope is the pure 42-cell Month projection; exact move rules; pure,
  bounded Timeline projection vocabulary and computation; atomic dual-engine
  `SetTaskSchedule` and start-aware `UpdateTask`; start/due editor controls;
  shared root drag mechanics with Board migrated; stateless Week/Month UI and
  a live-zone root clock; the complete accessible non-drag rescheduling
  fallback; and pointer drag rescheduling layered over that fallback. Undated
  day placement remains due at 18:00 in the current device zone; the editor's
  new due-date default remains 17:00 by design.
- Baseline `./gradlew testDebugUnitTest lintDebug :app:assembleDebug` passed
  before Task 1, every task passed its focused JVM/compile gate, and the
  controller re-ran the full gate green at the `5074c10` checkpoint (BUILD
  SUCCESSFUL, 553 actionable tasks; the run is recorded in the ignored
  ledger). Instrumented regressions were compile-only: no device or
  connected test ran. Task 9's eight drag tests exist as instrumented
  sources and are compile-verified only; their RED phase was runtime-shaped
  and honestly unobservable under the no-device rule.
- Carry-forward Task 15 obligation: expanded Week reserves a 280 dp
  unscheduled tray, so `expandedWeekDragMovesDatedTaskBetweenDays`, which
  needs two day columns visible at once, requires a wide-window/tablet leg
  and is expected to fail on a phone-width leg — layout geometry, not drag
  wiring.
- This is an in-development, unqualified checkpoint. There is no whole-stage
  review, Task 15 qualification, signed APK, release bump, GitHub CI, tag, or
  release evidence. The app remains 1.1.0 (versionCode 2); Room remains v9 and
  backup v1. No schema, backup format/family, fixture, dependency, permission,
  manifest, Drive scope, or route changed in Tasks 1–9.
- Thirty-six non-blocking deferred Minors are recorded in the ignored
  ledger: six from Task 14 (a sticky never-consumed `openHomeSignal` that
  can misroute a later reminder tap to Home after an app-lock
  recomposition — brief-prescribed shape, triage at the final review; the
  click-launched TimePickerDialog leaks its window on configuration
  change; a hard-coded English guidance-copy assertion; picker seeding
  untested off the default; no app-side wiring test; `onStart`'s
  sequential `runCatching` pair swallowing `CancellationException`);
  six from Task 13 (untested zero-count and SecurityException
  no-retry paths; digest reconcile inside the reminder receiver's shared
  IllegalStateException catch; an unguarded throwing setMinuteOfDay
  boundary; a third near-verbatim per-surface notification-channel helper;
  post-midnight Doze deferral consuming the next day's digest by design;
  channel-name/public-title literal duplication);
  four from Task 12 (a titles-suppression test that cannot
  behaviourally distinguish the flag; uncovered `store.state` wiring;
  missing handled-day/minute boundary tests; out-of-range `markHandled`
  resetting unrelated valid state untested and undocumented);
  four from Task 11 (no in-Timeline deselect; awkward outside-window
  milestone copy; requireNotNull trust in the projection invariant; a
  single-visible-day clipped+status span can overflow its day-cell box);
  two from Task 10 (decode-path test-coverage gaps: untested
  selections-dedup/anchor-ordering branches and the position-preserving
  non-string paired-list decode); six from Tasks 1–8 (an unused move-test zone constant; binned
  Timeline-selection coverage; a content-safe Room `ByteArray` helper; two
  editor-test strengthening notes; a Month agenda plural resource) and eight
  from Task 9 (per-move non-skippable Month/Week recomposition matching the
  Board precedent; unbounded `targetBounds` growth across navigation; the
  drag-opened confirmation using `remember` while the menu path uses
  `rememberSaveable`; a density-dependent RTL-preview test delta; a
  state-map-vs-plain-map choice; a latent compact-Week `dragSource` value
  beside its null binding; uncovered confirmation-dismiss and
  recurring-tray-eligibility branches; a duplicated `startOfWeek()`
  computation). Hand them to the final whole-branch review.
- **Task 15 is mid-flight, PAUSED by user instruction (usage budget).**
  Completed so far, all recorded in the ignored ledger:
  - Version 1.2.0 (versionCode 3) committed at `79afd97`. The whole-stage
    review over `8047f13..79afd97` returned zero Critical and two
    Important findings; both are discharged — the sticky
    `openTaskSignal`/`openHomeSignal` consumed-callback fix landed at
    `5426a2f` (scoped re-review clean), and the contract-docs refresh is
    Step 16 work with all seven drafts already written and independently
    anchor-verified in the workspace `step16-drafts/` directory.
  - Connected attempt 1 hung 96 minutes: on this AVD (`hw.gpu.mode=auto`)
    the plan's `-no-window` flag selects SwiftShader software rendering,
    whose missed frame budgets make `ThreadedRenderer` demand a redraw
    every frame, so the main looper never idles and the Compose rule's
    unbounded teardown `waitForIdleSync` blocks forever (AndroidX's own
    sources document the hazard). Fixed test-side by `HideWindowsRule`
    guarding all 31 compose instrumented classes (`9f17bd6` + `04b2ee4`,
    two review rounds, closed clean). Standing ruling: overlays launch
    with `-gpu host` added to the plan's pinned flags.
  - All forced-fresh gates re-ran green at the refreshed implementation
    head `04b2ee4`: 553/553 full gate, release build + verifier, schema/
    fixture/workflow gates, and the scope audit (one recorded harness
    ruling narrows its no-new-Log check to production sources; the only
    added Log lines in the whole range are content-free lines in the
    test-only guard).
  - Connected attempt 2 on the host-GPU overlay ran clean of hangs:
    450 tests, 423 passed, 0 errors, exactly the two established skips —
    and **27 real failures, every one in a never-device-run Stage 8
    test**, in six clusters: `core:data` 2 (the `SetTaskSchedule`
    journal/Undo parity pair — highest stakes), `feature:schedule` 12
    (drag previews not displayed, null drop callbacks, plus non-drag
    agenda/semantics failures and one bounded 60 s AppNotIdle),
    `feature:tasks` 5 (TaskEditor start/due tests), `feature:more` 4
    (digest settings `performScrollTo` cannot find LazyColumn items —
    suspected test-side), `app` 3 (a `setContent`-already-set possibly
    from the new guard's rule nesting; a zone-provider restoration null;
    digest channel `lockscreenVisibility` -1000 vs expected PRIVATE), and
    `feature:projects` 1 (Board RTL preview geometry 536 vs 225).
  - Both overlays were secure-stopped and audited clean; no emulator,
    ADB target, or temp file remains. The Task-12-era protected state is
    untouched.
- **Resume here:** durable evidence and the exact resume sequence live in
  the ignored ledger
  (`.superpowers/sdd/2026-08-14-stage-8-planning-surfaces-plan/progress.md`,
  tail section) and its `attempt2-evidence/` directory (failure messages,
  full Gradle log, SIGQUIT trace, amended audit/overlay scripts, and the
  ready six-cluster diagnosis workflow). Sequence: six-cluster read-only
  diagnosis → one consolidated fix wave → scoped re-review → forced-fresh
  gates → fresh `-gpu host` overlay → rerun the connected gate → manual
  matrix, signed smoke, cleanup, docs (drafts ready), CI push, tag.
  Carried device-proof obligations remain: digest channel properties,
  receiver export scope, notification public/private split,
  delivery-intent identity, the espresso TimePicker leg, and the
  wide-window `expandedWeekDragMovesDatedTaskBetweenDays` geometry.
  Stage 7's waivers do not carry into Stage 8. The qualification
  invalidation invariant governs: any production/test edit before the tag
  restarts Steps 8–9 review and the forced-fresh/device evidence chain.
- Preserve the unrelated user dirty state: the modified historical Stage 3
  plan, deleted pinfo spec, `.kotlin/`, and `artifacts/`. The ignored execution
  ledger is `.superpowers/sdd/2026-08-14-stage-8-planning-surfaces-plan/progress.md`.

## Stage 8 detailed design approved and implementation plan ready — 14 August 2026

The user approved all five Stage 8 design sections. The durable authority is
`docs/superpowers/specs/2026-08-14-stage-8-planning-surfaces-design.md` under
the approved Stage 7–9 roadmap. No product code, schema, backup format,
permission, dependency, route, or Drive scope changed during design.

Pinned rulings:

- Integrate into existing surfaces: Week/Month in Schedule,
  List/Board/Timeline in Projects, and inline daily-digest settings in More.
- An undated task dropped onto a day becomes due at 18:00 in the device's
  current local zone. A due-only task stays due-only and preserves its local
  time and stored zone.
- Month is a Monday-first 6×7 projection with a six-dot visual cap, exact
  text semantics, start-else-due placement, and the existing selected-day
  agenda/tray rather than a parallel store.
- One exact dual-engine single-task schedule command owns start/due/reminder
  mutation and Undo. Existing bulk due-only rescheduling remains unchanged.
- Gantt-lite is a read-only, navigable 12-week project projection with dot-run
  spans, milestone diamonds, blocked markers, and in-project transitive
  prerequisite/dependant highlighting; no arrows or bar drag.
- Daily digest is device-local, off by default, defaults to 08:00 local, uses
  one inexact alarm and its own notification channel, computes counts through
  `computeTodayProjection(..., titlesPermitted = false)`, and always exposes
  generic lock-screen copy.
- Stage 8 exits as signed sideload release 1.2.0 / versionCode 3 after the
  roadmap's complete review, connected, repository, privacy, qualification,
  and release gates.

The committed implementation authority is
`docs/superpowers/plans/2026-08-14-stage-8-planning-surfaces-plan.md`. It has 15
dependency-ordered, test-first tasks: Tasks 1–14 are independently
implemented/committed/reviewed, and controller-owned Task 15 performs the sole
device, signing, cleanup, GitHub Free CI, tag, and release-record gates. Stage
7's app-lock, cleanup, and CI waivers do not carry forward.

Resume by reviewing/approving the plan and choosing either
`superpowers:subagent-driven-development` (recommended) or
`superpowers:executing-plans`; do not start product code before that choice.
Preserve the unrelated Stage 3 plan edit, deleted pinfo spec, `.kotlin/`, and
`artifacts/`.

## Historical context

- Last updated: 10 August 2026
- Branch: `main` at Stage 7 Phase 0 commit `7638037`, with the implementation
  plan checkpoint at `2049581`, planning pause at `80ec470`, and approved
  design at `3b1a3c4`. All four are local and unpushed; `main` is ahead of
  `origin/main` by four. Task 1 changed only its six ruled model/domain/
  Tasks/app paths. Preserve the pre-existing user-owned Stage 3 plan edit,
  `.kotlin/`, and `artifacts/`.
  **Release 1.0.0 is shipped**: tag `v1.0.0` sits on `57703d2`. The
  completed run at `c74a435` confirmed the expected post-F6 shape —
  `verify`, compact API 36, and `release` green, only expanded API 37.0
  red. There are no open issues. Six Dependabot PRs (#14–#19) are open; all
  await a usable CI runner.
- **Stage 6 is complete and qualified.** Tasks 1–14, the whole-stage
  review, its six-finding consolidated fix wave, the focus-aware manual-Stop
  amendment, the connected-gate fix, all repository/release/privacy gates,
  and the documented disposable-device checklist are closed. Authority:
  `docs/superpowers/specs/2026-08-08-stage-6-daily-flow-design.md`,
  `docs/superpowers/specs/2026-08-09-ember-launcher-icon-design.md`, and
  `docs/superpowers/plans/2026-08-08-stage-6-daily-flow-plan.md`. The
  qualification record is `docs/qualification/stage6-daily-flow.md`; the
  ignored execution ledger remains
  `.superpowers/sdd/2026-08-08-stage-6-daily-flow-plan/progress.md`.
- Historical Phase-0 backlog (superseded; retained for context):
  1. **Restore GitHub Actions execution.** Push run
     [`31344561176`](https://github.com/ksdaklmk/open-tasks/actions/runs/31344561176)
     assigned no runner and executed zero steps for verify, compact API 36,
     and expanded API 37.0. GitHub annotated each job: recent account payments
     failed or the spending limit must be increased. Release was skipped.
     Resolve Billing & plans, then rerun; this is infrastructure failure with
     no product or test signal. Later docs-only pushes repeat the same
     zero-runner failure until billing is restored.
  2. **Dependabot queue.** PRs #14–#19 cover SQLite, setup-java, Gradle,
     SQLCipher, KSP, and coroutines updates. Review them only after Actions can
     execute; reruns cannot provide a fresh signal while the billing block
     remains.
  3. **Tasks date-chip defect — now a hard Stage 7 prerequisite.**
     `TasksScreen.kt:255`: the Overdue chip filters by
     `priority >= HIGH`, Today matches any task with a due date, and
     Upcoming matches any task with a start date, so the Tasks tab has
     no genuine overdue view (widget, Home, Insights, and Weekly review
     compute overdue correctly). Fix is pure view-code date predicates —
     a shared date-bucket rule in `core:domain` — plus unit tests; no
     command, schema, or backup change. The approved Stage 7 spec
     requires this fix to expose buckets OVERDUE, TODAY, THIS_WEEK,
     LATER, NO_DATE (half-open, zone-aware, injected clock) with the
     **`DueBucket` enum in `:core:model`** (feature modules and
     `SearchQuery` must reference it) and only the classifier function
     in `core:domain`. Stage 7 consumes it; Stage 8's month view reuses
     it. Executable at any time, independent of the roadmap.
  4. **F6 observe-only** (ruling, 7 August 2026): the expanded API
     37.0 canary lane stays in the matrix and stays red until a healed
     canary image appears; the runner fetches the current canary, so
     it self-restores. Green signal = API 36 + verify + release. No
     action, just observe on natural runs.
  5. **Ctrl+K production-window blind spot** — parked: the deterministic
     Compose root wiring now passes without a skip, but CI still cannot prove
     that a real headless `MainActivity` Dialog window receives OS focus.
     Revisit only if the runner or assertion strategy changes.
  6. **Play Console** — externally pending and out of scope by the
     sideload-only ruling; revisit only if that distribution ruling changes.
  Future releases follow `RELEASING.md`'s per-release loop (bump
  `versionCode` by exactly 1, gate, build, verify, smoke on the
  disposable AVD, record, tag).
- Stage 6 planning history, 8 August 2026: the user chose the Stage 6 feature
  set as the post-release direction and selected all four brainstormed
  directions (capture, glance/focus, organize, review, interop) as one
  Stage 5-sized stage. Recorded rulings: own-schema CSV import only;
  Kanban drag-and-drop plus a complete tap-to-move fallback built
  first; the weekly review walks overdue, stale, unscheduled, and
  project health; focus cycles are the 25/5 and 50/10 presets only.
  Planning-time discovery (verified in code, amending the approved
  draft): the dormant Stage 1 `saved_views` table and its `SAVED_VIEW`
  backup family already exist end-to-end (entity, codec validation,
  capture attribution, recovery import) with no product plumbing, so
  Task 1 lights them up instead of adding Room v10 — the stage makes
  **no durable Room/backup schema change and no backup-fixture change**; the
  one subtle requirement is moving the `SAVED_VIEW` journal fingerprint from
  identity-only to content-based so renames journal. Four parallel
  read-only research agents mapped the command layer, `:app`
  surfaces, feature UI, and export/import before the plan was written;
  the plan pins exact signatures and `file:line` seams from those
  maps. The user instructed: finish the plan, update the handoff, and
  pause — execution had not started at that planning checkpoint.
- Release-stage polish discharged, 8 August 2026 (`90c8857`):
  RELEASING.md step 6 lists the explicit git add/commit commands;
  `verify-release-apk.sh` check 1 distinguishes a missing apksigner
  from an unsigned APK, and the dex scope scan pipes bytes straight to
  grep instead of holding NULs in a shell variable (verifier re-run
  green against the shipped 1.0.0 APK); the signing config names the
  missing `keystore.properties` key instead of an obscure NPE. The CI
  gate passed.
- The CI test-fix task closed on 7 August: the user authorised the
  Ctrl+K API 36 exception (`39d2dc5`), bench 2 (run `31190499051`)
  returned API 36 fully green — 293 pass, 0 failures, 3 skips — with
  `verify` and `release` green, and PR #13 was merged to `main` as
  `1cdab0b` with branch `test-fix` deleted.
- Standing residuals: **F6** — the expanded API 37.0 canary image still
  fails before tests run (credential-encrypted storage unavailable); it
  is an image-quality blocker, not a code defect. User ruling, 7 August
  2026: keep the matrix entry and observe only — the runner fetches the
  current canary, so a healed image restores the lane for free; expect
  the lane red until then and treat API 36 plus verify plus release as
  the green signal. **CI blind spot** — CI never proves Ctrl+K focuses
  the query field (the headless runner cannot grant Dialog window
  focus); only focus-capable device runs execute that assertion.
- Dependency maintenance, 7 August 2026: Dependabot queue cleared.
  Stale PRs #4/#5/#7/#8 were closed (their versions were already on
  `main` from the 30 July audit). lifecycle 2.11.0 landed as `81f644e`
  and actions/upload-artifact v7.0.1 as `f175ef6` (SHA verified against
  the upstream tag; workflow verifier green). CI run `31194601589`
  validated both: verify, API 36 (all six modules), and release green;
  all three report artifacts uploaded under v7; API 37.0 red on F6
  only. PRs #9 and #11 are closed; no open PRs or issues remain.
- Release 1.0.0, 8 August 2026: the user chose release preparation
  (signed sideload only) as the post-Stage-5 direction. The
  release-readiness stage is executed per
  `docs/superpowers/specs/2026-08-08-release-readiness-design.md` and
  its plan: guarded local signing (release signs iff untracked
  `keystore.properties` exists; CI stays unsigned and unchanged),
  version 1.0.0/versionCode 1, `scripts/verify-release-apk.sh`
  (fail-closed, five checks), and `RELEASING.md` (process plus smoke
  checklist). The signed APK passed all automated checks and a 7/7
  user-executed smoke run on the disposable read-only AVD — the R8
  build's first runtime proof. The keystore is held and backed up by
  the user outside the repo. The qualification record is
  `docs/qualification/release-1.0.0-sideload.md`; the released commit
  is tagged `v1.0.0`. The final whole-branch review returned Ready to
  tag with zero blocking findings; its six deferred minors were all
  triaged stay-deferred and survive as backlog item 3 above (the SDD
  workspace is deleted per process). `RELEASING.md` also carries the
  device-install instructions (`dc6f0fb`). Play Console, AAB, and CI
  signing remain out of scope by ruling.
- Session status: **Stage 5 is complete and qualified: all 13 tasks of
  the plan (Room v9 retired blob-set index; RETIRED_BLOB_SET backup
  family and collection command; retired-set GC closure; silent
  attachment intake auto-resume; frozen `.otvault` v1 archive format
  with independent Node fixtures; encrypted vault export with SAF
  product surface; encrypted vault import with staged activation and
  rollback; disclosed formula-safe CSV export; Glance Today widget; app
  lock with title privacy and unified Quick Add; keyboard shortcuts
  with help dialog and accessible-action audit; one-way calendar
  insertion; and Task 13 qualification and exit gates), plus the
  dedicated pre-Task-13 `RECOVERED_SCHEMA_VERSION` fix, are complete and
  independently reviewed. Stage 5 closes at the commit containing the
  Step 4 contract documents, on top of the Part 1 passphrase-wipe fix
  `6bfafa8`; see the closure checkpoint below. The authoritative
  qualification record is
  `docs/qualification/stage5-platform-features.md`. The final
  whole-branch review returned Ready to merge with fixes, zero
  Critical; both Important findings — the then-missing contract
  documents and the export passphrase wipe — are discharged by this
  closure. The Task 6
  snapshot-only export ruling was upheld by the user
  before Task 7 and is discharged: import passes an empty segments
  list. The pre-existing recovery defect recorded at the Tasks 7–9
  checkpoint (`RECOVERED_SCHEMA_VERSION = 7` rejecting the schema
  marker 8 that `MIGRATION_7_8` writes, breaking Drive recovery and
  `.otvault` import on migrated devices) is discharged by that fix.
  Post-closure: the recommended hardening task is discharged at
  `81cf642`; remote CI provisioning is restored at `52f63aa` after the
  discovery that the Android workflow had zero green runs in its entire
  history; and the follow-up test-fix task is complete and merged to
  `main` (`1cdab0b`) with API 36 fully green — see the Ctrl+K closure
  below.
  Both recorded Stage 4 limits are
  discharged:
  the retired-set index by Tasks 1–3 (Room v9 + GC closure) and the
  `AttachmentBlobCoordinator.resume()` product caller by Task 4's silent
  auto-resume. Stage 4 itself is complete and qualified. Task 14's
  one-shot
  credentialed live attachment gate passed in 606.947 s; its preserved harness
  is `a813c41` and must not be rerun. The final disposable six-module gate was
  282 tests, 0 failures/errors, and exactly two expected skips: the
  credential-only row and the exact `Pixel_10_Pro_Fold` cross-display harness
  exception. It makes no native fold-continuity claim; route that work
  post-Stage-4. Forced-fresh debug/unit/lint/APK, release, schema-drift,
  deterministic-fixture, diff, release-scope, and production-logging gates
  passed. The authoritative qualification is
  `docs/qualification/stage4-notes-activity-attachments-search.md`.
  The Samsung Remote Test Lab item is closed: the user ruled on
  6 August 2026 that RTL is unusable for the time being, so its
  real-device rows lapse without verification. The Fold 8 adaptive
  slice, Stage 3, Stage 2,
  Train 1 Tasks 1.1–1.5, and Stage 1 remain complete and independently
  reviewed.**
- Current product source implementation point: `c5c5e11` (`fix: clear
  stage 6 device qualification gate`), on the focus-aware manual-Stop commit
  `8742a84` and recorded Stage 6 implementation base `fc4aad8`. This
  closure docs commit completes Task 14; test-only qualification proof is at
  `6b55f87`. The complete task, review, fix, and qualification history is in
  the ignored Stage 6 execution ledger and `docs/qualification/stage6-daily-flow.md`.
  The prior Stage 5 implementation point is `6bfafa8` (`fix: wipe
  export passphrase when the output stream cannot be opened`), Task
  13's Part 1 fix, on top of the Task 13 six-module connected-gate fix
  range `f0a8550..d53a9f9` (`334fcae` seed-fixture and `VaultId`
  assertion fixes, `31dd9fc` FoldContinuity teardown hardening,
  `5df2917` app-lock recovery baseline, `d53a9f9` search-focus
  `waitForIdle`) and its checkpoint commit `f0a8550`, on top of
  `d8c89e3` (`fix: accept
  migrated schema markers in recovery import`), the dedicated
  schema-fix commit on top of the checkpoint commit `1767514`, the
  Stage 5 Task 12 range `c0ad0ac..bd8f650` and its
  checkpoint commit `7693fb7`, the Task 11 range `11e4bcb..2ec80aa` and
  its checkpoint commit `aeb013e`, the user-ruled `glance-material3`
  drop `4652a2b`, the Task 10 range `a6c52eb..f76f2a6` and its
  checkpoint commit `36b98b5`, the
  Task 7–9 range `5feb1e8..99db7dc` on top of the
  checkpoint commit `afcfe07`, the Task 3–6 range `1cb768e..b4b1ec4`,
  the Task 1–2 range `33ea364..eb343cb`, and its checkpoint commit
  `f2835b7`. The prior Stage 4 implementation
  point is `b3da5d2` (`test: skip Pixel
  fold harness transition`), the tip of the qualified Stage 4 Task 1–13,
  whole-branch-review, and Task 14 gate-fix range `6538dca..b3da5d2`. The
  prior adaptive-slice closure point
  is `ddbe52a` (`test: guard all continuity database sidecars`) on top of
  `1194536` (`fix: close fold 8 review gaps`), `74d3064` (`fix: align hinge
  split with safe insets`) and the accepted
  visual corrections through `0368dcf`. The full adaptive implementation range
  is `7276f90..ddbe52a`. Accepted visual evidence spans
  `f46ce8c..0368dcf`, with affected rows recaptured after each visual fix;
  final review tests and repository gates ran at `ddbe52a`. The authoritative
  acceptance record is
  `docs/qualification/fold8-adaptive-acceptance.md`; ignored PNG and
  UIAutomator evidence is under
  `.superpowers/sdd/2026-07-31-galaxy-fold8-trifold-adaptive-plan/task-5-evidence/`.
  The prior Stage 3 closure point is `216de3e` (`docs: verify create-only Stage
  3 backup`). The prior Task 12
  closure point is `3109108`; the prior Stage 2 correction point is
  `f9e091b` (`fix: harden stage 2 backup state transitions`); it closes content-key authority, complete
  Inbox capture, same-generation state ownership, initial crash
  reconciliation, active-loop Retry, durable inbox truth, transient intake
  I/O, bounded threshold selection, and stale Ready presentation. Detailed
  RED/GREEN traces remain in
  `.superpowers/sdd/2026-07-29-stage-2-local-backup-android-auto-backup-plan/final-review-fix-brief.md`
  and its adjacent ignored `final-review-fix-report.md`. The completed Stage 2
  design remains
  `docs/superpowers/specs/2026-07-28-stage-2-local-backup-android-auto-backup-design.md`
  with its closed plan at
  `docs/superpowers/plans/2026-07-29-stage-2-local-backup-android-auto-backup-plan.md`.
  The closed Stage 3 execution authority is
  `docs/superpowers/plans/2026-07-30-stage-3-drive-create-only-backup-recovery-plan.md`.
  The controller verified Task 14 on the sole audited API 37 disposable and
  shut it down cleanly. The protected workspace received only a read-only
  metadata and visible-state comparison, then was stopped without snapshot
  save. The user-owned untracked `artifacts/` directory remains untouched.

This is the only live project handoff and ordered backlog. Update it whenever
work changes scope, priority, dependencies, architecture, security assumptions
or verification status.

## Stage 7–9 roadmap — spec written and user-approved — 10 August 2026

A competitive feature analysis (Asana, Jira, monday.com, ClickUp, Todoist,
Notion) produced a published reference report
(https://claude.ai/code/artifact/e7363086-75c8-45bd-8b91-bc3809ff1a9e) and a
code-level feature inventory. Verdict: solo-use parity is largely won; the
credible gaps are list ergonomics, capture grammar, and view surfaces. The
audit also found the Tasks date-chip defect recorded as backlog item 3. No
product code was changed in this session.

A superpowers:brainstorming session then covered the report's Tier 1 and
Tier 2 recommendations plus three reconsidered non-goals (custom fields,
automation rules, Gantt). Recorded rulings:

- The user works mainly in project boards and Schedule/planning and feels
  near-term pull for Gantt/timeline and automation rules. Custom fields
  stays parked with a written reopen condition: a concrete field needed
  repeatedly that priority, tags, estimate, and milestones cannot express.
- Roadmap shape B, cheap wins first, chosen over pain-first and
  canvas-first alternatives: Stage 7 is a no-schema ergonomics sweep,
  Stage 8 planning surfaces, Stage 9 the single Room v10 wave.
- Standing assumptions accepted: the chip fix ships immediately outside
  the stages; every durable-schema need batches into one Room v10
  migration in Stage 9; each stage ends with qualification and a sideload
  release per RELEASING.md; the Actions billing restore and Dependabot
  queue precede Stage 7 execution.
- All new or restyled visualisations use the dot-matrix / dotted-area
  language (user directive, 10 August): unit dots and dot-run bars in the
  ember system, with density and shape carrying the signal.

Approved stage contents (design sections 1–4 presented and approved):

- **Stage 7 — ergonomics sweep** (no schema or backup-format change):
  sort and group controls for the Tasks list, workbench list, and
  in-column board ordering (manual ordering excluded — it waits for the
  Stage 9 rank store); saved-view filters v2 (due-range, priority,
  workflow status, chosen sort; payload codec format v2 with strict
  fail-closed decode of v1 and v2; bounds 20/64/500 unchanged; the
  content-based SAVED_VIEW fingerprint already journals richer payloads);
  Quick Add grammar (#project, @tag, !priority, recurrence phrases onto
  the existing RecurrenceRule, ~estimate; Locale.ROOT per the Stage 6
  lesson); search ranking (exact > prefix > word boundary > substring
  inside the existing 50 cap); System/Light/Dark theme preference
  (device-local, never vault data or backup content; whether forcing dark
  promotes it beyond best-effort is a design-time decision); task
  duplication (copies title, description, priority, estimate, tags,
  unticked checklist, dependencies; excludes completion, activity, time
  entries, attachments; recurrence copy is open); Insights dot-matrix
  restyle (unit dots and dotted-area trends in Compose Canvas, Insights
  scope only, chart↔table toggle retained).
- **Stage 8 — planning surfaces** (no durable schema): month calendar
  view (read-only WorkspaceSnapshot projection, start-else-due placement,
  dot-density day cells, tap-through, no parallel store); drag-to-
  reschedule reusing the Stage 6 board-drag infrastructure across week
  columns, month cells, and the unscheduled tray (default drop time and
  due-only semantics are design-time decisions); Gantt-lite per-project
  timeline, read-only in v1 (start–due bars as dot runs, milestone
  diamonds, blocked markers, tap-to-highlight dependency chain; no arrow
  routing and no bar drag — edits via existing editors); daily digest
  notification (opt-in, computeTodayProjection reuse, generic lock-screen
  copy, boot/time-change re-arm on the reminder scheduler precedent).
- **Stage 9 — board flow and automation** (the one Room v10 wave): v10
  adds an automation_rules table with a new backup record family and a My
  Day ordered store; WIP limits amend the workflow-status payload inside
  its existing family; one exported schema, one deterministic fixture
  regeneration, one recovery-import extension. Automations-lite is a
  fixed rule menu (design trims to ~4 from: auto-archive completed after
  N days, stale marking, on-enter-status side effects, auto-remove
  completed from My Day), bounded at ~20 rules, firing at deterministic
  reconcile points and acting only by dispatching ordinary DomainCommands
  so journaling, undo, and activity attribution hold. Board WIP limits
  are soft per-column limits (confirm over limit, never block). My Day is
  a curated ordered plan (bounded ~200 IDs) on Home; manual rank exists
  only there. Subtasks UI lights up the dormant parentTaskId end-to-end:
  SetTaskParent with cycle/depth guards mirroring DependencyRules,
  CreateTask(parent:), a detail sub-task section, list indentation, and
  board-card rollups; no migration, full dual-engine command tax.

Resumed later on 10 August 2026: the final design section — the backlog
pool, parked custom fields, sequencing, risks, and per-stage exit
criteria — was presented and approved. The roadmap spec is written,
self-reviewed, and committed at `a80a9d6`:
`docs/superpowers/specs/2026-08-10-stage-7-9-roadmap-design.md`. Beyond
the approved stage contents above it pins the backlog pool (recurrence
skip/pause; SAF-folder second backup target), the custom-fields reopen
condition, sequencing (chip fix now; billing restore then Dependabot
before Stage 7; three per-stage releases), risks, uniform and per-stage
exit criteria, the manual-ordering clarification (Stage 7 ships
attribute sorts only; the roadmap's only manual rank is My Day's Stage 9
store), the pinned single-pass automation-rule evaluation, and the open
design-time decisions left to stage specs (dark-theme promotion,
duplication-recurrence copy, drag-to-reschedule drop-time and due-only
semantics, exact rule menu).

**Resume instruction discharged, 10 August 2026:** the user reviewed and
approved the roadmap spec, and the Stage 7 detailed design was
brainstormed and committed the same day. See the Stage 7 checkpoint
immediately below. Nothing from the roadmap has been implemented.

## Stage 7 Phase 0 complete — Stage 7 execution gated — 10 August 2026

The Stage 7 detailed design is brainstormed, self-reviewed, committed at
`3b1a3c4` (unpushed), and **approved by the user**:
`docs/superpowers/specs/2026-08-10-stage-7-ergonomics-sweep-design.md`.
It is the Stage 7 authority under the roadmap spec. After reading all six
research maps below in full, `superpowers:writing-plans` produced the
implementation plan at
`docs/superpowers/plans/2026-08-10-stage-7-ergonomics-sweep-plan.md`.
The plan is now executing with `superpowers:subagent-driven-development`.
Phase 0 Task 1 is committed at `7638037` and passed its independent
spec-and-quality review with no findings. The shared `DueBucket` vocabulary,
zone-aware classifier, corrected Tasks chips, and app-owned projection are in
place. The non-device baseline
`./gradlew testDebugUnitTest lintDebug :app:assembleDebug` passed before the
task; the focused classifier test and affected instrumentation/app compilation
passed after it. No device or connected test ran.

Five user rulings resolved the roadmap's open Stage 7 decisions:

1. Task duplication does **not** copy recurrence.
2. **The app is light-theme only.** The roadmap's System/Light/Dark
   preference item is replaced by pinning the light scheme
   unconditionally — `OpenTasksTheme` loses its `darkTheme` parameter
   and `DarkColorScheme` plus the dark OKLCH constants are deleted. No
   preference UI, no storage. This supersedes DESIGN.md's "dark retained
   as best-effort" wording (lines 13–14 and the "### Dark (best-effort)"
   section), which the stage amends.
3. Sort/group choices persist **durably device-local** on the
   `AppLockSettings` SharedPreferences pattern (new `view_prefs` file);
   Stage 6's session-only board/list mode is left untouched.
4. Group-by dimensions are **due bucket, project, priority** only
   (workbench status grouping and tag grouping excluded).
5. Insights gets the dot-matrix restyle **plus one new trend surface**:
   completions per day across the selected range.

Batch approvals additionally pinned: semantic-status and relative
due-bucket filtering in saved views (both flagged deviations from the
roadmap's "workflow status" / "due-range" wording), fixed sort
directions with no toggle, the saved-view chip identity fix, `!1` =
Urgent, recurrence phrases auto-anchoring a due date, an additive
`CreateTask` widening (`tagNames`/`estimate`/`recurrence`), the
duplication field set including reminder exclusion, and the dot-matrix
primitives living in `:core:designsystem` for Stage 8 reuse.

A three-lens adversarial spec review (consistency, roadmap/CLAUDE.md
conformance, code-fact checking) ran before the commit; every factual
claim about existing code verified clean. Seven findings were fixed
inline, three of them substantive: the saved-view `sort` and search
ranking had competing ordering authorities (now pinned, and the fix
surfaced that a filter-only saved view with blank text would hit the
existing blank-needle short-circuit and return nothing — blank-text
filter views are now specified); recurrence anchoring was defined only
for weekdays (now one uniform rule); and `parentTaskId` was in neither
the duplication copy nor exclude list (now copied).

Six read-only research agents then mapped the exact implementation
seams for the plan. Their durable maps are the six files in the ignored
`.superpowers/brainstorm/2026-08-10-stage-7-plan-research/`
(`task-detail`, `board-workbench`, `command-layer`, `app-wiring`,
`recurrence-parser`, `insights-designsystem`). They pin signatures and
`file:line` anchors and should be read before writing the plan rather
than re-derived. Load-bearing facts already established:

- `CreateTask(title, projectId, priority, due)` already carries
  `projectId` and `priority`, so `#project` and `!priority` need no
  command change; tags, estimate, and recurrence do.
- Snapshot tasks are served `ORDER BY id` (UUIDs) by Room while the
  in-memory engine publishes insertion order — the two must be aligned
  and all visible ordering must come from the new arrangement layer.
- Search has no ranking today (snapshot order, tasks then projects) and
  the in-memory engine hardcodes `.take(50)` while Room uses
  `MAX_SEARCH_RESULTS`.
- `SavedViewPayloadCodec` v1 rejects unknown keys *before* checking
  `formatVersion`, and an omitted `formatVersion` silently decodes as
  v1; v2 must become version-first and fail closed on both.
- The saved-view chip keeps filters only while typed text exactly
  equals the stored text — the identity fix keys on view id instead.
- Insights has no `Canvas` anywhere; every chart is a
  `LinearProgressIndicator` via `MetricBar`.

**BLOCKED before Task 2 by the approved external gate.** Latest `main` run
`31352167676` has zero executed steps for verify, compact API 36, and expanded
API 37.0; release is skipped. Dependabot PRs #14–#19 remain open. The user must
restore Actions billing first. Then rerun/review and resolve the six PRs with
fresh green verify, compact API 36, and release checks (expanded API 37.0 keeps
its documented F6 observe-only ruling). Only after that gate is green: record
the then-current full HEAD as the first full SHA in this plan's ignored SDD
ledger and dispatch Task 2. The protected Stage 3 plan amendment, `.kotlin/`,
and `artifacts/` were not touched.

## Stage 6 closure — 10 August 2026

Stage 6 is complete at product source `c5c5e11`, from implementation base
`fc4aad8`, with focus-aware manual Stop at `8742a84`. The six-finding
whole-stage fix wave is closed, and both the manual-Stop review and the final
connected-gate fix review returned Approved with zero Critical or Important
findings. The gate-fix review left one harmless Minor: Board drag keeps a
second callback-freshness wrapper that is redundant with the card-level
wrapper. Leave it until that code is otherwise edited.

The first Task 14 connected run exposed 14 failures and one extra skip across
332 tests. `c5c5e11` fixed three small product defects (48 dp Quick Add
clear, fresh board drop callback/semantics, and high-scale template-sheet
scrolling) and corrected test timing, scrolling, and the environment-dependent
Ctrl+K harness. The exact six-module rerun passed in 6m52s: 332 tests, zero
failures/errors, and exactly two expected skips (the preserved credentialed
Drive qualification and FoldContinuity cross-display exception). The sole
`Fold8_Acceptance` AVD was verified before use, booted read-only without
snapshots, and killed after the final checklist; ADB and emulator-process
audits were empty.

Forced-fresh `testDebugUnitTest lintDebug :app:assembleDebug` passed all
550 tasks in 1m23s, including 1,179 JVM tests with zero failures.
Forced-fresh `:app:assembleRelease` passed all 442 tasks in 1m02s. Schema
v9 drift, all four frozen fixture generators, workflow pinning, release
manifest/scope, production logging, privacy, and diff-hygiene gates passed.
Stage 6's release additions are the approved `text/plain` SEND and
PROCESS_TEXT filters, bind-permission tile service, and private focus alarm
receiver; `drive.appdata` remains the sole Drive scope.

The post-check accessibility proof at `6b55f87` invokes the exact Kanban
custom move action. It also closes the existing workflow-editor IME before
coordinate clicks and scrolls those controls into view. The affected focused
scenario passed, then the complete projects connected suite passed 19/19.

Disposable-device evidence covered two-value browser sharing, PROCESS_TEXT,
the Quick Settings tile, widget open/complete/reopen, app-lock concealment and
unlock routing, focus-owned manual Stop across background/foreground, bulk
complete/Undo, weekly review, Markdown export/open, CSV export/import/preview/
duplicate Undo, Kanban drag, and installed Ember artwork under the launcher
mask and minimal icon treatment. The headless harness cannot honestly perform
a 25-minute boundary wait, a TalkBack custom-action gesture, production
Ctrl+K Dialog focus, or a complete SAF `.otvault` saved-view round-trip by
hand. Those exact contracts are covered by connected/JVM suites and are marked
as deterministic substitutions, not manual observations, in the qualification
record.

Standing residuals are unchanged: F6 remains observe-only; real
`MainActivity` Ctrl+K Dialog focus remains a headless CI blind spot; Play
Console remains outside the sideload-only boundary. The protected
`Pixel_10_Pro_Fold`, the user-modified Stage 3 plan, `.kotlin/`, and
`artifacts/` were not changed.

## Stage 6 final-review fix closure + Task 6 decision pause — 9 August 2026

The resumed whole-stage review over `fc4aad8..ade3634` returned Needs fixes:
zero Critical and six Important. One consolidated TDD fix wave at `4f42dfe`
closed forward InMemory import visibility, natural-date locale/12-hour
validation, Today-widget live action authority, Markdown project selection
semantics, Quick Add's ineffective sub-48 dp date clear, and applied due-date
restoration. The ignored reports are `final-review-report.md` and
`final-fix-report.md` in the Stage 6 SDD workspace.

The implementer and controller independently ran the five focused tests and
Android-test compile targets. The full `testDebugUnitTest lintDebug
:app:assembleDebug` gate passed at 550 tasks, and `git diff --check` is clean.
No emulator, ADB mutation, install, or connected suite ran.

The one scoped re-review marked findings 1, 2, and 4–6 ADDRESSED and disputed
finding 3. The controller parked that residual as a false positive with this
ruling: an allowed widget action linearizes at its live-authority read; its
later atomic generation comparison proves it also precedes any stop or
concealment invalidation. Invalidation during authority evaluation is covered
and aborts. Invalidation after the final comparison is concurrent with an
already-authorized operation, not stale authorization; requiring it to cancel
an in-flight suspending repository transaction is a stronger contract than
the authority spec and would force invalidation to block across that write.
The durable re-review is `final-fix-re-review.md`.

Exactly one product decision now blocks Task 14. During FOCUS, generic
task-detail Stop ends only the current time entry; foreground reconciliation
starts a new entry for the same task. Keeping this pinned rule needs no source
change and Task 14 must prove it never touches another timer owner. Making
generic Stop end the focus cycle requires a spec/plan amendment, coordinated
`FocusCoordinator.stop()`, and concurrency/reconciliation tests before Task
14. On the user's ruling, take that chosen path, update this checkpoint, and
only then start controller-owned Task 14. The protected historical Stage 3
plan amendment, `.kotlin/`, and `artifacts/` remain untouched.

## Stage 6 Task 13 closure + whole-stage review pause — 9 August 2026

Task 13 is complete at `ade3634` and independently review-clean. It replaces
only the approved adaptive launcher foreground, monochrome layer, and Ember
background colour. All five delivery/adaptive byte comparisons passed; the
manifest and unchanged adaptive definitions retain their stable wiring. The
resource/debug build and full `testDebugUnitTest lintDebug
:app:assembleDebug` gate passed (550 tasks), and `git diff --check` was clean.
No emulator, ADB mutation, install, or connected suite ran.

The whole-stage reviewer received the exact recorded
`fc4aad8..ade3634` package (36 commits, 18,315 lines), read it completely, and
began synthesis. The user then requested this safe stopping point. The review
has no final readiness verdict, no fix wave has started, and Task 14 remains
untouched. The ignored durable pause report is
`.superpowers/sdd/2026-08-08-stage-6-daily-flow-plan/final-review-pause-handoff.md`.

Confirmed review findings to carry into the single final-fix wave:

- Important: forward `ImportTasks` in `InMemoryVaultRepository` publishes
  intermediate project/status, task/tag, and activity snapshots instead of
  one observable commit like Room. Build one final snapshot and add a
  forward-import emission test.
- Important: `NaturalDateParser` uses locale-sensitive `lowercase()` and its
  12-hour parser accepts `13am`/`0pm`. Use `Locale.ROOT`, enforce `1..12`, and
  add locale and invalid-time tests.
- Important: Today-widget completion authorizes through asynchronously cached
  title-permission/runtime state, leaving lock/privacy and stop races. Replace
  it with live authorization plus an invalidatable action generation/gate and
  test both races.
- Important candidate: the Markdown project picker changes only local state;
  its rows expose no selected appearance or semantics. Finalize the verdict,
  then use selectable/radio semantics and a Compose test if upheld.
- Final fix/defer verdicts remain open for Quick Add's sub-48 dp nested date
  clear target and non-saveable applied due-date state.

The Task 6 policy decision is still open. Current code is authority-compliant
and timer-ownership-safe: during FOCUS, manually stopping the task timer is
reversed by ON_RESUME reconciliation (`FOCUS + no timer -> START`), creating a
new time entry. Keeping that behavior needs no code change but must be recorded
and device-tested in Task 14. Making manual Stop end the focus cycle requires
a spec/plan amendment, coordinated `FocusCoordinator` stop, and concurrency/
reconciliation tests before Task 14.

**PAUSED by user instruction.** On a fresh go, read the pause report and
ledger, finalize the Markdown/Quick Add verdicts, and issue the complete
whole-stage review report. Then dispatch exactly one consolidated final-fix
wave, run its one scoped re-review, resolve the Task 6 policy decision with the
user, and only then execute controller-owned Task 14. Do not run any device
suite before Task 14. The working tree at this checkpoint otherwise retains
only the protected pre-existing Stage 3 plan amendment, `.kotlin/`, and
`artifacts/`.

## Superseded: Stage 6 Tasks 9–12 + Task 13 plan checkpoint — 9 August 2026

Execution resumed from checkpoint `89cff2c` with the Stage 6 plan, ignored
SDD ledger, direct-to-`main` rule, and independent-review-per-boundary
discipline. Tasks 9–12 are complete and review-clean. No device suite has run;
Task 14 still owns all connected testing.

- Task 9 (`f354deb`, fix `3c0b3f6`) added the guided weekly review: pure
  overdue > stale > unscheduled queueing with the exclusive 14-day boundary,
  active-project health cards, bounded `REVIEWED` activity through
  `MarkReviewed` in both engines, saved progress in `List<String>` state,
  Navigation 3 routing, and the stateless More surface. Review fix round 1
  made pending system Back consume rather than escape; the review also
  corrected its own false assumption that Compose's member-form
  `assertDoesNotExist` required a top-level import. Final re-review: zero
  open Critical/Important findings.
- Task 10 (`567a726`) added pure, escaped, single-line-normalised Markdown
  project export with active workflow grouping, milestones, tasks, tags,
  checklist rows and stored-zone UK dates; the single-document SAF flow reuses
  the existing operation mutex and non-cancellable partial-document cleanup.
  Independent review returned Approved with no findings.
- Task 11 (`bffb5c7`, fix `ca7b103`) added strict bounded own-schema CSV
  import: reversible formula/tag encoding, 5 MiB strict UTF-8/RFC 4180 parser,
  one shared pure project/tag/status resolver, create-only transactional
  `ImportTasks`, complete backup-representability preflight, exact receipt
  Undo, and ViewModel preview/commit state. Review fix round 1 remapped preview
  status identities to fresh project defaults, accepted valid historical
  second-precision offsets through `ZoneOffset.of`, added post-Undo backup
  encode/zero preflight in both engines, and made InMemory Undo publish once
  with task-owned rows removed before shared rows. Final re-review: all four
  findings addressed; parser 14/14 and InMemory import 12/12 green; Room
  instrumentation compile-verified only.
- Task 12 phase 1 (`0e5d06d`) added the pure board projection, project-scoped
  saved board/list mode, the only board-width policy decision, and a stateless
  Material 3 board over active project workflow columns. Tap-to-move is fully
  shippable through 48 dp menus and equivalent per-target TalkBack custom
  actions; card tap still opens the task. The mandatory checkpoint review
  returned Approved with no findings. Phase 2 (`56f2d3f`, fixes `a4125f6`,
  `7d6f446`, and `4b33866`) added long-press drag with board-hoisted state,
  root-coordinate column hit testing, a board-level drag proxy, non-colour
  hover border, continuous edge auto-scroll, and snap-back on invalid drops.
  The existing menu and TalkBack move fallbacks remain. Three review rounds
  fixed proxy clipping, stale callbacks, and physical RTL placement; final
  review returned Ready with no Critical, Important, or Minor findings.
  The drag instrumentation compiles and covers a root-bounds drop plus a
  callback replacement during an active gesture. On-device nested scrolling,
  drag, popup, and TalkBack behaviour remain correctly deferred to Task 14.
- The user supplied and approved the Ember launcher icon package at
  `/Users/kk/Downloads/deliverables-1a-ember/`. All 18 files were inspected.
  The approved design (`8cf75fe`) and amended fourteen-task Stage 6 plan
  (`4fba6e2`) make the icon standalone Task 13 and renumber qualification to
  Task 14. Task 13 changes only the three differing adaptive resources
  (foreground vector, monochrome vector, and background colour); the existing
  adaptive definitions already match. Legacy PNG fallbacks are excluded at
  minSdk 36, and the 512 px listing image stays with the parked Play Console
  work. No app resource was changed and Task 13 has not started.

Every checkpoint above passed its focused tests, named Android-test compile
targets, `git diff --check`, and the full
`testDebugUnitTest lintDebug :app:assembleDebug` gate. The Task 12 checkpoint
gate covered 550 Gradle tasks after the final fix. The working tree after the
tracked checkpoint contains only the three protected pre-existing items: the
modified Stage 3 plan amendment, `.kotlin/`, and `artifacts/`.

**Open user decision unchanged (Task 6):** the pinned `FOCUS + no timer →
START` rule restarts a manually stopped task timer while the focus phase is
active. Keep the pinned rule, or make a manual task-timer stop end the focus
cycle. Resolve this before or at the whole-stage final review.

**PAUSED by user instruction before Task 13 implementation.** On a fresh go,
execute Task 13 exactly from the amended plan and icon design: replace only
the three named adaptive resources, run its non-device gates, commit, and
complete the independent task review. Then run the whole-stage final review
over the recorded `fc4aad8..HEAD` range, resolve its findings and the Task 6
decision, and only then execute controller-owned Task 14. No device suite
before Task 14.

## Superseded: Stage 6 Tasks 1–8 checkpoint — 8 August 2026

Historical references to Stage 6 Task 13 in the superseded checkpoints below
mean qualification, which the 9 August Ember amendment renumbered to Task 14.

Execution ran from base `fc4aad8` directly on `main`. Task 1 was
executed inline; Tasks 2–8 ran subagent-driven per the user ruling
recorded in the plan header, each with an independent task review and
scoped re-reviews of every fix round. All eight boundaries closed with
zero open Critical/Important findings. The full CI gate
(`testDebugUnitTest lintDebug :app:assembleDebug`) passed at every
commit below; no device suite has run (Task 13). Deferred minors and
per-round review evidence live in the ignored execution ledger.

- Task 1 (`b3d4a42`, minors `81c28ba`): saved-view commands over the
  dormant `saved_views` table in both engines; content-based
  `SAVED_VIEW` journal fingerprint (renames journal in both engines,
  review-verified); `SavedViewPayloadCodec` (2 MiB, deterministic
  sorted-id encoding, strict fail-closed decode); bounds 20/64/500 in
  both companions; exact repository-produced undo. No schema, fixture,
  or format change.
- Task 2 (`fac4b70`): pure `NaturalDateParser` in core:domain with the
  pinned grammar/resolution rules; `CreateTask` gains additive
  `due: ZonedMoment? = null` seeded identically by both engines, no
  reminder created; Quick Add suggestion chip with span-strip and
  clear.
- Task 3 (`b07ef5c`, fixes `80a9b0a` `c204568` `214e759`): SEND +
  PROCESS_TEXT intent filters on MainActivity; pure `quickAddPrefill`;
  prefill rides the existing lock-gated quickAddSignal chain. Three
  review-driven fixes hardened the seed mechanism: null prefill can no
  longer discard a pending share; the sheet seeds correctly on every
  mount path (capture-before-consume + composition-time two-source
  seed); explicit no-prefill triggers (widget/shortcut) seed empty via
  the `""` sentinel. Recorded ruling: "last explicit trigger wins" — a
  share pending behind the lock is overwritten by a later explicit
  quick-add trigger. Three replica instrumented tests pin the
  mechanism (execute at Task 13).
- Task 4 (`0943009`): Quick Settings tile service
  (`QuickAddTileService`, BIND_QUICK_SETTINGS_TILE-gated, the stage's
  one new exported component besides Task 3's filters);
  `QUICK_ADD_ACTION` made internal on MainActivity's companion,
  value unchanged.
- Task 5 (`8822a0b`): interactive Today widget — `FocusEntry(taskId,
  title, completable)`; row tap-through via the existing
  ACTION_OPEN_TASK contract; complete glyph re-verifies against a
  fresh workspace read inside the active publisher before dispatching
  `CompleteTask`, and every write path provably routes through the
  Stage 5 `StopGatedWriter` gate; all three key families (titles, ids,
  completable) clear on concealment.
- Task 6 (`1456d3c`, fixes `d321dac` `94ef78a`): preset focus cycles
  (25/5, 50/10) — pure `FocusSessionController` with anchored
  phase-edge reconciliation; `StopTimerIfOwned` +
  `TIMER_OWNERSHIP_CHANGED` atomic owner-checked stop in both engines
  (no journal write on rejection, parity review-verified);
  fail-closed `focusTimerAction` as the single ownership decision
  point; exact alarms with boot/time-change re-arm ordered before the
  lazy repository lookup; generic-text notifications only. Two fix
  rounds serialized the coordinator (mutex + store re-read guard) and
  closed the eventually-consistent-snapshot races (coalesced
  reconciles; refused starts resolve against a fresh bounded workspace
  view, clear only on positive evidence; unconditional owner-checked
  Stop dispatch, silent on idempotent success).
- Task 7 (`f008af0`, fix `ed51354`): saved searches UI — chips on the
  blank-query search Dialog (structure untouched for the shortcut
  test), whole-SearchQuery debounce dispatch, direct un-debounced
  save, rename/delete via per-chip menus keyed on chip identity, limit
  handling at 20; `WorkspaceViewModel.search` takes `SearchQuery`.
  Six pinned instrumented tests plus a state-leak regression test
  (execute at Task 13).
- Task 8 (`74d2bf8`, fix `50d0a7b`): bulk multi-select and composite
  commands — `CompleteTasks`/`RescheduleTasks`/`MoveTasksToProject`/
  `SetTasksTag`/`DeleteTasks` plus repository-produced `UndoBatch` in
  both engines; pinned normalise → resolve → full-preflight → apply
  pipeline (all validation before the first write; a Rejected leaves
  records, relations, revisions, activity, and journal untouched);
  `MAX_BULK_TASKS = 200` and both new rejection reasons in both
  companions. Batch undo preflights every stored inverse per shape
  (no throw-as-control-flow), and InMemory applies it on a scratch
  engine with exactly one publish, matching Room's transaction
  isolation (fix round 1, re-review-verified). `TaskRow` long-press
  plus a custom accessibility select action; selection bar
  (count/clear/complete/reschedule via DatePicker at the Task 2 17:00
  convention/move/tag/bin), checkbox rows, row tap toggles instead of
  opening; `SavedStateHandle` stores `List<String>` under
  "bulkSelection"; blocked completion confirms then retries with
  `acknowledgeBlocked = true`, and only success clears the selection.
  InMemoryBulkCommandTest 10/10 and WorkspaceBulkSelectionStateTest
  green; two instrumented suites compile-verified (execute at
  Task 13); CI gate green at both commits.

**Open user decision (Task 6, parked; code is plan-correct as
committed):** the plan's pinned `FOCUS + no timer → START` rule makes a
manual "Stop timer" on the task itself inert during a FOCUS phase — the
ON_RESUME reconciler restarts the timer; only the focus banner's Stop
ends the cycle. The task reviewer reports this consequence as a product
defect (Important, plan-mandated). Ruling needed: keep the pinned rule,
or add a new pinned rule so a manual stop ends the focus cycle.

**Task 9 was NOT started** (user instruction, 8 August 2026: once
Task 8 is finished and verified, update the handoff and related docs,
then pause). The working tree at pause holds only the three protected
pre-existing items (uncommitted Stage 3 plan amendment, `.kotlin/`,
`artifacts/`).

**Resume instructions:** on the user's go, extract the Task 9 brief
with the SDD skill's `scripts/task-brief` and dispatch a fresh
implementer subagent-driven per the plan and ledger discipline (brief →
TDD → CI gate → commit → task review → fix rounds → scoped re-reviews →
ledger), then continue Tasks 10–12 the same way, Task 12 with its
mandatory mid-task tap-to-move review boundary, then the whole-branch
final review, then controller-owned Task 13. Every implementer dispatch
keeps the no-subagent/no-fork clause. Resolve the parked Task 6
decision with the user before or at the final review. The Stage 5
checkpoint history below is unchanged.

## Superseded: Stage 6 Task 1 checkpoint — 8 August 2026

Execution began from base `fc4aad8` (audited plan/spec revisions), inline
via `superpowers:executing-plans` directly on `main`, with the base SHA
recorded in the ignored execution ledger before any implementation.

- Task 1 (`b3d4a42`, review minors `81c28ba`) lit up the dormant Stage 1
  `saved_views` table end-to-end with no schema, migration, or fixture
  change: `CreateSavedView`/`RenameSavedView`/`UpdateSavedViewQuery`/
  `DeleteSavedView`/`RestoreSavedView` (undo-only) in both engines,
  bounds 20 views / 64-char name / 500-char query text in both
  repository companions (the hard limit counts physical DAO rows, so a
  preserved malformed row consumes a slot), and the internal
  `SavedViewPayloadCodec` (TemplatePayloadCodec shape, 2 MiB cap,
  format version 1, sorted filter-id lists for deterministic bytes,
  strict fail-closed decode). Undo pairs are exact and
  repository-produced. The `SAVED_VIEW` journal fingerprint moved from
  identity-only to content-based in `RoomBackupJournalSession`, and
  `InMemoryBackupJournal.toBackupRecords` gained the savedViews arm, so
  a rename or query update journals an UPSERT in both engines — the
  review's mandatory check, verified by execution (in-memory) and by
  code path plus the compiled pinned instrumented test (Room). A
  malformed dormant row is omitted from snapshots via `mapNotNull`
  without delete/rewrite/log, never blocks readiness, and stays
  invisible to commands (`NOT_FOUND`-style rejections) so both engines
  expose the same mutable set.
- Evidence: `:core:data:testDebugUnitTest` 596 tests green (reviewer
  re-run), including the plan-pinned InMemorySavedViewCommandTest and
  SavedViewPayloadCodecTest cases; CI gate
  `testDebugUnitTest lintDebug :app:assembleDebug` green;
  `scripts/check-schema-drift.sh` clean;
  `RoomSavedViewCommandInstrumentedTest` compile-verified (executes at
  Task 13). Independent review verdict: **Approve with minors, 0
  Critical, 0 Important**; two minors folded in (`81c28ba`), three
  recorded as deferred in the ledger (binary-vs-UTF-16 name-ordering
  parity nuance, defaulted `formatVersion` shape-conformance
  observation, unscoped DAO surface as specified).
- Resume instruction discharged: the user approved the subagent-driven
  switch and resumed execution at Task 2 on 8 August 2026 (see the
  amended plan header). The three protected workspace items (uncommitted
  Stage 3 plan amendment, `.kotlin/`, `artifacts/`) remain untouched.

## CI test-fix final checkpoint — 7 August 2026

The post-Stage-5 CI test-fix task completed on branch `test-fix` at
`c9e30daf2b429304a62d05e4b8a16ad5e4355434`, synchronized with
`origin/test-fix`. Draft PR #13 remains the sole bench. The authoritative
scope and evidence are
`.superpowers/sdd/2026-08-03-stage-5-platform-features-plan/test-fix-brief.md`
and the adjacent `test-fix-report.md`.

- F1's JVM race is fixed with a deterministic latch (`30ad043`). F2 was
  verified as a device-load timeout rather than a product defect and folded
  into F3's shared 30 s `:core:data` androidTest bound (`3fb9ce2`).
- F4's Quick Add IME assumption is fixed by directly driving the platform
  inset path (`3e5b751`). The remaining Ctrl+K test now models the headless
  API 36 window manager without changing product code; `b295c1e` replaces the
  iteration-5 API 37-only `WindowInspector` listener with the API 36-compatible
  snapshot API.
- F5's one-line Gradle `--continue` workflow change (`91095e2`) exposed all
  feature suites. At the last executed bench, `:feature:tasks` was 36/36,
  `:feature:projects` 16/16, `:feature:schedule` 2/2 and `:feature:more` 54/54.
- Executed iteration 5, run `31115797148`, had green `verify` and `release`.
  API 36 was 293 pass, one failure and two expected skips; the sole failure
  was the unavailable API 37 `WindowInspector` listener overload now removed
  by `b295c1e`. API 37.0 again lost package/activity-manager services before
  tests could run, the existing F6 canary-image blocker.
- Iteration 6, run `31118576902`, queued for 15 minutes during GitHub's
  official Actions outage, then all three entry jobs were cancelled with
  `runner_id: 0`, no assigned runner and no steps. `release` was skipped.
  This is infrastructure-only and provides no test signal. Six of the eight
  allowed CI runs have been issued; conservatively count a rerun as run 7.
- Fresh local verification at `b295c1e` is green:
  `testDebugUnitTest lintDebug :app:assembleDebug
  :app:assembleDebugAndroidTest :core:data:assembleDebugAndroidTest`,
  `scripts/verify-actions-workflow.sh`, and `git diff --check`.
- GitHub's official status page reports Actions operational and its 6 August
  incident resolved. After `gh run view` confirmed the cancelled run had zero
  steps, the exact workflow was manually rerun as iteration 7. `verify` and
  `release` passed; compact API 36 had 293 pass, one failure, and two expected
  skips; `:core:data` and all four feature modules were green. The sole API 36
  failure was the test's `ACTION_UP` consumption assertion at
  `ShortcutRootWiringInstrumentedTest.kt:213`; production intentionally handles
  only key-down. Expanded API 37.0 again ran zero tests after its system-service
  failure, the standing F6 image blocker.
- `c9e30da` keeps dispatching key-up but drops that invalid assertion. The full
  local JVM/lint/debug/APK and Android-test-assembly gate, workflow verifier,
  and diff check passed. It was pushed without unrelated files, triggering
  final iteration 8, run `31177454111`, at `2026-08-07T12:15:50Z`.
- Iteration 8 completed with failure. `verify` and `release` passed. API 36
  recorded 293 passes, one failure, and two expected skips: `:core:data`
  (158), `:feature:tasks` (36), `:feature:projects` (16),
  `:feature:schedule` (2), and `:feature:more` (54) were green; the sole
  failure was
  `ShortcutRootWiringInstrumentedTest.ctrlKOpensSearchAndFocusesTheQueryField`,
  which timed out in `UiAutomation.executeAndWaitForEvent` at test line 134
  with no event received. API 37.0 again hit F6: credential-encrypted storage
  was unavailable, producing 28 failures out of the 35 `:core:data` cases
  reached and subsequent activity-resolution failures in app and feature jobs.

The eight-run cap is exhausted and API 36 still fails. Stop and report this
evidence; do not rerun, push, merge, or change product or test code. The
controller owns any landing or follow-up decision. (Superseded: the
controller authorised the two-run diagnostic follow-up below, which
closed the failure and merged the branch.)

## Controller-authorized Ctrl+K diagnostic follow-up — 7 August 2026

The controller authorised a fresh, strict two-run follow-up for the sole API
36 Ctrl+K wiring failure. Scope is the existing instrumented test only; product
code, workflow logic, formats, and API 37.0 F6 remain out of scope. The
diagnostic keeps the original focused-EditText event predicate, but on timeout
records the dialog's attachment/window/native-focus state and the bounded
accessibility events observed during the synthetic focus transition. This
distinguishes a missing accessibility event from missing window focus without
faking focus or weakening the assertion.

The focused test compiles, and the required local
`testDebugUnitTest lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
:core:data:assembleDebugAndroidTest` gate, workflow verifier, and diff check
passed. The diagnostic was committed as `34f6de0` and pushed to `test-fix`,
triggering bench 1 of 2, run `31186660700`. `verify` and `release` passed;
API 36 again had 293 passes, one failure, and two expected skips. The sole
failure recorded `dialogAttached=true`, `dialogWindowFocused=false`,
`dialogFocused=false`, `focusedView=androidx.compose.ui.platform.AndroidComposeView`,
and `events=[]`.
The headless WindowManager has not focused the dialog, so the manual
`dispatchWindowFocusChanged(true)` callback cannot prove query input focus or
produce an accessibility event. The controller selected the narrow capability
exception: after proving Ctrl+K created the Dialog, the test uses
`assumeTrue(dialogRoot.hasWindowFocus())`. It skips only the impossible
query-focus assertion on this headless API 36 runner; environments with real
Dialog focus still run the original focused-EditText accessibility assertion
and retain the timeout diagnostic. The full local gate, workflow verifier, and
diff check passed on the change before commit.

Closure, later on 7 August 2026: the user authorised proceeding. The
exception was committed as `39d2dc5` (with the diagnostic plan and design
docs) and pushed, triggering bench 2, run `31190499051`. `verify` and
`release` passed; API 36 was fully green — 293 pass, 0 failures, 3 skips
(the credential-only row, the exact `Pixel_10_Pro_Fold` cross-display
exception, and the new headless-focus assumption, whose SKIPPED result is
recorded in the job log). API 37.0 failed on the standing F6 image
blocker, out of scope. PR #13 was marked ready and merged to `main` as
`1cdab0b`; branch `test-fix` is deleted. The accepted trade-off: CI does
not prove Ctrl+K focuses the query field on the headless API 36 runner;
focus-capable device runs (for example the Task-13-style connected gate)
still execute the original assertion.

Preserve the three unrelated pre-existing workspace items: modified
`docs/superpowers/plans/2026-07-30-stage-3-google-drive-backup-recovery-plan.md`,
untracked `.kotlin/`, and untracked `artifacts/`. The test-fix task made no
product-code change and did not touch those items. The brief still prohibits
subagents/forks unless the user explicitly overrides it; the model discussion
did not itself grant that override.

## Stage 5 Tasks 1–2 checkpoint — 3 August 2026

Stage 5 execution follows the approved design
`docs/superpowers/specs/2026-08-03-stage-5-platform-features-design.md` and
plan `docs/superpowers/plans/2026-08-03-stage-5-platform-features-plan.md`
(committed `981d8c1`, `1cd8cf4`), subagent-driven directly on `main` from
base `1cd8cf4` with an independent review per task. The execution ledger is
`.superpowers/sdd/2026-08-03-stage-5-platform-features-plan/progress.md`.

- Task 1 (`33ea364`, fix `32a8a6f`) added the Room v9 `retired_blob_sets`
  table with exported `9.json`, a non-destructive additive migration and
  preservation test, `RetiredBlobSet` in `WorkspaceSnapshot` (retirement
  time then id ordering), and same-transaction retirement of every
  blob-bearing attachment row a purge removes. Review closed after one fix
  round, which added trash-purge and tombstoned-with-blob coverage and
  extended retirement to a third, review-found path: `restoreTaskStatus`'s
  undo-of-generated-occurrence branch, whose in-memory arm previously
  failed to remove the occurrence's attachments at all.
- Task 2 (`35d54c3`, fix `eb343cb`) added the `RETIRED_BLOB_SET` backup
  family end-to-end after `NOTE` (the reviewer verified no family ordinal
  is persisted anywhere): strict mutation-codec validation (0..25 chunks),
  journal emission in both engines via the snapshot diff, recovery import,
  capture-DAO attribution, staged-vault verification, deterministic Stage 2
  fixture regeneration, and the idempotent `MarkRetiredBlobSetCollected`
  command (absent row → Success, no journal write, no Undo). The verifier's
  settle-purge accounting was extracted as a pure, unit-tested rule that
  accepts exactly the purge's own blobSetId-matched retired rows as drift
  and fails closed on anything else — load-bearing for Task 7's import
  reuse. The retired-row `revisionLogical = 0` decision was examined and
  upheld. Review approved with zero Critical or Important findings.
- No device suite ran; all new instrumented tests are compile-verified and
  execute at the Task 13 connected gate. Notable deferred minors in the
  ledger: the verifier's zero-slack `retiredAt <= now` check (a backwards
  clock step between settle and verification would reject a legitimate
  recovery), no Room-side execution of the collect arm before Task 13, and
  the pre-existing in-memory `restoreTaskStatus` gap that still leaves a
  generated occurrence's activity and time entries uncleaned.

This checkpoint's resume instruction is superseded: Tasks 3–6 were
executed on 3–4 August and are recorded in the checkpoint below.

## Stage 5 Tasks 3–6 checkpoint — 4 August 2026

Execution resumed from `f2835b7` with the same plan, ledger, and
independent-review-per-task discipline. All four task boundaries closed
with zero open Critical or Important findings. No device suite ran; all
new instrumented tests remain compile-verified and execute at the Task 13
connected gate.

- Task 3 (`1cb768e`, fix `7abe74e`) joined `retired_blob_sets` rows into
  the attachment GC candidate stream: `deletedAt = retiredAt`, tombstone
  generation from the RETIRED_BLOB_SET journal family, base coverage via
  the existing helpers, live-`blobSetId` replacement rows excluded, and
  post-batch release through `MarkRetiredBlobSetCollected`. The review's
  one Important finding — `recordAllContentCollected()` left retired rows
  permanently unsatisfiable after the destructive attachment wipe,
  costing a full authorize/resolve/list round trip forever — was fixed by
  releasing `workspace.retiredBlobSets` there too, with a covering test.
- Task 4 (`4a216bc`, fix `a6617d8`) gave
  `AttachmentBlobCoordinator.resume()` its product caller: silent
  auto-resume ordered strictly after session expiry on runtime start
  (same coroutine) and re-armed after each completed publication run, wired
  through a new `resumeAttachmentSessions` lambda in AppModule. The
  review's Important finding — resume authorized against Drive even with
  nothing pending — was fixed with the sibling
  `hasUnfinishedSessions()` guard. There is still no in-row transfer
  progress; that boundary is unchanged.
- Task 5 (`cf68f2e`) froze the `.otvault` v1 archive format:
  `OtVaultCodec` with a bounded authenticated header whose envelope is a
  real recovery envelope (export passphrase = recovery passphrase,
  Argon2id 64 MiB/3/1/16-byte salt), archive-scoped object IDs with
  bidirectional replay resistance, streamed Stage 1 frames, an
  inventory-last integrity check, and an independent Node fixture
  generator whose byte-identical regeneration the controller verified.
  Review approved with zero Critical or Important findings; the
  manifest-codec `internal` extraction was ruled a sound pure move. The
  practical archive bound is the inventory frame's byte limit (~6,000
  objects ≈ 240 attachments at 25 chunks), not the entry-count constant.
- Task 6 (`4eb112f`, fix `b4b1ec4`) built encrypted vault export:
  capture → recovery-envelope wrap → complete attachment pre-flight
  (returns `MissingAttachmentBytes` naming every unfetchable attachment
  before a single byte is written) → streamed archive → `Completed`,
  with a `VaultTransferViewModel` owning the SAF `CreateDocument` flow,
  passphrase sheet with confirmation, partial-document deletion on
  failure and — after the fix round — on cancellation via a
  `NonCancellable` finally. Archive manifests carry `ZERO_SHA256`-style
  sentinels instead of plausible-but-wrong digests; passphrase wipes now
  use the repo-wide NUL convention.

**Controller ruling requiring user confirmation before Task 7:** the
Task 6 review flagged that no operation-segment frames are written even
though the brief's export-order sentence lists them. The controller ruled
snapshot-only export correct: `RoomBackupCaptureSource.capture()` is a
complete point-in-time baseline captured in one transaction, the
authority spec never mentions segments, Task 7 imports into fresh-only
operational state (empty remote tables — segments would have no
consumer), and the brief's own Consumes list names no segment source.
Task 7 would pass an empty segments list to `RecoveryImportRequest`. The
user upheld the ruling on 4 August before Task 7 was dispatched; it is
discharged.

Carry-forwards for Task 7, recorded in the ledger: archive manifests
carry sentinel `ciphertextSha256`/`providerObjectId` values — the
importer must never hand them to the live open path expecting frame
digests (per-frame integrity is the inventory plus AEAD);
`readChunksForExport` and its AppModule wiring are compile-verified only,
with Task 7's `OtVaultImportInstrumentedTest` as the end-to-end proof at
the Task 13 connected gate. Notable deferred minors in the ledger: the
unwiped passphrase array on the rare null-`openOutputStream` branch
(memory-hygiene only; final review must triage), the unbounded read-side
inventory accumulator in `OtVaultCodec.readAll` (hostile archive could
OOM instead of failing closed), and the export row remaining enabled
during an in-flight export.

This checkpoint's resume instruction is superseded: Tasks 7–9 were
executed on 4–5 August and are recorded in the checkpoint below.

## Stage 5 Tasks 7–9 checkpoint — 5 August 2026

Execution resumed from `afcfe07` with the same plan, ledger, and
independent-review-per-task discipline. The user upheld the Task 6
segments ruling before dispatch, so `.otvault` stays snapshot-only and
import passes an empty segments list. All three task boundaries closed
with zero open Critical or Important findings. No device suite ran; new
instrumented tests are compile-verified and execute at the Task 13
connected gate.

- Task 7 (`5feb1e8`, fix `e8962e1`) built encrypted vault import and
  activation: `OtVaultImporter` stages into an isolated slot with full
  import-policy verification (single snapshot, no segments, contiguous
  manifest-authenticated chunks, digest reproduction, every live
  attachment's blob set present), previews exact counts with
  beyond-cache names, and activates through the proven recovery
  slot-replacement path, retaining the previous slot as rollback until
  first unlock. The archive envelope becomes the imported vault's
  stored recovery envelope. The fix round moved retained chunks into an
  import-scoped `vaultImportStagingRoot` that never mutates the live
  attachment cache (budgeted ceiling-minus-usage, promoted only after
  activation), joined `staging.activate` to `reconstruct` inside the
  guarded try so any failure abandons the staged vault and key, and
  moved the import passphrase wipe to a `finally` covering the
  null-stream branch. Sentinel archive manifest digests never reach a
  live open path; per-frame integrity is the inventory plus AEAD.
- Task 8 (`b9ecd9b`) added disclosed formula-safe CSV export: a pure
  `WorkspaceCsvWriter` with the four fixed tables, RFC 4180 quoting,
  UK-display and ISO columns in the moment's stored zone,
  `=`/`+`/`-`/`@` neutralisation, Bin exclusion, and the exact spec
  disclosure copy with no "do not ask again"; one
  `CreateDocument("text/csv")` per selected table, streamed with
  partial-output deletion on failure. Review approved with no fix
  round; two judgment calls upheld (ISO_LOCAL_DATE for bare project
  due dates; a generic failure outcome on a partial batch).
- Task 9 (`0f13b7c`, fixes `9a0353d`, `99db7dc`) added the Glance
  Today widget: pure `computeTodayProjection` (today/overdue counts,
  three focus titles, `titlesPermitted` seam for Task 10), a publisher
  bound to the active-slot lifecycle, receiver republish on placement,
  and Material typography role values. Two user rulings: overdue
  follows the prose via a new `now: Instant` parameter (a task due
  earlier today is overdue now, not at midnight), and
  `glance-material3` was approved as a second catalogue entry — it
  proved colour-only with no typography API, so it sits unreferenced
  while role values come from `material3.Typography()`; keep-or-drop
  is an open user decision. Fix round 2 extracted `StopGatedWriter`, a
  mutex gate proven by deterministic tests, so no Glance write on any
  path can land after the stop-time title clear.

**Pre-existing defect requiring its own task (controller-verified in
code):** `RECOVERED_SCHEMA_VERSION = 7` (`BackupRecordImporter.kt:443`,
`:760`) rejects any captured vault whose schema marker exceeds 7, while
`MIGRATION_7_8` sets marker 8 on every migrated vault
(`VaultDatabase.kt:1125`). This already breaks Drive recovery — and now
`.otvault` import — on migrated devices. Fresh vaults are written with
marker 7 (`RoomVaultRepository.kt:3115`), so the existing deterministic
and instrumented suites cannot catch it. Schedule a dedicated fix task
before the Task 13 gates.

Carry-forwards recorded in the ledger: Task 10 must wire the widget's
`titlesPermitted` seam to lock/privacy state; the Task 13 device
checklist gained the SAF `application/octet-stream` picker visibility
check for `.otvault` and an on-device tap test that Glance's
parameter-to-extra mapping reaches `MainActivity` as
`"open_quick_add"`. Deferred minors are in the ledger (Task 7
staging-promotion edges, Task 8 partial-batch messaging and the
untested ViewModel batch machine, Task 9 zone capture at construction
and midnight rollover).

This checkpoint's resume instruction is superseded: Task 10 was
executed on 5 August and is recorded in the checkpoint below.

## Stage 5 Task 10 checkpoint — 5 August 2026

Execution resumed from `36b98b5` with the same plan, ledger, and
independent-review-per-task discipline. The task boundary closed with
zero open Critical or Important findings after one fix round. No device
suite ran; new instrumented tests are compile-verified and execute at
the Task 13 connected gate.

- Task 10 (`a6c52eb`, fix `f76f2a6`) added the app lock, title privacy,
  and unified Quick Add: `AppLockSettings` over SharedPreferences,
  a clock-injected pure `AppLockController` (cold start locked when
  enabled; foreground after a background span >= the chosen delay
  locks; IMMEDIATE/1/5/15-minute options), and an `AppLockScreen`
  overlay that replaces all content with no workspace data composed
  behind it. Unlock is one platform `BiometricPrompt` with
  device-credential fallback and changes no key material.
  `titlePrivacy || locked` drives `titlesPermitted = false` into the
  Task 9 widget publisher strictly through the existing
  `StopGatedWriter` gate with an immediate republish on engage, plus
  the generic notification content path and generic external Quick Add
  labels. `setRecentsScreenshotEnabled(false)` applies whenever
  `lockEnabled || titlePrivacy`; `FLAG_SECURE` only under
  `screenshotBlocking`. A static launcher shortcut and the widget
  extra both route through `MainActivity.handleIntent` to the one
  shared `QuickAddSheet` after unlock; exported intents carry only the
  boolean extra. The fix round hoisted the `locked` check above the
  `activeRecovery` branch (a user-opened recovery shell with
  destructive restore/takeover actions can no longer render unlocked
  after a background span; `NoVault`/`Unreadable`/`Recovering` stay
  ungated), added the plan-named `AppLockOverlayInstrumentedTest`
  (compile-verified, runs at Task 13), and closed the silent unlock
  no-op at both ends: the overlay shows an unavailable message when
  the prompt cannot be shown, and the More toggle is gated on
  `canAuthenticate` success while an already-enabled lock can still be
  turned off.
- The open `glance-material3` decision was resolved by user ruling:
  dropped. `4652a2b` removes the catalogue entry and the unused `:app`
  dependency; Material role values continue to come from a plain
  `material3.Typography()` baseline, and the rationale comment in
  `TodayWidget.kt` stays.

Carry-forwards unchanged from the Tasks 7–9 checkpoint: the dedicated
`RECOVERED_SCHEMA_VERSION` fix task must land before the Task 13
gates, and the Task 13 device checklist retains the SAF
`application/octet-stream` picker visibility check for `.otvault` and
the Glance parameter-to-extra tap test reaching `MainActivity` as
`"open_quick_add"`; it now also covers running
`AppLockOverlayInstrumentedTest` and the runtime widget/notification
concealment checks. Deferred minors are in the ledger (notably: no
`AppLockSettings` persistence-key tests, the exactly-at-delay boundary
case, and the untested `setTitlesPermitted`/concealed-notification
branches until the device suite).

This checkpoint's resume instruction is superseded: Task 11 was
executed on 5 August and is recorded in the checkpoint below.

## Stage 5 Task 11 checkpoint — 5 August 2026

Execution resumed from `aeb013e` with the same plan, ledger, and
independent-review-per-task discipline. The task boundary closed with
zero open Critical or Important findings after one fix round. No device
suite ran; the new instrumented test is compile-verified and executes
at the Task 13 connected gate.

- Task 11 (`11e4bcb`, fix `2ec80aa`) added keyboard, mouse, and
  accessible actions: a pure `shortcutActionFor` dispatcher with the
  pinned mapping (`Ctrl+K` and `/` → search; `Ctrl+N` → Quick Add;
  `Ctrl+Shift+N` → new project only in the Projects route; `?` → help;
  `Esc` → dismiss top), single-key suppression while an editable is
  focused, a `ShortcutHelpDialog` listing every shortcut via
  `stringResource`, and root wiring in `OpenTasksApp` whose
  `DISMISS_TOP` closes help dialog → open sheet → expanded search and
  never finishes the Activity. Quick Add routes through the Task 10
  shared sheet. The accessible-action audit found no drag-only action
  on the five feature screens (workflow reordering is explicit up/down
  IconButtons). The fix round enforced the pinned phase split
  structurally: the preview handler early-returns unless Ctrl is
  pressed and dispatches only Ctrl combos, the bubbling handler
  early-returns when Ctrl is pressed and is the sole path for bare
  single keys including `/`, making the two phases mutually exclusive
  by construction, with the dispatcher KDoc and root comments corrected
  to match the real dispatch paths.
- Evidence: 15/15 `ShortcutDispatcherTest` unit tests (RED/GREEN); the
  CI gate passed; `ShortcutRootWiringInstrumentedTest` (Ctrl+K asserts
  search focus, Esc asserts sheet dismissal) compiles and runs at
  Task 13.

Carry-forwards unchanged: the dedicated `RECOVERED_SCHEMA_VERSION` fix
task must land before the Task 13 gates, and the Task 13 device
checklist retains the SAF `application/octet-stream` picker visibility
check for `.otvault`, the Glance parameter-to-extra tap test, running
`AppLockOverlayInstrumentedTest`, and the runtime widget/notification
concealment checks; it now also covers running
`ShortcutRootWiringInstrumentedTest`. Deferred minors are in the ledger
(notably: held-`Esc` key repeat can cascade the dismiss order through
several surfaces; `Ctrl+Escape` dismissed pre-fix and is now dropped by
both phase guards; the instrumented test exercises a replica of the
root wiring rather than `OpenTasksApp` itself).

This checkpoint's resume instruction is superseded: Task 12 was
executed on 6 August and is recorded in the checkpoint below.

## Stage 5 Task 12 checkpoint — 6 August 2026

Execution resumed from `7693fb7` with the same plan, ledger, and
independent-review-per-task discipline. The task boundary closed with
zero open Critical or Important findings after one fix round. No device
suite ran.

- Task 12 (`c0ad0ac`, fix `bd8f650`) added one-way calendar insertion:
  a pure `calendarEventDraft` in `:app` with the pinned rules (undated
  → null; start present → begin at `start.instant` with end at
  `due?.instant` only when strictly after; due-only → begin at
  `due.instant` with null end; description `Project: <name>` or empty),
  and an intent wrapper carrying exactly `ACTION_INSERT`,
  `Events.CONTENT_URI`, begin, conditional end, title, and description
  — no permission, no stored event id, no result handling. "Add to
  calendar" appears in the task editor and both Schedule layout modes
  only when the draft is non-null (the reviewer upheld the
  both-modes judgment call), via nullable `onAddToCalendar` lambdas
  that keep feature modules platform-free. A preview dialog shows
  title, times formatted in each moment's stored zone (UK format), and
  description with Insert/Cancel. The fix round moved the
  `feature:schedule` content-description copy into that module's first
  `res/values/strings.xml` (read via `stringResource`) and replaced the
  silent `ActivityNotFoundException` catch with the
  `calendar_no_provider` snackbar on the scaffold host, matching the
  attachment-delivery precedent.
- Evidence: 6/6 `CalendarInsertionTest` pinned cases (RED/GREEN); CI
  gate passed; `:app`, `:feature:tasks`, and `:feature:schedule`
  instrumented-test compilation green.

Carry-forwards: all 12 implementation tasks are complete; only Task 13
(qualification) remains. Before its gates, the dedicated
`RECOVERED_SCHEMA_VERSION` fix task must land — it is not in the plan;
author its brief from the Tasks 7–9 checkpoint defect record
(`RECOVERED_SCHEMA_VERSION = 7` at `BackupRecordImporter.kt:443`,
`:760` rejects the schema marker 8 that `MIGRATION_7_8` writes at
`VaultDatabase.kt:1125`; fresh vaults write marker 7 at
`RoomVaultRepository.kt:3115`, so existing suites cannot catch it).
The Task 13 device checklist retains the SAF
`application/octet-stream` picker visibility check for `.otvault`, the
Glance parameter-to-extra tap test, `AppLockOverlayInstrumentedTest`,
the runtime widget/notification concealment checks, and
`ShortcutRootWiringInstrumentedTest`. Deferred minors are in the
ledger.

This checkpoint's resume instruction is superseded: the dedicated
schema-fix task was executed on 6 August and is recorded in the
checkpoint below.

## Stage 5 schema-fix checkpoint — 6 August 2026

Execution resumed from `1767514` with the same plan, ledger, and
independent-review-per-task discipline. The dedicated pre-Task-13
`RECOVERED_SCHEMA_VERSION` fix task (not a numbered plan task; its
brief was authored from the Tasks 7–9 checkpoint defect record) closed
with zero Critical or Important findings and no fix round. No device
suite ran; the changed instrumented tests are compile-verified and
execute at the Task 13 connected gate.

- The fix (`d8c89e3`) ties the recovery gate to the Room database
  version: a shared `internal const val VAULT_DATABASE_VERSION = 9` in
  `VaultDatabase.kt` now feeds the `@Database` annotation,
  `RECOVERED_SCHEMA_VERSION` (previously a hand-tracked literal 7), and
  the fresh-vault seed (previously 7). Captured vaults with markers
  1..9 are accepted and normalized to 9; migrated devices keep row
  marker 8 and recover cleanly; a future migration can no longer reopen
  this defect class because migrations write markers at most the
  database version by construction. No migration was edited, no version
  bumped, and no exported schema or frozen fixture changed — the frozen
  `.otvault` v1 fixture's marker-9 vault record, previously rejected by
  the gate, now imports without a byte of it changing.
- Evidence: the new deterministic `BackupRecordImporterTest` ran RED
  against the pre-fix constant (3/4 cases failing) and GREEN after; the
  CI gate passed (all unit suites zero failures, lint clean, debug
  APK); `:core:data:assembleDebugAndroidTest` compiles;
  `scripts/check-schema-drift.sh` clean. `futureSchemaVersionIsRejected`
  now rejects `VAULT_DATABASE_VERSION + 1` instead of enshrining the
  defect; a marker-8 import-acceptance case and a migration-chain
  marker-bound assertion were added and run at Task 13.
- Reviewer minors are deferred in the ledger, notably: the
  migration-test guard comment overstates its reach (the deterministic
  JVM test is the real guard); two weak assertions (a bare `assertTrue`
  and an unpinned exception message); and one disclosed compatibility
  note — post-fix captures carry marker 9, which pre-fix builds would
  reject as unreadable (brief-mandated; no released builds exist).

Carry-forwards: only Task 13 (qualification) remains. Its device
checklist is unchanged from the Task 12 checkpoint (the SAF
`application/octet-stream` picker visibility check for `.otvault`, the
Glance parameter-to-extra tap test, `AppLockOverlayInstrumentedTest`,
the runtime widget/notification concealment checks, and
`ShortcutRootWiringInstrumentedTest`) and now also confirms the new
marker-8 import acceptance and migration-chain assertions execute green
on device.

This checkpoint's resume instruction is superseded: Task 13 was executed
on 6 August and is recorded in the checkpoint below.

## Stage 5 closure checkpoint — 6 August 2026

Stage 5 closes at `4df0af5` (`docs: fix stage 5 review round-2
findings`), which amends the Step 4 contract documents committed in
`8c7fcd0` (a broken threat-model table row and four architecture.md
sections the brief required), on top of the Part 1 passphrase-wipe fix
`6bfafa8`. The round-2 scoped re-review verified both amendments clean
against source with no new breakage. Execution resumed from `f0a8550`
with the same plan, ledger, and independent-review discipline; Task 13
(qualification and exit gates) is the plan's final task.

- The six-module connected gate's first run (10m13s) failed 7 tests
  across `:core:data` and `:app`. All seven were root-caused as
  test-only defects and fixed with no product code touched: a v8 seed
  fixture missing one `NULL` value, a raw-`String`-vs-`VaultId`
  assertion mismatch, `FoldContinuityInstrumentedTest` teardown that
  interleaved verification with destructive cleanup, a stale
  `lock_enabled` device baseline ahead of a recovery-restoration test,
  and a search-focus assertion racing its own `LaunchedEffect`. The
  official rerun at `d53a9f9` (9m05s) passed **293 tests, 0 failures, 0
  errors, 2 expected skips** — the credential-only qualification row and
  the exact `Pixel_10_Pro_Fold` cross-display exception, exactly the
  Stage 4 pair. This reconciles the plan's "only expected skip" wording,
  which undercounts by one. The `FoldContinuity` suite remains
  residue-independent only against residue this repository's own suites
  can create; residue from device history outside those suites, on the
  never-wiped protected AVD, can still trip the deliberate guard, and no
  code-only fix exists without weakening the guard or wiping the AVD,
  both forbidden.
- Forced-fresh `testDebugUnitTest lintDebug :app:assembleDebug` passed
  547/547 tasks with 1,045 JVM unit tests and zero failures; forced-fresh
  `:app:assembleRelease` passed 441/441 tasks. Schema drift, all three
  fixture generators, and `git diff --check` were clean. Release
  inspection found no new exported component (the widget receiver is
  `exported="false"`), no debug qualification activity, the expected
  `USE_BIOMETRIC` addition, and `auth/drive.appdata` as the sole Drive
  scope string.
- Privacy scans over the whole Stage 5 range (`1cd8cf4..d53a9f9`) found
  zero added `Log.`/`println`/`Timber` calls, shortcut and widget
  intents carrying only the boolean `open_quick_add` extra, disclosed
  calendar-intent content, and `CharArray`-only passphrase handling with
  the pending passphrase NUL-wiped.
- The final whole-branch review (`1cd8cf4..d53a9f9`, most capable tier)
  returned **Ready to merge with fixes; 0 Critical**. Its two Important
  findings are both closed by this wave: the Step 4 contract documents
  (this commit) and the export-path passphrase wipe (`6bfafa8`), which
  mirrors the import twin's existing `finally` shape exactly and needs
  no new test because the `:app` module's JVM unit tests cannot exercise
  a `ContentResolver` failure path (stub `android.jar`, no Robolectric
  by policy). Everything else in the deferred-minors backlog stays
  deferred and is bundled into the post-merge hardening task below.
- The authoritative qualification record is
  `docs/qualification/stage5-platform-features.md`.

Carry-forwards: the Samsung Remote Test Lab item is closed — the user
ruled on 6 August 2026 that RTL is unusable for the time being, so its
real-device rows lapse without verification and any future Samsung
real-device work needs a fresh decision; Play Console work remains
externally pending. The recommended post-merge hardening task is
discharged: the range `79e4aea..81cf642` (16 commits, tests plus two
sanctioned product hardenings) adds the leading-tab CSV formula prefix,
a fail-closed `.otvault` read-side inventory bound, and coverage for
every bundled item — GC shared-budget/duplicate/tombstone accounting,
`.otvault` write-time and import-policy edges (including the
duplicate-manifest guard, whose claimed unreachability the independent
review disproved), zero-attachment export, the app-lock exact-delay
boundary and literal persisted keys, shortcut dispatcher cases, the
schema-fix test polish, and the CSV batch machine's two JVM-reachable
terminal states (the two `Completed` states remain Robolectric-blocked
by design). Final gates: 1,070 JVM tests with zero failures, lint
clean, instrumented compile green, no schema drift, frozen fixtures
byte-identical. Residual one-word comment miscount and small test
housekeeping items stay in the ignored SDD ledger.

## CI restoration checkpoint — 6 August 2026 (test-fix PAUSED)

Investigation for a routine post-stage CI check found the GitHub
Actions `Android` workflow had ZERO green runs in its entire visible
history (red on every branch since at least 26 July; the 30 July
matrix rework was never proven). All local gates were always green;
the breakage was CI provisioning and CI resources.

- The CI-fix task landed `97e8016` + `b56dada` + `52f63aa` on `main`
  (pushed): minor-versioned SDK provisioning
  (`--channel=3 platforms;android-37.0`; the flat `platforms;android-37`
  id no longer exists), emulator-runner bumped to the verified v2.38.0
  SHA with quoted `api-level: "37.0"`, androidTest APKs pre-built
  before emulator boot (the API 36 emulator previously crashed under
  compile/execute overlap), runner disk-space freeing, fixed AVD disk
  size, and Gradle-daemon stop before boot.
  `scripts/verify-actions-workflow.sh` was updated in lock-step. Three
  bench iterations plus one confirmation run; the obsolete Dependabot
  emulator-runner PR is closed.
- API 37.0 verdict: the canary system image itself is defective — an
  FBE/credential-storage unlock failure cascades into SQLCipher
  `SQLiteCantOpenDatabaseException` and package-manager death
  (`INSTRUMENTATION_ABORTED`), confirmed non-resource. Documented as an
  image-quality blocker, not worked around; the matrix entry stays. A
  later canary image may self-heal (the runner fetches current canary).
- The healthy pipeline exposed real test failures, catalogued from
  baseline run `31088309158` in `ci-fix-report.md` (ignored SDD
  workspace): F1 verify JVM race
  (`BackupViewModelTest.prepareCanRunAgainAfterPreviousPublisherCallCompletes`,
  deterministic on CI); F2
  `RoomActivityGenerationInstrumentedTest.completionWritesActivityAndJournalAtTheCommandGeneration`
  (root-caused as the F3 timeout class, NOT a product defect); F3 six
  load-sensitive 5,000 ms timeouts; F4 two deterministic `:app` UI
  failures (Quick Add IME geometry; Ctrl+K cross-window focus); F5 the
  four feature modules have never executed on CI; F6 the API 37.0
  image blocker (observe only).
- The test-fix task is PAUSED with all F1–F5 fixes implemented and
  pushed on branch `test-fix` (head `91095e2`; per-finding commits
  `30ad043` F1 latch gate, `3fb9ce2` F3/F2 shared
  `DEVICE_TEST_TIMEOUT_MILLIS = 30_000` across `:core:data` device
  suites, `3e5b751` + `3bfa0c6` F4 synthetic IME insets and bounded
  cross-window focus poll, `91095e2` F5 `--continue` on the
  instrumented job). Draft PR #13 is the CI bench; bench iteration 1
  (run `31091532438`, cap 8) was IN FLIGHT and unwatched at pause.
  Local gates green at the pause point; the branch is in sync with
  origin; `main` is untouched at `52f63aa`.

Standing user rulings: land and push the test fixes to `main` only
once the matrix is fully green (verify, Instrumented API 36 all six
modules, release; API 37.0 as the image allows — if it stays
image-blocked, that residual needs an explicit user decision). The
lifecycle and upload-artifact Dependabot PRs remain open and untouched.

Resume by reading bench run `31091532438`'s outcome first
(`gh run view 31091532438 --json status,conclusion,jobs`) — never push
blindly — then continue the loop from `test-fix-brief.md` and the
RESUME STATE section of `test-fix-report.md` in the ignored SDD
workspace, either by resuming the paused implementer pattern (relay
run outcomes, one hypothesis per iteration) or a fresh agent given
those two files. F5's first-ever feature-module CI results are the
largest remaining unknown.

This checkpoint's resume instruction is superseded: the test-fix task
completed on 7 August and is recorded in the CI test-fix final
checkpoint and Ctrl+K closure above. The fully-green-matrix landing
ruling was satisfied by bench 2 (API 36 all six modules, verify,
release green; API 37.0 image-blocked per F6) and the merge of PR #13.

## Stage 4 closure checkpoint — 3 August 2026

Stage 4 closes at the commit containing the contract documents and
`docs/qualification/stage4-notes-activity-attachments-search.md`.

- The credentialed attachment qualification passed once in 606.947 s with the
  preserved `a813c41` harness. It proves only its exact chunk-create,
  readback, manifest-lookup, and cleanup properties; do not rerun it or infer
  wider provider coverage.
- Connected-gate history is retained in the qualification record: 282/10/0/1,
  then 282/1/0/1 after `1ba5d0e`, `b5e6a1f`, `3648595`, `a328695`, and
  `bf2f95a`; successive fold-guard rounds; the invalidated cascade run; and
  the final `b3da5d2` exact-AVD exception. Final result: 282/0/0/2.
- The final API 37 / Android 17 `Pixel_10_Pro_Fold` target was read-only,
  snapshot-disabled, headless, and the sole ADB device; only overlay-local
  `font_scale=1.0` changed. The preflight app was cleanly uninstalled and
  final package/ADB/host audits were empty.
- Forced-fresh debug/unit/lint/APK was 547 executed tasks and 935 JVM tests in
  80 suites with zero failures; release was 441 executed tasks. Schema drift,
  both generators, and diff-check were clean. Release exposed only
  `drive.appdata`, no debug activity or client secret, and logging scans were
  empty.
- Stage 5 must address the conservative retained encrypted bytes for purged
  attachment blob sets with a schema-backed retired-set index, and decide a
  product path for `AttachmentBlobCoordinator.resume()`; retain its tests.
  Samsung RTL is still externally blocked.

## Executive status

Open Tasks is a working local-authority Android foundation, not yet a
production release. Encrypted Room is the sole live structured-data authority.
The encrypted local workspace, adaptive shell, task editor, recurring
tasks, custom per-project workflows, editable milestones, Bin, project
workbench, search, timers and due-relative reminders persist across process
restarts. Reminder permission timing, exact-alarm fallback, lock-screen
redaction, task deep links, Snooze and Complete actions are implemented.
Workflow add, rename, reorder, archive and restore preserve immutable reporting
categories and assigned tasks.
Milestone create/edit/complete/reopen/delete and project-scoped task membership
are implemented with atomic backup-journal writes and exact Undo. Task dependency
editing, cycle rejection, dynamic blocking and named completion warnings are
also implemented. Schedule now derives real selected-day and Monday–Sunday
views from task dates and reminders, with an open-only unscheduled tray.
Routes, selected records, filters, meaningful scroll positions and bounded
drafts restore across process recreation without the initial repository
emission replacing unsaved text. Running timers resume from their encrypted
time entry and original start instant. Completed time can now be added, edited
and deleted from a task, with optional bounded notes, exact repository-produced
Undo and encrypted restart persistence. Overlapping records are deliberately
preserved and surfaced for review so double-counted work is never hidden. The
P0 hardening slice and P1-L01 through P1-L04 plus P1-L06 and P1-L07 product
slices are complete and verified. P2-F01 adds versioned reusable project
templates with relative zoned dates, deterministic relation remapping,
encrypted Room persistence and atomic backup-journal writes. Qualified workspace
Insights now cover completion, overdue work, estimate/actual time, project/tag
time and milestone health with accessible table/text alternatives. The
application is fixed to UK English with UK spelling, day–month dates, 24-hour
time and Bin terminology.

The private GitHub authority is
[ksdaklmk/open-tasks](https://github.com/ksdaklmk/open-tasks); local `main`
tracks `origin/main`. Secret scanning, push protection, Dependabot alerts and
security updates are enabled. The Android workflow now uses explicit compact
API 36/stable/Pixel 6 and expanded API 37.0/canary/Pixel Tablet instrumented
matrix entries, passes the matching channel and profile to the emulator runner,
and runs a separate release-assembly job after verification. Its local
structural verifier prevents mutable Action references. The 30 July maintenance
audit found no open pull requests or issues; it applied the queued dependency
updates directly to `main` after local verification. A blocking `PreToolUse` guard rejects raw
Kotlin `Color(0x...)` writes outside `core/designsystem`; its deterministic
protocol verifier covers both Write and Edit payloads. Non-provider secret
patterns and validity checks are unavailable in the current repository plan
and remain disabled. Stage 2 local backup and the supplementary Android
package are implemented and user-visible in More. Stage 3 create-only Tasks
1–14 are committed and verified: qualified transport, frozen formats, Room
v7 remote state, crash-safe vault slot gating, explicit authorization with
HMAC account binding, byte-bounded create-only object storage,
crash-resumable epoch-one ownership resolution, immutable successor
publication with namespace-safe cleanup, unique background scheduling,
verified staging reconstruction, recovery with writer takeover and activation,
passphrase rotation, disconnect, permanent remote-history deletion, and
separate-lineage preservation, product UI, complete recovery/takeover
qualification, and final release/privacy/protected-workspace gates. Stage 4
now qualifies attachment metadata, blob transport, and attachment product
surfaces; Play Console work remains externally pending.

Train 1 Tasks 1.1–1.5 and Stage 1 are complete. Vault-content keys are
independent of SQLCipher database keys and have separate recovery and per-vault
Android Keystore wrapping. Canonical bounded cloud frames exist for manifests,
snapshots, operation segments and attachment chunks. The authenticated
provider-independent codec binds their complete identity as AEAD associated
data and has independent deterministic vectors for all four families. Stage
2's committed Task 1–10 baseline and final-review correction add the local
backup journal, strict snapshot/segment payloads, consistent capture, verified
local recovery objects, recovery-envelope preparation, the portable package,
runtime activation, exact Android eligibility, inert restored input, and
status UI. The final review's correctness defects are closed in `f9e091b`.
The recovery, takeover, passphrase-rotation, remote-lifecycle, and product
surface work is committed through Task 14. Stage 4's attachment transport and
product flows are qualified; the committed Stage 3 Drive transport work is
recorded above. The historical
Train 1 Task 1.6 is superseded.

## Stage 3 provider and replacement-design checkpoint — 30 July 2026

The original Stage 3 design and plan were committed as `8e0cfdd`, `afb59e3`,
and `a39f117`. Task 1 then built an uncommitted debug-only credentialed
qualification path, bounded Drive HTTP transport, app-data authorization, and
provider evidence. None of that source is qualified or committed.

The credentialed investigation established:

- Google authorization, exact `drive.appdata` scope, account selection, and
  Drive API access reach the provider on the sole disposable API 37 emulator;
- pre-generated IDs are accepted for `appDataFolder` file creation;
- successful create responses provide no strong HTTP ETag;
- one approved bounded `files.get(...?fields=id)` metadata fallback also
  provides no strong HTTP ETag; and
- the final bounded result is
  `TRANSPORT_CREATE_CONTROL_CONDITIONAL_UNAVAILABLE`.

The approved hard stop was honored: there was no retry, JSON-version fallback,
staging, source commit, or Task 2 work.

The replacement design preserves the Drive-only boundary and eliminates
mutable provider control. A short immutable ownership chain uses one
pre-generated successor ID per takeover. Normal backups are immutable
publications within the active epoch. Every new epoch uploads and verifies two
independent complete bases before ownership activation. A stale old device may
finish non-authoritative old-epoch bytes, but cannot overwrite, delete, or
become authority over the new epoch.

Before any Stage 3 source commit, the revised first task must repeatedly prove
that two different authenticated claims racing one pre-generated successor ID
produce exactly one unchanged winner and bounded duplicate rejection. A
provider-property failure stops Stage 3 for another design; an authorization
setup failure blocks the qualification until diagnosed without establishing
any provider result. The untracked `artifacts/` directory remains user-owned
and untouched.

The executable replacement plan is
`docs/superpowers/plans/2026-07-30-stage-3-drive-create-only-backup-recovery-plan.md`.
Its fourteen review boundaries preserve the runtime-slot prerequisite, add a
local Room state version instead of any provider revision, give the two
same-generation bases independent remote identities, and finish with
credentialed two-installation, terminal-tombstone, protected-workspace,
privacy, schema, release, and connected gates. The older Stage 3 plan remains
historical and is not the execution authority for create-only work.

## Stage 3 revised Task 1 stopping checkpoint — 30 July 2026

Subagent-driven execution started from documentation commit `303d1ae` in the
user-approved `main` checkout. The first implementer replaced the superseded
revision-bearing boundary with uncommitted `CreateOnlyDriveTransport` and
`HttpCreateOnlyDriveTransport` work, plus the debug-only create-only
qualification harness.

Fresh local evidence recorded in
`docs/qualification/stage3-drive-create-only.md`:

- 14 focused create-only transport tests passed;
- 4 deterministic qualification tests passed;
- ten fake exact-successor races each selected one winner and one occupied
  loser;
- all thirty loser retries stayed occupied and all thirty winner readbacks
  stayed byte-identical and authenticated;
- deliberately discarded create success resolved only through exact-ID
  authenticated readback;
- deterministic cleanup-on-success and cleanup-on-failure passed;
- injected HTTP tests distinguished missing, authorization, quota, retryable,
  corrupt, provider-rejected, occupied, definite pre-request, and
  post-transmission ambiguous outcomes; and
- debug and release assemblies passed with the internal qualification
  activity present only in debug.

The exact credentialed command ran nine instrumentation tests on the sole
audited API 37 `Pixel_10_Pro_Fold` disposable. Eight passed. The explicit
credentialed test returned
`AUTH_START_ApiException_INTERNAL_ERROR_8` before the account UI completed.
No Drive provider request ran and no disposable provider object was created.
Therefore the ten live races, thirty live loser retries, unchanged live
winner readbacks, discarded-response resolution, and provider cleanup remain
unqualified. The controller re-audited that sole API 37 target, shut down only
the read-only/no-snapshot disposable, and confirmed the final ADB target list
was empty.

The hard stop was honoured: no Task 1 path is staged, no Stage 3 source commit
exists, and Task 2 did not start. The detailed ignored execution report is
`.superpowers/sdd/2026-07-30-stage-3-drive-create-only-backup-recovery-plan/task-1-report.md`.
Resume by diagnosing the authorization start failure on the same intended
Android authorization stack. Do not treat the deterministic harness as
provider evidence, and do not stage Task 1 or begin Task 2 unless a later
exact gate returns `PASS`. The historical uncommitted Stage 3 plan modification
and user-owned `artifacts/` directory remain outside scope and untouched.

Resolution, later on 30 July 2026: read-only device audit showed the freshly
booted disposable inherited zero Google accounts, while Play services,
checkin, network, and the device clock were healthy; the superseded gate had
only ever completed authorization in a session with existing signed-in
account state, which the read-only overlay discarded at shutdown. The Google
Identity Authorization API fails with bounded internal error 8 when the
device has no Google account. The user signed a Google account into the
AVD's persistent device state; it survived restart and appeared on the
freshly booted sole audited read-only API 37 disposable. The exact
credentialed gate then ran once and returned bounded `PASS` (nine tests,
zero failures; the credentialed test completed consent and the full live
provider sequence in 364.7 seconds). The CI gate passed, the Step 9
forbidden-concept scan found no matches, and Task 1 was committed as
`b6191fd`. The disposable was shut down after the run and the final ADB
target list was confirmed empty.

## Stage 3 create-only Tasks 2–6 checkpoint — 31 July 2026

Subagent-driven execution continued from the Task 1 qualification commit
`b6191fd` directly on `main`, with an independent task review after each
task and scoped re-reviews after every fix round. All five task boundaries
closed with zero open Critical or Important findings.

- Task 2 (`7ce27b1`) froze the ownership-claim and publication formats:
  opaque redacted IDs, strict authenticated codecs binding every public
  identity through the Stage 1 manifest family, publication-pair authority
  validation, and independent Node fixtures with byte-identical
  regeneration.
- Task 3 (`b0284bb`, `184786f`) added additive Room v7 remote state with a
  byte-preserving v6 fixture migration test. A user ruling added the
  `previousPublicationGeneration` column before the schema shipped, and the
  schema-drift script gained a restore-on-tool-failure path.
- Task 4 (`5fc11ae`, `839efc5`, `4f57781`) gated startup behind crash-safe
  vault slots. Fix rounds restarted the orphaned Stage 2 backup pipeline on
  session open, closed four activation-safety windows, added a real
  `AtomicFile` crash-boundary suite, and extended the staged content-key
  verification rung to the crash-restart completion path. A live disposable
  launch confirmed unrenamed legacy adoption on real migrated data.
- Task 5 (`ca70ca8`, `2bb76dc`) added explicit authorization and HMAC
  account binding behind a non-exportable Keystore key, with no token or
  identifier persistence. A user ruling enriched
  `DriveAuthorizationResult.Unavailable` with a bounded reason enum, and a
  missing Google `Account` handle now degrades revoke instead of failing
  authorization.
- Task 6 (`051ce53`, `05a1d44`, `75f94a8`) implemented create-only Drive
  object storage: exact-ID occupied/ambiguous resolution, take-once owned
  bytes/files, provider-confirmed resumable transfer with bounded restart
  and stall guards, compare-and-set persistence that fails closed, per-role
  Stage 1/2 byte ceilings, and lineage-visible small creates proven by a
  create-to-list round trip.

Deferred minors, controller rulings, and the full fix-round history are in
the ignored SDD ledger. The former Task 12 codec-tightening ruling and Task 14
live-confirmation carry-forwards are recorded there and discharged by the
later closures below. The protected workspace and user-owned `artifacts/`
were untouched; all connected and credentialed evidence came from sole
audited read-only disposables.

## Stage 3 create-only Task 7 checkpoint — 31 July 2026

Subagent-driven execution resumed from `a46f1f5` on `main`. Task 7
(`85e2f57`, `bf9bcb2`) implemented epoch-one ownership resolution and
publication and closed its independent review with zero open Critical or
Important findings after one fix round.

- `OwnershipChainStore` resolves ownership by exact successor IDs only:
  public headers navigate, the root and every successor authenticate after
  content-key unlock, a missing successor is the tip, authenticated and
  public tips must agree, and every duplicate, fork, decoy, epoch overflow,
  or bound violation (64 roots, 1,024 claims, 128 candidates) fails closed
  with no alternate slot.
- `PublicationCatalog` authenticates bounded publication candidates and
  rejects duplicate sequences, forks, gaps, generation regressions,
  competing tips, and claim/device mismatches.
- `RemoteObjectCodec` re-authenticates local Stage 2 objects and
  re-encrypts them under explicit fresh remote logical IDs, so the two
  epoch-one base copies never share identity; staged files are synced,
  cleared, and deleted on failure.
- `DefaultRemoteBackupConfigurator` runs crash-resumable epoch-one setup
  over the plan's exact ten phases: identities are generated once and
  persisted, two independent complete bases are uploaded and verified, the
  sequence-zero baseline binds the planned root and the root binds the
  baseline back, and remote backup activates only after root readback and
  full chain re-resolution. The fix round made every create-and-crash
  window resumable: intended bytes are recorded durably before the first
  network mutation, and an occupied slot is adopted only when its occupant
  authenticates at this connection's persisted identity; foreign occupants
  stay ambiguous or lost, and no alternate slot is ever generated.
- The review upheld the recorded interface deviations (sealed
  Blocked-capable discovery results, `PublicationCandidate`, a `contentKey`
  parameter on `createClaim`, nullable list lineage) as forced by the
  Task 6 list()-failure carry-forward and the frozen Task 2 formats.
- Evidence: focused RED compilation failure first; GREEN
  `:core:data:testDebugUnitTest` 317/317 (60 Task 7 tests plus 5 fix-round
  crash-window and fail-closed guard tests, mutation-verified); repository
  gate 547 tasks and separate release assembly 441 tasks passed at
  `bf9bcb2`; `git diff --check` clean. No emulator, ADB, or connected
  command ran; the protected workspace and user-owned `artifacts/` were
  untouched.

Carry-forwards for the resume session are in the ledger: Task 8 must clear
stale resumable transfer state before re-encoding a planned base and close
the `storeIdentities` orphaned-configuration-row crash window; a user
ruling is requested on whether a malformed listed ownership root may block
root discovery permanently; the deferred-minor list gained the Task 7
review and re-review items.

## Stage 3 create-only Tasks 8–10 checkpoint — 31 July 2026

Subagent-driven execution resumed from `bf9bcb2`/`26d2029` on `main`.

- Task 8 (`5495def`, `1b8feef`, `357ca98`) published immutable successor
  generations: the eleven-phase durable `DefaultRemoteBackupCoordinator`
  (checkpoint only after publication readback and a second ownership
  recheck), bounded `NamespaceSafeRemoteCleanup` (≤32 deletes, tip
  authentication before every batch, blockers retained), both Task 7
  carry-forwards closed, and the review's two Important findings fixed:
  the seven-day hold now applies only to abandoned candidates and
  old-epoch residue, and an unfulfillable frozen plan is discarded only
  when its local source is gone and its slot is provably empty. The
  reviewer disproved the "format forbids bridging" base-pair rationale;
  the same-generation pair stands as recorded policy.
- Task 9 (`6684478`, `eb26cfb`, `4ff32cc`) scheduled unique background
  work: WorkManager runtime with the exact One UI-independent plan
  cadence (15-minute debounce, 24-hour periodic, no unmetered
  requirement), non-interactive authorization that persists
  action-required state, a split `Unavailable` reason enum, a stopped
  flag making stale runners refuse after slot teardown, and completion
  re-arm only for strictly newer generations. Connected evidence:
  `:core:data` 100/100 (first device run of the Task 8 Room
  `adoptConnecting` cases, 20/20) and `:app` 13 tests on the sole
  disposable.
- Task 10 (`41d0a07`) reconstructs verified staging vaults: exhaustive
  per-family import with strict typed record access, fresh-only local
  operational state (schema marker normalised to 7), close/reopen
  canonical-capture verification, and 29 new instrumented tests
  (connected `:core:data` 129/129). Its interrupted independent review
  was re-dispatched and closed in the 1 August session recorded in the
  closure checkpoint below.

The separately approved Galaxy Z Fold 8 trifold-ready adaptive layout
slice was designed and planned in parallel (spec `1de991d`, plan
`c364a60`, user-approved) and is scheduled immediately after the Stage 3
exit gates in the execution order below.

## Stage 3 create-only Tasks 10–11 closure checkpoint — 1 August 2026

Subagent-driven execution resumed from `0cf354c` on `main`.

- The interrupted Task 10 review was re-dispatched over
  `4ff32cc..41d0a07` and returned Needs fixes (0 Critical, 3 Important,
  all in `StagedVaultVerifier`). One fix round (`a7e8ce6`) closed it:
  an unscoped structured-record count makes the verifier blind to no
  row outside the recovered vault, the replayed state is proven a valid
  Stage 2 vault through the full snapshot-codec rule set, the
  post-smoke-check settle step proves any drift is exactly
  retention-eligible trash purge (surfacing `activationGeneration` and
  `retentionPurge` on `VerifiedStagedVault`), and VAULT delete replay
  coverage was restored with a delete-and-reupsert segment — the
  apparent "DELETE for every family" plan conflict dissolved without a
  ruling. Concern verdicts: the recovered-vault capture defect was
  confirmed real and mandated into Task 11; `recoveryEnvelopeReady =
  true` was confirmed correct; the single-vault invariant was upheld.
  Connected `:core:data` closed at 134/134.
- Task 11 (`46e59ee`, `412fafa`, `b2eb2f6`) implemented
  `DefaultRecoveryCoordinator`: bounded discovery with opaque handles,
  terminal-tombstone refusal, wrong-account rejection before lineage
  access, KDF probing before derivation, full chain authentication with
  an `ACTIVE` tip match, unique publication-pair resolution, staging
  reconstruction and verification via Task 10, and a fourteen-phase
  self-contained takeover whose claim verification demands seven
  independent agreements including this device, the exact baseline, and
  no successor publication. Its review closed after two fix rounds:
  failed preparations now abandon staging and release the runtime
  in-process; a takeover always uploads two fresh independently
  identified complete bases, cross-compares the downloaded source pair
  (same-generation disagreement fails closed as ambiguity), declares
  the fresh bases at the generation they cover with retained segments
  bridging, and proves round-trip recoverability through the
  coordinator's own download path; occupied-slot claim branches and the
  dropped-`CLAIM_CREATED` resume window are covered; the mandated
  portable-decode tests exist. Stage 2 capture now works on recovered
  vaults via sole-vault attribution, and a foreign-vault-id payload
  fails closed at `prepare`. Connected `:core:data` closed at 141/141.
- Deferred minors, the amended base-declaration ruling, and the
  Task 12/13/14 carry-forwards are recorded in the ignored SDD ledger.
  Notable Task 12 items: the approved equal-generation codec
  tightening, the recovery-envelope derivation divergence after
  rotation, the settled-state capture-legitimacy decision for
  purge-orphaned parent links, and normalising the raw NUL character
  literal that makes grep treat `DefaultRecoveryCoordinator.kt` as
  binary.

## Stage 3 create-only Task 12 checkpoint — 1 August 2026

Subagent-driven execution resumed from `a14b818` on `main`. Task 12 closed in
four scoped commits: `d42ed14` (lifecycle implementation), `1c5b8cf` and
`ab9e9ce` (two review fix rounds), and `3109108` (test-only projection wait
found by the final device gate).

- `DefaultRecoveryPassphraseChanger` uses the exact seven durable phases. It
  verifies the current passphrase, publishes and reads back the portable and
  immutable replacements, reauthenticates the exact ownership tip immediately
  before promotion, and atomically promotes the SQLCipher envelope last. It
  retains the same content key, writer epoch, local generation, claim, active
  device, and inventory; only publication sequence and recovery-credential
  generation advance. Ordinary publication, rotation, and history deletion
  share one active-vault publication gate.
- Disconnect persists `DORMANT` before cancelling work and performing bounded
  non-interactive token cleanup. It makes zero Drive file list/read/create/
  delete calls, and revocation failure cannot reactivate the lineage.
- Permanent deletion creates and authenticates one exact `TERMINATED`
  successor and never allocates an alternate or deletes the terminal marker.
  The encrypted operation state durably records exact authenticated inventory,
  locally known objects, first-observed residue ages, bounded role/page cursors,
  and deletion progress. Publications, snapshots, and segments are exhausted
  before claims; a 64-page-per-role cap and final empty full rescan fail closed;
  one invocation shares a 32-delete budget; the exact terminal reauthenticates
  every later batch; young unauthenticated residue waits seven days; root is
  deleted last; resume after root loss reads the exact terminal directly.
- Divergent-work preservation is available only from locally recorded
  `OWNERSHIP_LOST`, requires the bound account non-interactively, and calls the
  existing configurator with an explicitly separate lineage. It imports,
  merges, or reactivates no lost-lineage record.
- Task 11 carry-forwards closed at shared roots: equal-generation retained
  publications require a strictly newer recovery credential; recovery stages
  the same envelope selected from the authenticated current publication and
  fails on divergence; permanent and retention purges detach surviving direct
  children in the same generation and journal their after-images; obsolete
  unreleased takeover state remains strictly rejected; the raw NUL literal is
  written as escaped `\u0000`.

The initial independent review found one Critical and four Important issues:
remote-only history could survive terminal deletion, rotation could race the
ordinary publisher, promotion could resume without fresh ownership, payload
and claim cleanup did not share the 32-delete budget, and deleting root first
made terminal resume fail. Fix round 1 closed four and most of the first; the
fresh re-review required durable page caps, direct inventory candidates, and a
final empty rescan. Fix round 2 closed that remainder. The final fresh verdict
is APPROVED with no open Critical, Important, or Minor finding.

Final evidence:

- The first connected `:core:data` run completed 141/142; its sole failure was
  the new test reading an asynchronous workspace projection before its second
  created task appeared, so the purge path had not run. `3109108` changed only
  that fixture to use the file's established bounded observation wait. Its
  focused rerun passed 1/1, then the complete sole-disposable API 37 run passed
  142/142 with zero failures or skips.
- The emulator was `Pixel_10_Pro_Fold`, API 37 / Android 17, 390 dpi, launched
  `-read-only`, `-no-snapshot-load`, `-no-snapshot-save`, `-no-window`, and
  `-no-boot-anim`. Its inherited 2.0 font scale changed only in the disposable
  overlay to 1.0. Snapshot save was ignored at shutdown; final ADB and qemu
  process audits were empty.
- Forced fresh `testDebugUnitTest lintDebug :app:assembleDebug` passed 547/547
  executed tasks. Forced fresh `:app:assembleRelease` passed 441/441 executed
  tasks including R8, resource shrinking, and release packaging. The Room
  schema-drift script passed with database version 7 unchanged. Scoped and
  working `git diff --check` and the added-code hygiene scan were clean.

Task 13 owns the user-facing backup/recovery surfaces and keeps the recorded
manual "back up now" retry affordance distinct. The then-deferred Task 14 live
Account derivation/revoke and R8 reachability evidence is discharged below.
The pre-existing historical Google Drive plan amendment and user-owned
`.kotlin/` and `artifacts/` remain untouched and unstaged.

## Stage 3 create-only Task 13 closure checkpoint — 1 August 2026

Task 13 is complete. Fix round 3 added a distinct `RETRYABLE_PROVIDER`
presentation category shared by discovery, prepare, and confirmation, with
bounded temporary-unavailability copy and no false Sign in guidance. The
restoration check now seeds the real recovery inbox before `MainActivity`
launch, enters the production recovery route, recreates the Activity, and
proves the private passphrase is not restored. No production test hook or new
runtime abstraction was added.

Closure evidence:

- the focused host gate passed 530 tasks and the scoped review found no
  remaining Critical or Important issue;
- the sole read-only API 37 disposable passed app 14 tests with one expected
  credentialed-only skip and feature:more 52/52, including the new transient
  failure and genuine Activity recreation cases;
- forced fresh debug/unit/lint passed 547/547 executed tasks and forced fresh
  release/R8 passed 441/441 executed tasks;
- Room schema drift, working/scoped `git diff --check`, and added-code hygiene
  checks passed; and
- the disposable emulator shut down with snapshots disabled and final ADB and
  emulator-session audits empty.

Task 14 supplied the final evidence below and closes Stage 3.

## Stage 3 create-only Task 14 closure checkpoint — 1 August 2026

Task 14 and Stage 3 are complete in the change with subject
`docs: verify create-only Stage 3 backup`, started from `a325017`. The
authoritative evidence is
`docs/qualification/stage3-google-drive-create-only-backup-recovery.md`.

The deterministic production-protocol end-to-end test uses isolated encrypted
Room and create-only provider contexts to prove epoch-one setup, incremental
publication, resumable process death beyond 5 MiB, staged recovery, two-base
takeover, exact-successor contention, stale-owner exclusion, fallback,
same-generation passphrase rotation, divergent-lineage preservation,
disconnect/reconnect, wrong-account rejection before provider access,
terminal cleanup resumption, tombstone-only final state, and an independent
inert Android package. Canonical workspace bytes match before backup and after
recovery.

That coverage exposed and closed root causes in the logical schema seed,
post-takeover passphrase ownership binding, durable ownership-loss state,
dormant reconnect ordering, and forced complete-baseline capture for recovered
and separate lineages. The debug credentialed harness also now cleans only its
exact marker objects after interruption and has a twenty-minute outer bound;
individual provider calls retain their fifteen-second bound.

Final evidence:

- the credentialed live provider gate passed in approximately six minutes,
  proving ten exact-ID races, thirty rejected loser retries, unchanged winner
  readbacks, exact-ID ambiguity resolution, and cleanup;
- the full sole-disposable API 37 connected gate passed 275 tests with zero
  failures or errors and one intentional credential-only skip;
- the final repository debug/unit/lint gate passed 547 Gradle tasks (20
  executed, 527 up-to-date) and 790 JVM tests in 66 suites; the release gate
  passed 441 tasks including R8, shrinking, optimization, and packaging;
- schema drift and create-only fixture regeneration passed without a diff;
- release inspection found only `drive.appdata`, excluded the debug activity,
  and found no application mutable-authority or client-secret string;
- privacy scans contained only redacted declarations, tests, negative or
  historical evidence, public endpoints, and runtime-library symbols; and
- the protected named snapshot matched package, database/WAL/SHM, visible
  record/project, and active-timer state without install, instrumentation,
  uninstall, clear, restore, backup-manager, or snapshot-save mutation.

The provider gate validates the live create-only coordination primitive. The
full lifecycle is deterministic production-protocol evidence; no second live
account or destructive second physical installation was used, and no broader
live claim is made. No private identifier, credential, or workspace content is
recorded.

That next approved adaptive action is now complete and recorded in the
checkpoint below.

## Galaxy Fold 8 trifold-ready adaptive slice closure — 2 August 2026

The approved adaptive slice is complete in the implementation range
`7276f90..ddbe52a`, including the visual-acceptance corrections `38a84f8`,
`da75a9e`, `9cc6057` and `0368dcf` and the final-review corrections `74d3064`
through `ddbe52a`. Its authoritative record is
`docs/qualification/fold8-adaptive-acceptance.md`.

The slice maps AndroidX window layout information to a model-independent
`WindowPosture`, applies One UI-aligned 42/58 and 38/62 pane fractions, snaps
vertical separating folds to the hinge, excludes editor content from a
horizontal tabletop hinge, and preserves bounded drafts and selection across
fold-driven Activity recreation. Fixture ownership and continuity acceptance
are isolated from the protected workspace.

Task 5 passed a 38/38 primary matrix across the API 37
`Fold8_Acceptance` cover/main displays and `Fold8_Ultra_Acceptance` main
display at 100% and 200% text. The selected `Client research` workbench was
verified rather than an empty Projects state. Acceptance found and closed the
medium-width Schedule routing, large-text project progress and large-text
cover status defects in `38a84f8`, `da75a9e` and `9cc6057` respectively. Review
then found clipped Quick Add actions above the IME; `0368dcf` made the shared
sheet scrollable and added a focused device regression. A further 8/8 focused
matrix passed on the physical cover at a verified 332×532 dp at both text
scales, covering compact navigation, Tasks single-pane behaviour, editor
scrolling and Quick Add with Gboard visible. Both disposable AVDs ran
sequentially with read-only, snapshot-disabled flags, were shut down, and left
empty ADB and emulator-process audits. The protected `Pixel_10_Pro_Fold` was
not started or mutated.

Final review corrected pane snapping for trailing safe insets and odd-width
trifold centres, made continuity-fixture database deletion targets
(WAL/SHM/journal/wipecheck/master journals) and active-slot sidecar ownership
fail closed, strengthened recreation/configuration/scroll checks,
asserted both pane widths within a 1 dp rounding tolerance, and made the task
editor's accessibility state use the current localized draft title. Focused
device reruns passed the continuity fixture's two executable rows (the native
transition row skipped on the DEFAULT-only AVD), Tasks pane tests 2/2 and
Projects pane tests 4/4.

The AVD exposed physical main and cover displays but no native AndroidX
`FoldingFeature`, separating fold or hinge. A single bounded fold/unfold probe
left `cmd device_state` at identifier `0` (`DEFAULT`). Physical-display
evidence therefore validates adaptive size-class surfaces only; Task 3's
instrumentation is the independent evidence for synthetic 50/50 hinge snap,
and no native emulator hinge claim is made.

Samsung Remote Test Lab remains **External-blocked** because the user's
Samsung developer account approval is pending. Real-device cover-to-main draft
continuity, One UI taskbar overlap, Samsung keyboard on both displays,
split-screen one-half and one-third widths, pop-up view, and physical hinge
alignment remain outstanding. No sign-in page was opened and no credential was
requested or handled. This recorded external gap does not reopen the completed
emulator slice, but it must be cleared before real-device or One UI integration
claims.

Pause after this checkpoint. Stage 4 was subsequently requested, designed,
planned, and started; its in-progress state is recorded in the checkpoint
below.

## Stage 4 Tasks 1–3 in-progress checkpoint — 2 August 2026

The user explicitly requested Stage 4. Brainstorming produced the approved
combined design (`7b5af15`) covering first-class notes, immutable activity
history, the create-only cloud attachment blob lifecycle, and search
extension; its one deliberate supersession replaces the 2026-07-28 design's
conditional control-manifest wording with the Stage 3 create-only lineage
model. The fourteen-task execution plan (`6538dca`) was then approved and
subagent-driven execution began directly on `main`. Each task closed with an
independent review and, where needed, scoped re-reviewed fix rounds.

- Task 1 (`1e30b3b`) added `NoteId`, the `Note` record,
  `WorkspaceSnapshot.notes`, the `AddNote`/`UpdateNote`/`DeleteNote`/
  `RestoreNote` commands with exact repository-produced Undo, bounds
  (10,000-char body, 500 per owner), three new rejection reasons, complete
  `InMemoryVaultRepository` behaviour, and fixture notes. Review: approved,
  no fix round. `RoomVaultRepository` carries compile-only stub arms until
  Task 4.
- Task 2 (`bfc51e4`, fix `f4d4451`) shipped Room v8: the `notes` table, the
  attachments rebuild dropping `keepOffline` and adding
  `blobSetId`/`chunkCount`/`deletedAtEpochMillis`/revision columns with
  existing rows preserved, the `attachment_transfer` session table, exported
  `8.json` (drift script clean), the finalised `Attachment` model with
  `BlobSetId`, snapshot `attachments`/`activityEntries` fields, mappers, and
  the v7→v8 preservation migration test. The fix round reshaped two
  instrumented-test attachment seeds that still used the dropped column.
- Task 3 (`a887732`, fix `ab5c3d0`) added the `NOTE` backup family
  end-to-end across the mutation codec, journal session
  (revision-based note and attachment snapshots), payload referential
  validation (exactly-one-owner, owner-present), recovery import with
  ciphertext zeroing, capture DAO, staged-vault verification and
  retention-purge rules, in-memory journal parity, and regenerated
  independent fixtures; it also finalised the ATTACHMENT record semantics.
  The reviewer independently confirmed the pre-Stage 4 attachments table had
  no write path, so the in-place finalisation of the frozen record shape is
  safe: no encoded old-shape instance can exist. The fix round added
  mutation-proven journal-wiring assertion tests. A record carrying
  `keepOffline` now fails strict decode by test.

Deferred minors and rulings live in the ignored execution ledger
`.superpowers/sdd/2026-08-02-stage-4-notes-activity-cloud-attachments-search-plan/progress.md`;
notable entries: Task 4 must replace the Room note stub arms' `INVALID_STATE`
rejection with real persistence, and the plan-mandated `nonNegativeLong`
NOTE-timestamp strictness was adjudicated defensible. No device suite ran;
all new instrumented tests are compile-verified only and run at the Task 14
connected gate.

The pause was honoured; Task 4 subsequently closed in the checkpoint below.

## Stage 4 Task 4 closure checkpoint — 2 August 2026

Subagent-driven execution resumed from `ab5c3d0`/`18fb577` on `main`.
Task 4 (`74563d1`, `bc4a0ce`) persisted notes through Room commands and
closed its independent review as Approved with zero Critical, Important, or
spec findings and no fix round.

- `WorkspaceDao` gained `observeNotes()` (parameterless, matching the
  sibling snapshot observers), `upsertNote`, `deleteNote(id): Int`,
  `deleteNotesForTask`, `deleteNotesForProject`, and the validation helpers
  `getNoteById`/`countNotesForOwner`; `purgeTask` deletes the task's notes
  before the task row.
- The four Room dispatch arms replace the recorded `INVALID_STATE` stubs
  and are line-for-line behaviour-equivalent to the Task 1 in-memory
  handlers — identical branch order, rejection reasons, user messages,
  revision math, and Undo shapes — wrapped in `database.withTransaction { }`
  so the surrounding `execute` journal diff emits NOTE rows without
  hand-written journal code. Write validation reads the DAO directly rather
  than the plan's literal `currentWorkspace()` wording; the reviewer
  confirmed the literal wording would have read stale pre-command state and
  upheld the deviation as the file's universal handler convention.
- A pre-review parity fix (`bc4a0ce`) made the in-memory
  `permanentlyDeleteTask` and the independently divergent
  `purgeExpiredTrash` strip purged tasks' notes with journalled NOTE
  deletes, with two new mutation-asserting unit tests; the reviewer
  verified the Room and in-memory purge paths now match.
- Evidence: RED compile failure first; GREEN
  `:core:data:compileDebugAndroidTestKotlin` plus
  `:core:data:testDebugUnitTest` 432/432. The new
  `RoomNoteCommandInstrumentedTest` (round trip, exactly-one-owner, journal
  evidence, purge) is compile-verified and executes at the Task 14
  connected gate. No emulator, ADB, or connected command ran; the protected
  workspace and user-owned files were untouched.

Deferred minors are in the ignored execution ledger; notable: Room orders
`WorkspaceSnapshot.notes` by creation time and id while in-memory appends
unsorted — sort before Task 6's UI reads it. Tasks 5–14 (activity
generation, search, attachment metadata commands, blob store, intake,
open/share/cache, GC and destructive deletion, runtime/recovery wiring,
product surfaces, and the qualification/exit gates) have not started.

Pause here at the user's request. Resume by re-entering
superpowers:subagent-driven-development with the plan and ledger above,
starting at Task 5 (activity history generation) from base `bc4a0ce`.

## Stage 4 Task 5 closure checkpoint — 2 August 2026

Task 5 (`45335ae`) generates immutable activity history in both repositories
and closed its independent review as Approved with zero Critical, Important,
Minor, or spec findings and no fix round.

- `ActivityKind` replaces the former free-form kind string and the unused
  `immutable` flag. Snapshot mapping skips unknown stored kinds instead of
  crashing, while both repositories now populate
  `WorkspaceSnapshot.activityEntries`.
- The specified task and project command handlers emit activity through one
  repository-local helper. Bodies truncate at 500 characters; each task or
  project retains the newest 500 entries with deterministic
  creation-time-then-ID eviction. Note commands emit no activity, and the
  attachment call sites remain assigned to Task 7.
- Room inserts and prunes activity inside the command transaction, so the
  existing before/after journal diff records both upserts and pruning deletes
  at the command generation. In-memory backup snapshots now include activity
  and permanently purged tasks lose their activity in parity with Room.
- Evidence: the focused in-memory suite failed RED on the missing generation
  behaviour, then passed GREEN. The final
  `:core:data:testDebugUnitTest :core:data:compileDebugAndroidTestKotlin` gate
  passed. `RoomActivityGenerationInstrumentedTest` is compile-verified and
  executes at the Task 14 connected gate; no emulator, ADB, or connected
  command ran.

The Task 4 deferred snapshot-note ordering minor remains assigned to Task 6.
Tasks 6–14 have not started. Resume Task 6 from `45335ae` with the approved
plan and execution ledger.

## Stage 4 Task 6 closure checkpoint — 2 August 2026

Task 6 (`ee28a16`) extends search to note bodies and active attachment display
names and closed its independent review as Approved with zero Critical,
Important, Minor, or spec findings and no fix round.

- Both repositories group notes by task or project and non-tombstoned
  attachment names by task, then append those fields to the existing
  `SearchNormalizer` text bundles. Activity bodies remain excluded; result
  types, filters, ordering, and the 50-result cap are unchanged.
- The Task 4 note-ordering minor was load-bearing for queries that span joined
  note boundaries. Both search implementations now order owner notes by
  creation time and ID before joining, matching Room without changing snapshot
  or UI ordering.
- `SearchExtensionTest` covers task and project notes, live and tombstoned
  attachment names, activity exclusion, diacritic normalisation, and the
  cross-note ordering case. The focused test failed RED on the missing search
  inputs and ordering, then passed GREEN with the full `core:data` unit suite.
  The final `testDebugUnitTest lintDebug :app:assembleDebug` repository gate
  also passed.

Tasks 7–14 have not started. Resume Task 7 from `ee28a16` with the approved
plan and execution ledger.

## Stage 4 Task 7 closure checkpoint — 2 August 2026

Task 7 (`24315cb`, corrected by `cdea044`) adds attachment metadata commands
in both repositories and closed its independent review after one fix round.

- Register validates the active owner task, sanitised bounded display name,
  bounded MIME type, byte and chunk limits, exact 4 MiB chunk arithmetic,
  lowercase SHA-256 text, and the 100-active-attachment task cap. Room now
  observes and seeds attachment rows through the existing v8 DAO and mapper.
- Delete retains the tombstoned row, emits `ATTACHMENT_REMOVED`, and returns
  exact `RestoreAttachment` Undo. Restore clears the tombstone without new
  activity; register emits `ATTACHMENT_ADDED` and deliberately has no Undo.
  Both accepted Room mutations remain inside the existing atomic journal
  transaction, with in-memory parity.
- Review found that reusing an active attachment ID could bypass a full target
  task's cap. The fix treats an ID as a replacement only for the same owner;
  the regression fills the destination to 100 and verifies rejection and
  unchanged ownership.
- The focused attachment suite passed after its recorded RED/GREEN cycles,
  `:core:data:compileDebugAndroidTestKotlin` passed, and the final
  `testDebugUnitTest lintDebug :app:assembleDebug` gate passed with 547
  actionable tasks and no failures. No emulator, ADB, or connected command
  ran.

Tasks 8–14 have not started. Resume Task 8 from `cdea044` with the approved
plan and execution ledger.

## Stage 4 Task 8 closure checkpoint — 2 August 2026

Task 8 (`da5488d`, corrected by `131f6b6`) adds the provider-neutral
`AttachmentBlobStore`, strict authenticated blob-set manifest codec, and
create-only Drive adapter. Its independent review closed after one fix round.

- The manifest codec binds the exact lineage and blob-set identity, uses
  canonical strict JSON, and enforces the 25-chunk, 4 MiB-per-chunk, and
  100 MiB aggregate bounds with exact indexes and byte-count sums.
- The Drive adapter uses only immutable create-by-ID app-data operations,
  exact attachment role/property tags, pre-transport family ceilings,
  bounded reads, opaque pagination, and fail-closed duplicate or malformed
  manifest discovery. Frozen Stage 3 formats and roles remain unchanged.
- A Node `crypto` generator independently produces the committed manifest
  frame fixture; `AttachmentGoldenTest` verifies byte and digest identity.
  Review fix round 1 added malformed-row and long opaque-token regressions.
- The generated fixture was reproduced deterministically, focused attachment
  codec/store/golden tests passed, and the final
  `testDebugUnitTest lintDebug :app:assembleDebug` gate passed with 547
  actionable tasks and no failures. No emulator, ADB, or connected command
  ran.

Tasks 9–14 remain. Continue Task 9 from `131f6b6` with the approved plan and
execution ledger.

## Stage 4 Task 9 closure checkpoint — 2 August 2026

Task 9 (`3bf8ce9`, corrected by `00d7786`) adds the bounded durable
`AttachmentBlobCoordinator`, transfer DAO, intake/resume/expiry state machine,
and hostile-input matrix. Its independent review closed after one fix round.

- Intake persists exact generated object IDs before any create, streams one
  4 MiB plaintext chunk and one ciphertext frame, verifies exact-ID readback,
  creates and verifies the manifest last, then registers metadata only through
  `VaultRepository.execute`.
- Resume adopts only exact authenticated occupied chunks and reconstructs a
  missing aggregate hash one bounded chunk at a time. Registration replay is
  semantically idempotent in both repositories, preventing duplicate records
  or `ATTACHMENT_ADDED` activity across the repository-success/DAO-crash gap.
- Persisted phases and their state are strictly validated. Ownership is
  checked before initial and manifest creates; stale expiry authenticates and
  deletes only a session's exact IDs. Hostile source open/read/close failures,
  lying sizes, readback mismatches, and unsafe names fail inside the sealed
  result contract.
- The focused intake and attachment-command tests plus
  `:core:data:compileDebugAndroidTestKotlin` passed. The final
  `testDebugUnitTest lintDebug :app:assembleDebug` gate passed with 547
  actionable tasks and no failures. No emulator, ADB, or connected command
  ran.

Tasks 10–14 remain. Continue Task 10 from `00d7786` with the approved plan and
execution ledger.

## Stage 4 Task 10 closure checkpoint — 2 August 2026

Task 10 (`90643ff`, corrected by `e627925` and `5d9fc19`) adds the
authenticated attachment open path, bounded ciphertext-frame LRU cache, and
FileProvider share directory. Its independent review closed after two scoped
fix rounds.

- Open authenticates the manifest and each chunk, streams one cleared
  plaintext chunk at a time, and succeeds only after exact byte-count and
  aggregate-hash checks. Missing bytes are unavailable; corrupt bytes fail
  closed.
- The cache stores only verified ciphertext at canonical hashed paths,
  enforces `min(128 MiB, availableBytes / 20)` by oldest access, and sweeps on
  construction. Traversal never follows symlinks, writes require atomic
  replacement, and malformed or unreadable entries are confined cache misses.
- Non-successful streaming may leave partial caller-owned output, so callers
  must discard it; Task 13 owns the share-file lifecycle. The focused 14-test
  cache/open suite and resource processing passed.
- The final `testDebugUnitTest lintDebug :app:assembleDebug` gate passed with
  547 actionable tasks and no failures. No emulator, ADB, or connected command
  ran.

Tasks 11–14 remain. Continue Task 11 from `5d9fc19` with the approved plan and
execution ledger.

## Stage 4 Task 11 closure checkpoint — 2 August 2026

Task 11 (`4639f02`, corrected by `c9f3ff5`, `80c6529`, `c2b3fe2`, and
`73b8922`) adds attachment garbage collection, attachment-only destructive
deletion, and attachment cleanup during terminal vault deletion. Its
independent review closed after four scoped fix rounds.

- Garbage collection uses exact current/previous-generation and 30-day
  eligibility, streams arbitrary finite pages, retains at most 32 candidates,
  deletes chunks before manifests, and reauthenticates ownership. Unknown or
  hostile state blocks deletion.
- Attachment-only deletion requires the passphrase and active remote tip,
  persists lineage-scoped role/cursor progress, preserves metadata and backup
  history, and uses an exact-role chunk probe before manifest deletion.
- Terminal deletion shares the 32-object budget, removes attachment roles
  before claims, preserves opaque pagination tokens, and reauthenticates
  authority after the final chunk probe and immediately before a manifest
  delete.
- Focused attachment and lifecycle tests, both Android-test compile gates, and
  the final `testDebugUnitTest lintDebug :app:assembleDebug` gate passed with
  547 actionable tasks and no failures. No emulator, ADB, or connected command
  ran.

Stage 4 was paused after Task 11 and resumed in the 2–3 August session
recorded in the checkpoint below.

## Historical Stage 4 Tasks 12–14 pre-qualification checkpoint — 3 August 2026

Subagent-driven execution resumed from `2d3ca4f` on `main`.

- Task 12 (`f323457`, fix `2db7e26`) wired the attachment runtime:
  per-vault-slot construction of the store, coordinators, cache, and
  collector in `AppModule` beside the `publicationGate` collaborators;
  lifecycle-gated `AttachmentRuntime` (non-ACTIVE lineages make zero store
  calls, tip mismatch refuses intake/GC); GC only after `Verified`
  publication; session expiry on start; teardown with the slot. Review
  closed after one fix round adding the shared GC eligibility pre-filter
  and the journalled `MarkAttachmentContentCollected` command (both
  repositories, no schema bump) so collected blob sets stop being
  candidates; the destructive-deletion path releases markers via one
  callback. The extended E2E proves the 9 MiB intake → publish → recover →
  open-on-B → stale-A-refused → delete → GC → terminal-tombstone scenario
  and asserts records persist with `blobSetId` cleared.
- Task 13 (`5967c67`, fix `b2604c0`) added the product surfaces: model-free
  `TimelineList`/`NotesTimelineSection` in designsystem; thin
  `NotesActivitySection` mappers in tasks and projects; attachment rows
  with state strings, 48 dp targets, and TalkBack descriptions; the Cloud
  attachments block in Backup & recovery (connection line, combined
  encrypted-frames-plus-staging cache figure, passphrase-guarded content
  deletion); `AttachmentIntakeViewModel` owning pickers, sanitised intake,
  FileProvider share staging, and per-attachment row states. `5967c67` is
  an amend of `c95a825` purging a raw-NUL binary blob from history. Review
  closed after two fix rounds (record-keyed note drafts, staging-cache
  wipe + usage accounting, dedup extraction, neutral unavailable copy,
  record-derived UNAVAILABLE after content deletion, atomic row-state
  updates). Accepted deviations: `onRetry` retries the open (an unfinished
  intake has no record); no in-row intake progress.
- The whole-branch review (`6538dca..b2604c0`, most capable model) found
  one Critical — note-edit Undo was a silent no-op in both repositories —
  plus in-memory purge keeping attachments and a missing startup sweep of
  plaintext share staging. The single fix wave (`f98d1c3`) closed all
  three with executed-round-trip tests; the scoped re-review confirmed no
  new breakage. Two recorded rulings stand as known limitations with
  Stage 5 backlog entries: purged attachments' blob sets are never GC
  candidates (conservative leak; destructive/terminal deletion still
  clears bytes; durable fix needs a schema-backed retired-set index), and
  `AttachmentBlobCoordinator.resume` has no product caller (interrupted
  intakes expire after 24 h; `resume()` and its tests are kept).
- Task 14 is in progress. The extended credentialed live gate PASSED once
  on the sole audited read-only API 37 disposable (bounded PASS in
  606.9 s; live exact-ID chunk create/occupied rejection, byte-identical
  readbacks, single-manifest lookup, exact-ID cleanup; harness committed
  as `a813c41` — the credentialed gate must NOT be re-run). The first
  full six-module connected gate ran 282 tests with 10 failures and the
  one expected credential-only skip. Fix commits so far: `1ba5d0e` (E2E
  task selection), `b5e6a1f` (stale post-Task-5 activity expectations),
  `3648595` (notes-section scroll), `a328695` (collected-attachment
  projection wait), `bf2f95a` (fold-wait retry). The second full
  six-module gate then ran 282 tests with ONE failure and the expected
  credential-only skip (core:data 7→0, feature:projects 1→0, app 2→1).
  The suspected Room activity-pruning parity divergence was disproven:
  Room's `ORDER BY id` snapshot made the test alternate a task into its
  existing status, short-circuiting all 501 commands; Room's 500-cap
  eviction is now genuinely proven. The sole remaining failure is the
  pre-existing, out-of-scope fold-continuity test described in the status
  block. The emulator procedure was followed exactly (read-only, no
  snapshots, audits empty; protected workspace and account untouched).
- Next session, in order: get the user's ruling on the fold-continuity
  failure (second recorded expected skip vs separate fix); then Task 14
  Steps 3–5 with the recorded controller rulings (scoped add only; the
  qualification doc and this file must record the first gate's 10
  failures, every fix commit, the second gate's counts, and the
  throwaway-first-`:app`-run behaviour on a fresh overlay). Then the
  Task 14 task review, ledger closure, and workspace deletion.

## Historical Stage 3 create-only Task 13 in-progress checkpoint — 1 August 2026

Task 13 began from `3109108` and is paused at `bc1c283`, not complete. Its four
commits are `cc2959a` (product surfaces), `6f0cb57` (active replacement route),
`d87e1de` (account binding and runtime-state fixes), and `bc1c283` (failure and
restoration hardening).

Implemented source at this checkpoint:

- More renders stateless Encrypted app backup and Android backup package cards.
  The active card exposes explicit connect, Back up now, passphrase change,
  disconnect, permanent deletion, and ownership-loss recovery actions according
  to lifecycle capability.
- `MainActivity` creates active-only services only for an active vault and uses
  the inert recovery shell for NoVault, Unreadable, Activating, Recovering, and
  active replacement. NoVault retains Start without restoring; active
  replacement does not.
- Recovery authorization carries the known account-binding digest before
  lineage discovery, and every authorized discovery, prepare, and confirmation
  session closes at the bounded operation boundary. Resolution-based
  reauthorisation resumes the shared `RemoteBackupRuntime.requestNow` path.
- Runner in-flight state drives Backing up with the local generation. Recovery
  failures expose bounded localized reasons, Error semantics, Drive retry, and
  the independent Android-package recovery action. Passphrases remain
  `CharArray` at service boundaries, non-saveable, and cleared in `finally`.

Review state:

- The first review found one Critical and six Important issues. Fix round 1
  closed account binding, takeover confirmation, requestNow resumption, token
  lifetime, failure actions, Backing up state, lifecycle capabilities, and most
  interaction coverage.
- Fix round 2 added truthful provider-storage/corrupt categories,
  exception/cancellation session-close tests, production digest/status-flow
  tests, and a restoration fixture. Its fresh re-review approved four of six
  focused findings but left two Important issues: transient `RETRYABLE`
  authorization failures still produce false Sign in guidance, and the
  restoration fixture never launches or recreates `MainActivity` or enters the
  production recovery route.
- Resume fix round 3 with the original implementer. Add a truthful bounded
  transient-provider recovery category/copy aligned across the active spec,
  model, mapping, UI, and tests. Replace the fixture-driven restoration case
  with a genuine Activity/production-route recreation test. Delete redundant
  fixture coverage if the real test subsumes it.

Evidence at the pause:

- Focused Task 13 tests and the app/feature host gate passed; the latest host
  gate completed 530 tasks.
- The final sole-disposable API 37 connected run at `bc1c283` passed 15 app
  tests total (14 pass, one expected credentialed skip) and feature:more 51/51.
  The AVD ran read-only with snapshot load/save disabled, opened posture,
  390 dpi, and a disposable 2.0→1.0 font overlay. Final ADB/qemu audits were
  empty.
- Final repository-wide forced rerun, release/R8, and pause schema gates were
  deliberately not claimed because Task 13 still has two open Important review
  findings. Run them only after fix round 3 is independently approved.
- The historical Google Drive plan amendment and user-owned `.kotlin/` and
  `artifacts/` remain untouched and unstaged.

## Stage 2 final-review correction checkpoint — 30 July 2026

The broad final review of `cc816ab..ccffefb` initially returned **Not Ready**
with three Critical and six Important findings. The single correction wave,
whose source diff began at `ccffefb` and crossed the documentation-only pause
commit `a02242a`, closes all nine:

- routine snapshot and segment work uses only `openExisting()`; guarded
  bootstrap is the sole key-creation authority and fails closed on any durable
  encrypted-backup evidence;
- complete capture includes the full unscoped Inbox workflow only when one
  workspace owns it unambiguously, and snapshot validation requires every
  Inbox semantic status;
- schema-v6 `backup_state` writers mutate the latest row inside a serialized
  Room transaction, merge only owned fields, and preserve package, envelope,
  restored-status, failure, object, and checkpoint truth;
- authenticated local crash residue is reconciled before generic restored
  intake for both durable `PREPARING` and legacy `NOT_PREPARED` boundaries;
- Retry feeds the active single-owner runtime loop without concurrent owners;
- the exact recovery inbox is durable startup truth and blocks publication
  even when status persistence previously crashed;
- transient intake read/open I/O retains eligible bytes as retryable blocking
  work, while deterministic invalid established packages remain withdrawable;
- the 5,000-operation snapshot threshold is decided by a capture-bounded
  count before journal payload rows are materialised; and
- generation advancement atomically changes stale `READY` to
  `UPDATE_PENDING`, with defensive runtime presentation for legacy residue.

Correction commit and verification:

- `f9e091b` — `fix: harden stage 2 backup state transitions` (20 Kotlin
  production/test files; Room remains schema version 6).
- Focused core-data correction tests passed 48/48; focused app backup tests
  passed 56/56. Affected app/data Android-test source compilation passed
  195/195 tasks.
- `./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
  --rerun-tasks` passed with 547/547 tasks executed and 390/390 JVM tests:
  App 102, Crypto 27, Data 165, Domain 44, and Sync 52.
- `./gradlew :app:assembleRelease --stacktrace --rerun-tasks` passed with
  441/441 tasks executed, including R8 and resource shrinking.
- Fixture regeneration produced no diff. Deterministic scans found only the
  intentional v5 `DRIVE_PRIMARY` migration fixtures and the negative
  `Backed up` UI assertion. `git diff --check` passed.
- The first connected attempt exposed test-only dispatcher and JUnit-signature
  defects in the new instrumentation scaffolding. Their focused rerun passed
  15/15 after correction.
- The complete four-module connected gate passed 387/387 executed tasks and
  128/128 tests: Crypto 28, Data 52, App 5, and More 43. XML evidence records
  zero failures, errors, and skips.
- The sole target was the API 37 / Android 17 `Pixel_10_Pro_Fold` AVD launched
  read-only with snapshot load/save disabled, no window, and no boot
  animation. Its inherited disposable font scale was changed from 2.0 to the
  suite baseline 1.0. The shutdown did not save a snapshot; final ADB and
  emulator-process audits were empty.
- A scoped line-by-line review against the correction brief, strict codec and
  package bounds, buffer ownership, migration rules, Android eligibility, and
  forbidden Stage 3 scope found no open Critical or Important issue.

Encrypted Google-account Android transport upload/restore remains external
qualification. No provider, WorkManager, recovery activation, writer
takeover, remote merge, or attachment transport was added. The protected
workspace and user-owned `artifacts/` remained untouched.

## Stage 2 Task 1–10 historical verification checkpoint

This checkpoint records the committed baseline before the final review. It is
historical evidence; the correction checkpoint above is authoritative.

The approved subagent-driven execution ran directly on `main`:

| Task | Result | Commit(s) |
|---:|---|---|
| 1 | Replaced product sync-facing contracts with local backup models, policy, and coordinator boundaries | `b579e9e` |
| 2 | Added additive Room v6 backup journal/state/envelope schema and preserved deterministic v5 legacy rows | `ebab71f`, `66e535f` |
| 3 | Journalled accepted local mutations under one atomic generation; removed active legacy outbox writes | `3a8520c`, `313047e` |
| 4 | Froze strict canonical snapshot/segment payload v1, golden fixtures, and vault-scoped consistent capture | `8f74219`, `f2b3a92`, `6811f3c` |
| 5 | Added crash-safe local recovery-object lifecycle, authenticated readback coordinator, checkpoint, retention, threshold, failure, cancellation, and coalescing behavior | `2a72670` |
| 6 | Prepared, verified, persisted, and failure-hardened the recovery envelope without replacing the existing content key | `99c9905`, `492a84b` |
| 7 | Added the bounded authenticated portable-package codec and crash-safe atomic publisher | `d89c0c1`, `4dea896`, `c9ca105` |
| 8 | Activated runtime coordination, exact Android allow-list, and inert restored-package intake | `92fc531`, `970bcde`, `0c8baf9` |
| 9 | Added adaptive More status/setup UI with masked non-saveable passphrase handling and bounded retry states | `79da8c2`, `26fc840`, `21bf211`, `a4069c7` |
| 10 | Ran the complete exit, Android transport, UI, protected-workspace, and release gates and reconciled active contracts | This checkpoint |

Fresh Stage 2 exit evidence:

- Deterministic source scans found only intentional v5 `DRIVE_PRIMARY`
  migration fixtures and a negative Compose assertion that **Backed up** does
  not appear. The Stage 2 v1 fixture generator produced no diff.
- `./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
  --rerun-tasks` passed with 547/547 tasks executed and 371/371 JVM tests:
  App 87, Crypto 27, Data 161, Domain 44, and Sync 52.
- The sole connected target was the disposable `Pixel_10_Pro_Fold`, API 37 /
  Android 17, opened, 390 dpi, started with `-read-only`,
  `-no-snapshot-save`, `-no-snapshot-load`, and `-no-window`. Its inherited
  2.0 font scale was changed only in the disposable overlay to the suite's
  1.0 baseline.
- The forced connected command passed 387/387 Gradle tasks and 122/122 tests:
  Crypto 28, Data 46, App 5, and More 43. This covers content-key failure
  modes, v5→v6 migration and encrypted restart, packaged eligibility,
  restored input, process recreation, and the complete More suite.
- Normal disposable UI setup produced and verified a 30,479-byte generation-0
  package with file evidence
  `573729:30479:files/android_backup/open_tasks_portable_v1.otb`. The
  passphrase fields were masked. Ready copy reported only local generation,
  bytes, and production time and made no upload claim. Visual acceptance
  passed at 100%, 130%, and 200% text in expanded, half-opened, and
  compact/closed postures.
- Packaged extraction rules and device tests prove that the package path is the
  sole eligible include. A separate ordinary `files/profileInstalled` file
  existed but was not eligible. Room, WAL/SHM, preferences, keys, credentials,
  cache, local staging, and attachment bytes remain excluded.
- Backup Manager was enabled on disposable state. The Google transport
  destination reported **Add a backup account now**. The selected debug-only
  local transport lacked the encryption capability required by the
  cloud-backup rule and rejected the package with full-data error `-1002`; no
  valid app dataset token existed. The uninstall/restore sequence therefore
  did not run. Actual encrypted Google transport upload/restore remains
  external qualification, and no upload or restore success is inferred.
- `./gradlew :app:assembleRelease --stacktrace --rerun-tasks` passed with
  441/441 tasks executed, including R8, resource shrinking, release packaging,
  and `:app:assembleRelease`.
- The named protected snapshot loads correctly only in its recorded hidden-Qt
  graphics posture with `-snapshot`, `-no-snapshot-save`, and
  `-qt-hide-window`. Two earlier renderer-mismatched starts fell back to cold
  state and were stopped before any install or data operation. The accepted
  load completed in 1.193 seconds.
- The protected snapshot's saved baseline is font scale 1.0, not the unsaved
  later 2.0 runtime value previously recorded. Before installation its exact
  identity was UID `10232`, first install `2026-07-28 05:53:59`, CE inode
  `549494`, database/WAL/SHM inodes `567204`/`567205`/`567234`, and sizes
  `4096`/`379072`/`32768`.
- The debug APK installed in place with `adb install -r`; no uninstall, clear
  data, instrumentation, or `bmgr` touched protected state. SQLCipher opened
  without a migration/runtime failure. `Reconcile July invoices`,
  `Finish launch proposal`, `Transcribe research interviews`, their projects,
  and the active timer remained visible across force-stop/cold relaunch.
  Package identity and all inodes stayed exact; the migrated WAL grew to
  `436752`. More showed generation-zero v6 initial backup state. Deterministic
  migration coverage plus this protected continuity prove legacy-row
  preservation without unsafe direct SQL inspection of the encrypted database.
- Reloading the named snapshot restored the exact original `TEST_ONLY`,
  no-`ALLOW_BACKUP` package flags, 379,072-byte WAL, package identity, and
  CE/database/WAL/SHM inodes. Both shutdowns ignored snapshot save. Final ADB
  and emulator process state were empty.
- Independent Task 1–9 reviews have no open Critical or Important findings.
  Deferred Minors remain in the ignored SDD ledger and do not reopen these
  completed boundaries.

## Train 0 baseline checkpoint verification

Train 0 Task 0.2 completed on 27 July 2026 against the API 37
`Pixel_10_Pro_Fold` AVD (Android 17, emulator 36.6.11). The debug APK installed
in place and the original workspace survived the cold process restart. The
first combined connected-test attempt exposed an AGP cleanup collision:
`:app:connectedDebugAndroidTest` targets and uninstalls `app.opentasks`.
The pre-run `default_boot` snapshot restored the exact original package and
data identity (UID `10331`, first install `10:08:16`, CE inode `573462`,
database/WAL/SHM inodes `573474`/`573476`/`573478`). That verified recovery was
saved as `train0_task02_recovered`.

The checked-in delivery plan now isolates the application suite on a sole
ADB-connected read-only emulator started with `-read-only`,
`-no-snapshot-save`, and `-no-snapshot-load`. It passed 3/3 there. The normal
emulator was then restored from `train0_task02_recovered`; packaged manifests
confirmed that Data, Tasks, Projects, Schedule, and More target isolated module
packages rather than `app.opentasks`. Their rerun passed 54/54:
Data 22, Tasks 18, Projects 9, Schedule 2, and More 3.

The first 54-test run found one transient recurrence-test failure: the task
table emission could arrive before the copied tag/checklist relation emission,
although the relations were written in the same Room transaction. The failure
did not reproduce in two isolated reruns. The test now waits for the complete
relation state before asserting; its isolated rerun and the full 54-test suite
both pass.

Post-suite force-stop/relaunch and ADB UI inspection passed Home, Tasks,
Projects, Schedule, More, Quick Add, the project `Save as template` journey,
and the manual `Add time entry` journey. Both sheets were cancelled without
creating records. The existing projects, overdue tasks, and running
`Finish launch proposal` timer remained visible, and the package/database
identity above was unchanged after the module suites.

## Status vocabulary

| Status | Meaning |
|---|---|
| Done | Implemented and verified for its current scope |
| Ready | Can be started without another project task |
| Paused | Active work deliberately stopped at the user's request; resume from the recorded checkpoint |
| Blocked | Cannot be completed until the named dependency or external input exists |
| Deferred | Deliberately ordered after higher-priority work |
| External | Requires an account, policy decision, physical-device session or store operation |

## Completed product foundation

### Application and adaptive UI

- Single-activity Kotlin/Compose app with Navigation 3, Hilt and Material 3
  Adaptive.
- Five destinations: Home, Tasks, Projects, Schedule and More.
- Compact navigation bar and medium/expanded navigation rail.
- One-pane and list/detail task and project workbenches selected by
  `WorkspaceLayoutPolicy`, including separating-fold handling.
- Responsive task editor now uses the available detail-pane width rather than
  the whole device width. Narrow panes stack Planning content, and option
  groups wrap instead of being clipped.
- Navigation labels stay readable at 100% and 130% text and deliberately
  collapse before wrapping at 150% and 200%.
- Final Galaxy Fold 8 visual acceptance passed a 38/38 primary matrix on API
  37 Fold 8 cover/main and Fold 8 Ultra main displays plus an 8/8 focused
  332×532 dp cover matrix, all at 100% and 200% text. Physical-display evidence
  validates the adaptive surfaces; Task 3 synthetic instrumentation validates
  50/50 hinge snapping because the AVD reported no native folding feature. The
  strengthened native transition row skipped because the AVD exposed only
  DEFAULT, so no native recreation/continuity claim is made.
  Samsung RTL is External-blocked pending account approval, so no real-device
  or One UI integration claim is made.

### Local workspace

- Encrypted task CRUD, core-field editing, debounced auto-save and exact Undo.
- Granular checklist and reusable tag editing with relation-safe Undo.
- Independent first-class workflows for every project and Inbox, including
  add, rename, explicit reorder, archive and restore; semantic completion
  behaviour and blocked-completion acknowledgement remain repository rules.
- Thirty-day Bin, restore, startup expiry, permanent delete and sync
  tombstones.
- Adaptive Project Workbench with create, edit, archive, restore, progress,
  workflow counts, milestone lifecycle editing and deep links.
- Project milestones support create, rename, optional due dates, complete,
  reopen and confirmed delete. Deleting clears assigned task memberships in
  the same transaction; Undo restores the milestone and captured memberships.
- Task milestone membership is limited to the selected project's milestones.
  Project moves clear membership; exact Undo restores the prior project,
  workflow status and milestone ID.
- Task prerequisites can be searched, added and removed in a bounded editor.
  Links survive prerequisite completion, but only unfinished linked tasks
  contribute to `blockedBy`; reopening a prerequisite blocks dependants again.
  Self-links, transitive cycles and more than 100 prerequisites are rejected.
- Active projects can be saved as reusable templates containing their active
  workflow, open milestones and open task structure. Using a template shifts
  local/zoned dates from a chosen anchor, resets progress, remaps parent,
  milestone, tag, checklist and dependency relationships, and creates all
  records plus backup-journal entries atomically.
- Blocked completion is enforced by the repository and confirmed consistently
  from every implemented completion path. The confirmation names unfinished
  prerequisites, while reminder notifications omit unsafe Complete actions.
- Universal search across the implemented local records.
- Compact Schedule shows a navigable selected-day agenda; expanded Schedule
  groups the containing Monday–Sunday week by actual local task dates and
  exposes an open-only unscheduled tray. Both views retain project, blocking,
  completion and reminder context and open the existing task editor.
- First-class timer and manual time history. Completed entries support add,
  edit and delete with exact Undo, a 500-character note bound and a 10,000-row
  per-task cap. Running entries remain timer-owned and read-only. A deterministic
  linear interval sweep exposes overlaps without deleting, truncating or
  silently merging recorded work.
- Process recreation preserves the current destination, selected task/project,
  task filter, list/editor scroll, search and quick-add input, and task/project
  drafts. Restored drafts win over the first repository emission; timer
  continuity remains a durable Room concern.
- One-time sample workspace seed followed by Room as the sole local authority.

### Recurring tasks

- Daily, weekly, monthly and yearly frequencies.
- Intervals, multiple weekly weekdays, count limits and end dates.
- Stable series ID, original wall-clock anchor and occurrence index.
- DST-safe wall-clock scheduling, non-drifting month-end scheduling and
  deterministic occurrence IDs.
- Completion and next-occurrence creation are one Room transaction with
  separate backup-journal entries.
- Repeated completion or redelivery creates exactly one next occurrence.
- Completion Undo reopens the original and removes only the generated
  occurrence.
- Editing a generated occurrence and undoing that edit restores its exact
  recurrence rule, due time, series ID, anchor and occurrence index.
- Room v1→v2, v2→v3, v3→v4, v4→v5 and v5→v6 migrations are non-destructive and
  preserve encrypted data; v3 creates project/Inbox workflows and remaps
  existing task statuses, v4 adds milestone revisions and v5 adds template
  revisions; v6 adds local backup journal/state/envelope tables and preserves
  every legacy outbox row.
- On-device Compose coverage exercises every cadence, interval editing,
  multiple weekdays, count ending, 200% text, 48 dp targets and keyboard
  activation.

### Reminders and notifications

- One persisted due-relative reminder per task with deterministic identity.
- Task and reminder editor changes, Undo and independent backup-journal entries
  are committed atomically.
- Flexible delivery uses an idle-safe inexact alarm. Precise delivery uses an
  exact alarm only while Android special access is granted and falls back
  safely if access is absent or revoked.
- Notification permission is requested only when the user selects a reminder;
  disabled app/channel state and precise-timing fallback are explained inline.
- Alarm payloads contain opaque IDs. The notification channel is private on
  the lock screen and supplies generic public content.
- Notification taps open the task. Snooze schedules 15 minutes later and
  Complete executes through the repository; blocked tasks omit Complete.
- Reboot, package replacement, time/time-zone changes and exact-access changes
  reconcile future alarms.
- Recurring occurrences inherit reminder lead time and precision. Completion
  Undo and permanent task purge queue reminder deletions with their task
  operations.

### Data, cryptography and sync foundations

- Every write is a typed `DomainCommand` executed by `VaultRepository`.
- Room writes and ordered backup-journal entries are atomic; the legacy outbox
  is read-only.
- SQLCipher database with a random 256-bit key wrapped by a non-exportable,
  unlocked-device-required Android Keystore AES-GCM key.
- Tink AES-256-GCM vault-content keys are independently generated from the
  SQLCipher key. The same content key is wrapped separately by an Argon2id
  recovery envelope and a per-vault Android Keystore key for local use.
- Existing database and vault-content envelopes fail closed if their Keystore
  key is lost, replaced or invalidated; a new key is never silently
  substituted. Failed preference or alias operations restore the prior
  envelope where possible and preserve the original failure.
- Canonical v1 cloud frames use a fixed-order strict UTF-8 JSON header and raw
  ciphertext. Decoding validates the 16 KiB header cap, per-family ciphertext
  bounds, exact frame length, versions, attachment chunk tuple and SHA-256
  checksum before exposing a one-shot owned ciphertext buffer.
- The ciphertext limits are 1 MiB for manifests, 64 MiB for snapshots, 16 MiB
  for operation segments and 4 MiB plaintext plus the fixed crypto-v1 overhead
  for each of at most 26 attachment chunks. Payload-model caps are 10,000
  manifest inventory entries, 100,000 snapshot records and 10,000 operations
  per segment.
- Golden vectors cover Argon2id output and associated-data encoding.
- Tests cover wrong passphrase, weakened KDF metadata, ciphertext/envelope
  tamper, associated-data swapping, passphrase change, key zeroisation and
  second-device decrypt.
- Hybrid logical clock and deterministic scalar, set and tombstone merge
  primitives cover clock rollback, redelivery, idempotence and arrival order.
- Repository shutdown now cancels and joins the Room observation job before
  its owner closes SQLCipher. This fixes a real connection-pool race found by
  the restart suite.
- Template payload v1 is self-contained and bounded to 2 MiB. Its strict
  decoder validates metadata binding, sizes, counts, workflow semantics, zones,
  relative dates and acyclic relationship graphs before use. SQLCipher protects
  the local rows and outbox; damaged template rows can still be deleted safely.
- Time-entry add, update, delete and restore use the same typed-command and
  exact-Undo contract in both repositories. Room commits each record change and
  its ordered backup-journal entry atomically; the legacy outbox remains
  read-only migration input.
  Workspace snapshots derive the running timer, full history and representative
  overlap conflicts from the same persisted stream.

### Security and maintenance

- Threat model, asset inventory, trust boundaries, dependency review,
  residual risks and release gates are in
  [docs/threat-model.md](docs/threat-model.md).
- Architecture lifecycle and security references are aligned in
  [docs/architecture.md](docs/architecture.md).
- Android backup and device-transfer rules exclude vault data and keys.
- Current source scan found no application logging calls or committed secrets.
- Dependabot is configured weekly for Gradle and GitHub Actions.
- Tracked-history audit found no credential, private-key, OAuth-client or
  provider-token patterns in the tracked tree or its existing history.
- `.gitignore` excludes local properties, environment files, signing keys,
  OAuth/service-account files, local vault databases/exports and generated
  release artefacts.
- 30 July 2026 maintenance resolved the eight queued Dependabot updates:
  Gradle 9.6.1; Compose BOM 2026.06.01; Hilt 2.60.1; Kotlin serialization
  1.11.0; AndroidX Window 1.5.1; and SHA-pinned `checkout` v7,
  `setup-java` v5 and `setup-android` v4. `testDebugUnitTest`, `lintDebug`,
  debug and release assembly, the workflow structural verifier, and the full
  API 37 disposable device matrix passed. The first device run failed only
  because the disposable Fold had booted closed at 2.0x font scale; the same
  failures reproduced on the pre-update baseline. Re-running opened at 1.0x
  passed in 8m16s.
- A useful repository rollback point now exists:
  `806090a Establish Open Tasks baseline`.

## Work completed in this P0 pass

| ID | Result | Evidence |
|---|---|---|
| P0-01 | Established a real baseline commit after auditing ignored files and scanning for secrets | Commit `806090a` |
| P0-02 | Re-audited the codebase and reconciled the stale recurrence handoff with the implementation | This handoff, architecture and threat-model updates |
| P0-03 | Completed the recurrence rule matrix and edge-case acceptance | Unit tests for all cadences, intervals, weekdays, count/end date, month-end and DST; nine Tasks device tests |
| P0-04 | Hardened duplicate completion, restart/redelivery and exact Undo | In-memory and encrypted Room regression tests |
| P0-05 | Added current-slice accessibility acceptance | 200% text, 48 dp/click semantics and keyboard focus/Enter activation on-device; narrow/fold visual checks |
| P0-06 | Added API 36/37 instrumented CI jobs, repaired to explicit compact/expanded runner profiles and a separate release gate | `.github/workflows/android.yml`; `scripts/verify-actions-workflow.sh`; YAML parsed locally |
| P0-07 | Completed threat and direct-dependency review | `docs/threat-model.md`; weekly Dependabot configuration |
| P0-08 | Added crypto golden, tamper, wrong-passphrase and key-loss coverage | Core crypto unit suite and Android Keystore device suite |
| P0-09 | Rehearsed every migration released at the P0 gate | The then-current v1→v2 encrypted migration device test passed; later P1 migrations are recorded below |
| P0-10 | Strengthened multi-device foundations | Second-device recovery test plus merge/HLC rollback, retry and order tests |
| P0-11 | Fixed repository teardown ordering | All 12 encrypted Room/Keystore device tests pass without connection-pool crashes |
| P0-12 | Verified R8/resource shrinking and installed final debug build in place | Release assembly passes; app data retained after cold restart |
| P0-13 | Published the audited history to GitHub | `main` tracks the now-private `origin/main`; GitHub secret scanning and push protection enabled |

## Work completed in this P1 pass

| ID | Result | Evidence |
|---|---|---|
| P1-L01 | Added persisted reminders, notification actions, in-context permission timing and exact-alarm fallback | Repository/JVM tests, encrypted Room restart/recurrence tests, Compose device test, app scheduling policy tests, architecture/design/threat-model updates |
| P1-L02 | Added independent project/Inbox workflows with add, rename, explicit reorder, archive/restore and semantic-preserving task moves | Repository/JVM tests, encrypted Room restart and v2→v3 migration tests, Tasks/Projects Compose device tests, in-place migration and visual QA, architecture/design/threat-model updates |
| P1-L03 | Added milestone create/edit/complete/reopen/delete, exact membership Undo and project-scoped task assignment | Repository/JVM tests, encrypted Room restart/outbox and v3→v4 migration tests, Tasks/Projects Compose device tests, architecture/design/threat-model updates |
| P1-L04 | Added searchable task prerequisites, cycle/limit rejection, dynamic unblock/reblock semantics and named blocked-completion warnings | Repository/JVM tests, encrypted Room restart/outbox tests, Tasks Compose device tests, full neighbouring-screen regression suite, live Fold visual QA, architecture/design/threat-model updates |
| P1-L06 | Replaced the static Schedule mock with a real compact day agenda, expanded date-grouped week timeline and open-only unscheduled tray | Schedule Compose device tests, reminder/status context checks, compact/unfolded Fold visual QA and 200% text acceptance, architecture/design updates |
| P1-L07 | Completed process restoration for navigation, selection, filters, list/editor scroll, search/quick-add and task/project drafts; bounded every saveable text input and kept running timers in encrypted Room state | Saved-state unit and Compose device tests, encrypted Room restart/timer test, keyboard-submit length guard, architecture/design/threat-model updates |

## Work completed in this P2 pass

| ID | Result | Evidence |
|---|---|---|
| P2-F01 | Added reusable project templates with active workflows, open milestones, open task structure, relative zoned dates, deterministic relation remapping, exact capture/delete Undo and atomic instantiation/outbox writes | Domain/codec/repository JVM tests; 21 encrypted Room/migration device tests; 9 Projects and 3 More Compose device tests; live unfolded Fold visual QA; schema v5; architecture/design/threat-model updates |
| P2-F03 | Added task-scoped manual time-entry add/edit/delete, optional notes, exact Undo, encrypted persistence and explicit timer/manual overlap reconciliation without silent data loss | Domain and repository JVM tests; 22 encrypted Room/Keystore/migration device tests; 18 Tasks Compose device tests; live unfolded Fold editor visual QA; release/lint verification; architecture/design/product/threat-model updates |
| P2-I18N | Fixed the application to UK English and aligned visible terminology/date handling | `en-GB` per-app locale, UK formatters, Organisation/Bin copy, live installed-app verification and product/design documentation |

## P0 verification record

The final source state passed:

```bash
./gradlew :app:assembleRelease --stacktrace
./gradlew testDebugUnitTest lintDebug --stacktrace
./gradlew :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest --stacktrace
./gradlew :app:installDebug
```

Results:

- All debug unit tests passed.
- Lint passed with zero errors and 20 non-blocking warnings: version update
  notices, one obsolete `mipmap-anydpi-v26` folder and one existing modifier
  parameter-order warning.
- R8 minification, resource shrinking and release APK assembly passed.
- All 28 device tests passed on the API 37 Pixel 10 Pro Fold emulator:
  12 data/Room/Keystore, 9 Tasks, 5 Projects and 2 More.
- Debug APK installed over the existing app; no uninstall or data clear was
  performed.
- Cold restart succeeded and the UI hierarchy confirmed persisted workspace
  content, including `Persistence check edited`.

Operational note: do not combine `lintDebug` and `assembleRelease` in the same
parallel Gradle invocation. AGP 9.3.1 lint can race KSP while release Hilt
sources are replaced, producing a transient missing
`Hilt_MainActivity.java`. Running release assembly and unit/lint as the two
phases above is stable. This is a tooling race, not a lint finding.

## P1 verification record

The combined P1-L01 through P1-L04 plus P1-L06 and P1-L07 local source state
passed:

```bash
./gradlew testDebugUnitTest lintDebug --stacktrace
./gradlew :app:connectedDebugAndroidTest \
  :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest --stacktrace
./gradlew :app:assembleRelease --stacktrace
./gradlew :app:installDebug
```

Results:

- All debug unit tests passed.
- Lint passed with zero errors. The app report contains 20 non-blocking
  version/folder warnings; Projects and Tasks each retain one existing
  modifier-order warning.
- R8 minification, resource shrinking and release APK assembly passed.
- All 50 affected device tests passed on the API 37 Pixel 10 Pro Fold
  emulator: 3 app restoration, 19 data/Room/migration, 16 Tasks, 8 Projects,
  2 More and 2 Schedule.
- Debug APK installed over the existing workspace without uninstall or data
  clear. Room v2→v3 and v3→v4 were both exercised in place across the P1
  slices; persisted projects and tasks remained visible, project workflow
  counts resolved to their project-scoped statuses and milestone records
  gained revision metadata without data loss.
- Visual QA passed for Home, Tasks, the reminder controls, Project Workbench
  and the project workflow editor on the unfolded display. The editor exposes
  readable category/task context, 48 dp save/up/down/archive actions and a
  scrollable add/restore path without clipping.
- Live milestone visual QA passed on the unfolded API 37 Fold display. The
  create editor renders as a bounded, readable bottom sheet and the task
  editor exposes only `No milestone` plus open milestones from its selected
  project, without clipping or cross-project options.
- Milestone verification covers v3→v4 migration, encrypted restart, atomic
  milestone/task outbox writes, create/edit/complete/reopen/delete callbacks,
  project-filtered assignment and exact delete/project-move Undo.
- Dependency verification covers encrypted restart, atomic task outbox
  payloads, add/remove Undo, completion resolution, reopen reblocking,
  self/transitive-cycle rejection and the 100-link cap.
- Live dependency visual QA passed on the unfolded API 37 Fold display. The
  searchable sheet is bounded and readable, exposes completion/project
  context and a `0/100` count, and the blocked-completion confirmation names
  the unfinished prerequisite without clipping.
- Schedule visual QA passed on both Fold displays. The compact day agenda
  remains usable at normal and 200% text, while the unfolded view exposes real
  Monday–Sunday columns beside the unscheduled tray with no hard-coded dates.
- Process-restoration acceptance covers serializable navigation, selected
  record IDs, filters, task-list and task/project editor scroll, quick-add,
  search re-query, unsaved editor drafts and active-timer elapsed continuity
  across an encrypted database restart.
- All saveable user text is bounded before it enters Android saved-instance
  state. Domain editors retain one over-limit character so validation remains
  visible; search queries are capped outright. Quick Add's keyboard action now
  enforces the same title limit as its button.
- The final restoration hardening rerun passed all 27 affected device tests:
  3 app restoration, 16 Tasks and 8 Projects.
- Source checks found no application logging calls, credential patterns or
  whitespace errors.

## P2 verification record

The P2-F01, P2-F03 and UK-English source state passed:

```bash
./gradlew testDebugUnitTest lintDebug --stacktrace
./gradlew :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest --stacktrace
./gradlew :app:assembleRelease --stacktrace
./gradlew :app:installDebug
```

Results:

- All debug unit tests and lint checks passed with zero errors.
- R8 minification, resource shrinking and release APK assembly passed.
- The latest P2-F03 affected rerun passed all 40 device tests on the API 37
  Pixel 10 Pro Fold: 22 encrypted Room/Keystore/migration tests and 18 Tasks
  Compose tests. The preceding P2-F01 acceptance also passed 9 Projects and
  3 More tests.
- Template coverage includes relative date/DST-safe wall-clock shifting,
  start-only recurrence anchors, deterministic child IDs, relation remapping,
  progress reset, exclusion rules, payload round-trip, oversize/metadata/cycle
  rejection, encrypted restart, v4→v5 migration, atomic outbox writes and
  capture/delete Undo.
- Live unfolded-Fold visual QA passed for Home, More, the empty Templates
  library, the Project Workbench template action and the capture sheet. It
  exposed and fixed singular count copy before the final build.
- Time-entry acceptance covers strict positive intervals, bounded optional
  notes, the per-task entry cap, encrypted restart, add/update/delete outbox
  writes, exact add/edit/delete Undo and deterministic linear overlap
  reconciliation. Running timer rows remain read-only until stopped.
- Tasks Compose acceptance covers task-scoped history, UK 24-hour ranges,
  date/start/duration/note editing, add and delete callbacks, explicit overlap
  warning/review and 48 dp actions. Live unfolded-Fold visual QA confirmed the
  editor sheet remains bounded, readable and unclipped.
- The final debug APK was installed in place without clearing the existing
  encrypted workspace. The observed UI used `Monday, 27 July`, Bin and other
  UK-English terminology.
- Source scans and `git diff --check` found no new user-visible US-English
  spellings, default-locale date formatters or whitespace errors.

## Train 1 Tasks 1.3–1.5 completion and protected baseline

Train 1 is being executed from
[the checked-in Train 1 plan](docs/superpowers/plans/2026-07-27-train-1-insights-cloud-format-plan.md)
with the approved subagent-driven workflow directly on `main`.

Task 1.3 completed through these independently reviewed commits:

- `cd4de59` — `feat: add pure workspace insights engine`
- `0ae7de2` — `fix: preserve historical insights selection`
- `29c7fb8` — `feat: complete qualified insights metrics`
- `f677fb7` — `fix: qualify insights display totals`
- `2b4df62` — `feat: add accessible workspace insights`
- `aeebbc48` — `fix: preserve live insights qualifications`
- `3bd1f4c0` — `fix: respect insights lifecycle boundaries`
- `f39af40e` — `fix: defer background insights projection`

The correction rounds made restored selections type-safe, refreshed time and
zone qualifications only while foregrounded, preserved restored internal
navigation, exposed every qualified metric, rendered positive sub-minute time
visibly, and kept 200% text in a readable stacked layout. Background workspace
emissions now defer analytics projection until foreground re-entry, and the
boundary scheduler remains tracked across clock jumps.

Final Task 1.3 evidence:

- App JVM: 25/25 passed after the final lifecycle correction;
- More device suite: 24/24 passed after the navigation correction;
- App device suite: 3/3 passed on a sole disposable API 37 emulator;
- debug assembly passed after the final source changes;
- four compact, light, 200%-text Insights captures passed direct inspection,
  including overdue metadata and complete milestone due/health/progress;
- three independent correction re-reviews closed with zero open findings.

Light mode is the required application acceptance colour scheme. Existing dark
theme support remains best-effort and is not a release gate.

Task 1.4 completed through three independently reviewed commits:

- `e2b2dfa` — `feat: add independently wrapped vault content keys`
- `15f15f7` — `fix: harden local vault key isolation`
- `376d35e` — `fix: preserve vault key delete failures`

The final Task 1.4 boundary passed 19/19 crypto JVM tests, 22/22 Android
Keystore tests on a sole disposable API 37 emulator and a forced 257/257-task
crypto-plus-app debug build. Two correction rounds closed failure-atomic
preference rollback, exact UTF-16 vault identity, fail-closed alias loss and
replacement, delete rollback and primary-exception preservation. The protected
workspace was then restored and its complete identity re-verified.

Task 1.5 completed through three independently reviewed commits:

- `7f779e3` — `feat: define bounded encrypted cloud object format`
- `10a2390` — `fix: preserve bounded cloud identity`
- `ce8f5bf` — `fix: isolate verified ciphertext ownership`

The final Task 1.5 boundary passed 28/28 focused cloud-format tests, 39/39
`core:sync` JVM tests, a forced 257/257-task sync-plus-app debug build, release
assembly and a byte-for-byte audit of all four v1 fixtures. Two correction
rounds closed lossy malformed-surrogate identity encoding, full-size buffer
copy amplification, circular fixture expectations and a hostile-stream alias
that could otherwise mutate ciphertext after checksum verification. For
ciphertext reads, the final decoder passes only an 8 KiB-bounded scratch buffer
to the source and retains a separate verified ciphertext array for one-shot
ownership transfer.

At the historical Task 1.5 checkpoint, the repository-wide command
`./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace` was
blocked by five pre-existing `UnrememberedMutableState` findings in
`feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt`
at lines 220, 277, 306, 347 and 572. Task 1.5 did not change that file. Its
final pause audit reproduced only that blocker: the command exited 1 at
`:feature:more:lintDebug` after 12 seconds with 469 actionable tasks (27
executed, 2 from cache and 440 up-to-date). Stage 1 Task 2 subsequently
resolved all five findings, and Task 7 reran the complete repository gate
successfully, as recorded in the Stage 1 checkpoint below.

The following block is historical replacement-baseline evidence from before
the Stage 1 exit restoration; it is not the current protected-state record.
The Android SDK update and Android Studio run coincided with loss of the old
snapshot identity, although causation was unproven. The user authorised the
fresh installation as the replacement protected baseline. The untouched
pre-replacement AVD clone remains at
`/private/tmp/open-tasks-avd-recovery.m7hw3u`. The verified replacement snapshot
was `task13_fixround1_replacement_20260728_055359`, and the emulator used at
that checkpoint was started from it with `-no-snapshot-save`.

Historical replacement protected identity:

```text
Pixel_10_Pro_Fold, API 37 / Android 17
device state: 2 (opened)
font scale: 1.0
night mode: no (light)
package UID: 10232
firstInstallTime: 2026-07-28 05:53:59
CE directory inode: 549494
open_tasks.db inode: 567204
open_tasks.db-wal inode: 567205
open_tasks.db-shm inode: 567234
```

At that checkpoint, the safety procedure prohibited
`:app:connectedDebugAndroidTest` on the protected emulator because AGP
uninstalls `app.opentasks`; connected suites used a sole disposable emulator
started read-only with no snapshot load/save.

The Task 1.4 plan correction for `core/crypto/build.gradle.kts` was applied:
its instrumentation suite has the Android test runner and AndroidX
core/JUnit/runner/rules dependencies it requires.

At that historical pause audit, `adb devices -l` reported no attached device.
The named replacement snapshot and the untouched pre-replacement recovery
clone were both still present. A later Stage 1 runtime session observed font
scale 2.0, but snapshot saving was disabled. The Stage 2 exit audit has now
proved that the named snapshot's saved baseline remains 1.0.

## Stage 1 authenticated object foundation checkpoint

Stage 1's original source foundation completed at `377c5c3`; final review added
the implementation correction `21c33bc`. Its reviewed commit chain is:

- Task 1: `29bd550` — `docs: reset programme to local data authority`;
  correction `4c97929` — `docs: make stage 1 task 2 next`.
- Task 2: `9822e03` — `test: remember Insights composition state`.
- Task 3: `6449940` — `feat: type cloud frame identity failures`; correction
  `a4fffed` — `fix: classify cloud frame length overflow`.
- Task 4: `e9388fb` — `feat: expose associated-data AEAD boundary`.
- Task 5: `53d63fb` — `feat: add authenticated cloud object codec`.
- Task 6: `377c5c3` — `test: freeze authenticated cloud object vectors`.
- Final review: `21c33bc` — `fix: clear rejected cloud ciphertext buffers`.

The original Stage 1 exit host gates passed on 28 July 2026:

```bash
./gradlew :core:sync:testDebugUnitTest \
  :core:crypto:testDebugUnitTest \
  :core:data:testDebugUnitTest \
  :feature:more:lintDebug \
  --stacktrace
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
./gradlew :app:assembleRelease --stacktrace
```

The exact focused command exited 0 in 772 ms (154 actionable tasks: 1
executed, 1 from cache and 152 up-to-date). Its forced fresh confirmation with
`--rerun-tasks` exited 0 in 14 seconds with 154/154 tasks executed and 125/125
JVM tests passing: Sync 49, Crypto 22 and Data 54. More lint reported no errors
and no `UnrememberedMutableState` finding; its 11 non-blocking findings are 10
warnings and one hint.

The exact repository debug gate exited 0 in 17 seconds (547 actionable tasks:
62 executed and 485 up-to-date). Its forced fresh confirmation with
`--rerun-tasks` exited 0 in 25 seconds with 547/547 tasks executed and 186/186
JVM tests passing: App 25, Crypto 22, Data 54, Domain 36 and Sync 49. Lint and
`:app:assembleDebug` completed.

The exact release gate exited 0 in 47 seconds (441 actionable tasks: 49
executed, 4 from cache and 388 up-to-date). Its forced fresh confirmation with
`--rerun-tasks` exited 0 in 54 seconds with 441/441 tasks executed, including
`:app:minifyReleaseWithR8`, resource conversion/shrinking and
`:app:assembleRelease`.

The required ADB audit first found one attached `emulator-5554`. Read-only
inspection identified the protected `Pixel_10_Pro_Fold` AVD, API 37 / Android
17, because its process had none of the disposable flags. Its package identity
matched the protected record: UID `10232`, first install
`2026-07-28 05:53:59`, CE inode `549494`, and database/WAL/SHM inodes
`567204`/`567205`/`567234`. No instrumentation ran against it.

The protected instance was stopped and ADB was verified empty. The same AVD
was then started as the sole disposable target with:

```bash
/Users/kk/Library/Android/sdk/emulator/emulator \
  -avd Pixel_10_Pro_Fold \
  -read-only -no-snapshot-save -no-snapshot-load -no-window
```

The process arguments and sole ADB identity were verified before running:

```bash
./gradlew :feature:more:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest \
  --stacktrace
```

The first run accurately exposed inherited AVD test state: the disposable
overlay had global font scale `2.0`. App restoration passed 3/3, while More
passed 21/24 and failed the three display/layout assertions
`expandedFoldableContentUsesTwoColumnsAfterTheNavigationRail`,
`contentWidthAtSevenHundredTwentyDpUsesTwoColumns`, and
`conflictedTimeIsDisclosedExcludedByDefaultAndCanBeIncluded`. The command
exited 1 in 1 minute 17 seconds (322 actionable tasks: 20 executed, 5 from
cache and 297 up-to-date).

Only the disposable overlay was changed to the suite's accepted baseline font
scale `1.0`; its API, opened Fold posture, density and sole-ADB state remained
unchanged. The full exact command then exited 0 in 1 minute 18 seconds (322
actionable tasks: 2 executed and 320 up-to-date): More passed 24/24 (Insights
21 and More/Archive/Bin 3), and App process restoration passed 3/3, for 27/27
device tests. Dedicated 200%-text coverage remained part of the passing
Insights suite.

The disposable emulator was stopped without snapshot save. The protected
snapshot `task13_fixround1_replacement_20260728_055359` was restored with
`-no-snapshot-save`. Its AVD/API, UID, install time, light mode, opened posture,
CE inode and database/WAL/SHM inodes matched the pre-suite audit exactly.
That runtime session reported font scale `2.0`; it was not saved. The Stage 2
exit audit later reloaded the named snapshot and proved its saved font scale is
`1.0`. No protected install, uninstall, data clear or instrumentation occurred
in this historical Stage 1 session; the exact protected workspace identity
remained unchanged.

The independent fixture generator was rerun and
`git diff --exit-code -- core/data/src/test/resources/cloud-format/v1-authenticated`
returned no diff. The 19 authenticated-object tests passed as part of the
fresh Data suite. Fixture SHA-256 digests are:

```text
6a685fe9ca734e102e0f96408a1e531fff79c912d7474098599cfb5044faa24e  attachment-chunk.json
15d967d35a59e0466a53733fc4a0ee21df1e1aad740f028d279e086f21faf042  manifest.json
8ba80ec4c814abcd767e61131ac2bf9ee14d25a3efd103ca18cd6f17ca3d6410  operation-segment.json
3ce3780b33e62c2954ba8e9999346f7c49b4872b1d08db51b246a7f97ce35cb2  snapshot.json
```

The original Stage 1 exit acceptance audit confirmed:

- Room remains the sole live structured-data authority. Stage 1 added only
  the internal `core:data` → `core:crypto` dependency; no provider transport,
  credential, backup scheduler or cloud-to-Room path was added.
- No Room model, repository, outbox or exported-schema path changed from the
  approved Stage 1 plan commit. `VaultDatabase` remains version 5 with the
  existing five schema resources. The protected package/database identity is
  unchanged.
- All eight `CloudHeaderIdentity` fields are encoded as strict
  length-prefixed AEAD associated data. Tests prove valid family, vault,
  object, chunk-index and chunk-count substitutions fail authentication;
  schema, crypto and minimum-reader incompatibilities reject before AEAD.
- Declared frame length and ciphertext checksum reject before AEAD.
  Untrusted frame/authentication failures map to typed categories; exception
  text interpolates only public version, bound, family or fixed region labels,
  never private identifiers, checksums, keys or recovery metadata.
- Caller plaintext remains caller-owned. Associated-data and owned ciphertext
  buffers are cleared in `finally`; decoder scratch, partial ciphertext and
  checksum-rejected full ciphertext are cleared before ownership can transfer.
  Successful plaintext is closeable, defensively copyable or transferred
  exactly once.
- Active contracts distinguish the implemented internal authenticated codec
  in `core:data` from unimplemented provider transport, backup/blob services,
  recovery, scheduling, Android Auto Backup, and product-visible features.
- `android:allowBackup` remains `false`; extraction rules still exclude the
  application root and legacy rules remain unchanged. Android Auto Backup is
  not shipped and remains Stage 2 work.
- The placeholder/logging scan, fixture provider/private-content scan,
  `git diff --check` and working-tree audit were clean.

### Final-review correction wave

The single authorised final-review correction wave started from clean
`main` at `19db11f`. Its implementation correction is:

- `21c33bc` — `fix: clear rejected cloud ciphertext buffers`.

Strict decoder TDD added three controlled-stream regressions before production
changed. The stream retained every ciphertext read target and proved that it
had held ciphertext. This forced-fresh RED command:

```bash
./gradlew :core:sync:testDebugUnitTest \
  --tests '*CloudObjectFormatTest.decodeClearsSourceRetainedCiphertextScratch*' \
  --stacktrace --rerun-tasks
```

failed all 3/3 new tests at the expected post-failure zeroisation assertion:
checksum mismatch remained `CHECKSUM_MISMATCH`, truncation remained
`TRUNCATED`, and stream failure rethrew the exact injected `IOException`.
After the minimal ownership correction, the unchanged command passed 3/3 with
25/25 Gradle tasks executed. The complete forced-fresh Sync suite then passed
52/52 tests.

Four direct `VaultCrypto` default-record tests use a capturing delegate that
retains the derived associated-data reference. Encrypt and decrypt defaults
both clear that reference after successful return and exact delegate failure,
while caller-owned plaintext and ciphertext remain unchanged. Their focused
forced-fresh command passed 4/4 tests. No production crypto API or internal
visibility changed.

The decoder now:

- clears its bounded scratch array in `finally` on success and every failure;
- clears partially filled owned ciphertext when truncation or a stream
  exception prevents a complete read;
- retains local ownership of complete ciphertext through checksum validation;
  and
- transfers the complete buffer to `CloudObjectFrame` only after checksum
  success, clearing it on every pre-transfer failure.

Typed failures, checksum-before-AEAD ordering, exact framing and all frozen
fixtures remain unchanged. Fresh affected-module verification passed:

```bash
./gradlew :core:sync:testDebugUnitTest \
  :core:crypto:testDebugUnitTest \
  :core:data:testDebugUnitTest \
  --stacktrace --rerun-tasks
```

The command exited 0 with 67/67 Gradle tasks executed and 132/132 JVM tests
passing: Sync 52, Crypto 26 and Data 54.

Deterministic authenticated vectors retained their four recorded SHA-256
digests. The generator produced no diff, and the forced-fresh authenticated
codec/golden command passed all 19 tests with 57/57 tasks executed. A first
attempt to start that Gradle command was denied by the workspace sandbox before
Gradle startup because it could not open the existing wrapper-cache lock; the
unchanged command was rerun with cache-lock access and passed.

The complete requested host gates then passed:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug \
  --stacktrace --rerun-tasks
./gradlew :app:assembleRelease --stacktrace --rerun-tasks
```

The debug gate exited 0 in 37 seconds with 547/547 tasks executed and 193/193
JVM tests passing: App 25, Crypto 26, Data 54, Domain 36 and Sync 52. Lint and
`:app:assembleDebug` completed. The separate release gate exited 0 in 39
seconds with 441/441 tasks executed, including R8, resource shrinking,
packaging and `:app:assembleRelease`.

Final-review contract reconciliation records the internal authenticated codec
as implemented in `core:data`, composed from `core:sync` framing/identity and
`core:crypto` generic AEAD. It does not claim provider transport, separate
backup/blob services, recovery, scheduling, Android Auto Backup, or
product-visible backup/attachment flows. `AttachmentBlobStore` is the active
blob contract name. At that Stage 1 checkpoint Android backup remained
disabled and supplementary future Stage 2 work.

The source/terminology scans found no placeholder, logging, provider/private
fixture, stale codec-status/ownership or obsolete blob-store contract name.
Negative mentions of superseded Drive-primary, multi-device and `keepOffline`
concepts remain only where the approved design explicitly rejects them. There
is no change to Room, schemas, repositories, provider code, manifests,
extraction or backup rules. No ADB, connected test, install, uninstall, data
clear or other device command ran in this correction wave; the protected state
was untouched. The ignored SDD progress ledger was not edited.

That correction wave observed 200% global text in an unsaved protected runtime
session. The Stage 2 exit audit proves the named snapshot itself retains its
1.0 saved baseline. Any future device suite must still verify its disposable
overlay before running; the product's dedicated 200%-text acceptance remains
covered.

At this Stage 1 checkpoint, the next recommended action was to design and plan
Stage 2. That design and its detailed implementation plan have since received
written approval. The approved Stage 2 plan was subsequently executed and is
now complete.

## Previous P1/P2 pause closure verification

After adding the explicit pause and resume instructions, the exact paused code
state passed the repository gate on 27 July 2026:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
```

Gradle reported `BUILD SUCCESSFUL`: all debug unit tests passed, Android lint
completed and the debug APK assembled. This pause closure changed Markdown
only; the immediately preceding P2 verification record remains the device and
release evidence for the unchanged application code. No commit, push, branch
change, app uninstall or emulator-data wipe was performed.

## Train 0 baseline manifest

This is the audited inventory of the completed P1/P2 work that Train 0
committed as the verified baseline. The inventory was captured with
`git status --short` and `git ls-files --others --exclude-standard` before this
section was added. Every captured path is accounted for below; the groups
describe the completed slice(s) they support, not a new runtime API. The
working tree was clean at that baseline checkpoint. It should also be clean at
each reviewed Train 1 task boundary; investigate and preserve unexpected user
changes.

| Group | Captured paths |
|---|---|
| P1/P2 product contracts and UK-English documentation | `DESIGN.md`; `PRODUCT.md`; `README.md`; `docs/architecture.md`; `docs/threat-model.md` |
| P1/P2 application shell, reminders, restoration and UK-English resources | `app/src/main/AndroidManifest.xml`; `app/src/main/kotlin/app/opentasks/MainActivity.kt`; `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`; `app/src/main/kotlin/app/opentasks/OpenTasksApplication.kt`; `app/src/main/kotlin/app/opentasks/QuickAddSheet.kt`; `app/src/main/kotlin/app/opentasks/SearchSurface.kt`; `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`; `app/src/main/res/values/strings.xml`; `app/src/androidTest/kotlin/app/opentasks/ProcessRestorationInstrumentedTest.kt`; `app/src/main/kotlin/app/opentasks/reminders/ReminderSystem.kt`; `app/src/main/res/drawable/ic_notification.xml`; `app/src/main/res/xml/locales_config.xml`; `app/src/test/kotlin/app/opentasks/WorkspaceSelectionStateTest.kt`; `app/src/test/kotlin/app/opentasks/reminders/ReminderSystemTest.kt` |
| P1/P2 data, migrations, templates and time-entry verification | `core/data/build.gradle.kts`; `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`; `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`; `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`; `core/data/src/main/kotlin/app/opentasks/core/data/db/Entities.kt`; `core/data/src/main/kotlin/app/opentasks/core/data/db/EntityMappers.kt`; `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`; `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`; `core/data/src/test/kotlin/app/opentasks/core/data/db/EntityMappersTest.kt`; `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/3.json`; `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/4.json`; `core/data/schemas/app.opentasks.core.data.db.VaultDatabase/5.json`; `core/data/src/main/kotlin/app/opentasks/core/data/TemplatePayloadCodec.kt`; `core/data/src/test/kotlin/app/opentasks/core/data/TemplatePayloadCodecTest.kt` |
| P1/P2 domain and shared presentation rules | `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/Components.kt`; `core/domain/src/main/kotlin/app/opentasks/core/domain/RecurringTaskPlanner.kt`; `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`; `core/domain/src/main/kotlin/app/opentasks/core/domain/WorkspaceRules.kt`; `core/domain/src/test/kotlin/app/opentasks/core/domain/RecurringTaskPlannerTest.kt`; `core/domain/src/test/kotlin/app/opentasks/core/domain/WorkspaceRulesTest.kt`; `core/domain/src/main/kotlin/app/opentasks/core/domain/ProjectTemplatePlanner.kt`; `core/domain/src/test/kotlin/app/opentasks/core/domain/ProjectTemplatePlannerTest.kt`; `core/model/src/main/kotlin/app/opentasks/core/model/Fixtures.kt`; `core/model/src/main/kotlin/app/opentasks/core/model/Identifiers.kt`; `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt`; `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt` |
| P1/P2 feature surfaces and device coverage | `feature/home/src/main/kotlin/app/opentasks/feature/home/HomeScreen.kt`; `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/TrashScreenInstrumentedTest.kt`; `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`; `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/ProjectWorkbenchInstrumentedTest.kt`; `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`; `feature/schedule/build.gradle.kts`; `feature/schedule/src/main/kotlin/app/opentasks/feature/schedule/ScheduleScreen.kt`; `feature/schedule/src/androidTest/kotlin/app/opentasks/feature/schedule/ScheduleScreenInstrumentedTest.kt`; `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TaskEditorInstrumentedTest.kt`; `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt` |
| Existing P1/P2 handoff | `HANDOFF.md` (its pre-existing P1/P2 content plus this Train 0 audit record) |

The tracked/untracked audit contained 42 modified paths and 14 untracked
paths. `git diff --check` exited 0 with no output. The credential-pattern scan
reported two benign text matches: `.gitignore` excludes
`client_secret*.json`, and the Train 0 plan documents the scan pattern itself.
Neither is a credential, private key or OAuth client secret. All unrelated
user files remain unstaged; this checkpoint stages only `HANDOFF.md`.

Room schema evidence: exported schemas `1.json`, `2.json`, `3.json`,
`4.json` and `5.json` exist under
`core/data/schemas/app.opentasks.core.data.db.VaultDatabase/`.
`VaultDatabase.version` is 5, and `5.json` declares database version 5
(identity hash `9678c8424993d9f4d0694e59aa6912fa).

Train 0 gate results on 27 July 2026:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
./gradlew :app:assembleRelease --stacktrace
```

Both commands exited 0. The debug gate reported `BUILD SUCCESSFUL` in 9s
(547 actionable tasks: 13 executed, 534 up-to-date); debug unit tests, lint
and debug assembly completed. The separately run release gate reported
`BUILD SUCCESSFUL` in 1s (441 actionable tasks: 5 executed, 436 up-to-date),
including `:app:minifyReleaseWithR8`,
`:app:convertShrunkResourcesToBinaryRelease`,
`:app:optimizeReleaseResources` and `:app:assembleRelease`. The release
configuration retains `isMinifyEnabled = true` and `isShrinkResources = true`.

## Historical programme boundary (superseded)

This section records the pre-Stage-6 boundary only. It is not a live resume
point; the Stage 6 paused checkpoint at the top of this file is authoritative.

Stage 2 and Stage 3 are implemented and verified through their task
boundaries. Stage 3 create-only Tasks 1–14 close with the Task 14 qualification
change, and the approved Galaxy Fold 8 trifold-ready adaptive slice closes with
the Task 5 qualification change containing this handoff. Stage 4 is complete
and qualified: notes, activity, search, attachment transport, and attachment
product flows are implemented. Remote merge remains absent by design.

The approved Stage 3, adaptive-slice, Stage 4 and Stage 5 execution
authorities are closed. Stage 5 is complete and qualified. The active work is
paused after the controller-approved, uncommitted API 36 headless-focus
exception described above: the remote branch is at `34f6de0`, local gates
passed, and new authority is required before publication or bench 2.
Samsung RTL is closed by user ruling; native fold continuity and broader
two-installation live recovery evidence remain external future work and are
not implied by Stage 4 or Stage 5 qualification.

## Historical resume instructions (do not use)

1. Read the controller-authorized Ctrl+K follow-up above, then read
   `.superpowers/sdd/2026-08-03-stage-5-platform-features-plan/test-fix-brief.md`
   and its adjacent report. Historical Stage 5 resume instructions are
   superseded.
2. Confirm branch `test-fix` is at `34f6de0`, synchronized with
   `origin/test-fix`, and preserve the local uncommitted exception plus all
   unrelated working-tree changes.
3. Pause. Do not commit, push, rerun, or merge without new controller
   authority. If authorisation resumes the work, review the local exception
   before deciding whether to spend bench 2.

## Historical backlog: six dependency-ordered stages

This table is retained as historical evidence, not as the active backlog. The
27 July Train 1–6 documents are historical evidence or replanning inputs and
must not be executed. Completed Train 0,
local-workspace, Insights, key-separation, and bounded-frame evidence remains
recorded above.

| Order | Stage | Status | Exit decision |
|---:|---|---|---|
| 1 | Direction reset and authenticated object foundation | Done | Active contracts match local authority; the authenticated provider-independent object codec is frozen |
| 2 | Local backup and Android Auto Backup | Done | Local generations produce verified primary snapshots and one strictly whitelisted portable package |
| 3 | App-managed backup and recovery takeover | Done | Drive backup, retention, recovery, writer epochs, stale-writer rejection, credential rotation, remote lifecycle, and approved product surfaces are proven |
| 4 | Notes, activity, cloud attachments, and search | Done (qualified) | Notes/activity/search and attachment lifecycle/product flows are implemented; native fold and broader two-installation evidence remain post-Stage-4 |
| 5 | Remaining platform features | Done (qualified) | Import/export, widget, app lock, input, and calendar features use the final local schema |
| 6 | Production qualification and rollout | Paused pending CI authorization and external owner gates | Backup, attachment, takeover, recovery, accessibility, performance, privacy, and release gates pass |

The dependency chain is strict:

```text
Stage 1 → Stage 2 → Stage 3 → Stage 4 → Stage 5 → Stage 6
```

### Historical execution order

1. Remain paused at the controller-approved local exception. Any future
   publication or use of bench 2 needs fresh controller authority.

GitHub dependency-PR checks and resolution remain paused. Android Auto Backup
and device transfer retain the verified exact-file allow-list. Existing Room,
legacy outbox, local package, and workspace data remain protected by their
explicitly planned, verified boundaries.

## Architecture and security rules for the next agent

- Read [docs/architecture.md](docs/architecture.md),
  [docs/threat-model.md](docs/threat-model.md), [DESIGN.md](DESIGN.md) and
  [PRODUCT.md](PRODUCT.md) before changing the corresponding contract.
- Every write must remain a `DomainCommand` through `VaultRepository`.
- Mutations and their ordered backup-journal entries must remain one
  transaction. The legacy outbox is read-only.
- Undo is repository-produced; never reconstruct it in UI code.
- Keep `InMemoryVaultRepository` behaviour aligned with
  `RoomVaultRepository`.
- Close `RoomVaultRepository` before closing its `VaultDatabase`; repository
  close joins the observation job.
- A Room version bump requires an exported schema and non-destructive
  migration fixture.
- Template capture includes only an active project's active workflow, open
  milestones and open/non-Bin/non-complete tasks. Preserve the 100-template,
  500-task, 100-year and 2 MiB limits; validate bounded self-contained payloads
  and acyclic parent/dependency graphs before use. Instantiation must retain
  wall-clock zone intent, derive relation IDs from the new project ID and
  commit every new record/journal entry atomically.
- Workflow status writes stay project/Inbox scoped, preserve immutable
  semantic categories and retain at least one active status per category.
  Moving a task between projects maps by semantic category; Undo restores the
  exact prior status ID.
- Milestones stay project-scoped, retain revision metadata and enforce 120
  character names, case-insensitive project uniqueness and a 100-row project
  cap. Deletion and membership restoration must remain atomic with every
  affected task journal entry.
- Dependency links stay distinct from derived blocking state: `dependencyIds`
  is the durable relation set and `blockedBy` contains only unfinished linked
  tasks. All writes use `SetTaskDependency`, enforce the 100-link cap and
  reject self/transitive cycles before the task, relation and backup-journal
  state are committed atomically.
- Every completion entry point must route through the same repository gate.
  UI confirmation is an acknowledgement, not an authority bypass, and
  notifications must continue to omit Complete for blocked tasks.
- Schedule remains a read-only snapshot projection. Group by the `start`
  moment's local date, falling back to `due`, preserve each moment's stored
  zone and open the canonical task editor for mutation.
- Process restoration stays layered: serializable Navigation 3 state for the
  destination, `SavedStateHandle` for selected IDs, bounded Compose saveable
  state for filters/scroll/drafts and Room for active timers. Never place
  passphrases, keys, attachments or vault payloads in saved-instance state, and
  never let the initial repository emission overwrite a restored draft.
- Time entries are first-class records. Keep add/update/delete/restore as
  repository commands with exact Undo and atomic journal writes; enforce a
  positive interval, 500-character notes and 10,000 entries per task. Running
  entries are timer-owned and cannot be edited or deleted. Preserve overlapping
  records, reconcile them with the deterministic linear sweep and keep the
  warning visible until the user corrects the source intervals. Do not turn
  reconciliation into silent trimming, merging or deletion.
- A crypto-format bump requires old-format fixtures and golden-vector review.
- Never replace a missing Keystore key for an existing local envelope.
- Keep SQLCipher database keys and Tink vault-content keys independently
  generated. Recovery-passphrase changes and local Android Keystore wrapping
  must re-wrap the same content key rather than re-encrypt content or reuse the
  database key.
- Cloud v1 headers remain strict canonical UTF-8 with fixed key ordering,
  explicit version fields, exact vault/object identity and optional attachment
  chunk identity. Validate the 16 KiB header and family-specific length/count
  bounds before allocating or reading ciphertext.
- A checksum detects corruption but is not authentication. The implemented
  `core:data` codec binds the full `CloudHeaderIdentity` as AEAD associated
  data, checks length and checksum before AEAD, and exposes no plaintext until
  authentication succeeds.
- Preserve `CloudObjectFrame`'s one-shot ciphertext ownership. Ciphertext reads
  from caller-controlled streams may use only bounded scratch storage, never
  the retained verified ciphertext array. Clear scratch in `finally` and clear
  partial or checksum-rejected ciphertext before ownership transfer.
- Keep passphrases as `CharArray` and zero temporary key arrays.
- Keep one process-scoped local backup coordinator. It may checkpoint only
  after authenticated strict readback and source comparison; failure never
  blocks local editing or advances the verified checkpoint.
- Keep the recovery envelope bound to the existing content key and the
  portable package capped at 24 MiB. Package readiness reports local
  generation, bytes, and production time only.
- Android backup and device transfer may include only the `file`-domain
  application-relative path
  `android_backup/open_tasks_portable_v1.otb`. Restored or unknown input moves
  to the no-backup inbox and remains inert until the explicit recovery path
  verifies and activates it.
- Never log private content, account data, Drive IDs, attachment names or
  encryption metadata.
- Future Drive code receives encrypted objects only and requests only
  `drive.appdata`.
- Alarm and pending-intent payloads contain record IDs only; private
  notification content must retain a generic lock-screen public version.
- Layout decisions use `WorkspaceLayoutPolicy`, never a device model.
- Feature composables stay stateless and free of Hilt.
- User-facing language is UK English: use UK spelling, Bin terminology,
  day–month dates and the 24-hour clock. Stable internal identifiers may retain
  historic names, but must never leak them into UI copy.
- Update architecture, design, threat-model and handoff documents in the same
  change whenever their contracts are affected.

## Historical recommendation (superseded)

Do not use the old local-exception recommendation below as a resume point.
Follow the Stage 6 paused checkpoint and live backlog at the top of this file.

Keep the protected workspace safe and run future device suites only on a sole
audited disposable emulator unless a new in-place procedure is explicitly
approved. Preserve existing outbox data and the exact Android package
allow-list. Continue normal dependency and pull-request maintenance against the
private GitHub repository; reauthenticate the local `gh` CLI before any future
CLI-only GitHub operation because its current token lacks valid private-repo
access.
