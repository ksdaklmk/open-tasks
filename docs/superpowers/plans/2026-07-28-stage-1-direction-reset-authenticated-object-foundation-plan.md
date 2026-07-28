# Stage 1 Direction Reset and Authenticated Object Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the stale Drive-primary programme with the approved
local-authority direction, clear the existing Insights lint blocker, and
complete the authenticated provider-independent object codec that later backup
and attachment services will share.

**Architecture:** Room remains the sole live structured-data authority.
`core:sync` owns strict bounded framing and canonical cloud-header identity,
`core:crypto` owns key material and generic AEAD operations, and `core:data`
composes those independent boundaries into `AuthenticatedCloudObjectCodec`.
This stage adds no provider, backup scheduler, Android Auto Backup rule,
recovery flow, or attachment transport.

**Tech Stack:** Kotlin 2.3.21, Android Gradle Plugin 9.3.1, Java 17 on JDK 21,
Compose BOM 2026.06.00, Room 2.8.4, SQLCipher 4.15.0, Tink 1.23.0,
Bouncy Castle 1.84, kotlinx.serialization, JUnit 4, Compose UI test v2, and
Node.js built-in `crypto` for independently generated test fixtures.

## Global Constraints

- Work directly on `main`; do not create a branch, worktree, or pull request.
- Preserve the protected Room workspace. Do not uninstall the application,
  clear its data, reset the repository, or attach app instrumentation to its
  normal emulator.
- Room is the only live structured-data authority. This stage adds no normal
  cloud-to-Room path and no structured-data synchronisation or merge.
- Do not remove or reinterpret existing outbox rows in this stage. Their
  non-destructive migration to a backup journal belongs to Stage 2.
- Keep the SQLCipher database key and vault-content key independently
  generated and wrapped.
- Keep `core:sync` and `core:crypto` free of Compose, Android UI, provider APIs,
  and credentials.
- Keep cloud framing provider-independent. No Google Identity, Drive REST,
  `CloudObjectStore`, WorkManager backup job, or provider identifier enters the
  codec.
- Bind every field of `CloudHeaderIdentity` as AEAD associated data: family,
  schema version, crypto version, minimum-reader version, vault ID, object ID,
  chunk index, and chunk count.
- Verify the complete frame length and SHA-256 ciphertext checksum before
  attempting AEAD decryption. A checksum is not an authentication claim.
- Preserve `CloudObjectFrame` one-shot ciphertext ownership. Clear owned
  ciphertext and associated-data arrays in `finally`.
- A caller-owned plaintext input remains caller-owned. A successful decode
  returns an owned plaintext object that must be closed or transferred once.
- Keep the fixed bounds: 16 KiB header; 1 MiB manifest ciphertext; 64 MiB
  snapshot ciphertext; 16 MiB and 10,000 operations per segment; 4 MiB
  attachment plaintext chunks; 26 chunks; 100,000 records per snapshot; and
  10,000 manifest inventory entries.
- Preserve strict canonical UTF-8 headers and compatibility version `1`.
- Return typed untrusted-object failures without logging rejected content,
  identifiers, checksums, ciphertext properties, keys, or recovery metadata.
- Keep `minSdk 36`, `compileSdk 37`, `targetSdk 37`, Java 17, JDK 21, and AGP
  built-in Kotlin. Do not apply `org.jetbrains.kotlin.android`.
- Use JUnit 4 assertions and camelCase test names. Add no mocking, Turbine,
  Robolectric, or coroutine-test dependency.
- User-facing contract text uses **backup**, **cloud attachment**, **restore**,
  and **active device**, never a promise of sync, Drive-primary authority, or
  concurrent multi-device editing.
- Android Auto Backup remains disabled until Stage 2 installs the strict
  portable-package include rules. Documentation must describe it as approved,
  not already shipped.
- GitHub maintenance and dependency-PR work remain paused.
- Run release assembly separately from lint because the repository records an
  AGP/KSP release-lint race.

---

## Stage Boundary and File Map

This plan is the first of the six stages approved in
`docs/superpowers/specs/2026-07-28-local-authority-cloud-attachments-backup-design.md`.
Later stages receive separate plans after this foundation passes review:

- Stage 2 — local backup and Android Auto Backup;
- Stage 3 — app-managed Drive backup and recovery takeover;
- Stage 4 — notes, activity, cloud attachments, and search;
- Stage 5 — remaining platform features; and
- Stage 6 — production qualification and rollout.

The file responsibilities locked by this plan are:

- `core/sync/src/main/kotlin/app/opentasks/core/sync/CloudHeaderIdentityEncoding.kt`
  — canonical, strict, length-prefixed identity bytes used as AEAD associated
  data.
- `core/sync/src/main/kotlin/app/opentasks/core/sync/CloudFormatFailure.kt`
  — typed format failure taxonomy shared by framing and the authenticated
  codec.
- `core/sync/src/main/kotlin/app/opentasks/core/sync/CloudObjectFormat.kt`
  — bounded canonical framing, checksum verification, and identity-to-header
  construction.
- `core/crypto/src/main/kotlin/app/opentasks/core/crypto/VaultCrypto.kt`
  — provider-neutral AEAD byte boundary plus the existing record-context
  convenience methods.
- `core/crypto/src/main/kotlin/app/opentasks/core/crypto/TinkVaultCrypto.kt`
  — Tink implementation of the byte AEAD boundary.
- `core/data/src/main/kotlin/app/opentasks/core/data/AuthenticatedCloudObjectCodec.kt`
  — composition of framing, identity associated data, AEAD, typed decode
  results, and byte ownership.
- `core/data/src/test/kotlin/app/opentasks/core/data/AuthenticatedCloudObjectCodecTest.kt`
  — behavioural and failure-order tests against real Tink.
- `core/data/src/test/kotlin/app/opentasks/core/data/AuthenticatedCloudObjectGoldenTest.kt`
  — independent deterministic vectors for all four object families.
- `scripts/generate-authenticated-cloud-v1-fixtures.mjs` — reviewable,
  provider-independent fixture generator using only Node.js built-ins.
- `core/data/src/test/resources/cloud-format/v1-authenticated/*.json` —
  immutable reviewed vectors; production code never reads them.

### Task 1: Ratify the Local-Authority Programme in Every Active Contract

**Files:**

- Modify:
  `docs/superpowers/specs/2026-07-28-local-authority-cloud-attachments-backup-design.md`
- Modify: `HANDOFF.md`
- Modify: `README.md`
- Modify: `PRODUCT.md`
- Modify: `DESIGN.md`
- Modify: `docs/architecture.md`
- Modify: `docs/threat-model.md`
- Modify:
  `docs/superpowers/specs/2026-07-27-open-tasks-production-programme-design.md`
- Modify:
  `docs/superpowers/plans/2026-07-27-open-tasks-production-master-plan.md`
- Modify:
  `docs/superpowers/plans/2026-07-27-train-1-insights-cloud-format-plan.md`
- Modify:
  `docs/superpowers/specs/2026-07-27-train-1-insights-cloud-format-design.md`
- Modify:
  `docs/superpowers/plans/2026-07-27-train-2-drive-sync-plan.md`
