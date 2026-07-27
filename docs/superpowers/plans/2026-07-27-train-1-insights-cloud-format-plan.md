# Train 1 — Insights and Cloud Format Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship trustworthy local Insights and freeze the bounded, versioned
encrypted object/key foundation required by Drive sync.

**Architecture:** `InsightsEngine` is a pure `WorkspaceSnapshot` projection in
`core:domain`; `app` computes it and passes immutable UI state to stateless
More/Home composables. Separately, `core:sync` defines canonical plaintext
headers and payload codecs, while `core:crypto` creates and locally wraps a
vault-content key independent from SQLCipher. `core:data` combines the format
and AEAD without exposing plaintext to Drive.

**Tech Stack:** Kotlin, `java.time`, coroutines, kotlinx.serialization JSON,
Tink AEAD, Android Keystore, JUnit 4, Compose UI test, Room/SQLCipher fixtures.

**Backlog:** P2-F04 and P1-D02.

## Global Constraints

- Follow the master plan constraints.
- Insights must not add a database table, cached analytics store, invented
  health score, or background computation.
- Reporting intervals are local-calendar, half-open `[start, end)` ranges.
- Conflicted completed time is excluded by default and always reported
  separately.
- Cloud headers contain no record text, filenames, account data, or Drive IDs.
- Decoders check lengths and counts before allocating.

---

### Task 1.1: Define Insights types and interval rules

**Files:**
- Create:
  `core/model/src/main/kotlin/app/opentasks/core/model/Insights.kt`
- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/InsightsEngine.kt`
- Create:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/InsightsEngineTest.kt`

**Interfaces:**

```kotlin
enum class InsightsRange(val dayCount: Long) {
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    NINETY_DAYS(90),
}

data class InsightsSelection(
    val range: InsightsRange = InsightsRange.SEVEN_DAYS,
    val projectIds: Set<ProjectId> = emptySet(),
    val tagIds: Set<TagId> = emptySet(),
    val includeConflictedTime: Boolean = false,
)

data class MetricComparison(
    val current: Long,
    val previous: Long,
)

data class DurationQuality(
    val trusted: Duration,
    val conflicted: Duration,
)

data class InsightsSnapshot(
    val interval: InstantRange,
    val comparisonInterval: InstantRange,
    val completed: MetricComparison,
    val overdue: List<OverdueRow>,
    val estimateActual: EstimateActual,
    val projectTime: List<ProjectTimeRow>,
    val tagTime: List<TagTimeRow>,
    val milestoneHealth: List<MilestoneHealthRow>,
    val quality: InsightsQuality,
)

interface InsightsEngine {
    fun calculate(
        workspace: WorkspaceSnapshot,
        selection: InsightsSelection,
        now: Instant,
        zoneId: ZoneId,
    ): InsightsSnapshot
}
```

`InstantRange`, row types, and `InsightsQuality` are immutable `core:model`
data classes. Sort project/tag rows by duration descending then display name
using `Locale.ROOT`; sort milestones by due instant then name.

- [ ] Write failing tests for 7/30/90-day local-calendar boundaries, DST
spring/fall transitions, and exact exclusion at `endExclusive`.

- [ ] Run:

```bash
./gradlew :core:domain:testDebugUnitTest --tests '*InsightsEngineTest' --stacktrace
```

Expected: compilation fails because the Insights types and engine do not exist.

- [ ] Implement interval creation from `now.atZone(zoneId).toLocalDate()`,
where `endExclusive` is the next local midnight and `startInclusive` is
`range.dayCount - 1` days earlier. The comparison ends at `startInclusive`.

- [ ] Implement task filtering once, then reuse the selected task IDs for all
metrics. Exclude deleted/Bin tasks from active overdue rows.

- [ ] Re-run the focused test.

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/model/src/main/kotlin/app/opentasks/core/model/Insights.kt \
  core/domain/src/main/kotlin/app/opentasks/core/domain/InsightsEngine.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/InsightsEngineTest.kt
