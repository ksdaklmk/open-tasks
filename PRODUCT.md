# Product

## Register

product

## Platform

android

## Users

Open Tasks is for solo professionals who coordinate projects, deadlines, and
focused work across a phone, foldable, or tablet. They need to capture work
quickly, understand what deserves attention, and maintain a trustworthy project
record without operating a server or inviting a team.

## Product Purpose

Open Tasks combines fast personal task capture with the workflow, scheduling,
dependency, milestone, and time context of a full project workspace. Success
means the complete core workflow remains useful offline, resists data loss, and
continues seamlessly through rotation, resizing, folding, process recreation,
and recovery from a verified encrypted backup.

## Positioning

A serious project workspace with the privacy and immediacy of a local-first
personal tool.

## Current Delivery Boundary

The encrypted workspace remains fully useful without an account or network
connection, and encrypted Room is the sole live structured-data authority.
The cloud foundation has an
independently generated vault-content key with separate recovery and local
Android Keystore wrapping, plus strict bounded canonical frames for manifests,
snapshots, operation segments and attachment chunks. The implemented internal
authenticated object codec binds each frame's complete identity as AEAD
associated data, verifies its checksum before decryption, and returns typed
untrusted-object failures. Room v8 also records accepted local generations
and ordered backup-journal rows atomically. Strict snapshot/segment payloads,
consistent capture, and verified encrypted current/previous local recovery
objects are implemented under the no-backup directory.

Stage 2 now activates the local coordinator, persists a verified recovery
envelope, and atomically publishes one verified portable package no larger
than 24 MiB. Android Auto Backup and device transfer are enabled for the exact
package path only; Room, WAL/SHM, preferences, keys, credentials, cache, local
staging, and attachment bytes remain excluded. More exposes locally provable
package readiness, pending, unavailable, and inert restored-package states.
It never claims that Android uploaded the package or invents a platform backup
time.

Source includes explicit Google authorization, create-only `drive.appdata`
transport, app-managed backup publication, verified recovery staging/activation,
writer takeover, lifecycle management, and the backup/recovery product
surfaces. Stage 4 adds local notes, immutable activity history, note/active
attachment-name search, and cloud attachment intake, open/share, unavailable,
GC, and destructive-deletion flows. Normal operation still never downloads or
merges live structured records. The one-shot credentialed attachment check
proves its exact create/readback/manifest/cleanup properties only; it is not a
broader live-provider or protected-workspace claim.

Stage 5 adds a Room v9 retired-blob-set index and its `RETIRED_BLOB_SET`
backup family, so a purged attachment's remote bytes become a bounded
garbage-collection candidate instead of a permanent conservative leak, plus
silent attachment-intake auto-resume. It adds a frozen whole-vault archive
format (`.otvault`) with an independent Node fixture generator, encrypted
export through the Storage Access Framework, and encrypted import with a
confirmable preview, staged activation, and rollback until first unlock.
It adds disclosed, formula-safe CSV export; a Home-screen Today widget; app
lock with title privacy and a unified Quick Add reachable from a launcher
shortcut, the widget, and the app itself; keyboard, mouse, and
accessible-action shortcuts with a help dialog; and one-way calendar insertion
from the task editor and Schedule. Stage 5 explicitly ships no
bidirectional sync path, no in-row attachment transfer-progress display,
nothing beyond a snapshot-only `.otvault` export, and no stored calendar
event identifier: calendar insertion is a one-way, fire-and-forget
hand-off to the device calendar app with no result handling.

Stage 6 reduces daily friction without changing the Room v9 or backup format.
It adds natural-language due dates, browser share and selected-text Quick Add,
a lock-gated Quick Settings tile, interactive Today-widget completion, 25/5
and 50/10 focus cycles, live saved searches, atomic bulk actions, a
drag-and-accessible Kanban board, four-part weekly review, plaintext project
Markdown, and create-only round-trip import for the app's own Tasks CSV. Manual
Stop ends a focus cycle only when the stopped timer owns that cycle; stale
alarms cannot stop another task. The installed identity is the approved
adaptive Ember/card icon, including its normal, round, and monochrome launcher
resources; the supplied 512 px store listing asset remains outside the
sideload-only product boundary.

