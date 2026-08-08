# Stage 6 Daily-Flow Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development to execute this plan — user
> ruling, 8 August 2026, superseding the earlier inline pin (Task 1 was
> executed and reviewed inline before the switch). One fresh implementer
> subagent per task with an independent review per boundary; implementer
> dispatches must never spawn subagents or forks of their own (standing
> fork-swarm ruling); Task 13 stays controller-owned because it drives
> the device gate. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut daily-use friction: share-sheet/selection/tile capture with
natural-language dates, an interactive Today widget, preset focus cycles,
saved searches, bulk multi-select, a guided weekly review, Markdown project
export, own-schema CSV import, and a Kanban board over existing workflows.

**Architecture:** Room stays the sole live structured-data authority at
version 9 — no schema change: saved searches light up the dormant Stage 1
`saved_views` table and its existing `SAVED_VIEW` backup family, so they
survive recovery and ride `.otvault` with no format or fixture change.
Every new write path —
widget completes, bulk composites, saved-search CRUD, CSV import, review
actions — is a `DomainCommand` through `VaultRepository.execute`; whenever a
surface offers Undo, only the repository constructs it. Saved-search CRUD,
bulk composites, and CSV import require exact Undo; `MarkReviewed` is the
deliberate append-only activity action and returns no Undo. All other features
are additive product surfaces over existing data and commands: no new
transport, no provider changes, no merge path, no new runtime permissions.

**Tech Stack:** Kotlin 2.3.21, AGP 9 built-in Kotlin, Java 17 on JDK 21,
Room 2.8.4, SQLCipher 4.15.0, `androidx.glance:glance-appwidget`, Storage
Access Framework (`CreateDocument`/`OpenDocument`), platform
`TileService`, Navigation 3, JUnit 4, Compose UI test v2. No new
catalogue entries.

## Global Constraints

- Authority spec:
  `docs/superpowers/specs/2026-08-08-stage-6-daily-flow-design.md`.
- Work directly on `main`; no branch, worktree, or pull request.
- The user-owned untracked `artifacts/` and `.kotlin/` stay untouched, as
  does the uncommitted historical Stage 3 plan amendment.
- Never start, install to, instrument, or mutate the protected
  `Pixel_10_Pro_Fold` AVD. Connected suites run only on a sole disposable
  ADB target started with `-read-only -no-snapshot-load -no-snapshot-save`.
- Bounds verbatim from the spec: 20 saved searches per workspace,
  500-character saved-search query text; bulk batches bounded at 200 ids;
  staleness is exactly 14 days without activity; focus presets are 25/5
  and 50/10 only; Stage 4/5 bounds unchanged.
- Frozen things that must not change: `.otvault` v1 format version, Stage
  3 ownership/publication codecs and roles, the Stage 4 attachment
  manifest codec, `drive.appdata`-only scope, create-only immutable
  objects. Unknown or ambiguous inputs fail closed.
- Every write is a `DomainCommand` through `VaultRepository.execute`;
  records and ordered backup-journal entries commit in one transaction;
  Undo is repository-produced; `InMemoryVaultRepository` stays
  behaviourally in sync with `RoomVaultRepository`.
- Every composite command completes all validation before its first
  mutation. A returned `CommandResult.Rejected` must leave records,
  relations, revisions, activity, and the backup journal unchanged;
  throwing across the transaction boundary is the rollback backstop, not
  normal validation control flow.
- `SavedStateHandle` stores only Bundle-safe primitives and collections
  (`String`, `Boolean`, and `List<String>` here). Domain identifier wrappers
  are reconstructed at the ViewModel boundary and never stored directly.
- Room stays at v9. `saved_views` already exists in the exported schema;
  Task 1 adds no entity, no migration, and no schema export. Any change
  that would require a version bump is out of scope for this stage.
- Logs and telemetry never contain task text, saved-search text, shared
  intent text, account details, Drive IDs, attachment names, or
  encryption metadata.
- Feature composables stay stateless, no Hilt in feature/core modules,
  new UI copy in `res/values/strings.xml` in UK English, OKLCH-only
  colours, 4 dp spacing scale, Material typography roles.
- Tests: JUnit 4 `org.junit.Assert.*`, no mocking library, `runBlocking`
  + `withTimeout(5_000)`, camelCase behaviour names,
  `androidx.compose.ui.test.junit4.v2.createComposeRule`.
- The CI gate before any completion claim:
  `./gradlew testDebugUnitTest lintDebug :app:assembleDebug`. Release
  assembly runs separately, never combined with `lintDebug`.
- Commit after every task with a conventional-prefix message ending in
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Execution notes

- Immediately before Task 1, record `git rev-parse HEAD` as the Stage 6
  implementation-base SHA in the ignored execution ledger at
  `.superpowers/sdd/2026-08-08-stage-6-daily-flow-plan/progress.md`. Task 13
  uses that exact `<base>..HEAD` range for privacy and release-surface diff
  scans; do not infer the base later from dates or commit counts.
- Each task is an independent review boundary: dispatch an independent
  review after the task commit and fix findings before the next task.
- Implementer dispatches must not spawn subagents or forks of their own
  (standing ruling; see the fork-swarm hazard note in the SDD ledger).
- No task before Task 13 runs a device suite. Instrumented tests are
  compile-verified with `:<module>:compileDebugAndroidTestKotlin` and
  execute at the Task 13 connected gate.
- No `BackupRecordFamily` change: `SAVED_VIEW` already exists with codec
  validation, capture attribution, and recovery import. Task 1 must move
  its journal fingerprint from identity-only to the content-fingerprint
  style (`toBackupRecordV1().contentSnapshot()`, the unrevisioned-family
  style used by CHECKLIST_ITEM/TAG/REMINDER/TIME_ENTRY) — identity-only
  would never journal a rename or query update. The reviewer must verify
  a rename journals an UPSERT in both engines.
- The Kanban fallback ordering inside Task 12 is mandatory: tap-to-move
  ships complete before any drag code lands; drag polish beyond
  drop-target highlight and edge auto-scroll is out of scope.
- Task 12's tap-to-move commit is an additional mandatory review boundary:
  dispatch the independent review and fix it before adding pointer-drag
  code, even though both commits remain inside the single authority task.
- CSV import is deliberately lossy and create-only; `.otvault` remains
  the transfer format. Import UI copy must say so.
- Focus transitions may start or stop only the timer owned by the persisted
  focus task. A stale alarm, missing task, rejected timer command, or timer
  owned by another task fails closed without altering the other timer.
- CSV parsing, resolution, backup-representability validation, and Undo
  preflight finish before the first import or removal write. Import and Undo
  each either commit their complete record set or commit nothing.

---

### Task 1: Saved-view commands over the dormant `saved_views` table

**Files:**

- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Snapshots.kt`
  (add `savedViews` to `WorkspaceSnapshot` after `retiredBlobSets`,
  line 39)
- Modify:
  `core/model/src/main/kotlin/app/opentasks/core/model/Identifiers.kt:98-99`
  (give `SavedViewId` a `companion object { fun new() }` like `NoteId`)
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
  (new commands + `RejectionReason` constants)
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/SavedViewPayloadCodec.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
  (WorkspaceDao members beside the note helpers, `:393-414`)
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/db/EntityMappers.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/RoomBackupJournalSession.kt`
  (SAVED_VIEW fingerprint: identity → content)
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/backup/InMemoryBackupJournal.kt`
  (`toBackupRecords` gains the savedViews arm)
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/SavedViewPayloadCodecTest.kt`
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/InMemorySavedViewCommandTest.kt`
- Test (instrumented, compile-verified):
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomSavedViewCommandInstrumentedTest.kt`

**Interfaces:**

- Consumes: `SavedView(id, workspaceId, name, query: SearchQuery)`
  (`Records.kt:315-320`), `SavedViewEntity(id, workspaceId, name,
  encryptedQuery: ByteArray)` (`Entities.kt:256-263`), `SearchQuery`
  (`Snapshots.kt:48-54`), `TemplatePayloadCodec` shape
  (`TemplatePayloadCodec.kt:25-60`), the existing `SAVED_VIEW` arms in
  `BackupMutationCodec` (`:384-387`), `BackupRecordImporter`,
  `RecoveryImportDao`, and `BackupCaptureDao`.
- Produces:

```kotlin
// VaultRepository.kt — inside sealed interface DomainCommand
data class CreateSavedView(
    val savedViewId: SavedViewId,
    val name: String,
    val query: SearchQuery,
) : DomainCommand

data class RenameSavedView(val savedViewId: SavedViewId, val name: String) : DomainCommand

data class UpdateSavedViewQuery(
    val savedViewId: SavedViewId,
    val query: SearchQuery,
) : DomainCommand

data class DeleteSavedView(val savedViewId: SavedViewId) : DomainCommand

data class RestoreSavedView(val savedView: SavedView) : DomainCommand // undo-only

// RejectionReason gains: SAVED_VIEW_LIMIT_REACHED, SAVED_VIEW_NAME_INVALID,
// SAVED_VIEW_QUERY_TOO_LONG, SAVED_VIEW_PAYLOAD_TOO_LARGE

// WorkspaceSnapshot gains (after retiredBlobSets):
val savedViews: List<SavedView> = emptyList(),

// SavedViewPayloadCodec (internal object, TemplatePayloadCodec shape):
// MAX_PAYLOAD_BYTES = 2 MiB, matching the backup binary-field ceiling.
fun encode(query: SearchQuery): ByteArray   // bounded JSON, FORMAT_VERSION = 1
fun decode(payload: ByteArray): SearchQuery
```

Bounds (both repository companions): `MAX_SAVED_VIEWS = 20`,
`MAX_SAVED_VIEW_NAME_LENGTH = 64`, `MAX_SAVED_VIEW_QUERY_LENGTH = 500`
(the query `text` length). Names are trimmed and blank names reject; query
text is trimmed but may be blank because project/tag/completion filters can
carry the search. The hard limit uses the physical DAO row count, so a
preserved malformed/unknown-version row still consumes one slot; product UI
may optimistically offer Save from the visible count, but the repository then
rejects without rewriting or deleting that raw row.
Undo pairs: `CreateSavedView` → `DeleteSavedView`; `RenameSavedView` →
`RenameSavedView` (prior name); `UpdateSavedViewQuery` →
`UpdateSavedViewQuery` (prior query); `DeleteSavedView` →
`RestoreSavedView(savedView)`.

- [ ] **Step 1: Write the failing tests**

`InMemorySavedViewCommandTest.kt` (fixture wiring copied from
`RetiredBlobSetFamilyTest.kt:19-20`):

```kotlin
class InMemorySavedViewCommandTest {

    private val journal = InMemoryBackupJournal()
    private val repository = InMemoryVaultRepository(backupJournal = journal)

    @Test
    fun createRenameAndDeleteRoundTripWithExactUndo() = runBlocking {
        withTimeout(5_000) {
            val id = SavedViewId.new()
            val query = SearchQuery(text = "deep work")
            val created = repository.execute(
                DomainCommand.CreateSavedView(id, "Focus", query),
            ) as CommandResult.Success
            assertEquals(
                "Focus",
                repository.currentWorkspace().savedViews.single().name,
            )

            val renamed = repository.execute(
                DomainCommand.RenameSavedView(id, "Deep focus"),
            ) as CommandResult.Success
            repository.execute(renamed.undo!!)
            assertEquals(
                "Focus",
                repository.currentWorkspace().savedViews.single().name,
            )

            repository.execute(created.undo!!)
            assertTrue(repository.currentWorkspace().savedViews.isEmpty())
        }
    }

    @Test
    fun renameJournalsAnUpsertForTheSavedViewFamily() = runBlocking {
        withTimeout(5_000) {
            val id = SavedViewId.new()
            repository.execute(
                DomainCommand.CreateSavedView(id, "Focus", SearchQuery("q")),
            )
            val before = journal.entries.size
            repository.execute(DomainCommand.RenameSavedView(id, "Later"))
            val appended = journal.entries.drop(before)
            assertTrue(
                appended.any {
                    it.objectType == BackupRecordFamily.SAVED_VIEW.name
                },
            )
        }
    }

    @Test
    fun twentyFirstSavedViewIsRejectedFailClosed() = runBlocking {
        withTimeout(5_000) {
            repeat(20) {
                repository.execute(
                    DomainCommand.CreateSavedView(
                        SavedViewId.new(), "View $it", SearchQuery("q$it"),
                    ),
                )
            }
            val result = repository.execute(
                DomainCommand.CreateSavedView(
                    SavedViewId.new(), "One too many", SearchQuery("q"),
                ),
            )
            assertTrue(result is CommandResult.Rejected)
            assertEquals(20, repository.currentWorkspace().savedViews.size)
        }
    }
}
```

