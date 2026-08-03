# Stage 5 — Remaining Platform Features Design

- Date: 3 August 2026
- Status: Approved for planning
- Authority: this design is the Stage 5 execution intent under the
  [production master plan](../plans/2026-07-27-open-tasks-production-master-plan.md).
  It supersedes the historical
  [Train 5 design](2026-07-27-train-5-platform-features-design.md), which
  remains evidence only.

## Goal

Complete the remaining platform features on the final Stage 4 local schema:
close the two recorded Stage 4 limitations, then deliver encrypted vault
import/export, warned plaintext CSV export, the Today widget, app lock and
title privacy with unified Quick Add, full keyboard/mouse/accessible input,
and one-way calendar insertion.

## Recorded scope rulings

1. One combined Stage 5 design and one execution plan cover the two Stage 4
   carry-forwards and all seven Train 5 features. Nothing is deferred.
2. Interrupted attachment intakes take the silent auto-resume path: no new
   UI and no in-row progress, honouring the Stage 4 ruling that in-row
   progress requires a durable product contract that still does not exist.
3. Execution is foundations-first: the Room v9 schema work and the frozen
   `.otvault` format land before every feature that depends on them.
4. Remote merge, bidirectional sync, `drive.appdata` broadening, any
   update/PATCH path, and CSV import remain absent by design.

## Foundations

### Retired blob-set index (Room v9)

Stage 4 recorded a conservative leak: a purge that removes an attachment
record also removes the only local reference to its blob set, so Stage 4
garbage collection can never select those remote bytes. Stage 5 closes this
with the stage's only schema bump.

- Room v9 adds a `retired_blob_sets` table. Every path that deletes an
  attachment record still carrying a blob-set reference — permanent task
  deletion and expired-Bin purge — inserts, in the same transaction, one
  retired row holding the blob-set identity sufficient for exact-ID
  manifest lookup, the stored chunk count, and the retirement time.
  Attachment-only destructive deletion and terminal vault deletion already
  clear remote bytes directly and write no retired row.
- Garbage collection gains retired rows as a candidate source beside the
  existing referenced-set rules. A retired row becomes eligible under the
  unchanged Stage 4 conditions: 30-day age, current/previous-generation
  safety, ACTIVE lineage, and authenticated ownership tip. Deletion follows
  the existing bounded cleanup contract exactly — exact-ID only, chunks
  before manifest, the exact-role chunk probe when the manifest is missing,
  the shared 32-delete budget, reauthentication before every batch, and
  fail-closed treatment of unknown or ambiguous state. Confirmed deletion
  removes the retired row.
- The bump ships the exported `core/data/schemas/.../9.json`, a
  non-destructive v8→v9 migration, and a byte-preserving v8 fixture
  migration test, matching every earlier bump.

### Retired-set backup family

A retired set that exists only in local Room dies with the device, and the
leak returns after every recovery. Retired rows therefore become a new
backup payload family, following the Stage 4 NOTE pattern end-to-end:
mutation codec, journal session emission in the same command transaction,
strict payload validation, recovery import, staged-vault verification,
retention-purge rules, in-memory journal parity, and independent
Node-generated fixtures with byte-identical regeneration. Forced complete
baselines for recovered and separate lineages include the retired set.

### Silent attachment intake auto-resume

`AttachmentBlobCoordinator.resume()` gains its product caller with no new
scheduler and no UI.

- The Stage 4 runtime already sweeps expired sessions on start. Immediately
  after that sweep, each surviving interrupted intake session (< 24 h) is
  handed to `resume()` sequentially under the existing lifecycle gates:
  ACTIVE lineage, ownership tip match, stopped-runtime refusal.
- The same bounded resume pass runs after the existing periodic backup work
  trigger, so a long-lived process retries without new WorkManager
  plumbing.
- Success completes registration through the normal
  `VaultRepository.execute` path with its existing idempotent replay.
  Failure leaves the session untouched for the next pass until the
  unchanged 24-hour expiry. The session expiry, bounds, and hostile-input
  behaviour of Stage 4 Task 9 are not altered.

## Portable encrypted vault (`.otvault`)

### Format

One streamed container reusing the frozen Stage 1 authenticated frame
families; no new cryptographic primitive.

- Layout, in order: an outer portable manifest carrying the format version
  and KDF metadata (Argon2id, 64 MiB, 3 iterations, parallelism 1, 16-byte
  salt); one complete framed workspace snapshot; required
  operation/tombstone state; every attachment metadata record; each
  attachment's encrypted chunks and blob-set manifest; one final inventory
  checksum frame covering every preceding object.
