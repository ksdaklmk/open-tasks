# Release 1.2.0 sideload qualification

## Status and candidate identity

**Pre-tag candidate.** The signed artefact is built and verified at the final
reviewed Stage 8 head, and its smoke run is executed and recorded below. The
remote GitHub Free run and the `v1.2.0` tag are recorded as pending and must be
filled in from observed results before the tag is created. No Stage 7 waiver is
inherited by this candidate.

- Date: 16 August 2026
- Product source commit:
  `b6c438f312b40f228e1d19365062debe479054e7` (`b6c438f`, the final reviewed
  Stage 8 head; the APK was built from this tree). The version bump alone is
  `79afd979a494b3fd903acad8616af99df4123400` (`79afd97`); Stage 8 began at
  `8047f136541d22b15ca20db8971ea67685e250b5`.
- Version: versionName 1.2.0, versionCode 3
- Distribution: signed sideload only; no AAB, no Play Console, no CI signing.
  CI continues to build the release unsigned.
- Signing: the established external user-held release identity, unchanged since
  1.0.0, so updates install over the top with data preserved. No signing
  material, file location, alias, or certificate identifier is recorded here.
- Artefact: `app-release.apk`, 16,372,751 bytes, SHA-256
  `c81fa17da3c940b4719523be53df4b021d56b81e3c60e31e6e22f357af3c3f17`

The `v1.2.0` tag will point at the qualification commit that carries this
record, not at the product source commit above; the product identity is never a
self-reference to a future commit. No device identifier, process id, credential,
private task text, vault id, or Drive id belongs in this record.

## Automated verification

`bash scripts/verify-release-apk.sh` against the signed APK built from
`b6c438f`, re-run immediately before installation:

    verify-release-apk: all checks passed

(exit 0; checks: signature verifies, versionName/versionCode match the build
file at 1.2.0 (3), the debug qualification activity is absent from the
manifest, `auth/drive.appdata` is present and the sole Drive scope in the dex,
and the package is not debuggable.) On device, `dumpsys package app.opentasks`
independently reported `versionName=1.2.0`, `versionCode=3`, and no
`DEBUGGABLE` flag.

Supporting non-device gates at the same head, each forced fresh:

- `./gradlew :app:assembleRelease --rerun-tasks` — BUILD SUCCESSFUL in 4m28s,
  442/442 actions, minified and shrunk release configuration.
- `./gradlew testDebugUnitTest lintDebug :app:assembleDebug --rerun-tasks` —
  BUILD SUCCESSFUL in 5m03s, 553/553 actions, 1,304 JVM tests across 127
  suites, run as a separate invocation from the release build.
- All six connected-test source sets compile.
- Schema-drift, deterministic fixture regeneration, workflow pinning, and
  whitespace checks green; the Stage 8 scope audit (Room v9, backup format v1,
  no new permission, dependency, route, or Drive scope, and a single
  non-exported `DailyDigestReceiver` as the only manifest delta) is green.

Six-module connected gate totals for this candidate: **453 tests, 0 failures,
0 errors, 2 established skips** in 10m25s — `:app` 92 (2 skips), `:core:data`
192, `:feature:tasks` 48, `:feature:projects` 30, `:feature:schedule` 23,
`:feature:more` 68. Full stage evidence, review dispositions, and the manual
matrix live in `docs/qualification/stage8-planning-surfaces.md`; this record
covers the artefact and the signed smoke run.

## Signed smoke checklist

Executed against this exact signed APK on the sole audited disposable ADB
target, the `Fold8_Acceptance` AVD booted with `-read-only -no-snapshot-load
-no-snapshot-save`. The inherited debug build was uninstalled inside the
overlay session only; the release APK was then installed. Every `PASS` below
comes from an observed result, never inferred from a unit or connected test. No
row is waived for this candidate.