Use the existing `BackupJournalEntry.objectType` field shown in
`InMemoryBackupJournal.kt:26-84`; compare it directly with
`BackupRecordFamily.SAVED_VIEW.name`.
`SavedViewPayloadCodecTest.kt`: encode→decode round trip preserving all
`SearchQuery` fields; two logically equal queries whose project/tag sets were
inserted in different orders encode to identical bytes; oversized `text`
(501 chars), a payload over `MAX_PAYLOAD_BYTES`, malformed UTF-8, an
unsupported format version, and foreign JSON
(`ignoreUnknownKeys = false`) each throw without returning a partial query.
The payload DTO stores project/tag id strings in sorted lists and decodes them
back to sets. The codec serialises only the query; duplicated entity identity
fields can therefore never drift. Repository command tests also prove a query
whose encoded filter ids exceed the payload ceiling returns
`SAVED_VIEW_PAYLOAD_TOO_LARGE` without mutation rather than throwing.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*SavedView*"`
Expected: FAIL — `savedViews`/commands unresolved.

- [ ] **Step 3: Implement**

Snapshot field, `SavedViewId.new()`, commands, rejection reasons. Codec
per `TemplatePayloadCodec` (bounded JSON, `encodeDefaults = true`,
`explicitNulls = true`, `ignoreUnknownKeys = false`). WorkspaceDao gains
the five-member surface (`Entities` order:
`observeSavedViews(): Flow<List<SavedViewEntity>>` ordered by `name, id`,
`getSavedView(id)`, `upsertSavedView(value)`,
`deleteSavedView(id): Int`, plus `savedViewCount(): Int`).
`EntityMappers` gains strict `SavedViewEntity.toModel()` by taking
id/workspace/name from the entity and decoding only `encryptedQuery`; the
write mapper accepts and stores the handler's already validated encoded query
bytes. The Room
snapshot mapper wraps only this dormant-family decode with `mapNotNull` and
omits a malformed legacy/recovered payload without deleting, rewriting, or
logging it; one bad dormant row must not prevent repository readiness. The
backup capture path still preserves its raw entity. Room:
dispatch arms + handlers (validation → encode once, mapping a size failure to
`SAVED_VIEW_PAYLOAD_TOO_LARGE` and reusing those bytes for the entity → upsert
→ `CommandResult.Success`
with undo; delete reads the row first for `RestoreSavedView`);
`observeDatabase()` gains the collection as a new outermost
`combine` wrapper exactly as retired sets did
(`RoomVaultRepository.kt:2989-2993`), `RelationRows` gains a defaulted
field, `buildSnapshot` maps it. In-memory: arms + handlers + a defaulted
`publish` parameter + `copy` line, sorted by `name` then id to match the
DAO. Journal: in `RoomBackupJournalSession.snapshots()` replace the
SAVED_VIEW `identitySnapshot` line with the content-fingerprint style
(`it.toBackupRecordV1().contentSnapshot()`, as CHECKLIST_ITEM/TAG do),
using the existing `BackupMutationDao.savedView(id)` full-row query;
`InMemoryBackupJournal.toBackupRecords` gains the savedViews arm
building `SavedViewEntity` via the codec.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :core:data:testDebugUnitTest` — all green (existing
suites prove no journal/fixture regression; no fixture regenerates
because no format changed).
Run: `./gradlew :core:data:compileDebugAndroidTestKotlin` after writing
`RoomSavedViewCommandInstrumentedTest`. Its four tests create/rename/update/
delete with exact Undo and database read-back, reject the twenty-first row
without mutation, and assert rename plus query-update each append a
`SAVED_VIEW` UPSERT whose content differs from the prior fingerprint. The
fourth inserts one malformed dormant payload beside a valid row, proves the
repository exposes only the valid row and remains ready, and proves the raw
malformed entity remains in the DAO. The instrumented class executes at
Task 13.
Run: `scripts/check-schema-drift.sh` — clean (no schema change).
Run the CI gate: `./gradlew testDebugUnitTest lintDebug :app:assembleDebug`.

- [ ] **Step 5: Commit**

```bash
git add core/model core/domain core/data
git commit -m "feat: add saved view commands over the dormant table" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Natural-language dates in Quick Add

**Files:**

- Create:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/NaturalDateParser.kt`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt:146-150`
  (`CreateTask` appends `val due: ZonedMoment? = null` after `priority`,
  preserving existing positional callers)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt:1040`
  and `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt:963`
  (`createTask` handlers seed `due`)
- Modify: `app/src/main/kotlin/app/opentasks/QuickAddSheet.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt:176-178`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt:1247-1255`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `core/domain/src/test/kotlin/app/opentasks/core/domain/NaturalDateParserTest.kt`
- Modify: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`
- Modify (instrumented, compile-verified):
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`

**Interfaces:**

- Consumes: `ZonedMoment(instant, zoneId)` (`Records.kt:31-36`),
  `MAX_QUICK_ADD_TITLE_LENGTH = 240` (`QuickAddSheet.kt:117`).
- Produces:

```kotlin
// NaturalDateParser.kt
data class NaturalDateMatch(
    val startIndex: Int,
    val endIndex: Int,      // exclusive
    val due: ZonedMoment,
)

fun parseNaturalDate(text: String, now: Instant, zone: ZoneId): NaturalDateMatch?

// QuickAddSheet signature becomes:
fun QuickAddSheet(
    onDismiss: () -> Unit,
    onAdd: (String, ZonedMoment?) -> Unit,
    initialTitle: String = "",
)

// WorkspaceViewModel
fun addTask(title: String, due: ZonedMoment? = null) {
    execute(DomainCommand.CreateTask(title, due = due))
}
```

Pinned grammar (case-insensitive, matched at token boundaries, last
match in the text wins): `today`, `tomorrow`, full and three-letter
weekday names, `next <weekday>`, `in N days`/`in N weeks`, times as
`HH:mm`, `H[am|pm]`, `at <time>`; a date token may be followed by a
time token. Resolution rules, pinned by tests: date-only → 17:00 in
`zone`; time-only → today at that time if still future, else tomorrow;
bare weekday → soonest strictly-future occurrence (1..7 days); `next
<weekday>` → that occurrence plus 7 days; `today` keeps 17:00 even if
past (the chip is a suggestion, not a validator). A token boundary rejects
an adjacent Unicode letter, digit, underscore, or hyphen, so
`friday-market` is plain title text. Numeric parsing uses `toLongOrNull` and
checked `java.time` arithmetic; invalid times and overflowing day/week
offsets return null. Returns null on no match. Pure `java.time` arithmetic,
no library.

- [ ] **Step 1: Write the failing parser test**

`NaturalDateParserTest.kt` — fixed `now` (a Wednesday):

```kotlin
class NaturalDateParserTest {

    private val zone = ZoneId.of("Asia/Bangkok")
    // Wednesday 5 August 2026, 10:00 in Asia/Bangkok
    private val now = ZonedDateTime.of(2026, 8, 5, 10, 0, 0, 0, zone).toInstant()

    private fun dueOf(match: NaturalDateMatch?): ZonedDateTime =
        ZonedDateTime.ofInstant(match!!.due.instant, zone)

    @Test
    fun tomorrowWithTimeResolvesAndReportsTheMatchedSpan() {
        val match = parseNaturalDate("Pay invoices tomorrow 4pm", now, zone)
        assertEquals(ZonedDateTime.of(2026, 8, 6, 16, 0, 0, 0, zone), dueOf(match))
        assertEquals("tomorrow 4pm", "Pay invoices tomorrow 4pm"
            .substring(match!!.startIndex, match.endIndex))
    }

    @Test
    fun dateOnlyDefaultsToFivePm() {
        val match = parseNaturalDate("call plumber fri", now, zone)
        assertEquals(ZonedDateTime.of(2026, 8, 7, 17, 0, 0, 0, zone), dueOf(match))
    }

    @Test
    fun nextWeekdayAddsSevenDaysToTheSoonestOccurrence() {
        val match = parseNaturalDate("review next fri", now, zone)
        assertEquals(ZonedDateTime.of(2026, 8, 14, 17, 0, 0, 0, zone), dueOf(match))
    }

    @Test
    fun timeOnlyPastRollsToTomorrow() {
        val match = parseNaturalDate("standup 9am", now, zone)
        assertEquals(ZonedDateTime.of(2026, 8, 6, 9, 0, 0, 0, zone), dueOf(match))
    }

    @Test
    fun relativeDaysResolveAtFivePm() {
        val match = parseNaturalDate("send report in 3 days", now, zone)
        assertEquals(ZonedDateTime.of(2026, 8, 8, 17, 0, 0, 0, zone), dueOf(match))
    }

    @Test
    fun embeddedTokensPlainTextAndInvalidValuesDoNotMatch() {
        assertNull(parseNaturalDate("buy milk friday-market", now, zone))
        assertNull(parseNaturalDate("no dates here", now, zone))
        assertNull(parseNaturalDate("meet at 25:99", now, zone))
        assertNull(parseNaturalDate("later in 999999999999999999999 weeks", now, zone))
    }
}
```

Add `createTaskPersistsProvidedDue` to both repository test classes. Dispatch
`CreateTask("Due task", due = expected)`, read the created task back, and
assert the exact instant and zone id; the Room case executes at Task 13.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:domain:testDebugUnitTest --tests "*NaturalDateParser*"`
and `./gradlew :core:data:testDebugUnitTest --tests "*InMemoryVaultRepositoryTest.createTaskPersistsProvidedDue*"`.
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement parser and wire Quick Add**

Parser: tokenize with regexes over the pinned grammar using explicit
negative look-behind/look-ahead for the boundary character set above;
resolve with checked `ZonedDateTime` arithmetic; return the last (rightmost)
valid match and catch `DateTimeException`/arithmetic overflow as null.
`CreateTask` gains `due` (additive, default null); both `createTask`
handlers copy it onto the new task exactly as `UpdateTask` writes due —
no reminder is created. `QuickAddSheet`: seed
`rememberSaveable { mutableStateOf(initialTitle) }`; run
`parseNaturalDate(title, now, zone)` on each change (system clock/zone
here is fine — this is UI suggestion, not domain logic); when non-null
show an `AssistChip` under the field (test tag `"quick-add-date-chip"`,
label via new `quick_add_date_suggestion` string using the UK format
`d MMM HH:mm`); tapping the chip sets an `appliedDue` state and strips
the matched span from `title`; a second tap-target (`"quick-add-date-clear"`)
clears it. Submit passes `(title, appliedDue)`. `OpenTasksApp:1247-1255`
and the FAB/shortcut call sites pass `initialTitle = ""` and the
two-argument `onAdd`.

- [ ] **Step 4: Run to verify pass + CI gate**

Run: `./gradlew :core:domain:testDebugUnitTest` then the full gate
`./gradlew testDebugUnitTest lintDebug :app:assembleDebug`.
Expected: PASS; `QuickAddSheetInstrumentedTest` still compiles
(`./gradlew :app:compileDebugAndroidTestKotlin`) — update its call site
for the new signature — and
`./gradlew :core:data:compileDebugAndroidTestKotlin` compiles the Room due
case.

- [ ] **Step 5: Commit**

```bash
git add core/domain core/data app
git commit -m "feat: parse natural-language dates in quick add" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Share-sheet and text-selection intake

**Files:**

- Modify: `app/src/main/AndroidManifest.xml:23-37` (two intent filters
  on `.MainActivity`)
- Modify: `app/src/main/kotlin/app/opentasks/MainActivity.kt`
- Create: `app/src/main/kotlin/app/opentasks/QuickAddPrefill.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Test: `app/src/test/kotlin/app/opentasks/QuickAddPrefillTest.kt`

**Interfaces:**

- Consumes: `handleIntent` (`MainActivity.kt:289-301`),
  `quickAddSignal` chain (`MainActivity.kt:58`, `OpenTasksApp.kt:548-550`),
  `QuickAddSheet(onDismiss, onAdd, initialTitle)` from Task 2,
  `MAX_QUICK_ADD_TITLE_LENGTH = 240`.
- Produces:

```kotlin
// QuickAddPrefill.kt — pure, JVM-testable (no android.* imports)
fun quickAddPrefill(raw: CharSequence?): String? =
    raw?.take(240)
        ?.toString()
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

// MainActivity gains (the distinct name does not hide the helper):
private var quickAddPrefillText by mutableStateOf<String?>(null)
// OpenTasksApp gains parameter:
quickAddPrefillText: String? = null,
```

- [ ] **Step 1: Write the failing prefill test**

```kotlin
class QuickAddPrefillTest {

