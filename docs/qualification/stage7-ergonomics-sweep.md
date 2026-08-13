# Stage 7 ergonomics-sweep qualification

## Status and source identity

**Candidate only — not yet qualified or released.** The exact-head connected
gate is red. Its fix/rerun, signed-smoke, disposal, remote-CI, and tag gates
marked TODO below are release blockers.

- Date: 13 August 2026
- Implementation base:
  `7026596d33c6430d4a05919fd1e05f174a6cefd7`
- `implementationHeadSha` and product source commit:
  `ec09e1b1e48653e61e4ecc833c54b5962b0fef92`
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
zero-Important, zero-Minor verdict. There are zero open Critical or Important
review findings.

## Completed evidence that remains valid

### Saved-view compatibility and privacy

The unit and connected contracts prove strict v1 decode with v2 defaults,
canonical deterministic v2 encode/decode, and fail-closed unknown versions or
enum values. Recovery imports v1 and v2 as visible views while retaining a v3
row invisibly. The Room test proves a v2 query survives update, Undo, and an
encrypted restart. The 20-view, 64-character name, 500-character query, and
2 MiB payload bounds remain unchanged.

Static comparison of the implementation base to `ec09e1b` finds all checked-in
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
or errors. Because `ec09e1b` later changed production widget code, those counts
are retained only as prerequisite evidence, not as the final exact-head gate.

The widget change followed a focused RED/GREEN cycle. On the actual launcher,
the current implementation displayed complete normal-text counts with a fully
visible 48 dp Quick Add affordance; tapping it opened a sheet whose title was
exactly empty. After a forced recompose at 200% text, compact numeric counts and
the same action remained visible. The setting was restored after the check.

## Exact-head automated qualification

| Gate | Result |
|---|---|
| Six Android-test source compiles | PASS — the 219-action graph completed; 2 actions executed and 217 were up-to-date after the forced local gate. |
| Forced JVM/lint/debug build | PASS — 553/553 actions executed in 5m08s; XML totals are 1,235 tests, 0 failures, 0 errors, and 0 skips. |
| Six-module connected run | **FAIL — release blocker.** The complete restart ran 455 actions in 1h19m57s; 391 tests produced 388 passes, 2 established skips, and 1 More failure. |
| Room/fixtures/workflow | PASS — schema drift executed 33/33 actions; all five fixture families were byte-identical; workflow pinning and whitespace passed. |
| Privacy/release scope | PASS — the exact base-to-head audit found no added logging/endpoints or schema/backup drift; 12 manifests were byte-identical and the app manifest contained only the reviewed `singleTop` semantic delta. |
| Forced signed release | TODO — blocked until the complete connected gate is green; record the forced action count, duration, verifier result, APK byte count, and SHA-256. |

### Connected-gate blocker

The first exact-head attempt passed Projects 23/23, Schedule 2/2, Tasks 43/43,
Data 179/179, and More 64/64. App never started because UTP rejected the debug
APK with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. UTP cleanup left both app
packages absent, and a bounded debug install/uninstall proof passed before the
entire matrix restarted.

The complete restart produced these XML totals:

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
the scroll viewport. Work paused before modification. Resume with the bounded
test correction—scroll each guidance node into view before retaining its
display assertion—then run one focused RED/GREEN proof, the full More suite,
scoped review, the Step 9 local/compile checks, and a complete six-module rerun
from zero. Do not weaken the assertion to existence or waive the failure.

The eventual green connected result must contain exactly the two established
skips: the preserved one-shot credentialed Drive qualification row and the
cross-display fold-continuity harness exception. Any additional skip, failure,
or error blocks qualification.

## Final signed acceptance and closure

- **TODO (release blocker):** run and record all seven literal signed-sideload
  rows from `RELEASING.md` on the exact-head APK.
- **TODO (release blocker):** record the extra saved-filter, grammar-capture,
  duplicate/Undo, and Insights chart/table checks.
- **TODO (release blocker):** remove temporary credential material, disable the
  disposable screen credential, kill only the audited disposable overlay, and
  confirm no ADB target or disposable process remains.
- **TODO (release blocker):** qualify the exact GitHub candidate push under the
  Free-only ruling, create annotated tag `v1.1.0`, then record the HANDOFF-only
  closure commit.

Until all four items are complete, Stage 7 remains a local candidate and this
document must not say qualified or released.