Stage 6 adds no cloud surface, runtime permission, third-party CSV format,
record merge, wearable surface, location reminder, or durable schema change.
Markdown and CSV deliberately leave the encrypted vault as plaintext through
person-selected Storage Access Framework documents.

Stage 7's implemented visible ergonomics changes give Tasks and project
workbenches fixed-direction sort and bounded grouping, saved filters gain
due-bucket, priority, semantic-status, and sort choices, and search uses one
relevance ranking. Quick Add offers confirm-only project, tag, priority,
recurrence, and estimate grammar; task detail and board cards can duplicate a
task; Insights uses accessible dot runs and a completion-per-day trend; the
app is light-only. The responsive Today widget keeps counts and Quick Add in
its compact 2×1 layout and adds focus-task actions at expanded height. Room
remains v9 and the authenticated backup object format remains v1.

The deliberate ceilings are equally visible. There is no sort-direction toggle
or manual board rank; grouping is limited to due bucket, project, and priority;
Quick Add does not express multi-word tags or weekday lists and never applies a
token without confirmation. Arrangement preferences are device-local, deleted
project entries may remain as bounded enum-and-ID residue, and Schedule and Home
keep their existing comparators. Duplication omits reminders, recurrence,
activity history, time entries, notes, and attachments. Stage 7 adds no external
surface, permission, network path, cloud path, schema, or backup family.

Stage 8 adds planning surfaces inside the existing destinations rather than a
new one. Schedule gains a Monday-first month calendar at every window size: a
six-row by seven-column grid whose cells state the date and the exact placed,
completed, and overdue counts, with a selected-day agenda beside the
unscheduled tray at expanded sizes. Rescheduling becomes exact and
single-task — dragging a task onto a day, or using its 48 dp Reschedule
action, moves that task's start, due, and reminder together in one atomic,
undoable step — and the task editor gains native start date and time controls
beside an explicit due time. Dropping an undated task on a day makes it due at
18:00 in the device's current zone; new start moments default to 09:00, and
the editor keeps its existing 17:00 date-only due convention. Projects gains
Timeline as a third per-project presentation beside List and Board: a
read-only, Monday-aligned 12-week Gantt-lite with dot-run spans, start, due,
warning, and continuation markers, milestone diamonds, exact before-and-after
counts for out-of-window milestones, and transitive prerequisite and
dependant highlighting for a selected task. More gains an opt-in daily digest
that posts one private notification at a chosen 24-hour local time carrying
open-today and overdue counts only, on its own notification channel, with a
generic lock-screen version. Selected presentation, timeline anchor, selected
day, and dependency selection survive rotation, resizing, and process
recreation.

The digest is device-local to the installation, off by default, excluded from
Android backup, and stores only an enabled flag, a local minute of day, and
the last handled local date — never titles, counts, zone identifiers, or vault
content. Invalid settings fail closed by disabling the feature and cancelling
its alarm, and a backward clock or repeated enabling cannot post twice on one
local day. Room stays v9 and the authenticated backup object format stays v1;
Stage 8 adds no schema, backup family, permission, dependency, route, Drive
scope, or network path, and its only manifest change is a single non-exported
broadcast receiver for the digest alarm.

The deliberate ceilings are equally visible. Timeline v1 is read-only: no
dependency arrows, no zoom, no bar drag or resize, no resource allocation and
no critical path, and every edit still routes through the existing task and
milestone editors. Month density stops at six dots followed by a compact
overflow label, with the exact counts always available as text. Pointer drag
is layered over a complete tap-and-menu path that remains sufficient on its
own, and compact Week stays tap-and-menu by design because it has no honest
pointer target grid. The digest never catches up on a missed day, never
carries task titles or actions, never claims an exact alarm or a background
worker, and remains one device-local setting rather than a per-vault one.
There is no Planner destination and no parallel planning store: month,
timeline, and digest are projections of the same encrypted workspace.

