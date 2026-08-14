# Stage 7 ergonomics-sweep qualification

## Status and source identity

**Waiver-accepted candidate — pre-tag, not released.** The exact-head local
connected gate and signed artifact verification are green. The user explicitly
waived app-lock testing, secure cleanup, and GitHub CI qualification and
authorized tagging with those gaps. This is not fully qualified under the
original Task 18 plan.

- Date: 14 August 2026
- Implementation base:
  `7026596d33c6430d4a05919fd1e05f174a6cefd7`
- `implementationHeadSha` and product source commit:
  `19aecf4bf7ac7322e9ecdf51d0c09412e2c73b84`
- Candidate version: versionName 1.1.0, versionCode 2

The implementation head is deliberately the last product/test commit, not the
future qualification-document commit. This record contains no account or
device identifier, process id, credential, private task text, Drive id, or
other secret.

## Delivered boundary

Stage 7 delivers the shared half-open, zone-aware due buckets; one arrangement
and search authority across both repository engines; durable Tasks and
per-project workbench/board arrangement choices; strict saved-view payload v2;
confirm-only Quick Add grammar; a light-only Ember theme; atomic task creation
and duplication; and dot-matrix Insights with a completion trend chart/table.
The final widget correction uses native Glance responsive sizes so a compact
2-by-1 host keeps both counts and the canonical 48 dp Quick Add action visible.

There is no Room migration, backup-family change, new network path, or new
permission. Arrangement preferences contain only enum names and project ids.
Quick Add draft state remains transient, and saved-view payload bytes remain in
the existing encrypted payload column.

## Toolchain and platform

| Component | Version |
|---|---|
| Gradle | 9.6.1 |
| Android Gradle Plugin | 9.3.1 |
| Kotlin | 2.3.21 |
| Gradle daemon JDK | 21 |
| Java source/target | 17 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 36 |
| Room | 2.8.4; schema remains v9 |
| Glance AppWidget | 1.1.1 |

## Review disposition

The whole-stage review at version commit `8cecbcc` found 0 Critical, 5
Important, and 3 Minor issues. Commit `e33bd09` resolved all five Important
findings: recurrence disclosure, the 51st-tag boundary, lifecycle-aware
relative-date refresh, light system-bar appearance, and the 48 dp Quick Add
chip-clear target. Its scoped re-review found every finding addressed and no
new Critical or Important issue.

The three Minor dispositions are unchanged:

- Unicode tag identity needs one repository-wide normalization policy across
  every caller; a partial Quick Add-only change is intentionally deferred.
- The unrelated Pinfo document is user-owned dirty state and is excluded from
  Stage 7 scope.
- The archived-project parser assertion remains a non-blocking test-hardening
  item.

Subsequent connected-boundary fixes were independently re-reviewed with zero
Critical, Important, or Minor issues. The canonical widget-action fix's three
test-evidence findings were addressed by `1b5f3a3`; its re-review was clean.
The final responsive widget patch at `ec09e1b` also received a zero-Critical,
zero-Important, zero-Minor verdict. The passphrase viewport correction is
test-only: two `performScrollTo()` calls immediately precede the existing
guidance-display assertions. Its scoped re-review passed with zero findings.
There are zero open Critical or Important review findings.

## Completed evidence that remains valid

### Saved-view compatibility and privacy

The unit and connected contracts prove strict v1 decode with v2 defaults,
canonical deterministic v2 encode/decode, and fail-closed unknown versions or
enum values. Recovery imports v1 and v2 as visible views while retaining a v3
row invisibly. The Room test proves a v2 query survives update, Undo, and an
encrypted restart. The 20-view, 64-character name, 500-character query, and
2 MiB payload bounds remain unchanged.

Static comparison of the implementation base to `19aecf4` finds all checked-in
Room schemas and `BackupRecordFamily` unchanged. Twelve manifests are
byte-identical; the app manifest's sole semantic delta is native `singleTop`
on the already-exported `MainActivity`. Its exported value, intent filters,
permissions, providers, services, and receivers are unchanged. The responsive
widget provider XML is not an Android manifest and adds no permission or
exported component.

### Independent feature acceptance

The earlier debug acceptance on the audited disposable established:

| Surface | Observed result |
|---|---|
| Due buckets and chips | Overdue, Today, This week, Later, and No date membership matched the shared classifier; completed and Bin exclusions held. |
| Arrangement | Tasks and two projects retained their sort/group choices across a cold restart; project choices stayed isolated. |
| Saved filter | A blank-text filtered view stayed active through text refinement, explicit clear, and restart. |
| Quick Add grammar | Project, existing/new tags, numeric priority, recurrence with effective due, estimate, and date remained confirm-only and created one stripped-title task. |
| Light-only theme | Dark device configuration still rendered the light Ember recovery and main surfaces; deterministic tests cover initialising and app-lock surfaces. |
| Duplication and Undo | Board and detail duplication followed the status and exclusion rules; Undo affected only the copy. |
| Insights | 7/30/90-day ranges, horizontal trend movement, one merged summary, and chart/table parity were observed; semantics tests cover once-only labels and silent decorative dots. |