    @Test
    fun firstLineTrimmedAndBounded() {
        assertEquals("Buy milk", quickAddPrefill("  Buy milk  \nsecond line"))
        assertEquals(240, quickAddPrefill("x".repeat(500))!!.length)
        assertNull(quickAddPrefill("\nBuy milk"))
        assertNull(quickAddPrefill("   \n \n"))
        assertNull(quickAddPrefill(null))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*QuickAddPrefill*"`
Expected: FAIL — `quickAddPrefill` not defined.

- [ ] **Step 3: Implement**

Manifest — inside the existing `.MainActivity` element:

```xml
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/plain" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.PROCESS_TEXT" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/plain" />
</intent-filter>
```

`handleIntent` gains two arms before the existing `when` falls through:
`Intent.ACTION_SEND` →
`quickAddPrefill(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))`,
`Intent.ACTION_PROCESS_TEXT` →
`quickAddPrefill(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))`;
a non-null prefill sets `quickAddPrefillText` state and increments
`quickAddSignal` (reuse the existing signal so the lock gate and
`LaunchedEffect` chain stay untouched; a null prefill does nothing).
`OpenTasksApp` copies `quickAddPrefillText.orEmpty()` into its own pending
sheet title inside `LaunchedEffect(quickAddSignal)`, then calls
`onQuickAddConsumed()` immediately to clear MainActivity's copy. Render the
sheet inside `key(quickAddSignal)` so a second `onNewIntent` while the sheet
is already visible creates fresh `rememberSaveable` title/due state even
when the same text is shared twice. Both Add and Dismiss clear the pending
sheet title. Pass `onQuickAddConsumed` alongside the existing signal params,
following the `openTaskId`/`openTaskSignal` precedent at
`MainActivity.kt:106-114`.
The intent text is never logged; the extras carry only what the user
explicitly shared.

- [ ] **Step 4: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests "*QuickAddPrefill*"` — PASS.
Run: `./gradlew testDebugUnitTest lintDebug :app:assembleDebug` — green;
manifest merges (lint would flag a malformed filter).
Device proof lands in the Task 13 checklist (share from a real app,
select-text → "Open Tasks").

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "feat: accept shared and selected text into quick add" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Quick Settings tile

**Files:**

- Create: `app/src/main/kotlin/app/opentasks/tile/QuickAddTileService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**

- Consumes: `QUICK_ADD_ACTION = "app.opentasks.action.QUICK_ADD"`
  (`MainActivity.kt:303-308` — make the constant `internal` on the
  companion so the tile reuses it instead of duplicating the literal),
  the existing `@drawable/ic_quick_add` and `@string/quick_add`.
- Produces: nothing later tasks consume.

- [ ] **Step 1: Implement (no JVM-testable logic — the service is
  declarative glue; device proof is Task 13's checklist)**

```kotlin
class QuickAddTileService : TileService() {

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.QUICK_ADD_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }
}
```

(minSdk 36 — only the `PendingIntent` overload of
`startActivityAndCollapse` exists; the deprecated Intent overload throws
on UpsideDownCake+.)

Manifest, inside `<application>`:

```xml
<service
    android:name=".tile.QuickAddTileService"
    android:exported="true"
    android:icon="@drawable/ic_quick_add"
    android:label="@string/quick_add"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
```

The bind permission means only SystemUI can bind — this is the one new
exported component of the stage besides the Task 3 intent filters, and
Task 13's release-scope check names it.

- [ ] **Step 2: Verify**

Run: `./gradlew testDebugUnitTest lintDebug :app:assembleDebug` — green
(lint validates the service declaration and tile metadata).

- [ ] **Step 3: Commit**

```bash
git add app
git commit -m "feat: add quick settings quick add tile" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Interactive Today widget

**Files:**

- Modify: `app/src/main/kotlin/app/opentasks/widget/TodayWidgetProjection.kt`
- Modify: `app/src/main/kotlin/app/opentasks/widget/TodayWidget.kt`
- Test: `app/src/test/kotlin/app/opentasks/widget/TodayWidgetProjectionTest.kt`
  (extend)

**Interfaces:**

- Consumes: `computeTodayProjection(snapshot, today, zone, now,
  titlesPermitted)` (`TodayWidgetProjection.kt:42-48`),
  `TodayWidgetPublisher` and its `companion object { active }`
  (`TodayWidget.kt:245-366`), `StopGatedWriter` (`:209-228`),
  `DomainCommand.CompleteTask(taskId)` (`VaultRepository.kt:234-238`),
  `Task.isBlocked` (`Records.kt:158-161`), Glance
  `actionRunCallback`/`ActionCallback`.
- Produces:

```kotlin
// TodayWidgetProjection.kt
data class FocusEntry(
    val taskId: String,
    val title: String,
    val completable: Boolean,   // false when the task is blocked
)

data class TodayWidgetProjection(
    val openTodayCount: Int,
    val overdueCount: Int,
    val focusEntries: List<FocusEntry>,   // replaces focusTitles
)

// TodayWidget.kt
class CompleteFocusTaskAction : ActionCallback   // reads TaskIdKey parameter

// TodayWidgetPublisher companion gains:
fun completeActiveTask(taskId: String)   // delegates only to an active publisher
```

- [ ] **Step 1: Extend the projection test (failing)**

Add to `TodayWidgetProjectionTest.kt`:

```kotlin
@Test
fun focusEntriesCarryIdsAndBlockedTasksAreNotCompletable() {
    val snapshot = OpenTasksFixtures.snapshot
    val blocked = snapshot.tasks.first { it.isBlocked }.copy(
        start = null,
        due = ZonedMoment(
            Instant.parse("2026-07-26T10:00:00Z"),
            "Asia/Bangkok",
        ),
    )
    val projection = computeTodayProjection(
        snapshot = snapshot.copy(tasks = listOf(blocked)),
        today = LocalDate.of(2026, 7, 26),
        zone = ZoneId.of("Asia/Bangkok"),
        now = Instant.parse("2026-07-26T03:00:00Z"),
        titlesPermitted = true,
    )
    val blockedEntry = projection.focusEntries.single()
    assertEquals(blocked.id.value, blockedEntry.taskId)
    assertFalse(blockedEntry.completable)
}
```

Adapt the existing tests from `focusTitles` to `focusEntries.map { it.title }`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*TodayWidgetProjection*"`
Expected: FAIL — `focusEntries` unresolved.

- [ ] **Step 3: Implement**

Projection: same task selection as today, now emitting id + title +
`completable = !task.isBlocked`. Widget state: keep the three
`FocusTitleKeys` and add `FocusIdKeys` and `FocusCompletableKeys`
(same three-slot pattern, `TodayWidget.kt:56-60`); the publisher's
`writeProjection`/`writeFocusTitles` write them and `clearTitles` clears
them (ids are workspace identifiers, not text — but clear them anyway:
concealment stays total). Content: each focus row becomes a `Row` with
the title (tap-through: reuse the existing task-open contract exactly via
`actionStartActivity(Intent(context, MainActivity::class.java)
.setAction(ReminderIntents.ACTION_OPEN_TASK)
.putExtra(ReminderIntents.EXTRA_TASK_ID, entry.taskId))`; add no second
widget-only extra or `MainActivity` branch) and, only when
`titlesPermitted && completable`, a trailing complete glyph wrapped in
`.clickable(actionRunCallback<CompleteFocusTaskAction>(actionParametersOf(TaskIdKey to entry.id)))`.
`CompleteFocusTaskAction.onAction` calls
`TodayWidgetPublisher.completeActiveTask(taskId)`, which executes
an instance method inside the active publisher's own scope. Immediately
before dispatch it re-reads `repository.observeWorkspace().value`, recomputes
the current projection, and requires `titlesPermitted == true` plus a matching
`focusEntries` row with `completable == true`; a stale, concealed, missing,
blocked, or no-longer-today action only republishes truth. An authorised row
executes `DomainCommand.CompleteTask(TaskId(taskId))`, then republishes; a
repository rejection also only republishes. When `titlesPermitted` is
false nothing action-bearing renders (counts only, whole-widget tap
opens the app, existing behaviour). Single tap completes; no confirm —
deliberate glance-surface trade-off, recorded in the spec.

- [ ] **Step 4: Verify**

Run: `./gradlew :app:testDebugUnitTest` then the CI gate. Expected:
PASS; `StopGatedWriterTest` untouched and green. Device tap +
locked-state concealment land in Task 13's checklist.

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "feat: make today widget rows complete and open tasks" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Focus cycles

**Files:**

- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
  (`StopTimerIfOwned` + rejection reason)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
  and `InMemoryVaultRepository.kt` (atomic owner-checked stop)
- Modify:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`
- Modify (instrumented, compile-verified):
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`
- Create: `app/src/main/kotlin/app/opentasks/focus/FocusSession.kt`
  (pure model + phase math)
- Create: `app/src/main/kotlin/app/opentasks/focus/FocusSessionStore.kt`
- Create: `app/src/main/kotlin/app/opentasks/focus/FocusAlarms.kt`
- Modify: `app/src/main/kotlin/app/opentasks/reminders/ReminderSystem.kt`
  (system-event receiver re-arms focus alarms)
- Modify: `app/src/main/AndroidManifest.xml` (non-exported
  `FocusAlarmReceiver`)
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt` (banner +
  start entry point)
- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
  (nullable `onStartFocus` lambda + preset option enum)
- Modify: `feature/tasks/src/main/res/values/strings.xml` (preset/menu copy)
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/app/opentasks/focus/FocusSessionTest.kt`
- Test: `app/src/test/kotlin/app/opentasks/focus/FocusTimerOwnershipTest.kt`
- Test (instrumented, compile-verified):
  `app/src/androidTest/kotlin/app/opentasks/focus/FocusSessionStoreInstrumentedTest.kt`

**Interfaces:**

- Consumes: `DomainCommand.StartTimer(taskId)` / `StopTimer`
  (`VaultRepository.kt:258-263`), the `AppLockSettings` SharedPreferences
  precedent (`AppLockSettings.kt:25`), the `ReminderScheduler` alarm
  shape (`ReminderSystem.kt:101-128`: `setExactAndAllowWhileIdle` with
  `SecurityException` → inexact fallback; PendingIntent uniqueness via
  `Uri` data, request code 0), `ReminderNotifications.createChannel`
  precedent (`:162-173`).
- Produces:

```kotlin
// FocusSession.kt — pure, no android.* imports
enum class FocusPreset(val focus: Duration, val rest: Duration) {
    TWENTY_FIVE_FIVE(Duration.ofMinutes(25), Duration.ofMinutes(5)),
    FIFTY_TEN(Duration.ofMinutes(50), Duration.ofMinutes(10)),
}

data class FocusSession(
    val taskId: String,
    val preset: FocusPreset,
    val phase: FocusPhaseKind,
    val phaseEndsAt: Instant,
)

enum class FocusPhaseKind { FOCUS, REST }

class FocusSessionController(private val clock: Clock) {
    fun start(taskId: String, preset: FocusPreset): FocusSession
    fun reconcile(session: FocusSession): FocusSession?
}

internal enum class FocusTimerAction { START, STOP, NONE, CLEAR_SESSION }
internal fun focusTimerAction(
    session: FocusSession,
    activeTimerTaskId: String?,
    sessionTaskAvailable: Boolean,
): FocusTimerAction

// FocusSessionStore: SharedPreferences("focus_session") — keys
// "task_id", "preset", "phase", "phase_end"; exposes a StateFlow and
// load(): FocusSession?, save(FocusSession), clear(). Unknown/corrupt values
// clear the store and return null.

// WorkspaceViewModel keeps existing callers source-compatible; it still
// publishes the normal snackbar event before invoking the callback:
fun execute(command: DomainCommand, onResult: (CommandResult) -> Unit = {})

// DomainCommand gains; the repository compares and stops in one atomic
// handler. Existing ownerless StopTimer callers remain unchanged.
data class StopTimerIfOwned(val taskId: TaskId) : DomainCommand
// RejectionReason gains TIMER_OWNERSHIP_CHANGED.

// feature/tasks — module-owned twin (LockDelayOption precedent):
enum class FocusPresetOption { TWENTY_FIVE_FIVE, FIFTY_TEN }
// TasksScreen gains: onStartFocus: ((FocusPresetOption) -> Unit)? = null
```

- [ ] **Step 1: Write the failing phase and ownership tests**

```kotlin
class FocusSessionTest {

    private val start = Instant.parse("2026-08-08T09:00:00Z")
    private val initial = FocusSession(
        taskId = "task-1",
        preset = FocusPreset.TWENTY_FIVE_FIVE,
        phase = FocusPhaseKind.FOCUS,
        phaseEndsAt = start.plus(Duration.ofMinutes(25)),
    )

    private fun controllerAt(now: Instant) =
        FocusSessionController(Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun startPersistsFocusPhaseAndItsEnd() {
        val session = controllerAt(start).start("task-1", FocusPreset.TWENTY_FIVE_FIVE)
        assertEquals(FocusPhaseKind.FOCUS, session.phase)
        assertEquals(start.plus(Duration.ofMinutes(25)), session.phaseEndsAt)
    }

    @Test
    fun exactBoundaryAndDelayedReconcileReachTheCurrentPhase() {
        val rest = controllerAt(start.plus(Duration.ofMinutes(25))).reconcile(initial)!!
        assertEquals(FocusPhaseKind.REST, rest.phase)
        assertEquals(start.plus(Duration.ofMinutes(30)), rest.phaseEndsAt)

        val secondFocus = controllerAt(start.plus(Duration.ofMinutes(31))).reconcile(initial)!!
        assertEquals(FocusPhaseKind.FOCUS, secondFocus.phase)
        assertEquals(start.plus(Duration.ofMinutes(55)), secondFocus.phaseEndsAt)
    }

    @Test
    fun preBoundaryClockLeavesSessionUnchanged() {
        assertEquals(
            initial,
            controllerAt(start.plus(Duration.ofMinutes(24))).reconcile(initial),
        )
    }
}
```

`FocusTimerOwnershipTest.kt` pins the four safety decisions: unavailable
session task (missing or binned) → CLEAR_SESSION; FOCUS with no timer → START; REST with the
session task active → STOP; and either phase with another task active never
returns STOP or START (it returns CLEAR_SESSION). These are plain JVM tests;
the receiver and foreground reconciler both call this same function.
`FocusSessionStoreInstrumentedTest` saves and reloads all four persisted
fields through a fresh store instance, then separately injects an unknown
preset, unknown phase, and malformed end instant and proves each load returns
null and clears every focus-session preference key.
Add two focused cases to each repository's existing test class:
`stopTimerIfOwnedStopsTheMatchingTimer` and
`stopTimerIfOwnedRejectsAnotherOwnerWithoutMutation`. The latter snapshots
the active entry and journal count, dispatches the command for a different
task, and asserts `TIMER_OWNERSHIP_CHANGED` with both unchanged.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "*FocusSession*"`
and `./gradlew :core:data:testDebugUnitTest --tests "*InMemoryVaultRepositoryTest.stopTimerIfOwned*"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`FocusSessionController.start` creates FOCUS ending at `clock.instant() +
preset.focus`. `reconcile` uses quotient/remainder over the focus+rest cycle
from the persisted `phaseEndsAt` to find the phase whose end is strictly
after `clock.instant()`; phase edges remain anchored to the prior end, so
late alarms never drift or require an unbounded loop. Checked duration
arithmetic rejects an unrepresentable session. Store: exact
`AppLockSettings` shape over
`getSharedPreferences("focus_session", MODE_PRIVATE)` provided in
`AppModule`. `FocusAlarms`: schedules one exact alarm for the current
phase's `phaseEndsAt` (`setExactAndAllowWhileIdle`, `SecurityException` →
`setAndAllowWhileIdle`), PendingIntent to a new non-exported
`FocusAlarmReceiver` with data `opentasks://focus/boundary`;
`FocusAlarmReceiver.onReceive` reloads and reconciles the session, persists
the new phase/end, re-arms, then attempts one generic notification (new
channel `"focus_sessions"`, strings
`focus_phase_ended_focus` = "Focus block finished — take a break" /
`focus_phase_ended_rest` = "Break over — back to it", no task text
ever). A small focus-channel availability check uses
the existing POST_NOTIFICATIONS/global-settings checks plus that channel's
importance; missing permission, a disabled channel, or `SecurityException`
skips only the alert and never the phase transition. Only with an active
vault runtime, inspect the current active `TimeEntry`: FOCUS starts the
session task only when no timer is active; REST stops only an active timer
whose `taskId` equals the session task. If another task owns the timer, or
the focus task is missing or binned, clear the focus session and alarm without
touching that timer. Every focus-owned stop dispatches
`StopTimerIfOwned(TaskId(session.taskId))`; its repository handler queries the active
entry and conditionally stops it inside the same command transaction. No
active timer is an idempotent success; another owner rejects
`TIMER_OWNERSHIP_CHANGED` without a record or journal write. A rejected
focus start/stop clears the session and alarm. Swallow inactive-runtime `IllegalStateException`
exactly as `ReminderActionReceiver.onReceive` does
(`ReminderSystem.kt:324-339`).

Session start (task detail → `onStartFocus`) first executes
`StartTimer(taskId)` through the result-reporting ViewModel method. Only a
success saves `controller.start(taskId, preset)` and arms its alarm; rejection leaves no
session or alarm. Stop clears store/cancels the alarm and dispatches
`StopTimerIfOwned` only when the current active timer belongs to the session task.
Foreground reconcile uses the same ownership helper as the receiver; a dead
process does not retroactively edit elapsed time. The existing notification
permission/settings affordance remains the sole permission flow; when the
focus-channel availability check fails, the banner shows that existing
enable-notifications action
without blocking the timer session. Banner: a compact `Surface` above the
bottom bar when a session exists (phase label, remaining time via a 1 s
ticker, and Stop only; tags `focus-banner`, `focus-stop`). No skip-phase
control or transition exists. `ReminderSystemEventReceiver`
gains focus re-arm on its existing boot/time-change actions. It reloads and
re-arms the device-local focus store before the existing lazy repository
lookup, so an inactive vault runtime can skip reminder reconciliation without
also skipping focus re-arm. TasksScreen:
`onStartFocus` nullable lambda + a small preset menu on the timer row
(48 dp targets, `stringResource` copy in the tasks module's existing
`strings.xml`); `:app` maps `FocusPresetOption` → `FocusPreset`.

- [ ] **Step 4: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests "*Focus*"` → PASS, then
`:core:data:testDebugUnitTest` and the CI gate;
`:feature:tasks:compileDebugAndroidTestKotlin` still compiles, and
`:app:compileDebugAndroidTestKotlin` plus
`:core:data:compileDebugAndroidTestKotlin` compile the instrumented tests.
Boundary notification and timer ownership land in Task 13's device checklist.

- [ ] **Step 5: Commit**

```bash
git add core/domain core/data app feature/tasks
git commit -m "feat: add preset focus cycles on the task timer" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Saved searches UI

**Files:**

- Modify: `app/src/main/kotlin/app/opentasks/SearchSurface.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt:1270-1291`
- Modify: `app/src/main/res/values/strings.xml`
- Test (instrumented, compile-verified):
  `app/src/androidTest/kotlin/app/opentasks/SearchSurfaceSavedViewsInstrumentedTest.kt`

**Interfaces:**

- Consumes: Task 1's commands and `WorkspaceSnapshot.savedViews`;
  `SearchSurface(results, onQueryChange, onDismiss, onOpenTask,
  onOpenProject)` (`SearchSurface.kt:50-57`; the surface MUST stay a
  `Dialog` — `ShortcutRootWiringInstrumentedTest.kt:134` structurally
  depends on the Dialog window), local query state
  (`var query by rememberSaveable`, `:58`), `MAX_SEARCH_QUERY_LENGTH = 500`
  (`:159`).
- Produces — `SearchSurface` gains defaulted parameters (existing
  parameters; update the app caller and instrumented search callers for the
  `SearchQuery` callback type):

```kotlin
onQueryChange: (SearchQuery) -> Unit,
savedViews: List<SavedView> = emptyList(),
onSaveView: ((String, SearchQuery) -> Unit)? = null,
onRenameView: (SavedViewId, String) -> Unit = { _, _ -> },
onDeleteView: (SavedViewId) -> Unit = {},

// WorkspaceViewModel replaces search(String) at the app boundary:
fun search(query: SearchQuery)
```

- [ ] **Step 1: Write the instrumented test (compile-verified now,
  executes at Task 13)**

`SearchSurfaceSavedViewsInstrumentedTest.kt` — `createComposeRule`
(`.v2`), `OpenTasksTheme`, fixture `SavedView`s; asserts: chips render
when the query is blank (tags `saved-view-chip-<id>`), tapping a chip
sets the field text to the view's query text (assert via
`onNodeWithTag("workspace-search-query")` text), the save affordance
(tag `save-search`) appears only when `onSaveView != null` and the
query is non-blank, and saving immediately after text entry through the name
dialog (tags `save-search-name`, `save-search-confirm`) invokes `onSaveView`
with both the typed name and exact undebounced query text. Give one fixture
query non-empty `projectIds`/`tagIds`; after tapping its chip and advancing
the test clock by 150 ms, the captured `onQueryChange` value must equal that
whole `SearchQuery`, not `SearchQuery(text)`.

- [ ] **Step 2: Verify it fails to compile, then implement**

Run: `./gradlew :app:compileDebugAndroidTestKotlin` — FAIL (unresolved
params). Implement: when `query.isBlank() && savedViews.isNotEmpty()`,
a `FlowRow` of `AssistChip`s above the results list, each with a
long-press-free overflow (a trailing `IconButton` per chip opening a
`DropdownMenu` with Rename/Delete — 48 dp, content descriptions
"Rename <name>" / "Delete <name>", the workflow-editor precedent);
store `queryText` and a Bundle-safe `selectedSavedViewId: String?` in
`rememberSaveable`. Derive the current query from that saved view only while
its text still equals `queryText`; direct typing clears the selected id and
therefore creates `SearchQuery(queryText)`. Tapping a chip sets both values,
and the existing 150 ms `LaunchedEffect(currentQuery)` dispatches the entire
query. Save invokes `onSaveView(name, currentQuery)` directly, so it cannot
race the debounce. Save: a trailing icon in the search field row (enabled
when non-blank and `savedViews.size < 20`; disable with the
`saved_search_limit` supporting text when the list is full); name
dialog with `stringResource` copy. Wire in `OpenTasksApp:1270-1291`:
`savedViews = snapshot.savedViews`; `onSaveView` dispatches
`CreateSavedView(SavedViewId.new(), name, query)`, `onRenameView` dispatches
`RenameSavedView(id, name)`, and `onDeleteView` dispatches
`DeleteSavedView(id)`, each through `viewModel.execute`.
`WorkspaceViewModel.search(query)` passes that exact query to
`repository.search`; no parent query mirror is introduced.

- [ ] **Step 3: Verify**

Run: `./gradlew :app:compileDebugAndroidTestKotlin` — compiles. Run the
CI gate — green. Undo of saved-view commands already proven in Task 1.

- [ ] **Step 4: Commit**

```bash
git add app
git commit -m "feat: add saved search chips to the search surface" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Bulk multi-select and composite commands

**Files:**

- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`
- Modify:
  `core/designsystem/src/main/kotlin/app/opentasks/core/designsystem/Components.kt:85-93`
  (`TaskRow` gains `onLongPress`)
- Modify: `core/designsystem/src/main/res/values/strings.xml`
  (selection accessibility action)
- Modify: `feature/tasks/src/main/kotlin/app/opentasks/feature/tasks/TasksScreen.kt`
- Modify: `feature/tasks/src/main/res/values/strings.xml` (selection-bar copy)
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Test: `app/src/test/kotlin/app/opentasks/WorkspaceBulkSelectionStateTest.kt`
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryBulkCommandTest.kt`
- Test (instrumented, compile-verified):
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomBulkCommandInstrumentedTest.kt`
- Test (instrumented, compile-verified):
  `feature/tasks/src/androidTest/kotlin/app/opentasks/feature/tasks/TasksScreenBulkSelectionInstrumentedTest.kt`

**Interfaces:**

- Consumes: single-task handlers (`completeTask`
  `RoomVaultRepository.kt:1089`, `changeTaskStatus` `:1128`,
  `deleteTask` `:1346`, `updateTaskDetails` `:1485`, `setTaskTag`
  `:1859`), `RestoreTaskStatus`/`RestoreTask` undo shapes,
  `SavedStateHandle` in `WorkspaceViewModel` (`:75-82`).
- Produces:

```kotlin
// DomainCommand gains:
data class CompleteTasks(
    val taskIds: List<TaskId>,
    val acknowledgeBlocked: Boolean = false,
    val completedAt: Instant = Instant.now(),
) : DomainCommand

data class RescheduleTasks(
    val taskIds: List<TaskId>,
    val due: ZonedMoment?,          // null clears the due moment
) : DomainCommand

data class MoveTasksToProject(
    val taskIds: List<TaskId>,
    val projectId: ProjectId?,      // null moves to Inbox
) : DomainCommand

data class SetTasksTag(
    val taskIds: List<TaskId>,
    val tagId: TagId,
    val present: Boolean,
) : DomainCommand

data class DeleteTasks(
    val taskIds: List<TaskId>,
    val deletedAt: Instant = Instant.now(),
) : DomainCommand

/** Repository-produced batch undo. Commands are stored in reverse
 *  application order and replayed in list order inside one transaction.
 *  Never constructed by UI code. */
data class UndoBatch(val commands: List<DomainCommand>) : DomainCommand

// RejectionReason gains: EMPTY_BULK_SELECTION, BULK_SELECTION_TOO_LARGE
// Both repository companions gain: MAX_BULK_TASKS = 200

// WorkspaceViewModel exposes domain ids but stores List<String> under
// SavedStateHandle key "bulkSelection":
val bulkSelection: StateFlow<Set<TaskId>>
fun toggleBulkSelection(taskId: TaskId)
fun clearBulkSelection()
fun executeBulk(command: DomainCommand)
fun completeBulkSelection()
fun confirmBlockedBulkCompletion()
fun dismissBlockedBulkCompletion()

// TaskRow gains: onLongPress: (() -> Unit)? = null  (combinedClickable
// only when non-null, so existing call sites are untouched)
// TasksScreen gains:
selectedBulkIds: Set<TaskId> = emptySet(),
onToggleBulkSelection: (TaskId) -> Unit = {},
onClearBulkSelection: () -> Unit = {},
onBulkComplete: () -> Unit = {},
onBulkReschedule: (LocalDate) -> Unit = {},
onBulkMoveToProject: (ProjectId?) -> Unit = {},
onBulkSetTag: (TagId, Boolean) -> Unit = {},
onBulkDelete: () -> Unit = {},
```

Semantics, pinned: each composite first removes duplicate ids while
preserving encounter order, then validates the resulting list is non-empty
and ≤ 200. It resolves every existing task, skipping ids that disappeared;
zero resolved tasks rejects `NOT_FOUND`. Before the first write, it runs the
same operation-specific validators used by the single-task path over the
whole resolved set: completion status and blocked acknowledgement,
reschedule/recurrence validity, active destination project plus status
mapping, tag existence/capacity, or deletion eligibility. Any validation
failure returns the same rejection as the single-task command with no record,
activity, relation, revision, or journal change. Only after full preflight
does it apply the planned changes in one transaction; recurring completion
still creates occurrences with `RecurringTaskPlanner`.

Room relies on the outer `withTransaction`; InMemory builds one final
snapshot/relation/activity value and calls `publish` once, so observers never
see a partial batch. An unexpected apply-time rejection is an internal
invariant failure thrown across the transaction boundary, ensuring rollback;
tests must never exercise that path as normal validation.
The result message reports the applied count ("5 tasks completed");
`undo` is `UndoBatch` of the exact captured inverses
(`RestoreTaskStatus`, `RestoreTask`, an `UpdateTask` reconstructed via
the existing `Task.toUpdateCommand` shape for reschedule/move, or the
flipped `SetTaskTag` over only the ids that actually changed).
`RescheduleTasks` writes `due` and bumps `nextRevision` per task,
touching neither reminders nor recurrence anchors; only `due` and the task
revision change. Capture inverses in application order,
store them with `asReversed()`, and have `UndoBatch` execute that stored order
in one transaction with no further undo. Undo preflights every inverse before
its first write under the same all-or-nothing rule.

- [ ] **Step 1: Write the failing repository tests**

`InMemoryBulkCommandTest.kt` — the load-bearing cases:

```kotlin
@Test
fun completeTasksAppliesAllAndUndoBatchRestoresEveryStatus() = runBlocking {
    withTimeout(5_000) {
        val ids = repository.currentWorkspace().tasks
            .filterNot { it.isCompleted || it.isBlocked }
            .take(2).map { it.id }
        val result = repository.execute(
            DomainCommand.CompleteTasks(ids),
        ) as CommandResult.Success
        assertTrue(
            repository.currentWorkspace().tasks
                .filter { it.id in ids }.all { it.isCompleted },
        )
        repository.execute(result.undo!!)
        assertTrue(
            repository.currentWorkspace().tasks
                .filter { it.id in ids }.none { it.isCompleted },
        )
    }
}

@Test
fun missingIdsAreSkippedNotFatal() = runBlocking {
    withTimeout(5_000) {
        val real = repository.currentWorkspace().tasks
            .first { !it.isCompleted && !it.isBlocked }.id
        val result = repository.execute(
            DomainCommand.CompleteTasks(listOf(real, TaskId("missing-task"))),
        ) as CommandResult.Success
        assertTrue(repository.currentWorkspace().tasks.single { it.id == real }.isCompleted)
        assertEquals("1 task completed", result.message)
    }
}

@Test
fun twoHundredAndOneIdsAreRejectedWithoutMutation() = runBlocking {
    withTimeout(5_000) {
        val before = repository.currentWorkspace()
        val result = repository.execute(
            DomainCommand.DeleteTasks(List(201) { TaskId("task-$it") }),
        ) as CommandResult.Rejected
        assertEquals(RejectionReason.BULK_SELECTION_TOO_LARGE, result.reason)
        assertEquals(before, repository.currentWorkspace())
    }
}

@Test
fun setTasksTagUndoFlipsOnlyIdsThatChanged() = runBlocking {
    withTimeout(5_000) {
        val snapshot = repository.currentWorkspace()
        val tag = snapshot.tags.first()
        val ids = snapshot.tasks.filter { !it.isCompleted }.take(2).map { it.id }
        repository.execute(DomainCommand.SetTaskTag(ids[0], tag.id, present = true))
        repository.execute(DomainCommand.SetTaskTag(ids[1], tag.id, present = false))

        val result = repository.execute(
            DomainCommand.SetTasksTag(ids, tag.id, present = true),
        ) as CommandResult.Success
        repository.execute(result.undo!!)

        val restored = repository.currentWorkspace().tasks.associateBy { it.id }
        assertTrue(tag.id in restored.getValue(ids[0]).tagIds)
        assertFalse(tag.id in restored.getValue(ids[1]).tagIds)
    }
}
```

Add five further named tests to this file with these exact assertions:
`blockedCompletionRejectsWholeBatchUntilAcknowledged` snapshots before the
call, combines one blocked and one unblocked task, proves the first call is
`BLOCKED_TASK_WARNING_REQUIRED` with identical snapshot/journal size, then
proves `acknowledgeBlocked = true` completes both; `rescheduleTasksAndUndo`
proves due-only revisions and exact restoration; `moveTasksAndUndo` proves
project/status mapping and restoration; `deleteTasksAndUndo` proves Bin plus
restoration; `recurringCompletionAndUndo` proves every generated occurrence
is captured and removed by the reversed batch undo.

`RoomBulkCommandInstrumentedTest` contains three explicit cases: successful
completion plus Undo; blocked completion rejection with identical table and
journal counts; and tag change plus Undo. For the successful batch, query only
the entries appended by that execute and assert one generation, consecutive
sequences starting at zero, and `entries.size == operationIds.toSet().size`.
Operation IDs are intentionally unique per journal row.
`WorkspaceBulkSelectionStateTest` follows `WorkspaceSelectionStateTest`: add
two ids, reconstruct from the raw `List<String>` in a fresh
`SavedStateHandle`, prove both domain ids restore, then clear and prove the raw
list is empty.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*BulkCommand*"`
Expected: FAIL — commands unresolved.

- [ ] **Step 3: Implement commands, then UI**

Implement the normalise → resolve → full-preflight → apply → reverse-inverses
pipeline above in both engines; the in-memory handler emits exactly one
`publish`. UI: `TaskRow` adds
`combinedClickable` (long-press) only when `onLongPress != null` and
keeps the custom accessibility action list — add a "Select <title>"
custom action using `task_select_action` from the design-system resource file
so selection is reachable without long-press.
`TasksScreen`: when `selectedBulkIds` is non-empty the list header
swaps for a selection bar (count, clear (`bulk-clear`), complete
(`bulk-complete`), reschedule (`bulk-reschedule`, opens a
`DatePickerDialog` → `LocalDate`), move (`bulk-move`, menu of active
projects + Inbox), tag (`bulk-tag`, menu of tags with present/absent
toggle), bin (`bulk-delete`)); rows render a leading `Checkbox` in
selection mode and row tap toggles instead of opening. `:app` maps
`onBulkReschedule(date)` to
`RescheduleTasks(ids, ZonedMoment(date.atTime(17, 0).atZone(zone).toInstant(), zone.id))`
— 17:00, the Task 2 date-only convention, one shared constant in
`:app`. `WorkspaceViewModel` stores `bulkSelection` as `List<String>` and
maps it to the exposed set. `executeBulk` uses Task 6's result callback:
success clears selection after the normal snackbar/Undo event; rejection
keeps selection. `completeBulkSelection` retains the selected ids when it
receives `BLOCKED_TASK_WARNING_REQUIRED` and exposes the existing-style
confirmation dialog; confirm retries
`CompleteTasks(selectedIds, acknowledgeBlocked = true)`, while dismiss leaves
selection intact. No other failure clears the user's selection.
All new selection-bar copy lives in the feature/tasks resource file.
`TasksScreenBulkSelectionInstrumentedTest` long-presses one row, asserts the
selection bar/count and checked row, taps a second row to select it without
opening, invokes Complete, and asserts the callback once.

- [ ] **Step 4: Verify**

`./gradlew :core:data:testDebugUnitTest` green; CI gate green;
`:app:testDebugUnitTest --tests "*WorkspaceBulkSelectionState*"`,
`:feature:tasks:compileDebugAndroidTestKotlin`, and
`:core:data:compileDebugAndroidTestKotlin` compile.

- [ ] **Step 5: Commit**

```bash
git add core/domain core/data core/designsystem feature/tasks app
git commit -m "feat: add bulk multi-select with composite commands" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: Weekly review

**Files:**

- Create: `core/model/src/main/kotlin/app/opentasks/core/model/ReviewModels.kt`
  (`ReviewQueue` data only; feature modules may consume it)
- Create: `core/domain/src/main/kotlin/app/opentasks/core/domain/ReviewQueue.kt`
  (`buildReviewQueue` pure builder)
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
  (`MarkReviewed` command)
- Modify: `core/model/src/main/kotlin/app/opentasks/core/model/Records.kt:217-230`
  (`ActivityKind` gains `REVIEWED` — stored as a string column, no
  schema change; `BackupMutationCodec`'s ACTIVITY_ENTRY arm already
  validates kind as bounded text rather than an enum whitelist, so no
  codec change is required)
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
  and `InMemoryVaultRepository.kt` (dispatch arm → `recordActivity`)
- Create: `feature/more/src/main/kotlin/app/opentasks/feature/more/ReviewScreen.kt`
- Modify: `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`
  (`onOpenReview` row)
- Modify: `feature/more/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
  (`ReviewRoute` + entry)
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Test: `app/src/test/kotlin/app/opentasks/WorkspaceReviewProgressStateTest.kt`
- Test: `core/domain/src/test/kotlin/app/opentasks/core/domain/ReviewQueueTest.kt`
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryMarkReviewedTest.kt`
- Test (instrumented, compile-verified):
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/ReviewScreenInstrumentedTest.kt`

**Interfaces:**

- Consumes: `Revision.wallTimeMillis` (`Records.kt:25-29` — note the
  exact field name), `ActivityEntry.createdAt`, `recordActivity`
  (private helper in both repositories), `ArchiveProject`,
  `CompleteTask`, `DeleteTask`, Task 2's `parseNaturalDate` is NOT used
  here (review reschedule uses a date picker; keep the surface small),
  Navigation 3 route pattern (`OpenTasksApp.kt:146-162`), the
  `MoreScreen` deep-link latch precedent (`MoreScreen.kt:100-103`).
- Produces:

```kotlin
// core:model/ReviewModels.kt — data only
data class ReviewQueue(
    val overdue: List<Task>,
    val stale: List<Task>,
    val unscheduled: List<Task>,
    val projects: List<Project>,
)

// core:domain/ReviewQueue.kt — pure builder
fun buildReviewQueue(
    snapshot: WorkspaceSnapshot,
    now: Instant,
    staleAfter: Duration = Duration.ofDays(14),
): ReviewQueue

// DomainCommand gains:
data class MarkReviewed(
    val taskId: TaskId? = null,
    val projectId: ProjectId? = null,
    val reviewedAt: Instant = Instant.now(),
) : DomainCommand   // XOR owner, Success("Marked as reviewed"), no undo

// ReviewScreen — stateless feature composable:
fun ReviewScreen(
    queue: ReviewQueue,
    projectNames: Map<ProjectId, String>,
    reviewedTaskIds: Set<TaskId>,
    reviewedProjectIds: Set<ProjectId>,
    actionPending: Boolean,
    onBack: () -> Unit,
    onCompleteTask: (TaskId, Boolean) -> Unit,
    onRescheduleTask: (TaskId, LocalDate) -> Unit,
    onKeepTask: (TaskId) -> Unit,
    onBinTask: (TaskId) -> Unit,
    onKeepProject: (ProjectId) -> Unit,
    onArchiveProject: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
)
```

Queue rules, pinned: open = `deletedAt == null && !isCompleted`.
Overdue = open, `due != null`, `due.instant < now`. Stale = open, not
overdue, last-touch older than `staleAfter`, where last-touch =
`max(revision.wallTimeMillis, latest activity entry createdAt for the
task)`. Unscheduled = open, `start == null && due == null`, not
already stale (each task appears once: overdue > stale > unscheduled).
Within-section ordering is deterministic: overdue by due instant/title/id;
stale by oldest last-touch/title/id; unscheduled by title/id; projects by
case-insensitive name/id. Projects = `archivedAt == null`. `MarkReviewed`
writes a `REVIEWED` activity entry (body `"Reviewed"`) through
`recordActivity` — that is what resets staleness; no new persistence.

- [ ] **Step 1: Write the failing queue test**

`ReviewQueueTest.kt` — construct `WorkspaceSnapshot` values directly with
small private `taskFixture`, `projectFixture`, and `activityFixture` helpers in the
test file; cases: overdue task appears only in overdue even
if also stale; fresh activity entry rescues a stale-by-revision task;
unscheduled excludes stale-listed and completed tasks; binned tasks
appear nowhere; archived projects appear nowhere; boundary — exactly
14 days old is NOT yet stale (`staleAfter` is exclusive).

- [ ] **Step 2: Run to verify failure, implement queue + command**

Run: `./gradlew :core:domain:testDebugUnitTest --tests "*ReviewQueue*"`
— FAIL, then implement. `MarkReviewed`: dispatch arms in both
repositories validating XOR ownership (reject `NOT_FOUND` when neither
or both set, when a task is missing/binned, or when a project is
missing/archived) then `recordActivity(taskId, projectId,
ActivityKind.REVIEWED, "Reviewed", reviewedAt)`;
`InMemoryMarkReviewedTest` proves the entry lands, staleness resets
through `buildReviewQueue`, the 500-entry trim still holds, and every invalid
owner case rejects without mutation.

- [ ] **Step 3: Build the screen and route**

`WorkspaceReviewProgressState` stores reviewed task/project ids as two
`List<String>` values in `SavedStateHandle` and exposes reconstructed domain
sets plus `actionPending`. `startReview()` clears both lists before opening a
new review; `finishReview()` clears them on Back or all-done, and both reset
the transient pending flag. Process death
while the route is open preserves progress. Its JVM test proves restoration,
then proves finish and a later start each reset both lists. `ReviewScreen`
remains stateless: sections appear in queue order and the current card is the
first queue id absent from the corresponding supplied reviewed set, never a
numeric index into a live list.
Task cards show title/project/due with four 48 dp actions (tags
`review-complete`, `review-reschedule` (DatePickerDialog),
`review-keep`, `review-bin`), project cards show milestone summary
counts with `review-keep-project` / `review-archive-project`. Disable the
current card and Back action while `actionPending`, so a late success callback
cannot repopulate progress after `finishReview()`. A blocked Complete first shows a local
confirmation and passes `acknowledgeBlocked = true` only after confirmation.
An all-done `EmptyState` closes with `onBack`. All copy via
`stringResource` in the more module. Route:
`@Serializable data object ReviewRoute : WorkspaceRoute` beside the
five existing routes (NOT added to `destinations` — no nav-bar item);
`entry<ReviewRoute>` builds
`buildReviewQueue(snapshot, Instant.now())` and maps each lambda through
`viewModel.executeReview(command, taskId, projectId)`. That method sets
pending, uses Task 6's result callback, adds the stable owner id only for
`CommandResult.Success`, and always clears pending; rejection leaves the card
visible while the normal message renders. `MoreScreen.onOpenReview` calls
`viewModel.startReview()` before navigation. Review Back and the all-done
action both call `viewModel.finishReview()` before navigating to `MoreRoute`.
Commands are
`CompleteTask(id, acknowledgeBlocked)`,
`RescheduleTasks(listOf(id), dueAt17Local)`, `MarkReviewed`, `DeleteTask`,
and `ArchiveProject`; back → `navigate(MoreRoute)`. `MoreScreen` gains
`onOpenReview: () -> Unit = {}` and a `DestinationRow` (tag
`open-review`, icon `Icons.Rounded.Checklist`, strings
`review_title`/`review_open`).

- [ ] **Step 4: Verify**

`./gradlew :core:domain:testDebugUnitTest :core:data:testDebugUnitTest`
green; CI gate green; `ReviewScreenInstrumentedTest` (walks one task
through Keep, updates the supplied reviewed-id state and live queue with the
first task removed, and asserts the immediately following task—not the one
after it—renders; a second case leaves reviewed ids unchanged and proves the
same card remains; a third reaches all-done and asserts the finish callback)
compiles via `:feature:more:compileDebugAndroidTestKotlin`.

- [ ] **Step 5: Commit**

```bash
git add core/model core/domain core/data feature/more app
git commit -m "feat: add guided weekly review" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: Markdown project export

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/export/ProjectMarkdownWriter.kt`
- Modify: `app/src/main/kotlin/app/opentasks/VaultTransferViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/BackupRecoveryScreen.kt`
- Modify: `feature/more/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/opentasks/di/AppModule.kt`
- Modify: `app/src/test/kotlin/app/opentasks/VaultTransferViewModelTest.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/export/ProjectMarkdownWriterTest.kt`

**Interfaces:**

- Consumes: `WorkspaceCsvWriter`'s streaming shape
  (`WorkspaceCsvWriter.kt:31-33` —
  `write(table: CsvTable, snapshot: WorkspaceSnapshot, out: Appendable)`, pure JVM,
  UK formats, moments rendered in their stored zone), the CSV batch machine in
  `VaultTransferViewModel` (`operation` mutex `:67`, request `Channel`,
  `NonCancellable` finally `:302-322`, `deletePartialDocument`
  `:424-430`), the `CsvExportTable` feature-twin precedent
  (`BackupRecoveryScreen.kt:108-125`), workflow-status rank ordering
  (`ORDER BY rank`), `Bin` exclusion (`deletedAt == null`).
- Produces:

```kotlin
// ProjectMarkdownWriter.kt — pure JVM, no android.* imports or unused zone
class ProjectMarkdownWriter {
    fun write(projectId: ProjectId, snapshot: WorkspaceSnapshot, out: Appendable)
}

// VaultTransferViewModel gains:
val markdownExportInProgress: StateFlow<Boolean>
val markdownExportOutcome: StateFlow<MarkdownExportOutcome?>   // feature-side type
val markdownCreateDocumentRequests: Channel<String>            // suggested file name
fun beginMarkdownExport(projectId: ProjectId)
fun onMarkdownDocumentSelected(uri: Uri?)
fun dismissMarkdownExportOutcome()

// BackupRecoveryScreen (feature side):
sealed interface MarkdownExportOutcome {
    data object Completed : MarkdownExportOutcome
    data class Failed(val reason: String) : MarkdownExportOutcome
}
```

Document shape, pinned by the test: `# <project name>`; the summary
paragraph when non-empty; `Due <d MMMM yyyy>` when the project has a
due date; `## Milestones` with `- [x]/- [ ] <name> — <due>` rows (only
when milestones exist); then one `## <status name>` section per
non-archived workflow status of the project in rank order that has
tasks, each task as `- [x]/- [ ] <title>` (completed ⇒ `[x]`), with
` — due <d MMMM yyyy HH:mm>` in the moment's stored zone when due is
set, escaped `#tag` suffixes for tag names, and checklist items nested two
spaces deeper as `- [x]/- [ ] <text>`. Binned tasks excluded. Trailing
newline, LF line endings (Markdown, not CSV — no CRLF). Before insertion,
every user-owned inline field normalises CR/LF to one space and backslash-
escapes CommonMark ASCII punctuation, including backslash, backtick, `#`,
`*`, `_`, brackets, angle brackets, and pipe. Formula neutralisation is
unnecessary because Markdown is not executable, but escaping prevents user
text from changing document structure.

- [ ] **Step 1: Write the failing writer test**

`ProjectMarkdownWriterTest.kt` — `WorkspaceCsvWriterTest` idiom (in-file
builders, exact whole-document string assertions on a `StringBuilder`):
a project with two statuses, a completed and an open task, a checklist,
tags, a milestone; asserts the exact document. Second test: binned task
absent; third: statuses with no tasks are omitted; fourth: project/status/
task/checklist/tag text containing `#`, `*`, a backtick, pipe, CR, and LF
renders as escaped single-line text (for example `# heading` becomes
`\# heading`) and cannot create a new Markdown block.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ProjectMarkdown*"`
Expected: FAIL.

- [ ] **Step 3: Implement writer + flow**

Writer per the pinned shape (reuse the UK formatters' patterns; keep
every helper private). ViewModel: reuse the exact locking, cancellation, and
partial-delete control flow of the single-document `.otvault` export machine,
not the multi-table CSV queue — `beginMarkdownExport`
`tryLock`s the shared `operation` mutex, captures the snapshot
synchronously, sends the suggested name
(`"open_tasks_" + project name lowercased with `Locale.ROOT`,
non-alphanumerics collapsed to `_`, trimmed, fallback `project`, capped at 80
base-name characters, + ".md"`); `onMarkdownDocumentSelected` writes via
`OutputStreamWriter(stream, Charsets.UTF_8).use`, with the exact
cancellation-rethrow and `NonCancellable`-finally shape of the CSV
write (`:297-322`), deleting the partial document on any non-success.
Launcher in `OpenTasksApp`:
`ActivityResultContracts.CreateDocument("text/markdown")` plus a pump
`LaunchedEffect`, the `:315-319` pattern. UI: a "Markdown export" row
in `BackupRecoveryScreen`'s export section (heading string
`markdown_export_heading`, action `markdown-export` tag) opening a
project-picker sheet (single-select list of active projects, tag
`markdown-export-project-<id>`; `BackupRecoveryScreen` gains
`projects: List<Project> = emptyList()`, threaded from `MoreScreen`'s
existing `projects` param); outcome row + dismiss using CSV's exact states.
`AppModule` provides `ProjectMarkdownWriter()`
beside the CSV provider.