- Modify:
  `docs/superpowers/specs/2026-07-27-train-2-drive-sync-design.md`
- Modify:
  `docs/superpowers/plans/2026-07-27-train-3-migration-recovery-plan.md`
- Modify:
  `docs/superpowers/specs/2026-07-27-train-3-migration-recovery-design.md`
- Modify:
  `docs/superpowers/plans/2026-07-27-train-4-notes-attachments-search-plan.md`
- Modify:
  `docs/superpowers/specs/2026-07-27-train-4-notes-attachments-search-design.md`
- Modify:
  `docs/superpowers/plans/2026-07-27-train-5-platform-features-plan.md`
- Modify:
  `docs/superpowers/specs/2026-07-27-train-5-platform-features-design.md`
- Modify:
  `docs/superpowers/plans/2026-07-27-train-6-production-qualification-rollout-plan.md`
- Modify:
  `docs/superpowers/specs/2026-07-27-train-6-production-qualification-rollout-design.md`

**Interfaces:**

- Consumes: the approved decision record at
  `docs/superpowers/specs/2026-07-28-local-authority-cloud-attachments-backup-design.md`.
- Produces: one live backlog in `HANDOFF.md`, one six-stage programme in the
  existing master-plan path, and explicit historical markers on every
  superseded train document.

- [ ] **Step 1: Prove the active contracts still express the old direction**

Run:

```bash
rg -n \
  'Drive-primary|DRIVE_PRIMARY|multi-device sync|SyncCoordinator|sync health|keepOffline' \
  HANDOFF.md README.md PRODUCT.md DESIGN.md docs/architecture.md \
  docs/threat-model.md \
  docs/superpowers/plans/2026-07-27-open-tasks-production-master-plan.md \
  docs/superpowers/specs/2026-07-27-open-tasks-production-programme-design.md
```

Expected: matches in the live handoff, product, architecture, threat model, or
old programme prove that the direction reset is not yet recorded.

- [ ] **Step 2: Make the approved design record final**

Keep its status exactly:

```markdown
**Status:** Approved
```

Do not change the approved decisions while performing the contract rewrite.

- [ ] **Step 3: Rewrite the active product and architecture contracts**

Apply these exact responsibilities:

- `README.md`: describe Room as the sole live authority; state that encrypted
  app backup, supplementary Android Auto Backup, recovery takeover, and
  cloud-only attachment bytes are approved but not yet shipped; describe
  `core:sync` as bounded object formats and legacy merge primitives rather than
  a product sync module.
- `PRODUCT.md`: replace future Drive-primary and multi-device promises with
  local structured data, app-managed encrypted backup, supplementary Android
  backup, cloud-authoritative attachment bytes, and one active writer per
  backed-up vault.
- `DESIGN.md`: remove the planned sync-health surface. Define the future
  **Backup & recovery** sections and state that Home may show backup attention
  only after backup is configured and blocked or overdue.
- `docs/architecture.md`: remove runtime authority modes; document Room,
  `BackupJournal`, `BackupCoordinator`, `PortableBackupPublisher`,
  `AttachmentBlobCoordinator`, and `RecoveryCoordinator`; make clear that only
  recovery may reconstruct Room from backup data.
- `docs/threat-model.md`: retain the current fact that Android backup is
  disabled, then describe the Stage 2 portable-package mitigation as planned;
  replace multi-device convergence threats with backup corruption,
  stale-writer takeover, blob retention, and portable-package exclusion
  threats.

The current-state wording must distinguish implemented facts from approved
future work. In particular, do not claim that backup, Android Auto Backup,
Drive transport, recovery UI, or attachments are operational.

- [ ] **Step 4: Replace the live handoff and programme map**

`HANDOFF.md` must:

- record the approved direction and the two design commits;
- retain all completed Train 0 and Train 1 Tasks 1.1–1.5 evidence;
- state that no Stage 1 source change has started at this checkpoint;
- replace the Drive-primary backlog with the six dependency-ordered stages;
- name Task 2 of this plan as the first source change after the contract reset;
- retain the protected-workspace and sole-disposable-emulator rules; and
- retain GitHub maintenance as paused.

Rewrite the programme map in
`docs/superpowers/plans/2026-07-27-open-tasks-production-master-plan.md`
to:

```markdown
| Order | Stage | Exit decision |
|---:|---|---|
| 1 | Direction reset and authenticated object foundation | Active contracts match local authority; the authenticated provider-independent object codec is frozen |
| 2 | Local backup and Android Auto Backup | Local generations produce verified primary snapshots and one strictly whitelisted portable package |
| 3 | App-managed backup and recovery takeover | Drive backup, retention, recovery, writer epochs, and stale-writer rejection are proven |
| 4 | Notes, activity, cloud attachments, and search | Cloud-authoritative blob lifecycle and final structured metadata are complete |
| 5 | Remaining platform features | Import/export, widget, app lock, input, and calendar features use the final local schema |
| 6 | Production qualification and rollout | Backup, attachment, takeover, recovery, accessibility, performance, privacy, and release gates pass |
```

The programme dependency chain is:

```text
Stage 1 → Stage 2 → Stage 3 → Stage 4 → Stage 5 → Stage 6
```

Rewrite
`docs/superpowers/specs/2026-07-27-open-tasks-production-programme-design.md`
as the narrative programme contract for the same six stages. Its architecture
must use the approved Room, backup, portable-package, blob, and recovery
boundaries; its product surfaces and acceptance matrix must use backup and
active-device terminology. Preserve still-valid privacy, accessibility,
platform, and rollout decisions.

- [ ] **Step 5: Mark historical train documents without erasing evidence**

Keep completed evidence, but add an immediately visible banner after each
historical title.

For the Train 1 plan and design:

```markdown
> **Direction update — 28 July 2026:** Tasks 1.1–1.5 remain accepted
> historical evidence. The unstarted Task 1.6 is replaced by the Stage 1
> local-authority foundation plan and must not be executed from this file.
```

For Train 2, Train 3, and Train 4:

```markdown
> **Superseded — 28 July 2026:** Do not execute this train. The approved
> local-authority, backup, recovery-takeover, and cloud-attachment direction is
> defined in the 28 July design and the live production master plan.
```

For Train 5:

```markdown
> **Replanning required — 28 July 2026:** The feature intent remains, but this
> train cannot execute until Stage 4 freezes the final local metadata and
> cloud-attachment contracts. Follow the live production master plan.
```

For Train 6:

```markdown
> **Replanning required — 28 July 2026:** Keep non-cloud qualification intent,
> but replace every sync/convergence gate with the approved backup, takeover,
> stale-writer, Android Auto Backup, attachment, and recovery matrices before
> execution.
```

- [ ] **Step 6: Verify terminology, current-state honesty, and historical banners**

Run:

```bash
rg -n \
  'Drive-primary|DRIVE_PRIMARY|multi-device sync|SyncCoordinator|sync health|keepOffline' \
  HANDOFF.md README.md PRODUCT.md DESIGN.md docs/architecture.md \
  docs/threat-model.md \
  docs/superpowers/plans/2026-07-27-open-tasks-production-master-plan.md \
  docs/superpowers/specs/2026-07-27-open-tasks-production-programme-design.md
```

