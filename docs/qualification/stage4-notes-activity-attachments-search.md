# Stage 4 notes, activity, attachments, and search qualification

## Outcome

Stage 4 is qualified as of 3 August 2026. This record covers first-class
notes, immutable activity history, extended local search, the create-only
attachment blob lifecycle, its product surfaces, and the final exit gates.
Encrypted Room remains the sole live structured-data authority; no remote merge
or cloud-to-Room record path was added.

No account, token, consent text, provider object identifier, request or
response body, workspace identifier, note text, attachment name, or protected
workspace detail is recorded here.

## One-shot credentialed attachment evidence

The debug-only, non-exported qualification harness preserved at `a813c41` ran
once and passed in **606.947 s**. It returned its bounded `PASS` result only
after Drive app-data authorization, ten create-only successor races, thirty
rejected loser retries, unchanged authenticated winner readbacks,
discarded-success exact-ID resolution, and cleanup all passed. The attachment
properties added for Stage 4 were:

- pre-generated exact-ID chunk create plus occupied-ID rejection;
- byte-identical authenticated chunk readback;
- manifest exact-ID create/readback and single-manifest lookup, including a
  missing unrelated blob set; and
- exact-ID cleanup of every generated attachment object.

This was a one-shot credentialed check and was never rerun. It proves only the
listed live properties and cleanup for the preserved harness. It makes no
broader live-provider, two-installation, account, or protected-workspace
claim.

## Connected-gate history

The first six-module connected gate ran **282 tests: 10 failures, 0 errors,
1 expected credential-only skip**. The fixes were deliberately small and
committed as `1ba5d0e` (created task selection), `b5e6a1f` (activity test
expectations and fixtures), `3648595` (project notes-section scroll),
`a328695` (await the collected attachment projection), and `bf2f95a` (retry
the fold wait while the Activity reattaches). The next full gate was **282:
1 failure, 0 errors, 1 credential-only skip**; the remaining row was the
pre-existing fold harness, not a Stage 4 product surface.

Four narrow fold-harness attempts followed: `7e421ac`, `ff0651f`,
`240e65e`, and `7410f865` successively narrowed the unsupported
cross-display condition; `204d4cea` then attempted an IME completion for the
project-template interaction and was corrected by `3d2f249` to close the
keyboard before the same physical confirm click. The preceding authoritative
gate at `7410f865` was **283: 1 failure, 0 errors, 2 expected skips**.

The capability-guard rerun after `3d2f249` was causally invalidated and
stopped: after CLOSED, the host once reported `RESUMED` but the Compose tree
became transient/unusable. Reset did not restore a usable host, so later app,
schedule, and task failures had the same missing-hierarchy signature. The last
complete XMLs were core:data **150/0/0/0**, app **25/6/0/1**, and schedule
**2/2/0/0**. Recording those cascades as product failures would have been
misleading.

The approved final correction is `b3da5d2`: the fold-continuity row skips
before any `device_state` mutation only when
`ro.boot.qemu.avd_name == Pixel_10_Pro_Fold`. This target's cross-display
transition is unsupported by the ActivityScenario/Compose harness. Every
non-skipped target retains hard lifecycle assertions. This is not native
fold-continuity evidence; real fold continuity is post-Stage-4 work.

## Final disposable connected gate

The final gate started with an empty ADB device list and no host qemu process.
Its sole disposable target was launched as exactly:

```text
emulator -avd Pixel_10_Pro_Fold -read-only -no-snapshot-load \
  -no-snapshot-save -no-window -no-boot-anim
```

The live process was headless and retained those read-only/no-snapshot flags.
The sole target reported API 37 / Android 17 and the exact AVD name above.
Only `font_scale=1.0` was written to the disposable overlay.

The required throwaway `:app` preflight ran **24 tests, 4 inherited-workspace
failures, 0 errors, and 1 credential-only skip**; AGP then cleanly uninstalled
the throwaway app from the overlay. The credentialed gate was not rerun.

The authoritative command completed **BUILD SUCCESSFUL in 15m 03s**:

| Module | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| `:core:data` | 150 | 0 | 0 | 0 |
| `:feature:tasks` | 36 | 0 | 0 | 0 |
| `:feature:projects` | 16 | 0 | 0 | 0 |
| `:feature:schedule` | 2 | 0 | 0 | 0 |
| `:feature:more` | 54 | 0 | 0 | 0 |
| `:app` | 24 | 0 | 0 | 2 |
| **Total** | **282** | **0** | **0** | **2** |

The two skips were exactly the credential-only qualification row and the exact
`Pixel_10_Pro_Fold` harness exception. There were no other skips. AGP left no
installed `app.opentasks` package. The emulator was shut down with snapshots
disabled; final ADB and host qemu/emulator audits were empty.

## Repository, release, schema, and privacy gates

- Forced-fresh `testDebugUnitTest lintDebug :app:assembleDebug` passed with
  **547 executed Gradle tasks**. Its XML aggregate was **935 JVM tests in 80
  suites, with 0 failures, errors, or skips**.
- Forced-fresh `:app:assembleRelease` passed with **441 executed Gradle
  tasks**, including R8, lint-vital, shrinking, optimisation, and packaging.
- Schema drift was clean. Both deterministic fixture generators were clean,
  as was `git diff --check`.
- The release APK exposes only `drive.appdata`; it has no debug qualification
  activity or OAuth client-secret string. The only `compareAndSwap` strings
  are runtime-library `sun.misc.Unsafe` symbols.
- Production Kotlin logging scans were empty: no `Log.`, `Timber.`, `println`,
  or `printStackTrace` calls carrying private fields were introduced.

## Recorded Stage 5 limitations

1. Purged attachments' blob sets never become GC candidates: their records are
   hard-deleted before individual collection. This is a conservative encrypted-
   byte leak only; attachment-only destructive deletion and terminal deletion
   still clear bytes. The durable remedy is a schema-backed retired-set index,
   and Room v8 is frozen.
2. `AttachmentBlobCoordinator.resume()` has no product caller. Interrupted
   intakes expire after 24 hours; retain `resume()` and its tests. There is no
   in-row transfer-progress contract.

Samsung Remote Test Lab RTL remains externally blocked pending Samsung
developer-account approval and is unchanged by this qualification.