- [ ] **Step 4: Verify**

`./gradlew :core:data:testDebugUnitTest` green; CI gate green;
`:feature:more:compileDebugAndroidTestKotlin` compiles. The
`VaultTransferViewModel` unit-test ceiling applies (no `Uri` under the
stub jar): cover `beginMarkdownExport` when locked (no-op) and the
null-uri cancel path, the `VaultTransferViewModelTest` precedent.

- [ ] **Step 5: Commit**

```bash
git add core/data feature/more app
git commit -m "feat: export a project as markdown" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 11: CSV import (own schema, create-only)

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/export/TasksCsvParser.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/export/WorkspaceCsvWriter.kt`
  (make `TASKS_HEADER` internal and make formula neutralisation reversible)
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`
  and `InMemoryVaultRepository.kt`
- Modify: `core/data/src/main/kotlin/app/opentasks/core/data/db/VaultDatabase.kt`
  (normal-path exact hard-delete/read-back helpers used only by import Undo)
- Modify: `app/src/main/kotlin/app/opentasks/VaultTransferViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/BackupRecoveryScreen.kt`
- Modify: `feature/more/src/main/res/values/strings.xml`
- Modify: `app/src/test/kotlin/app/opentasks/VaultTransferViewModelTest.kt`
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/export/TasksCsvParserTest.kt`
- Modify:
  `core/data/src/test/kotlin/app/opentasks/core/data/export/WorkspaceCsvWriterTest.kt`
