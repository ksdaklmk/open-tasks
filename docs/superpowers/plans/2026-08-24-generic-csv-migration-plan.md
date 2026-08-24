# Generic CSV Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a discoverable, offline generic task-CSV mapper on Welcome and
More while retaining the strict Open Tasks CSV round trip and exact import
Undo.

**Architecture:** Extract the existing bounded RFC 4180 record reader, then
translate a reviewed generic table into the existing `ImportedTaskRow` and
`DomainCommand.ImportTasks` path. Keep mapping pure in `core:data`, mapping
choices and warnings Android-free in `core:model`, transient state and picker
effects in one app ViewModel, and the shared review UI stateless in
`feature:more`.

**Tech Stack:** Kotlin, Java time, coroutines, Jetpack Compose Material 3,
Android Storage Access Framework, Hilt ViewModel, Room, JUnit 4, Gradle.

**Spec:**
`docs/superpowers/specs/2026-08-24-generic-csv-migration-design.md`
(approved in commit `1dd8990`).

## Global Constraints

- Preserve the existing strict Open Tasks Tasks CSV parser and its exact
  14-column, all-or-nothing round-trip behavior.
- The generic path accepts only local UTF-8, comma-delimited RFC 4180 CSV with
  a header row; ignore one leading UTF-8 BOM only on the generic path.
- Enforce 5 MiB, 5,000 task rows, 100 source columns, 500 newly created
  projects, 1,000 newly created tags, 50 tags per task, and every existing
  field-length bound before repository dispatch.
- Support Title, Project/List, Status, Priority, Start, Due, Completion,
  Estimate, Tags/Labels, and Notes/Description. Title is required; every other
  field is optional.
- Invalid-title rows are skipped. Invalid optional values are omitted. Every
  skip, omission, case merge, semantic fallback, ambiguous-date decision, and
  inferred completion is visible before confirmation; never silently
  truncate.
- Status values map only to Backlog, In progress, Done, or explicit Ignore.
  Priority values map only to None, Low, Medium, High, Urgent, or explicit
  Ignore. Never guess numeric priority direction.
- Resolve ambiguous numeric dates with one import-wide day/month choice.
  Date-only starts use 09:00, date-only due dates use 17:00, and date-only
  completions use 17:00 in the captured device zone. Store the resulting exact
  offset.
- A Done row without a completion timestamp uses the confirmation instant and
  discloses that inference.
- Every confirmed import creates new records; do not match, merge, deduplicate,
  or persist an external ID.
- Dispatch reviewed rows only through `DomainCommand.ImportTasks`. Preserve its
  one transaction, ordered backup journal, receipt, and exact
  `RemoveImportedRecords` Undo in both repository engines.
- Keep source bytes, rows, and warnings memory-only. Do not persist the source
  URI permission, copy plaintext to disk, put task text in saved-instance
  state, log it, or send it over a network.
- Add no Room migration, backup/archive format, provider preset, account
  integration, network path, permission, dependency, signing change, version
  bump, or release artifact.
- New UI copy comes from `feature/more` string resources. Preserve 48 dp
  targets, logical keyboard/TalkBack order, visible focus, text/error semantics,
  RTL, 200% font, and compact/folding/expanded reachability.
- Follow RED-GREEN for each behavioral task. Run the named focused test before
  production changes and record the expected failure.
- Never run connected tests on the protected emulator. A connected run is
  permitted only when an explicitly safe disposable target is the sole
  selected device; otherwise compile Android tests and record the limit.
- Stage only the exact files named by each task. Preserve all unrelated
  modified, deleted, and untracked workspace entries.
- Work directly on `main`, as required by repository guidance, with one focused
  commit after each independently green task.

## File Map

- Create
  `core/data/src/main/kotlin/app/opentasks/core/data/export/CsvRecordReader.kt`:
  the one bounded UTF-8/RFC 4180 record reader shared by strict and generic
  import parsing.
- Modify
  `core/data/src/main/kotlin/app/opentasks/core/data/export/TasksCsvParser.kt`:
  retain strict parsing, resolve the additive semantic status hint, and consume
  the shared record reader.
- Create
  `core/model/src/main/kotlin/app/opentasks/core/model/TaskCsvMigration.kt`:
  Android-free field, mapping-choice, warning, and blocking-issue values shared
  by mapper, app state, and feature UI.
- Create
  `core/data/src/main/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapper.kt`:
  generic document parsing, header suggestions, full-field conversion,
  canonicalisation, warnings, and preview rows.
- Modify
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`:
  add the optional non-persisted status-semantic hint to `ImportedTaskRow`.
- Test
  `core/data/src/test/kotlin/app/opentasks/core/data/export/TasksCsvParserTest.kt`:
  pin unchanged strict behavior and semantic resolution.
- Create
  `core/data/src/test/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapperTest.kt`:
  prove generic structure, suggestions, conversion, warning accounting, and
  limits on the JVM.
- Modify
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryImportTasksTest.kt`:
  prove status-semantic selection, delayed rejection, transactionality, and
  Undo through the in-memory repository.
- Modify
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomImportTasksInstrumentedTest.kt`:
  real-Room parity for semantic selection and exact Undo.
- Create
  `feature/more/src/main/kotlin/app/opentasks/feature/more/TaskMigrationUiState.kt`:
  plain feature-facing review/load-failure state.
- Create
  `app/src/main/kotlin/app/opentasks/TaskMigrationViewModel.kt`:
  transient document intake, mapping edits, preview recomputation, and one-shot
  confirmation.
- Create
  `app/src/test/kotlin/app/opentasks/TaskMigrationViewModelTest.kt`:
  picker, buffer clearing, edit, confirmation, retry, cancel, and commit-state
  tests without a mocking library.
- Create
  `feature/more/src/main/kotlin/app/opentasks/feature/more/TaskMigrationScreen.kt`:
  the stateless combined mapping/review surface shared by Welcome and More.
- Create
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/TaskMigrationScreenInstrumentedTest.kt`:
  mapping controls, warnings, callbacks, semantics, responsive layout, and
  200% font coverage.
- Modify `feature/more/src/main/res/values/strings.xml`: all migration,
  warning, field, action, Welcome, and More copy.
- Modify
  `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`:
  add the top-level Import from another app row and callback only.
- Modify
  `feature/more/src/main/kotlin/app/opentasks/feature/more/WelcomeScreen.kt`:
  add the independent 48 dp migration action.
- Modify
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/BackupRecoveryScreenInstrumentedTest.kt`:
  prove the top-level More entry remains separate from Backup & recovery.
- Modify
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/WelcomeScreenInstrumentedTest.kt`:
  prove independent callbacks, ordering, compact/expanded layout, and 200%
  font reachability.
- Modify `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`: own the active
  workspace picker/review overlay, current-snapshot confirmation, command
  dispatch, Tasks navigation, and snackbar Undo path.
- Modify `app/src/main/kotlin/app/opentasks/WindowPostureMapper.kt`: choose the
  largest physical pane not crossed by an existing separating fold.
- Modify `app/src/test/kotlin/app/opentasks/WindowPostureMapperTest.kt`: pin
  vertical, horizontal, crossed, zero-width, and non-separating pane choices.
- Modify `app/src/main/kotlin/app/opentasks/MainActivity.kt`: own the NoVault
  picker/review, transient confirmed-row handoff across runtime activation,
  one-shot command dispatch, and post-success Tasks signal.
- Modify `docs/architecture.md`, `DESIGN.md`, `CLAUDE.md`, the approved spec,
  and `HANDOFF.md`: record the implemented split between strict round-trip and
  generic migration plus verification evidence.

---

### Task 1: Share the bounded CSV record reader and parse a generic table

**Files:**

- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/export/CsvRecordReader.kt`
- Create:
  `core/data/src/main/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapper.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/export/TasksCsvParser.kt:1-355`
- Create:
  `core/data/src/test/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapperTest.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/export/TasksCsvParserTest.kt`

**Interfaces:**

- Consumes: `MAX_TASKS_CSV_BYTES`, the current `parseRecords` state machine,
  and strict `CsvParseResult`.
- Produces:
  `GenericTasksCsvDocument`, `GenericTasksCsvRow`,
  `GenericTasksCsvParseResult`, and one internal `readCsvRecords` used by both
  parsers.

- [ ] **Step 1: Write the failing generic-structure tests**

Create `GenericTasksCsvMapperTest.kt` with the following first contract:

```kotlin
package app.opentasks.core.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericTasksCsvMapperTest {
    @Test
    fun genericParserAcceptsBomQuotesAndPadsShortRows() {
        val source = "\uFEFFTask Name,Project,Notes\r\n" +
            "One,Launch,\"Line one\nLine two\"\r\n" +
            "Two\r\n"

        val parsed = parseGenericTasksCsv(source.toByteArray())
            as GenericTasksCsvParseResult.Parsed

        assertEquals(listOf("Task Name", "Project", "Notes"), parsed.document.headers)
        assertEquals(1, parsed.document.rows[0].sourceRowNumber)
        assertEquals(listOf("One", "Launch", "Line one\nLine two"), parsed.document.rows[0].cells)
        assertEquals(listOf("Two", "", ""), parsed.document.rows[1].cells)
        val lf = parseGenericTasksCsv("Title\nThree\n".toByteArray())
            as GenericTasksCsvParseResult.Parsed
        assertEquals("Three", lf.document.rows.single().cells.single())
    }

    @Test
    fun genericParserRejectsARecordWiderThanItsHeader() {
        val result = parseGenericTasksCsv("Title,Project\r\nOne,A,extra\r\n".toByteArray())
            as GenericTasksCsvParseResult.Failed

        assertEquals(1, result.rowNumber)
        assertEquals(GenericTasksCsvFailure.ROW_WIDER_THAN_HEADER, result.reason)
    }

    @Test
    fun genericParserRejectsMissingHeaderBareCrAndUnclosedQuote() {
        assertEquals(
            GenericTasksCsvFailure.MISSING_HEADER,
            (parseGenericTasksCsv("\r\n".toByteArray())
                as GenericTasksCsvParseResult.Failed).reason,
        )
        assertEquals(
            GenericTasksCsvFailure.MALFORMED,
            (parseGenericTasksCsv("Title\rOne".toByteArray())
                as GenericTasksCsvParseResult.Failed).reason,
        )
        val unclosed = parseGenericTasksCsv("Title\n\"One".toByteArray())
            as GenericTasksCsvParseResult.Failed
        assertEquals(GenericTasksCsvFailure.MALFORMED, unclosed.reason)
        assertEquals(1, unclosed.rowNumber)
    }

    @Test
    fun genericParserEnforcesEncodingByteRowAndColumnBounds() {
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        assertEquals(
            GenericTasksCsvFailure.INVALID_UTF8,
            (parseGenericTasksCsv(invalidUtf8) as GenericTasksCsvParseResult.Failed).reason,
        )
        assertEquals(
            GenericTasksCsvFailure.TOO_LARGE,
            (parseGenericTasksCsv(ByteArray(MAX_TASKS_CSV_BYTES + 1))
                as GenericTasksCsvParseResult.Failed).reason,
        )
        val wideHeader = (1..101).joinToString(",") { "Column $it" } + "\r\n"
        assertEquals(
            GenericTasksCsvFailure.TOO_MANY_COLUMNS,
            (parseGenericTasksCsv(wideHeader.toByteArray())
                as GenericTasksCsvParseResult.Failed).reason,
        )
        val tooManyRows = buildString {
            append("Title\r\n")
            repeat(5_001) { append("Task\r\n") }
        }
        assertEquals(
            GenericTasksCsvFailure.TOO_MANY_ROWS,
            (parseGenericTasksCsv(tooManyRows.toByteArray())
                as GenericTasksCsvParseResult.Failed).reason,
        )
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "*GenericTasksCsvMapperTest" \
  --console=plain
```

Expected: FAIL compilation because `parseGenericTasksCsv` and its result
types do not exist.

- [ ] **Step 3: Extract the current state machine without changing strict behavior**

Move `RecordParseResult`, `FieldState`, and the complete current
`parseRecords` loop to `CsvRecordReader.kt`. Replace the hard-coded 14-field
guard with `maxColumns`, retain the current data-row numbering, and keep the
5,000-row guard independent of column width:

```kotlin
package app.opentasks.core.data.export

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal enum class CsvRecordFailureKind {
    INVALID_UTF8,
    MALFORMED,
    TOO_MANY_ROWS,
    TOO_MANY_COLUMNS,
}

internal sealed interface CsvTextResult {
    data class Ready(val text: String) : CsvTextResult
    data class Failed(val kind: CsvRecordFailureKind) : CsvTextResult
}

internal sealed interface CsvRecordResult {
    data class Ready(val records: List<List<String>>) : CsvRecordResult
    data class Failed(
        val rowNumber: Int,
        val kind: CsvRecordFailureKind,
        val message: String,
    ) : CsvRecordResult
}

internal fun decodeCsvUtf8(bytes: ByteArray): CsvTextResult = try {
    CsvTextResult.Ready(
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString(),
    )
} catch (_: java.nio.charset.CharacterCodingException) {
    CsvTextResult.Failed(CsvRecordFailureKind.INVALID_UTF8)
}
```

Define the shared reader with the complete existing four-state grammar and
parameterised bounds:

```kotlin
internal fun readCsvRecords(
    text: String,
    maxDataRows: Int,
    maxColumns: Int,
): CsvRecordResult {
    require(maxDataRows > 0 && maxColumns > 0)
    val records = mutableListOf<List<String>>()
    var fields = mutableListOf<String>()
    val field = StringBuilder()
    var state = FieldState.START
    var index = 0

    fun rowNumber() = records.size
    fun failed(kind: CsvRecordFailureKind, message: String) =
        CsvRecordResult.Failed(rowNumber(), kind, message)
    fun finishField() {
        fields += field.toString()
        field.setLength(0)
        state = FieldState.START
    }
    fun finishSeparatedField(): CsvRecordResult.Failed? {
        finishField()
        return if (fields.size >= maxColumns) {
            failed(
                CsvRecordFailureKind.TOO_MANY_COLUMNS,
                "CSV records may contain at most $maxColumns columns.",
            )
        } else {
            null
        }
    }
    fun finishRecord(): CsvRecordResult.Failed? {
        finishField()
        if (records.size > maxDataRows) {
            return CsvRecordResult.Failed(
                maxDataRows + 1,
                CsvRecordFailureKind.TOO_MANY_ROWS,
                "Import at most $maxDataRows tasks",
            )
        }
        records += fields
        fields = mutableListOf()
        return null
    }

    while (index < text.length) {
        val char = text[index]
        when (state) {
            FieldState.START -> when (char) {
                '"' -> state = FieldState.QUOTED
                ',' -> finishSeparatedField()?.let { return it }
                '\n' -> finishRecord()?.let { return it }
                '\r' -> {
                    if (index + 1 >= text.length || text[index + 1] != '\n') {
                        return failed(
                            CsvRecordFailureKind.MALFORMED,
                            "Bare CR outside a quoted field",
                        )
                    }
                    finishRecord()?.let { return it }
                    index++
                }
                else -> {
                    field.append(char)
                    state = FieldState.UNQUOTED
                }
            }
            FieldState.UNQUOTED -> when (char) {
                '"' -> return failed(
                    CsvRecordFailureKind.MALFORMED,
                    "Quote in unquoted field",
                )
                ',' -> finishSeparatedField()?.let { return it }
                '\n' -> finishRecord()?.let { return it }
                '\r' -> {
                    if (index + 1 >= text.length || text[index + 1] != '\n') {
                        return failed(
                            CsvRecordFailureKind.MALFORMED,
                            "Bare CR outside a quoted field",
                        )
                    }
                    finishRecord()?.let { return it }
                    index++
                }
                else -> field.append(char)
            }
            FieldState.QUOTED -> when {
                char != '"' -> field.append(char)
                index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                else -> state = FieldState.AFTER_QUOTE
            }
            FieldState.AFTER_QUOTE -> when (char) {
                ',' -> finishSeparatedField()?.let { return it }
                '\n' -> finishRecord()?.let { return it }
                '\r' -> {
                    if (index + 1 >= text.length || text[index + 1] != '\n') {
                        return failed(
                            CsvRecordFailureKind.MALFORMED,
                            "Bare CR after quoted field",
                        )
                    }
                    finishRecord()?.let { return it }
                    index++
                }
                else -> return failed(
                    CsvRecordFailureKind.MALFORMED,
                    "Text after closing quote",
                )
            }
        }
        index++
    }
    if (state == FieldState.QUOTED) {
        return failed(CsvRecordFailureKind.MALFORMED, "Unclosed quoted field")
    }
    if (
        (state != FieldState.START || field.isNotEmpty() || fields.isNotEmpty())
    ) {
        finishRecord()?.let { return it }
    }
    return CsvRecordResult.Ready(records)
}

private enum class FieldState { START, UNQUOTED, QUOTED, AFTER_QUOTE }
```

Do not introduce a second parser, callback policy, regex CSV parser, or
dependency.

In `TasksCsvParser.kt`, call `decodeCsvUtf8`, then
`readCsvRecords(text, maxDataRows = MAX_IMPORT_ROWS, maxColumns = 14)`.
Preserve strict
reason text by mapping `TOO_MANY_COLUMNS` to **Expected 14 fields**,
`TOO_MANY_ROWS` to **Import at most 5000 tasks**, and `MALFORMED` to the
reader's unchanged grammar message. Retain the exact header and exact
14-field checks in `parseTasksCsv`. Remove only the moved private reader
declarations.

- [ ] **Step 4: Add the generic document parser on top of the same reader**

Start `GenericTasksCsvMapper.kt` with these exact public values and parser:

```kotlin
package app.opentasks.core.data.export

data class GenericTasksCsvRow(
    val sourceRowNumber: Int,
    val cells: List<String>,
)

data class GenericTasksCsvDocument(
    val headers: List<String>,
    val rows: List<GenericTasksCsvRow>,
)

enum class GenericTasksCsvFailure {
    TOO_LARGE,
    INVALID_UTF8,
    MALFORMED,
    TOO_MANY_ROWS,
    TOO_MANY_COLUMNS,
    MISSING_HEADER,
    ROW_WIDER_THAN_HEADER,
}

sealed interface GenericTasksCsvParseResult {
    data class Parsed(val document: GenericTasksCsvDocument) : GenericTasksCsvParseResult
    data class Failed(
        val rowNumber: Int?,
        val reason: GenericTasksCsvFailure,
    ) : GenericTasksCsvParseResult
}

fun parseGenericTasksCsv(bytes: ByteArray): GenericTasksCsvParseResult {
    if (bytes.size > MAX_TASKS_CSV_BYTES) {
        return GenericTasksCsvParseResult.Failed(null, GenericTasksCsvFailure.TOO_LARGE)
    }
    val decoded = when (val result = decodeCsvUtf8(bytes)) {
        is CsvTextResult.Ready -> result.text.removePrefix("\uFEFF")
        is CsvTextResult.Failed -> return GenericTasksCsvParseResult.Failed(
            null,
            GenericTasksCsvFailure.INVALID_UTF8,
        )
    }
    val records = when (
        val result = readCsvRecords(
            decoded,
            maxDataRows = MAX_IMPORT_ROWS,
            maxColumns = 100,
        )
    ) {
        is CsvRecordResult.Ready -> result.records
        is CsvRecordResult.Failed -> return GenericTasksCsvParseResult.Failed(
            result.rowNumber.takeIf { it > 0 },
            when (result.kind) {
                CsvRecordFailureKind.INVALID_UTF8 -> GenericTasksCsvFailure.INVALID_UTF8
                CsvRecordFailureKind.MALFORMED -> GenericTasksCsvFailure.MALFORMED
                CsvRecordFailureKind.TOO_MANY_ROWS -> GenericTasksCsvFailure.TOO_MANY_ROWS
                CsvRecordFailureKind.TOO_MANY_COLUMNS -> GenericTasksCsvFailure.TOO_MANY_COLUMNS
            },
        )
    }
    val headers = records.firstOrNull()
        ?.takeIf { row -> row.isNotEmpty() && row.any(String::isNotBlank) }
        ?: return GenericTasksCsvParseResult.Failed(
            null,
            GenericTasksCsvFailure.MISSING_HEADER,
        )
    val rows = records.drop(1).mapIndexed { index, cells ->
        if (cells.size > headers.size) {
            return GenericTasksCsvParseResult.Failed(
                index + 1,
                GenericTasksCsvFailure.ROW_WIDER_THAN_HEADER,
            )
        }
        GenericTasksCsvRow(
            sourceRowNumber = index + 1,
            cells = cells + List(headers.size - cells.size) { "" },
        )
    }
    return GenericTasksCsvParseResult.Parsed(GenericTasksCsvDocument(headers, rows))
}
```

Use a shared internal `MAX_IMPORT_ROWS = 5_000` constant rather than retaining
two numeric authorities; keep it in the export package beside the parsers.

- [ ] **Step 5: Run generic and strict parser suites and verify GREEN**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "*GenericTasksCsvMapperTest" \
  --tests "*TasksCsvParserTest" \
  --console=plain
```

Expected: PASS. Existing strict BOM/header, formula, tag escaping, row-number,
date, and round-trip tests must remain unchanged and green.

- [ ] **Step 6: Commit the shared reader boundary**

```bash
git add \
  core/data/src/main/kotlin/app/opentasks/core/data/export/CsvRecordReader.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapper.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/export/TasksCsvParser.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapperTest.kt
git commit -m "refactor: share bounded csv record parsing"
```

### Task 2: Resolve reviewed status semantics in the existing import command

**Files:**

- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt:45-57`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/export/TasksCsvParser.kt:109-225,480-620`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/export/TasksCsvParserTest.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryImportTasksTest.kt`
- Test:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomImportTasksInstrumentedTest.kt`

**Interfaces:**

- Consumes: `ImportedTaskRow.statusName`, `SemanticStatus`, active workflow
  rank, `previewTasksImport`, and the shared import plan used by both engines.
- Produces: `ImportedTaskRow.statusSemantic: SemanticStatus? = null`; null pins
  strict behavior, while generic rows resolve by semantic category.

- [ ] **Step 1: Add host tests for semantic selection and delayed rejection**

In `InMemoryImportTasksTest`, add:

```kotlin
@Test
fun semanticHintSelectsTheFirstActiveStatusWithThatMeaning() = runBlocking {
    val base = OpenTasksFixtures.snapshot
    val project = OpenTasksFixtures.studioProject
    val customStarted = base.workflowStatuses.map { status ->
        if (status.projectId == project.id &&
            status.semanticStatus == SemanticStatus.STARTED
        ) {
            status.copy(name = "Doing")
        } else {
            status
        }
    }
    val repository = InMemoryVaultRepository(initial = base.copy(workflowStatuses = customStarted))

    val result = repository.execute(
        DomainCommand.ImportTasks(
            listOf(
                row(1, "Mapped work", project = project.name).copy(
                    statusName = "In progress",
                    statusSemantic = SemanticStatus.STARTED,
                ),
            ),
        ),
    )

    assertTrue(result is CommandResult.Success)
    val imported = repository.currentWorkspace().tasks.single { it.title == "Mapped work" }
    assertEquals("Doing", repository.currentWorkspace().workflowStatuses.single {
        it.id == imported.statusId
    }.name)
}

@Test
fun semanticHintRejectsAtomicallyWhenTheCategoryIsUnavailable() = runBlocking {
    val base = OpenTasksFixtures.snapshot
    val project = OpenTasksFixtures.studioProject.copy(
        id = ProjectId("project-without-started"),
        name = "Project without started",
    )
    val withoutStarted = base.workflowStatuses +
        WorkflowStatus.defaults(project.id).filterNot {
            it.semanticStatus == SemanticStatus.STARTED
        }
    val journal = InMemoryBackupJournal()
    val repository = InMemoryVaultRepository(
        initial = base.copy(
            projects = base.projects + project,
            workflowStatuses = withoutStarted,
        ),
        backupJournal = journal,
    )
    val before = repository.currentWorkspace()

    val result = repository.execute(
        DomainCommand.ImportTasks(
            listOf(
                row(4, "Unavailable state", project = project.name).copy(
                    statusName = "Doing",
                    statusSemantic = SemanticStatus.STARTED,
                ),
            ),
        ),
    ) as CommandResult.Rejected

    assertEquals(RejectionReason.IMPORT_STATUS_CONFLICT, result.reason)
    assertEquals(before, repository.currentWorkspace())
    assertTrue(journal.entries.isEmpty())
}
```

Add `statusSemantic = null` to the strict round-trip assertions in
`TasksCsvParserTest`:

```kotlin
assertNull(rows[0].statusSemantic)
assertNull(rows[1].statusSemantic)
```

- [ ] **Step 2: Run the host tests and verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "*semanticHint*" \
  --tests "*TasksCsvParserTest.keystoneTasksExportRoundTripsEveryOwnedFieldInOrder" \
  --console=plain
```

Expected: FAIL compilation because `ImportedTaskRow.statusSemantic` does not
exist.

- [ ] **Step 3: Add a Room parity test before production changes**

In `RoomImportTasksInstrumentedTest`, create a started-semantic import using the
existing fixture project, but replace that project's default started status in
the database with a custom active `Doing` status before dispatch. Then assert
the imported row points at `Doing`, execute the returned Undo, and assert table
counts return exactly to their prior values:

```kotlin
@Test
fun semanticHintUsesTheActiveRoomStatusAndUndoRemainsExact() = runBlocking {
    withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
        repository!!.currentWorkspace()
        val project = OpenTasksFixtures.studioProject
        val started = repository!!.currentWorkspace().workflowStatuses.single {
            it.projectId == project.id && it.semanticStatus == SemanticStatus.STARTED
        }
        database!!.workspaceDao().upsertWorkflowStatus(
            started.copy(name = "Doing")
                .toEntity(Revision(DeviceId("import-test"), 1L, 0)),
        )
        repository!!.observeWorkspace().first { snapshot ->
            snapshot.workflowStatuses.any { it.id == started.id && it.name == "Doing" }
        }
        val before = recordTableCounts()

        val imported = repository!!.execute(
            DomainCommand.ImportTasks(
                listOf(
                    importRow().copy(
                        projectName = project.name,
                        statusName = "In progress",
                        statusSemantic = SemanticStatus.STARTED,
                    ),
                ),
            ),
        ) as CommandResult.Success

        val task = repository!!.currentWorkspace().tasks.single {
            it.title == "Imported task"
        }
        assertEquals("Doing", repository!!.currentWorkspace().workflowStatuses.single {
            it.id == task.statusId
        }.name)
        repository!!.execute(requireNotNull(imported.undo))
        assertEquals(before, recordTableCounts())
    }
}
```

Import `kotlinx.coroutines.flow.first`. Use the file's existing entity mapper
and DAO helper rather than direct SQL.
Renaming the existing status keeps every fixture foreign-key reference valid;
do not delete it or add a DAO method for this test.

- [ ] **Step 4: Compile the Room RED test safely**

Run:

```bash
./gradlew :core:data:compileDebugAndroidTestKotlin --console=plain
```

Expected before production change: FAIL compilation on `statusSemantic`. Do
not run it against the protected emulator.

- [ ] **Step 5: Add the optional hint and resolve it in the shared planner**

Append the defaulted field to `ImportedTaskRow` so every current named caller
continues compiling unchanged:

```kotlin
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
    val statusSemantic: SemanticStatus? = null,
)
```

In `resolveTaskImport`, preserve the current name-only branch when the hint is
null. When it is non-null, allow only `BACKLOG`, `STARTED`, or `COMPLETED`,
ignore a stale case-only name collision, and select by exact compatible name
then semantic rank:

```kotlin
val semanticHint = row.statusSemantic
if (semanticHint != null && semanticHint !in setOf(
        SemanticStatus.BACKLOG,
        SemanticStatus.STARTED,
        SemanticStatus.COMPLETED,
    )
) {
    return invalid(
        row.sourceRowNumber,
        RejectionReason.IMPORT_STATUS_CONFLICT,
        "CSV row ${row.sourceRowNumber} uses an unsupported mapped status.",
    )
}
val statusName = row.statusName?.trim()?.ifBlank { null }
val exact = statusName?.let { name -> availableStatuses.firstOrNull { it.name == name } }
if (
    semanticHint == null && exact == null && statusName != null &&
    availableStatuses.any { it.name.equals(statusName, ignoreCase = true) }
) return collision(row, "status", statusName)

val semanticMatch = semanticHint?.let { requested ->
    availableStatuses
        .filter { it.semanticStatus == requested }
        .minByOrNull(WorkflowStatus::rank)
}
val status = when {
    row.completedAt != null -> {
        val completed = exact?.takeIf { it.semanticStatus == SemanticStatus.COMPLETED }
            ?: availableStatuses
                .filter { it.semanticStatus == SemanticStatus.COMPLETED }
                .minByOrNull(WorkflowStatus::rank)
            ?: return invalid(
                row.sourceRowNumber,
                RejectionReason.IMPORT_STATUS_CONFLICT,
                "CSV row ${row.sourceRowNumber} has no active completed status.",
            )
        StatusSelection.Existing(completed)
    }
    semanticHint == SemanticStatus.COMPLETED -> return invalid(
        row.sourceRowNumber,
        RejectionReason.IMPORT_STATUS_CONFLICT,
        "CSV row ${row.sourceRowNumber} uses a completed status without a completion instant.",
    )
    semanticHint != null -> StatusSelection.Existing(
        exact?.takeIf { it.semanticStatus == semanticHint }
            ?: semanticMatch
            ?: return invalid(
                row.sourceRowNumber,
                RejectionReason.IMPORT_STATUS_CONFLICT,
                "CSV row ${row.sourceRowNumber} has no active mapped status.",
            ),
    )
    exact?.semanticStatus == SemanticStatus.COMPLETED -> return invalid(
        row.sourceRowNumber,
        RejectionReason.IMPORT_STATUS_CONFLICT,
        "CSV row ${row.sourceRowNumber} uses a completed status without a completion instant.",
    )
    exact != null -> StatusSelection.Existing(exact)
    else -> StatusSelection.Default(SemanticStatus.BACKLOG)
}
```

When a `StatusSelection.Default` is materialised in `buildTasksImportPlan`,
replace `first` with `minBy` on `WorkflowStatus::rank`. Do not touch either
repository implementation; both already call this single planner.

- [ ] **Step 6: Run host parity and compile Room tests GREEN**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "*InMemoryImportTasksTest" \
  --tests "*TasksCsvParserTest" \
  :core:data:compileDebugAndroidTestKotlin \
  --console=plain
```

Expected: PASS. If a safe disposable target is the sole device, also run:

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.core.data.RoomImportTasksInstrumentedTest \
  --console=plain
```

Expected on that disposable target: PASS. Otherwise record compile-only Room
evidence.

- [ ] **Step 7: Commit the semantic import contract**

```bash
git add \
  core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/export/TasksCsvParser.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/export/TasksCsvParserTest.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/InMemoryImportTasksTest.kt \
  core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomImportTasksInstrumentedTest.kt
git commit -m "feat: resolve mapped csv status semantics"
```

### Task 3: Define mapping choices and conservative header suggestions

**Files:**

- Create:
  `core/model/src/main/kotlin/app/opentasks/core/model/TaskCsvMigration.kt`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapper.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapperTest.kt`

**Interfaces:**

- Consumes: `GenericTasksCsvDocument` from Task 1.
- Produces: `TaskCsvField`, mapping-choice enums, `TaskCsvMapping`, warning and
  blocker types, `GenericTasksCsvColumn`, and
  `suggestTaskCsvMapping(document, columns)` for Tasks 4-8.

- [ ] **Step 1: Write failing suggestion tests for the full field set**

Append to `GenericTasksCsvMapperTest`:

```kotlin
@Test
fun suggestionsCoverEveryApprovedFieldWithoutVendorProfiles() {
    val document = parsedGeneric(
        "Task Name,List,State,Priority,Start Date,Due Date,Completed At," +
            "Estimate Hours,Labels,Notes\r\n" +
            "Ship,Launch,Doing,3,24/08/2026,25/08/2026,,2,work|urgent,Text\r\n",
    )

    val mapping = suggestTaskCsvMapping(document)

    assertEquals(0, mapping.columns[TaskCsvField.TITLE])
    assertEquals(1, mapping.columns[TaskCsvField.PROJECT])
    assertEquals(2, mapping.columns[TaskCsvField.STATUS])
    assertEquals(3, mapping.columns[TaskCsvField.PRIORITY])
    assertEquals(4, mapping.columns[TaskCsvField.START])
    assertEquals(5, mapping.columns[TaskCsvField.DUE])
    assertEquals(6, mapping.columns[TaskCsvField.COMPLETION])
    assertEquals(7, mapping.columns[TaskCsvField.ESTIMATE])
    assertEquals(8, mapping.columns[TaskCsvField.TAGS])
    assertEquals(9, mapping.columns[TaskCsvField.DESCRIPTION])
    assertEquals(TaskCsvEstimateUnit.HOURS, mapping.estimateUnit)
    assertEquals(TaskCsvTagMode.PIPE, mapping.tagMode)
    assertEquals(TaskCsvStatusChoice.IN_PROGRESS, mapping.statusChoices["Doing"])
    assertEquals(null, mapping.priorityChoices["3"])
}

@Test
fun ambiguousHeadersRemainUnmappedAndSamplesStayBounded() {
    val document = parsedGeneric(
        "Task Name,Task-Name,Unknown\r\n" +
            "One,Project A,${"x".repeat(121)}\r\n" +
            "Two,Project B,second\r\n" +
            "Three,Project C,third\r\n" +
            "Four,Project D,fourth\r\n",
    )

    val columns = describeGenericTasksCsv(document)
    val mapping = suggestTaskCsvMapping(document)

    assertTrue(mapping.columns.isEmpty())
    assertEquals(listOf("One", "Two", "Three"), columns[0].samples)
    assertEquals(3, columns[2].samples.size)
    assertEquals(120, columns[2].samples.first().length)
    assertTrue(columns[2].samples.first().endsWith("…"))
}
```

