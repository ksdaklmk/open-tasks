# Generic CSV Migration

Date: 2026-08-24. Status: **implemented**.
Authority: this is the second delivery slice in the agreed sequence:
Undo gaps, migration friction, version/trust footer, then Play internal beta.

Tasks 1–8 are implementation- and task-review complete in
[the `b22d967..c0b195e` commit range](https://github.com/ksdaklmk/open-tasks/compare/b22d967e7ab5b55f6e0c50a5c7228b6180302e35^...c0b195e71a252854bc0a8cfcf81af74841f7287f).
The focused host suites pass and the Room and Compose Android tests compile.
Connected and manual acceptance remains unexecuted because no explicitly
disposable target is available; the protected `Pixel_10_Pro_Fold` was not
touched. The implementation adds no Room or authenticated backup/archive
format change.

## Outcome

Open Tasks will add a discoverable, offline **Import from another app** flow
for generic task CSV files. It appears on both Welcome and the top-level More
page, conservatively suggests column mappings, and combines mapping, samples,
warnings, and confirmation on one review page.

The import is create-only and best-effort before commit: rows with invalid
titles are skipped, invalid optional values are omitted, and every omission is
reported before confirmation. The reviewed valid rows still commit as one
transaction through the existing `DomainCommand.ImportTasks` path and retain
its repository-produced exact Undo.

There is no account connection, network access, vendor-specific preset,
database migration, backup-format change, or new dependency.

## Relationship to the existing CSV path

Stage 6 deliberately limited its CSV importer to the exact Open Tasks Tasks
export schema. That strict round-trip path remains unchanged and continues to
live under Backup & recovery.

This design supersedes the earlier "no third-party formats" ruling only by
adding a separate generic migration path. It does not weaken the strict
parser, reinterpret an Open Tasks export, merge records, or turn CSV into a
full-fidelity backup. `.otvault` remains the transfer format for complete Open
Tasks data.

Both CSV paths ultimately produce the existing bounded `ImportedTaskRow`
contract and use the same repository command. The new path owns only the
translation from an external table to reviewed rows.

## Chosen approach

The approved approach is a generic mapper rather than named app presets:

- no OAuth, account connection, or provider API;
- no Todoist, TickTick, or other vendor profile to maintain;
- conservative header suggestions plus manual correction;
- one mapping engine for any conforming task CSV; and
- no claim that unsupported task-app concepts can be preserved.

Named presets may be added later only if real migration evidence shows that
the generic mapper is insufficient.

## Entry points and navigation

### Welcome

Welcome adds a full-width, 48 dp **Import from another app** action between
**Continue offline** and **Restore from this device**. It is visibly separate
from encrypted recovery: CSV creates tasks in a new workspace, while Restore
replaces the device with a complete authenticated vault.

Selecting the action opens the system document picker and then the shared
mapping screen. No vault is created while the person selects, reviews, edits,
or cancels. Cancellation returns to Welcome without changing local data.

On confirmation, the app captures the final reviewed rows in transient memory,
creates the normal empty offline vault, and dispatches the existing import
command as soon as that vault becomes active. A repository rejection creates
no partial tasks; the new empty workspace remains available and the person can
retry from More.

The confirmed rows are not written to `SavedStateHandle` or disk. If Android
terminates the process during the wizard, the wizard restarts and the source
must be selected again. If termination lands in the narrow interval after the
vault was created but before dispatch, the person returns to an empty workspace
and can retry from More. The source CSV is never modified.

### More

More adds a top-level **Import from another app** destination row with concise
copy such as **Bring tasks from a CSV file**. It is not buried inside Backup &
recovery. The existing strict **Import Open Tasks CSV** action stays where it
is.

The destination opens the same mapping screen. Confirmation revalidates the
reviewed rows against the latest workspace snapshot and sends the same
transactional import command. A race with project, tag, or workflow changes
fails without a partial write and leaves the person able to review again.

## Input contract and bounds

The first release accepts a local UTF-8, comma-delimited CSV with a header row
and RFC 4180 quoting. A leading UTF-8 byte-order mark is ignored. CRLF and LF
record endings are accepted; malformed quoting, bare carriage returns, or
invalid UTF-8 are blocking errors.

The existing trust-boundary limits remain:

- at most 5 MiB read from the selected document;
- at most 5,000 task rows;
- at most 500 newly created projects; and
- at most 1,000 newly created tags.

The generic table is additionally bounded at 100 source columns. A row with
fewer cells than its header is padded with empty optional values; a row with
more cells has data with no header identity and is a blocking structural
error. Empty records are skipped and reported.

Tab-separated, semicolon-delimited, spreadsheet workbook, JSON, XML, archive,
and proprietary formats are outside this release. Delimiters inside a mapped
Tags cell are handled separately from the CSV record delimiter.

## Header suggestions

Header matching is vendor-neutral and deterministic. It trims a header,
normalises case with `Locale.ROOT`, and ignores ordinary spacing, hyphen, and
underscore differences. A small destination-field alias list and sample value
shape may suggest a mapping. No fuzzy model, telemetry, vendor lookup, or
network request is used.

Suggestions are never authoritative:

- an unambiguous suggestion is preselected but remains editable;
- an ambiguous or unknown column remains unmapped;
- Title must be mapped before import can proceed;
- one source column maps to at most one destination field; and
- unmapped source columns are listed as ignored.

The review shows the first few non-empty sample values beside each selector so
the person can detect a plausible but incorrect suggestion.

## Supported destination fields

The approved first release supports the complete existing task-import set.

### Title

Title is the only required destination field. Whitespace is trimmed. A row is
skipped when its title is blank or exceeds the existing 240-character bound.
Titles are never truncated silently.

### Project or list

A trimmed project/list value reuses the active project whose name matches
case-insensitively. Otherwise it proposes a new project with default workflow
statuses. Case-only variants within the source use one canonical spelling and
produce a warning. An invalid or overlong project value is omitted, placing
the task in Inbox, with a warning.

### Status

Each distinct non-empty source status value is shown on the review page. The
person maps it to **Backlog**, **In progress**, **Done**, or explicitly
**Ignore (use Backlog)**. Recognisable words may receive a visible suggestion;
ambiguous values require a choice.

The mapper carries the selected semantic category to repository resolution.
For each destination project, preview selects the first active status of that
semantic category by workflow rank. A new project has the standard Backlog,
In progress, and Done targets. If an existing project cannot represent the
chosen category, preview warns and deliberately converts the row to its active
Backlog status rather than creating a workflow. A Done-to-Backlog fallback
also omits the completion value and reports that loss. If no safe active
Backlog exists, confirmation is blocked.

The reviewed row carries both the selected status name and an additive semantic
hint. At commit, an exact active name still wins; if that status changed after
preview, another active status of the same semantic category may replace it by
rank. If none exists, repository revalidation rejects instead of applying an
unreviewed Backlog fallback.

The strict Open Tasks CSV path retains exact status-name behaviour. It leaves
the additive semantic hint empty, so existing round trips do not change.

### Priority

Each distinct source priority value maps to **None**, **Low**, **Medium**,
**High**, **Urgent**, or **Ignore (use None)**. Clear text labels may be
suggested. Numeric schemes are not guessed because different task apps reverse
their meaning; they require an explicit choice.

### Start and due

Start and Due accept ISO-8601 date/time values, unambiguous numeric dates, and
common unambiguous English month-name dates. Numeric dates that could be either
day/month or month/day trigger one import-wide choice. The chosen order is
shown on the review page and applied consistently to all ambiguous dates.

An explicit source offset is preserved. Values without an offset resolve in
the device zone captured once when the file is selected, so preview and commit
cannot disagree after a zone change. They are then stored with the exact
offset at that local instant, matching the existing CSV import convention.

Date-only starts use 09:00 and date-only due dates use 17:00. Java time-zone
resolution remains the sole daylight-saving gap/overlap authority. An
unparseable value is omitted with a warning.

### Completion

The optional Completion mapping accepts a timestamp, date, or recognisable
open/completed value. A parsed completion timestamp marks the task Done. A
date-only completion uses 17:00 in the captured device zone.

When Status maps a row to Done but the source provides no completion time, the
mapper uses the confirmation instant and reports that inference in preview.
An explicit completion timestamp or completed value takes precedence over a
conflicting open status and produces a warning. Unknown completion values are
omitted rather than guessed.

### Estimate

Estimate accepts positive whole numeric values. A header that clearly states
minutes or hours suggests that unit; otherwise the review requires one unit
choice for the mapped column. Values are normalised to the existing positive
minute contract with checked arithmetic. Invalid or overflowing values are
omitted with a warning.

### Tags or labels

The review suggests comma, semicolon, or pipe splitting from non-empty sample
cells and lets the person choose one of those separators or **Treat each cell
as one tag**. The resulting tokens are shown before confirmation.

Whitespace is trimmed and duplicate names collapse case-insensitively. An
existing tag supplies canonical casing; otherwise the first source spelling
wins. Blank, overlong, or excess per-task tags are omitted individually and
reported. The existing 50-tags-per-task, 64-character name, 1,000-new-tag, and
repository uniqueness limits remain authoritative.

### Notes or description

One mapped Notes/Description column becomes the task description. A value over
the existing 20,000-character bound is omitted with a warning rather than
truncated or used to skip the whole task.

## Combined review page

One scrollable, responsive page owns mapping and preview. It contains:

1. source filename and bounded row/column summary;
2. destination selectors with sample values;
3. conditional status and priority value mappings;
4. the date-order choice only when ambiguity exists;
5. the estimate-unit and tag-separator choices only when those fields map;
6. ready-task, skipped-task, omitted-value, new-project, and new-tag counts;
7. a row-and-field warning list; and
8. one final import action.

Mapping edits recompute the pure preview. The screen never auto-commits. With
no warnings the action reads **Import N tasks**. With warnings it reads
**Import N tasks anyway**, making the accepted loss explicit without an extra
checkbox or second preview page.

The page always states that import creates new tasks. Re-selecting the same
file creates another set; generic CSV has no trustworthy record identity and
the app performs no exact or fuzzy deduplication.

## Best-effort and transactional rules

Best-effort applies only while translating source cells into reviewed rows:

- an invalid Title skips that row;
- an invalid optional value omits that field or token;
- every skipped row, omitted field, case merge, date inference, completion
  inference, and semantic fallback is reported by row and field;
- values are never silently truncated; and
- zero valid tasks blocks confirmation.

Blocking trust-boundary failures include malformed structure, invalid
encoding, missing or unusable headers, input/row/column limits, new-project or
new-tag creation limits, and any state that cannot produce a safe final
repository plan.

After review, all accepted rows remain one import command. The repository
revalidates the complete set, writes tasks/projects/tags plus ordered backup
journal entries in one transaction, and returns one receipt. Either every
reviewed row commits or none does. The receipt's existing
`RemoveImportedRecords` inverse removes exactly the tasks, projects, and tags
created by that import.

## Architecture and ownership

The data flow is:

`CSV bytes -> shared record reader -> mapping draft -> reviewed rows/warnings`

`-> existing import preview -> DomainCommand.ImportTasks -> exact Undo`

The existing private RFC 4180 reader is extracted into one bounded internal
record reader parameterised by the strict 14-column contract or the generic
header width. `parseTasksCsv` continues to enforce its exact header and
all-or-nothing row parser. No second CSV parser or parsing dependency is added.

A pure mapper in `core:data` owns header suggestions, source-value conversion,
date parsing, case canonicalisation, and warning production. It receives the
target snapshot (or the known empty-workspace defaults), explicit zone, and
clock/confirmation instant as inputs for deterministic tests. It does not read
Android state, a repository, or the network.

The app layer owns transient mapping state, document-picker effects, and
repository preview/dispatch. A stateless feature composable receives plain
review state and callbacks, preserving the existing module boundary. Feature
code does not dispatch commands or depend on `core:data`.

The NoVault-to-Active transition is the only special case. `MainActivity`
briefly retains confirmed rows in memory across the runtime activation that
clears slot-scoped ViewModels, then hands them to the normal
`WorkspaceViewModel.execute` path and clears the reference on every result.
Task text is never placed in saved-instance state.

For semantic status mapping, `ImportedTaskRow` gains one optional semantic
hint with a null default. The generic mapper sets it; the strict parser and
all existing callers retain null. No persisted record, Room schema, archive,
backup object, or CSV export column changes.

## Error, privacy, and accessibility contract

Picker cancellation is quiet. Blocking errors retain the selected mapping
screen when correction is possible and otherwise offer **Choose another
file**. Repository rejection uses truthful text and permits a fresh review;
there is no partial-success claim.

The selected document is read through the Storage Access Framework. Open
Tasks does not persist its URI permission, copy it to app storage, log its
filename or cells, or send it anywhere. The bounded byte buffer is cleared
after record parsing; reviewed strings live only for the active flow and the
short confirmation transition.

The Welcome action, More row, mapping controls, warnings, and final action use
string resources for every supported locale. Controls retain at least 48 dp
targets, logical keyboard/TalkBack order, visible focus, scalable text, and
RTL support. Headers identify sections, selectors expose their destination
and sample, counts have screen-reader summaries, and warnings use text and
error semantics rather than colour alone. The page remains usable at 200%
font on compact, folding, and expanded layouts.

## Verification

Prefer existing suites and test shapes:

1. pure JVM reader tests pin UTF-8/BOM handling, quoting, row/column limits,
   short-row padding, long-row rejection, and unchanged strict CSV behaviour;
2. pure mapper tests cover header suggestions, every destination field,
   status/priority choices, date-order selection, device-zone offset
   normalisation, completion fallback, estimate units, tag splitting,
   canonical casing, and complete warning accounting;
3. app state tests cover picker cancellation, mapping edits, confirmation,
   Welcome handoff, active-workspace revalidation, command rejection, and
   clearing transient rows;
4. repository tests prove the additive semantic hint, atomic import, creation
   limits, and exact `RemoveImportedRecords` Undo in both engines; and
5. Compose tests prove both entry points, the combined page's conditional
   controls, warning copy, keyboard/TalkBack semantics, large text, and compact
   and expanded layouts.

Run the normal host gate, Android-test compilation, and any focused module
tests named by the implementation plan. Connected tests run only on an
explicitly safe disposable target; the protected emulator remains untouched.

## Explicitly out of scope

- provider presets or vendor-specific header/value contracts;
- OAuth, cloud account connections, direct provider APIs, or sync;
- matching, merging, duplicate detection, or external-ID persistence;
- creating source-named custom workflows;
- subtasks, checklists, reminders, recurrence, attachments, dependencies,
  time entries, comments/notes as separate records, milestones, project
  settings, or activity history;
- non-CSV input and alternate record delimiters; and
- a durable migration draft or process-death resume mechanism.

Add any of these only after real import evidence justifies the additional
contract. Unsupported source columns remain visible as ignored on review.

## Documentation impact

Implementation updates `docs/architecture.md`, `DESIGN.md`, `CLAUDE.md`, and
`HANDOFF.md` to distinguish the retained strict own-schema round-trip from the
new generic migration path. The historical Stage 6 design remains unchanged;
this document records the later, separately approved exception.

## Acceptance criteria

- Import from another app is discoverable from Welcome and top-level More.
- One combined page provides conservative suggestions, editable full-field
  mapping, samples, warnings, and confirmation.
- Ambiguous dates require one import-wide day/month choice.
- Source statuses map explicitly to Backlog, In progress, or Done.
- Done tasks without a completion time use the disclosed confirmation instant.
- Invalid titles skip rows; invalid optional values are omitted; no loss is
  silent and no value is silently truncated.
- Every import creates new tasks and clearly warns about repeat imports.
- Reviewed rows commit atomically through the existing repository command and
  retain exact repository-produced Undo.
- The strict Open Tasks CSV importer behaves exactly as before.
- Input stays offline, bounded, unlogged, and unstaged on disk.
- No Room migration, backup/archive change, network path, permission, account
  integration, dependency, or version bump is introduced.
