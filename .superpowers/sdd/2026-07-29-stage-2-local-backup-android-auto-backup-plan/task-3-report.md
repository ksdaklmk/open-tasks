# Task 3 Report: Atomic Local Mutation Journaling

## Status

DONE

Commit:

```text
feat: journal local mutations by generation
```

## Implementation summary

- Added the canonical v1 mutation payload model and exact ordered schemas for
  all 18 Room-backed record families.
- Added strict encoding and decoding with canonical-byte equality, strict
  UTF-8, exact version checks, unpadded canonical Base64, payload and field
  bounds, identity agreement, enum/date/zone/count/revision validation, caller
  input preservation, and decoder-owned buffer clearing.
- Wrapped every Room command in one outer transaction that captures the
  lightweight logical pre/post identities, revision tuples, or small
  non-revision values; allocates one generation only when the diff is
  non-empty; materializes only changed after-images; and appends stable
  family/identity-ordered sequences.
- Journal rows contain complete post-command after-images or physical deletion
  markers. Generic record diffing also captures cascaded relation deletions.
- Added an injectable append boundary. A thrown append rolls product rows,
  generation state, and journal rows back together.
- Added matching in-memory record diffing, generation/sequence allocation,
  retained `present=false` task-tag relations, tombstones for physical task
  removal, staged journal append, and product rollback on journal failure.
- Bootstrapped a zero-generation `backup_state` row while seeding a new
  database, without journaling seed fixtures as local mutations.
- Removed the active `SyncOperationDao`, all runtime
  `sync_operations` writes, and the old v5 operation builders. The v5 entity,
  read-only legacy audit DAO, and migration source query remain.
- Preserved Room schema version 6, `MIGRATION_5_6`, generation-guarded CAS,
  physical `storageMode`, and the existing command/UI/provider surface.

## Files changed

- `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupRecordV1.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/BackupMutationCodec.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomBackupJournalSession.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/backup/InMemoryBackupJournal.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
- `core/data/src/test/kotlin/app/opentasks/core/data/backup/BackupMutationCodecTest.kt`
- `core/data/src/test/kotlin/app/opentasks/core/data/backup/InMemoryBackupJournalTest.kt`
- `core/data/src/androidTest/kotlin/app/opentasks/core/data/`
  `RoomVaultRepositoryInstrumentedTest.kt`

## TDD evidence

### Initial RED

The codec tests were written before the schema or codec. The required focused
command:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*BackupMutationCodecTest' --stacktrace
```

failed at test compilation with the expected unresolved
`BackupMutationCodec`, `BackupMutationPayloadV1`, `BackupRecordV1`, field
types, and record-family symbols.

The in-memory atomicity tests were then written before the journal dependency
and implementation. The required command:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*InMemoryBackupJournalTest' --stacktrace
```

failed at test compilation with the expected missing
`InMemoryBackupJournal` and injectable repository constructor.

Room task edit, project creation, workflow reorder, template instantiation,
relation purge, multi-expiry, rejected/idempotent, and append-failure tests
were added before the Room journal session. Android-test compilation failed
with the expected missing `BackupJournalAppendBoundary` and constructor seam.

### Debugging RED/GREEN

The first complete unit run exposed five existing-behaviour incompatibilities.
Focused reproduction showed three root causes: canonical empty recurrence
weekdays, a timer stopped in its start millisecond, and an unnecessary
in-memory diff of unchanged synthetic records. Minimal fixes made their
focused tests green.

A later full run exposed the weekly due-date fallback variant. The focused
regression:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests \
  '*BackupMutationCodecTest.weeklyRecurrenceAllowsEmptyWeekdaysWhenDomainUsesDueDateFallback' \
  --stacktrace
```

failed 1/1 before the validator change. The focused codec and original
recurrence-undo tests then passed 2/2.

Independent review found two additional compatibility cases. Focused tests
proved RED before either fix:

- in-memory tag removal emitted `TASK_TAG` deletion instead of a retained
  `present=false` after-image, and purging the task could not delete that
  retained relation; and
- canonical signed epoch values were rejected even though domain `Instant`
  values may predate 1970.

Both tag-parity tests failed 2/2, and the signed-epoch test failed 1/1. After
retaining/reconciling exact in-memory relation rows and limiting non-negative
checks to counts, sizes, and revisions, the three focused tests passed 3/3.

The first device run found four JUnit expression-bodied methods whose final
legacy-outbox assertion had been removed, causing a non-`Unit` return type.
Explicit `Unit` endings restored runner compatibility. The next full run
found two test synchronization races; waiting for repository bootstrap and
Room flow emission made both focused methods pass before the final full class
run.

## Verification evidence

### Complete Data unit tests

```bash
./gradlew :core:data:testDebugUnitTest --stacktrace
```

Result:

```text
80 tests, 0 skipped, 0 failed, 0 errors
BUILD SUCCESSFUL
```