Add this helper at the bottom of the test class:

```kotlin
private fun parsedGeneric(source: String): GenericTasksCsvDocument =
    (parseGenericTasksCsv(source.toByteArray()) as GenericTasksCsvParseResult.Parsed).document
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "*GenericTasksCsvMapperTest.suggestions*" \
  --tests "*GenericTasksCsvMapperTest.ambiguousHeaders*" \
  --console=plain
```

Expected: FAIL compilation because the mapping contract and suggestion
functions do not exist.

- [ ] **Step 3: Add the one cross-layer mapping contract**

Create `TaskCsvMigration.kt` in `core:model`:

```kotlin
package app.opentasks.core.model

enum class TaskCsvField {
    TITLE,
    PROJECT,
    STATUS,
    PRIORITY,
    START,
    DUE,
    COMPLETION,
    ESTIMATE,
    TAGS,
    DESCRIPTION,
}

enum class TaskCsvStatusChoice { BACKLOG, IN_PROGRESS, DONE, IGNORE }

enum class TaskCsvPriorityChoice { NONE, LOW, MEDIUM, HIGH, URGENT, IGNORE }

enum class TaskCsvDateOrder { DAY_MONTH_YEAR, MONTH_DAY_YEAR }

enum class TaskCsvEstimateUnit { MINUTES, HOURS }

enum class TaskCsvTagMode(val separator: Char?) {
    COMMA(','),
    SEMICOLON(';'),
    PIPE('|'),
    SINGLE(null),
}

data class TaskCsvMapping(
    val columns: Map<TaskCsvField, Int> = emptyMap(),
    val statusChoices: Map<String, TaskCsvStatusChoice> = emptyMap(),
    val priorityChoices: Map<String, TaskCsvPriorityChoice> = emptyMap(),
    val dateOrder: TaskCsvDateOrder? = null,
    val estimateUnit: TaskCsvEstimateUnit? = null,
    val tagMode: TaskCsvTagMode? = null,
)

enum class TaskCsvWarningReason {
    EMPTY_ROW,
    TITLE_BLANK,
    TITLE_TOO_LONG,
    PROJECT_OMITTED,
    PROJECT_CASE_MERGED,
    STATUS_OMITTED,
    STATUS_FALLBACK,
    PRIORITY_OMITTED,
    START_OMITTED,
    START_TIME_INFERRED,
    START_ZONE_INFERRED,
    DUE_OMITTED,
    DUE_TIME_INFERRED,
    DUE_ZONE_INFERRED,
    COMPLETION_OMITTED,
    COMPLETION_TIME_INFERRED,
    COMPLETION_ZONE_INFERRED,
    COMPLETION_INFERRED,
    COMPLETION_OVERRIDES_STATUS,
    ESTIMATE_OMITTED,
    TAG_BLANK_OMITTED,
    TAG_TOO_LONG_OMITTED,
    TAG_DUPLICATE_OMITTED,
    TAG_LIMIT_OMITTED,
    TAG_CASE_MERGED,
    DESCRIPTION_OMITTED,
}

data class TaskCsvWarning(
    val rowNumber: Int,
    val field: TaskCsvField?,
    val reason: TaskCsvWarningReason,
)

enum class TaskCsvBlockingIssue {
    TITLE_MAPPING_REQUIRED,
    COLUMN_MAPPING_INVALID,
    STATUS_CHOICES_REQUIRED,
    PRIORITY_CHOICES_REQUIRED,
    DATE_ORDER_REQUIRED,
    ESTIMATE_UNIT_REQUIRED,
    TAG_MODE_REQUIRED,
    NO_VALID_TASKS,
    TOO_MANY_NEW_PROJECTS,
    TOO_MANY_NEW_TAGS,
    TARGET_REJECTED,
}
```

These values are transient plain Kotlin data. Do not make them serializable,
parcelable, persisted preferences, or a second domain command.

- [ ] **Step 4: Implement deterministic aliases and suggestions**

Add to `GenericTasksCsvMapper.kt`:

```kotlin
data class GenericTasksCsvColumn(
    val index: Int,
    val header: String,
    val samples: List<String>,
)

fun describeGenericTasksCsv(document: GenericTasksCsvDocument): List<GenericTasksCsvColumn> =
    document.headers.mapIndexed { index, header ->
        GenericTasksCsvColumn(
            index = index,
            header = header,
            samples = document.rows.asSequence()
                .map { it.cells[index].trim() }
                .filter(String::isNotEmpty)
                .distinct()
                .take(3)
                .map(::displaySample)
                .toList(),
        )
    }

fun suggestTaskCsvMapping(
    document: GenericTasksCsvDocument,
    columns: Map<TaskCsvField, Int>? = null,
): TaskCsvMapping {
    val selected = columns ?: suggestColumns(document.headers)
    val statusValues = distinctValues(document, selected[TaskCsvField.STATUS])
    val priorityValues = distinctValues(document, selected[TaskCsvField.PRIORITY])
    return TaskCsvMapping(
        columns = selected,
        statusChoices = statusValues.mapNotNull { value ->
            suggestStatus(value)?.let { value to it }
        }.toMap(),
        priorityChoices = priorityValues.mapNotNull { value ->
            suggestPriority(value)?.let { value to it }
        }.toMap(),
        estimateUnit = selected[TaskCsvField.ESTIMATE]
            ?.let(document.headers::get)
            ?.let(::suggestEstimateUnit),
        tagMode = selected[TaskCsvField.TAGS]
            ?.let { index -> suggestTagMode(distinctValues(document, index)) },
    )
}
```

Use a private alias map covering only the approved generic names:

```kotlin
private val FIELD_ALIASES = mapOf(
    TaskCsvField.TITLE to setOf("title", "task", "taskname", "content"),
    TaskCsvField.PROJECT to setOf("project", "projectname", "list", "listname"),
    TaskCsvField.STATUS to setOf("status", "state", "workflowstatus"),
    TaskCsvField.PRIORITY to setOf("priority", "taskpriority"),
    TaskCsvField.START to setOf("start", "startdate", "starttime", "scheduled"),
    TaskCsvField.DUE to setOf("due", "duedate", "duetime", "deadline"),
    TaskCsvField.COMPLETION to setOf("completed", "completedat", "completion", "closedat"),
    TaskCsvField.ESTIMATE to setOf("estimate", "estimateminutes", "estimatehours", "duration"),
    TaskCsvField.TAGS to setOf("tags", "tag", "labels", "label"),
    TaskCsvField.DESCRIPTION to setOf("description", "notes", "note", "details"),
)
```

Add these exact private helpers beneath it:

```kotlin
private const val MAX_DISPLAY_SAMPLE_CHARS = 120

private fun displaySample(value: String): String =
    if (value.length <= MAX_DISPLAY_SAMPLE_CHARS) value
    else value.take(MAX_DISPLAY_SAMPLE_CHARS - 1) + "…"

private fun normaliseHeader(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .filterNot { it.isWhitespace() || it == '-' || it == '_' }

private fun suggestColumns(headers: List<String>): Map<TaskCsvField, Int> {
    val claims = headers.mapIndexed { index, header ->
        val normalised = normaliseHeader(header)
        index to TaskCsvField.entries.filter { field ->
            normalised in FIELD_ALIASES.getValue(field)
        }
    }
    return TaskCsvField.entries.mapNotNull { field ->
        val candidates = claims.filter { (_, fields) -> fields == listOf(field) }
        candidates.singleOrNull()?.first?.let { field to it }
    }.toMap()
}

private fun distinctValues(
    document: GenericTasksCsvDocument,
    columnIndex: Int?,
): List<String> = columnIndex?.let { index ->
    document.rows.asSequence()
        .map { it.cells[index].trim() }
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
}.orEmpty()

private fun suggestStatus(value: String): TaskCsvStatusChoice? =
    when (normaliseHeader(value)) {
        "backlog", "open", "todo" -> TaskCsvStatusChoice.BACKLOG
        "inprogress", "doing", "started" -> TaskCsvStatusChoice.IN_PROGRESS
        "done", "completed", "closed" -> TaskCsvStatusChoice.DONE
        else -> null
    }

private fun suggestPriority(value: String): TaskCsvPriorityChoice? {
    if (value.trim().toLongOrNull() != null) return null
    return when (normaliseHeader(value)) {
        "none", "nopriority" -> TaskCsvPriorityChoice.NONE
        "low" -> TaskCsvPriorityChoice.LOW
        "medium", "normal" -> TaskCsvPriorityChoice.MEDIUM
        "high" -> TaskCsvPriorityChoice.HIGH
        "urgent", "critical" -> TaskCsvPriorityChoice.URGENT
        else -> null
    }
}

private fun suggestEstimateUnit(header: String): TaskCsvEstimateUnit? =
    when {
        normaliseHeader(header).let { value ->
            value.endsWith("minutes") || value.endsWith("minute") ||
                value.endsWith("mins") || value.endsWith("min")
        } -> TaskCsvEstimateUnit.MINUTES
        normaliseHeader(header).let { value ->
            value.endsWith("hours") || value.endsWith("hour") ||
                value.endsWith("hrs") || value.endsWith("hr")
        } -> TaskCsvEstimateUnit.HOURS
        else -> null
    }

private fun suggestTagMode(values: List<String>): TaskCsvTagMode? {
    val separators = values.asSequence()
        .flatMap(String::asSequence)
        .filter { it == ',' || it == ';' || it == '|' }
        .distinct()
        .toList()
    return when (separators.singleOrNull()) {
        ',' -> TaskCsvTagMode.COMMA
        ';' -> TaskCsvTagMode.SEMICOLON
        '|' -> TaskCsvTagMode.PIPE
        null -> TaskCsvTagMode.SINGLE.takeIf { separators.isEmpty() }
        else -> null
    }
}
```

Normalise with `trim().lowercase(Locale.ROOT)` and remove only whitespace,
hyphen, and underscore. A header maps only when exactly one field claims its
normalised alias and that field has exactly one candidate column; duplicate
`Name` or otherwise ambiguous headers remain unmapped.

The helper bodies above are the complete suggestion vocabulary. Do not add
fuzzy matching, provider names, numeric-priority direction, or telemetry.
The 120-character ellipsis affects display samples only; imported values are
still validated in full and are never truncated.

- [ ] **Step 5: Run the complete suggestion suite GREEN**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "*GenericTasksCsvMapperTest" \
  --console=plain
```

Expected: PASS, including Task 1's bounds and parsing cases.

- [ ] **Step 6: Commit the mapping contract and suggestions**

```bash
git add \
  core/model/src/main/kotlin/app/opentasks/core/model/TaskCsvMigration.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapper.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapperTest.kt
git commit -m "feat: suggest generic task csv mappings"
```

### Task 4: Convert every supported field with transparent best effort

**Files:**

- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapper.kt`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapperTest.kt`

**Interfaces:**

- Consumes: `TaskCsvMapping`, `ImportedTaskRow.statusSemantic`,
  `previewTasksImport`, current projects/statuses/tags, an explicit `ZoneId`,
  and an explicit completion fallback `Instant`.
- Produces: `TaskCsvTarget`, `GenericTasksCsvReview`,
  `WorkspaceSnapshot.toTaskCsvTarget()`, `emptyTaskCsvTarget()`, and
  `reviewGenericTasksCsv(...)` for the ViewModel.

- [ ] **Step 1: Add a full-field happy-path test and verify RED**

Append a test that proves the approved full set, explicit status/priority
choices, day/month order, exact offsets, estimate hours, tags, and description:

```kotlin
@Test
fun reviewConvertsTheFullApprovedFieldSet() {
    val document = parsedGeneric(
        "Title,Project,Status,Priority,Start,Due,Completed,Estimate,Tags,Notes\r\n" +
            "Ship release,Launch,Doing,High,24/08/2026," +
            "25/08/2026 18:30,,2,work|urgent,Migration note\r\n",
    )
    val mapping = TaskCsvMapping(
        columns = TaskCsvField.entries.associateWith { it.ordinal },
        statusChoices = mapOf("Doing" to TaskCsvStatusChoice.IN_PROGRESS),
        priorityChoices = mapOf("High" to TaskCsvPriorityChoice.HIGH),
        dateOrder = TaskCsvDateOrder.DAY_MONTH_YEAR,
        estimateUnit = TaskCsvEstimateUnit.HOURS,
        tagMode = TaskCsvTagMode.PIPE,
    )

    val review = reviewGenericTasksCsv(
        document = document,
        mapping = mapping,
        target = emptyTaskCsvTarget(),
        zone = ZoneId.of("Asia/Bangkok"),
        completionFallback = Instant.parse("2026-08-24T12:00:00Z"),
    )

    assertTrue(review.blockingIssues.isEmpty())
    val row = review.rows.single()
    assertEquals("Ship release", row.title)
    assertEquals("Launch", row.projectName)
    assertEquals("In progress", row.statusName)
    assertEquals(SemanticStatus.STARTED, row.statusSemantic)
    assertEquals(Priority.HIGH, row.priority)
    assertEquals(Instant.parse("2026-08-24T02:00:00Z"), row.start?.instant)
    assertEquals("+07:00", row.start?.zoneId)
    assertEquals(Instant.parse("2026-08-25T11:30:00Z"), row.due?.instant)
    assertEquals(120L, row.estimateMinutes)
    assertEquals(listOf("work", "urgent"), row.tagNames)
    assertEquals(listOf("work", "urgent"), review.tagSamples)
    assertEquals("Migration note", row.description)
    assertEquals(1, review.newProjectCount)
    assertEquals(2, review.newTagCount)
}
```

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "*GenericTasksCsvMapperTest.reviewConvertsTheFullApprovedFieldSet" \
  --console=plain
```

Expected: FAIL compilation because review/target types do not exist.

- [ ] **Step 2: Add best-effort, completion, canonicalisation, and blocker tests**

Add four focused cases before production code:

```kotlin
@Test
fun invalidTitlesSkipRowsAndInvalidOptionalValuesAreCounted() {
    val document = parsedGeneric(
        "Title,Project,Due,Estimate,Tags,Notes\r\n" +
            ",Launch,not-a-date,-1,valid|${"x".repeat(65)},Text\r\n" +
            "Kept,${"p".repeat(121)},not-a-date,-1," +
            "valid|${"x".repeat(65)},${"d".repeat(20_001)}\r\n",
    )
    val mapping = TaskCsvMapping(
        columns = mapOf(
            TaskCsvField.TITLE to 0,
            TaskCsvField.PROJECT to 1,
            TaskCsvField.DUE to 2,
            TaskCsvField.ESTIMATE to 3,
            TaskCsvField.TAGS to 4,
            TaskCsvField.DESCRIPTION to 5,
        ),
        estimateUnit = TaskCsvEstimateUnit.MINUTES,
        tagMode = TaskCsvTagMode.PIPE,
    )

    val review = reviewGenericTasksCsv(
        document,
        mapping,
        emptyTaskCsvTarget(),
        ZoneId.of("UTC"),
        Instant.parse("2026-08-24T12:00:00Z"),
    )

    assertEquals(1, review.rows.size)
    assertEquals(1, review.skippedTaskCount)
    assertEquals(5, review.omittedValueCount)
    assertTrue(review.warnings.any { it.reason == TaskCsvWarningReason.TITLE_BLANK })
    assertEquals(null, review.rows.single().projectName)
    assertEquals(null, review.rows.single().due)
    assertEquals(null, review.rows.single().estimateMinutes)
    assertEquals(listOf("valid"), review.rows.single().tagNames)
    assertEquals("", review.rows.single().description)
}

@Test
fun doneWithoutTimeUsesFallbackAndExplicitCompletionOverridesOpenStatus() {
    val document = parsedGeneric(
        "Title,Status,Completed\r\n" +
            "Inferred,Done,\r\n" +
            "Override,Open,yes\r\n",
    )
    val mapping = TaskCsvMapping(
        columns = mapOf(
            TaskCsvField.TITLE to 0,
            TaskCsvField.STATUS to 1,
            TaskCsvField.COMPLETION to 2,
        ),
        statusChoices = mapOf(
            "Done" to TaskCsvStatusChoice.DONE,
            "Open" to TaskCsvStatusChoice.BACKLOG,
        ),
    )
    val fallback = Instant.parse("2026-08-24T12:34:56Z")

    val review = reviewGenericTasksCsv(
        document,
        mapping,
        emptyTaskCsvTarget(),
        ZoneId.of("UTC"),
        fallback,
    )

    assertTrue(review.rows.all { it.completedAt == fallback })
    assertTrue(review.rows.all { it.statusSemantic == SemanticStatus.COMPLETED })
    assertTrue(review.warnings.any { it.reason == TaskCsvWarningReason.COMPLETION_INFERRED })
    assertTrue(review.warnings.any {
        it.reason == TaskCsvWarningReason.COMPLETION_OVERRIDES_STATUS
    })
}

@Test
fun dateOnlyValuesReportTheirVisibleDefaultTimes() {
    val document = parsedGeneric(
        "Title,Start,Due,Completed\r\n" +
            "Defaults,24/08/2026,25/08/2026,26/08/2026\r\n",
    )
    val mapping = TaskCsvMapping(
        columns = mapOf(
            TaskCsvField.TITLE to 0,
            TaskCsvField.START to 1,
            TaskCsvField.DUE to 2,
            TaskCsvField.COMPLETION to 3,
        ),
        dateOrder = TaskCsvDateOrder.DAY_MONTH_YEAR,
    )

    val review = reviewGenericTasksCsv(
        document,
        mapping,
        emptyTaskCsvTarget(),
        ZoneId.of("Asia/Bangkok"),
        Instant.EPOCH,
    )

    assertTrue(review.warnings.any {
        it.reason == TaskCsvWarningReason.START_TIME_INFERRED
    })
    assertTrue(review.warnings.any {
        it.reason == TaskCsvWarningReason.START_ZONE_INFERRED
    })
    assertTrue(review.warnings.any {
        it.reason == TaskCsvWarningReason.DUE_TIME_INFERRED
    })
    assertTrue(review.warnings.any {
        it.reason == TaskCsvWarningReason.DUE_ZONE_INFERRED
    })
    assertTrue(review.warnings.any {
        it.reason == TaskCsvWarningReason.COMPLETION_TIME_INFERRED
    })
    assertTrue(review.warnings.any {
        it.reason == TaskCsvWarningReason.COMPLETION_ZONE_INFERRED
    })
    assertEquals(0, review.omittedValueCount)
}

@Test
fun projectAndTagCaseVariantsReuseCanonicalTargetNames() {
    val target = TaskCsvTarget(
        projects = listOf(project("Existing Project")),
        workflowStatuses = WorkflowStatus.defaults(null) +
            WorkflowStatus.defaults(ProjectId("existing-project")),
        tags = listOf(Tag(TagId("tag-work"), workspaceId, "Work")),
    )
    val document = parsedGeneric(
        "Title,Project,Tags\r\nOne,existing project,work|New\r\n" +
            "Two,EXISTING PROJECT,WORK|new\r\n",
    )
    val mapping = TaskCsvMapping(
        columns = mapOf(
            TaskCsvField.TITLE to 0,
            TaskCsvField.PROJECT to 1,
            TaskCsvField.TAGS to 2,
        ),
        tagMode = TaskCsvTagMode.PIPE,
    )

    val review = reviewGenericTasksCsv(
        document,
        mapping,
        target,
        ZoneId.of("UTC"),
        Instant.EPOCH,
    )

    assertEquals(listOf("Existing Project", "Existing Project"), review.rows.map {
        it.projectName
    })
    assertTrue(review.rows.all { it.tagNames == listOf("Work", "New") })
    assertEquals(0, review.newProjectCount)
    assertEquals(1, review.newTagCount)
    assertTrue(review.warnings.any { it.reason == TaskCsvWarningReason.PROJECT_CASE_MERGED })
    assertTrue(review.warnings.any { it.reason == TaskCsvWarningReason.TAG_CASE_MERGED })
}

@Test
fun unresolvedChoicesAndZeroValidRowsBlockConfirmation() {
    val document = parsedGeneric(
        "Title,Status,Priority,Due,Estimate,Tags\r\n" +
            ",3,2,03/04/2026,10,a;b\r\n",
    )
    val mapping = TaskCsvMapping(
        columns = mapOf(
            TaskCsvField.TITLE to 0,
            TaskCsvField.STATUS to 1,
            TaskCsvField.PRIORITY to 2,
            TaskCsvField.DUE to 3,
            TaskCsvField.ESTIMATE to 4,
            TaskCsvField.TAGS to 5,
        ),
    )

    val review = reviewGenericTasksCsv(
        document,
        mapping,
        emptyTaskCsvTarget(),
        ZoneId.of("UTC"),
        Instant.EPOCH,
    )

    assertEquals(
        setOf(
            TaskCsvBlockingIssue.STATUS_CHOICES_REQUIRED,
            TaskCsvBlockingIssue.PRIORITY_CHOICES_REQUIRED,
            TaskCsvBlockingIssue.DATE_ORDER_REQUIRED,
            TaskCsvBlockingIssue.ESTIMATE_UNIT_REQUIRED,
            TaskCsvBlockingIssue.TAG_MODE_REQUIRED,
            TaskCsvBlockingIssue.NO_VALID_TASKS,
        ),
        review.blockingIssues,
    )
}

@Test
fun invalidOrDuplicateColumnMappingsBlockWithoutReadingOutsideTheTable() {
    val document = parsedGeneric("Title,Project\r\nOne,Launch\r\n")
    val mappings = listOf(
        TaskCsvMapping(
            columns = mapOf(
                TaskCsvField.TITLE to 0,
                TaskCsvField.PROJECT to 0,
            ),
        ),
        TaskCsvMapping(columns = mapOf(TaskCsvField.TITLE to 99)),
    )

    mappings.forEach { mapping ->
        val review = reviewGenericTasksCsv(
            document,
            mapping,
            emptyTaskCsvTarget(),
            ZoneId.of("UTC"),
            Instant.EPOCH,
        )
        assertTrue(TaskCsvBlockingIssue.COLUMN_MAPPING_INVALID in review.blockingIssues)
        assertTrue(review.rows.isEmpty())
    }
}

@Test
fun projectAndTagCreationLimitsBlockOnlyAboveTheApprovedCeilings() {
    fun projectReview(count: Int): GenericTasksCsvReview {
        val source = buildString {
            append("Title,Project\r\n")
            repeat(count) { index -> append("Task $index,Project $index\r\n") }
        }
        return reviewGenericTasksCsv(
            parsedGeneric(source),
            TaskCsvMapping(
                columns = mapOf(
                    TaskCsvField.TITLE to 0,
                    TaskCsvField.PROJECT to 1,
                ),
            ),
            emptyTaskCsvTarget(),
            ZoneId.of("UTC"),
            Instant.EPOCH,
        )
    }
    fun tagReview(count: Int): GenericTasksCsvReview {
        val source = buildString {
            append("Title,Tags\r\n")
            (0 until count).chunked(50).forEachIndexed { row, tags ->
                append("Task $row,")
                append(tags.joinToString("|") { "Tag $it" })
                append("\r\n")
            }
        }
        return reviewGenericTasksCsv(
            parsedGeneric(source),
            TaskCsvMapping(
                columns = mapOf(
                    TaskCsvField.TITLE to 0,
                    TaskCsvField.TAGS to 1,
                ),
                tagMode = TaskCsvTagMode.PIPE,
            ),
            emptyTaskCsvTarget(),
            ZoneId.of("UTC"),
            Instant.EPOCH,
        )
    }

    assertEquals(500, projectReview(500).newProjectCount)
    assertTrue(
        TaskCsvBlockingIssue.TOO_MANY_NEW_PROJECTS in
            projectReview(501).blockingIssues,
    )
    assertEquals(1_000, tagReview(1_000).newTagCount)
    assertTrue(
        TaskCsvBlockingIssue.TOO_MANY_NEW_TAGS in
            tagReview(1_001).blockingIssues,
    )
}

@Test
fun tagOmissionsReportTheSpecificTokenLoss() {
    val values = buildList {
        add("Work")
        add("")
        add("work")
        add("x".repeat(65))
        repeat(51) { add("Tag $it") }
    }
    val document = parsedGeneric(
        "Title,Tags\r\nOne,${values.joinToString("|")}\r\n",
    )
    val review = reviewGenericTasksCsv(
        document,
        TaskCsvMapping(
            columns = mapOf(
                TaskCsvField.TITLE to 0,
                TaskCsvField.TAGS to 1,
            ),
            tagMode = TaskCsvTagMode.PIPE,
        ),
        emptyTaskCsvTarget(),
        ZoneId.of("UTC"),
        Instant.EPOCH,
    )

    val reasons = review.warnings.map(TaskCsvWarning::reason).toSet()
    assertTrue(TaskCsvWarningReason.TAG_BLANK_OMITTED in reasons)
    assertTrue(TaskCsvWarningReason.TAG_DUPLICATE_OMITTED in reasons)
    assertTrue(TaskCsvWarningReason.TAG_TOO_LONG_OMITTED in reasons)
    assertTrue(TaskCsvWarningReason.TAG_LIMIT_OMITTED in reasons)
}
```

Use compact test helpers for `project`, `workspaceId`, and target statuses;
reuse the fixture builders already present in `TasksCsvParserTest` rather than
introducing a fixture framework.

- [ ] **Step 3: Implement target, review, and one conversion pass**

Add these public values to `GenericTasksCsvMapper.kt`:

```kotlin
data class TaskCsvTarget(
    val projects: List<Project>,
    val workflowStatuses: List<WorkflowStatus>,
    val tags: List<Tag>,
)

data class GenericTasksCsvReview(
    val rows: List<ImportedTaskRow>,
    val warnings: List<TaskCsvWarning>,
    val blockingIssues: Set<TaskCsvBlockingIssue>,
    val blockingMessage: String?,
    val statusValues: List<String>,
    val priorityValues: List<String>,
    val ambiguousDatesPresent: Boolean,
    val estimateValuesPresent: Boolean,
    val tagValuesPresent: Boolean,
    val tagSamples: List<String>,
    val capturedZoneId: String,
    val skippedTaskCount: Int,
    val omittedValueCount: Int,
    val ignoredColumnIndexes: List<Int>,
    val newProjectCount: Int,
    val newTagCount: Int,
) {
    val canImport: Boolean
        get() = rows.isNotEmpty() && blockingIssues.isEmpty()
}

fun WorkspaceSnapshot.toTaskCsvTarget() = TaskCsvTarget(
    projects = projects.filter { it.archivedAt == null },
    workflowStatuses = workflowStatuses.filter { it.archivedAt == null },
    tags = tags,
)

fun emptyTaskCsvTarget() = TaskCsvTarget(
    projects = emptyList(),
    workflowStatuses = WorkflowStatus.defaults(null),
    tags = emptyList(),
)
```

Add the exact function signature
`reviewGenericTasksCsv(document: GenericTasksCsvDocument, mapping:
TaskCsvMapping, target: TaskCsvTarget, zone: ZoneId, completionFallback:
Instant): GenericTasksCsvReview`. It must make one ordered pass over source
rows. Before the pass, build exact/folded project and tag indexes from
`TaskCsvTarget`, empty insertion-ordered maps for proposed names, mutable rows
and warnings, exact skip/omission counters, and a linked blocker set.

Before reading any cell, validate that every mapped index is in
`document.headers.indices` and that `mapping.columns.values` contains no
duplicate. On failure, return an empty review with
`COLUMN_MAPPING_INVALID`; this rechecks the one-source-to-one-destination rule
even if a caller bypasses the UI.

Within the valid pass, first count and warn for a wholly empty record. Then
read the mapped Title, trim it, and count/warn/skip the row when it is blank or
longer than 240 characters. For each surviving row, resolve the optional
fields below and construct one complete `ImportedTaskRow`. Every omitted cell
or tag token increments `omittedValueCount` exactly once and appends one typed
warning:

- Project: trim, reject over 120, reuse target folded name, otherwise reuse the
  first proposed folded name. New case variants warn. Null means Inbox.
- Status: require a choice for every distinct non-empty value. `IGNORE` means
  Backlog plus `STATUS_OMITTED`. Map Backlog/Started/Completed to
  `BACKLOG`/`STARTED`/`COMPLETED`, select the first target status of that
  semantic by rank, and use default names for proposed projects. If unavailable,
  choose active Backlog, warn `STATUS_FALLBACK`, and clear completion for a
  Done fallback. If no Backlog exists, add `TARGET_REJECTED`.
- Priority: require each non-empty distinct value. Map to the existing
  `Priority`; `IGNORE` becomes `Priority.NONE` plus `PRIORITY_OMITTED`.
- Start/Due/Completion: use the exact date policy in Step 4 below.
- Estimate: positive whole number only; minutes unchanged, hours through
  `Math.multiplyExact(value, 60L)`; invalid/overflow becomes null plus warning.
- Tags: split using the selected mode, trim, drop blanks/over-64 values, folded
  deduplicate, reuse target/proposed canonical casing, and keep the first 50
  valid distinct tags. Warn once per dropped token with
  `TAG_BLANK_OMITTED`, `TAG_TOO_LONG_OMITTED`, `TAG_DUPLICATE_OMITTED`, or
  `TAG_LIMIT_OMITTED`; use `TAG_CASE_MERGED` only when a retained token adopts
  canonical target/proposed casing.
- Description: keep at most 20,000 characters as-is; an overlong value becomes
  empty plus `DESCRIPTION_OMITTED`.

Count omissions, not transformations: increment once for invalid project,
ignored/fallback/overridden status, ignored priority, invalid start/due/
completion/estimate/description, and every dropped tag token. Do not increment
for a skipped row, canonical case merge, selected ambiguous-date order,
date-only time, inferred zone, or inferred completion instant. If a Done status
falls back to Backlog and its completion is cleared, count the status and
completion losses separately.

For existing preview reuse, add one private `TaskCsvTarget.toPreviewSnapshot`
extension in this file. It creates `HomeSnapshot(LocalDate.EPOCH, emptyList(),
emptyList(), projects, null, 0)` and a `WorkspaceSnapshot` containing only the
target projects, workflow statuses, and tags; every other list is empty. Call
`previewTasksImport(rows, target.toPreviewSnapshot())` after mapper-side
canonicalisation. This is an adapter to the existing authority, not another
preview implementation.

- [ ] **Step 4: Implement deterministic date and completion parsing**

Use Java time only. Parse in this order:

1. `Instant.parse` / `OffsetDateTime.parse` for explicit ISO values;
2. `LocalDateTime` ISO and the numeric date-time patterns;
3. `LocalDate` ISO, numeric patterns, `d MMM uuuu`, and `MMM d uuuu` with
   `Locale.UK` and `ResolverStyle.STRICT`.

For numeric `d/M/uuuu` versus `M/d/uuuu`, inspect the first two components:
first over 12 is day/month, second over 12 is month/day, both at most 12 require
`mapping.dateOrder`, and impossible dates are omitted. Accept `/`, `-`, or `.`
consistently within one value and optional 24-hour `HH:mm[:ss]` time.

Use this parse result and formatter construction so ambiguity is never inferred
from a failed formatter attempt:

```kotlin
private sealed interface ParsedDateValue {
    data class Offset(val value: OffsetDateTime) : ParsedDateValue
    data class LocalDateTimeValue(val value: LocalDateTime) : ParsedDateValue
    data class Date(val value: LocalDate) : ParsedDateValue
    data object Ambiguous : ParsedDateValue
    data object Invalid : ParsedDateValue
}

private val NUMERIC_DATE = Regex(
    """^(\d{1,2})([/.-])(\d{1,2})\2(\d{4})""" +
        """(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?$""",
)

private fun numericFormatter(
    order: TaskCsvDateOrder,
    separator: Char,
): DateTimeFormatter = DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern(
        if (order == TaskCsvDateOrder.DAY_MONTH_YEAR) {
            "d${separator}M${separator}uuuu"
        } else {
            "M${separator}d${separator}uuuu"
        },
    )
    .optionalStart()
    .appendLiteral(' ')
    .appendPattern("H:mm")
    .optionalStart()
    .appendPattern(":ss")
    .optionalEnd()
    .optionalEnd()
    .toFormatter(Locale.UK)
    .withResolverStyle(ResolverStyle.STRICT)

private fun englishFormatter(pattern: String) = DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern(pattern)
    .toFormatter(Locale.UK)
    .withResolverStyle(ResolverStyle.STRICT)

private val ENGLISH_DMY = englishFormatter("d MMM uuuu")
private val ENGLISH_MDY = englishFormatter("MMM d uuuu")
private val ISO_SPACE_LOCAL_DATE_TIME =
    DateTimeFormatter.ofPattern("uuuu-MM-dd H:mm[:ss]", Locale.UK)
        .withResolverStyle(ResolverStyle.STRICT)
```