Expected: no active promise of those concepts. Matches are permitted only
inside explicit statements that they are removed, superseded, or not a product
contract.

Run:

```bash
for file in \
  docs/superpowers/{plans,specs}/2026-07-27-train-{1-insights-cloud-format,2-drive-sync,3-migration-recovery,4-notes-attachments-search,5-platform-features,6-production-qualification-rollout}*.md
do
  head -8 "$file"
done
```

Expected: every historical train begins with the correct direction-update,
superseded, or replanning banner.

Run:

```bash
git diff --check
```

Expected: exit `0` with no output.

- [ ] **Step 7: Commit the direction reset**

```bash
git add HANDOFF.md README.md PRODUCT.md DESIGN.md docs/architecture.md \
  docs/threat-model.md docs/superpowers/plans docs/superpowers/specs
git commit -m "docs: reset programme to local data authority"
```

### Task 2: Clear the Insights Instrumented-Test Lint Gate

**Files:**

- Modify:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt:6-10`
- Modify:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt:220-574`

**Interfaces:**

- Consumes: Compose runtime `remember` and the existing stateless
  `InsightsScreen` test harness.
- Produces: the same five mutable test states scoped correctly to their
  compositions, with no product-code or assertion change.

- [ ] **Step 1: Reproduce the five recorded lint failures**

Run:

```bash
./gradlew :feature:more:lintDebug --stacktrace
```

Expected: FAIL with five `UnrememberedMutableState` findings in
`InsightsScreenInstrumentedTest.kt`. If the exact failure count differs, stop
and record the actual findings before editing.

- [ ] **Step 2: Remember only state created inside compositions**

Add:

```kotlin
import androidx.compose.runtime.remember
```

Change the five declarations inside `setContent` blocks to this form:

```kotlin
var state by remember {
    mutableStateOf(populatedState().copy(snapshot = emptySnapshot()))
}

var includeConflicted by remember { mutableStateOf(false) }
var presentation by remember { mutableStateOf(InsightsPresentation.CHART) }
var state by remember { mutableStateOf(populatedState()) }
```

There are two separate `presentation` declarations and both use the shown
remembered form. Leave the `openInsights` state created outside composition
unchanged.

- [ ] **Step 3: Re-run the focused lint gate**

Run:

```bash
./gradlew :feature:more:lintDebug --stacktrace
```

Expected: PASS with no `UnrememberedMutableState` finding.

- [ ] **Step 4: Verify the source change is limited to state lifetime**

Run:

```bash
git diff -- \
  feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt
git diff --check
```

Expected: one import and five `remember` wrappers; no changed assertions,
semantics queries, product source, or whitespace errors.

- [ ] **Step 5: Commit the lint correction**

```bash
git add \
  feature/more/src/androidTest/kotlin/app/opentasks/feature/more/InsightsScreenInstrumentedTest.kt
git commit -m "test: remember Insights composition state"
```

### Task 3: Canonicalise Cloud Identity and Type Frame Failures

**Files:**

- Create:
  `core/sync/src/main/kotlin/app/opentasks/core/sync/CloudHeaderIdentityEncoding.kt`
- Create:
  `core/sync/src/main/kotlin/app/opentasks/core/sync/CloudFormatFailure.kt`
- Modify:
  `core/sync/src/main/kotlin/app/opentasks/core/sync/CloudObjectFormat.kt`
- Modify:
  `core/sync/src/main/kotlin/app/opentasks/core/sync/CloudPayloads.kt`
- Create:
  `core/sync/src/test/kotlin/app/opentasks/core/sync/CloudHeaderIdentityEncodingTest.kt`
- Modify:
  `core/sync/src/test/kotlin/app/opentasks/core/sync/CloudObjectFormatTest.kt`

**Interfaces:**

- Consumes:
  `CloudHeaderIdentity(family, schemaVersion, cryptoVersion,
  minimumReaderVersion, vaultId, objectId, chunkIndex, chunkCount)`.
- Produces:

```kotlin
object CloudHeaderIdentityEncoding {
    fun associatedData(identity: CloudHeaderIdentity): ByteArray
}

enum class CloudFormatFailure {
    MALFORMED,
    UNSUPPORTED_FORMAT,
    LIMIT_EXCEEDED,
    LENGTH_MISMATCH,
    CHECKSUM_MISMATCH,
    TRUNCATED,
}

class CloudFormatException(
    val failure: CloudFormatFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

interface CloudObjectFrameCodec {
    fun encode(header: CloudObjectHeader, ciphertext: ByteArray): ByteArray
    fun encode(identity: CloudHeaderIdentity, ciphertext: ByteArray): ByteArray
    fun decode(source: InputStream, totalLength: Long): CloudObjectFrame
}
```

Later tasks depend on those exact names and enum values.

- [ ] **Step 1: Write failing identity-encoding tests**

Create `CloudHeaderIdentityEncodingTest.kt` with:

```kotlin
class CloudHeaderIdentityEncodingTest {
    @Test
    fun manifestIdentityMatchesIndependentGoldenBytes() {
        val identity = CloudHeaderIdentity(
            family = CloudObjectFamily.MANIFEST,
            schemaVersion = 1,
            cryptoVersion = 1,
            minimumReaderVersion = 1,
            vaultId = "vault-alpha",
            objectId = "manifest-0001",
        )

        assertEquals(
            "000000236f70656e2d7461736b733a636c6f75642d6865616465722d6964656e" +
                "746974793a7631000000084d414e494645535400000001310000000131000000" +
                "01310000000b7661756c742d616c7068610000000d6d616e69666573742d3030" +
                "30310000000000000000",
            CloudHeaderIdentityEncoding.associatedData(identity).toHex(),
        )
    }

    @Test
    fun everyValidIdentityMutationChangesAssociatedData() {
        val base = manifestIdentity()
        val mutations = listOf(
            base.copy(family = CloudObjectFamily.SNAPSHOT),
            base.copy(vaultId = "vault-beta"),
            base.copy(objectId = "manifest-0002"),
        )
        val expected = CloudHeaderIdentityEncoding.associatedData(base)

        mutations.forEach { mutation ->
            assertFalse(
                expected.contentEquals(
                    CloudHeaderIdentityEncoding.associatedData(mutation),
                ),
            )
        }

        val chunk = attachmentIdentity()
        val chunkMutations = listOf(
            chunk.copy(chunkIndex = 1),
            chunk.copy(chunkCount = 25),
        )
        val expectedChunk =
            CloudHeaderIdentityEncoding.associatedData(chunk)
        chunkMutations.forEach { mutation ->
            assertFalse(
                expectedChunk.contentEquals(
                    CloudHeaderIdentityEncoding.associatedData(mutation),
                ),
            )
        }
    }
}
```

Also test strict rejection of blank IDs, unpaired surrogates, unsupported
versions, missing/invalid attachment tuples, and chunk fields on non-attachment
families. The hard-coded manifest golden proves that schema, crypto, and
minimum-reader versions each occupy a distinct length-prefixed field even
though v1 is the only supported value.

- [ ] **Step 2: Write failing typed-frame tests**

