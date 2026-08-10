# Stage 6 daily-flow qualification

## Outcome

Stage 6 is qualified as of 10 August 2026. The implementation range starts at
`fc4aad8`; the qualified product source is `c5c5e11`, including the
focus-aware manual-Stop amendment at `8742a84`, with test-only qualification
proof at `6b55f87`. The stage adds faster capture,
interactive glance/focus surfaces, saved searches, atomic bulk actions, weekly
review, Kanban, plaintext Markdown/CSV interop, and the installed adaptive
Ember icon without a Room or backup-format change.

The whole-stage review's six Important findings are resolved. Independent
reviews of the focus-aware Stop amendment and the connected-gate repair both
returned Approved with zero Critical or Important findings. The latter left one
non-blocking Minor: Board drag has a harmless second callback-freshness wrapper
that is redundant with its card-level wrapper.

No account identifier, device serial, record UUID, Drive object ID, or other
private identifier is recorded here. The protected `Pixel_10_Pro_Fold` AVD
was never booted, installed to, or mutated during this qualification.

## Step 1 — six-module connected gate

### First run: failed, 14 failures and 3 skips

The first exact six-module command ran on the sole verified
`Fold8_Acceptance` API 37 / Android 17 disposable:

```bash
./gradlew :app:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest \
  :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest \
  :feature:more:connectedDebugAndroidTest
```

The initial result was 332 tests: 315 passed, 14 failed, and 3 skipped.

| Module | Tests | Failures | Skipped |
|---|---:|---:|---:|
| `:app` | 45 | 3 | 3 |
| `:core:data` | 171 | 1 | 0 |
| `:feature:tasks` | 37 | 2 | 0 |
| `:feature:projects` | 18 | 4 | 0 |
| `:feature:schedule` | 2 | 0 | 0 |
| `:feature:more` | 59 | 4 | 0 |
| **Total** | **332** | **14** | **3** |

The failures reduced to three small product gaps plus qualification-harness
assumptions exposed only by the real connected run:

- Quick Add's nested date-clear target was below 48 dp.
- The template sheet did not scroll at the connected high-scale layout, and
  Board drag needed fresh drop callbacks plus a semantics-order correction.
- Compose frame/scroll settling in saved search, review, project, task, and
  backup/recovery tests was too optimistic.
- The Ctrl+K harness depended on a real headless Dialog receiving window focus,
  creating the third skip instead of proving component root wiring.

Commit `c5c5e11` repaired those roots and kept assertions substantive. Its
independent review approved the complete 12-file patch with no Critical or
Important finding.

### Official rerun: PASS, 6m52s

The exact command then passed. XML-authoritative counts are:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `:app` | 45 | 0 | 0 | 2 |
| `:core:data` | 171 | 0 | 0 | 0 |
| `:feature:tasks` | 37 | 0 | 0 | 0 |
| `:feature:projects` | 18 | 0 | 0 | 0 |
| `:feature:schedule` | 2 | 0 | 0 | 0 |
| `:feature:more` | 59 | 0 | 0 | 0 |
| **Total** | **332** | **0** | **0** | **2** |

That is 330 passes and exactly the existing expected Stage 4 skips:

- the preserved one-shot credentialed Drive qualification row; and
- the exact FoldContinuity cross-display harness exception.

Before each device phase, ADB and emulator-process audits were empty. The AVD
was booted `-read-only -no-snapshot-load -no-snapshot-save -no-window`, was
the sole ADB target, and returned exactly `Fold8_Acceptance` from
`adb emu avd name`. It was killed through `adb emu kill`; the final ADB
target list and emulator-process audit were empty.

### Post-gate accessibility proof: PASS

The manual TalkBack check could observe focus but could not dispatch a custom
gesture through the headless shell. Test-only commit `6b55f87` therefore adds
an exact `BoardViewInstrumentedTest` custom-action dispatch assertion. Its
focused connected run passed 1/1. Re-running the wider module exposed an
existing workflow-editor test leaving the IME over later coordinate clicks;
the same commit closes the IME and scrolls those controls into view. That
focused scenario passed, followed by the complete projects suite at 19/19.

## Step 2 — repository, release, schema, fixture, and hygiene gates

All required non-device gates passed:

- Forced-fresh `./gradlew testDebugUnitTest lintDebug :app:assembleDebug
  --rerun-tasks`: 550/550 tasks, BUILD SUCCESSFUL in 1m23s.
- JVM test XML: `:app` 396, `:core:data` 644, `:core:domain` 58,
  `:core:sync` 52, `:core:crypto` 27, and `:feature:projects` 2 —
  **1,179 tests, zero failures/errors/skips**.
- Forced-fresh `./gradlew :app:assembleRelease --rerun-tasks`: 442/442
  tasks, BUILD SUCCESSFUL in 1m02s. The unsigned release APK is 16,116,455
  bytes.
- `scripts/check-schema-drift.sh`: clean at Room schema v9.
- Stage 2, Stage 3, Stage 4, and Stage 5 fixture generators all regenerated
  byte-identically; the resource diff was empty after every generator.
- `bash scripts/verify-actions-workflow.sh`: passed.
- `git diff --check`: passed.

Release inspection passed:

- `MainActivity` retains the approved launcher and existing QUICK_ADD entry
  points; Stage 6 adds only `text/plain` SEND and PROCESS_TEXT.
- `QuickAddTileService` is exported only with
  `android.permission.BIND_QUICK_SETTINGS_TILE`.
- `FocusAlarmReceiver` is not exported, and no debug qualification activity
  is present in release.
- `drive.appdata` remains the sole Drive authorization scope.
- The normal and round launcher entries resolve to the approved adaptive
  Ember resources, with a distinct monochrome layer.