`parseDateValue` first tries `Instant.parse` (converted with
`atOffset(ZoneOffset.UTC)`), then `OffsetDateTime` ISO, ISO local date-time,
ISO space local date-time, and ISO local date. It then matches `NUMERIC_DATE`,
chooses or requests the one import-wide order from the first two components,
replaces the matched date/time separator `T` with a space, and parses with
`numericFormatter` into a `LocalDateTime` when groups 5-7 are present or
`LocalDate` otherwise. Finally try `ENGLISH_DMY` and `ENGLISH_MDY`.
Catch only `DateTimeParseException`/`DateTimeException` from each attempt and
return `Invalid` after all formats fail.

Resolve locals and normalise offsets with:

```kotlin
private fun LocalDateTime.toImportedMoment(zone: ZoneId): ZonedMoment {
    val resolved = atZone(zone)
    return ZonedMoment(resolved.toInstant(), resolved.offset.id)
}

private fun OffsetDateTime.toImportedMoment() = ZonedMoment(toInstant(), offset.id)
```

Date-only defaults are explicit:

```kotlin
private val START_DATE_ONLY_TIME = LocalTime.of(9, 0)
private val DUE_DATE_ONLY_TIME = LocalTime.of(17, 0)
private val COMPLETION_DATE_ONLY_TIME = LocalTime.of(17, 0)
```

Emit `START_TIME_INFERRED`, `DUE_TIME_INFERRED`, or
`COMPLETION_TIME_INFERRED` when those date-only defaults are applied. These
warnings do not increment the omission count because the value is retained.
Emit the matching `*_ZONE_INFERRED` warning whenever a date or date-time has no
explicit offset and is resolved in the captured `zone`; expose `zone.id` as
`capturedZoneId` so the review names the exact zone. Offset-bearing values emit
no zone warning.

Completion recognises case-insensitive `yes`, `true`, `1`, `done`,
`completed`, and `closed` as completed; `no`, `false`, `0`, `open`, and blank
as open/no timestamp. A completed value without a time and a Done status
without a completion column both use `completionFallback` and emit
`COMPLETION_INFERRED`. An explicit completed value overrides Backlog/In
progress and emits `COMPLETION_OVERRIDES_STATUS`. An explicit open value
overrides a Done status to Backlog with the same warning. Unknown values are
omitted with `COMPLETION_OMITTED`.

- [ ] **Step 5: Make blockers and counts exact**

After conversion:

- expose distinct trimmed status/priority source values in first-seen order;
- add the corresponding choice blocker if any value lacks a choice;
- add `DATE_ORDER_REQUIRED` only when at least one mapped date is truly
  ambiguous and no date order is selected;
- add `ESTIMATE_UNIT_REQUIRED` and `TAG_MODE_REQUIRED` only when the mapped
  column contains a non-empty value and its option is null;
- derive ignored column indexes as all source indexes absent from
  `mapping.columns.values`;
- expose at most the first ten distinct canonical `tagNames` from ready rows as
  `tagSamples`, preserving first-seen order;
- derive new-project/new-tag counts from the canonical proposed-name maps, add
  the matching blocker above 500/1,000, and call `previewTasksImport` only when
  those limits hold;
- on a ready repository preview, assert its task/project/tag counts equal the
  mapper counts rather than maintaining a second resolution policy; and
- retain any other repository-preview rejection message in `blockingMessage`
  with `TARGET_REJECTED`.

No row is sent to `previewTasksImport` until it satisfies the current
`ImportedTaskRow` validation bounds.

- [ ] **Step 6: Run mapper, strict parser, and import host suites GREEN**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "*GenericTasksCsvMapperTest" \
  --tests "*TasksCsvParserTest" \
  --tests "*InMemoryImportTasksTest" \
  --console=plain
```

Expected: PASS, including direct 500/501-project and 1,000/1,001-tag generic
review assertions; do not rely only on the strict parser's limit tests.

- [ ] **Step 7: Commit the pure mapping engine**

```bash
git add \
  core/data/src/main/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapper.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/export/GenericTasksCsvMapperTest.kt
git commit -m "feat: map generic task csv rows"
```

### Task 5: Own transient mapping state without a vault dependency

**Files:**

- Create:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/TaskMigrationUiState.kt`
- Create: `app/src/main/kotlin/app/opentasks/TaskMigrationViewModel.kt`
- Create: `app/src/test/kotlin/app/opentasks/TaskMigrationViewModelTest.kt`

**Interfaces:**

- Consumes: `parseGenericTasksCsv`, `suggestTaskCsvMapping`,
  `reviewGenericTasksCsv`, a `TaskCsvTarget`, a selected `Uri`, the device
  zone, and the confirmation clock.
- Produces: `TaskMigrationUiState`, one buffered document-picker request,
  mapping-edit methods, and a one-shot list of confirmed `ImportedTaskRow`
  values. It never injects or calls `VaultRepository`.

- [ ] **Step 1: Write RED tests for intake, edits, and one-shot confirmation**

Create `TaskMigrationViewModelTest.kt`. Use the existing JUnit dependency,
`Dispatchers.Unconfined`, and the internal `acceptDocument` seam; do not add
coroutines-test or a mocking library. Add these tests:

```kotlin
@Test
fun beginRequestsOnePickerAndCancellationIsQuiet() {
    val subject = viewModel()

    subject.begin(emptyTaskCsvTarget())

    assertNotNull(subject.openDocumentRequests.tryReceive().getOrNull())
    subject.onDocumentSelected(null)
    assertNull(subject.state.value)
}

@Test
fun cancellingAReplacementPickerKeepsTheCurrentReview() {
    val subject = viewModel()
    load(subject, "Title\r\nOne\r\n")
    val before = subject.state.value

    subject.chooseAnother()
    assertNotNull(subject.openDocumentRequests.tryReceive().getOrNull())
    subject.onDocumentSelected(null)

    assertSame(before, subject.state.value)
}

@Test
fun acceptedBytesAreClearedAfterParsing() {
    val subject = viewModel()
    val bytes = "Title\r\nOne\r\n".toByteArray()
    subject.begin(emptyTaskCsvTarget())
    subject.openDocumentRequests.tryReceive()

    subject.acceptDocument("tasks.csv", bytes)

    assertTrue(bytes.all { it == 0.toByte() })
    assertTrue(subject.state.value is TaskMigrationUiState.Review)
}

@Test
fun parseFailurePublishesTheExactLoadFailureAndRow() {
    val subject = viewModel()

    load(subject, "Title\r\n\"unterminated")

    val failure = subject.state.value as TaskMigrationUiState.LoadFailure
    assertEquals(TaskMigrationLoadFailure.MALFORMED, failure.reason)
    assertEquals(1, failure.rowNumber)
}

@Test
fun remappingAColumnRemovesItFromThePreviousField() {
    val subject = viewModel()
    load(subject, "Title,Project\r\nOne,Launch\r\n")

    subject.mapField(TaskCsvField.PROJECT, 0)

    val mapping = review(subject).mapping
    assertNull(mapping.columns[TaskCsvField.TITLE])
    assertEquals(0, mapping.columns[TaskCsvField.PROJECT])
    assertTrue(
        TaskCsvBlockingIssue.TITLE_MAPPING_REQUIRED in
            review(subject).blockingIssues,
    )
}

@Test
fun statusPriorityDateEstimateAndTagEditsRecomputeReview() {
    val subject = viewModel()
    load(
        subject,
        "Title,Status,Priority,Due,Estimate,Tags\r\n" +
            "One,3,2,03/04/2026,2,a;b|c\r\n",
    )
    assertTrue(review(subject).blockingIssues.containsAll(
        setOf(
            TaskCsvBlockingIssue.STATUS_CHOICES_REQUIRED,
            TaskCsvBlockingIssue.PRIORITY_CHOICES_REQUIRED,
            TaskCsvBlockingIssue.DATE_ORDER_REQUIRED,
            TaskCsvBlockingIssue.ESTIMATE_UNIT_REQUIRED,
            TaskCsvBlockingIssue.TAG_MODE_REQUIRED,
        ),
    ))

    subject.chooseStatus("3", TaskCsvStatusChoice.IN_PROGRESS)
    subject.choosePriority("2", TaskCsvPriorityChoice.HIGH)
    subject.chooseDateOrder(TaskCsvDateOrder.DAY_MONTH_YEAR)
    subject.chooseEstimateUnit(TaskCsvEstimateUnit.HOURS)
    subject.chooseTagMode(TaskCsvTagMode.SINGLE)

    assertTrue(review(subject).blockingIssues.isEmpty())
    assertEquals(120L, subject.confirm(emptyTaskCsvTarget())!!.single().estimateMinutes)
}

@Test
fun confirmRevalidatesAgainstTheLatestTarget() {
    val subject = viewModel()
    load(subject, "Title\r\nOne\r\n")

    val rows = subject.confirm(
        TaskCsvTarget(
            projects = emptyList(),
            workflowStatuses = emptyList(),
            tags = emptyList(),
        ),
    )

    assertNull(rows)
    assertTrue(TaskCsvBlockingIssue.TARGET_REJECTED in review(subject).blockingIssues)
}

@Test
fun confirmUsesTheFreshConfirmationInstantForInferredDoneRows() {
    var instant = Instant.parse("2026-08-24T12:00:00Z")
    val subject = viewModel(now = { instant }, zone = { ZoneId.of("UTC") })
    load(subject, "Title,Status\r\nOne,Done\r\n")
    instant = Instant.parse("2026-08-24T12:05:00Z")

    val row = subject.confirm(emptyTaskCsvTarget())!!.single()

    assertEquals(instant, row.completedAt)
}

@Test
fun aSecondConfirmWhileCommittingReturnsNoRows() {
    val subject = viewModel()
    load(subject, "Title\r\nOne\r\n")

    assertNotNull(subject.confirm(emptyTaskCsvTarget()))
    assertNull(subject.confirm(emptyTaskCsvTarget()))
}

@Test
fun aRejectedCommitKeepsTheReviewAndASuccessClearsIt() {
    val subject = viewModel()
    load(subject, "Title\r\nOne\r\n")
    subject.confirm(emptyTaskCsvTarget())

    subject.onCommitFinished(success = false)
    assertFalse(review(subject).isCommitting)
    assertNotNull(subject.confirm(emptyTaskCsvTarget()))
    subject.onCommitFinished(success = true)
    assertNull(subject.state.value)
}

@Test
fun cancelDropsTheDocumentAndReview() {
    val subject = viewModel()
    load(subject, "Title\r\nOne\r\n")

    subject.cancel()

    assertNull(subject.state.value)
    assertNull(subject.confirm(emptyTaskCsvTarget()))
}
```

Build the subject with this direct constructor shape:

```kotlin
private fun viewModel(
    now: () -> Instant = { Instant.parse("2026-08-24T12:00:00Z") },
    zone: () -> ZoneId = { ZoneId.of("Asia/Bangkok") },
) = TaskMigrationViewModel(
    readDocument = { null },
    now = now,
    zoneProvider = zone,
    ioDispatcher = Dispatchers.Unconfined,
)

private fun load(subject: TaskMigrationViewModel, source: String) {
    subject.begin(emptyTaskCsvTarget())
    assertNotNull(subject.openDocumentRequests.tryReceive().getOrNull())
    subject.acceptDocument("tasks.csv", source.toByteArray())
}

private fun review(subject: TaskMigrationViewModel) =
    subject.state.value as TaskMigrationUiState.Review
```

Import the exact assertion methods, migration model values, target helpers,
and `TaskMigrationUiState` referenced above.

- [ ] **Step 2: Run the app unit test and verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests "*TaskMigrationViewModelTest" \
  --console=plain
```

Expected: FAIL compilation because the state and ViewModel do not exist.

- [ ] **Step 3: Add the feature-facing state contract**

Create `TaskMigrationUiState.kt` with plain Kotlin values only:

```kotlin
package app.opentasks.feature.more

import app.opentasks.core.model.TaskCsvBlockingIssue
import app.opentasks.core.model.TaskCsvMapping
import app.opentasks.core.model.TaskCsvWarning

enum class TaskMigrationLoadFailure {
    UNREADABLE,
    TOO_LARGE,
    INVALID_UTF8,
    MALFORMED,
    TOO_MANY_ROWS,
    TOO_MANY_COLUMNS,
    MISSING_HEADER,
    ROW_WIDER_THAN_HEADER,
}

data class TaskMigrationColumnUi(
    val index: Int,
    val header: String,
    val samples: List<String>,
)

data class TaskMigrationSummaryUi(
    val readyTaskCount: Int,
    val skippedTaskCount: Int,
    val omittedValueCount: Int,
    val newProjectCount: Int,
    val newTagCount: Int,
)

sealed interface TaskMigrationUiState {
    data class LoadFailure(
        val fileName: String?,
        val reason: TaskMigrationLoadFailure,
        val rowNumber: Int?,
    ) : TaskMigrationUiState

    data class Review(
        val fileName: String,
        val sourceRowCount: Int,
        val sourceColumnCount: Int,
        val columns: List<TaskMigrationColumnUi>,
        val mapping: TaskCsvMapping,
        val statusValues: List<String>,
        val priorityValues: List<String>,
        val ambiguousDatesPresent: Boolean,
        val estimateValuesPresent: Boolean,
        val tagValuesPresent: Boolean,
        val tagSamples: List<String>,
        val capturedZoneId: String,
        val summary: TaskMigrationSummaryUi,
        val warnings: List<TaskCsvWarning>,
        val blockingIssues: Set<TaskCsvBlockingIssue>,
        val blockingMessage: String?,
        val ignoredHeaders: List<String>,
        val isCommitting: Boolean,
    ) : TaskMigrationUiState {
        val canImport: Boolean
            get() = summary.readyTaskCount > 0 &&
                blockingIssues.isEmpty() &&
                !isCommitting
    }
}
```

Do not place source cells or task text in this state beyond the three bounded
column samples, ten resulting tag samples, captured zone identifier, and
warning row numbers approved for display.

- [ ] **Step 4: Implement one Activity-scoped migration ViewModel**

Create `TaskMigrationViewModel.kt` with this constructor and public surface:

```kotlin
internal data class SelectedTaskMigrationDocument(
    val displayName: String,
    val bytes: ByteArray,
)