| # | Required step | Result |
|---|---|---|
| 1 | Fresh launch; choose `Start without restoring` to create the local workspace | PASS |
| 2 | Add a project, a task with one checklist item, and a tag | PASS — the project was created, Quick Add confirmed both the project and the tag as separate suggestions, and the task reached `0/1 complete` on its checklist |
| 3 | Force-stop and relaunch; everything from row 2 persists | PASS |
| 4 | Export `.otvault`, then import it back; counts match | PASS — the export wrote 42,344 bytes and reported `Vault exported with 0 attachments.`; the import preview read `This archive holds 57 records and 0 attachments.`; after `Replace vault` the open-task, blocked, project, and at-risk counts were identical and the row-2 project was present |
| 5 | Enable immediate app lock, background past the delay, and unlock | PASS — the unlock overlay appears on return and the credential restores access |
| 6 | Place the Today widget on the launcher; counts render | **NOT EXECUTED** — see the note below |
| 7 | Open the app via the Quick Add launcher shortcut | PASS — the launcher shortcut launches the app, app lock gates it, and the Quick Add sheet opens after unlocking |

Summary: **6 of 7 rows PASS; row 6 was not executed.**

**Row 6 note.** The Today 2×1 widget is present and correctly advertised in the
launcher's widget catalogue, described as "Today's open and overdue task
counts, with quick add." Placing it requires a drag-and-drop out of the widget
picker, and the launcher does not begin that drag from the synthetic pointer
events available to a headless overlay; six attempts with varying dwell and
interpolation either left the picker open or dropped unrelated widgets. This is
an automation limitation of the harness, not an observed product failure. It is
recorded as un-executed rather than waived, inferred, or marked pass. A future
qualification should place this widget by hand, or on a target where the
launcher accepts synthetic drag, before claiming a complete 7-of-7 smoke.

## Stage 8 release extras

Three representative Stage 8 behaviours were repeated against the same signed
build, matching the debug manual matrix already executed for the stage.

| Required extra | Result |
|---|---|
| One representative Month move and Undo | PASS — a dated task moved from 20 August to 21 August through its Reschedule action, and the snackbar Undo restored it to 20 August with both day cells reporting the corrected counts |
| One Timeline chain and Open | PASS — selecting a blocked task showed `Dependency chain: 0 tasks outside this project` and labelled the other row `Prerequisite of the selected task`; the row's separate Open action opened that task's detail, shown as Blocked |
| One near-future daily digest | PASS — exactly one private counts-only shade entry titled `Today` on the dedicated `daily_digest` channel at private visibility, a generic public version titled `Daily digest` reading `Open Open Tasks to view it` with no counts and no content intent, no task title in either version, and a tap that reached Home |

Summary: **3 of 3 extras PASS.**

The digest extra was deliberately executed as the exact scenario that blocked
the previous candidate: Quick Add was opened and cancelled, the app was
backgrounded behind immediate app lock, and the digest was then tapped. The
app-lock overlay appeared first and, after authentication, the app landed on
**Home** — the already-consumed Quick Add sheet did not reopen. That is the
observed device confirmation of the `c495dd2` repair.

One environment note: because the signed build is a fresh install, its runtime
notification permission had to be granted before delivery. The digest
occurrence that fired before the grant correctly marked the day handled and
re-armed the next alarm without posting, matching the documented
mark → re-arm → vault → post ordering.

## Disposable cleanup

Complete. The temporary exported `.otvault` was deleted through its owning
provider and the Downloads directory verified empty; the temporary device
screen credential was removed and re-read as `CredentialType: NONE` without
being recorded here; the application package was uninstalled, leaving zero
digest alarms and zero notification records; no controller-created credential
helper file was ever written. Only the audited disposable overlay was killed,
reporting `OVERLAY-STOPPED-CLEAN`, and the final host audit shows no ADB
target, no disposable emulator process, and no temporary directory. The
protected persistent workspace was never touched.

## Remote qualification and tag

- Candidate GitHub Free run: **pending.** No candidate run has been dispatched
  or observed. GitHub Actions is currently unable to start jobs for this
  account: the most recent push run reported every job failing in two to four
  seconds with zero executed steps and the annotation "The job was not started
  because recent account payments have failed or your spending limit needs to be
  increased." This record claims no CI evidence.
- Tag `v1.2.0`: **pending.** The annotated tag is created only after every
  smoke row and extra is satisfied, cleanup is confirmed, and the candidate's
  own CI run is green; it will then point at the qualification commit
  containing this record.
- Installation on the owner's physical device is owner-controlled and outside
  this qualification.

Until the pending lines above carry observed results, release 1.2.0 is a
locally verified candidate and does not exist as a released version.