The 80 tests include 19 codec tests, seven in-memory journal tests, 29 existing
in-memory repository tests, authenticated object tests, template codec tests,
and entity mapper tests.

### Room instrumentation

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.core.data.RoomVaultRepositoryInstrumentedTest \
  --stacktrace
```

Result:

```text
27 tests, 0 skipped, 0 failed, 0 errors
BUILD SUCCESSFUL in 45s
```

The eight new tests prove one-row and multi-row generation allocation,
sequence stability, full inserted-family coverage, relation deletion markers,
multi-task purge grouping, no allocation for rejected/idempotent commands,
new-database state bootstrap, and transactional append-failure rollback.

### Legacy runtime-write scan

```bash
rg -n \
  'syncOperationDao|INSERT INTO sync_operations|UPDATE sync_operations|DELETE FROM sync_operations' \
  core/data/src/main --glob '*.kt'
```

Result: no matches.

The remaining `sync_operations` references are limited to the v5 entity, the
read-only audit DAO, and the `MIGRATION_5_6` source query.

### Repository CI gate

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
```

Result:

```text
BUILD SUCCESSFUL in 16s
547 actionable tasks: 40 executed, 507 up-to-date
```

Additional checks:

```text
:core:data:compileDebugAndroidTestKotlin: PASS
git diff --check: PASS
```

## Device audit, start, and stop evidence

The initial audit showed no ADB target and no emulator/QEMU process. The only
device started was:

```bash
/Users/kk/Library/Android/sdk/emulator/emulator \
  -avd Pixel_10_Pro_Fold \
  -read-only \
  -no-snapshot-save \
  -no-snapshot-load \
  -no-window
```

The live audit before instrumentation proved:

```text
exactly one ADB target: emulator-5554
AVD: Pixel_10_Pro_Fold
API: 37
PID: 98383
qemu-system-aarch64-headless -avd Pixel_10_Pro_Fold -read-only
-no-snapshot-save -no-snapshot-load -no-window
```

No protected snapshot was named, loaded, booted, installed over, cleared, or
otherwise touched.

The disposable target was stopped with:

```bash
/Users/kk/Library/Android/sdk/platform-tools/adb emu kill
```

The emulator reported that snapshots were disabled and ignored the shutdown
save request. The post-stop audit showed no ADB target and no emulator/QEMU
process.

## Self-review and mutation pass

- Schema family count, field names, field types, field order, nullability, and
  composite identity order are centralized and exercised across all 18
  families.
- Canonical tests kill reordered-field, duplicate-key, duplicate-field,
  unknown-key, invalid-UTF-8, padded-Base64, version, identity, size, and
  ownership/clearing mutations.
- Generation tests kill eager allocation, separate generation, missing
  sequence, unstable family order, rejected/idempotent allocation, and missing
  multi-row mutations.
- Purge tests kill missing task/checklist/task-tag/reminder deletion markers
  and missing tombstone after-images; generic table diffing covers the other
  physically cascaded relation families.
- Failure tests kill product-only commit, generation-only commit, and
  journal-only commit implementations.
- In-memory relation tests kill `TASK_TAG` deletion-on-remove, loss of
  `present=false` revisions, incorrect re-add behavior, and failure to delete
  retained false rows during physical task purge.
- Signed-epoch coverage prevents count/size constraints from being
  incorrectly applied to legitimate pre-1970 timestamps.
- Room snapshot metadata deliberately excludes large task/template payload
  bytes and fetches complete records only for changed upserts, preventing
  valid large vaults from being Base64-materialized twice per command.
- The runtime-write scan kills any reintroduced active
  `sync_operations` DAO or SQL mutation.
- No backup capture, package storage, recovery, provider, WorkManager, or UI
  behavior was added.

## Concerns

No known correctness blockers remain. The pre/post logical diff queries all 18
record families inside the command transaction to make cascaded deletions
complete and deterministic, but retains only bounded identity/revision
metadata for large-payload families and materializes complete records only
when they changed. Command-local record collection remains a possible future
performance optimization without changing payload v1.

## Independent review fix round

The reviewer found no Critical issues and three Important issues:

1. in-memory task-tag removals did not preserve Room's `present=false` row;
2. full Room record materialization could exceed memory for a valid vault with
   many maximum-sized templates; and
3. epoch validation incorrectly rejected signed pre-1970 timestamps.

The fixes retain and reconcile exact in-memory task-tag entities, use
lightweight Room snapshots plus changed-row materialization, and distinguish
signed epochs from non-negative counts/sizes/revisions. Focused RED/GREEN
coverage was added for the two behavioral cases, and the complete unit and
connected suites passed after all three changes.

The reviewer re-inspected the fix diff, confirmed all three findings were
addressed, found no new Critical or Important regressions, and returned
`Ready to merge`.
