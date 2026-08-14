# Release 1.1.0 signed-sideload qualification

## Status and candidate identity

**Waiver-accepted candidate — pre-tag, not released.** The exact-head local
connected gate and signed artifact verification are green. The user explicitly
waived app-lock testing, secure cleanup, and GitHub CI qualification and
authorized tagging with those gaps. This is not fully qualified under the
original Task 18 plan.

- Date: 14 August 2026
- Product source commit:
  `19aecf4bf7ac7322e9ecdf51d0c09412e2c73b84`
- Version: versionName 1.1.0, versionCode 2
- Distribution: signed sideload only; no AAB, Play Console, or CI signing
- Signing: the established external user-held release identity; no signing
  material is stored or reproduced here
- Final artifact: `app-release.apk`, 16,242,815 bytes, SHA-256
  `e5a0d947b890c72cfa692f78e54341a5fe562415a57ee6489f7d6d19d262802c`

The future `v1.1.0` tag will point to the qualification-document candidate
commit, not create an impossible self-reference in the product source identity
above. No device identifier, process id, credential, private task text, Drive
id, or other secret belongs in this record.

## Automated verification

The passphrase viewport issue was corrected in the test only with two
`performScrollTo()` calls before the existing exact guidance assertions. The
focused test passed 1/1; the complete More suite passed 64/64; and the final
six-module connected module totals (tests/failures/errors/skips) were App
80/0/0/2, Data 179/0/0/0, Tasks 43/0/0/0, Projects 23/0/0/0, Schedule 2/0/0/0,
and More 64/0/0/0: 391 tests, 389 passed, 0 failures, 0 errors, 2 established
skips.

The forced signed APK build and `bash scripts/verify-release-apk.sh` passed.
The verifier proved a valid modern signature, version 1.1.0 (2), absence of the
debug qualification activity, non-debuggable packaging, and `drive.appdata` as
the sole Drive authorization scope. Established release-identity continuity and
the minified/shrunk release configuration are separate build evidence without
publishing certificate details. The final artifact is
16,242,815 bytes with SHA-256
`e5a0d947b890c72cfa692f78e54341a5fe562415a57ee6489f7d6d19d262802c`.

The final artifact is tied to the recorded product/test head `19aecf4`, rather
than an earlier widget candidate.

The post-candidate non-device gates are green: 553/553 forced local actions,
1,235 JVM tests with no failure/error/skip, six Android-test source compiles,
Room v9 drift, five byte-identical fixture families, workflow/whitespace, and
the exact privacy audit. The historical pre-fix connected run is retained below;
the final post-fix connected matrix is green with exactly the two established
skips.

## Focused pre-final evidence

The release workflow's first-launch text now matches production: a fresh user
chooses `Start without restoring`. A prior diagnostic signed flow proved fresh
workspace creation, project/task/checklist/tag persistence after force-stop,
an encrypted `.otvault` Replace round trip with unchanged visible content, and
immediate app-lock enable/unlock. Those observations used disposable synthetic
content and do not replace the clean final exact-head run below.

The current responsive widget implementation was also exercised on the actual
launcher at normal and 200% text. Counts and its 48 dp Quick Add action stayed
visible, and the action opened a sheet with an exactly empty title. The actual
launcher shortcut independently opened the same empty sheet. This is focused
regression evidence only; the final checklist still starts from a fresh exact-
head install.

## Final signed smoke checklist

Six rows were executed literally against the final exact-head APK on the sole
audited read-only disposable. `PASS` is based on observed results, not inferred
from a unit or connected test. Row 5 is explicitly waived, not evidence.

| # | Required step | Result |
|---|---|---|
| 1 | Fresh launch; choose `Start without restoring` | PASS |
| 2 | Add a project, a task with one checklist item, and a tag | PASS |
| 3 | Force-stop and relaunch; all created content persists | PASS |
| 4 | Export `.otvault`, import with Replace, and verify counts/content | PASS |
| 5 | Enable immediate app lock, background, and unlock through the system prompt | **SKIPPED — explicit user instruction** |
| 6 | Place Today widget; counts render and its visible 48 dp Quick Add action opens an exactly empty sheet | PASS |
| 7 | Use the actual launcher Quick Add shortcut; an exactly empty sheet opens | PASS |

The original plan required all seven rows; the user explicitly waived row 5 for
this candidate.

## Stage 7 release extras

| Required extra | Result |
|---|---|
| Save, activate, refine, clear, and restore one filtered view | PASS, including force-stop persistence |
| Confirm grammar tokens before one atomic create | PASS, six-token confirm-only grammar and one Root create |
| Duplicate one task and prove Undo affects only the copy | PASS, open count 7→8→7 with no copy |
| Switch Insights completion trend between chart and table | PASS, 7/30/90 ranges and project/tag rows match |

All four extras passed; none is waived.

## Disposable cleanup

**SKIPPED — explicit user instruction.** The temporary `.otvault` export was
removed, but credential-file cleanup, screen-credential removal, and
disposable-overlay shutdown were not performed; residual state remains.
Neither a credential nor a device identifier is recorded here. The provisional
signed workspace and widget were erased before the debug gate, so the final
signed checklist began from a fresh install.

## Remote qualification and tag

- **SKIPPED — explicit user instruction.** GitHub CI qualification was not run
  for this candidate, and the authorized tag will be created without CI
  evidence. This record does not claim any CI job passed.
- The API 37.0 F6 phrase **credential-encrypted storage unavailable** is
  historical observe-only context, not a check of this candidate.
- The user explicitly accepted these original Task 18 gaps and authorized
  tagging. The later HANDOFF-only closure remains a separate post-tag action.

This is an explicitly waiver-accepted pre-tag candidate, not a fully qualified
release under the original Task 18 plan. Release 1.1.0 does not yet exist.