git commit -m "feat: add pure workspace insights engine"
```

### Task 1.2: Complete metric semantics and data-quality coverage

**Files:**
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/InsightsEngine.kt`
- Modify:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/InsightsEngineTest.kt`
- Modify:
  `core/model/src/main/kotlin/app/opentasks/core/model/Insights.kt`

**Interfaces:** Extends the types from Task 1.1 without changing method
signatures.

- [ ] Add failing tests for:
  completed current/previous counts; overdue bands `1..7`, `8..30`, and
  `31+`; missing estimates; clipped time-entry boundaries; Inbox attribution;
  multi-tag non-additive time; open milestone task counts; explicit project
  health; overlapping/conflicted time; project/tag filters; empty selections.

- [ ] Run the focused test and confirm assertion failures name each incomplete
metric.

- [ ] Implement trusted duration as completed time entries intersected with
the interval and clipped to its bounds:

```kotlin
val clippedStart = maxOf(entry.startedAt, interval.startInclusive)
val clippedEnd = minOf(entry.endedAt, interval.endExclusive)
val clipped = Duration.between(clippedStart, clippedEnd)
```

If an entry ID occurs in `workspace.timeEntryConflicts`, add it to
`conflicted`; include it in displayed totals only when
`selection.includeConflictedTime` is true. Never silently delete an overlap.

- [ ] Count estimates only for tasks completed in the interval and only when
their estimate is non-null. Carry `estimatedTaskCount`,
`unestimatedTaskCount`, and `actualTaskCount` so the UI exposes denominators.

- [ ] Re-run:

```bash
./gradlew :core:domain:testDebugUnitTest --tests '*InsightsEngineTest' --stacktrace
```

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/model/src/main/kotlin/app/opentasks/core/model/Insights.kt \
  core/domain/src/main/kotlin/app/opentasks/core/domain/InsightsEngine.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/InsightsEngineTest.kt
git commit -m "feat: complete qualified insights metrics"
```

### Task 1.3: Add the accessible Insights surface

**Files:**
- Create:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/InsightsScreen.kt`
- Create:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`
- Create: `feature/more/src/main/res/values/strings.xml`
- Create: `feature/home/src/main/res/values/strings.xml`
- Modify:
  `feature/home/src/main/kotlin/app/opentasks/feature/home/HomeScreen.kt`
- Modify:
  `docs/superpowers/plans/2026-07-27-train-1-insights-cloud-format-plan.md`

**Interfaces:**

```kotlin
data class InsightsUiState(
    val snapshot: InsightsSnapshot,
    val selection: InsightsSelection,
    val presentation: InsightsPresentation,
    val projectOptions: List<InsightsProjectOption>,
    val tagOptions: List<InsightsTagOption>,
)

enum class InsightsPresentation { CHART, TABLE }

data class InsightsProjectOption(val id: ProjectId, val displayName: String)
data class InsightsTagOption(val id: TagId, val displayName: String)
```

`InsightsScreen` receives only state and callbacks:
`onRangeChange`, `onProjectFilter`, `onTagFilter`,
`onIncludeConflictedTimeChange`, `onPresentationChange`, and `onBack`.

- [ ] Write Compose tests for More→Insights navigation, 7/30/90 range changes,
project/tag filters, conflicted-time disclosure, table parity, empty state,
200% text, keyboard traversal, and 48 dp actions.

- [ ] Run:

```bash
./gradlew :feature:more:connectedDebugAndroidTest --stacktrace
```

Expected: compile failure because `InsightsScreen` does not exist.

- [ ] Split the Insights destination out of `MoreScreen.kt`; do not move
Templates, Archive, or Bin yet. Add `MoreDestination.INSIGHTS`.

- [ ] Inject `InsightsEngine` into `WorkspaceViewModel`, derive
`InsightsUiState` from the current snapshot plus selection, and keep selection
in `SavedStateHandle` using enum names and record IDs only.

Bind `InsightsEngine` to `DefaultInsightsEngine` in the existing app Hilt
module. Feature-owned user-facing copy lives in each feature module's string
resources; Android library modules must not depend on app resources.
Derive the option lists from the unfiltered workspace, sort them by display
name with `Locale.ROOT`, and keep them stable while active filters change so a
user can add, switch, or clear filters even when the filtered result is empty.

- [ ] Implement restrained bars plus an equivalent ordered table. Each bar
has a text value and content description; red/green is never the sole signal.
Add explicit copy for no data, no estimates, no time, and all-conflicted data.

- [ ] Add one compact seven-day summary to More and a link from Home. Do not
add a Home dashboard grid.

- [ ] Re-run the More device suite and:

```bash
./gradlew :app:testDebugUnitTest \
  :feature:more:connectedDebugAndroidTest --stacktrace
```

Expected: exit `0` on the preserved normal emulator. Run
`:app:connectedDebugAndroidTest` separately on a sole disposable, read-only,
no-snapshot-save/no-snapshot-load emulator because AGP uninstalls
`app.opentasks` after that suite. Never run the App suite against the preserved
workspace.

- [ ] Commit:

```bash
git add feature/more app/src/main feature/home/src/main \
  docs/superpowers/plans/2026-07-27-train-1-insights-cloud-format-plan.md
git commit -m "feat: add accessible workspace insights"
```

**Approved execution correction (2026-07-27):** Train 0 proved that the App
connected suite uninstalls its target package. This task therefore preserves
the verified workspace through the safe split above. The plan's original
app-resource path was also corrected to feature-owned resources plus the
existing Hilt module, which is required by Android module boundaries and the
specified interface injection.

The original three-field `InsightsUiState` also lacked the complete option
lists required by its own project/tag multi-filter controls. The immutable
option fields above close that testability and interaction gap without
changing the engine or stateless-screen boundary.

### Task 1.4: Separate and locally wrap the vault-content key

**Files:**
- Modify:
  `core/crypto/src/main/kotlin/app/opentasks/core/crypto/VaultCrypto.kt`
- Modify:
  `core/crypto/src/main/kotlin/app/opentasks/core/crypto/TinkVaultCrypto.kt`
- Create:
  `core/crypto/src/main/kotlin/app/opentasks/core/crypto/VaultContentKeyStore.kt`
- Create:
  `core/crypto/src/main/kotlin/app/opentasks/core/crypto/AndroidVaultContentKeyStore.kt`
- Modify:
  `core/crypto/src/test/kotlin/app/opentasks/core/crypto/TinkVaultCryptoTest.kt`
- Create:
  `core/crypto/src/androidTest/kotlin/app/opentasks/core/crypto/AndroidVaultContentKeyStoreInstrumentedTest.kt`

**Interfaces:**

```kotlin
interface VaultCrypto {
    fun createKey(): VaultKey
    fun wrapForRecovery(
        unlockedKey: VaultKey,
        passphrase: CharArray,
    ): VaultKeyEnvelope
    fun unlock(passphrase: CharArray, envelope: VaultKeyEnvelope): VaultKey
    fun changePassphrase(
        unlockedKey: VaultKey,
        newPassphrase: CharArray,
    ): VaultKeyEnvelope
    fun encryptRecord(
        key: VaultKey,
        context: CryptoContext,
        plaintext: ByteArray,
    ): ByteArray
    fun decryptRecord(
        key: VaultKey,
        context: CryptoContext,
        ciphertext: ByteArray,
    ): ByteArray
}

interface VaultContentKeyStore {
    fun getOrCreate(vaultId: VaultId): VaultKey
    fun replace(vaultId: VaultId, key: VaultKey)
    fun delete(vaultId: VaultId)
}
```

Keep `createVault(passphrase)` only as a deprecated source-compatible default
that calls `createKey` and `wrapForRecovery`; remove it after all callers
migrate.

- [ ] Add failing tests proving two calls create different keys, local key
bytes differ from the SQLCipher key fixture, recovery rewrapping preserves
decryption, wrong passphrases fail, closed keys fail, and key-loss fails closed.

- [ ] Add Android tests proving Keystore alias isolation per `VaultId`,
process-reopen recovery, replace, delete, and invalidated-alias failure.

- [ ] Run:

```bash
./gradlew :core:crypto:testDebugUnitTest \
  :core:crypto:connectedDebugAndroidTest --stacktrace
```

Expected before implementation: compilation failures for the new API.

- [ ] Implement Android Keystore AES-GCM wrapping inside `core:crypto`, where
package-private code may access `VaultKey.serializedKeyset`. Store only nonce
and ciphertext in private preferences. Zero every temporary keyset array in
`finally`; never convert passphrases to `String`.