- Test: `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryImportTasksTest.kt`
- Test (instrumented, compile-verified):
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomImportTasksInstrumentedTest.kt`

**Interfaces:**

- Consumes: `WorkspaceCsvWriter.TASKS_HEADER` (the 14 columns,
  `WorkspaceCsvWriter.kt:194-219` — make the header list `internal`
  rather than copying it), the neutralisation trigger set
  `= + - @ \t` with apostrophe prefix (`:180-195`),
  `WorkflowStatus.defaults(projectId)` (`Records.kt:75-90`),
  `CreateProject`/`CreateAndAssignTag` validation bounds,
  `ISO_OFFSET_DATE_TIME` moments in stored zones.
- Produces:

```kotlin
// ImportedTaskRow is declared in core:domain beside ImportTasks because a
// DomainCommand cannot carry a core:data type.
data class ImportedTaskRow(
    val sourceRowNumber: Int,
    val title: String,
    val projectName: String?,
    val statusName: String?,
    val priority: Priority,
    val start: ZonedMoment?,
    val due: ZonedMoment?,
    val completedAt: Instant?,
    val estimateMinutes: Long?,
    val tagNames: List<String>,
    val description: String,
)

// CsvParseResult and the parser remain owned by core:data, but are public
// because :app consumes them across the Gradle module boundary.
const val MAX_TASKS_CSV_BYTES: Int = 5 * 1024 * 1024