These feature observations are not a substitute for the final exact-head
release checklist.

### Pre-final gate and widget evidence

At committed head `cfd7b52`, the complete six-module run executed 391 tests:
389 passed, exactly two established skips remained, and there were no failures
or errors. Those counts remain valid prerequisite evidence. The final exact
head adds the passphrase test-only correction and retains the same aggregate
green result.

The widget change followed a focused RED/GREEN cycle. On the actual launcher,
the current implementation displayed complete normal-text counts with a fully
visible 48 dp Quick Add affordance; tapping it opened a sheet whose title was
exactly empty. After a forced recompose at 200% text, compact numeric counts and
the same action remained visible. The setting was restored after the check.

## Exact-head automated qualification

| Gate | Result |
|---|---|
| Six Android-test source compiles | PASS — all six Android-test source compiles are green. |
| Forced JVM/lint/debug build | PASS — 553/553 actions executed in 5m08s; XML totals are 1,235 tests, 0 failures, 0 errors, and 0 skips. |
| Six-module connected run | PASS — module totals (tests/failures/errors/skips): App 80/0/0/2, Data 179/0/0/0, Tasks 43/0/0/0, Projects 23/0/0/0, Schedule 2/0/0/0, More 64/0/0/0; 391 tests, 389 passed, 0 failures, 0 errors, 2 established skips. |
| Room/fixtures/workflow | PASS — schema drift executed 33/33 actions; all five fixture families were byte-identical; workflow pinning and whitespace passed. |
| Privacy/release scope | PASS — the exact base-to-head audit found no added logging/endpoints or schema/backup drift; 12 manifests were byte-identical and the app manifest contained only the reviewed `singleTop` semantic delta. |
| Forced signed release | PASS — verifier passed; APK is 16,242,815 bytes with SHA-256 `e5a0d947b890c72cfa692f78e54341a5fe562415a57ee6489f7d6d19d262802c`. |

### Connected-gate history and correction

The first exact-head attempt passed Projects 23/23, Schedule 2/2, Tasks 43/43,
Data 179/179, and More 64/64. App never started because UTP rejected the debug
APK with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. UTP cleanup left both app
packages absent, and a bounded debug install/uninstall proof passed before the
entire matrix restarted.

The historical complete restart produced these XML totals:

| Module | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| App | 80 | 0 | 0 | 2 |
| Data | 179 | 0 | 0 | 0 |
| Tasks | 43 | 0 | 0 | 0 |
| Projects | 23 | 0 | 0 | 0 |
| Schedule | 2 | 0 | 0 | 0 |
| More | 64 | 1 | 0 | 0 |
| Total | 391 | 1 | 0 | 2 |

The failing row is
`BackupRecoveryScreenInstrumentedTest.passphraseValidationUsesExactLengthAndMismatchGuidance`.
After entering a short passphrase and tapping submit, its line 548 assertion
could not see the exact `Use 12–128 characters.` guidance. Read-only triage
found that exact supporting-text node in the merged field semantics, proving
validation, state, and copy worked; the open password IME had moved it outside
the scroll viewport. The test-only correction then scrolled both exact
guidance nodes into view before retaining their display assertions. The
focused test passed 1/1 and the full More suite passed 64/64 afterward.

The final connected result contains exactly the two established skips: the
preserved one-shot credentialed Drive qualification row and the cross-display
fold-continuity harness exception. Any additional skip, failure, or error
blocks qualification.

## Final signed acceptance and closure

- Signed-sideload rows 1, 2, 3, 4, 6, and 7: PASS on the exact-head APK.
- Signed-sideload row 5 (immediate app-lock background/unlock): **SKIPPED by
  explicit user instruction**, not PASS evidence.
- Release extras: PASS — saved-view save/activate/refine/clear/restore with
  force-stop persistence; six-token confirm-only grammar with one atomic Root
  create; duplicate/Undo open count 7→8→7 with no copy; Insights 7/30/90 and
  chart/table parity including project/tag rows.
- Secure cleanup is **SKIPPED by explicit user instruction**. The temporary
  `.otvault` was removed, but credential-file, screen-credential, and
  disposable-overlay cleanup was not performed; residual state remains. No
  credential or device identifier is recorded here.
- GitHub CI qualification is **SKIPPED by explicit user instruction**. The tag
  is authorized without CI evidence; no candidate CI job is claimed to have
  passed. The API 37.0 F6 phrase **credential-encrypted storage unavailable**
  is historical observe-only context, not a check of this candidate.

The user explicitly accepted the original Task 18 gaps and authorized tagging.
Stage 7 remains an explicitly waiver-accepted pre-tag candidate, not a fully
qualified release under the original Task 18 plan; `v1.1.0` does not yet exist.