Extend `CloudObjectFormatTest.kt` so checksum, version, bound, length, and
truncation cases assert the exact failure:

```kotlin
val failure = assertThrows(CloudFormatException::class.java) {
    CloudObjectFormat.decode(
        ByteArrayInputStream(tampered),
        tampered.size.toLong(),
    )
}
assertEquals(CloudFormatFailure.CHECKSUM_MISMATCH, failure.failure)
```

Add a test for `encode(identity, ciphertext)`:

```kotlin
val frame = CloudObjectFormat.encode(identity, ciphertext)
val decoded = CloudObjectFormat.decode(
    ByteArrayInputStream(frame),
    frame.size.toLong(),
)

assertEquals(identity, decoded.header.identity)
assertArrayEquals(ciphertext, decoded.takeCiphertext())
```

- [ ] **Step 3: Run the focused tests to verify they fail**

Run:

```bash
./gradlew :core:sync:testDebugUnitTest \
  --tests '*CloudHeaderIdentityEncodingTest' \
  --tests '*CloudObjectFormatTest' \
  --stacktrace
```

Expected: compilation failure because the new encoding, failure types, and
identity overload do not exist.

- [ ] **Step 4: Implement strict length-prefixed identity encoding**

Encode these nine UTF-8 fields in this exact order, each preceded by one
four-byte unsigned big-endian byte length:

```text
open-tasks:cloud-header-identity:v1
family.name
schemaVersion as base-10 ASCII
cryptoVersion as base-10 ASCII
minimumReaderVersion as base-10 ASCII
vaultId
objectId
chunkIndex as base-10 ASCII, or empty for null
chunkCount as base-10 ASCII, or empty for null
```

Use a reporting UTF-8 encoder, not `String.toByteArray`, for user-supplied
identifiers. Centralise identity validation in the same file and call it from
both `associatedData` and `CloudObjectFormat`.

The implementation shape is:

```kotlin
object CloudHeaderIdentityEncoding {
    private const val DOMAIN = "open-tasks:cloud-header-identity:v1"

    fun associatedData(identity: CloudHeaderIdentity): ByteArray {
        validateCloudHeaderIdentity(identity)
        val fields = listOf(
            DOMAIN,
            identity.family.name,
            identity.schemaVersion.toString(),
            identity.cryptoVersion.toString(),
            identity.minimumReaderVersion.toString(),
            identity.vaultId,
            identity.objectId,
            identity.chunkIndex?.toString().orEmpty(),
            identity.chunkCount?.toString().orEmpty(),
        ).map(::strictUtf8)
        val size = fields.sumOf { Integer.BYTES + it.size }
        return ByteBuffer.allocate(size).also { target ->
            fields.forEach { bytes ->
                target.putInt(bytes.size)
                target.put(bytes)
            }
        }.array()
    }
}
```

Validation accepts only v1 schema/crypto/minimum-reader values, non-blank
strict UTF-8 IDs, a complete `0 <= chunkIndex < chunkCount <= 26` tuple for
attachments, and two null chunk fields for every other family.

- [ ] **Step 5: Implement typed frame failures and identity framing**

Replace untyped decode-path `require` failures with
`CloudFormatException`. Preserve their safe messages, but callers branch only
on `failure`.

Map conditions exactly:

- malformed prefix, UTF-8, JSON, canonical order, identifiers, or chunk tuple
  → `MALFORMED`;
- magic, schema, crypto, or minimum-reader incompatibility
  → `UNSUPPORTED_FORMAT`;
- header or family ciphertext bound
  → `LIMIT_EXCEEDED`;
- declared total or ciphertext length mismatch/overflow
  → `LENGTH_MISMATCH`;
- SHA-256 mismatch → `CHECKSUM_MISMATCH`;
- early end of prefix, header, or ciphertext → `TRUNCATED`.

Implement `encode(identity, ciphertext)` by deriving `ciphertextLength` and
lowercase SHA-256 internally, then delegating to the existing header overload.
Call identity validation before framing.

- [ ] **Step 6: Run the complete sync unit suite**

Run:

```bash
./gradlew :core:sync:testDebugUnitTest --stacktrace
```

Expected: PASS. Existing canonical v1 frame resources remain byte-identical,
and all existing ownership/boundary tests still pass.

- [ ] **Step 7: Verify no framing fixture changed unintentionally**

Run:

```bash
git diff -- \
  core/sync/src/test/resources/cloud-format/v1
git diff --check
```

Expected: no diff under the existing `v1` resource directory and no whitespace
errors.

- [ ] **Step 8: Commit the identity and frame contract**

```bash
git add core/sync
git commit -m "feat: type cloud frame identity failures"
```

### Task 4: Expose the Generic AEAD Boundary in `core:crypto`

**Files:**

- Modify:
  `core/crypto/src/main/kotlin/app/opentasks/core/crypto/VaultCrypto.kt:8-71`
- Modify:
  `core/crypto/src/main/kotlin/app/opentasks/core/crypto/TinkVaultCrypto.kt:77-92`
- Modify:
  `core/crypto/src/test/kotlin/app/opentasks/core/crypto/TinkVaultCryptoTest.kt`

**Interfaces:**

- Consumes: `VaultKey`, caller-owned plaintext/ciphertext, and caller-owned
  associated-data arrays.
- Produces these exact methods while preserving `encryptRecord` and
  `decryptRecord`:

```kotlin
interface VaultCrypto {
    fun encryptBytes(
        key: VaultKey,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    fun decryptBytes(
        key: VaultKey,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray
}
```

`encryptRecord` and `decryptRecord` become default convenience methods that
derive and clear `CryptoContext.associatedData()` around those byte methods.

- [ ] **Step 1: Write failing generic-AEAD tests**

Add:

```kotlin
@Test
fun genericAeadRoundTripsWithCallerAssociatedData() {
    val key = crypto.createKey()
    val associatedData = "independent-context".toByteArray()
    val plaintext = "private payload".toByteArray()

    val ciphertext = crypto.encryptBytes(key, plaintext, associatedData)
    val decoded = crypto.decryptBytes(key, ciphertext, associatedData)

    assertArrayEquals(plaintext, decoded)
    plaintext.fill(0)
    decoded.fill(0)
    associatedData.fill(0)
    ciphertext.fill(0)
    key.close()
}

@Test
fun genericAeadRejectsChangedAssociatedData() {
    val key = crypto.createKey()
    val ciphertext = crypto.encryptBytes(
        key,
        "private payload".toByteArray(),
        "context-a".toByteArray(),
    )

    assertThrows(GeneralSecurityException::class.java) {
        crypto.decryptBytes(
            key,
            ciphertext,
            "context-b".toByteArray(),
        )
    }
    ciphertext.fill(0)
    key.close()
}
```

Add assertions that `encryptBytes` does not modify caller plaintext or
associated data and that a closed `VaultKey` fails closed.

- [ ] **Step 2: Run the crypto tests to verify compilation fails**

Run:

```bash
./gradlew :core:crypto:testDebugUnitTest \
  --tests '*TinkVaultCryptoTest' \
  --stacktrace
```

Expected: compilation failure because `encryptBytes` and `decryptBytes` do not
exist.