sealed interface CsvParseResult {
    data class Parsed(val rows: List<ImportedTaskRow>) : CsvParseResult
    data class Malformed(val rowNumber: Int, val reason: String) : CsvParseResult
}

fun parseTasksCsv(bytes: ByteArray): CsvParseResult

data class CsvImportPreviewSummary(
    val taskCount: Int,
    val newProjectCount: Int,
    val newTagCount: Int,
)

sealed interface CsvImportPreviewResult {
    data class Ready(val summary: CsvImportPreviewSummary) : CsvImportPreviewResult
    data class Invalid(
        val rowNumber: Int?,
        val reason: RejectionReason,
        val message: String,
    ) : CsvImportPreviewResult
}

fun previewTasksImport(
    rows: List<ImportedTaskRow>,
    snapshot: WorkspaceSnapshot,
): CsvImportPreviewResult

// DomainCommand gains:
data class ImportTasks(val rows: List<ImportedTaskRow>) : DomainCommand

data class ImportedTaskReceipt(
    val taskId: TaskId,
    val expectedRevision: Revision,
    val expectedTagIds: Set<TagId>,
    val activityEntryId: String,
)

data class ImportedProjectReceipt(
    val project: Project,
    val statuses: List<WorkflowStatus>,
    val activityEntryId: String,
)