Stage 8 closes as a signed sideload release; its device qualification and
release evidence live in the Stage 8 qualification record.

## Approved future contract

- Structured workspace data remains local in Room during normal use.
- App-managed encrypted backup preserves structured data for recovery.
- Android Auto Backup supplements that future guarantee with the implemented
  strictly whitelisted portable encrypted package.
- Attachment metadata remains local structured data, while attachment bytes
  are durable only in the separate encrypted cloud attachment service.
- Each backed-up vault has one active writer. Recovery on another device is an
  explicit takeover that advances writer ownership; it never merges two live
  workspaces.
- Backup failure never blocks local editing, and attachment failure affects
  only the file operation.
- A fresh installation starts at Welcome and makes no provider discovery or
  application network call until the person explicitly chooses Google.
- Account-free local use remains complete. Google authorization is optional
  and serves encrypted backup/recovery only.
- A new offline vault contains structural workspace/default workflow records
  only and no demonstration or user content.
- Insights may export one self-contained plaintext executive HTML dashboard
  through person-directed download or share. Aggregate output is the default;
  task detail requires an explicit opt-in.

### Delivered onboarding, dashboard, and NFR programme

Welcome now offers `Continue with Google`, `Continue offline`, and
`Restore from this device` without automatic restore discovery. Offline is a
complete primary path, not a degraded guest mode; Google never becomes
identity or authority for local records.

Insights generates one polished offline HTML page from its existing selection
and metric engine. The report presents executive summary, portfolio health,
milestone risk, overdue ageing, completion, estimate/actual, time allocation,
blockers, and data caveats. It is downloadable and shareable without Google,
carries a plaintext disclosure, loads no remote asset, and excludes
descriptions, notes, attachment names, credentials, and provider metadata even
when task detail is enabled.

Release-like performance, size, archive/import bounds, dependency checksums,
SBOMs, CodeQL, and dependency review are now measurable gates. The programme
adds no telemetry, server, hosted report, reporting database, or parallel
analytics path. The implemented boundary and remaining external release gates
are recorded in
`docs/qualification/onboarding-dashboard-nfr-acceptance.md`.
That programme is paused before release while five validated security
findings in the same record are remediated.

## Brand Personality

Focused, candid, and quietly capable. The interface should feel calm under a
large workload, direct about risk, and satisfying during daily maintenance
without becoming playful or decorative.

## Anti-references

Do not resemble an iOS task app transplanted onto Android, a floating bottom
pill, a collaboration-first project manager, or a card-grid SaaS dashboard.
Avoid collaborator avatars, status side-stripes, decorative gradients,
glassmorphism, cream-coloured AI-product styling, and colour-only status
communication.

## Design Principles

1. Put the next useful action ahead of workspace administration.
2. Keep privacy, backup state, active-device ownership, and recovery legible
   rather than invisible.
3. Restructure for the current window instead of stretching a phone layout.
4. Preserve context across interruption: selection, drafts, scroll, filters,
   navigation, and timer state should survive.
5. Make every irreversible or risky state explicit, while ordinary edits remain
   immediate and undoable.
6. Never hide double-counted work: preserve overlapping time records, explain
   the conflict and let the user correct either entry explicitly.

## Accessibility & Inclusion

Meet an enhanced WCAG 2.2 AA-equivalent target with 48 dp touch targets,
TalkBack names and custom actions, logical focus order, keyboard and switch
access, visible focus, high-contrast validation, non-colour status cues, reduced
motion, and layouts that remain usable at 200% font scaling. UK English ships
first, with UK spelling, day–month dates and 24-hour time; strings, dates,
plurals, sorting, and layouts must remain ready for localisation and RTL.