@HiltViewModel
class TaskMigrationViewModel internal constructor(
    private val readDocument: suspend (Uri) -> SelectedTaskMigrationDocument?,
    private val now: () -> Instant,
    private val zoneProvider: () -> ZoneId,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        readDocument = { uri -> context.readTaskMigrationDocument(uri) },
        now = Instant::now,
        zoneProvider = ZoneId::systemDefault,
        ioDispatcher = Dispatchers.IO,
    )

    val openDocumentRequests = Channel<Unit>(Channel.BUFFERED)
    val state: StateFlow<TaskMigrationUiState?>

    fun begin(target: TaskCsvTarget)
    fun onDocumentSelected(uri: Uri?)
    internal fun acceptDocument(displayName: String, bytes: ByteArray)
    fun mapField(field: TaskCsvField, columnIndex: Int?)
    fun chooseStatus(value: String, choice: TaskCsvStatusChoice)
    fun choosePriority(value: String, choice: TaskCsvPriorityChoice)
    fun chooseDateOrder(order: TaskCsvDateOrder)
    fun chooseEstimateUnit(unit: TaskCsvEstimateUnit)
    fun chooseTagMode(mode: TaskCsvTagMode)
    fun confirm(latestTarget: TaskCsvTarget): List<ImportedTaskRow>?
    fun onCommitFinished(success: Boolean)
    fun chooseAnother()
    fun cancel()
}
```

Use one private draft containing the parsed document, current mapping, target,
captured `ZoneId`, and display name, plus one nullable `targetAtSelection`
field for the interval before parsing. `begin` clears any prior draft/state,
stores the supplied target in `targetAtSelection`, and `trySend`s one picker
request. A cancelled picker leaves state null. A non-null `Uri` is read on
`ioDispatcher`; query
`OpenableColumns.DISPLAY_NAME`, fall back to `tasks.csv`, and read at most
`MAX_TASKS_CSV_BYTES + 1` bytes with `InputStream.readNBytes`. Do not call
`takePersistableUriPermission` and do not create a file.

In `onDocumentSelected`, rethrow `CancellationException`; map a null stream,
`SecurityException`, `IOException`, or any other provider `Exception` to
`TaskMigrationLoadFailure.UNREADABLE`. A display-name query failure alone uses
the fallback name and still attempts the stream. Never log the exception,
filename, URI, bytes, headers, samples, or warnings.

Hold the returned `SelectedTaskMigrationDocument?` in a local declared before
the read and fill `selected?.bytes` in the coroutine's outer `finally`, even
though `acceptDocument` also clears it. This guarantees cancellation after a
successful read cannot bypass byte clearing; double clearing is harmless.

`acceptDocument` parses synchronously and fills the supplied byte array with
zero in `finally`. Translate every `GenericTasksCsvFailure` one-for-one to
`TaskMigrationLoadFailure`, preserving the row number. On success, capture
`zoneProvider()` once, call `describeGenericTasksCsv` and
`suggestTaskCsvMapping`, then publish the pure review, including
`review.capturedZoneId` and `review.tagSamples` unchanged.

Every mapping edit copies the mapping and republishes a review. When a source
column is assigned to one field, remove that index from any other field first.
When Status, Priority, Estimate, or Tags changes source column, discard only
that field's dependent choices and rerun the same conservative suggestion
helper against the new mapping. Do not persist a draft or source URI.

Use this merge so editing one field does not erase reviewed choices for the
others:

```kotlin
private fun remap(
    current: TaskCsvMapping,
    field: TaskCsvField,
    columnIndex: Int?,
    document: GenericTasksCsvDocument,
): TaskCsvMapping {
    val withoutField = current.columns - field
    val withoutDuplicate = if (columnIndex == null) {
        withoutField
    } else {
        withoutField.filterValues { it != columnIndex }
    }
    val columns = if (columnIndex == null) {
        withoutDuplicate
    } else {
        withoutDuplicate + (field to columnIndex)
    }
    val suggested = suggestTaskCsvMapping(document, columns)
    return current.copy(
        columns = columns,
        statusChoices = if (
            current.columns[TaskCsvField.STATUS] != columns[TaskCsvField.STATUS]
        ) suggested.statusChoices else current.statusChoices,
        priorityChoices = if (
            current.columns[TaskCsvField.PRIORITY] != columns[TaskCsvField.PRIORITY]
        ) suggested.priorityChoices else current.priorityChoices,
        estimateUnit = if (
            current.columns[TaskCsvField.ESTIMATE] != columns[TaskCsvField.ESTIMATE]
        ) suggested.estimateUnit else current.estimateUnit,
        tagMode = if (
            current.columns[TaskCsvField.TAGS] != columns[TaskCsvField.TAGS]
        ) suggested.tagMode else current.tagMode,
    )
}
```

`confirm` recomputes from the stored document and captured zone against
`latestTarget`, passing a fresh `now()` as `completionFallback`. If the new
review blocks, store `latestTarget` back into the draft, publish the review,
and return null. Otherwise store that target, publish the same review with
`isCommitting = true`, and return its rows once. While committing, another
confirm returns null. `onCommitFinished(true)` calls `cancel`; false changes
only `isCommitting` to false so a repository rejection remains reviewable.
`chooseAnother` retains the last target and current review while it sends
another picker request; a cancelled replacement therefore changes nothing. A
success or load failure replaces the prior draft/state. `cancel` drops every
document/mapping/review reference.

- [ ] **Step 5: Run the ViewModel and mapper suites GREEN**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests "*TaskMigrationViewModelTest" \
  --console=plain
./gradlew :core:data:testDebugUnitTest \
  --tests "*GenericTasksCsvMapperTest" \
  --console=plain
```

Expected: PASS. Confirm from the test output that the byte-clear, latest-target,
fresh-clock, double-confirm, rejection, and success cases all executed.

- [ ] **Step 6: Commit the transient state boundary**

```bash
git add \
  feature/more/src/main/kotlin/app/opentasks/feature/more/TaskMigrationUiState.kt \
  app/src/main/kotlin/app/opentasks/TaskMigrationViewModel.kt \
  app/src/test/kotlin/app/opentasks/TaskMigrationViewModelTest.kt
git commit -m "feat: own transient task csv migration state"
```

### Task 6: Build the shared combined mapping and review page

**Files:**

- Create:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/TaskMigrationScreen.kt`
- Create:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/TaskMigrationScreenInstrumentedTest.kt`
- Modify: `feature/more/src/main/res/values/strings.xml`

**Interfaces:**

- Consumes: only `TaskMigrationUiState` and callbacks for its mapping choices,
  file selection, cancellation, and final import.
- Produces: one stateless, scrollable Compose surface shared by Welcome and
  More. It has no picker, repository, `core:data`, or navigation dependency.

- [ ] **Step 1: Write RED Compose tests for the combined page**

Create `TaskMigrationScreenInstrumentedTest.kt` using `createComposeRule`,
`HideWindowsRule`, and the existing `OpenTasksTheme`. Add these tests and the
fixture below:

```kotlin
@Test
fun loadFailureOffersChooseAnotherAndCancel() {
    val chooseAnother = AtomicInteger()
    val cancel = AtomicInteger()
    setScreen(
        TaskMigrationUiState.LoadFailure(
            fileName = "broken.csv",
            reason = TaskMigrationLoadFailure.MALFORMED,
            rowNumber = 4,
        ),
        onChooseAnother = { chooseAnother.incrementAndGet() },
        onCancel = { cancel.incrementAndGet() },
    )

    composeRule.onNodeWithText("broken.csv").assertIsDisplayed()
    composeRule.onNodeWithText("The CSV structure is invalid at row 4.")
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    composeRule.onNodeWithTag("migration-choose-another").performClick()
    composeRule.onNodeWithTag("migration-cancel").performClick()
    assertEquals(1, chooseAnother.get())
    assertEquals(1, cancel.get())
}

@Test
fun reviewShowsSourceSamplesIgnoredColumnsAndCreateOnlyDisclosure() {
    setScreen(reviewState())

    composeRule.onNodeWithText("tasks.csv • 2 rows • 2 columns").assertIsDisplayed()
    composeRule.onNodeWithText("Samples: One").assertIsDisplayed()
    composeRule.onNodeWithText("Ignored columns").assertIsDisplayed()
    composeRule.onNodeWithTag("migration-ignored-columns").assertIsDisplayed()
    composeRule.onNodeWithText(
        "This creates new tasks. Importing the same file again creates duplicates.",
    ).assertIsDisplayed()
    composeRule.onNodeWithText("Dates without an offset use Asia/Bangkok.")
        .assertIsDisplayed()
}

@Test
fun destinationSelectorForwardsItsSelectedColumn() {
    val selected = AtomicReference<Pair<TaskCsvField, Int?>?>()
    setScreen(reviewState(), onMapField = { field, index -> selected.set(field to index) })

    composeRule.onNodeWithTag("migration-field-title").performClick()
    composeRule.onNodeWithTag("migration-field-title-option-1").performClick()

    assertEquals(TaskCsvField.TITLE to 1, selected.get())
}

@Test
fun unresolvedStatusAndPriorityValuesExposeExplicitChoices() {
    val status = AtomicReference<Pair<String, TaskCsvStatusChoice>?>()
    val priority = AtomicReference<Pair<String, TaskCsvPriorityChoice>?>()
    setScreen(
        reviewState(
            statusValues = listOf("3"),
            priorityValues = listOf("2"),
            blockers = setOf(
                TaskCsvBlockingIssue.STATUS_CHOICES_REQUIRED,
                TaskCsvBlockingIssue.PRIORITY_CHOICES_REQUIRED,
            ),
        ),
        onStatusChoice = { value, choice -> status.set(value to choice) },
        onPriorityChoice = { value, choice -> priority.set(value to choice) },
    )

    composeRule.onNodeWithTag("migration-status-0-done").performClick()
    composeRule.onNodeWithTag("migration-priority-0-urgent").performClick()
    assertEquals("3" to TaskCsvStatusChoice.DONE, status.get())
    assertEquals("2" to TaskCsvPriorityChoice.URGENT, priority.get())
}

@Test
fun conditionalDateEstimateAndTagControlsAppearOnlyWhenNeeded() {
    var state by mutableStateOf(reviewState())
    val dateOrder = AtomicReference<TaskCsvDateOrder?>()
    val estimateUnit = AtomicReference<TaskCsvEstimateUnit?>()
    val tagMode = AtomicReference<TaskCsvTagMode?>()
    composeRule.setContent {
        OpenTasksTheme {
            MigrationTestScreen(
                state = state,
                onDateOrder = dateOrder::set,
                onEstimateUnit = estimateUnit::set,
                onTagMode = tagMode::set,
            )
        }
    }
    composeRule.onNodeWithTag("migration-date-order").assertDoesNotExist()
    composeRule.onNodeWithTag("migration-estimate-unit").assertDoesNotExist()
    composeRule.onNodeWithTag("migration-tag-mode").assertDoesNotExist()

    composeRule.runOnIdle {
        state = reviewState(
            ambiguousDatesPresent = true,
            estimateValuesPresent = true,
            tagValuesPresent = true,
            tagSamples = listOf("work", "urgent"),
        )
    }
    composeRule.onNodeWithTag("migration-date-order").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("migration-estimate-unit").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("migration-tag-mode").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("Resulting tags: work, urgent")
        .performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("migration-date-order-dmy").performClick()
    composeRule.onNodeWithTag("migration-estimate-hours").performClick()
    composeRule.onNodeWithTag("migration-tag-pipe").performClick()
    assertEquals(TaskCsvDateOrder.DAY_MONTH_YEAR, dateOrder.get())
    assertEquals(TaskCsvEstimateUnit.HOURS, estimateUnit.get())
    assertEquals(TaskCsvTagMode.PIPE, tagMode.get())
}

@Test
fun warningsShowRowFieldAndReasonAndChangeTheImportLabel() {
    setScreen(
        reviewState(
            warnings = listOf(
                TaskCsvWarning(
                    rowNumber = 2,
                    field = TaskCsvField.DUE,
                    reason = TaskCsvWarningReason.DUE_OMITTED,
                ),
            ),
        ),
    )

    composeRule.onNodeWithTag("migration-warning-0")
        .performScrollTo()
        .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    composeRule.onNodeWithText("Row 2 • Due • Invalid value omitted")
        .performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("Import 2 tasks anyway")
        .performScrollTo().assertIsDisplayed()
}

@Test
fun blockedOrCommittingReviewDisablesActions() {
    var state by mutableStateOf(
        reviewState(
            blockers = setOf(TaskCsvBlockingIssue.DATE_ORDER_REQUIRED),
        ),
    )
    composeRule.setContent {
        OpenTasksTheme { MigrationTestScreen(state) }
    }
    composeRule.onNodeWithTag("migration-import").performScrollTo().assertIsNotEnabled()

    composeRule.runOnIdle { state = reviewState(isCommitting = true) }
    composeRule.onNodeWithTag("migration-import").performScrollTo().assertIsNotEnabled()
    composeRule.onNodeWithTag("migration-choose-another")
        .performScrollTo().assertIsNotEnabled()
    composeRule.onNodeWithTag("migration-cancel").performScrollTo().assertIsNotEnabled()
}

@Test
fun compactAndExpandedWidthsKeepOneReachableScrollablePage() {
    var size by mutableStateOf(320.dp to 520.dp)
    composeRule.setContent {
        OpenTasksTheme {
            Box(Modifier.width(size.first).height(size.second)) {
                MigrationTestScreen(reviewState())
            }
        }
    }
    listOf(320.dp to 520.dp, 1_000.dp to 700.dp).forEach { next ->
        composeRule.runOnIdle { size = next }
        composeRule.onNodeWithTag("task-migration-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("migration-import")
            .performScrollTo().assertIsDisplayed()
    }
}

@Test
fun everyControlRemainsReachableAtTwoHundredPercentFont() {
    composeRule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
            OpenTasksTheme {
                Box(Modifier.width(320.dp).height(520.dp)) {
                    MigrationTestScreen(
                        reviewState(
                            ambiguousDatesPresent = true,
                            estimateValuesPresent = true,
                            tagValuesPresent = true,
                        ),
                    )
                }
            }
        }
    }

    listOf(
        "migration-field-title",
        "migration-field-project",
        "migration-field-status",
        "migration-field-priority",
        "migration-field-start",
        "migration-field-due",
        "migration-field-completion",
        "migration-field-estimate",
        "migration-field-tags",
        "migration-field-description",
        "migration-date-order",
        "migration-estimate-unit",
        "migration-tag-mode",
        "migration-import",
        "migration-choose-another",
        "migration-cancel",
    ).forEach { tag ->
        composeRule.onNodeWithTag(tag)
            .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
}
```

Add `reviewState`, `setScreen`, and the no-op callback wrapper used above:

```kotlin
private fun reviewState(
    statusValues: List<String> = emptyList(),
    priorityValues: List<String> = emptyList(),
    ambiguousDatesPresent: Boolean = false,
    estimateValuesPresent: Boolean = false,
    tagValuesPresent: Boolean = false,
    tagSamples: List<String> = emptyList(),
    warnings: List<TaskCsvWarning> = emptyList(),
    blockers: Set<TaskCsvBlockingIssue> = emptySet(),
    isCommitting: Boolean = false,
) = TaskMigrationUiState.Review(
    fileName = "tasks.csv",
    sourceRowCount = 2,
    sourceColumnCount = 2,
    columns = listOf(
        TaskMigrationColumnUi(0, "Title", listOf("One")),
        TaskMigrationColumnUi(1, "Project", listOf("Launch")),
    ),
    mapping = TaskCsvMapping(columns = mapOf(TaskCsvField.TITLE to 0)),
    statusValues = statusValues,
    priorityValues = priorityValues,
    ambiguousDatesPresent = ambiguousDatesPresent,
    estimateValuesPresent = estimateValuesPresent,
    tagValuesPresent = tagValuesPresent,
    tagSamples = tagSamples,
    capturedZoneId = "Asia/Bangkok",
    summary = TaskMigrationSummaryUi(2, 0, 0, 1, 1),
    warnings = warnings,
    blockingIssues = blockers,
    blockingMessage = null,
    ignoredHeaders = listOf("Project"),
    isCommitting = isCommitting,
)

private fun setScreen(
    state: TaskMigrationUiState,
    onMapField: (TaskCsvField, Int?) -> Unit = { _, _ -> },
    onStatusChoice: (String, TaskCsvStatusChoice) -> Unit = { _, _ -> },
    onPriorityChoice: (String, TaskCsvPriorityChoice) -> Unit = { _, _ -> },
    onDateOrder: (TaskCsvDateOrder) -> Unit = {},
    onEstimateUnit: (TaskCsvEstimateUnit) -> Unit = {},
    onTagMode: (TaskCsvTagMode) -> Unit = {},
    onChooseAnother: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    composeRule.setContent {
        OpenTasksTheme {
            MigrationTestScreen(
                state = state,
                onMapField = onMapField,
                onStatusChoice = onStatusChoice,
                onPriorityChoice = onPriorityChoice,
                onDateOrder = onDateOrder,
                onEstimateUnit = onEstimateUnit,
                onTagMode = onTagMode,
                onChooseAnother = onChooseAnother,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun MigrationTestScreen(
    state: TaskMigrationUiState,
    onMapField: (TaskCsvField, Int?) -> Unit = { _, _ -> },
    onStatusChoice: (String, TaskCsvStatusChoice) -> Unit = { _, _ -> },
    onPriorityChoice: (String, TaskCsvPriorityChoice) -> Unit = { _, _ -> },
    onDateOrder: (TaskCsvDateOrder) -> Unit = {},
    onEstimateUnit: (TaskCsvEstimateUnit) -> Unit = {},
    onTagMode: (TaskCsvTagMode) -> Unit = {},
    onChooseAnother: () -> Unit = {},
    onCancel: () -> Unit = {},
) = TaskMigrationScreen(
    state = state,
    onMapField = onMapField,
    onStatusChoice = onStatusChoice,
    onPriorityChoice = onPriorityChoice,
    onDateOrder = onDateOrder,
    onEstimateUnit = onEstimateUnit,
    onTagMode = onTagMode,
    onImport = {},
    onChooseAnother = onChooseAnother,
    onCancel = onCancel,
)
```