data class ImportedTagReceipt(val tag: Tag)

data class ImportReceipt(
    val tasks: List<ImportedTaskReceipt>,
    val projects: List<ImportedProjectReceipt>,
    val tags: List<ImportedTagReceipt>,
)

/** Repository-produced Undo only; never constructed by UI code. */
data class RemoveImportedRecords(val receipt: ImportReceipt) : DomainCommand

// RejectionReason gains: IMPORT_TOO_LARGE, IMPORT_EMPTY,
// IMPORT_NAME_COLLISION, IMPORT_STATUS_CONFLICT,
// IMPORT_BACKUP_LIMIT_EXCEEDED, IMPORT_UNDO_CONFLICT
// Both repository companions gain: MAX_IMPORT_ROWS = 5_000

// feature/more, containing no core:domain type:
sealed interface CsvImportOutcome {
    data class Preview(
        val taskCount: Int,
        val newProjectCount: Int,
        val newTagCount: Int,
    ) : CsvImportOutcome
    data class Completed(val taskCount: Int) : CsvImportOutcome
    data class Failed(val rowNumber: Int?, val reason: String) : CsvImportOutcome
}

// VaultTransferViewModel app-internal bridge to WorkspaceViewModel:
val csvImportInProgress: StateFlow<Boolean>
val csvImportOutcome: StateFlow<CsvImportOutcome?>
val csvOpenDocumentRequests = Channel<Unit>(Channel.BUFFERED)
val csvImportCommitRequests = Channel<List<ImportedTaskRow>>(capacity = 1)
fun beginCsvImport()
fun onCsvDocumentSelected(uri: Uri?)
fun confirmCsvImport()
fun onCsvImportCommandResult(result: CommandResult)
fun cancelCsvImport()
fun dismissCsvImportOutcome()
internal fun handleCsvParseResult(result: CsvParseResult)
```

Parser rules, all fail-closed with the 1-based data-row number: refuse more
than 5 MiB, decode UTF-8 with `CodingErrorAction.REPORT`, and require the
header to equal `TASKS_HEADER` exactly. The state machine accepts RFC 4180
quoted fields and `""` escapes with CRLF or bare LF; a quote in an unquoted
field, text after a closing quote before comma/end-of-record, an unclosed
quote, or any row with other than 14 fields is malformed.

Expose `WorkspaceCsvWriter.TASKS_HEADER` as an `internal` companion member
(the companion itself cannot remain private); keep its other constants
private. Formula neutralisation becomes bijective without changing the column
schema:
when a cell has `k` leading apostrophes followed by `= + - @` or tab, export
`2k + 1` apostrophes; import only an odd apostrophe run followed by a trigger
and restores `(run - 1) / 2`. Thus `=SUM`, `'=SUM`, and `''=SUM` all
round-trip distinctly. Other cells are unchanged.

The existing `tags` cell keeps semicolon as its list separator but becomes
reversible: the writer escapes `\` as `\\` and `;` as `\;` inside each tag
name before joining; the parser splits only on unescaped semicolons and then
unescapes those two sequences. A dangling backslash or any other backslash
escape is malformed at that row. This changes no column or header and
preserves every tag name currently allowed by the domain. Export applies this
tag-list escape before whole-cell formula neutralisation; import reverses the
formula layer before parsing the tag list.

After reversal: title is non-blank and ≤ 240; description ≤ 20,000;
project name ≤ 120; status name ≤ 64; at most 50 unique trimmed tags, each ≤
64; priority is an enum name or blank (NONE); start/due parse with
`ISO_OFFSET_DATE_TIME` into `ZonedMoment(instant, offsetId)`; completed parses
with `ISO_INSTANT`; estimate is blank or a positive minute value accepted by
`Duration.ofMinutes`; display/id columns are syntactically consumed but not
trusted. Status resolution is deterministic: when `completedAt != null`, use
an exact resolved status only if it is semantically COMPLETED; otherwise use
the project's active COMPLETED default. When `completedAt == null`, use the
exact resolved non-COMPLETED status or the active BACKLOG default; an exact
COMPLETED status without a completion instant rejects
`IMPORT_STATUS_CONFLICT` rather than creating inconsistent task state. Rows
are bounded 1..5,000.

`ImportTasks` first builds a complete immutable import plan with fresh ids.
`previewTasksImport` and the internal repository-plan builder delegate to one
private pure name/status resolution pass in `TasksCsvParser.kt`; do not copy
the collision or fallback rules into `:app` or either repository.
Resolve active projects and tags by trimmed exact name against both the
starting snapshot and records already planned by earlier rows. If there is no exact
match but the existing case-insensitive uniqueness rule finds a collision,
reject `IMPORT_NAME_COLLISION` rather than bypassing product invariants;
otherwise create the project plus its five defaults or the tag. Active-status
resolution is exact within the selected project, with the same collision
rejection before the completion/BACKLOG fallback above. Preflight every row
and construct the post-import structured backup record set; encoding it through
`BackupSnapshotCodec` must satisfy the existing 100,000-record and plaintext
byte bounds or the command rejects `IMPORT_BACKUP_LIMIT_EXCEEDED`. Hold the
encoded plaintext only for that size check and `fill(0)` it in `finally`.
Only then
do both engines commit tasks, relations, created shared records, activity,
and one ordered journal generation. Nothing merges; task ids are always
fresh. Change each repository's private `recordActivity` helper to return its
generated String id (existing callers ignore the return); import captures the
task/project creation ids in `ImportReceipt` rather than attempting to infer
them after the write.

Undo preflights the whole `ImportReceipt`: every imported task still has its
expected revision/tag links and only its import-created activity; it has no
new checklist, dependency, reminder, attachment, note, or time-entry child;
every created project/status/tag is unchanged and has no reference outside
the receipt. Any mismatch rejects `IMPORT_UNDO_CONFLICT` with no deletion.
Otherwise delete exact task-owned rows first, then created statuses/projects/
tags, producing journal DELETE rows in one transaction and no tombstones.
Imports never create attachments, notes, checklists, dependencies, reminders,
or time entries—the disclosed lossy boundary.

- [ ] **Step 1: Write the failing parser + round-trip tests**

`TasksCsvParserTest.kt`: quoted multi-line description round-trips; exporter
then parser distinctly restore `=SUM(A1)`, `'=SUM(A1)`, and `''=SUM(A1)`;
tag names `ops;urgent` and `path\name` round-trip distinctly, while an invalid
tag escape reports its row; wrong header → `Malformed(0, …)`; bad ISO due on
data row 3 →
`Malformed(3, …)`; malformed UTF-8, wrong field count, quote in an unquoted
field, trailing text after a quoted field, blank/overlong title, overlong
description/project/status/tag, 51 tags, invalid/overflowing estimate, and an
unclosed quote each return the exact offending row without partial rows. The
keystone round-trip builds a snapshot, writes TASKS to UTF-8 bytes, parses
those bytes, and compares title, description, project/status names,
start/due instant+offset, completion instant, priority, estimate, and tags for
every non-binned source task in order.

`InMemoryImportTasksTest.kt`: import creates tasks plus one new project with
five statuses and new tags; re-import duplicates tasks; Undo removes the
receipt and preserves all pre-existing records; 5,001 rows and a projected
backup beyond current bounds reject untouched. Add
`undoConflictPreservesEverything`: mutate one imported task/add a child, run
Undo, assert `IMPORT_UNDO_CONFLICT` and equality with the pre-Undo snapshot.
Add `caseOnlyNameCollisionRejectsBeforeWrite` for both project and tag, and
`completedStatusWithoutInstantRejectsBeforeWrite` with a source-row message.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*CsvParser*" --tests "*ImportTasks*"`
Expected: FAIL.

- [ ] **Step 3: Implement parser, commands, then flow**

Parser: implement the byte-bound/strict-decoder and field state machine above
(quote handling first, no regex for record grammar). Build the immutable
repository import plan, validate backup representability, then apply the
receipt-producing command and conflict-checked Undo exactly as specified.
ViewModel: `beginCsvImport()` sends `csvOpenDocumentRequests`; a dedicated
`OpenDocument` launcher in `OpenTasksApp` consumes that pump (do not reuse the
passphrase `.otvault` import channel/launcher). Its MIME array is
`arrayOf("text/csv", "text/comma-separated-values", "text/plain")` — pickers
tag CSVs inconsistently. On document, call
`stream.readNBytes(MAX_TASKS_CSV_BYTES + 1)`, pass those bounded bytes to the
strict parser, zero the raw `ByteArray` in `finally`, then pass its result to
the non-Android `handleCsvParseResult` transition and surface
`CsvImportOutcome.Preview(taskCount, newProjectCount, newTagCount)`
(computed by `previewTasksImport(rows, repository.observeWorkspace().value)`).
A collision produces `Failed` and releases the import lock before
preview; the same applies to a completed-status contradiction, using its
`sourceRowNumber`. The repository repeats both checks at commit. Retain only
parsed rows for preview. `confirmCsvImport()` sends
those rows through a bounded app-internal Channel collected by
`OpenTasksApp`; the collector calls Task 6's
`workspaceViewModel.execute(ImportTasks(rows), onResult)` so Success and its
repository-produced Undo use the normal snackbar path, then reports the
result back to `VaultTransferViewModel` for Completed/Failed UI state. Confirm
uses `trySend`; a full/closed channel becomes `Failed` and finishes the flow
instead of suspending with the shared transfer lock held.

Model the lock lifecycle explicitly as Idle → AwaitingDocument → Preview →
Committing. `beginCsvImport` alone `tryLock`s; confirm never re-locks. Null
URI, parse failure, cancel/dismiss, command result, and `onCleared` each call
one idempotent `finishCsvImport()` that clears parsed rows/preview references
and unlocks only if this flow owns the mutex. No raw byte/string buffer is
stored in ViewModel state.
Extend `VaultTransferViewModelTest` with state-machine tests that prove null
URI and Cancel return to Idle/unlock, Confirm emits exactly one bounded
commit request without a second lock attempt, and command Success/Rejected
clear retained rows and unlock exactly once. The tests reach Preview by
calling `handleCsvParseResult(Parsed(rows))` after `beginCsvImport`; no fake
`Uri`, mock, or test-only production branch is introduced.
UI: import row + preview dialog + outcome in `BackupRecoveryScreen`
(tags `csv-import`, `csv-import-preview`, `csv-import-confirm`,
`csv-import-cancel`, `csv-import-outcome`); copy states create-only,
what CSV does not carry, that named zone ids normalise to the exported UTC
offset, and that `.otvault` is the full-fidelity transfer (strings
`csv_import_*`).

- [ ] **Step 4: Verify**

`./gradlew :core:data:testDebugUnitTest` green (round-trip is the
contract pin); CI gate green. `RoomImportTasksInstrumentedTest` compiles and
contains successful import/Undo with exact table counts and one-generation
unique-operation-id journal assertions, plus conflict rejection with
identical table/journal counts.

- [ ] **Step 5: Commit**

```bash
git add core/domain core/data feature/more app
git commit -m "feat: import tasks from own-schema csv" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 12: Kanban board

**Files:**

- Create: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/BoardView.kt`
- Modify: `feature/projects/src/main/kotlin/app/opentasks/feature/projects/ProjectsScreen.kt`
- Create: `feature/projects/src/main/res/values/strings.xml` (module's
  first — the directory does not exist yet)