### Post-push remote CI: blocked before runner

Commit `0d61c53` was pushed to `origin/main` and triggered Android run
[`31344561176`](https://github.com/ksdaklmk/open-tasks/actions/runs/31344561176).
Verify, compact API 36, and expanded API 37.0 each received no runner and
executed zero steps. GitHub annotated all three with the same account
payments/spending-limit error; release was skipped. The run supplies no code or
test result and does not supersede the successful local qualification gates.

## Step 3 — privacy and boundary scans

The complete `fc4aad8..c5c5e11` range added zero `Log.`, `println`, or
`Timber` calls. Shared text and selected text remain transient Quick Add
state, are capped at 240 characters, and do not reach logs or an intent extra
beyond Android's original user-directed share.

The two focus boundary notifications are exactly:

- `Focus block finished — take a break`
- `Break over — back to it`

Locked-widget inspection found counts plus `today_titles_permitted=0`, and
found every title, task-ID, and completable-slot key absent. The widget's live
action-authority and stop-generation gates are covered by the connected/JVM
suites.

Tasks CSV intake is capped at 5 MiB and 5,000 rows, rejects non-UTF-8 or a
non-own-schema header, validates before create-only repository dispatch, wipes
source bytes after parse, and clears parsed rows after the operation. CSV and
Markdown output tests prove every non-success path deletes its partial
document.

## Step 4 — disposable-device acceptance

The checklist used only fresh disposable overlays. Rows marked **manual** were
observed through Android-native input, UI hierarchy, launcher state, file
picker, and generated-file inspection. Rows marked **deterministic
substitution** could not be honestly driven by the headless shell; their exact
contract passed in the connected/JVM gate instead.

| Surface | Result | Evidence |
|---|---|---|
| Browser share | PASS — manual | First shared text prefilled Quick Add; a second share while capture was pending replaced the title instead of retaining the first value. |
| PROCESS_TEXT | PASS — manual | Android's selected-text action exposed Open Tasks and opened the same prefilled Quick Add route. |
| Quick Settings | PASS — manual | The tile was added and tapped; it opened empty Quick Add through the existing app route. |
| Today widget | PASS — manual | Opening a row reached its canonical task; completing a different row changed counts; the completed task was found in-app and Reopened, restoring the widget count. |
| App lock and widget privacy | PASS — manual | Immediate lock concealed widget titles, widget taps routed to Unlock Open Tasks, and the Glance preference inspection contained no title/ID/completable slots. The temporary lock was disabled before shutdown. |
| Focus-owned manual Stop | PASS — manual + deterministic DB proof | A fresh 25/5 cycle was stopped from task details; its banner stayed gone through background/foreground. Ownership/race suites prove no replacement time entry and no stop of another owner. |
| Boundary and stale focus alarm | PASS — deterministic substitution | `FocusSessionTest` and `FocusTimerOwnershipTest` prove generic boundary copy, phase advance, stale-alarm owner rejection, and both Stop/reconcile race directions. A real 25-minute wait was not claimed. |
| Saved search restart | PASS — deterministic substitution | `SearchSurfaceSavedViewsInstrumentedTest` plus Room/InMemory saved-view suites prove filtered save/apply and restoration. The headless OS could not grant the production Ctrl+K Dialog focus by injected key input. |
| Saved views through `.otvault` | PASS — deterministic substitution | Saved-view backup records, content fingerprints, frozen archive codec, staged import, and live-vault activation all passed. A second full SAF archive round-trip was not fabricated after the connected proof. |
| Bulk Complete and Undo | PASS — manual | Three non-blocked tasks were selected and completed together; the single Undo restored all three and the original open/blocked counts. |
| Weekly review | PASS — manual + connected | The fixture's non-empty Overdue and Project health sections were reviewed and the empty Stale/Unscheduled sections auto-advanced. Connected review tests prove the four-section model and pending-write Back handling. |
| Markdown project export | PASS — manual | A project Markdown document was saved through SAF and opened in a system viewer; heading, summary, milestone, planned tasks, checklist, and done task were present. |
| Tasks CSV round-trip | PASS — manual | Tasks-only export showed the plaintext disclosure; import preview reported the same task count with zero new projects/tags, created duplicates without merging, and one Undo restored the prior count. |
| Kanban drag | PASS — manual | A long-press drag moved one card from Planned to Backlog; both columns updated to one open task. |
| Kanban TalkBack action | PASS — deterministic substitution | TalkBack was enabled and its focus overlay was observed on the board, then restored to disabled. Headless shell injection cannot dispatch TalkBack gestures; `BoardViewInstrumentedTest` invokes and verifies the exact custom move action. |
| Installed Ember icon | PASS — manual + static | The full-colour card artwork was centred and unclipped under the Pixel launcher's round mask. Android 17's Default and Minimal icon styles kept the app-drawer card/bars/cursor visible; connected/static checks pin the monochrome resource. |

## Closure and residuals

Stage 6 changes no durable Room schema, backup-record schema, frozen
`.otvault` format, cloud transport, Drive scope, or live-data authority.
Plaintext Markdown and CSV are disclosed interop boundaries, and CSV import is
own-schema, create-only, and undoable.

The documented deterministic substitutions are evidence-source limitations,
not hidden manual claims. Standing project residuals remain:

- GitHub Actions is blocked before runner assignment by an account
  billing/spending-limit error; rerun the push workflow after that external
  account state is repaired;
- the expanded API 37.0 F6 canary lane is observe-only;
- a real headless `MainActivity` Ctrl+K Dialog focus transition is not a CI
  claim, although deterministic root wiring and query focus now pass without a
  skip; and
- Play Console, the supplied 512 px listing asset, and store distribution
  remain outside the sideload-only boundary.