Pin the option tags referenced above in addition to
`task-migration-screen`, `migration-status-0`, `migration-priority-0`,
`migration-ignored-columns`, `migration-warning-0`, and the action/group tags.
Assert every section heading
through `SemanticsProperties.Heading`, errors through
`SemanticsProperties.Error`, and every actionable node at least 48 dp high.

- [ ] **Step 2: Compile the Compose tests and verify RED**

Run:

```bash
./gradlew :feature:more:compileDebugAndroidTestKotlin --console=plain
```

Expected: FAIL compilation because `TaskMigrationScreen` does not exist.

- [ ] **Step 3: Add all migration copy to string resources**

Add resource entries for:

- title **Import from another app**, source summary
  **%1$s • %2$d rows • %3$d columns**, and the disclosure
  **This creates new tasks. Importing the same file again creates duplicates.**;
- date disclosure **Dates without an offset use %1$s.**;
- all ten destination field labels, **Not mapped**, **Column %1$d: %2$s**,
  **Ignored columns**, **Samples: %1$s**, and **Resulting tags: %1$s**;
- the four status choices, six priority choices, both numeric date orders,
  minute/hour estimate units, and comma/semicolon/pipe/single-tag modes;
- ready, skipped, omitted, new-project, and new-tag count plurals;
- one human-readable row/field string for every `TaskCsvWarningReason` and one
  blocking summary for every `TaskCsvBlockingIssue`;
- the eight load-failure messages, including an optional row number for
  malformed and over-wide records; and
- **Choose another file**, **Cancel**, **Import %1$d tasks**,
  **Import %1$d tasks anyway**, and **Importing…**.

Use **Row %1$d • %2$s • %3$s** for field warnings and these exact reason
strings:

- Empty row skipped; Blank title; row skipped; Title over 240 characters; row
  skipped;
- Invalid project omitted; task will use Inbox; Project casing merged;
- Status ignored; Backlog will be used; Status unavailable; Backlog will be
  used; Priority ignored; None will be used;
- Invalid value omitted; No time supplied; 09:00 will be used; No time
  supplied; 17:00 will be used; No offset supplied; %1$s will be used;
- No completion time supplied; confirmation time will be used; Completion
  value overrides Status;
- Invalid or overflowing estimate omitted;
- Blank tag omitted; Tag over 64 characters omitted; Duplicate tag omitted;
  More than 50 tags; excess tag omitted; Tag casing merged; and
- Description over 20,000 characters omitted.

Map the blocking issues to these exact messages, using `blockingMessage` for
`TARGET_REJECTED` when it is non-null:

- **Map a Title column.**
- **Each mapped field needs one valid, different source column.**
- **Choose a destination for every status value.**
- **Choose a destination for every priority value.**
- **Choose day/month or month/day for ambiguous dates.**
- **Choose whether estimates are minutes or hours.**
- **Choose how tags are separated.**
- **No valid tasks are ready to import.**
- **This import would create more than 500 projects.**
- **This import would create more than 1,000 tags.**
- fallback: **The current workspace cannot accept this import.**

Map load failures exactly: **The selected file could not be read.**,
**Choose a CSV file no larger than 5 MiB.**,
**The CSV is not valid UTF-8.**,
**The CSV structure is invalid.** / **The CSV structure is invalid at row
%1$d.**, **A CSV can contain at most 5,000 task rows.**,
**A CSV can contain at most 100 columns.**,
**The CSV needs a non-empty header row.**, and
**Row %1$d has more values than the header.**.

Use plurals for task/row/project/tag counts. Do not build new English sentences
in Kotlin or expose enum names as copy.

- [ ] **Step 4: Implement the stateless screen with one scroll owner**

Add this public composable:

```kotlin
@Composable
fun TaskMigrationScreen(
    state: TaskMigrationUiState,
    onMapField: (TaskCsvField, Int?) -> Unit,
    onStatusChoice: (String, TaskCsvStatusChoice) -> Unit,
    onPriorityChoice: (String, TaskCsvPriorityChoice) -> Unit,
    onDateOrder: (TaskCsvDateOrder) -> Unit,
    onEstimateUnit: (TaskCsvEstimateUnit) -> Unit,
    onTagMode: (TaskCsvTagMode) -> Unit,
    onImport: () -> Unit,
    onChooseAnother: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Wrap the page in an always-enabled `BackHandler` that calls `onCancel` unless
the state is a committing `Review`; while committing it consumes Back without
leaving the operation. Use a full-size `Box` with safe-drawing and IME padding.
Centre a single `LazyColumn`
with `widthIn(max = 720.dp)`; this same native responsive layout serves compact
and expanded widths without a second screen. Task 7's existing-window-info
wrapper confines it to one physical pane on a separating fold.

For `LoadFailure`, show a heading, filename when present, the resource-mapped
failure/row text with error semantics, then 48 dp **Choose another file** and
**Cancel** actions. For `Review`, render in this exact logical order:

1. heading, filename/row/column summary, create-only disclosure, and the
   captured-zone disclosure;
2. one exposed dropdown per `TaskCsvField`, each listing **Not mapped** plus
   all headers labelled with their one-based column number, and the selected
   column's first three non-empty samples;
3. ignored headers;
4. one radio group per distinct status value and priority value;
5. date order only when `ambiguousDatesPresent`, estimate unit only when
   `estimateValuesPresent`, and tag mode plus the resulting `tagSamples` only
   when `tagValuesPresent`;
6. the five preview counts;
7. blocking messages and the full lazy warning list by source row, field, and
   reason; and
8. the final import button followed by **Choose another file** and **Cancel**.

Use `itemsIndexed` for warnings because multiple tag tokens in one row may
share the same typed reason. The import label uses **anyway** whenever warnings
is non-empty, shows **Importing…** while committing, and is enabled only when
`state.canImport`. While `isCommitting`, disable every mapping, reselect,
cancel, and back action as well as the import button; only the progress label
remains visible. Mark blocking messages and warnings with text/error
semantics; colour may supplement but never replace the text. Preserve focus
order by keeping controls in this single composition order. Source values use
`maxLines = 2` and `TextOverflow.Ellipsis` for layout only; callbacks retain
the complete underlying value.

- [ ] **Step 5: Compile Android tests GREEN and run only on a safe target**

Run:

```bash
./gradlew :feature:more:compileDebugAndroidTestKotlin --console=plain
```

Expected: PASS. If and only if an explicitly disposable device is the sole
selected target, run:

```bash
./gradlew :feature:more:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.feature.more.TaskMigrationScreenInstrumentedTest \
  --console=plain
```

Expected on that disposable target: PASS. Otherwise retain compile evidence
and do not touch the protected emulator.

- [ ] **Step 6: Commit the shared review surface**

```bash
git add \
  feature/more/src/main/kotlin/app/opentasks/feature/more/TaskMigrationScreen.kt \
  feature/more/src/androidTest/kotlin/app/opentasks/feature/more/TaskMigrationScreenInstrumentedTest.kt \
  feature/more/src/main/res/values/strings.xml
git commit -m "feat: review generic task csv mappings"
```

### Task 7: Add the top-level More entry and active-workspace dispatch

**Files:**

- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt`
- Modify:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/BackupRecoveryScreenInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `app/src/main/kotlin/app/opentasks/WindowPostureMapper.kt`
- Modify: `app/src/test/kotlin/app/opentasks/WindowPostureMapperTest.kt`

**Interfaces:**

- Consumes: the existing More overview, current `WorkspaceSnapshot`,
  `TaskMigrationViewModel`, `WorkspaceViewModel.execute`, navigation helper,
  and snackbar event collector.
- Produces: a top-level **Import from another app** row and active-workspace
  confirmation through `DomainCommand.ImportTasks`, with the current exact
  snackbar Undo unchanged.

- [ ] **Step 1: Write the RED More-entry test**

Add a test to `BackupRecoveryScreenInstrumentedTest` that proves migration is
an independent top-level action:

```kotlin
@Test
fun migrationEntryIsTopLevelAndDoesNotOpenBackupRecovery() {
    val migrations = AtomicInteger()
    composeRule.setContent {
        OpenTasksTheme {
            MoreScreen(
                tasks = emptyList(),
                projects = emptyList(),
                onImportFromAnotherApp = { migrations.incrementAndGet() },
                onRestoreProject = {},
                onRestoreTask = {},
                onPermanentlyDeleteTask = {},
            )
        }
    }

    composeRule.onNodeWithTag("open-task-migration")
        .performScrollTo()
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
        .performClick()
    assertEquals(1, migrations.get())
    composeRule.onNodeWithTag("backup-screen").assertDoesNotExist()
    composeRule.onNodeWithTag("more-overview").assertIsDisplayed()
}
```

- [ ] **Step 2: Compile the More test and verify RED**

Run:

```bash
./gradlew :feature:more:compileDebugAndroidTestKotlin --console=plain
```

Expected: FAIL compilation because `onImportFromAnotherApp` does not exist.

- [ ] **Step 3: Add one callback and one native destination row**

Add `onImportFromAnotherApp: () -> Unit = {}` beside `onOpenReview` in
`MoreScreen`. In the existing Workspace destination list, place this row after
Weekly review and before Templates:

```kotlin
DestinationRow(
    icon = Icons.Rounded.MoveToInbox,
    title = stringResource(R.string.task_migration_title),
    supportingText = stringResource(R.string.task_migration_more_supporting),
    onClick = onImportFromAnotherApp,
    modifier = Modifier.testTag("open-task-migration"),
)
```

Add `task_migration_more_supporting` with exact copy **Bring tasks from a CSV
file**. Do not add a `MoreDestination`, nest the flow under Backup & recovery,
or move the strict Open Tasks CSV action.

- [ ] **Step 4: Keep the shared page inside one unoccluded fold pane**

Add this test to `WindowPostureMapperTest` before production changes:

```kotlin
@Test
fun migrationPaneChoosesTheLargestUnoccludedPhysicalPane() {
    assertEquals(
        MigrationPane(0, 0, 1_000, 800),
        largestMigrationPane(1_000, 800, emptyList()),
    )
    assertEquals(
        MigrationPane(0, 0, 480, 800),
        largestMigrationPane(
            1_000,
            800,
            listOf(RawFold(480, 0, 40, 800, isSeparating = true)),
        ),
    )
    assertEquals(
        MigrationPane(0, 320, 480, 800),
        largestMigrationPane(
            1_000,
            800,
            listOf(
                RawFold(480, 0, 40, 800, isSeparating = true),
                RawFold(0, 300, 1_000, 20, isSeparating = true),
            ),
        ),
    )
    assertEquals(
        MigrationPane(0, 0, 500, 800),
        largestMigrationPane(
            1_000,
            800,
            listOf(RawFold(500, 0, 0, 800, isSeparating = true)),
        ),
    )
    assertEquals(
        MigrationPane(0, 0, 1_000, 800),
        largestMigrationPane(
            1_000,
            800,
            listOf(RawFold(480, 0, 40, 800, isSeparating = false)),
        ),
    )
}
```

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests "*WindowPostureMapperTest.migrationPane*" \
  --console=plain
```

Expected: FAIL compilation because `MigrationPane` and
`largestMigrationPane` do not exist.

Add this minimum pure calculation to `WindowPostureMapper.kt`:

```kotlin
internal data class MigrationPane(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
)

private data class PixelSpan(val start: Int, val endExclusive: Int) {
    val length: Int get() = endExclusive - start
}

internal fun largestMigrationPane(
    widthPx: Int,
    heightPx: Int,
    folds: List<RawFold>,
): MigrationPane {
    require(widthPx > 0 && heightPx > 0)
    val separating = folds.filter(RawFold::isSeparating)
    val horizontal = largestSpan(
        widthPx,
        separating.filter { it.heightPx >= it.widthPx }
            .map { PixelSpan(it.leftPx, it.leftPx + it.widthPx) },
    )
    val vertical = largestSpan(
        heightPx,
        separating.filter { it.heightPx < it.widthPx }
            .map { PixelSpan(it.topPx, it.topPx + it.heightPx) },
    )
    return MigrationPane(
        leftPx = horizontal.start,
        topPx = vertical.start,
        rightPx = horizontal.endExclusive,
        bottomPx = vertical.endExclusive,
    )
}

private fun largestSpan(length: Int, blocked: List<PixelSpan>): PixelSpan {
    var cursor = 0
    val available = mutableListOf<PixelSpan>()
    blocked.sortedBy(PixelSpan::start).forEach { raw ->
        val start = raw.start.coerceIn(0, length)
        val end = raw.endExclusive.coerceIn(start, length)
        if (start > cursor) available += PixelSpan(cursor, start)
        cursor = maxOf(cursor, end)
    }
    if (cursor < length) available += PixelSpan(cursor, length)
    return available.maxWithOrNull(
        compareBy<PixelSpan>(PixelSpan::length).thenBy { -it.start },
    ) ?: PixelSpan(0, length)
}
```

This conservatively treats a separating feature as spanning its whole axis;
non-separating folds do not constrain content. Hoist the existing
`WindowInfoTracker` collector from `OpenTasksApp` into
`rememberRawFolds(activity: Activity): List<RawFold>`, preserving its exact
`FoldingFeature -> RawFold` mapping. Add an internal `TaskMigrationPane`
composable in `OpenTasksApp.kt` that receives the collected folds and a content
lambda, calculates the pane inside `BoxWithConstraints`, and applies physical
`absolutePadding` from the pane edges before rendering the shared screen. It
adds no dependency and keeps RTL behavior inside the unoccluded physical pane.

```kotlin
@Composable
internal fun rememberRawFolds(activity: Activity): List<RawFold> {
    var folds by remember(activity) { mutableStateOf(emptyList<RawFold>()) }
    LaunchedEffect(activity) {
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .collect { layout ->
                folds = layout.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .map { feature ->
                        RawFold(
                            leftPx = feature.bounds.left,
                            topPx = feature.bounds.top,
                            widthPx = feature.bounds.width(),
                            heightPx = feature.bounds.height(),
                            isSeparating = feature.isSeparating,
                        )
                    }
            }
    }
    return folds
}

@Composable
internal fun TaskMigrationPane(
    folds: List<RawFold>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.coerceAtLeast(1)
        val heightPx = constraints.maxHeight.coerceAtLeast(1)
        val pane = remember(widthPx, heightPx, folds) {
            largestMigrationPane(widthPx, heightPx, folds)
        }
        val density = LocalDensity.current
        Box(
            Modifier
                .fillMaxSize()
                .absolutePadding(
                    left = with(density) { pane.leftPx.toDp() },
                    top = with(density) { pane.topPx.toDp() },
                    right = with(density) { (widthPx - pane.rightPx).toDp() },
                    bottom = with(density) { (heightPx - pane.bottomPx).toDp() },
                ),
        ) {
            content()
        }
    }
}
```

Run the same focused `WindowPostureMapperTest` command again. Expected: PASS.

- [ ] **Step 5: Wire the active picker and overlay at the app root**

Add this defaulted parameter to `OpenTasksApp`:

```kotlin
taskMigrationViewModel: TaskMigrationViewModel = viewModel(),
```

Collect its state with lifecycle, register one
`ActivityResultContracts.OpenDocument()` launcher, and collect
`openDocumentRequests` in a `LaunchedEffect`. Launch only:

```kotlin
arrayOf("text/csv", "text/comma-separated-values", "text/plain")
```

Pass this callback to `MoreScreen`:

```kotlin
onImportFromAnotherApp = {
    taskMigrationViewModel.begin(snapshot.toTaskCsvTarget())
},
```

After the existing `navigate` helper and snackbar-event collector are in
scope, render `TaskMigrationPane(rawFolds) { TaskMigrationScreen(...) }`
instead of the navigation scaffold while migration state is non-null. Reuse
the same `SnackbarHostState` in the overlay scaffold so command rejection and
the eventual Undo remain visible. Forward each edit/cancel/reselect callback
directly to the migration ViewModel.

The final callback must revalidate against the current snapshot and dispatch
only the returned rows:

```kotlin
onImport = {
    taskMigrationViewModel.confirm(snapshot.toTaskCsvTarget())?.let { rows ->
        viewModel.execute(DomainCommand.ImportTasks(rows)) { result ->
            val success = result is CommandResult.Success
            taskMigrationViewModel.onCommitFinished(success)
            if (success) navigate(TasksRoute)
        }
    }
},
```

Do not show a second success snackbar, synthesize Undo, or call a repository
from the migration ViewModel. `WorkspaceViewModel.execute` already publishes
the repository result and exact `RemoveImportedRecords` inverse through the
single snackbar collector. A rejection calls `onCommitFinished(false)`, so the
review remains on screen and no partial-success claim appears.

- [ ] **Step 6: Compile app and feature Android tests GREEN**

Run:

```bash
./gradlew \
  :app:compileDebugKotlin \
  :app:testDebugUnitTest \
  --tests "*TaskMigrationViewModelTest" \
  --tests "*WindowPostureMapperTest" \
  :feature:more:compileDebugAndroidTestKotlin \
  --console=plain