- [ ] **Step 3: Add the byte AEAD methods and preserve record behaviour**

Implement in `VaultCrypto`:

```kotlin
fun encryptRecord(
    key: VaultKey,
    context: CryptoContext,
    plaintext: ByteArray,
): ByteArray {
    val associatedData = context.associatedData()
    return try {
        encryptBytes(key, plaintext, associatedData)
    } finally {
        associatedData.fill(0)
    }
}

fun decryptRecord(
    key: VaultKey,
    context: CryptoContext,
    ciphertext: ByteArray,
): ByteArray {
    val associatedData = context.associatedData()
    return try {
        decryptBytes(key, ciphertext, associatedData)
    } finally {
        associatedData.fill(0)
    }
}
```

Implement in `TinkVaultCrypto`:

```kotlin
override fun encryptBytes(
    key: VaultKey,
    plaintext: ByteArray,
    associatedData: ByteArray,
): ByteArray = primitive(key).encrypt(plaintext, associatedData)

override fun decryptBytes(
    key: VaultKey,
    ciphertext: ByteArray,
    associatedData: ByteArray,
): ByteArray = primitive(key).decrypt(ciphertext, associatedData)
```

Remove its direct `encryptRecord` and `decryptRecord` overrides so the
interface defaults exercise the new methods. Update the test-only
`TrackingVaultCrypto` implementation for the new abstract methods. Do not
expose serialized keysets or Tink primitives outside `core:crypto`.

- [ ] **Step 4: Run the complete crypto unit suite**

Run:

```bash
./gradlew :core:crypto:testDebugUnitTest --stacktrace
```

Expected: PASS, including existing recovery-envelope, Argon2id, key-erasure,
record-associated-data, wrong-passphrase, and tamper tests.

- [ ] **Step 5: Commit the AEAD boundary**

```bash
git add core/crypto
git commit -m "feat: expose associated-data AEAD boundary"
```

### Task 5: Compose Framing and AEAD into the Authenticated Codec

**Files:**

- Modify: `core/data/build.gradle.kts`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/AuthenticatedCloudObjectCodec.kt`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/AuthenticatedCloudObjectCodecTest.kt`

**Interfaces:**

- Consumes: `CloudObjectFrameCodec`,
  `CloudHeaderIdentityEncoding.associatedData`, `VaultCrypto.encryptBytes`,
  `VaultCrypto.decryptBytes`, and `VaultKey`.
- Produces:

```kotlin
interface AuthenticatedCloudObjectCodec {
    fun encrypt(
        identity: CloudHeaderIdentity,
        plaintext: ByteArray,
        key: VaultKey,
    ): ByteArray

    fun decrypt(
        source: InputStream,
        totalLength: Long,
        key: VaultKey,
    ): CloudDecodeResult
}

enum class CloudDecodeFailure {
    MALFORMED_FRAME,
    UNSUPPORTED_FORMAT,
    LIMIT_EXCEEDED,
    LENGTH_MISMATCH,
    CHECKSUM_MISMATCH,
    TRUNCATED,
    AUTHENTICATION_FAILED,
}

sealed interface CloudDecodeResult {
    data class Success(
        val value: DecryptedCloudObject,
    ) : CloudDecodeResult

    data class Failure(
        val reason: CloudDecodeFailure,
    ) : CloudDecodeResult
}

class DecryptedCloudObject internal constructor(
    val identity: CloudHeaderIdentity,
    plaintext: ByteArray,
) : AutoCloseable {
    fun copyPlaintext(): ByteArray
    fun takePlaintext(): ByteArray
    override fun close()
}

class DefaultAuthenticatedCloudObjectCodec(
    private val crypto: VaultCrypto,
    private val frameCodec: CloudObjectFrameCodec = CloudObjectFormat,
) : AuthenticatedCloudObjectCodec
```

Later stages depend on those exact public names. `DecryptedCloudObject`
retains one owned array, returns defensive copies before transfer, transfers
the exact array once, rejects use after transfer, and zeroes retained plaintext
on `close`.

- [ ] **Step 1: Write failing round-trip and ownership tests**

Create `AuthenticatedCloudObjectCodecTest.kt` with real
`TinkVaultCrypto`:

```kotlin
class AuthenticatedCloudObjectCodecTest {
    private val crypto = TinkVaultCrypto()
    private val codec = DefaultAuthenticatedCloudObjectCodec(crypto)

    @Test
    fun everyObjectFamilyRoundTripsThroughAuthenticatedFrame() {
        val key = crypto.createKey()
        try {
            identities().forEach { identity ->
                val plaintext = "payload:${identity.family}".toByteArray()
                val frame = codec.encrypt(identity, plaintext, key)

                val result = codec.decrypt(
                    ByteArrayInputStream(frame),
                    frame.size.toLong(),
                    key,
                )

                val decoded = (result as CloudDecodeResult.Success).value
                assertEquals(identity, decoded.identity)
                val taken = decoded.takePlaintext()
                assertArrayEquals(plaintext, taken)
                plaintext.fill(0)
                taken.fill(0)
                frame.fill(0)
            }
        } finally {
            key.close()
        }
    }

    @Test
    fun encryptingSamePlaintextTwiceUsesDifferentCiphertext() {
        val key = crypto.createKey()
        val identity = manifestIdentity()
        val plaintext = "same payload".toByteArray()

        val first = codec.encrypt(identity, plaintext, key)
        val second = codec.encrypt(identity, plaintext, key)

        assertFalse(first.contentEquals(second))
        plaintext.fill(0)
        first.fill(0)
        second.fill(0)
        key.close()
    }
}
```

Add focused tests for defensive `copyPlaintext`, one-shot
`takePlaintext`, close-before-transfer zeroisation, caller plaintext
preservation, and a closed key.

- [ ] **Step 2: Write failing authentication and ordering tests**

Add tests that:

- reframe valid ciphertext under a different valid family, vault, object,
  chunk index, or chunk count and receive `AUTHENTICATION_FAILED`;
- reframe it with an incompatible version and receive
  `UNSUPPORTED_FORMAT` before AEAD;
- decrypt with an independent vault key and receive
  `AUTHENTICATION_FAILED`;
- flip ciphertext without repairing the checksum and receive
  `CHECKSUM_MISMATCH`, proving the checksum runs first;
- truncate each frame region and receive `TRUNCATED`;
- declare a future reader/version and receive `UNSUPPORTED_FORMAT`;
- declare an oversized family payload and receive `LIMIT_EXCEEDED`; and
- declare a mismatched total length and receive `LENGTH_MISMATCH`.

Use this assertion helper:

```kotlin
private fun assertFailure(
    expected: CloudDecodeFailure,
    result: CloudDecodeResult,
) {
    assertEquals(expected, (result as CloudDecodeResult.Failure).reason)
}
```

- [ ] **Step 3: Run the focused test to verify it fails**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*AuthenticatedCloudObjectCodecTest' \
  --stacktrace
