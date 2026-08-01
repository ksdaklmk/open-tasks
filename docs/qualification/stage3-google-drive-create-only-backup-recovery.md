# Stage 3 Google Drive create-only backup and recovery qualification

## Outcome

Stage 3 is implemented and verified as of 1 August 2026. Task 14 began from
`a325017` and is closed by the commit containing this document, with subject
`docs: verify create-only Stage 3 backup`.

Encrypted Room remains the sole live structured-data authority. The provider
boundary uses only Drive `appDataFolder`, immutable create-by-ID objects, exact
ID reads, bounded lists and deletes, and resumable creates. It contains no
mutable update, PATCH, provider revision, conditional-write, synchronization,
merge, or attachment-byte path.

No account, token, permission identifier, Drive object identifier, request or
response body, resumable session URI, cryptographic material, or private
workspace content is recorded here.

## Deterministic end-to-end evidence

Two isolated installation contexts exercised the production codecs,
coordinators, encrypted Room state, vault activation protocol, and a
deterministic create-only provider. The final tests passed these scenarios:

- epoch-one setup with two independent complete bases;
- incremental immutable publication and process-death-safe upload resumption
  for a frame larger than 5 MiB;
- staged recovery and byte-exact canonical workspace comparison;
- two-base takeover with two contenders racing one exact successor ID;
- rejection of the loser and an ignored late old-epoch publication;
- fallback recovery after corruption of the current base;
- same-generation passphrase rotation at the next publication sequence;
- divergent old-owner preservation into a separate lineage;
- disconnect and reconnect, including zero provider calls while dormant;
- wrong-account rejection before any lineage access;
- terminal-tombstone creation, interrupted cleanup, bounded resumption, and a
  final provider state containing only the tombstone; and
- an independent inert Android backup package.

The test exposed and closed three root-cause defects before qualification: a
stale logical-schema seed, missing ownership binding for the immediate
post-takeover passphrase baseline, and dormant/recovered lineages that could
contact the provider before reactivation or fail to request a complete
baseline.

## Credentialed provider evidence

The sole audited API 37 disposable target ran the debug-only, non-exported
credentialed qualification against Drive REST v3 `appDataFolder`. Its first
attempt reached the original ten-minute instrumentation bound after a slow
sequence of bounded provider calls. The harness was corrected to remove only
its exact marker objects from an interrupted prior run and to use a twenty-
minute outer bound; each HTTP operation retained its existing fifteen-second
bound.

The fresh credentialed run passed in approximately six minutes. It proved ten
live create-only successor races, thirty rejected loser retries, unchanged
authenticated winner readbacks, discarded-success resolution by exact ID, and
final cleanup. This live gate verifies the provider coordination primitive;
the complete two-installation recovery, takeover, wrong-account, passphrase,
disconnect, tombstone, and Android-package lifecycle is deterministic evidence
over the same production protocols. A second live account and a destructive
second physical installation were deliberately not used, so no broader live
claim is made.

## Repository and device gates

- `testDebugUnitTest lintDebug :app:assembleDebug`: passed; 547 Gradle tasks
  (20 executed, 527 up-to-date) and 790 JVM tests in 66 suites.
- `:app:assembleRelease`: passed; 441 Gradle tasks, including R8, resource
  shrinking, optimization, and release packaging.
- Release inspection found only `drive.appdata`, excluded the debug
  qualification activity, and found no app mutable-authority, broad-scope, or
  client-secret string. The only `compareAndSwap` strings were runtime-library
  `sun.misc.Unsafe` symbols.
- Room schema drift and deterministic create-only fixture regeneration passed
  with no difference.
- The sole read-only/no-snapshot API 37 disposable passed all connected suites:
  275 tests, zero failures or errors, and one intentional credential-only skip.
- Focused boundary and production-protocol recovery/takeover suites passed.
- Privacy scans found only redacted declarations, endpoints, tests, negative
  assertions, historical provider evidence, and runtime-library symbols; no
  runtime value is logged or shown in copy and no production mutable authority
  exists.

## Protected-workspace comparison

The protected named snapshot was loaded without instrumentation, install,
uninstall, clear, restore, or backup-manager commands. Package identity and
version, database/WAL/SHM inode identities and sizes, five visible overdue
records, three project summaries, and the active timer matched the saved
workspace. The target was stopped without saving a snapshot, and final ADB and
emulator-process audits were empty.

## Residual boundary and next action

Stage 3 does not migrate accounts, transport attachment bytes, synchronize or
merge structured records, or revoke historical encrypted copies after a
passphrase change. Those are explicit product boundaries, not qualification
failures.

The next approved work is the already-designed Galaxy Z Fold 8 trifold-ready
adaptive layout slice. Stage 4 remains after that slice and requires a new
explicit request. Task 14 stops here.