```

Expected: PASS. On an explicitly disposable sole device, also run the focused
More test; otherwise stop at compilation:

```bash
./gradlew :feature:more:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.feature.more.BackupRecoveryScreenInstrumentedTest \
  --console=plain
```

- [ ] **Step 7: Commit the active-workspace entry and wiring**

```bash
git add \
  feature/more/src/main/kotlin/app/opentasks/feature/more/MoreScreen.kt \
  feature/more/src/main/res/values/strings.xml \
  feature/more/src/androidTest/kotlin/app/opentasks/feature/more/BackupRecoveryScreenInstrumentedTest.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt \
  app/src/main/kotlin/app/opentasks/WindowPostureMapper.kt \
  app/src/test/kotlin/app/opentasks/WindowPostureMapperTest.kt
git commit -m "feat: import generic task csv from More"
```

### Task 8: Add Welcome migration and the NoVault-to-Active handoff

**Files:**

- Modify:
  `feature/more/src/main/kotlin/app/opentasks/feature/more/WelcomeScreen.kt`
- Modify:
  `feature/more/src/androidTest/kotlin/app/opentasks/feature/more/WelcomeScreenInstrumentedTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/TaskMigrationViewModel.kt`
- Modify: `app/src/test/kotlin/app/opentasks/TaskMigrationViewModelTest.kt`
- Modify: `app/src/main/kotlin/app/opentasks/MainActivity.kt`
- Modify: `app/src/main/kotlin/app/opentasks/OpenTasksApp.kt`
- Modify: `feature/more/src/main/res/values/strings.xml`

**Interfaces:**

- Consumes: the existing Welcome action column, empty-workspace target,
  `RecoveryViewModel.startWithoutRestoring`, Activity-scoped migration
  ViewModel, runtime transition, active `WorkspaceViewModel`, and existing
  signal-based root navigation pattern.
- Produces: review-before-vault creation, one transient confirmed-row handoff,
  one command dispatch after activation, and Tasks navigation only after
  repository success.

- [ ] **Step 1: Expand the Welcome RED test before changing production UI**

In `WelcomeScreenInstrumentedTest`, add a fourth `AtomicInteger`, pass
`onImportFromAnotherApp`, and extend the independent-callback assertions. Pin
the visual action order with `boundsInRoot.top`:

```kotlin
composeRule.onNodeWithTag("welcome-offline")
    .assertHeightIsAtLeast(48.dp)
composeRule.onNodeWithTag("welcome-import")
    .assertHeightIsAtLeast(48.dp)
    .performClick()
assertEquals(1, migration.get())
assertEquals(0, portable.get())
composeRule.onNodeWithTag("welcome-portable")
    .assertHeightIsAtLeast(48.dp)
val offlineTop = composeRule.onNodeWithTag("welcome-offline")
    .fetchSemanticsNode().boundsInRoot.top
val migrationTop = composeRule.onNodeWithTag("welcome-import")
    .fetchSemanticsNode().boundsInRoot.top
val portableTop = composeRule.onNodeWithTag("welcome-portable")
    .fetchSemanticsNode().boundsInRoot.top
assertTrue(offlineTop < migrationTop)
assertTrue(migrationTop < portableTop)
```

Import `androidx.compose.ui.test.fetchSemanticsNode` and
`org.junit.Assert.assertTrue`.

Add `welcome-import` to the compact, expanded, and 200%-font reachability
checks. Keep the three existing positional callback call sites compiling by
making the new callback defaulted.

- [ ] **Step 2: Compile the Welcome test and verify RED**

Run:

```bash
./gradlew :feature:more:compileDebugAndroidTestKotlin --console=plain
```

Expected: FAIL because the callback and `welcome-import` action do not exist.

- [ ] **Step 3: Add the independent Welcome action**

Add `onImportFromAnotherApp: () -> Unit = {}` immediately before `modifier` in
`WelcomeScreen`, pass it to `WelcomeActions`, and render a full-width 48 dp
`OutlinedButton` after **Continue offline** and before **Restore from this
device**:

```kotlin
OutlinedButton(
    onClick = onImportFromAnotherApp,
    modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .testTag("welcome-import"),
) {
    Text(stringResource(R.string.task_migration_title))
}
```

Keep encrypted Restore as the following `TextButton`; do not imply that CSV is
a recovery or replacement format.

- [ ] **Step 4: Add an explicit one-shot Welcome row handoff to the ViewModel**

Append these methods and one private field to `TaskMigrationViewModel`:

```kotlin
private var pendingWelcomeRows: List<ImportedTaskRow>? = null

fun confirmForWelcome(latestTarget: TaskCsvTarget): Boolean {
    val rows = confirm(latestTarget) ?: return false
    pendingWelcomeRows = rows
    return true
}

fun takeWelcomeRows(): List<ImportedTaskRow>? =
    pendingWelcomeRows.also { pendingWelcomeRows = null }

fun abandonWelcomeHandoff(): Boolean {
    if (pendingWelcomeRows == null) return false
    pendingWelcomeRows = null
    cancel()
    return true
}
```

Make `cancel` clear `pendingWelcomeRows` as well. Add tests proving that a
confirmed Welcome list is returned exactly once, `abandonWelcomeHandoff`
clears both rows and review, and `onCommitFinished(false)` after
`takeWelcomeRows` restores the review for an active-workspace retry. Keep
`SavedStateHandle` out of the constructor and imports so source state cannot be
restored after process death.

```kotlin
@Test
fun confirmedWelcomeRowsAreTakenExactlyOnce() {
    val subject = viewModel()
    load(subject, "Title\r\nOne\r\n")

    assertTrue(subject.confirmForWelcome(emptyTaskCsvTarget()))
    assertEquals("One", subject.takeWelcomeRows()!!.single().title)
    assertNull(subject.takeWelcomeRows())
}

@Test
fun abandoningWelcomeHandoffDropsRowsAndReview() {
    val subject = viewModel()
    load(subject, "Title\r\nOne\r\n")
    subject.confirmForWelcome(emptyTaskCsvTarget())

    assertTrue(subject.abandonWelcomeHandoff())
    assertNull(subject.takeWelcomeRows())
    assertNull(subject.state.value)
}

@Test
fun rejectedWelcomeDispatchKeepsReviewReadyForActiveRetry() {
    val subject = viewModel()
    load(subject, "Title\r\nOne\r\n")
    subject.confirmForWelcome(emptyTaskCsvTarget())
    assertNotNull(subject.takeWelcomeRows())

    subject.onCommitFinished(success = false)

    assertFalse(review(subject).isCommitting)
    assertNotNull(subject.confirm(emptyTaskCsvTarget()))
}
```

- [ ] **Step 5: Wire Welcome selection, review, and vault creation**

In `RecoverySurface`, obtain the same Activity-owned
`TaskMigrationViewModel = viewModel()` that `OpenTasksApp` uses after
activation. Collect its state, register the same three-MIME `OpenDocument`
launcher, collect its picker-request channel, and call
`rememberRawFolds(this@MainActivity)`. `viewModelStore` is not
cleared on the existing `NoVault -> Active` transition because
`activeRuntime` was null; preserve that runtime invariant.

Pass this Welcome callback:

```kotlin
onImportFromAnotherApp = {
    taskMigrationViewModel.begin(emptyTaskCsvTarget())
},
```

Inside the existing `OpenTasksTheme`, choose
`TaskMigrationPane(rawFolds) { TaskMigrationScreen(...) }` before
Welcome/RecoveryShell whenever migration state is non-null. Forward the same
mapping callbacks as the active flow. Its import callback is:

```kotlin
onImport = {
    if (taskMigrationViewModel.confirmForWelcome(emptyTaskCsvTarget())) {
        recoveryViewModel.startWithoutRestoring()
    }
},
```

This ordering confirms reviewed rows first, then creates the normal empty
offline vault. Cancellation or picker failure never calls
`startWithoutRestoring`. Add a `LaunchedEffect(presentation)` that calls
`abandonWelcomeHandoff()` when `RecoveryPresentation.Failed` follows a pending
Welcome import, allowing the existing truthful recovery failure screen to
show with no retained rows.

- [ ] **Step 6: Dispatch the handoff exactly once after runtime activation**

Add one Activity property:

```kotlin
private var openTasksAfterMigrationSignal by mutableIntStateOf(0)
```

In the Active branch, obtain the same Activity-owned
`TaskMigrationViewModel`, then add this effect before calling `OpenTasksApp`:

```kotlin
LaunchedEffect(workspaceViewModel, taskMigrationViewModel) {
    taskMigrationViewModel.takeWelcomeRows()?.let { rows ->
        workspaceViewModel.execute(DomainCommand.ImportTasks(rows)) { result ->
            val success = result is CommandResult.Success
            taskMigrationViewModel.onCommitFinished(success)
            if (success) openTasksAfterMigrationSignal++
        }
    }
}
```

`takeWelcomeRows` clears the handoff before asynchronous dispatch, preventing a
recomposition from sending it twice. A rejection leaves the now-active shared
review visible and sends no navigation signal; the empty workspace remains
available. Pass both the migration ViewModel and this defaulted signal pair to
`OpenTasksApp`:

```kotlin
openTasksAfterMigrationSignal = openTasksAfterMigrationSignal,
onOpenTasksAfterMigrationConsumed = {
    openTasksAfterMigrationSignal = 0
},
taskMigrationViewModel = taskMigrationViewModel,
```

Add matching defaulted parameters to `OpenTasksApp`. Beside the existing
root-signal effects, consume a positive signal by calling
`navigate(TasksRoute)` and then `onOpenTasksAfterMigrationConsumed()`.
Repository success has already published the standard snackbar with exact
Undo before this callback runs.

Do not save rows in a Bundle, `SavedStateHandle`, preferences, or disk. A
process death during mapping restarts selection. A process death after vault
creation but before command dispatch intentionally leaves an empty workspace
that can retry from More.

- [ ] **Step 7: Run Welcome state tests and compile all root wiring GREEN**

Run:

```bash
./gradlew \
  :app:testDebugUnitTest \
  --tests "*TaskMigrationViewModelTest" \
  :app:compileDebugAndroidTestKotlin \
  :feature:more:compileDebugAndroidTestKotlin \
  --console=plain
```

Expected: PASS, including the one-shot Welcome handoff and rejection retry.
On an explicitly disposable sole device, run the focused Welcome and migration
screen classes; otherwise preserve compile-only evidence:

```bash
./gradlew :feature:more:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
app.opentasks.feature.more.WelcomeScreenInstrumentedTest,\
app.opentasks.feature.more.TaskMigrationScreenInstrumentedTest \
  --console=plain
```

- [ ] **Step 8: Commit the Welcome-to-workspace path**

```bash
git add \
  feature/more/src/main/kotlin/app/opentasks/feature/more/WelcomeScreen.kt \
  feature/more/src/androidTest/kotlin/app/opentasks/feature/more/WelcomeScreenInstrumentedTest.kt \
  feature/more/src/main/res/values/strings.xml \
  app/src/main/kotlin/app/opentasks/TaskMigrationViewModel.kt \
  app/src/test/kotlin/app/opentasks/TaskMigrationViewModelTest.kt \
  app/src/main/kotlin/app/opentasks/MainActivity.kt \
  app/src/main/kotlin/app/opentasks/OpenTasksApp.kt
git commit -m "feat: migrate task csv from Welcome"
```

### Task 9: Verify the complete slice, review it, and update durable docs

**Files:**

- Modify: `docs/architecture.md`
- Modify: `DESIGN.md`
- Modify: `CLAUDE.md`
- Modify:
  `docs/superpowers/specs/2026-08-24-generic-csv-migration-design.md`
- Modify: `HANDOFF.md`
- Review: every production and test file committed in Tasks 1-8

**Interfaces:**

- Consumes: the completed generic migration slice and all focused evidence.
- Produces: full repository verification, independent code review, current
  architecture/design/handoff truth, and no version/release mutation.

- [ ] **Step 1: Run the complete focused host and Android-compile matrix**

Run:

```bash
./gradlew \
  :core:data:testDebugUnitTest \
  --tests "*GenericTasksCsvMapperTest" \
  --tests "*TasksCsvParserTest" \
  --tests "*InMemoryImportTasksTest" \
  --console=plain
./gradlew \
  :app:testDebugUnitTest \
  --tests "*TaskMigrationViewModelTest" \
  --tests "*WindowPostureMapperTest" \
  :core:data:compileDebugAndroidTestKotlin \
  :feature:more:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin \
  --console=plain
```

Expected: PASS. Record whether the Room and Compose tests were compile-only or
also ran on an explicitly disposable target; never substitute the protected
emulator.

- [ ] **Step 2: Exercise the end-to-end acceptance matrix on a safe target**

When an explicitly disposable device or owner-approved physical test device
is available, use small local CSV documents to prove:

- Welcome cancellation returns to Welcome without a vault;
- Welcome import creates the vault, lands on Tasks, and Undo removes exactly
  the imported tasks/projects/tags;
- More import shows header samples, every field selector, ignored columns,
  status/priority choices, DMY/MDY, estimate unit, tag separator, exact
  warning rows, and the **anyway** action;
- a repeated import creates a second set and says so before confirmation;
- a Done row without time uses the disclosed confirmation time;
- a repository-race rejection writes nothing and keeps review available; and
- Backup & recovery's strict **Import Open Tasks CSV** still accepts the exact
  14-column export and rejects a generic header.

Also inspect compact and expanded widths, RTL, TalkBack/keyboard order, and
200% font reachability. Record each observed PASS or the exact unavailable
device limitation in `HANDOFF.md`; do not claim manual evidence that was not
run.

- [ ] **Step 3: Update architecture, design, guidance, spec status, and handoff**

Make only these durable documentation changes:

- `docs/architecture.md`: add the two-line generic data flow, pure mapper
  ownership, Activity-scoped transient state, existing command/Undo reuse, and
  strict-own-schema separation;
- `DESIGN.md`: add Welcome/More entry placement, the single combined page,
  create-only disclosure, conditional choices, warning/action wording, and
  accessibility behavior;
- `CLAUDE.md`: record the strict-versus-generic parser boundary, the generic
  limits, the no-persistence/no-network rule, and the focused test commands;
- the approved spec: change status to **implemented**, link the Task 1-8 commit
  range, and record any verified limitation without rewriting the historical
  decisions; and
- `HANDOFF.md`: add a dated current-state entry with commits, exact test counts
  and commands, connected/manual evidence or limitation, unchanged Room/archive
  versions, and the next agreed slice: version/trust footer.

Do not edit the historical Stage 6 design, bump versionCode/versionName, create
a tag, sign/copy an APK, publish a release, or start Play Console work.

- [ ] **Step 4: Request an independent code review and resolve findings**

Invoke `superpowers:requesting-code-review` against the complete Task 1-8
commit range. Ask the reviewer to check approved-spec coverage, trust-boundary
bounds, strict-parser regression, status fallback/revalidation, timestamp
semantics, warning completeness, source-data lifetime, Welcome one-shot
handoff, exact Undo, accessibility, and unrequested scope.

For every valid finding, return to the smallest owning task, add or tighten one
failing check, apply the minimal fix, and rerun that task's GREEN command. If
review changes code, stage only those files and commit:

```bash
git commit -m "fix: harden generic csv migration"
```

Repeat review until no actionable findings remain. Do not expand into provider
presets, durable drafts, deduplication, or any other out-of-scope feature.

- [ ] **Step 5: Run final verification before claiming completion**

Invoke `superpowers:verification-before-completion`, then run these commands
separately in the required order:

```bash
./scripts/check-schema-drift.sh
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --console=plain
./gradlew :app:assembleRelease --console=plain
git diff --check
git status --short
```

Expected: schema clean; full host gate PASS; separate release assembly PASS;
no whitespace errors; only the known unrelated workspace entries plus the
intended documentation edits remain. The release assembly is verification
only: do not sign, copy, tag, push, or publish it.

- [ ] **Step 6: Commit the implementation record**

```bash
git add \
  docs/architecture.md \
  DESIGN.md \
  CLAUDE.md \
  docs/superpowers/specs/2026-08-24-generic-csv-migration-design.md \
  HANDOFF.md
git diff --cached --check
git commit -m "docs: record generic csv migration"
```

Finally inspect `git status --short` and `git log --oneline` for the complete
slice. Report the focused/full/release evidence, any explicit connected/manual
limitation, and the exact commit range. Stop before the version/trust-footer
slice until its separately approved design and plan are selected.
