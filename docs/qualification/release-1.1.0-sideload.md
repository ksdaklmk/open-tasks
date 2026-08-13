# Release 1.1.0 signed-sideload qualification

## Status and candidate identity

**Candidate only — tagging is blocked.** The exact-head connected gate is red,
so the final signed artifact was not rebuilt. Its seven-row smoke,
disposable-overlay cleanup, remote CI, and annotated tag are all still TODO.

- Date: 13 August 2026
- Product source commit:
  `ec09e1b1e48653e61e4ecc833c54b5962b0fef92`
- Version: versionName 1.1.0, versionCode 2
- Distribution: signed sideload only; no AAB, Play Console, or CI signing
- Signing: the established external user-held release identity; no signing
  material is stored or reproduced here
- Final artifact: **TODO (release blocker):** `app-release.apk` byte count and
  SHA-256 from the forced exact-head build

The eventual `v1.1.0` tag will point to the later qualification-document
candidate commit, not create an impossible self-reference in the product
source identity above. No device identifier, process id, credential, private
task text, Drive id, or other secret belongs in this record.

## Automated verification

**TODO (release blocker):** first fix and pass the complete six-module
connected gate. Then build the signed APK from the exact product head with all
release actions forced, run `bash scripts/verify-release-apk.sh`, and record the
action count, duration, verifier result, final byte count, and SHA-256. The
verifier must prove a valid modern signature, version 1.1.0 (2), absence of the
debug qualification activity, non-debuggable packaging, and `drive.appdata` as
the sole Drive authorization scope. Record established release-identity
continuity and the minified/shrunk release configuration as separate build
evidence without publishing certificate details.

Do not reuse the digest of an earlier candidate: `ec09e1b` changed production
widget code after that artifact was built.

The post-`ec09e1b` non-device gates are green: 553/553 forced local actions,
1,235 JVM tests with no failure/error/skip, six source compiles, Room v9 drift,
five byte-identical fixture families, workflow/whitespace, and the exact privacy
audit. The connected matrix is not green: its complete run recorded 391 tests,
388 passes, the two established skips, and one failure in More's passphrase
length-guidance assertion. No signed build or final smoke may proceed until a
reviewed fix and a full green connected rerun exist.

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

**TODO (release blocker):** execute every row literally against the final
exact-head APK on the sole audited read-only disposable. `PASS` must be based on
an observed result, never inferred from a unit or connected test.

| # | Required step | Result |
|---|---|---|
| 1 | Fresh launch; choose `Start without restoring` | TODO |
| 2 | Add a project, a task with one checklist item, and a tag | TODO |
| 3 | Force-stop and relaunch; all created content persists | TODO |
| 4 | Export `.otvault`, import with Replace, and verify counts/content | TODO |
| 5 | Enable immediate app lock, background, and unlock through the system prompt | TODO |
| 6 | Place Today widget; counts render and its visible 48 dp Quick Add action opens an exactly empty sheet | TODO |
| 7 | Use the actual launcher Quick Add shortcut; an exactly empty sheet opens | TODO |

Seven of seven rows must pass before tagging.

## Stage 7 release extras

| Required extra | Result |
|---|---|
| Save, activate, refine, clear, and restore one filtered view | TODO |
| Confirm grammar tokens before one atomic create | TODO |
| Duplicate one task and prove Undo affects only the copy | TODO |
| Switch Insights completion trend between chart and table | TODO |

Any failed row or extra blocks the release.

## Disposable cleanup

**TODO (release blocker):** delete the temporary credential file without
reading or printing it, disable the disposable screen credential through the
visible system flow, kill only the audited read-only overlay, and confirm that
both the ADB target list and disposable-emulator process audit are empty. The
protected persistent AVD must remain untouched.

At the safe pause, no Gradle or instrumentation process is running. The sole
audited read-only disposable remains alive with its temporary credential state
intact for resumption; neither the credential nor any device identifier is
recorded here. The provisional signed workspace and widget were erased before
the debug gate, so the final signed checklist must begin from a fresh install.

## Remote qualification and tag

- **TODO (release blocker):** push the candidate under the GitHub Free-only
  ruling and identify the exact push workflow by candidate head SHA.
- **TODO (release blocker):** require `verify`, `release`, and exactly one API
  36 job to succeed. Keep exactly one API 37.0 lane observe-only; if red, record
  the reason exactly as **credential-encrypted storage unavailable**.
- **TODO (release blocker):** create and push annotated tag `v1.1.0` only after
  the required jobs pass, assert that it points to the qualified candidate,
  then complete the later HANDOFF-only closure commit.

Until these TODOs are discharged, release 1.1.0 does not exist.