```

Expected: compilation failure because `core:data` does not depend on
`core:crypto` and the authenticated codec types do not exist.

- [ ] **Step 4: Add the dependency and implement owned plaintext**

Add:

```kotlin
implementation(project(":core:crypto"))
```

Implement `DecryptedCloudObject` with the same ownership pattern as
`CloudObjectFrame`, plus zeroisation:

```kotlin
class DecryptedCloudObject internal constructor(
    val identity: CloudHeaderIdentity,
    plaintext: ByteArray,
) : AutoCloseable {
    private var plaintextBytes: ByteArray? = plaintext

    fun copyPlaintext(): ByteArray = synchronized(this) {
        checkNotNull(plaintextBytes) {
            "Plaintext ownership has already ended"
        }.copyOf()
    }

    fun takePlaintext(): ByteArray = synchronized(this) {
        checkNotNull(plaintextBytes) {
            "Plaintext ownership has already ended"
        }.also {
            plaintextBytes = null
        }
    }

    override fun close() = synchronized(this) {
        plaintextBytes?.fill(0)
        plaintextBytes = null
    }
}
```

- [ ] **Step 5: Implement encryption with explicit ownership cleanup**

Use:

```kotlin
override fun encrypt(
    identity: CloudHeaderIdentity,
    plaintext: ByteArray,
    key: VaultKey,
): ByteArray {
    val associatedData =
        CloudHeaderIdentityEncoding.associatedData(identity)
    val ciphertext = try {
        crypto.encryptBytes(key, plaintext, associatedData)
    } finally {
        associatedData.fill(0)
    }
    return try {
        frameCodec.encode(identity, ciphertext)
    } finally {
        ciphertext.fill(0)
    }
}
```

Identity validation therefore completes before AEAD encryption. The codec does
not clear caller plaintext.

- [ ] **Step 6: Implement checksum-before-decrypt and typed failure mapping**

Decode the frame first, map `CloudFormatException.failure` exactly, then
transfer and clear the verified ciphertext around AEAD:

```kotlin
override fun decrypt(
    source: InputStream,
    totalLength: Long,
    key: VaultKey,
): CloudDecodeResult {
    val frame = try {
        frameCodec.decode(source, totalLength)
    } catch (failure: CloudFormatException) {
        return CloudDecodeResult.Failure(failure.failure.toDecodeFailure())
    }
    val ciphertext = frame.takeCiphertext()
    val associatedData =
        CloudHeaderIdentityEncoding.associatedData(frame.header.identity)
    return try {
        CloudDecodeResult.Success(
            DecryptedCloudObject(
                identity = frame.header.identity,
                plaintext = crypto.decryptBytes(
                    key,
                    ciphertext,
                    associatedData,
                ),
            ),
        )
    } catch (_: GeneralSecurityException) {
        CloudDecodeResult.Failure(
            CloudDecodeFailure.AUTHENTICATION_FAILED,
        )
    } finally {
        associatedData.fill(0)
        ciphertext.fill(0)
    }
}
```

The private mapping is:

```kotlin
private fun CloudFormatFailure.toDecodeFailure(): CloudDecodeFailure =
    when (this) {
        CloudFormatFailure.MALFORMED ->
            CloudDecodeFailure.MALFORMED_FRAME
        CloudFormatFailure.UNSUPPORTED_FORMAT ->
            CloudDecodeFailure.UNSUPPORTED_FORMAT
        CloudFormatFailure.LIMIT_EXCEEDED ->
            CloudDecodeFailure.LIMIT_EXCEEDED
        CloudFormatFailure.LENGTH_MISMATCH ->
            CloudDecodeFailure.LENGTH_MISMATCH
        CloudFormatFailure.CHECKSUM_MISMATCH ->
            CloudDecodeFailure.CHECKSUM_MISMATCH
        CloudFormatFailure.TRUNCATED ->
            CloudDecodeFailure.TRUNCATED
    }
```

Do not catch `IllegalStateException` from a closed key; that is a local
programming/lifecycle fault rather than an untrusted-object result.

- [ ] **Step 7: Run all three affected module suites**

Run:

```bash
./gradlew :core:sync:testDebugUnitTest \
  :core:crypto:testDebugUnitTest \
  :core:data:testDebugUnitTest \
  --stacktrace
```

Expected: PASS. No Android device or provider credential is required.

- [ ] **Step 8: Scan the codec boundary**

Run:

```bash
rg -n 'println|android\\.util\\.Log|java\\.util\\.logging' \
  core/data/src/main/kotlin/app/opentasks/core/data/AuthenticatedCloudObjectCodec.kt \
  core/crypto/src/main/kotlin/app/opentasks/core/crypto/VaultCrypto.kt \
  core/crypto/src/main/kotlin/app/opentasks/core/crypto/TinkVaultCrypto.kt \
  core/sync/src/main/kotlin/app/opentasks/core/sync/Cloud*.kt
git diff --check
```

Expected: no logging match and no whitespace errors.

- [ ] **Step 9: Commit the authenticated codec**

```bash
git add core/data core/crypto core/sync
git commit -m "feat: add authenticated cloud object codec"
```

### Task 6: Freeze Independent Authenticated v1 Golden Vectors

**Files:**

- Create: `scripts/generate-authenticated-cloud-v1-fixtures.mjs`
- Create:
  `core/data/src/test/resources/cloud-format/v1-authenticated/manifest.json`
- Create:
  `core/data/src/test/resources/cloud-format/v1-authenticated/snapshot.json`
- Create:
  `core/data/src/test/resources/cloud-format/v1-authenticated/operation-segment.json`
- Create:
  `core/data/src/test/resources/cloud-format/v1-authenticated/attachment-chunk.json`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/AuthenticatedCloudObjectGoldenTest.kt`

**Interfaces:**

- Consumes: the public Stage 1 codec interface and fixed v1 identity/framing
  contract.
- Produces: four deterministic vectors generated outside Kotlin/Tink and a
  test-only `FixtureVaultCrypto` that verifies the codec against them.

Each JSON fixture has exactly:

```json
{
  "family": "MANIFEST",
  "schemaVersion": 1,
  "cryptoVersion": 1,
  "minimumReaderVersion": 1,
  "vaultId": "vault-alpha",
  "objectId": "manifest-0001",
  "chunkIndex": null,
  "chunkCount": null,
  "keyHex": "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
  "nonceHex": "101112131415161718191a1b",
  "associatedDataHex": "000000236f70656e2d7461736b733a636c6f75642d6865616465722d6964656e746974793a7631000000084d414e49464553540000000131000000013100000001310000000b7661756c742d616c7068610000000d6d616e69666573742d303030310000000000000000",
  "plaintextHex": "6d616e69666573742d7631",
  "ciphertextHex": "010000002a101112131415161718191a1b109ff67f2fac49c7e70339403b1d0caf6817f5de03bc47e3c427c6",
  "headerJson": "{\"magic\":\"OPEN_TASKS\",\"family\":\"MANIFEST\",\"schemaVersion\":1,\"cryptoVersion\":1,\"minimumReaderVersion\":1,\"vaultId\":\"vault-alpha\",\"objectId\":\"manifest-0001\",\"ciphertextLength\":44,\"ciphertextSha256\":\"df4556cec1a9d247b7916c58e4e341f8dc1738963482619e57d270131001159f\",\"chunkIndex\":null,\"chunkCount\":null}",
  "frameHex": "0000012a7b226d61676963223a224f50454e5f5441534b53222c2266616d696c79223a224d414e4946455354222c22736368656d6156657273696f6e223a312c2263727970746f56657273696f6e223a312c226d696e696d756d52656164657256657273696f6e223a312c227661756c744964223a227661756c742d616c706861222c226f626a6563744964223a226d616e69666573742d30303031222c22636970686572746578744c656e677468223a34342c2263697068657274657874536861323536223a2264663435353663656331613964323437623739313663353865346533343166386463313733383936333438323631396535376432373031333130303131353966222c226368756e6b496e646578223a6e756c6c2c226368756e6b436f756e74223a6e756c6c7d010000002a101112131415161718191a1b109ff67f2fac49c7e70339403b1d0caf6817f5de03bc47e3c427c6"
}
```

