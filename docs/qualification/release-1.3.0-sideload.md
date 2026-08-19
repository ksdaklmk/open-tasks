# Release 1.3.0 sideload qualification

## Status and candidate identity

**Pre-tag candidate.** The signed artefact is built and verified at the
final reviewed Stage 9 head; its smoke run is executed and recorded
below. The `v1.3.0` tag will be created after this record is committed;
the push decision is the owner's per `RELEASING.md`.

- Date: 19 August 2026
- Product source commit:
  `23c3510e53270a9741179ca12f69bf215da2d0a2c747e199f2f36f4d951fbf81`
  is the APK SHA-256; the tree is the qualification head `86af72b`
  (version bump `21e3926`, Ruling L bump-first) plus the docs commits
  through the Step 5 acceptance record. Stage 9 began at `8d70c96`.
- Version: versionName 1.3.0, versionCode 4
- Distribution: signed sideload only; no AAB, no Play Console, no CI
  signing. CI continues to build the release unsigned.
- Signing: the established external user-held release identity,
  unchanged since 1.0.0, so updates install over the top with data
  preserved. No signing material, file location, alias, or certificate
  identifier is recorded here.
- Artefact: `app-release.apk`, 16,595,235 bytes, SHA-256
  `23c3510e53270a9741179ca12f69bf215da2d0a2c747e199f2f36f4d951fbf81`
- 1.2.0 upgrade baseline: built in a separate git worktree at tag
  `v1.2.0` (`8841fa1`), 16,372,751 bytes, SHA-256
  `f4738e34cc92336f3ed646aebe43f6416224b59a71ce4643de229b9379e74e90`,
  `verify-release-apk` all checks passed. `main` was never checked out
  at the tag; the four protected tree entries were untouched.

## Automated verification

`bash scripts/verify-release-apk.sh` against the signed APK, re-run
immediately before installation:

    verify-release-apk: all checks passed

(exit 0; checks: signature verifies, versionName/versionCode match the
build file at 1.3.0 (4), the debug qualification activity is absent
from the manifest, `auth/drive.appdata` is present and the sole Drive
scope in the dex, and the package is not debuggable.) On device,
`dumpsys package app.opentasks` independently reported
`versionName=1.3.0`, `versionCode=4`, no `DEBUGGABLE` flag.

Supporting host gates at the same head were executed as Task 17 Steps
0–3 and are recorded in `docs/qualification/stage9-board-flow-automation.md`
(host gates 1,355/0, signed minified release build, seven-module
Fold8 connected gate, ten-class compact gate, migration 10/10,
determinism and scope scans).

## Signed smoke checklist

Executed against this exact signed APK. Rows 1–5 and 7 ran on the sole
audited disposable ADB target, the `Fold8_Acceptance` AVD booted with
`-read-only -no-snapshot-load -no-snapshot-save` under the Stage 8
headless procedure (Ruling T: windowed boots of that AVD give false
failures; a windowed-boot ANR observed once was eliminated by the
headless procedure and did not recur). Row 6 ran on the compact-profile
scratch AVD `Pixel6_Scratch` booted with the same flags, with the
widget placed by hand by the owner. Every PASS below comes from an
observed result. No row is waived.

| # | Required step | Result |
|---|---|---|
| 1 | Fresh launch; choose `Start without restoring` to create the local workspace | PASS — Home renders the Stage 9 My Day surface with suggestions, the active timer, and `5 overdue items` |
| 2 | Add a project, a task with one checklist item, and a tag | PASS — project `Release smoke` created (`4 projects`); task `Smoke release task` added via Quick Add and visible in Inbox; a CommonMark checklist item `- [ ] Row two checklist item` saved (`Changes saved`); tag `smoke` created and added (`Tag created and added`, `1 selected`) |
| 3 | Force-stop and relaunch; everything from row 2 persists | PASS — task, checklist note text, tag selection, and project all present after force-stop and relaunch |
| 4 | Export `.otvault`, then import it back; counts match | PASS — export wrote 43,051 bytes with `Vault exported with 0 attachments.`; the import preview read `This archive holds 56 records and 0 attachments.`; after `Replace vault` the project count (`4 projects • 1 at risk`), the seeded task with its checklist text and `smoke` tag, and all Home content matched |
| 5 | Enable immediate app lock, background past the delay, and unlock | PASS — lock set to `Immediately`; after home and return the `Unlock Open Tasks` overlay appeared and the credential restored access |
| 6 | Place the Today widget on the launcher; counts render | PASS — placed by hand by the owner on the compact AVD (2×1 minimum honoured; launcher recorded span 3×1 with minSpan 2×1); `dumpsys appwidget` shows the widget bound with live RemoteViews, and the rendered text reads `0 open today • 5 overdue` with the Quick Add button beside it (macOS Vision OCR of a screenshot; uiautomator could not idle on the ticking widget) |
| 7 | Open the app via the Quick Add launcher shortcut | PASS — the launcher's `quick_add` shortcut (`Capture a task`) launched behind app lock; after unlocking, the Quick Add sheet was open |

Summary: **7 of 7 rows PASS** (row 6 executed this release, closing the
1.2.0 gap).

## Stage 9 release extras

Three representative Stage 9 behaviours repeated against the same
signed build, alongside the Step 5 manual acceptance matrix already
accepted by the owner (recorded in
`docs/qualification/stage9-board-flow-automation.md`).

| Required extra | Result |
|---|---|
| My Day curation entry point | PASS — `Add Reconcile July invoices to My Day` from Home produced the row inside My Day with the `Added to My Day` confirmation and its More-options menu |
| Automations editor on More | PASS — the five-type Add sheet listed all five rule types; `Badge stale tasks` with a threshold of 7 and All-projects scope created as rule 1 (`1 of 20 rules`, `Automation rule created`, delete guard present) |
| v9→v10 upgrade in place | PASS — the 1.2.0 (3) build created a fresh v9 workspace and a `Upgrade smoke task`; installing 1.3.0 (4) over it (`adb install -r`) preserved the workspace: Home content, the running timer, all projects, and the seeded task present with `Complete Upgrade smoke task` available |

Summary: **3 of 3 extras PASS.**

## Disposable cleanup

Complete. The exported `.otvault` was deleted from the disposable
Downloads storage (verified empty); the temporary device screen
credential was cleared (`Lock credential cleared`, PIN never recorded
beyond the disposable overlay); the application package was uninstalled
from both disposable overlays; both emulators were killed cleanly. No
ADB target or emulator process remains. The protected
`Pixel_10_Pro_Fold` AVD was never booted. The separate 1.2.0 worktree
and its keystore copy will be removed after tagging.

## Tag

- Tag `v1.3.0`: created by annotated tag on the commit carrying this
  record, after every smoke row and extra passed. The push decision is
  the owner's per `RELEASING.md`.
- Installation on the owner's physical device is owner-controlled and
  outside this qualification.