- All frames are re-encrypted under a key derived from a fresh export
  passphrase, or from the current recovery passphrase after explicit
  confirmed reuse. Passphrases stay `CharArray`; derived keys are zeroed in
  `finally`.
- Frames carry archive-scoped logical identities. Remote lineage object IDs
  never appear in an archive, and archive objects can never collide with
  live provider objects.
- Per-family byte and count bounds are enforced before allocation on both
  write and read. There is no fixed aggregate cap; the archive streams.
- The format freezes with independent Node-generated fixtures, golden byte
  and digest tests, and rejection vectors for corrupt, truncated,
  oversized, and newer-version (old-reader) archives.

### Export

- Export uses the existing Stage 2 consistent-capture machinery for
  records and streams directly to a Storage Access Framework destination.
  No complete plaintext archive is ever staged; partial app-private
  temporary state is deleted on failure.
- Attachment ciphertext comes from the local frame cache, or from the
  remote blob store by exact ID when not cached. Export fails closed if any
  active attachment's chunks are unfetchable, and reports the affected
  attachment names in-app so the user can retry online. No metadata-only
  archive is produced.
- Export requires the fresh passphrase entry described above and displays
  the destination and passphrase consequences before writing.

### Import

Import replaces the single active vault after confirmation; it invents no
merge policy. Unsupported or corrupt archives leave the active vault
unchanged.

1. Read and bound the outer header before allocating anything else.
2. Validate KDF metadata and unlock with a `CharArray` passphrase.
3. Stream and authenticate every framed object in an isolated staging
   directory.
4. Decode records under current domain limits.
5. Validate identifiers, ownership, workflow category coverage, relations,
   recurrence, zones, attachment inventory against the checksum frame, and
   format compatibility.
6. Build and open a staged SQLCipher database; run staged-vault
   verification including the retired set.
7. Present record and attachment counts plus explicit replacement
   consequences.
8. On confirmation: close the active repository, preserve a rollback copy,
   and atomically activate the staged vault. The rollback copy is deleted
   after the imported vault completes its first successful unlock; any
   activation failure restores it.

Two rules newer than the Train 5 design:

- Import replaces the active vault slot, so an ACTIVE remote backup
  runtime stops, per the Room v8 authority rule. The remote connection
  becomes disconnected-dormant; the old lineage is never written again.
  Reconnecting later runs the existing separate-lineage configurator with
  a forced complete baseline.
- Imported attachment chunks are staged into the local ciphertext frame
  cache under its unchanged `min(128 MiB, 5%)` bound, so attachments open
  offline immediately. When a new lineage connects, blob sets re-upload
  from those local bytes through the existing intake machinery. Chunks
  that do not fit the cache bound are not retained: the import preview
  names the affected attachments before confirmation, they show the
  existing unavailable state afterwards, and they cannot re-upload from
  this device. This is a recorded bound, not silent loss; the source
  archive remains the user's copy of those bytes.

## Plaintext CSV export

- Export-only. Every export presents a fresh disclosure that titles,
  descriptions, notes, dates, and time information become readable outside
  Open Tasks. There is no "do not ask again".
- Four selectable tables: tasks, projects, time entries, and notes. Notes
  are the one extension over Train 5, since notes now exist and a data
  export that silently omitted them would be a gap. Attachments are
  excluded entirely.
- RFC 4180 quoting, UTF-8 with a header row, UK display fields plus ISO
  machine-readable date/time columns, stable documented column ordering,
  and formula-injection neutralisation for values beginning with `=`, `+`,
  `-`, or `@`.
- Streams to the chosen SAF destination and retains no plaintext copy;
  partial output is deleted on failure.

## Today widget

- Implemented with `androidx.glance` — the stage's only new dependency,
  added through the version catalogue and configuration-cache compatible.
- The widget never opens SQLCipher. The application process computes a
  minimal projection — today's open count, overdue count, and up to three
  focus-task titles — and publishes it to widget state through
  scheduled/widget update work.
- Titles are written to widget state only while the app is unlocked and
  title privacy is off. Engaging app lock or title privacy clears titles
  from widget state immediately; locked or private widgets show counts and
  generic labels only. Widget state lives outside the Android Auto Backup
  allow-list (which includes only the portable package path) and so is
  never backed up. This plaintext-titles-at-rest decision is recorded in a
  threat-model addendum in the same change.
- Widget actions open authenticated application UI: open app, and Quick
  Add via the shared sheet. Exported intents contain no task text.