- [ ] Re-run the focused suites.

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/crypto
git commit -m "feat: add independently wrapped vault content keys"
```

### Task 1.5: Define canonical bounded cloud objects

**Files:**
- Modify: `core/sync/build.gradle.kts`
- Create:
  `core/sync/src/main/kotlin/app/opentasks/core/sync/CloudObjectFormat.kt`
- Create:
  `core/sync/src/main/kotlin/app/opentasks/core/sync/CloudPayloads.kt`
- Create:
  `core/sync/src/main/kotlin/app/opentasks/core/sync/CloudBounds.kt`
- Create:
  `core/sync/src/test/kotlin/app/opentasks/core/sync/CloudObjectFormatTest.kt`
- Create: `core/sync/src/test/resources/cloud-format/v1/*.json`

**Interfaces:**

```kotlin
enum class CloudObjectFamily {
    MANIFEST,
    SNAPSHOT,
    OPERATION_SEGMENT,
    ATTACHMENT_CHUNK,
}

data class CloudObjectHeader(
    val magic: String = "OPEN_TASKS",
    val family: CloudObjectFamily,
    val schemaVersion: Int = 1,
    val cryptoVersion: Int = 1,
    val minimumReaderVersion: Int = 1,
    val vaultId: String,
    val objectId: String,
    val ciphertextLength: Long,
    val ciphertextSha256: String,
    val chunkIndex: Int? = null,
    val chunkCount: Int? = null,
)

interface CloudObjectFrameCodec {
    fun encode(header: CloudObjectHeader, ciphertext: ByteArray): ByteArray
    fun decode(source: InputStream, totalLength: Long): CloudObjectFrame
}
```

Frame bytes are: 4-byte big-endian header length, canonical UTF-8 JSON header,
then exactly `ciphertextLength` bytes. Canonical JSON uses fixed property
ordering, no insignificant whitespace, no unknown keys, and no locale-sensitive
formatting.

`CloudBounds` is fixed at: 16 KiB header, 1 MiB manifest, 64 MiB snapshot,
16 MiB operation segment, 4 MiB plaintext attachment chunk, 26 ciphertext
chunks per 100 MiB attachment, 10,000 operations per segment, 100,000 records
per snapshot, and 10,000 manifest inventory entries.

- [ ] Add failing tests for golden byte equality, every family, negative and
overflowing lengths, unknown/missing keys, checksum mismatch, trailing bytes,
unsupported reader version, invalid chunk tuples, invalid UTF-8, and
allocation-before-bound regressions.

- [ ] Run:

```bash
./gradlew :core:sync:testDebugUnitTest --tests '*CloudObjectFormatTest' --stacktrace
```

Expected: compilation failure because the format does not exist.

- [ ] Add kotlinx serialization to `core:sync`, implement strict decoding, and
store v1 golden headers/payloads in test resources. Do not serialize
`ByteArray` through JSON; frames carry raw ciphertext.

- [ ] Re-run the focused suite.

Expected: exit `0`.

- [ ] Commit:

```bash
git add core/sync
git commit -m "feat: define bounded encrypted cloud object format"
```

### Task 1.6: Authenticate the format and freeze golden vectors

**Files:**
- Modify:
  `core/crypto/src/main/kotlin/app/opentasks/core/crypto/VaultCrypto.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/EncryptedCloudObjectCodec.kt`
- Modify: `core/data/build.gradle.kts`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/EncryptedCloudObjectCodecTest.kt`
- Create: `core/data/src/test/resources/cloud-format/v1/*`
- Modify: `docs/architecture.md`
- Modify: `docs/threat-model.md`
- Modify: `DESIGN.md`
- Modify: `HANDOFF.md`

**Interfaces:**

```kotlin
interface EncryptedCloudObjectCodec {
    fun encrypt(
        headerIdentity: CloudHeaderIdentity,
        plaintext: ByteArray,
        key: VaultKey,
    ): ByteArray

    fun decrypt(
        framedObject: InputStream,
        totalLength: Long,
        key: VaultKey,
    ): DecryptedCloudObject
}
```

`CloudHeaderIdentity` contains family, versions, vault/object IDs, and optional
chunk tuple. `CryptoContext.associatedData()` must include all of those fields
in a canonical length-prefixed encoding; the checksum and ciphertext length
are verified before AEAD decrypt.

- [ ] Add failing tests for deterministic golden header bytes, successful
round-trip, random nonce/ciphertext variation, swapped header, vault, object,
family, version and chunk rejection, tamper, truncation, checksum corruption,
wrong key, old readable version, and future minimum-reader rejection.

- [ ] Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*EncryptedCloudObjectCodecTest' --stacktrace
```

Expected: compilation failure for the new codec.

- [ ] Add `implementation(project(":core:crypto"))` to `core:data`; implement
checksum-before-decrypt and ensure plaintext arrays are zeroed by callers after
decode. Translate all failures to typed `CloudDecodeFailure` values; do not log
the rejected object.

- [ ] Re-run `:core:crypto`, `:core:sync`, and codec tests. Review golden
fixture diffs byte-for-byte.

- [ ] Run the train exit gates and the More/App device suites. Update the five
architecture/product documents with metric definitions, key separation,
object bounds, and threat gates T02–T06/T11–T13.

- [ ] Commit:

```bash
git add core/data core/crypto core/sync DESIGN.md HANDOFF.md \
  docs/architecture.md docs/threat-model.md
git commit -m "feat: freeze authenticated cloud format foundation"
```
