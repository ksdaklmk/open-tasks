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

Stage 7's implemented visible ergonomics changes give Tasks and project workbenches
fixed-direction sort and bounded grouping, saved filters gain due-bucket,
priority, semantic-status, and sort choices, and search uses one relevance
ranking. Quick Add offers confirm-only project, tag, priority, recurrence, and
estimate grammar; task detail and board cards can duplicate a task; Insights
uses accessible dot runs and a completion-per-day trend; the app is light-only.
The responsive Today widget keeps counts and Quick Add in its compact 2×1
layout and adds focus-task actions at expanded height. Room remains v9 and the
authenticated backup object format remains v1.

The deliberate ceilings are equally visible. There is no sort-direction toggle
or manual board rank; grouping is limited to due bucket, project, and priority;
Quick Add does not express multi-word tags or weekday lists and never applies a
token without confirmation. Arrangement preferences are device-local, deleted
project entries may remain as bounded enum-and-ID residue, and Schedule and Home
keep their existing comparators. Duplication omits reminders, recurrence,
activity history, time entries, notes, and attachments. Stage 7 adds no external
surface, permission, network path, cloud path, schema, or backup family.

Stage 8 is an in-development, unqualified checkpoint. It has landed the
Month schedule surface, exact single-task schedule mutation, start/due editor
controls, a complete non-drag rescheduling fallback, and long-press pointer
drag rescheduling layered over that fallback; its Timeline remains
projection-only. Timeline UI/state, the daily digest, device coverage, and
release qualification remain pending. Room stays v9 and backup v1, with no
schema, permission, manifest, Drive-scope, or route change.

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