## App lock, title privacy, and Quick Add

- App lock uses `BiometricPrompt` with device-credential fallback and a
  user-selected immediate, 1-minute, 5-minute, or 15-minute background
  delay; when enabled it also gates cold start. It changes no SQLCipher or
  vault-content key and never blocks the recovery path behind an
  unavailable biometric.
- Title privacy is one setting controlling four surfaces: recent-apps
  preview concealment, widget titles, notification private content, and
  external Quick Add presentation. Screenshot blocking remains a separate
  opt-in setting. Recovery and permission dialogs remain understandable
  while content is concealed.
- Quick Add keeps one title field, optional project choice, bounded saved
  state, and keyboard submission. The launcher shortcut and the widget
  action reuse the same authenticated sheet.

## Keyboard, mouse, and accessible actions

- `Ctrl+K` and `/` open search; `Ctrl+N` opens Quick Add; `Ctrl+Shift+N`
  creates a project in Projects; `?` opens the shortcut helper; `Esc`
  dismisses the top transient surface; Enter/Space activate the focused
  control. Single-key shortcuts apply only outside editable text focus.
- Visible hover and focus states across pointer hardware.
- Context menus exist only where every action also has a visible or
  TalkBack path. No workflow, milestone, dependency, attachment, or
  schedule action requires drag; existing up/down and explicit actions
  remain the accessible authority.

## Calendar insertion

- Task detail and Schedule expose "Add to calendar" only for tasks with a
  start or due moment.
- The app builds an `ACTION_INSERT` event with the task title, start
  moment when present, due moment as end or due-only context, and project
  name in the description. A preview shows exactly these values before the
  calendar provider launches.
- No calendar permission is requested, no event ID is stored, and no
  background synchronisation exists. Cancelling the provider changes no
  Open Tasks record.

## Constraints carried forward

The standing CLAUDE.md and handoff rules bind every task; the ones Stage 5
touches most: every write is a `DomainCommand` through
`VaultRepository.execute` with atomic journal entries; in-memory/Room
parity on every command change; feature composables stay stateless and
Hilt-free; layout decisions come from `WorkspaceLayoutPolicy`; new UI copy
goes in `strings.xml` as UK English; logs and telemetry never contain task
text, account details, Drive IDs, attachment names, or encryption
metadata; OKLCH colours and the 4 dp spacing scale.

## Execution order

1. Room v9 retired blob-set index, backup family, and GC closure.
2. Silent attachment intake auto-resume.
3. `.otvault` format freeze with fixtures.
4. `.otvault` export.
5. `.otvault` import.
6. CSV export.
7. Today widget.
8. App lock, title privacy, and unified Quick Add.
9. Keyboard, mouse, and accessible actions.
10. Calendar insertion.
11. Stage qualification and exit gates.

Subagent-driven execution with an independent review per task and scoped
re-reviews after fix rounds, as in Stages 2–4.

## Testing and qualification

- TDD RED/GREEN per task. JUnit 4, no mocking library; suspend code uses
  `runBlocking` with `withTimeout(5_000)`.
- Deterministic provider fakes prove retired-set GC (purge → eligible →
  bounded deletion → row removal) and auto-resume (interrupt → restart →
  resumed without re-picking) as unit tests in `:core:data`.
- `.otvault` round-trips the final v9 schema, attachments, and the retired
  set across a clean install, and rejects the corrupt/oversized/old-reader
  fixture set.
- Widget and Quick Add respect locked and private states across reboot and
  process death; connected suites run only on a sole disposable read-only
  emulator, never the protected `Pixel_10_Pro_Fold` workspace.
- Exit gates mirror Stage 4: forced-fresh
  `testDebugUnitTest lintDebug :app:assembleDebug`, separate
  `:app:assembleRelease`, schema-drift check at v9, fixture regeneration
  diff-clean, `git diff --check`, privacy scans extended to widget state
  and CSV/`.otvault` temporary cleanup, and release-scope inspection
  (still only `drive.appdata`, no debug surfaces).
- `docs/architecture.md`, `docs/threat-model.md`, `DESIGN.md`,
  `PRODUCT.md`, and `HANDOFF.md` update in the same change as the
  contracts they describe.

## External and deferred work

Unchanged and explicitly not part of Stage 5: Samsung Remote Test Lab
(External-blocked on developer-account approval), native fold continuity,
broader two-installation live recovery evidence, and Play Console work.
In-row attachment transfer progress remains declined pending a durable
product contract.