- Modify: `feature/projects/build.gradle.kts` (add the currently absent
  `testImplementation(libs.junit)`)
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
  (`onChangeTaskStatus`, saved board mode, and policy width threading)
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceViewModel.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WorkspaceLayoutPolicy.kt`
- Modify: `app/src/test/kotlin/app/opentasks/WorkspaceLayoutPolicyTest.kt`
- Test: `app/src/test/kotlin/app/opentasks/WorkspaceBoardViewStateTest.kt`
- Test: `feature/projects/src/test/kotlin/app/opentasks/feature/projects/BoardColumnsTest.kt`
- Test (instrumented, compile-verified):
  `feature/projects/src/androidTest/kotlin/app/opentasks/feature/projects/BoardViewInstrumentedTest.kt`

**Interfaces:**

- Consumes: `ProjectsScreen`/`ProjectWorkbench` signatures
  (`ProjectsScreen.kt:123-153`, `:352-378`), workflow chip strip to
  replace (`:609-645`), rank ordering (`sortedBy(WorkflowStatus::rank)`),
  `DomainCommand.ChangeTaskStatus` wiring precedent
  (`OpenTasksApp.kt:846`), the 48 dp IconButton + testTag +
  full-sentence contentDescription precedent
  (`ProjectsScreen.kt:1443-1466`).
- Produces:

```kotlin
// BoardView.kt — pure helpers + composable
data class BoardColumn(
    val status: WorkflowStatus,
    val tasks: List<Task>,      // open tasks in this status, priority desc then title
)

fun boardColumns(
    project: Project,
    statuses: List<WorkflowStatus>,
    tasks: List<Task>,
): List<BoardColumn>            // non-archived statuses of the project, rank order

fun moveTargets(columns: List<BoardColumn>, current: WorkflowStatusId): List<WorkflowStatus>

@Composable
fun BoardView(
    columns: List<BoardColumn>,
    columnWidth: Dp,
    onMoveTask: (TaskId, WorkflowStatusId) -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
)

// ProjectsScreen gains:
boardMode: Boolean = false,
boardColumnWidth: Dp = 272.dp,
onBoardModeChange: (Boolean) -> Unit = {},
onChangeTaskStatus: (TaskId, WorkflowStatusId) -> Unit = { _, _ -> },

// WorkspaceViewModel exposes a Set<ProjectId>, but its helper persists the
// board-mode project ids as List<String> under "projectBoardModeIds".
val boardModeProjectIds: StateFlow<Set<ProjectId>>
fun setBoardMode(projectId: ProjectId, enabled: Boolean)

// WorkspaceLayoutPolicy gains the only board-width decision point:
fun boardColumnWidthDp(layout: WorkspaceLayout): Int
```

- [ ] **Step 1: Write the failing pure-logic test**

`BoardColumnsTest.kt` (plain JVM): archived statuses excluded; other
projects' statuses and tasks excluded; only open tasks
(`deletedAt == null && !isCompleted`) appear, so completed and binned tasks
never render; ordering inside a column is priority desc then
case-insensitive title (the unscheduled-tray comparator,
`ScheduleScreen.kt:279-282`); `moveTargets` returns every column's
status except the current one, in board order.

- [ ] **Step 2: Run to verify failure, implement helpers**

Run: `./gradlew :feature:projects:testDebugUnitTest` — FAIL, then make
the two pure functions pass.

- [ ] **Step 3: Build tap-to-move first (the complete interaction)**

`WorkspaceLayoutPolicy.boardColumnWidthDp` returns 272 for a single-pane
layout, 296 when a detail pane is present, and 320 when a supporting pane is
present; extend its JVM test with all three values. `OpenTasksApp` calculates
the policy once and passes `.dp` into the stateless feature.

`BoardView`: horizontal `Row` in a `horizontalScroll`, one `columnWidth`
column per `BoardColumn` (`Surface` + header with status name and
count + `LazyColumn` of cards, column tag `board-column-<statusId>`).
Card: title + priority glyph + due, tap opens
(`onOpenTask`), trailing 48 dp `IconButton` (tag
`board-move-<taskId>`, description "Move <title> to another stage")
opening a `DropdownMenu` of `moveTargets` (item tag
`board-move-<taskId>-to-<statusId>`); the same moves are exposed as
`CustomAccessibilityAction`s on the card ("Move <title> to <status>").
Workbench: a list/board `SegmentedButton` toggle (tags
`workbench-view-list` / `workbench-view-board`) driven only by
`boardMode`/`onBoardModeChange`, board replacing the chip strip + task list
section. `WorkspaceBoardViewStateTest` creates a `SavedStateHandle`, enables
two project ids, reconstructs the state helper, and asserts both ids restore
as domain wrappers. Thread `onChangeTaskStatus` from `OpenTasksApp`
(`viewModel.execute(DomainCommand.ChangeTaskStatus(taskId, statusId))`
— completion warnings surface through the existing rejected-command
snackbar). All new copy in the module's new `strings.xml`. Commit this
step on its own: the board is shippable here.

```bash
git add feature/projects app
git commit -m "feat: add tap-to-move kanban board to the workbench" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

Stop here. Dispatch the task's independent review against the authority's
open-task, `SavedStateHandle`, layout-policy, and accessibility requirements;
fix and recommit every finding before Step 4. Drag code is forbidden until
this checkpoint is green.

- [ ] **Step 4: Layer drag on top**

Pointer input on cards: `detectDragGesturesAfterLongPress`; drag state
(dragged task id, accumulated offset, source column) hoisted in
`BoardView`; column drop targets via `onGloballyPositioned` bounds; the
hovered column renders a highlight (`surfaceVariant` border); release
over a different column → `onMoveTask`; edge auto-scroll nudges the
horizontal scroll state when the drag point nears either edge. Drag
never removes the menu path; a failed hit-test just snaps back. No
haptics/springs (deferred by spec).
`BoardViewInstrumentedTest`: tap-to-move via the menu asserts
`onMoveTask(taskId, targetStatus)`; a drag via
root-coordinate bounds asserts the same callback: fetch the card and target
column `boundsInRoot`, then call `onRoot().performTouchInput { down(cardCenter);
advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 1);
moveTo(targetCenter); up() }`. `longClick()` is not used because it releases
before the drag. The test is compile-verified here and executes at Task 13.

- [ ] **Step 5: Verify + commit**

`./gradlew :feature:projects:testDebugUnitTest` green; CI gate green;
`:feature:projects:compileDebugAndroidTestKotlin` compiles.

```bash
git add feature/projects
git commit -m "feat: add drag between board columns" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 13: Qualification and exit gates

**Files:**

- Modify: `docs/architecture.md`, `DESIGN.md`, `PRODUCT.md`,
  `docs/threat-model.md`, `CLAUDE.md`, `HANDOFF.md`
- Create: `docs/qualification/stage6-daily-flow.md`

**Steps:**

- [ ] **Step 1: Full connected gate on the sole disposable**

Use exactly the previously audited disposable `Fold8_Acceptance`; never boot,
clone, install to, or otherwise touch the protected `Pixel_10_Pro_Fold`.
Before boot, require empty `adb devices` output and no emulator process for
either name. Start `Fold8_Acceptance` with `-read-only -no-snapshot-load
-no-snapshot-save`, wait for boot completion, require it is the sole ADB
target, and verify `adb emu avd name` returns exactly `Fold8_Acceptance`
before running:

```bash
./gradlew :app:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest \
  :feature:tasks:connectedDebugAndroidTest :feature:projects:connectedDebugAndroidTest \
  :feature:schedule:connectedDebugAndroidTest :feature:more:connectedDebugAndroidTest
```

Expected: PASS including the new Room parity suites (saved views,
owner-checked focus stop, bulk, import), `SearchSurfaceSavedViewsInstrumentedTest`,
`ReviewScreenInstrumentedTest`, `BoardViewInstrumentedTest`, and the
existing Stage 4/5 pair of expected skips. Record exact counts. Shut down
only that verified disposable through `adb emu kill`; confirm empty ADB and
emulator-process audits before continuing.

- [ ] **Step 2: Repository, release, schema, fixture, hygiene gates**

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --rerun-tasks
./gradlew :app:assembleRelease --rerun-tasks
scripts/check-schema-drift.sh
node scripts/generate-stage2-backup-v1-fixtures.mjs
git diff --exit-code core/data/src/test/resources
node scripts/generate-stage3-drive-create-only-v1-fixtures.mjs
git diff --exit-code core/data/src/test/resources
node scripts/generate-stage4-attachment-v1-fixtures.mjs
git diff --exit-code core/data/src/test/resources
node scripts/generate-stage5-otvault-v1-fixtures.mjs
git diff --exit-code core/data/src/test/resources
bash scripts/verify-actions-workflow.sh
git diff --check
```

Expected: all pass at schema v9 with byte-identical backup fixtures (the stage
made no `.otvault` or backup-record format change; Task 11's CSV cell encoding
is pinned separately by JVM round-trip tests). Release inspection:
`auth/drive.appdata` sole
scope; new exported surface is exactly the bind-permission
`QuickAddTileService` and the two `MainActivity` intent filters; the
`FocusAlarmReceiver` is not exported; no debug activity.

- [ ] **Step 3: Privacy scans**

Using the implementation-base SHA recorded before Task 1, grep the exact
`<base>..HEAD` stage range for `Log.`/`println`/`Timber` (expect
zero), for shared-text or query-text reaching any log or intent extra
beyond the user's own share, and verify: focus notifications carry
only the two generic strings; widget state files carry no ids or
titles after lock engages (inspect the disposable's Glance preference state
after the locked-state device step and record that every title/id/completable
slot is absent); CSV import buffers are not retained
after parse; Markdown/CSV partial documents delete on failure (unit
suites).

- [ ] **Step 4: Device checklist (by hand on the disposable)**

Share text from a browser → prefilled Quick Add, then share a second value
while the sheet remains open and prove the title resets; select text →
"Open Tasks" → prefilled Quick Add; add the QS tile → tap → Quick Add
after unlock; widget: open one focus row and confirm the canonical task,
return, tap-complete a different row, verify counts update, then find that
completed task in the app and Reopen it; enable app lock → widget
conceals and taps route to unlock; start a 25/5 focus session →
boundary notification fires with generic text, then start another task's
timer before a stale focus alarm and prove it is not stopped; save a search
with project/tag filters, restart the process, apply it; `.otvault` export → import → saved views
survive; bulk-select 3 tasks → complete → Undo restores all; run the
weekly review across all four sections; Markdown-export a project and
open the file; CSV-export tasks, re-import the file, confirm the
preview counts and the created duplicates, then Undo; Kanban: drag a
card between columns, and move one with TalkBack enabled via the
accessibility action.

- [ ] **Step 5: Contract documents + closure**

`docs/architecture.md` (saved views live, composite commands,
`REVIEWED` activity kind, import boundary); `docs/threat-model.md`
(share-intent text handling, tile surface, CSV import parsing bounds,
Markdown plaintext note); `DESIGN.md` (board, review, selection bar,
focus banner, widget actions); `PRODUCT.md` (Stage 6 boundary);
`CLAUDE.md` (new bounds: 20 saved views, 200 bulk, 5_000 import rows,
14-day staleness, focus presets; the saved-view content-fingerprint
rule); `HANDOFF.md` (Stage 6 closure checkpoint). Write
`docs/qualification/stage6-daily-flow.md` with every gate result and
exact counts, no private identifiers.

```bash
git add docs/architecture.md docs/threat-model.md \
  docs/qualification/stage6-daily-flow.md DESIGN.md PRODUCT.md CLAUDE.md HANDOFF.md
git diff --cached --name-only
git diff --cached --check
git commit -m "docs: verify stage 6 daily-flow features" \
  -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

The staged-name audit must equal only the seven paths above. In particular it
must not contain
`docs/superpowers/plans/2026-07-30-stage-3-google-drive-backup-recovery-plan.md`,
`.kotlin/`, `artifacts/`, or any unrelated user-owned path.

---

## Spec coverage map

| Spec section | Tasks |
|---|---|
| Goal | 1–13 |
| Recorded scope rulings | Global constraints, 11, 12, 13 |
| Execution order | the task order above |
| Saved-search commands (dormant `saved_views`) | 1 |
| Natural-language dates in Quick Add | 2 |
| Share-sheet and text-selection intake | 3 |
| Quick Settings tile | 4 |
| Interactive Today widget | 5 |
| Focus cycles | 6 |
| Saved searches UI | 7 |
| Bulk multi-select | 8 |
| Weekly review | 9 |
| Markdown project export | 10 |
| CSV import | 11 |
| Kanban board | 12 |
| Data and formats (no Room/backup schema change; backup fixtures untouched) | 1, 11, 13 |
| Constraints carried forward | Global constraints; every task |
| Privacy and security | 3, 4, 5, 6, 13 |
| Error handling | 1, 2, 3, 5, 6, 8, 9, 10, 11, 12 |
| Testing and qualification | every task's gates, 13 |
| Main risk pre-mitigated (fallback-first board) | 12 |
| External and deferred work | 13 (recorded, unchanged) |