The other three resources use the exact identities, nonces, and plaintexts
listed in Step 1 and the same calculated field set.

- [ ] **Step 1: Create the independent deterministic generator**

Use Node built-ins only:

```javascript
import {
  createCipheriv,
  createHash,
} from "node:crypto";
import {
  mkdirSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";

const key = Buffer.from(
  "000102030405060708090a0b0c0d0e0f" +
    "101112131415161718191a1b1c1d1e1f",
  "hex",
);
const tinkPrefix = Buffer.from("010000002a", "hex");

function lengthPrefix(value) {
  const bytes = Buffer.from(value, "utf8");
  const length = Buffer.alloc(4);
  length.writeUInt32BE(bytes.length);
  return Buffer.concat([length, bytes]);
}

function associatedData(testCase) {
  return Buffer.concat([
    "open-tasks:cloud-header-identity:v1",
    testCase.family,
    "1",
    "1",
    "1",
    "vault-alpha",
    testCase.objectId,
    testCase.chunkIndex === null ? "" : String(testCase.chunkIndex),
    testCase.chunkCount === null ? "" : String(testCase.chunkCount),
  ].map(lengthPrefix));
}

function ciphertext(testCase, aad) {
  const cipher = createCipheriv("aes-256-gcm", key, testCase.nonce);
  cipher.setAAD(aad);
  const encrypted = Buffer.concat([
    cipher.update(testCase.plaintext),
    cipher.final(),
  ]);
  return Buffer.concat([
    tinkPrefix,
    testCase.nonce,
    encrypted,
    cipher.getAuthTag(),
  ]);
}
```

Define four cases with these exact identities:

```javascript
const cases = [
  {
    name: "manifest",
    family: "MANIFEST",
    objectId: "manifest-0001",
    chunkIndex: null,
    chunkCount: null,
    nonce: Buffer.from("101112131415161718191a1b", "hex"),
    plaintext: Buffer.from("manifest-v1", "utf8"),
  },
  {
    name: "snapshot",
    family: "SNAPSHOT",
    objectId: "snapshot-0001",
    chunkIndex: null,
    chunkCount: null,
    nonce: Buffer.from("202122232425262728292a2b", "hex"),
    plaintext: Buffer.from("snapshot-v1", "utf8"),
  },
  {
    name: "operation-segment",
    family: "OPERATION_SEGMENT",
    objectId: "segment-0001",
    chunkIndex: null,
    chunkCount: null,
    nonce: Buffer.from("303132333435363738393a3b", "hex"),
    plaintext: Buffer.from("operation-segment-v1", "utf8"),
  },
  {
    name: "attachment-chunk",
    family: "ATTACHMENT_CHUNK",
    objectId: "attachment-0001-chunk-00",
    chunkIndex: 0,
    chunkCount: 26,
    nonce: Buffer.from("404142434445464748494a4b", "hex"),
    plaintext: Buffer.from("attachment-chunk-v1", "utf8"),
  },
];
```

For each case, build the header using the existing canonical declaration order,
SHA-256 the complete Tink-style ciphertext, prefix the header with a four-byte
big-endian length, append ciphertext, and write the displayed JSON schema with
this exact loop:

```javascript
const outputDirectory = join(
  process.cwd(),
  "core/data/src/test/resources/cloud-format/v1-authenticated",
);
mkdirSync(outputDirectory, { recursive: true });

for (const testCase of cases) {
  const aad = associatedData(testCase);
  const encrypted = ciphertext(testCase, aad);
  const checksum = createHash("sha256")
    .update(encrypted)
    .digest("hex");
  const header = {
    magic: "OPEN_TASKS",
    family: testCase.family,
    schemaVersion: 1,
    cryptoVersion: 1,
    minimumReaderVersion: 1,
    vaultId: "vault-alpha",
    objectId: testCase.objectId,
    ciphertextLength: encrypted.length,
    ciphertextSha256: checksum,
    chunkIndex: testCase.chunkIndex,
    chunkCount: testCase.chunkCount,
  };
  const headerJson = JSON.stringify(header);
  const headerBytes = Buffer.from(headerJson, "utf8");
  const headerLength = Buffer.alloc(4);
  headerLength.writeUInt32BE(headerBytes.length);
  const frame = Buffer.concat([
    headerLength,
    headerBytes,
    encrypted,
  ]);
  const fixture = {
    family: testCase.family,
    schemaVersion: 1,
    cryptoVersion: 1,
    minimumReaderVersion: 1,
    vaultId: "vault-alpha",
    objectId: testCase.objectId,
    chunkIndex: testCase.chunkIndex,
    chunkCount: testCase.chunkCount,
    keyHex: key.toString("hex"),
    nonceHex: testCase.nonce.toString("hex"),
    associatedDataHex: aad.toString("hex"),
    plaintextHex: testCase.plaintext.toString("hex"),
    ciphertextHex: encrypted.toString("hex"),
    headerJson,
    frameHex: frame.toString("hex"),
  };
  writeFileSync(
    join(outputDirectory, `${testCase.name}.json`),
    `${JSON.stringify(fixture, null, 2)}\n`,
  );
}
```

- [ ] **Step 2: Generate and inspect the four immutable resources**

Run:

```bash
node scripts/generate-authenticated-cloud-v1-fixtures.mjs
find core/data/src/test/resources/cloud-format/v1-authenticated \
  -type f -print | sort
head -40 \
  core/data/src/test/resources/cloud-format/v1-authenticated/manifest.json
```

Expected: exactly four JSON files; every hex field is lowercase and non-empty;
null chunk values remain JSON null.

- [ ] **Step 3: Write the failing independent golden test**

`AuthenticatedCloudObjectGoldenTest` parses each fixture, constructs its
`CloudHeaderIdentity`, and supplies a test-only `FixtureVaultCrypto` whose
`encryptBytes` and `decryptBytes` use JCA AES/GCM with the fixture key, nonce,
and five-byte prefix.

The core assertions are:

```kotlin
assertEquals(
    fixture.associatedDataHex,
    CloudHeaderIdentityEncoding
        .associatedData(fixture.identity)
        .toHex(),
)

val encrypted = codec.encrypt(
    fixture.identity,
    fixture.plaintextHex.hexToByteArray(),
    key,
)
assertEquals(fixture.frameHex, encrypted.toHex())

val decoded = codec.decrypt(
    ByteArrayInputStream(fixture.frameHex.hexToByteArray()),
    fixture.frameHex.length.toLong() / 2,
    key,
) as CloudDecodeResult.Success
decoded.value.use { value ->
    val plaintext = value.copyPlaintext()
    try {
        assertEquals(fixture.plaintextHex, plaintext.toHex())
    } finally {
        plaintext.fill(0)
    }
}
```

Also reconstruct the canonical header bytes from `frameHex` and compare them
to `headerJson`; do not trust the production JSON encoder to derive the
expected string.

- [ ] **Step 4: Run the golden test to verify it fails before the test fake is complete**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests '*AuthenticatedCloudObjectGoldenTest' \
  --stacktrace
```

Expected: compilation or assertion failure until fixture parsing and
`FixtureVaultCrypto` are complete.

- [ ] **Step 5: Complete the independent test AEAD**

`FixtureVaultCrypto` delegates key creation/wrapping to `TinkVaultCrypto`, but
its byte AEAD methods:

- verify the supplied associated data matches the selected fixture;
- encrypt with `AES/GCM/NoPadding`, fixed nonce, and fixed raw key;
- prepend `01 00 00 00 2a` and the 12-byte nonce;
- on decrypt, verify/remove that prefix and nonce before JCA decryption; and
- throw `GeneralSecurityException` on prefix, nonce, tag, or associated-data
  mismatch.

The fake exists only in `src/test`; production never accepts a raw key or fixed
nonce.

- [ ] **Step 6: Run generator reproducibility and all codec tests**

Capture the fixture digest, regenerate, and compare:

```bash
find core/data/src/test/resources/cloud-format/v1-authenticated \
  -type f -print | sort | xargs shasum -a 256
node scripts/generate-authenticated-cloud-v1-fixtures.mjs
git diff --exit-code -- \
  core/data/src/test/resources/cloud-format/v1-authenticated
./gradlew :core:data:testDebugUnitTest \
  --tests '*AuthenticatedCloudObject*Test' \
  --stacktrace
```

Expected: regeneration produces no diff and both authenticated-codec test
classes pass.

- [ ] **Step 7: Review the fixture boundary**

Run:

```bash
rg -n \
  'drive|google|account|credential|task title|attachment name' \
  scripts/generate-authenticated-cloud-v1-fixtures.mjs \
  core/data/src/test/resources/cloud-format/v1-authenticated \
  core/data/src/test/kotlin/app/opentasks/core/data/AuthenticatedCloudObjectGoldenTest.kt
git diff --check
```

Expected: no provider/private-content match and no whitespace errors.

- [ ] **Step 8: Commit the vectors**

```bash
git add scripts/generate-authenticated-cloud-v1-fixtures.mjs \
  core/data/src/test/resources/cloud-format/v1-authenticated \
  core/data/src/test/kotlin/app/opentasks/core/data/AuthenticatedCloudObjectGoldenTest.kt
git commit -m "test: freeze authenticated cloud object vectors"
```

### Task 7: Run the Stage Exit Gates and Record the Verified Checkpoint

**Files:**

- Modify: `HANDOFF.md`
- Modify:
  `docs/superpowers/plans/2026-07-28-stage-1-direction-reset-authenticated-object-foundation-plan.md`

**Interfaces:**

- Consumes: Tasks 1–6 and the repository/device safety rules.
- Produces: a reproducible Stage 1 completion record and a single next action:
  write the focused Stage 2 local-backup and Android Auto Backup plan.

- [ ] **Step 1: Run the focused JVM and lint gates**

Run:

```bash
./gradlew :core:sync:testDebugUnitTest \
  :core:crypto:testDebugUnitTest \
  :core:data:testDebugUnitTest \
  :feature:more:lintDebug \
  --stacktrace
```

Expected: exit `0`.

- [ ] **Step 2: Audit ADB before any device test**

Run:

```bash
/Users/kk/Library/Android/sdk/platform-tools/adb devices -l
```

Expected: either no attached device, or exactly one disposable/read-only
emulator that is not the protected normal workspace. If the protected
workspace is attached, stop before instrumentation and use the repository's
recorded disposable-emulator procedure.

- [ ] **Step 3: Run the affected device suites on the disposable emulator**

Run:

```bash
./gradlew :feature:more:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest \
  --stacktrace
```

Expected: exit `0`; the Insights suite and application process-restoration
suite pass without touching the protected workspace.

- [ ] **Step 4: Run the repository debug gate**

Run:

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --stacktrace
```

Expected: exit `0`.

- [ ] **Step 5: Run release assembly separately**

Run:

```bash
./gradlew :app:assembleRelease --stacktrace
```

Expected: exit `0`, including R8 and resource shrinking.

- [ ] **Step 6: Run source, placeholder, and whitespace audits**

Run:

```bash
rg -n 'TO''DO|FIX''ME|println|android\\.util\\.Log|java\\.util\\.logging' \
  core/sync/src/main/kotlin/app/opentasks/core/sync \
  core/crypto/src/main/kotlin/app/opentasks/core/crypto \
  core/data/src/main/kotlin/app/opentasks/core/data/AuthenticatedCloudObjectCodec.kt
git diff --check
git status --short
```

Expected: no new placeholder/logging match, no whitespace error, and only the
two checkpoint documents remain modified.

- [ ] **Step 7: Re-read the approved acceptance boundary**

Confirm all of the following from code and fresh command output:

- Room is still the only live authority and no provider dependency was added.
- Existing Room/outbox data and schemas are unchanged.
- All eight `CloudHeaderIdentity` fields are encoded in AEAD associated data;
  valid identity substitutions fail authentication and incompatible versions
  reject before AEAD.
- Frame length and checksum fail before AEAD.
- Untrusted frame/authentication failures are typed and contain no private
  metadata.
- Ciphertext and associated-data ownership is cleared; successful plaintext is
  closeable or transferred once.
- Four independent family vectors reproduce byte-for-byte.
- The five Insights lint findings are absent.
- Active documents contain no Drive-primary or multi-device product promise.
- Android Auto Backup remains unmodified and accurately described as Stage 2.

- [ ] **Step 8: Record exact evidence in the handoff**

Update `HANDOFF.md` with:

- every Task 1–6 commit hash;
- exact JVM/lint/debug/release/device commands and exit results;
- device/API/AVD identity and test counts;
- confirmation that no Room schema or protected workspace changed;
- authenticated fixture SHA-256 digests;
- Stage 1 status and any non-blocking residual risk; and
- Stage 2 planning as the sole recommended next action.

Mark completed checkboxes in this plan only after their command or commit has
actually succeeded.

- [ ] **Step 9: Invoke completion verification and commit the checkpoint**

Invoke `superpowers:verification-before-completion`, then re-run:

```bash
git diff --check
git status --short
```

Expected: only `HANDOFF.md` and this plan are modified.

Commit:

```bash
git add HANDOFF.md \
  docs/superpowers/plans/2026-07-28-stage-1-direction-reset-authenticated-object-foundation-plan.md
git commit -m "docs: record stage 1 foundation verification"
```

Do not begin Stage 2 source work in this checkpoint.
